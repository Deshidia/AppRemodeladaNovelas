package com.lagradost.quicknovel.ui

import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.LIBRARY_NOVEL_LISTS
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.RESULT_BOOKMARK_STATE

const val LIBRARY_LISTS_KEY = "library_lists_data"

/**
 * A single library shelf/status, eg "Reading", "Plan to read" or any custom
 * list created by the user in Settings > Library lists.
 *
 * [id] is the value stored per novel in [RESULT_BOOKMARK_STATE] (same role the
 * old ReadType.prefValue used to have). It must stay stable once assigned, since
 * it is what links a saved novel to the list it belongs to.
 */
data class LibraryList(
    val id: Int,
    var name: String
)

// Wrapper so Jackson keeps full generic type info when (de)serializing the list.
data class LibraryListData(val lists: List<LibraryList> = emptyList())

// Ids of the library lists a single novel belongs to (same wrapper trick as above).
data class LibraryMembership(val ids: List<Int> = emptyList())

/**
 * Handles the user-editable set of library lists. This replaces the previously
 * hardcoded ReadType tabs (Reading, Plan to read, Dropped, Completed, On-Hold).
 */
object LibraryListManager {

    // Ids 1-5 are kept identical to the old ReadType.prefValue values so that
    // novels bookmarked before this feature existed keep their list.
    private fun defaultLists(): List<LibraryList> {
        val ctx = context
        return listOf(
            LibraryList(
                ReadType.READING.prefValue,
                ctx?.getString(R.string.type_reading) ?: "Reading"
            ),
            LibraryList(
                ReadType.ON_HOLD.prefValue,
                ctx?.getString(R.string.type_on_hold) ?: "On-Hold"
            ),
            LibraryList(
                ReadType.PLAN_TO_READ.prefValue,
                ctx?.getString(R.string.type_plan_to_read) ?: "Plan to Read"
            ),
            LibraryList(
                ReadType.COMPLETED.prefValue,
                ctx?.getString(R.string.type_completed) ?: "Completed"
            ),
            LibraryList(
                ReadType.DROPPED.prefValue,
                ctx?.getString(R.string.type_dropped) ?: "Dropped"
            ),
        )
    }

    /** Returns the current lists, in the order they should be displayed as tabs. */
    fun getLists(): List<LibraryList> {
        val saved = getKey<LibraryListData>(LIBRARY_LISTS_KEY)?.lists
        if (saved != null && saved.isNotEmpty()) return saved
        val defaults = defaultLists()
        saveLists(defaults)
        return defaults
    }

    fun saveLists(lists: List<LibraryList>) {
        setKey(LIBRARY_LISTS_KEY, LibraryListData(lists))
    }

    /** Creates a new list with [name] and returns it. */
    fun addList(name: String): LibraryList {
        val lists = getLists().toMutableList()
        val nextId = (lists.maxOfOrNull { it.id } ?: 0) + 1
        val newList = LibraryList(nextId, name.trim())
        lists.add(newList)
        saveLists(lists)
        return newList
    }

    fun renameList(id: Int, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        saveLists(getLists().map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    /**
     * Removes the list with [id]. Every novel currently assigned to it is
     * migrated to [moveNovelsToId] (pass null / 0 to just take them out of this
     * list; a novel that was in no other list is then removed from the library
     * but keeps its downloaded files intact).
     */
    fun deleteList(id: Int, moveNovelsToId: Int? = null) {
        // Hay que averiguar qué novelas la usaban antes de borrarla.
        val affected = getNovelsInList(id)
        saveLists(getLists().filter { it.id != id })

        val target = moveNovelsToId?.takeIf { it != 0 }
        for (novelId in affected) {
            // getNovelLists ya ignora la lista recién borrada
            val remaining = getNovelLists(novelId)
            setNovelLists(novelId, if (target != null) remaining + target else remaining)
        }
    }

    /**
     * Ids of the lists [novelId] belongs to, in the order the lists are displayed.
     * Novels saved before multi-list support only have the old single state, which is
     * used as a fallback.
     */
    fun getNovelLists(novelId: Int): Set<Int> {
        val key = novelId.toString()
        val saved = getKey<LibraryMembership>(LIBRARY_NOVEL_LISTS, key)?.ids
            ?: listOfNotNull(getKey<Int>(RESULT_BOOKMARK_STATE, key))
        return getLists().map { it.id }.filter { it in saved }.toSet()
    }

    /**
     * Sets the lists [novelId] belongs to (empty = not in the library). The old single
     * [RESULT_BOOKMARK_STATE] is kept in sync with the first list: it is still what
     * identifies which novels are in the library, and it keeps backups readable.
     * This does not touch the cached novel data ([RESULT_BOOKMARK]).
     */
    fun setNovelLists(novelId: Int, ids: Collection<Int>) {
        val key = novelId.toString()
        val ordered = getLists().map { it.id }.filter { it in ids }
        if (ordered.isEmpty()) {
            removeKey(LIBRARY_NOVEL_LISTS, key)
            removeKey(RESULT_BOOKMARK_STATE, key)
        } else {
            setKey(LIBRARY_NOVEL_LISTS, key, LibraryMembership(ordered))
            setKey(RESULT_BOOKMARK_STATE, key, ordered.first())
        }
    }

    /** Takes the novel out of the library completely (all lists + cached data). */
    fun removeNovel(novelId: Int) {
        setNovelLists(novelId, emptySet())
        removeKey(RESULT_BOOKMARK, novelId.toString())
    }

    /** Ids of every novel that is in the list [listId]. */
    fun getNovelsInList(listId: Int): List<Int> {
        val keys = getKeys(RESULT_BOOKMARK_STATE) ?: return emptyList()
        return keys.mapNotNull { it.substringAfterLast('/').toIntOrNull() }
            .filter { listId in getNovelLists(it) }
    }

    /**
     * Text for a bookmark button: null when not in any list, the list name when in one,
     * and "name +N" when in several.
     */
    fun describe(ids: Set<Int>): String? {
        val names = getLists().filter { it.id in ids }.map { it.name }
        return when {
            names.isEmpty() -> null
            names.size == 1 -> names[0]
            else -> "${names[0]} +${names.size - 1}"
        }
    }

    fun reorderLists(lists: List<LibraryList>) {
        saveLists(lists)
    }
}
