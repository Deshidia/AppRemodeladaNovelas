package com.lagradost.quicknovel.ui.result

import android.content.DialogInterface
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.APIRepository
import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.BookDownloader2.downloadProgress
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.BookDownloader2Helper.generateId
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.DOWNLOAD_EPUB_LAST_ACCESS
import com.lagradost.quicknovel.DownloadActionType
import com.lagradost.quicknovel.DownloadProgressState
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_CHAPTER
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_READ_AT
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_SCROLL_CHAR
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.PreferenceDelegate
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.ui.LibraryListManager
import com.lagradost.quicknovel.RESULT_CHAPTER_FILTER_BOOKMARKED
import com.lagradost.quicknovel.RESULT_CHAPTER_FILTER_DOWNLOADED
import com.lagradost.quicknovel.RESULT_CHAPTER_FILTER_READ
import com.lagradost.quicknovel.RESULT_CHAPTER_FILTER_UNREAD
import com.lagradost.quicknovel.RESULT_CHAPTER_SORT
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.launchSafe
import com.lagradost.quicknovel.ui.download.CHAPTER_SORT
import com.lagradost.quicknovel.ui.download.DownloadFragment
import com.lagradost.quicknovel.ui.download.LAST_ACCES_SORT
import com.lagradost.quicknovel.ui.download.LAST_UPDATED_SORT
import com.lagradost.quicknovel.ui.download.REVERSE_CHAPTER_SORT
import com.lagradost.quicknovel.ui.download.REVERSE_LAST_ACCES_SORT
import com.lagradost.quicknovel.ui.download.REVERSE_LAST_UPDATED_SORT
import com.lagradost.quicknovel.ui.download.SortingMethod
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.DownloadTranslation
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.util.TextReplacement
import com.lagradost.quicknovel.util.TextReplacements
import com.lagradost.quicknovel.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


class ResultViewModel : ViewModel() {
    companion object {
        val chapterSortingMethods = arrayOf(
            SortingMethod(R.string.chapter_sort, CHAPTER_SORT, REVERSE_CHAPTER_SORT),
            SortingMethod(R.string.recently_sort, LAST_ACCES_SORT, REVERSE_LAST_ACCES_SORT),
        )
        var sortChapterBy by PreferenceDelegate(RESULT_CHAPTER_SORT, CHAPTER_SORT, Int::class)

        var filterChapterByDownloads by PreferenceDelegate(
            RESULT_CHAPTER_FILTER_DOWNLOADED,
            false,
            Boolean::class
        )
        var filterChapterByBookmarked by PreferenceDelegate(
            RESULT_CHAPTER_FILTER_BOOKMARKED,
            false,
            Boolean::class
        )
        var filterChapterByRead by PreferenceDelegate(
            RESULT_CHAPTER_FILTER_READ,
            true,
            Boolean::class
        )
        var filterChapterByUnread by PreferenceDelegate(
            RESULT_CHAPTER_FILTER_UNREAD,
            true,
            Boolean::class
        )

    }

    var chapterQuery: String? = null

    fun reorderChapters() {
        when (val response = this.loadResponse.value) {
            is Resource.Success -> {
                reorderChapters(response.value)
            }

            else -> {}
        }
    }

    fun reorderChapters(response: LoadResponse) {
        when (response) {
            is StreamResponse -> {
                chapters.postValue(orderChapters(response.data))
            }

            else -> chapters.postValue(null)
        }
    }


    private fun orderChapters(list: List<ChapterData>): List<ChapterData> {
        val filterRead = filterChapterByRead
        val filterUnread = filterChapterByUnread
        // val filterBookmarked = filterChapterByBookmarked
        val filterDownloaded = filterChapterByDownloads
        val sort = sortChapterBy
        val state = downloadState.value
        val query = chapterQuery

        return list.filter { chapter ->
            val read = hasReadChapter(chapter)

            ((filterUnread && !read) || (filterRead && read) ||
                    (filterDownloaded && (state != null && state.progress > (chapterIndex(chapter)
                        ?: Int.MAX_VALUE)))) && (query == null || chapter.name.contains(query, ignoreCase = true))
        }.sortedBy { chapter ->
            return@sortedBy when (sort) {
                CHAPTER_SORT -> {
                    chapterIndex(chapter)?.toLong()
                }

                REVERSE_CHAPTER_SORT -> {
                    chapterIndex(chapter)?.toLong()?.unaryMinus()
                }

                LAST_ACCES_SORT -> {
                    getChapterReadTime(chapter) ?: Long.MAX_VALUE
                }

                REVERSE_LAST_ACCES_SORT -> {
                    -(getChapterReadTime(chapter) ?: Long.MAX_VALUE)
                }

                else -> null
            }
        }
    }

    fun clear() {
        loadResponse.postValue(null)
        chapters.postValue(null)
    }

    fun hasReadChapter(chapter: ChapterData): Boolean {
        return getChapterReadTime(chapter) != null
    }

    fun getChapterReadTime(chapter: ChapterData): Long? {
        val streamResponse =
            (load as? StreamResponse) ?: return null
        val index = chapterIndex(chapter) ?: return null
        return getKey<Long>(
            EPUB_CURRENT_POSITION_READ_AT,
            "${streamResponse.name}/$index"
        )
    }

    fun setReadChapter(chapter: ChapterData, value: Boolean): Boolean {
        val streamResponse =
            (load as? StreamResponse) ?: return false
        val index = chapterIndex(chapter) ?: return false

        if (value) {
            setKey(
                EPUB_CURRENT_POSITION_READ_AT,
                "${streamResponse.name}/$index",
                System.currentTimeMillis()
            )
        } else {
            removeKey(
                EPUB_CURRENT_POSITION_READ_AT,
                "${streamResponse.name}/$index",
            )
        }

        return true
    }

    var repo: APIRepository? = null

    var isGetLoaded = false

    var id: MutableLiveData<Int> = MutableLiveData<Int>(-1)
    /** The ids of the library lists this novel currently belongs to
     * (empty if it isn't in any list). */
    var readStates: MutableLiveData<Set<Int>> = MutableLiveData<Set<Int>>(emptySet())

    var apiName : String = ""

    /**
     * Si esta ficha (o vista previa) se abrió desde la búsqueda por título original de otra
     * novela, esa novela. Al guardar esta en marcadores se pregunta entonces si se duplica o si
     * se migra desde aquella. Null en cualquier otro caso.
     */
    var migrationSource: MigrationSource? = null

    /*This is to detect whether it actually returned to
     the fragment after reading a chapter, and thus update the list of chapters.*/
    var isResume = false

    val currentTabIndex: MutableLiveData<Int> by lazy {
        MutableLiveData<Int>(0)
    }

    val currentTabPosition: MutableLiveData<Int> by lazy {
        MutableLiveData<Int>(0)
    }

    private val loadMutex = Mutex()
    private lateinit var load: LoadResponse
    private var loadId: Int = 0
    private var loadUrl: String = ""
    private var hasLoaded: Boolean = false

    val loadResponse: MutableLiveData<Resource<LoadResponse>?> =
        MutableLiveData<Resource<LoadResponse>?>()

    val chapters: MutableLiveData<List<ChapterData>?> =
        MutableLiveData<List<ChapterData>?>()

    /** Se incrementa cuando termina de traducirse en segundo plano el nombre de los capítulos. */
    val chapterNamesChanged: MutableLiveData<Int> = MutableLiveData<Int>(0)

    private fun onChapterNamesTranslated() {
        // los nombres cambian in place: se vuelve a filtrar (la búsqueda usa el nombre) y se avisa
        // al fragmento para que repinte los textos visibles
        if (currentTabIndex.value == 3) {
            reorderChapters()
        }
        chapterNamesChanged.value = (chapterNamesChanged.value ?: 0) + 1
    }

    /** Reglas de la pestaña "Sustituir" de esta novela. */
    val replacements: MutableLiveData<List<TextReplacement>> =
        MutableLiveData<List<TextReplacement>>(emptyList())
    private var replacementsNovel: String? = null

    fun loadReplacements(novelName: String) {
        if (replacementsNovel == novelName) return
        replacementsNovel = novelName
        replacements.postValue(TextReplacements.get(novelName))
    }

    /** Añade una regla, o reemplaza la de [editIndex] si se está editando. */
    fun saveReplacement(rule: TextReplacement, editIndex: Int?) {
        val novel = replacementsNovel ?: return
        val list = (replacements.value ?: emptyList()).toMutableList()
        if (editIndex != null && editIndex in list.indices) {
            list[editIndex] = rule
        } else {
            list.add(rule)
        }
        TextReplacements.set(novel, list)
        replacements.postValue(list)
    }

    fun deleteReplacement(index: Int) {
        val novel = replacementsNovel ?: return
        val list = (replacements.value ?: emptyList()).toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        TextReplacements.set(novel, list)
        replacements.postValue(list)
    }

    val reviews: MutableLiveData<Resource<ArrayList<UserReview>>> by lazy {
        MutableLiveData<Resource<ArrayList<UserReview>>>()
    }
    private var currentReviews: ArrayList<UserReview> = arrayListOf()

    private val reviewPage: MutableLiveData<Int> by lazy {
        MutableLiveData<Int>(0)
    }

    private val loadMoreReviewsMutex = Mutex()
    private fun loadMoreReviews(url: String) {
        viewModelScope.launch {
            if (loadMoreReviewsMutex.isLocked) return@launch
            val api = repo ?: return@launch
            loadMoreReviewsMutex.withLock {
                val loadPage = (reviewPage.value ?: 0) + 1
                if (loadPage == 1) {
                    reviews.postValue(Resource.Loading())
                }
                when (val data = api.loadReviews(
                    url,
                    loadPage,
                    showSpoilers = false,
                    data = if (::load.isInitialized) load.reviewData else null
                )) {
                    is Resource.Success -> {
                        val moreReviews = data.value
                        currentReviews.addAll(moreReviews)

                        reviews.postValue(Resource.Success(currentReviews))
                        reviewPage.postValue(loadPage)
                    }

                    else -> {}
                }
            }
        }
    }

    fun openInBrowser() = viewModelScope.launchSafe {
        loadMutex.withLock {
            if (loadUrl.isBlank()) return@launchSafe
            val i = Intent(Intent.ACTION_VIEW)
            i.data = loadUrl.toUri()
            activity?.startActivity(i)
        }
    }

    fun switchTab(index: Int?, position: Int?) {
        val newPos = index ?: return
        currentTabPosition.postValue(position ?: return)
        currentTabIndex.postValue(newPos)
        if (newPos == 1 && currentReviews.isEmpty()) {
            loadMoreReviews(verify = false)
        }
        if (newPos == 3) {
            reorderChapters()
        } else {
            // clears the chapters to avoid flicker
            chapters.postValue(null)
        }
    }

    fun readEpub() = viewModelScope.launchSafe {
        loadMutex.withLock {
            if (!hasLoaded) return@launchSafe
            addToHistory()
            BookDownloader2.readEpub(
                loadId,
                downloadState.value?.progress?.toInt() ?: return@launchSafe,
                load.author,
                load.name,
                apiName,
                load.synopsis
            )
        }
    }

    // Por identidad: el nombre de un capítulo (parte de su hashCode) cambia in place cuando termina
    // la traducción en segundo plano, y con un HashMap normal se perderían las entradas.
    private var cachedChapters: java.util.IdentityHashMap<ChapterData, Int> =
        java.util.IdentityHashMap()

    private fun chapterIndex(chapter: ChapterData): Int? {
        return cachedChapters[chapter]
    }

    private fun reCacheChapters() {
        val streamResponse = (load as? StreamResponse)
        if (streamResponse == null) {
            cachedChapters = java.util.IdentityHashMap()
            return
        }
        val out = java.util.IdentityHashMap<ChapterData, Int>()
        streamResponse.data.mapIndexed { index, chapterData ->
            out[chapterData] = index
        }
        cachedChapters = out
    }

    fun streamRead(chapter: ChapterData? = null) = ioSafe {
        loadMutex.withLock {
            if (!hasLoaded) return@ioSafe
            addToHistory()

            chapter?.let {
                // TODO BETTER STORE
                val streamResponse =
                    ((loadResponse.value as? Resource.Success)?.value as? StreamResponse)
                        ?: return@let
                val index = chapterIndex(chapter)
                if (index != null && index >= 0) {
                    setReadChapter(chapter, true)
                    setKey(EPUB_CURRENT_POSITION, streamResponse.name, index)
                    setKey(
                        EPUB_CURRENT_POSITION_CHAPTER,
                        streamResponse.name,
                        streamResponse.data[index].name
                    )
                    setKey(
                        EPUB_CURRENT_POSITION_SCROLL_CHAR, streamResponse.name, 0,
                    )
                }
            }

            BookDownloader2.stream(load, apiName)
        }
    }

    /** paused => resume,
     *  downloading => pause,
     *  done / pending => nothing,
     *  else => download
     * */
    fun downloadOrPause() = viewModelScope.launchSafe {
        loadMutex.withLock {
            if (!hasLoaded) return@launchSafe

            BookDownloader2.downloadInfoMutex.withLock {
                downloadProgress[loadId]?.let { downloadState ->
                    when (downloadState.state) {
                        DownloadState.IsPaused -> BookDownloader2.addPendingAction(
                            loadId,
                            DownloadActionType.Resume
                        )

                        DownloadState.IsDownloading -> BookDownloader2.addPendingAction(
                            loadId,
                            DownloadActionType.Pause
                        )

                        DownloadState.IsDone, DownloadState.IsPending -> {

                        }

                        else -> BookDownloader2.download(load, context ?: return@launchSafe)
                    }
                } ?: run {
                    BookDownloader2.download(load, context ?: return@launchSafe)
                }
            }
        }
    }

    fun pause() = viewModelScope.launchSafe {
        loadMutex.withLock {
            if (!hasLoaded) return@launchSafe

            BookDownloader2.downloadInfoMutex.withLock {
                downloadProgress[loadId]?.let { downloadState ->
                    when (downloadState.state) {
                        DownloadState.IsDownloading -> BookDownloader2.addPendingAction(
                            loadId,
                            DownloadActionType.Pause
                        )

                        else -> {

                        }
                    }
                }
            }
        }
    }

    fun stop() = viewModelScope.launchSafe {
        loadMutex.withLock {
            if (!hasLoaded) return@launchSafe

            BookDownloader2.downloadInfoMutex.withLock {
                downloadProgress[loadId]?.let { downloadState ->
                    when (downloadState.state) {
                        DownloadState.Nothing, DownloadState.IsDone, DownloadState.IsStopped, DownloadState.IsFailed -> {

                        }

                        else -> {
                            BookDownloader2.addPendingAction(
                                loadId,
                                DownloadActionType.Stop
                            )
                        }
                    }
                }
            }
        }
    }

    fun downloadFrom(start: Int?, translation: DownloadTranslation? = null) = viewModelScope.launchSafe {
        loadMutex.withLock {
            if (!hasLoaded) return@launchSafe
            val api = repo ?: return@launchSafe
            BookDownloader2.downloadInfoMutex.withLock {
                BookDownloader2.changeDownloadStart(load, api, start)
                downloadProgress[loadId]?.let { downloadState ->
                    when (downloadState.state) {
                        DownloadState.IsPaused -> BookDownloader2.addPendingAction(
                            loadId,
                            DownloadActionType.Resume
                        )

                        DownloadState.IsPending -> {

                        }

                        // DownloadState.IsDone
                        else -> BookDownloader2.download(
                            load,
                            context ?: return@launchSafe,
                            translation
                        )
                    }
                } ?: run {
                    BookDownloader2.download(load, context ?: return@launchSafe, translation)
                }
            }
        }
    }

    fun download() = viewModelScope.launchSafe {
        loadMutex.withLock {
            if (!hasLoaded) return@launchSafe
            BookDownloader2.downloadInfoMutex.withLock {
                downloadProgress[loadId]?.let { downloadState ->
                    when (downloadState.state) {
                        DownloadState.IsPaused -> BookDownloader2.addPendingAction(
                            loadId,
                            DownloadActionType.Resume
                        )

                        DownloadState.IsDone, DownloadState.IsPending -> {

                        }

                        else -> BookDownloader2.download(load, context ?: return@launchSafe)
                    }
                } ?: run {
                    BookDownloader2.download(load, context ?: return@launchSafe)
                }
            }
        }
    }


    private fun addToHistory() = viewModelScope.launchSafe {
        // we wont add it to history from cache
        if (!isGetLoaded) return@launchSafe
        loadMutex.withLock {
            if (!hasLoaded) return@launchSafe
            setKey(
                HISTORY_FOLDER, loadId.toString(), ResultCached(
                    loadUrl,
                    load.name,
                    apiName,
                    loadId,
                    load.author,
                    load.posterUrl,
                    load.tags,
                    load.rating,
                    (load as? StreamResponse)?.data?.size ?: 1,
                    System.currentTimeMillis(),
                    synopsis = load.synopsis
                )
            )
        }
    }

// requireContext().setKey(DOWNLOAD_TOTAL, localId.toString(), res.data .size)
// loadReviews()

    fun isInReviews(): Boolean {
        return currentTabIndex.value == 1
    }

    fun deleteAlert() = viewModelScope.launch {
        loadMutex.withLock {
            if (!hasLoaded) return@launch
            val dialogClickListener = DialogInterface.OnClickListener { _, which ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> {
                        delete()
                    }

                    DialogInterface.BUTTON_NEGATIVE -> {
                    }
                }
            }
            val act = activity ?: return@launch
            val builder: AlertDialog.Builder = AlertDialog.Builder(act)
            builder.setMessage(act.getString(R.string.permanently_delete_format).format(load.name))
                .setTitle(R.string.delete)
                .setPositiveButton(R.string.delete, dialogClickListener)
                .setNegativeButton(R.string.cancel, dialogClickListener)
                .show()
        }
    }

    fun delete() = viewModelScope.launch {
        loadMutex.withLock {
            if (!hasLoaded) return@launch
            BookDownloader2.deleteNovel(load.author, load.name, apiName)
        }
    }

    private fun updateBookmarkData() {
        // dont update data if preview because that data is from cache
        if (!isGetLoaded && getKey<ResultCached>(RESULT_BOOKMARK, loadId.toString()) != null) {
            return
        }
        val totalChapters = (load as? StreamResponse)?.data?.size ?: 1
        setKey(
            RESULT_BOOKMARK, loadId.toString(), ResultCached(
                loadUrl,
                load.name,
                apiName,
                loadId,
                load.author,
                load.posterUrl,
                load.tags,
                load.rating,
                totalChapters,
                System.currentTimeMillis(),
                synopsis = load.synopsis
            )
        )
    }

    fun bookmark(states: Set<Int>) = viewModelScope.launch {
        loadMutex.withLock {
            if (!hasLoaded) return@launch
            LibraryListManager.setNovelLists(loadId, states)
            updateBookmarkData()
        }

        readStates.postValue(LibraryListManager.getNovelLists(loadId))
    }

    /** Esta novela como origen de una búsqueda por título original; null si aún no ha cargado. */
    fun currentMigrationSource(): MigrationSource? {
        if (!hasLoaded) return null
        return MigrationSource(loadId, load.name, apiName)
    }

    /**
     * Vuelve a leer en qué listas está la novela. Otra pantalla pudo cambiarlo mientras esta
     * ficha seguía abierta debajo (p. ej. al migrar, la antigua sale de la biblioteca).
     */
    fun refreshReadStates() {
        if (!hasLoaded) return
        readStates.postValue(LibraryListManager.getNovelLists(loadId))
    }

    /**
     * Lo que debe llamar el botón de marcadores con las listas elegidas. Guarda sin más salvo que
     * la ficha venga de la búsqueda por título original de otra novela ([migrationSource]) y esta
     * aún no esté en ninguna lista: entonces pregunta si se añade como duplicado o si se migra.
     */
    fun bookmarkOrAskMigration(states: Set<Int>) = viewModelScope.launch {
        val source = migrationSource
        val newName: String? = loadMutex.withLock {
            if (!hasLoaded || source == null) {
                null
            } else when {
                // quitar de la biblioteca, o la misma novela: no hay nada que decidir
                states.isEmpty() || source.id == loadId -> null
                // ya estaba en alguna lista: solo se están cambiando las listas
                LibraryListManager.getNovelLists(loadId).isNotEmpty() -> null
                else -> load.name
            }
        }

        val act = activity
        if (source == null || newName == null || act == null) {
            bookmark(states)
            return@launch
        }

        NovelMigration.showChoiceDialog(
            act,
            source,
            newName,
            onDuplicate = { bookmark(states) },
            onMigrate = { migrate(source, states) }
        )
    }

    /**
     * Migración completa: esta novela entra en la biblioteca con las listas elegidas, hereda el
     * progreso de capítulos de [source] y [source] sale de la biblioteca. Se hace en este orden
     * para que, si algo fallara a medias, se conserven las dos en vez de perder ambas.
     */
    private fun migrate(source: MigrationSource, states: Set<Int>) = viewModelScope.launch {
        val newName: String = loadMutex.withLock {
            if (!hasLoaded) return@launch
            LibraryListManager.setNovelLists(loadId, states)
            updateBookmarkData()
            load.name
        }

        val lastRead = withContext(Dispatchers.IO) {
            val read = NovelMigration.lastChapterRead(source.name)
            NovelMigration.copyProgress(source.name, newName)
            NovelMigration.removeFromLibrary(source)
            read
        }

        readStates.postValue(LibraryListManager.getNovelLists(loadId))

        val ctx = context ?: return@launch
        showToast(
            if (lastRead > 0) ctx.getString(R.string.migrate_done_progress, newName, lastRead)
            else ctx.getString(R.string.migrate_done, newName)
        )
    }

    fun share() = viewModelScope.launch {
        loadMutex.withLock {
            if (!hasLoaded) return@launch

            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_SUBJECT, load.name)
            i.putExtra(Intent.EXTRA_TEXT, loadUrl)
            activity?.startActivity(Intent.createChooser(i, load.name))
        }
    }

    fun loadMoreReviews(verify: Boolean = true) = viewModelScope.launch {
        loadMutex.withLock {
            if (!hasLoaded) return@launch
            if (verify && currentTabIndex.value == 0) return@launch
            loadMoreReviews(loadUrl)
        }
    }

    init {
        // BookDownloader2.downloadDataChanged += ::progressDataChanged
        BookDownloader2.downloadProgressChanged += ::progressChanged
        BookDownloader2.downloadRemoved += ::downloadRemoved
    }

    override fun onCleared() {
        super.onCleared()
        BookDownloader2.downloadProgressChanged -= ::progressChanged
        //BookDownloader2.downloadDataChanged -= ::progressDataChanged
        BookDownloader2.downloadRemoved -= ::downloadRemoved
    }

    val downloadState: MutableLiveData<DownloadProgressState> by lazy {
        MutableLiveData<DownloadProgressState>(null)
    }
    private var downloadStateValue: DownloadProgressState? = null

    fun setDownloadState(state: DownloadProgressState) {
        downloadStateValue = state
        downloadState.postValue(state)
    }

    private fun progressChanged(data: Pair<Int, DownloadProgressState>) =
        viewModelScope.launch {
            val (id, state) = data
            loadMutex.withLock {
                if (!hasLoaded || id != loadId) return@launch
                setDownloadState(state)
            }
        }

    /*fun progressDataChanged(data: Pair<Int, DownloadFragment.DownloadData>) =
        viewModelScope.launch {
            val (id, downloadData) = data
            loadMutex.withLock {
                if (!hasLoaded || id != loadId) return@launch

            }
        }*/

    private fun downloadRemoved(id: Int) = viewModelScope.launch {
        loadMutex.withLock {
            if (!hasLoaded || id != loadId) return@launch
            insertZeroData()
        }
    }

    private fun insertZeroData() = viewModelScope.launch {
        loadMutex.withLock {
            if (!hasLoaded) return@launch

            BookDownloader2.downloadInfoMutex.withLock {
                val current = downloadProgress[loadId]
                if (current != null) {
                    setDownloadState(current)
                } else {
                    BookDownloader2Helper.downloadInfo(
                        context,
                        load.author,
                        load.name,
                        load.apiName
                    )?.let { info ->
                        val new = DownloadProgressState(
                            state = DownloadState.Nothing,
                            progress = info.progress,
                            total = info.total,
                            downloaded = info.downloaded,
                            lastUpdatedMs = System.currentTimeMillis(),
                            etaMs = null
                        )
                        downloadProgress[loadId] = new
                        setDownloadState(new)
                    } ?: run {
                        val new = DownloadProgressState(
                            state = DownloadState.Nothing,
                            progress = 0,
                            total = (load as? StreamResponse)?.data?.size?.toLong() ?: 1,
                            downloaded = 0,
                            lastUpdatedMs = System.currentTimeMillis(),
                            etaMs = null
                        )
                        setDownloadState(new)
                    }
                }
            }
        }
    }

    fun initState(card: ResultCached) = viewModelScope.launch {
        isGetLoaded = false
        loadMutex.withLock {
            this@ResultViewModel.apiName = card.apiName
            repo = Apis.getApiFromNameOrNull(card.apiName)
            loadUrl = card.source

            val data = StreamResponse(
                url = card.source,
                name = card.name,
                data = listOf(),
                author = card.author,
                posterUrl = card.poster,
                rating = card.rating,
                synopsis = card.synopsis,
                tags = card.tags,
                apiName = card.apiName
            )

            load = data
            loadResponse.postValue(Resource.Success(data))
            setState(card.id)
        }
    }

    private fun setState(tid: Int) {
        loadId = tid

        readStates.postValue(LibraryListManager.getNovelLists(tid))

        setKey(
            DOWNLOAD_EPUB_LAST_ACCESS, tid.toString(), System.currentTimeMillis()
        )
        reCacheChapters()
        updateBookmarkData()

        hasLoaded = true

        // insert a download progress if not found
        insertZeroData()
    }

    fun initState(card: DownloadFragment.DownloadDataLoaded) = viewModelScope.launch {
        isGetLoaded = false
        loadResponse.postValue(Resource.Loading(card.source))

        loadMutex.withLock {
            this@ResultViewModel.apiName = card.apiName
            repo = Apis.getApiFromName(card.apiName)
            loadUrl = card.source

            val data = StreamResponse(
                url = card.source,
                name = card.name,
                data = listOf(),
                author = card.author,
                posterUrl = card.posterUrl,
                rating = card.rating,
                synopsis = card.synopsis,
                tags = card.tags,
                apiName = card.apiName
            )
            load = data
            loadResponse.postValue(Resource.Success(data))
            setState(card.id)
        }
    }

    fun initState(apiName: String, url: String) = viewModelScope.launch {
        isGetLoaded = true
        loadResponse.postValue(Resource.Loading(url))

        loadMutex.withLock {
            this@ResultViewModel.apiName = apiName
            repo = Apis.getApiFromNameOrNull(apiName)
            loadUrl = url
        }

        // La traducción de los nombres de capítulo (miles de textos) sigue en segundo plano: la
        // ficha se muestra en cuanto están el título, la sinopsis y las etiquetas.
        val data = repo?.load(url, awaitChapterTranslation = false)
        loadMutex.withLock {
            when (data) {
                is Resource.Success -> {
                    val res = data.value

                    load = res
                    loadUrl = res.url

                    val tid = generateId(res, apiName)
                    setState(tid)
                }

                else -> {}
            }
            loadResponse.postValue(data)
        }

        // Cuando acaben de traducirse los capítulos, se refresca lo que ya se está mostrando.
        // Si ya venían traducidos (caché) no hay nada que refrescar.
        val translation = ((data as? Resource.Success)?.value as? StreamResponse)
            ?.chapterNamesTranslation
        if (translation != null && !translation.isCompleted) {
            viewModelScope.launch {
                if (translation.await()) onChapterNamesTranslated()
            }
        }
    }
}