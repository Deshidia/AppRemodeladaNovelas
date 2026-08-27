package com.lagradost.quicknovel.ui

import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.R
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
     * migrated to [moveNovelsToId] (pass null / 0 to leave them unclassified,
     * ie removed from the library but keeping their downloaded files intact).
     */
    fun deleteList(id: Int, moveNovelsToId: Int? = null) {
        saveLists(getLists().filter { it.id != id })

        val keys = getKeys(RESULT_BOOKMARK_STATE) ?: return
        for (key in keys) {
            val state = getKey<Int>(key)
            if (state == id) {
                if (moveNovelsToId != null && moveNovelsToId != 0) {
                    setKey(key, moveNovelsToId)
                } else {
                    removeKey(key)
                }
            }
        }
    }

    fun reorderLists(lists: List<LibraryList>) {
        saveLists(lists)
    }
}
