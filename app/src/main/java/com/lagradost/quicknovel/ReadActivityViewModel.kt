package com.lagradost.quicknovel

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Looper
import android.speech.tts.Voice
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.text.getSpans
import androidx.core.text.toSpanned
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKeyClass
import com.lagradost.quicknovel.BookDownloader2Helper.getQuickChapter
import com.lagradost.quicknovel.CommonActivity.TAG
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.TTSHelper.parseTextToSpans
import com.lagradost.quicknovel.TTSHelper.preParseHtml
import com.lagradost.quicknovel.TTSHelper.ttsParseText
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.letInner
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.mvvm.map
import com.lagradost.quicknovel.mvvm.safe
import com.lagradost.quicknovel.mvvm.safeApiCall
import com.lagradost.quicknovel.mvvm.safeAsync
import com.lagradost.quicknovel.mvvm.throwableToResource
import com.lagradost.quicknovel.providers.RedditProvider
import com.lagradost.quicknovel.providers.WebLinkProvider
import com.lagradost.quicknovel.ui.OrientationType
import com.lagradost.quicknovel.ui.ReadingType
import com.lagradost.quicknovel.ui.ScrollIndex
import com.lagradost.quicknovel.ui.ScrollVisibilityIndex
import com.lagradost.quicknovel.ui.UiText
import com.lagradost.quicknovel.ui.toScroll
import com.lagradost.quicknovel.ui.toUiText
import com.lagradost.quicknovel.ui.txt
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.CoilImagesPlugin
import com.lagradost.quicknovel.util.CoilImagesPlugin.CoilStore
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.Coroutines.runOnMainThread
import com.lagradost.quicknovel.util.GoogleTranslateOnline
import com.lagradost.quicknovel.util.DeepLTranslate
import com.lagradost.quicknovel.util.GeminiTranslate
import com.lagradost.quicknovel.util.ReplaceTiming
import com.lagradost.quicknovel.util.TextReplacement
import com.lagradost.quicknovel.util.TextReplacements
import com.lagradost.quicknovel.util.UIHelper
import com.lagradost.safefile.closeQuietly
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageSizeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.util.zip.AndroidZipFile
import org.commonmark.node.Node
import org.jsoup.Jsoup
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

const val DEF_FONT_SIZE: Int = 14
const val DEF_HORIZONTAL_PAD: Int = 20
const val DEF_VERTICAL_PAD: Int = 0

class PreferenceDelegate<T : Any>(
    val key: String, val default: T, private val klass: KClass<T>
) {
    // simple cache to make it not get the key every time it is accessed, however this requires
    // that ONLY this changes the key
    private var cache: T? = null

    operator fun getValue(self: Any?, property: KProperty<*>) =
        cache ?: getKeyClass(key, klass.java).also { newCache -> cache = newCache } ?: default

    operator fun setValue(
        self: Any?,
        property: KProperty<*>,
        t: T?
    ) {
        cache = t
        if (t == null) {
            removeKey(key)
        } else {
            setKeyClass(key, t)
        }
    }
}

enum class GeminiThinkingLevel {
    AUTO, OFF, LOW, MEDIUM, HIGH;

    companion object {
        val list = listOf(
            AUTO to R.string.thinking_level_auto,
            OFF to R.string.thinking_level_off,
            LOW to R.string.thinking_level_low,
            MEDIUM to R.string.thinking_level_medium,
            HIGH to R.string.thinking_level_high
        )

        fun fromName(name: String): GeminiThinkingLevel =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

class PreferenceDelegateLiveView<T : Any>(
    val key: String, val default: T, klass: KClass<T>, private val _liveData: MutableLiveData<T>
) {
    // simple cache to make it not get the key every time it is accessed, however this requires
    // that ONLY this changes the key
    private var cache: T

    init {
        cache = getKeyClass(key, klass.java) ?: default
        _liveData.postValue(cache)
    }

    operator fun getValue(self: Any?, property: KProperty<*>) = cache

    operator fun setValue(
        self: Any?,
        property: KProperty<*>,
        t: T?
    ) {
        cache = t ?: default
        _liveData.postValue(cache)
        if (t == null) {
            removeKey(key)
        } else {
            setKeyClass(key, t)
        }
    }
}

abstract class AbstractBook {
    open fun resolveUrl(url: String): String {
        return url
    }

    abstract val canReload: Boolean

    abstract fun size(): Int
    abstract fun title(): String
    abstract fun getChapterTitle(index: Int): UiText
    abstract fun getLoadingStatus(index: Int): String?

    @Throws
    open fun loadImage(image: String): ByteArray? {
        return null
    }

    fun loadImageBitmap(image: String): Bitmap? {
        try {
            val data = this.loadImage(image) ?: return null
            return BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (t: Throwable) {
            logError(t)
            return null
        }
    }

    @WorkerThread
    @Throws
    abstract suspend fun getChapterData(index: Int, reload: Boolean): String

    abstract fun expand(last: String): Boolean

    @WorkerThread
    @Throws
    protected abstract suspend fun posterBytes(): ByteArray?

    private var poster: Bitmap? = null

    init {
        ioSafe {
            poster = posterBytes()?.let { byteArray ->
                BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            }
        }
    }

    fun poster(): Bitmap? {
        return poster
    }

    abstract fun author(): String?
}

class QuickBook(val data: QuickStreamData) : AbstractBook() {
    override fun resolveUrl(url: String): String {
        return Apis.getApiFromNameNull(data.meta.apiName)?.fixUrl(url) ?: url
    }

    override fun author(): String? {
        return data.meta.author
    }

    override val canReload = true

    override fun size(): Int {
        return data.data.size
    }

    override fun title(): String {
        return data.meta.name
    }

    override fun getChapterTitle(index: Int): UiText {
        return data.data[index].name.toUiText()
    }

    override fun getLoadingStatus(index: Int): String {
        return data.data[index].url
    }

    override suspend fun getChapterData(index: Int, reload: Boolean): String {
        val ctx = context ?: throw ErrorLoadingException("Invalid context")
        return ctx.getQuickChapter(
            data.meta,
            data.data[index],
            index,
            reload
        )?.html ?: throw ErrorLoadingException("Error loading chapter")
    }

    override fun expand(last: String): Boolean {
        // Páginas abiertas desde "Abrir enlace": el siguiente capítulo se detectó al descargar la página
        if (data.meta.apiName == WebLinkProvider.NAME) {
            val next = data.data.lastOrNull()?.url?.let { WebLinkProvider.getNextUrl(it) }
                ?: return false
            // evita bucles (el "siguiente" apunta a un capítulo que ya está en la lista)
            if (data.data.any { it.url == next }) return false
            val name = WebLinkProvider.chapterNameFromUrl(next) ?: "Next"
            data.data.add(ChapterData(name, next, null, null))
            return true
        }

        try {
            val elements =
                Jsoup.parse(last).allElements.filterNotNull()

            for (element in elements) {
                val href = element.attr("href") ?: continue

                val text =
                    element.ownText().replace(Regex("[\\[\\]().,|{}<>]"), "").trim()
                if (text.equals("next", true) || text.equals(
                        "next chapter",
                        true
                    ) || text.equals("next part", true)
                ) {
                    val name = RedditProvider.getName(href) ?: "Next"
                    data.data.add(ChapterData(name, href, null, null))
                    return true
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        return false
    }

    override suspend fun posterBytes(): ByteArray? {
        val poster = data.poster
        if (poster != null) {
            try {
                return MainActivity.app.get(poster).okhttpResponse.body.bytes()
            } catch (t: Throwable) {
                logError(t)
            }
        }
        return null
    }
}

class RegularBook(val data: EpubBook) : AbstractBook() {
    private val allTocReferences: List<TOCReference>
    init {
        val flatTOC = mutableListOf<TOCReference>()
        fun flatten(refs: List<TOCReference>) {
            refs.forEach { ref ->
                //this variable is to corrupted epubs
                val isValid = data.spine.getResourceIndex(ref.resource) != -1
                if(isValid){
                    flatTOC.add(ref)
                    if (ref.children != null && ref.children.isNotEmpty()) {
                        flatten(ref.children)
                    }
                }
            }
        }
        flatten(data.tableOfContents.tocReferences)

        //this is to corrupted epubs
        allTocReferences = if (flatTOC.size <= 1) {
            data.spine.spineReferences
                .filter { it.isLinear }
                .mapIndexed { index, spineRef ->
                    val res = spineRef.resource
                    TOCReference(res.title ?: "Chapter ${index + 1}", res)
                }
        } else {
            flatTOC
        }
    }

    override val canReload = false

    override fun author(): String? {
        val author = data.metadata.authors.firstOrNull() ?: return null
        return listOfNotNull(author.firstname, author.lastname).joinToString(" ").ifBlank { null }
    }

    override fun loadImage(image: String): ByteArray? {
        val decodedImage = try { URLDecoder.decode(image, "UTF-8") } catch (e: Exception) { image }

        data.resources.resourceMap[decodedImage]?.data?.let { return it }
        data.resources.resourceMap[image]?.data?.let { return it }

        val fileName = decodedImage.substringAfterLast("/")
        return data.resources.resourceMap.values.find {
            val entryName = it.href.substringAfterLast("/")
            entryName.equals(fileName, ignoreCase = true)
        }?.data
    }

    override fun size(): Int = allTocReferences.size

    override fun title(): String = data.title ?: "Unknown Book"

    override fun getChapterTitle(index: Int): UiText {
        return allTocReferences.getOrNull(index)?.title?.toUiText()
            ?: txt(R.string.chapter_format, (index + 1).toString())
    }

    override fun getLoadingStatus(index: Int): String? = null

    override suspend fun getChapterData(index: Int, reload: Boolean): String {
        val start = allTocReferences[index].resource
        val startIdx = data.spine.getResourceIndex(start)

        val end = allTocReferences.getOrNull(index + 1)?.resource
        var endIdx = data.spine.getResourceIndex(end)
        if (endIdx == -1) {
            endIdx = data.spine.spineReferences.size
        }
        val builder = StringBuilder()

        for (i in startIdx until endIdx) {
            try {
                val ref = data.spine.spineReferences[i]

                // I have no idea, but nonlinear = stop?
                if (!ref.isLinear && i != startIdx) continue

                val html = ref.resource.reader.readText()
                val doc = Jsoup.parse(html)
                val basePath = ref.resource.href.substringBeforeLast("/", "")

                doc.select("img, image").forEach { img ->
                    val attrName = if (img.tagName() == "image") "xlink:href" else "src"
                    var src = img.attr(attrName)
                    if (src.isNotEmpty() && !src.startsWith("http") && !src.startsWith("data:")) {
                        try {
                            val decodedSrc = URLDecoder.decode(src, "UTF-8")
                            src = resolveRelativePath(basePath, decodedSrc)
                        } catch (e: Throwable) {
                            logError(e)
                        }
                    }
                    if (img.tagName() == "image") {
                        val newImg = doc.createElement("img")
                        newImg.attr("src", src)
                        img.replaceWith(newImg)
                    } else {
                        img.attr("src", src)
                    }
                }

                builder.append(doc.body().html())
            } catch (t: Throwable) {
                logError(t)
            }
        }
        return builder.toString()
    }

    private fun resolveRelativePath(basePath: String, relativePath: String): String {
        val cleanRelative = relativePath.substringBefore("?").substringBefore("#")

        val fullPath = if (cleanRelative.startsWith("/") || basePath.isEmpty()) {
            cleanRelative
        } else {
            "$basePath/$cleanRelative"
        }

        val parts = fullPath.split("/")
        val resolvedParts = ArrayDeque<String>()

        for (part in parts) {
            when (part) {
                "", "." -> continue
                ".." -> if (resolvedParts.isNotEmpty()) resolvedParts.removeLast()
                else -> resolvedParts.addLast(part)
            }
        }
        return resolvedParts.joinToString("/")
    }

    override fun expand(last: String): Boolean = false

    override suspend fun posterBytes(): ByteArray? = data.coverImage?.data
}

class MLException(cause: Throwable) : Exception(cause)

data class LiveChapterData(
    val index: Int,
    /** Translated */
    val spans: List<TextSpan>,
    /** Translated */
    val rendered: Spanned,
    /** Non-Translated */
    val originalRendered: Spanned,
    /** Non-Translated */
    val originalSpans: ArrayList<TextSpan>,

    val title: UiText,
    val rawText: String,
    //val ttsLines: List<TTSHelper.TTSLine>
) {
    // tts lines are lazy because not everyone uses tts
    val ttsLines by lazy {
        ttsParseText(rendered.substring(0, rendered.length), index)
    }
}

data class ChapterUpdate(
    val data: ArrayList<SpanDisplay>,
    val seekToDesired: Boolean
)

class ReadActivityViewModel : ViewModel() {
    lateinit var book: AbstractBook
    private lateinit var markwon: Markwon
    private var isInApp: Boolean = true
    private var leftAppAt: ScrollIndex? = null
    private var mlTranslator: Translator? = null

    fun leftApp() {
        lastChangeIndex?.let { setScrollKeys(it) }
        isInApp = false
        leftAppAt = desiredIndex
        // Si el TTS está activo, al salir del lector la frase que se lee se subraya sola
        // para guardar el progreso de la lectura.
        saveTTSProgressAsUserMark()
    }

    fun resumedApp() {
        val leftAt = leftAppAt
        val resumeAt = desiredIndex

        leftAppAt = null
        isInApp = false
        // de vuelta en el lector el subrayado deja de seguir al TTS (ver followTTSWithMark)
        followTTSWithMark = false

        // if we resume the app and the desired index is different from what we are at currently
        // then we scroll to it
        if (leftAt != null && resumeAt != null && leftAt != resumeAt) {
            scrollToDesired(resumeAt)
        }
    }

    //private lateinit var reducer: MarkwonReducer

    fun canReload(): Boolean {
        return book.canReload
    }


    var mlSettings
        get() = getKey<MLSettings>(EPUB_CURRENT_ML, book.title()) ?: MLSettings(
            from = mlFromLanguage,
            to = mlToLanguage,
            useOnlineTranslation = mlUseOnlineTransaltion,
            useAiTranslation = mlUseAiTranslation,
            useDeepLTranslation = mlUseDeepLTranslation
        )
        set(value) = setKey(EPUB_CURRENT_ML, book.title(), value)

    private val _chapterData: MutableLiveData<ChapterUpdate> =
        MutableLiveData<ChapterUpdate>(null)
    val chapter: LiveData<ChapterUpdate> = _chapterData

    // we use bool as we cant construct Nothing, does not represent anything
    val _loadingStatus: MutableLiveData<Resource<Boolean>> =
        MutableLiveData<Resource<Boolean>>(null)
    val loadingStatus: LiveData<Resource<Boolean>> = _loadingStatus

    private val _chaptersTitles: MutableLiveData<List<UiText>> =
        MutableLiveData<List<UiText>>(null)
    val chaptersTitles: LiveData<List<UiText>> = _chaptersTitles

    private val _title: MutableLiveData<String> =
        MutableLiveData<String>(null)
    val title: LiveData<String> = _title

    private val _chapterTile: MutableLiveData<UiText> =
        MutableLiveData<UiText>(null)
    val chapterTile: LiveData<UiText> = _chapterTile

    private val _bottomVisibility: MutableLiveData<Boolean> =
        MutableLiveData<Boolean>(false)
    val bottomVisibility: LiveData<Boolean> = _bottomVisibility

    private val _ttsStatus: MutableLiveData<TTSHelper.TTSStatus> =
        MutableLiveData<TTSHelper.TTSStatus>(TTSHelper.TTSStatus.IsStopped)
    val ttsStatus: LiveData<TTSHelper.TTSStatus> = _ttsStatus

    private val _ttsLine: MutableLiveData<TTSHelper.TTSLine?> =
        MutableLiveData<TTSHelper.TTSLine?>(null)
    val ttsLine: LiveData<TTSHelper.TTSLine?> = _ttsLine


    /*  private val _orientation: MutableLiveData<OrientationType> =
          MutableLiveData<OrientationType>(null)
      val orientation: LiveData<OrientationType> = _orientation

      private val _backgroundColor: MutableLiveData<Int> =
          MutableLiveData<Int>(null)
      val backgroundColor: LiveData<Int> = _backgroundColor

      private val _textColor: MutableLiveData<Int> =
          MutableLiveData<Int>(null)
      val textColor: LiveData<Int> = _textColor

      private val _textSize: MutableLiveData<Int> =
          MutableLiveData<Int>(null)
      val textSize: LiveData<Int> = _textSize*/

    // private val _textFont: MutableLiveData<String> =
    //     MutableLiveData<String>(null)
    // val textFont: LiveData<String> = _textFont

    fun switchVisibility() {
        val next = !(_bottomVisibility.value ?: false)
        // postValue deja .value desactualizado hasta que se entrega: dos toggles seguidos (con el
        // hilo principal ocupado) leerían el mismo valor y uno se perdería. Desde el hilo
        // principal se asigna directamente.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _bottomVisibility.value = next
        } else {
            _bottomVisibility.postValue(next)
        }
    }


    private var chaptersTitlesInternal: ArrayList<UiText> = arrayListOf()

    var desiredIndex: ScrollIndex? = null
    var desiredTTSIndex: ScrollIndex? = null

    private fun updateChapters() {
        for (idx in chaptersTitlesInternal.size until book.size()) {
            chaptersTitlesInternal.add(book.getChapterTitle(idx))
        }
        _chaptersTitles.postValue(chaptersTitlesInternal)
    }

    private val chapterMutex = Mutex()
    private val chapterExpandMutex = Mutex()

    private val requested: HashSet<Int> = hashSetOf()

    private val loading: HashSet<Int> = hashSetOf()
    private val chapterData: HashMap<Int, Resource<LiveChapterData>?> = hashMapOf()
    private val hasExpanded: HashSet<Int> = hashSetOf()

    var currentIndex = Int.MIN_VALUE
        private set

    // Session-only toggle (not persisted): when true, chapters are displayed using their
    // original (non-translated) text instead of the translated one, without discarding the
    // translation - both are already kept per-chapter in LiveChapterData.
    private val _showOriginalText = MutableLiveData(false)
    val showOriginalTextLive: LiveData<Boolean> = _showOriginalText

    // Fuente de verdad síncrona del toggle: updateReadArea() puede ejecutarse en otro hilo justo
    // después de cambiarlo y un LiveData.postValue() aún no sería visible para él.
    @Volatile
    private var showOriginalText = false

    fun toggleShowOriginalText() {
        val toOriginal = !showOriginalText
        showOriginalText = toOriginal
        _showOriginalText.value = toOriginal

        // Los ids de los párrafos dependen de (capítulo, start, end) y esos offsets cambian entre
        // la versión original y la traducida/sustituida, así que DiffUtil ve una lista nueva y el
        // RecyclerView pierde su ancla y salta al inicio del capítulo. Por eso se convierte la
        // posición de lectura a la otra versión y se recoloca explícitamente.
        val position = desiredIndex
        ioSafe {
            if (position != null) {
                desiredIndex = remapScrollIndexForToggle(position, toOriginal)
            }
            updateReadArea(seekToDesired = position != null)
        }
    }

    /**
     * Traslada [from] (posición en la versión que se estaba mostrando) a la versión a la que se
     * cambia: mismo párrafo (innerIndex, que es estable entre versiones) y mismo punto relativo
     * dentro de él, ya que el texto sustituido/traducido tiene otra longitud.
     */
    private suspend fun remapScrollIndexForToggle(from: ScrollIndex, toOriginal: Boolean): ScrollIndex {
        val live = (chapterMutex.withLock { chapterData[from.index] } as? Resource.Success<LiveChapterData>)?.value
            ?: return from

        val oldSpans = if (toOriginal) live.spans else live.originalSpans
        val newSpans = if (toOriginal) live.originalSpans else live.spans

        val old = oldSpans.firstOrNull { it.innerIndex == from.innerIndex } ?: return from
        // si el párrafo no existe en la otra versión, el más cercano anterior
        val new = newSpans.firstOrNull { it.innerIndex == from.innerIndex }
            ?: newSpans.lastOrNull { it.innerIndex <= from.innerIndex }
            ?: newSpans.firstOrNull()
            ?: return from

        val oldLength = (old.end - old.start).coerceAtLeast(1)
        val fraction = ((from.char - old.start).toFloat() / oldLength).coerceIn(0f, 1f)
        val newChar = new.start + (fraction * (new.end - new.start)).toInt()

        return ScrollIndex(from.index, new.innerIndex, newChar)
    }




    /** lower padding for preloading current-chapterPaddingBottom*/
    private var initPaddingBottom = 1//these are to reduce loadings times
    private var chapterPaddingBottom: Int = 1

    /** upper padding, for preloading current+chapterPaddingTop */
    private var initPaddingTop = 1
    private var chapterPaddingTop: Int = 2

    fun reloadChapter(index: Int) = ioSafe {
        hasExpanded.clear() // will unfuck the rest
        // La descarga puede tardar (reintentos), y sin ningún indicador parecía que el botón no
        // hacía nada. Se usa el mismo indicador a pantalla completa que en "Aplicar" y, al
        // terminar, se vuelve al punto de lectura en vez de quedarse donde caiga la lista.
        val title = context?.let { ctx ->
            ctx.getString(R.string.reload_chapter_format)
                .format(book.getChapterTitle(index).asString(ctx))
        }
        _loadingStatus.postValue(Resource.Loading(title))
        loadIndividualChapter(index, reload = true, notify = false, postLoading = true)
        updateReadArea(seekToDesired = true)
    }

    fun reTranslateChapter(index: Int) = ioSafe {
        hasExpanded.clear() // will unfuck the rest
        // Use the same full-screen loading indicator as "Aplicar" (postLoading = true) so the
        // user actually sees that a retranslation is happening, instead of it silently updating
        // chapterData in the background with no visible feedback.
        _loadingStatus.postValue(Resource.Loading(context?.getString(R.string.translating)))
        loadIndividualChapter(index, reload = false, reTranslate = true, notify = true, postLoading = true)
        updateReadArea(seekToDesired = false)
    }

    fun reloadChapter() {
        reloadChapter(currentIndex)
    }

    fun copyChapterToClipboard(index: Int) = viewModelScope.launch {
        val data = chapterMutex.withLock { chapterData[index] }
        if (data is Resource.Success) {
            val liveData = data.value
            val ctx = context ?: return@launch
            val title = liveData.title.asString(ctx)
            val content = liveData.rendered.toString()
            val fullText = "$title\n\n$content"
            UIHelper.clipboardHelper(liveData.title, fullText)
        }
    }

    fun refreshChapters() = ioSafe {
        hasExpanded.clear() // will unfuck the rest
        chapterMutex.withLock {
            chapterData.clear()
        }
        _loadingStatus.postValue(Resource.Loading())
        loadIndividualChapter(currentIndex, reload = false, notify = true, postLoading = true)
        updateReadArea(seekToDesired = true)
    }

    private suspend fun updateIndexAsync(
        index: Int,
        notify: Boolean = true,
        postLoading: Boolean = false,
    ) {
        val range =
            if(!notify) (index - initPaddingBottom..index + initPaddingTop)
            else (index - chapterPaddingBottom..index + chapterPaddingTop)

        for (idx in range) {
            requested += idx
            loadIndividualChapter(idx, notify = notify, postLoading = postLoading)
        }

    }

    private fun updateIndex(index: Int) {
        val range = (index - chapterPaddingBottom .. index + chapterPaddingTop)
        val needToLoad = range.any { !requested.contains(it) }
        if (needToLoad)
            ioSafe {
                updateIndexAsync(index)
            }
    }

    fun onScroll(visibility: ScrollVisibilityIndex?) {
        if (visibility == null) return
        // dynamically increase padding in case of very small chapters with a maximum of 10 chapters
        val first = visibility.firstInMemory.index
        val last = visibility.lastInMemory.index
        chapterPaddingTop = minOf(10, maxOf(chapterPaddingTop, (last - first) + 1))

        val current = currentIndex

        val save = visibility.firstFullyVisible ?: visibility.firstInMemory
        desiredTTSIndex = visibility.firstFullyVisibleUnderLine?.toScroll()
        changeIndex(save.toScroll())

        // update the read area if changed index
        if (current != save.index)
            updateReadArea()

        // load forwards and backwards
        updateIndex(visibility.firstInMemory.index)
        updateIndex(visibility.lastInMemory.index)
    }

    private fun chapterIdxToSpanDisplay(index: Int): List<SpanDisplay> {
        return when (val data = chapterData[index]) {
            null -> emptyList()
            is Resource.Loading -> {
                listOf<SpanDisplay>(LoadingSpanned(data.url, index))
            }

            is Resource.Success -> {
                if (showOriginalText) data.value.originalSpans else data.value.spans
            }

            is Resource.Failure -> listOf<SpanDisplay>(
                FailedSpanned(
                    reason = data.errorString.toUiText(),
                    index = index,
                    cause = data.cause
                )
            )
        }
    }

    // ChapterLoadSpanned(fromIndex, 0, index, text)
    private fun chapterIdxToSpanDisplayNextButton(index: Int, fromIndex: Int): SpanDisplay? {
        return chapterIdxToSpanDisplayNext(
            index,
            fromIndex
        ) { cIndex, innerIndex, loadIndex, name ->
            ChapterLoadSpanned(cIndex, innerIndex, loadIndex, name)
        }
    }

    private fun chapterIdxToSpanDisplayOverscrollButton(index: Int, fromIndex: Int): SpanDisplay? {
        return chapterIdxToSpanDisplayNext(
            index,
            fromIndex
        ) { cIndex, innerIndex, loadIndex, name ->
            ChapterOverscrollSpanned(cIndex, innerIndex, loadIndex, name)
        }
    }

    private fun chapterIdxToSpanDisplayNext(
        index: Int,
        fromIndex: Int,
        constructor: (Int, Int, Int, UiText) -> SpanDisplay
    ): SpanDisplay? {
        return when (val data = chapterData[index]) {
            is Resource.Loading -> LoadingSpanned(data.url, index)
            is Resource.Failure ->
                FailedSpanned(
                    reason = data.errorString.toUiText(),
                    index = index,
                    cause = data.cause
                )

            else -> chaptersTitlesInternal.getOrNull(index)
                ?.let { text -> constructor(fromIndex, 0, index, text) }
        }
    }

    private fun updateReadArea(seekToDesired: Boolean = false) {
        val cIndex = currentIndex
        val chapters = ArrayList<SpanDisplay>()
        val canReload = this.book.canReload
        when (readerType) {
            ReadingType.DEFAULT, ReadingType.INF_SCROLL -> {
                for (idx in cIndex - chapterPaddingBottom..cIndex + chapterPaddingTop) {
                    if (idx < chaptersTitlesInternal.size && idx >= 0)
                        chapters.add(
                            ChapterStartSpanned(
                                idx,
                                0,
                                chaptersTitlesInternal[idx],
                                canReload
                            )
                        )
                    chapters.addAll(chapterIdxToSpanDisplay(idx))
                }
            }

            ReadingType.BTT_SCROLL -> {
                chapterIdxToSpanDisplayNextButton(cIndex - 1, cIndex)?.let {
                    chapters.add(it)
                }

                chaptersTitlesInternal.getOrNull(cIndex)?.let { text ->
                    chapters.add(ChapterStartSpanned(cIndex, 0, text, canReload))
                }

                chapters.addAll(chapterIdxToSpanDisplay(cIndex))

                chapterIdxToSpanDisplayNextButton(cIndex + 1, cIndex)?.let {
                    chapters.add(it)
                }
            }

            ReadingType.OVERSCROLL_SCROLL -> {
                chapterIdxToSpanDisplayOverscrollButton(cIndex - 1, cIndex)?.let {
                    chapters.add(it)
                }

                chaptersTitlesInternal.getOrNull(cIndex)?.let { text ->
                    chapters.add(ChapterStartSpanned(cIndex, 0, text, canReload))
                }

                chapters.addAll(chapterIdxToSpanDisplay(cIndex))

                chapterIdxToSpanDisplayOverscrollButton(cIndex + 1, cIndex)?.let {
                    chapters.add(it)
                }
            }
        }

        _chapterData.postValue(ChapterUpdate(data = chapters, seekToDesired = seekToDesired))
    }

    private fun notifyChapterUpdate(index: Int, seekToDesired: Boolean = false) {
        val cIndex = currentIndex
        if (cIndex - chapterPaddingBottom <= index && index <= cIndex + chapterPaddingTop) {
            updateReadArea(seekToDesired)
        }
    }

    private val markwonMutex = Mutex()

    @WorkerThread
    private suspend fun loadIndividualChapter(
        index: Int,
        reload: Boolean = false,
        notify: Boolean = true,
        reTranslate: Boolean = false,
        postLoading: Boolean = false,
    ) {
        if (index < 0) return

        // set loading and return early if already loading or return cache
        chapterMutex.withLock {
            if (loading.contains(index)) return
            if (!reload && !reTranslate && chapterData.contains(index)) {
                return
            }

            loading += index
            chapterData[index] = Resource.Loading(null)
            if (notify) notifyChapterUpdate(index)
        }

        // we check for out of bounds and if it is out of bounds then try to expand it (Reddit next)
        // we lock it here to prevent duplicate loading when init
        chapterExpandMutex.withLock {
            val preSize = book.size()
            while (index >= book.size()) {
                // will only expand once per session per chapter
                if (hasExpanded.contains(book.size())) break
                hasExpanded += book.size()

                try {
                    // we assume that the text is cached
                    book.expand(book.getChapterData(book.size() - 1, reload = false))
                } catch (t: Throwable) {
                    logError(t)
                }
            }
            if (preSize != book.size()) updateChapters()
        }

        // if we are still out of bounds then return no more chapters
        if (index >= book.size()) {
            chapterMutex.withLock {
                // only push one no more chapters
                if (index == book.size()) {
                    chapterData[index] =
                        Resource.Failure(
                            null,
                            context?.getString(R.string.no_more_chapters) ?: "ERROR"
                        )
                } else {
                    chapterData[index] = null
                }
                loading -= index
                if (notify) notifyChapterUpdate(index)
            }
            return
        }

        // we have verified we are within bounds, then set the loading to the index url
        chapterMutex.withLock {
            chapterData[index] = Resource.Loading(book.getLoadingStatus(index))
            if (notify) notifyChapterUpdate(index)
        }

        // load the data and precalculate everything needed
        try {
            val data = safeApiCall {
                book.getChapterData(index, reload)
            }.map { text ->
                val rawText = preParseHtml(text, authorNotes)
                // val renderedBuilder = SpannableStringBuilder()
                // val lengths : IntArray
                // val nodes : Array<Node>
                val parsed: Node
                var rendered: Spanned
                val originalRendered: Spanned
                val originalSpans: ArrayList<TextSpan>
                var spans: ArrayList<TextSpan>

                markwonMutex.withLock {
                    parsed = markwon.parse(rawText)
                    rendered = markwon.render(parsed)

                    spans = parseTextToSpans(rendered, index)
                    originalSpans = spans
                    originalRendered = rendered

                    val asyncDrawables = rendered.getSpans<AsyncDrawableSpan>()
                    for (async in asyncDrawables) {
                        async.drawable.result =
                            book.loadImageBitmap(async.drawable.destination)?.toDrawable(
                                Resources.getSystem()
                            )
                    }

                    // translation may strip stuff, idk how to solve that in a clean way atm
                    translate(
                        rendered,
                        spans,
                        reTranslate = reTranslate
                    ) { (progressChapter, progressInnerIndex, progressInnerTotal) ->
                        val progressText =
                            "${context?.getString(R.string.translating)} ${
                                book.getChapterTitle(
                                    progressChapter
                                )
                            } ($progressInnerIndex/$progressInnerTotal)"
                        if (postLoading) {
                            _loadingStatus.postValue(Resource.Loading(progressText))
                        } else {
                            chapterMutex.withLock {
                                chapterData[index] =
                                    Resource.Loading(progressText)
                                if (notify) notifyChapterUpdate(index)
                            }
                        }
                    }.let { (mlRender, mlSpans) ->
                        rendered = mlRender
                        spans = mlSpans
                    }
                }

                LiveChapterData(
                    index = index,
                    rendered = rendered,
                    spans = spans,
                    originalRendered = originalRendered,
                    originalSpans = originalSpans,
                    rawText = rawText,
                    title = book.getChapterTitle(index),
                )
            }

            // set the data and return
            chapterMutex.withLock {
                chapterData[index] = data
            }
        } catch (t: Throwable) {
            // Tasks.await may throw
            chapterMutex.withLock {
                chapterData[index] = throwableToResource(t)
            }
        } finally {
            chapterMutex.withLock {
                loading -= index
                if (notify) notifyChapterUpdate(index)
            }
        }
    }

    private fun hashString(text: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(text)
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun getFinalTranslatedText(spans: ArrayList<TextSpan>, translatedLines: List<String>):Pair<SpannableStringBuilder, ArrayList<TextSpan>>{
        val builder = SpannableStringBuilder()
        val out = ArrayList<TextSpan>()
        spans.forEachIndexed { i, originalSpan ->
            val hasImage = originalSpan.text.getSpans<AsyncDrawableSpan>().isNotEmpty()
            val finalText =
                if (hasImage)
                    originalSpan.text
                else
                    (translatedLines.getOrNull(i)?: return@forEachIndexed).toSpanned()
            val start = builder.length
            builder.append(finalText)
            val end = builder.length
            builder.append('\n')
            out.add(TextSpan(finalText, start, end, originalSpan.index, originalSpan.innerIndex))
        }
        return builder to out
    }


    /**
     * Sin traducción activa lo que se muestra es el texto original, así que las sustituciones
     * (tanto "antes" como "después") se aplican directamente sobre él, conservando el formato.
     * Devuelve null si no hay nada que cambiar.
     */
    private fun applyReplacementsWithoutTranslation(
        spans: ArrayList<TextSpan>,
        rules: List<TextReplacement>
    ): Pair<Spanned, ArrayList<TextSpan>>? {
        if (rules.isEmpty()) return null
        var changed = false
        val builder = SpannableStringBuilder()
        val out = ArrayList<TextSpan>()
        spans.forEach { span ->
            val hasImage = span.text.getSpans<AsyncDrawableSpan>().isNotEmpty()
            val finalText = if (hasImage) {
                span.text
            } else {
                TextReplacements.applyKeepingFormat(span.text, rules).also {
                    if (it !== span.text) changed = true
                }
            }
            val start = builder.length
            builder.append(finalText)
            val end = builder.length
            builder.append('\n')
            out.add(TextSpan(finalText, start, end, span.index, span.innerIndex))
        }
        return if (changed) builder to out else null
    }

    /**
     * Una sustitución "antes" puede dejar un párrafo vacío; los traductores trabajan por
     * párrafos y un hueco podría desalinear las líneas, así que esos párrafos no se envían.
     */
    private suspend fun translateSkippingBlank(
        lines: List<String>,
        skipBlank: Boolean,
        translateFn: suspend (List<String>) -> List<String>
    ): List<String> {
        if (!skipBlank) return translateFn(lines)
        val indices = lines.indices.filter { lines[it].isNotBlank() }
        if (indices.size == lines.size) return translateFn(lines)
        if (indices.isEmpty()) return lines
        val translated = translateFn(indices.map { lines[it] })
        val out = ArrayList(lines)
        indices.forEachIndexed { k, i -> translated.getOrNull(k)?.let { out[i] = it } }
        return out
    }

    @Throws(MLException::class)
    private suspend fun translate(
        text: Spanned,
        spans: ArrayList<TextSpan>,
        reTranslate: Boolean = false,
        loading: suspend (Triple<Int, Int, Int>) -> Unit
    ): Pair<Spanned, ArrayList<TextSpan>> {
        try {
            val currentSettings = mlSettings

            // Sustituciones de la ficha de la novela (pestaña "Sustituir")
            val replacementRules = TextReplacements.get(book.title())
            val beforeRules =
                TextReplacements.forTiming(replacementRules, ReplaceTiming.BEFORE_TRANSLATION)
            val afterRules =
                TextReplacements.forTiming(replacementRules, ReplaceTiming.AFTER_TRANSLATION)

            if (spans.isEmpty()) return text to spans
            if (currentSettings.isInvalid()) {
                return applyReplacementsWithoutTranslation(spans, beforeRules + afterRules)
                    ?: (text to spans)
            }

            // lo que recibe el traductor (con las sustituciones "antes" ya aplicadas)
            val sourceLines = spans.map {
                TextReplacements.apply(it.text.toString(), beforeRules)
            }

            val textHash = hashString(
                text.trim().toString().toByteArray()
            )

            //the file - cache specific to model to avoid poisoned cache
            val filePrefix =
                "ml_${textHash}.${currentSettings.from}_to_${currentSettings.to}.${
                if (currentSettings.useOnlineTranslation) "online" 
                else if (currentSettings.useDeepLTranslation) "deepl"
                else if (currentSettings.useAiTranslation) "ai_${mlAiModel.replace(Regex("[^a-zA-Z0-9]"), "_")}_${hashString(mlAiPrompt.toByteArray()).take(8)}_${mlAiThinkingLevel}_${mlAiTemperature}_${mlAiTopP}_${mlAiTopK}"
                else "offline"
            }${if (beforeRules.isEmpty()) "" else ".r${TextReplacements.hash(beforeRules)}"}"

            // read from cache if it exists
            // we assume that parseTextToSpans is equivalent from restoring from the builder
            // aka out == parseTextToSpans(builder)
            if (!reTranslate) {
                val cachedData = safe {
                    context?.cacheDir?.let { dir ->
                        val cache = File(dir, "$filePrefix.txt")
                        if (cache.exists()) {
                            Log.i(TAG, "Cache exists for $filePrefix")
                            // la caché guarda la traducción "pura"; "después" se aplica al leerla
                            val lines = cache.readLines().map {
                                TextReplacements.apply(it, afterRules)
                            }
                            val (builder, out) = getFinalTranslatedText(spans, lines)
                            return@safe builder to out
                        }
                    }
                    return@safe null
                }
                if(cachedData != null) return cachedData
            }


            // Avisa al progreso de la UI y a la notificación del servicio en primer plano
            suspend fun reportProgress(chapter: Int, progress: Int, total: Int) {
                ReaderTranslationKeepAlive.update(
                    book.getChapterTitle(chapter).asStringNull(context) ?: book.title(),
                    progress,
                    total
                )
                loading.invoke(Triple(chapter, progress, total))
            }

            // Mientras se traduce de verdad (no con la caché) mantenemos el proceso vivo con un
            // servicio en primer plano: sin él Android congela la red al salir de la app y la
            // traducción se queda parada hasta volver. El finally garantiza que siempre se libera.
            ReaderTranslationKeepAlive.begin(book.title())
            val translatedList: List<String> = try {
                // --- Online mode ---
                if (currentSettings.useOnlineTranslation) {
                    translateSkippingBlank(
                        sourceLines,
                        beforeRules.isNotEmpty()
                    ) { lines ->
                        GoogleTranslateOnline.onlineTranslate(
                            lines,
                            currentSettings.from,
                            currentSettings.to
                        ) { progress, total ->
                            reportProgress(spans[0].index, progress, total)
                        }
                    }
                }

                // --- DeepL mode (ya omite los párrafos vacíos y devuelve el mismo tamaño) ---
                else if (currentSettings.useDeepLTranslation) {
                    DeepLTranslate.deeplTranslate(
                        sourceLines,
                        mlDeepLApiKey,
                        currentSettings.from,
                        currentSettings.to
                    ) { progress, total ->
                        reportProgress(spans[0].index, progress, total)
                    }
                }

                // --- AI mode ---
                else if (currentSettings.useAiTranslation) {
                    translateSkippingBlank(
                        sourceLines,
                        beforeRules.isNotEmpty()
                    ) { lines ->
                        GeminiTranslate.geminiTranslate(
                            lines,
                            mlAiApiKey,
                            mlAiModel,
                            currentSettings.from,
                            currentSettings.to,
                            mlAiPrompt,
                            thinkingLevel = GeminiThinkingLevel.fromName(mlAiThinkingLevel),
                            temperature = mlAiTemperature,
                            topP = mlAiTopP,
                            topK = mlAiTopK,
                            maxRequestsPerMinute = mlAiMaxRequestsPerMinute,
                            backupModel = mlAiBackupModel,
                            maxCharsPerRequest = mlAiMaxCharsPerRequest
                        ) { progress, total ->
                            reportProgress(spans[0].index, progress, total)
                        }
                    }
                }

                // --- Offline mode ---
                else {
                    val translator = mlTranslator
                        ?: return applyReplacementsWithoutTranslation(spans, beforeRules + afterRules)
                            ?: (text to spans)
                    spans.mapIndexed { i,  span ->
                        reportProgress(span.index, i, spans.size)
                        val line = sourceLines[i]
                        if (line.isBlank()) return@mapIndexed line
                        try {
                            Tasks.await(translator.translate(line))
                        } catch (t: ExecutionException) {
                            throw t.cause ?: t
                        }
                    }
                }
            } finally {
                ReaderTranslationKeepAlive.end()
            }

            // traducción "pura" (sin sustituciones "después"): es lo que se guarda en caché
            val (builder, out) = getFinalTranslatedText(spans, translatedList)

            // Protection: if translation is same as original, don't cache
            // (también si es igual al texto ya corregido por las sustituciones "antes",
            // que es lo que devuelven los traductores cuando fallan)
            val resultText = builder.toString()
            val sourceText = if (beforeRules.isEmpty()) null
            else getFinalTranslatedText(spans, sourceLines).first.toString()
            val isSameAsOriginal = resultText.trim() == text.toString().trim()
                    || (sourceText != null && resultText.trim() == sourceText.trim())

            if (!isSameAsOriginal) {
                // atomically write the file by rename
                safe {
                    context?.cacheDir?.let {
                        val cache = File(it, "$filePrefix.tmp")
                        cache.writeText(resultText)
                        safe { File(it, "$filePrefix.txt").delete() } // just in case
                        cache.renameTo(File(it, "$filePrefix.txt"))
                    }
                }
            }

            if (afterRules.isNotEmpty()) {
                return getFinalTranslatedText(
                    spans,
                    translatedList.map { TextReplacements.apply(it, afterRules) }
                )
            }
            return builder to out
        } catch (t: Throwable) {
            throw MLException(t)
        }
    }

    @Throws(MLException::class)
    suspend fun requireMLDownload(): Boolean {
        val settings = MLSettings(from = mlFromLanguage, to = mlToLanguage, mlUseOnlineTransaltion, mlUseAiTranslation, mlUseDeepLTranslation)
        if (settings.isInvalid() || mlUseOnlineTransaltion || mlUseAiTranslation || mlUseDeepLTranslation) {
            return false
        }
        val modelManager = RemoteModelManager.getInstance()

        for (model in arrayOf(settings.from, settings.to)) {
            if (model == "en") continue

            if (!Tasks.await(
                    modelManager.isModelDownloaded(
                        TranslateRemoteModel.Builder(model).build()
                    )
                )
            ) {
                return true
            }
        }

        return false
    }

    fun applyMLSettings(allowDownload: Boolean) = ioSafe {
        val settings = MLSettings(from = mlFromLanguage, to = mlToLanguage, mlUseOnlineTransaltion, mlUseAiTranslation, mlUseDeepLTranslation)
        if (settings.isValid() && allowDownload && safeAsync { requireMLDownload() } == true) {
            _loadingStatus.postValue(Resource.Loading("Downloading language"))
        }
        initMLFromSettings(settings, allowDownload)
        reloadMLForAllChapters()
    }

    private suspend fun reloadMLForAllChapters(reTranslate: Boolean = false) {
        _loadingStatus.postValue(Resource.Loading(context?.getString(R.string.translating)))
        chapterMutex.withLock {
            val cIndex = currentIndex
            val lower = cIndex - chapterPaddingBottom
            val upper = cIndex + chapterPaddingTop

            val keys =
                chapterData.keys.toTypedArray() // deep copy it to avoid ConcurrentModificationException

            // remove all irrelevant cache so we do not translate outdated shit
            for (key in keys) {
                if (key < lower || key > upper) {
                    chapterData.remove(key)
                }
            }

            // update the rem cache
            for (entry in chapterData.entries) {
                val value = entry.value
                val success = when (value) {
                    is Resource.Success -> value.value
                    is Resource.Failure -> null // Force retry on failures!
                    else -> null
                }

                if (success != null) {
                    try {
                        translate(
                            success.originalRendered,
                            success.originalSpans,
                            reTranslate = reTranslate
                        ) { (progressChapter, progressInnerIndex, progressInnerTotal) ->
                            _loadingStatus.postValue(
                                Resource.Loading(
                                    "${context?.getString(R.string.translating)} ${
                                        book.getChapterTitle(
                                            progressChapter
                                        )
                                    } ($progressInnerIndex/$progressInnerTotal)"
                                )
                            )
                        }.let { (mlRender, mlSpans) ->
                            entry.setValue(
                                Resource.Success(
                                    success.copy(
                                        rendered = mlRender,
                                        spans = mlSpans,
                                    )
                                )
                            )
                        }
                    } catch (t: Throwable) {
                        entry.setValue(
                            throwableToResource(t)
                        )
                    }
                } else if (value is Resource.Failure) {
                    // Trigger a clean reload for this chapter
                    val idx = entry.key
                    viewModelScope.launch(Dispatchers.IO) {
                        loadIndividualChapter(idx, reload = false, notify = true, postLoading = false, reTranslate = reTranslate)
                    }
                }
            }
        }

        // update what we have read
        updateReadArea()
        //refreshChapters()
    }

    private suspend fun initMLFromSettings(settings: MLSettings, allowDownload: Boolean) {
        try {
            mlTranslator?.closeQuietly()
            mlTranslator = null

            if (settings.isInvalid() || settings.useOnlineTranslation || settings.useAiTranslation || settings.useDeepLTranslation) {
                mlSettings = settings
                return
            }

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(settings.from)
                .setTargetLanguage(settings.to)
                .build()

            val translator = Translation.getClient(options)
            mlTranslator = translator

            if (allowDownload) {
                Tasks.await(
                    translator.downloadModelIfNeeded(), 120L, TimeUnit.SECONDS
                )//for bad wifi, like my 2mb/s one TT
            }

            mlSettings = settings
        } catch (_: TimeoutException) {
            showToast(R.string.unable_to_download_language)
            mlTranslator?.closeQuietly()
            mlTranslator = null
        } catch (t: Throwable) {
            logError(t)
        }
    }

    fun init(intent: Intent?, context: ReadActivity2) = ioSafe {
        _loadingStatus.postValue(Resource.Loading())
        initTTSSession(context)

        val loadedBook = safeApiCall {
            if (intent == null) throw ErrorLoadingException("No intent")

            val data = intent.data ?: throw ErrorLoadingException("Empty intent")
            val isFromEpub = intent.type != "quickstream"

            val epub = if (isFromEpub) {
                val fd = context.contentResolver.openFileDescriptor(data, "r")
                    ?: throw ErrorLoadingException("Unable to open file descriptor")
                val zipFile = AndroidZipFile(fd, "")
                val book = EpubReader().readEpubLazy(zipFile, "utf-8")
                RegularBook(book)
            } else {
                val input = context.contentResolver.openInputStream(data)
                    ?: throw ErrorLoadingException("Empty data")
                QuickBook(DataStore.mapper.readValue(input.reader().readText()))
            }

            if (epub.size() <= 0) {
                throw ErrorLoadingException("Empty book, failed to parse ${intent.type}")
            }
            epub
        }

        when (loadedBook) {
            is Resource.Success -> {
                init(loadedBook.value, context)

                // antes del primer render, para que el subrayado ya salga al abrir el lector
                loadUserMark()

                initMLFromSettings(mlSettings, false)

                // cant assume we know a chapter max as it can expand

                val desiredChapterName = getKey<String>(EPUB_CURRENT_POSITION_CHAPTER, book.title())
                val desiredChapterIndex =
                    (0 until book.size()).firstOrNull {
                        loadedBook.value.getChapterTitle(it)
                            .asStringNull(context) == desiredChapterName
                    } ?: getKey<Int>(EPUB_CURRENT_POSITION, book.title()) ?: 0
                val loadedChapterIndex =
                    maxOf(desiredChapterIndex, 0)

                // we the current loaded thing here, but because loadedChapter can be >= book.size (expand) we have to check
                if (loadedChapterIndex < book.size()) {
                    _loadingStatus.postValue(
                        Resource.Loading(
                            book.getLoadingStatus(
                                loadedChapterIndex
                            )
                        )
                    )
                }

                currentIndex = loadedChapterIndex
                updateIndexAsync(loadedChapterIndex, notify = false, postLoading = true)

                if (book.size() <= 0) {
                    _loadingStatus.postValue(
                        Resource.Failure(
                            null,
                            "Invalid chapter data when trying to load chapter $loadedChapterIndex when the book only has ${book.size()} chapters"
                        )
                    )
                    return@ioSafe
                }

                // if we are reading a book that sub/resize for some reason, this will clamp it into the correct range
                if (loadedChapterIndex >= book.size()) {
                    currentIndex = book.size() - 1
                    updateIndexAsync(currentIndex, notify = false)
                    showToast("Resize $loadedChapterIndex -> $currentIndex", Toast.LENGTH_LONG)
                }

                val char = getKey(
                    EPUB_CURRENT_POSITION_SCROLL_CHAR, book.title()
                ) ?: 0

                val innerIndex = innerCharToIndex(currentIndex, char) ?: 0

                // don't update as you want to seek on update
                changeIndex(ScrollIndex(currentIndex, innerIndex, char))

                // notify once because initial load is 3 chapters I don't care about 10 notifications when the user cant see it
                updateReadArea(seekToDesired = true)

                /*_loadingStatus.postValue(
                    Resource.Success(true)
                )*/
            }

            is Resource.Failure -> {
                _loadingStatus.postValue(
                    Resource.Failure(
                        loadedBook.cause,
                        loadedBook.errorString
                    )
                )
            }

            else -> throw NotImplementedError()
        }
    }

    fun init(book: AbstractBook, context: Context) {
        this.book = book
        _title.postValue(book.title())

        updateChapters()
        val imageLoader: ImageLoader = SingletonImageLoader.get(context)

        val coilStore = object : CoilStore {
            override fun load(drawable: AsyncDrawable): ImageRequest {
                val newUrl = drawable.destination.substringAfter("&url=")
                val url =
                    book.resolveUrl(
                        if (newUrl.length > 8) { // we assume that it is not aHub url by length > 8
                            URLDecoder.decode(newUrl)
                        } else {
                            drawable.destination
                        }
                    )

                return ImageRequest.Builder(context)
                    .data(url)
                    .build()
            }

            override fun cancel(disposable: Disposable) {
                disposable.dispose()
            }
        }

        markwon = Markwon.builder(context)
            .usePlugin(HtmlPlugin.create { plugin -> plugin.excludeDefaults(false) })
            .usePlugin(CoilImagesPlugin.create(context, coilStore, imageLoader))
            .usePlugin(object :
                AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.imageSizeResolver(object : ImageSizeResolver() {
                        override fun resolveImageSize(drawable: AsyncDrawable): Rect {
                            return drawable.result.bounds
                        }
                    })
                }
            })
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .build()
        //reducer = MarkwonReducer.directChildren()
    }

    // =================================  SUBRAYADO DEL USUARIO  ==================================
    // Solo puede haber UNO por novela: vive bajo EPUB_USER_MARK/<título> y crear uno nuevo en la
    // misma novela sustituye al anterior. Las demás novelas conservan el suyo.

    /** Fuente de verdad (legible desde cualquier hilo). El LiveData solo avisa de los cambios. */
    @Volatile
    private var userMarkValue: ReaderMark? = null
    private val _userMark = MutableLiveData<ReaderMark?>(null)
    val userMark: LiveData<ReaderMark?> = _userMark

    /** Carga el subrayado guardado de la novela abierta (si lo hay). */
    private fun loadUserMark() {
        val mark = getKey<ReaderMark>(EPUB_USER_MARK, book.title())
        userMarkValue = mark
        _userMark.postValue(mark)
    }

    /** Sustituye el subrayado actual de esta novela por este. */
    fun setUserMark(chapterIndex: Int, start: Int, end: Int, text: String) {
        if (end <= start || text.isEmpty()) return
        storeUserMark(
            ReaderMark(
                bookTitle = book.title(),
                chapterIndex = chapterIndex,
                start = start,
                end = end,
                text = text
            )
        )
    }

    private fun storeUserMark(mark: ReaderMark) {
        if (userMarkValue == mark) return
        setKey(EPUB_USER_MARK, book.title(), mark)
        userMarkValue = mark
        _userMark.postValue(mark)
    }

    /**
     * Frase que el TTS está leyendo ahora mismo, ya convertida en marca (null si no hay TTS).
     * La actualiza el hilo del TTS en cada frase.
     */
    @Volatile
    private var ttsProgressMark: ReaderMark? = null

    /**
     * true desde que el usuario sale del lector con el TTS activo hasta que vuelve. Mientras dure,
     * el TTS (que sigue leyendo en segundo plano) mueve el subrayado a cada frase nueva para que
     * el progreso guardado no se quede atrás.
     */
    @Volatile
    private var followTTSWithMark = false

    /** Convierte la línea [line] del TTS en marca, sin espacios sueltos a los lados. */
    private fun markForTTSLine(line: TTSHelper.TTSLine, text: CharSequence): ReaderMark? {
        var start = line.startChar.coerceAtLeast(0)
        var end = line.endChar.coerceAtMost(text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end <= start) return null
        return ReaderMark(
            bookTitle = book.title(),
            chapterIndex = line.index,
            start = start,
            end = end,
            text = text.subSequence(start, end).toString()
        )
    }

    /**
     * Se llama al salir del lector. Si el TTS está leyendo (o en pausa), subraya la frase actual,
     * sustituyendo al subrayado anterior, y mantiene el subrayado al día mientras el TTS siga
     * leyendo fuera del lector. Si el TTS está parado no hace nada.
     */
    fun saveTTSProgressAsUserMark() {
        if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) return
        followTTSWithMark = true
        ttsProgressMark?.let { storeUserMark(it) }
    }

    /** Borra el subrayado de forma permanente. */
    fun clearUserMark() {
        removeKey(EPUB_USER_MARK, book.title())
        userMarkValue = null
        _userMark.postValue(null)
    }

    /**
     * Borra el subrayado solo si sigue siendo [mark]. Lo usa el hilo del TTS: si mientras leía el
     * usuario ha subrayado otra frase, no hay que llevarse por delante la nueva.
     */
    private fun clearUserMarkIf(mark: ReaderMark) {
        if (userMarkValue == mark) clearUserMark()
    }

    /**
     * Rango (inicio, fin exclusivo) del subrayado dentro del texto de [span], o null si no aplica.
     * Se comprueba que el texto coincida: si los offsets ya no son válidos (traducción, texto
     * original, cambio de sustituciones...) la marca simplemente no se pinta, pero NO se borra.
     */
    fun userMarkRangeFor(span: TextSpan): Pair<Int, Int>? {
        val mark = userMarkValue ?: return null
        if (mark.chapterIndex != span.index) return null
        if (mark.start < span.start || mark.end > span.end) return null
        val localStart = mark.start - span.start
        val localEnd = mark.end - span.start
        if (localEnd > span.text.length) return null
        if (span.text.subSequence(localStart, localEnd).toString() != mark.text) return null
        return localStart to localEnd
    }

    /** ¿La selección [localStart, localEnd) de [span] toca el subrayado actual? */
    fun userMarkOverlaps(span: TextSpan, localStart: Int, localEnd: Int): Boolean {
        val range = userMarkRangeFor(span) ?: return false
        return localStart < range.second && localEnd > range.first
    }

    /** Dónde empieza/acaba la marca dentro del texto del capítulo tal y como lo lee el TTS. */
    private data class TTSMarkTarget(
        val stored: ReaderMark,
        val chapterIndex: Int,
        val start: Int,
        val end: Int
    )

    /** Busca [mark] en [text]: primero en sus offsets y, si ya no encaja, por su texto. */
    private fun locateMarkInText(mark: ReaderMark, text: CharSequence): Pair<Int, Int>? {
        if (mark.start >= 0 && mark.end <= text.length &&
            text.subSequence(mark.start, mark.end).toString() == mark.text
        ) {
            return mark.start to mark.end
        }

        // los offsets ya no valen: nos quedamos con la aparición del texto más cercana
        val str = text.toString()
        var best = -1
        var from = 0
        while (true) {
            val found = str.indexOf(mark.text, from)
            if (found == -1) break
            if (best == -1 || kotlin.math.abs(found - mark.start) < kotlin.math.abs(best - mark.start)) {
                best = found
            }
            from = found + 1
        }
        return if (best == -1) null else best to (best + mark.text.length)
    }

    /**
     * Si hay un subrayado, carga su capítulo (si hace falta) y devuelve dónde está para que el TTS
     * empiece por él. Devuelve null si no hay subrayado o ya no se encuentra en el texto.
     */
    private suspend fun resolveUserMarkForTTS(): TTSMarkTarget? {
        val stored = userMarkValue ?: return null

        // vuelve al instante si el capítulo ya está cargado o cargándose
        loadIndividualChapter(stored.chapterIndex)

        for (attempt in 0 until 300) { // ~30 s como máximo (una traducción puede tardar)
            when (val data = chapterMutex.withLock { chapterData[stored.chapterIndex] }) {
                is Resource.Success -> {
                    // el TTS lee siempre el texto "rendered" (traducido si hay traducción)
                    val (start, end) = locateMarkInText(stored, data.value.rendered) ?: return null
                    return TTSMarkTarget(stored, stored.chapterIndex, start, end)
                }

                is Resource.Loading -> {
                    if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) return null
                    delay(100)
                }

                else -> return null
            }
        }
        return null
    }

    // ========================================  TTS STUFF ========================================

    var ttsSession: TTSSession? = null

    private fun initTTSSession(context: Context) {
        runOnMainThread {
            ttsSession = TTSSession(context, ::parseAction)
        }
    }

    private var pendingTTSSkip: Int = 0
    private var _currentTTSStatus: TTSHelper.TTSStatus = TTSHelper.TTSStatus.IsStopped
    var currentTTSStatus: TTSHelper.TTSStatus
        get() = _currentTTSStatus
        set(value) = synchronized(this@ReadActivityViewModel) {
            playDummySound()
            if (_currentTTSStatus == TTSHelper.TTSStatus.IsStopped && value == TTSHelper.TTSStatus.IsRunning) {
                startTTSWorker()
            }

            _ttsStatus.postValue(value)
            _currentTTSStatus = value
            updateTTSNotification()
            updateTTSGlobalState()
        }

    fun updateTTSNotification(index: Int? = null) {
        val useIndex = index ?: currentIndex
        if (useIndex < 0 || useIndex >= chaptersTitlesInternal.size) return
        TTSNotifications.notify(
            book.title(),
            chaptersTitlesInternal[useIndex],
            book.poster(),
            currentTTSStatus,
            context
        )
    }

    /** Keeps TTSGlobalState (used by the library's mini-player) in sync with this session. */
    private fun updateTTSGlobalState(index: Int? = null) {
        if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) {
            TTSGlobalState.clear()
            return
        }
        val useIndex = index ?: currentIndex
        TTSGlobalState.update(
            book.title(),
            chaptersTitlesInternal.getOrNull(useIndex),
            currentTTSStatus,
            book.poster()
        )
    }

    fun stopTTS() {
        currentTTSStatus = TTSHelper.TTSStatus.IsStopped
    }

    fun setTTSLanguage(locale: Locale?) {
        ttsSession?.setLanguage(locale)
    }

    fun setTTSVoice(voice: Voice?) {
        ttsSession?.setVoice(voice)
    }

    fun pauseTTS() {
        val ttsSession = ttsSession ?: return
        if (!ttsSession.ttsInitialized()) return
        if (currentTTSStatus == TTSHelper.TTSStatus.IsRunning) {
            currentTTSStatus = TTSHelper.TTSStatus.IsPaused
        }
    }

    fun startTTS() {
        currentTTSStatus = TTSHelper.TTSStatus.IsRunning
    }

    fun forwardsTTS() {
        val ttsSession = ttsSession ?: return
        if (!ttsSession.ttsInitialized()) return
        pendingTTSSkip += 1
        // if we were paused, pressing forward should also resume playback
        if (currentTTSStatus == TTSHelper.TTSStatus.IsPaused) {
            currentTTSStatus = TTSHelper.TTSStatus.IsRunning
        }
    }

    fun backwardsTTS() {
        val ttsSession = ttsSession ?: return
        if (!ttsSession.ttsInitialized()) return
        pendingTTSSkip -= 1
        // if we were paused, pressing backwards should also resume playback
        if (currentTTSStatus == TTSHelper.TTSStatus.IsPaused) {
            currentTTSStatus = TTSHelper.TTSStatus.IsRunning
        }
    }

    fun playTTS() {
        currentTTSStatus = TTSHelper.TTSStatus.IsRunning
    }

    fun pausePlayTTS() {
        if (currentTTSStatus == TTSHelper.TTSStatus.IsRunning) {
            currentTTSStatus = TTSHelper.TTSStatus.IsPaused
        } else if (currentTTSStatus == TTSHelper.TTSStatus.IsPaused) {
            currentTTSStatus = TTSHelper.TTSStatus.IsRunning
        }
    }

    fun isTTSRunning(): Boolean {
        return currentTTSStatus == TTSHelper.TTSStatus.IsRunning
    }


    private val ttsThreadMutex = Mutex()

    fun startTTSWorker() = ioSafe {
        TTSNotificationService.start(this@ReadActivityViewModel, context ?: return@ioSafe)
    }

    suspend fun startTTSThread() = coroutineScope {
        val ttsSession = ttsSession ?: return@coroutineScope
        try {
            val ttsStartTime = System.currentTimeMillis()
            var ttsEndTime = ttsStartTime + ttsTimer
            val ttsHasTimer = ttsEndTime > ttsStartTime

            val dIndex = desiredTTSIndex ?: desiredIndex ?: return@coroutineScope

            if (ttsThreadMutex.isLocked) return@coroutineScope
            ttsThreadMutex.withLock {
                ttsSession.register()
                ttsSession.setSpeed(ttsSpeed)
                ttsSession.setPitch(ttsPitch)

                var ttsInnerIndex = 0 // this inner index is different from what is set
                var index = dIndex.index

                // Si el usuario dejó una frase subrayada, el TTS empieza exactamente por ella y,
                // cuando termina de leerla, el subrayado se borra de forma permanente.
                var markTarget: TTSMarkTarget? = resolveUserMarkForTTS()
                // texto "rendered" del capítulo que se está leyendo (los offsets de las líneas son suyos)
                var currentRendered: CharSequence = ""
                // última marca de progreso escrita por el TTS mientras se estaba fuera del lector
                var followedMark: ReaderMark? = null

                let {
                    val target = markTarget
                    if (target != null) index = target.chapterIndex
                    val startChar = target?.start ?: dIndex.char

                    val lines = chapterMutex.withLock {
                        chapterData[index].letInner {
                            it.ttsLines
                        }
                    } ?: run {
                        // in case of error just go to the next chapter
                        index++
                        return@let
                    }

                    // La selección puede empezar a mitad de frase: con la marca partimos de la
                    // línea que CONTIENE su inicio en vez de saltar a la siguiente.
                    val idx = if (target != null) {
                        lines.indexOfFirst { it.endChar > startChar }
                    } else {
                        lines.indexOfFirst { it.startChar >= startChar }
                    }
                    if (idx != -1) {
                        ttsInnerIndex = idx
                    } else {
                        // In case we are at the very last thing, then goto the next chapter
                        index++
                    }
                }

                loadIndividualChapter(index)
                while (isActive && currentTTSStatus != TTSHelper.TTSStatus.IsStopped) {
                    val lines =
                        when (val currentData = chapterMutex.withLock { chapterData[index] }) {
                            null -> {
                                showToast(R.string.got_null_data)
                                break
                            }

                            is Resource.Failure -> {
                                showToast(currentData.errorString)
                                break
                            }

                            is Resource.Loading -> {
                                if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break
                                delay(100)
                                continue
                            }

                            is Resource.Success -> {
                                currentRendered = currentData.value.rendered
                                currentData.value.ttsLines
                            }
                        }

                    fun notify() {
                        TTSNotifications.notify(
                            book.title(),
                            chaptersTitlesInternal[index],
                            book.poster(),
                            currentTTSStatus,
                            context
                        )
                        updateTTSGlobalState(index)
                    }
                    notify()

                    // this is because if you go back one line you will be on the previous chapter with
                    // a negative innerIndex, this makes the wrapping good
                    if (ttsInnerIndex < 0) {
                        ttsInnerIndex += lines.size
                    }

                    updateIndex(index)

                    //preload next chapter
                    viewModelScope.launch(Dispatchers.IO) {
                        val exists =
                            chapterMutex.withLock { chapterData[index + 1] is Resource.Success }
                        if (!exists)
                            loadIndividualChapter(index + 1)
                    }
                    // speak all lines
                    while (ttsInnerIndex < lines.size && ttsInnerIndex >= 0) {
                        ensureActive()

                        // auto stop
                        val currentTimeRemaining = ttsEndTime - System.currentTimeMillis()
                        if (ttsHasTimer) {
                            if (currentTimeRemaining < 0) {
                                currentTTSStatus = TTSHelper.TTSStatus.IsStopped
                            } else {
                                ttsTimeRemaining.postValue(currentTimeRemaining)
                            }
                        }

                        if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break

                        val line = lines[ttsInnerIndex]
                        val nextLine = lines.getOrNull(ttsInnerIndex + 1)

                        // set keys
                        /*setKey(
                            EPUB_CURRENT_POSITION_SCROLL_CHAR,
                            book.title(),
                            line.startChar
                        )
                        setKey(EPUB_CURRENT_POSITION, book.title(), line.index)*/

                        // if we are outside the app, then we post new desired location
                        // as otherwise the scroll overrides it
                        // this is done to scroll to latest when we go back to the app
                        if (!isInApp) {
                            innerCharToIndex(index, line.startChar)?.let {
                                changeIndex(
                                    ScrollIndex(
                                        index,
                                        it,
                                        line.startChar
                                    ), alsoTitle = false
                                )
                            }
                        }


                        // progreso: esta frase es por donde va la lectura. Si el usuario está fuera
                        // del lector, además se subraya para que quede guardado.
                        val progress = markForTTSLine(line, currentRendered)
                        ttsProgressMark = progress
                        if (progress != null && followTTSWithMark) {
                            storeUserMark(progress)
                            followedMark = progress
                        }

                        // post visual
                        _ttsLine.postValue(line)

                        // wait for next line
                        val waitFor = ttsSession.speak(
                            line,
                            nextLine
                        ) {
                            currentTTSStatus != TTSHelper.TTSStatus.IsRunning || pendingTTSSkip != 0
                        }

                        if (!ttsSession.isValidTTS()) {
                            currentTTSStatus = TTSHelper.TTSStatus.IsStopped
                        }

                        ttsSession.waitForOr(waitFor, {
                            currentTTSStatus != TTSHelper.TTSStatus.IsRunning || pendingTTSSkip != 0
                        }) {
                            notify()
                        }

                        // wait for pause
                        var isPauseDuration = 0L
                        while (currentTTSStatus == TTSHelper.TTSStatus.IsPaused) {
                            isPauseDuration++
                            delay(100)
                        }

                        // do not count in tts sleep
                        ttsEndTime += 100L * isPauseDuration

                        // if we pause then we resume on the same line, unless a forward/backward
                        // was pressed while paused, in which case honor that skip below
                        if (isPauseDuration > 0 && pendingTTSSkip == 0) {
                            notify()
                            continue
                        }

                        if (followTTSWithMark) {
                            // el subrayado lo lleva el progreso, no hay que borrar el original
                            markTarget = null
                        } else {
                            // el usuario volvió al lector con el TTS en marcha: el subrayado de
                            // progreso pasa a ser uno normal y se borra al dejar atrás su frase
                            followedMark?.let {
                                markTarget = TTSMarkTarget(it, it.chapterIndex, it.start, it.end)
                                followedMark = null
                            }
                        }

                        // Dejamos atrás la última frase de la marca (por haberla leído entera o
                        // porque el usuario ha saltado hacia delante): se borra para siempre.
                        // Retroceder no la borra, ya que se vuelve a leer. Tampoco si el usuario
                        // ha parado el TTS a mitad de frase (waitForOr también retorna entonces).
                        markTarget?.let { target ->
                            val movingForward = pendingTTSSkip >= 0 &&
                                    currentTTSStatus != TTSHelper.TTSStatus.IsStopped
                            if (movingForward && index == target.chapterIndex &&
                                (nextLine == null || nextLine.startChar >= target.end)
                            ) {
                                clearUserMarkIf(target.stored)
                                markTarget = null
                            }
                        }

                        if (pendingTTSSkip != 0) {
                            ttsInnerIndex += pendingTTSSkip
                            pendingTTSSkip = 0
                        } else {
                            ttsInnerIndex += 1
                        }
                    }
                    if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break

                    // this may case a bug where you cant seek back if the entire chapter is none
                    // but this is better than restarting the chapter
                    if (ttsInnerIndex > 0 || lines.isEmpty()) {
                        // goto next chapter and set inner to 0
                        index++
                        ttsInnerIndex = 0
                    } else if (index > 0) {
                        index--
                    } else {
                        ttsInnerIndex = 0
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {

        } catch (t: Throwable) {
            logError(t)
        } finally {
            currentTTSStatus = TTSHelper.TTSStatus.IsStopped
            TTSNotifications.notify(
                book.title(),
                "".toUiText(),
                book.poster(),
                TTSHelper.TTSStatus.IsStopped,
                context
            )
            ttsSession.interruptTTS()
            ttsSession.unregister()
            ttsProgressMark = null
            followTTSWithMark = false
            _ttsLine.postValue(null)
            ttsTimeRemaining.postValue(null)
        }
    }

    fun parseAction(input: TTSHelper.TTSActionType): Boolean {
        val ttsSession = ttsSession ?: return false

        // validate that the action makes sense
        if (
            (currentTTSStatus == TTSHelper.TTSStatus.IsPaused && input == TTSHelper.TTSActionType.Pause) ||
            (currentTTSStatus != TTSHelper.TTSStatus.IsPaused && input == TTSHelper.TTSActionType.Resume) ||
            (currentTTSStatus == TTSHelper.TTSStatus.IsStopped && input == TTSHelper.TTSActionType.Stop) ||
            (currentTTSStatus != TTSHelper.TTSStatus.IsRunning && input == TTSHelper.TTSActionType.Next)
        ) {
            return false
        }

        if (!ttsSession.ttsInitialized()) return false

        when (input) {
            TTSHelper.TTSActionType.Pause -> pauseTTS()
            TTSHelper.TTSActionType.Resume -> startTTS()
            TTSHelper.TTSActionType.Stop -> stopTTS()
            TTSHelper.TTSActionType.Next -> forwardsTTS()
        }

        return true
    }

    fun innerCharToIndex(index: Int, char: Int): Int? {
        // the lock is so short it does not matter I *hope*
        return runBlocking {
            chapterMutex.withLock { chapterData[index] }?.letInner { live ->
                // todo binary search, but strip all but TextSpan first
                live.spans.firstOrNull { it.start >= char }?.innerIndex
            }
        }
    }

    /** sets the metadata and global vars used as well as keys */
    private var lastChangeIndex: ScrollIndex? = null
    private var lastScrollMs: Long = 0
    private fun changeIndex(scrollIndex: ScrollIndex, alsoTitle: Boolean = true) {
        if (alsoTitle) {
            _chapterTile.postValue(chaptersTitlesInternal[scrollIndex.index])
        }

        desiredIndex = scrollIndex
        currentIndex = scrollIndex.index

        // the majority of the time is spent on setKey, and because this is called from onscroll
        // this fixes lag
        lastChangeIndex = scrollIndex
        if (System.currentTimeMillis() > lastScrollMs + 200L) {
            lastScrollMs = System.currentTimeMillis()
            setScrollKeys(scrollIndex)
        }
    }

    private fun setScrollKeys(scrollIndex: ScrollIndex) {
        setKey(
            EPUB_CURRENT_POSITION_READ_AT,
            "${book.title()}/${scrollIndex.index}",
            System.currentTimeMillis()
        )

        setKey(
            EPUB_CURRENT_POSITION_SCROLL_CHAR,
            book.title(),
            scrollIndex.char
        )
        setKey(EPUB_CURRENT_POSITION, book.title(), scrollIndex.index)
        context?.let {
            setKey(
                EPUB_CURRENT_POSITION_CHAPTER,
                book.title(),
                book.getChapterTitle(scrollIndex.index).asString(it)
            )
        }
    }

    fun scrollToDesired(scrollIndex: ScrollIndex) {
        changeIndex(scrollIndex)
        updateReadArea(seekToDesired = true)
    }

    fun nextChapter() {
        seekToChapter(currentIndex + 1)
    }

    fun prevChapter() {
        seekToChapter(currentIndex - 1)
    }

    fun seekToChapter(index: Int) = ioSafe {
        // sanity check
        if (index < 0 || index >= book.size()) return@ioSafe

        // we wont allow chapter switching and tts at the same time, stop it
        if (currentTTSStatus != TTSHelper.TTSStatus.IsStopped) {
            currentTTSStatus = TTSHelper.TTSStatus.IsStopped
        }

        // set loading
        _loadingStatus.postValue(Resource.Loading())

        // load the chapters
        updateIndexAsync(index, notify = false, postLoading = true)
        // set the keys
        setKey(EPUB_CURRENT_POSITION, book.title(), index)
        setKey(EPUB_CURRENT_POSITION_SCROLL_CHAR, book.title(), 0)

        // set the state
        desiredIndex = ScrollIndex(index, 0, 0)
        currentIndex = index
        desiredTTSIndex = ScrollIndex(index, 0, 0)

        // push the update
        updateReadArea(seekToDesired = true)
        // update the view
        _chapterTile.postValue(chaptersTitlesInternal[index])
        //_loadingStatus.postValue(Resource.Success(true))
    }

    /*private fun changeIndex(index: Int, updateArea: Boolean = true) {
        val realNewIndex = minOf(index, book.size() - 1)
        if (currentIndex == realNewIndex) return
        setKey(EPUB_CURRENT_POSITION, book.title(), realNewIndex)
        currentIndex = realNewIndex
        if (updateArea) updateReadArea()
        _chapterTile.postValue(chaptersTitlesInternal[realNewIndex])
    }*/



    // FUCK ANDROID WITH ALL MY HEART
    // SEE https://stackoverflow.com/questions/45960265/android-o-oreo-8-and-higher-media-buttons-issue WHY
    private fun playDummySound() {
        val ctx = context ?: return
        val mMediaPlayer: MediaPlayer = MediaPlayer.create(ctx, R.raw.dummy_sound_500ms)
        mMediaPlayer.setOnCompletionListener { mMediaPlayer.release() }
        mMediaPlayer.start()
    }

    override fun onCleared() {
        println("onCleared===${System.currentTimeMillis()}")
        lastChangeIndex?.let { setScrollKeys(it) }
        ttsSession?.release()
        ttsSession = null
        mlTranslator?.close()
        mlTranslator = null
        super.onCleared()
    }


    private var readerTypeInternal by PreferenceDelegate(
        EPUB_READER_TYPE,
        ReadingType.DEFAULT.prefValue,
        Int::class
    )

    var readerType
        get() = ReadingType.fromSpinner(readerTypeInternal)
        set(value) {
            readerTypeInternal = value.prefValue
            updateReadArea(seekToDesired = true)
        }


    var scrollWithVolume by PreferenceDelegate(EPUB_SCROLL_VOL, true, Boolean::class)
    var authorNotes by PreferenceDelegate(EPUB_AUTHOR_NOTES, true, Boolean::class)
    var ttsLock by PreferenceDelegate(EPUB_TTS_LOCK, true, Boolean::class)
    //var ttsOSSpeed by PreferenceDelegate(EPUB_TTS_OS_SPEED, true, Boolean::class)

    private var ttsSpeedKey by PreferenceDelegate(EPUB_TTS_SET_SPEED, 1.0f, Float::class)
    private var ttsPitchKey by PreferenceDelegate(EPUB_TTS_SET_PITCH, 1.0f, Float::class)

    var ttsSpeed: Float
        get() = ttsSpeedKey
        set(value) {
            ttsSession?.setSpeed(value)
            ttsSpeedKey = value
        }

    var ttsPitch: Float
        get() = ttsPitchKey
        set(value) {
            ttsSession?.setPitch(value)
            ttsPitchKey = value
        }


    val textFontLive: MutableLiveData<String> = MutableLiveData(null)
    var textFont by PreferenceDelegateLiveView(EPUB_FONT, "", String::class, textFontLive)
    val textSizeLive: MutableLiveData<Int> = MutableLiveData(null)
    var textSize by PreferenceDelegateLiveView(
        EPUB_TEXT_SIZE,
        DEF_FONT_SIZE,
        Int::class,
        textSizeLive
    )

    val bionicReadingLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var bionicReading by PreferenceDelegateLiveView(
        EPUB_TEXT_BIONIC,
        false,
        Boolean::class,
        bionicReadingLive
    )

    val isTextSelectableLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var isTextSelectable by PreferenceDelegateLiveView(
        EPUB_TEXT_SELECTABLE,
        false,
        Boolean::class,
        isTextSelectableLive
    )

    val orientationLive: MutableLiveData<Int> = MutableLiveData(null)
    var orientation by PreferenceDelegateLiveView(
        EPUB_LOCK_ROTATION,
        OrientationType.DEFAULT.prefValue,
        Int::class, orientationLive
    )

    val textColorLive: MutableLiveData<Int> = MutableLiveData(null)
    var textColor by PreferenceDelegateLiveView(
        EPUB_TEXT_COLOR, "#cccccc".toColorInt(), Int::class, textColorLive
    )

    val textVerticalPaddingLive: MutableLiveData<Float> = MutableLiveData(null)
    var textVerticalPadding by PreferenceDelegateLiveView(
        EPUB_TEXT_VERTICAL_PADDING, 7.5f, Float::class, textVerticalPaddingLive
    )

    val backgroundColorLive: MutableLiveData<Int> = MutableLiveData(null)
    var backgroundColor by PreferenceDelegateLiveView(
        EPUB_BG_COLOR, "#292832".toColorInt(), Int::class, backgroundColorLive
    )

    val showBatteryLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var showBattery by PreferenceDelegateLiveView(
        EPUB_HAS_BATTERY, true, Boolean::class, showBatteryLive
    )

    val showTimeLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var showTime by PreferenceDelegateLiveView(
        EPUB_HAS_TIME, true, Boolean::class, showTimeLive
    )

    val paddingHorizontalLive: MutableLiveData<Int> = MutableLiveData(null)
    var paddingHorizontal by PreferenceDelegateLiveView(
        EPUB_TEXT_PADDING, DEF_HORIZONTAL_PAD, Int::class, paddingHorizontalLive
    )

    val paddingVerticalLive: MutableLiveData<Int> = MutableLiveData(null)
    var paddingVertical by PreferenceDelegateLiveView(
        EPUB_TEXT_PADDING_TOP, DEF_VERTICAL_PAD, Int::class, paddingVerticalLive
    )

    //val time12HLive: MutableLiveData<Boolean> = MutableLiveData(null)
    //var time12H by PreferenceDelegateLiveView(
    //    EPUB_TWELVE_HOUR_TIME, false, Boolean::class, time12HLive
    //)

    val screenAwakeLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var screenAwake by PreferenceDelegateLiveView(
        EPUB_KEEP_SCREEN_ACTIVE, true, Boolean::class, screenAwakeLive
    )

    // in milliseconds
    val ttsTimerLive: MutableLiveData<Long> = MutableLiveData(null)
    var ttsTimer by PreferenceDelegateLiveView(
        EPUB_SLEEP_TIMER, 0, Long::class, ttsTimerLive
    )

    val ttsTimeRemaining: MutableLiveData<Long?> = MutableLiveData(null)

    val mlFromLanguageLive: MutableLiveData<String> = MutableLiveData(null)
    var mlFromLanguage by PreferenceDelegateLiveView(
        EPUB_ML_FROM_LANGUAGE,
        TranslateLanguage.ENGLISH,
        String::class,
        mlFromLanguageLive
    )

    val mlToLanguageLive: MutableLiveData<String> = MutableLiveData(null)
    var mlToLanguage by PreferenceDelegateLiveView(
        EPUB_ML_TO_LANGUAGE,
        TranslateLanguage.ENGLISH,
        String::class,
        mlToLanguageLive
    )

    val mlUseOnlineTransaltionLive: MutableLiveData<Boolean> = MutableLiveData(false)
    var mlUseOnlineTransaltion by PreferenceDelegateLiveView(
        EPUB_ML_USEONLINETRANSLATION,
        false,
        Boolean::class,
        mlUseOnlineTransaltionLive
    )

    val mlUseAiTranslationLive: MutableLiveData<Boolean> = MutableLiveData(false)
    var mlUseAiTranslation by PreferenceDelegateLiveView(
        EPUB_ML_USEAITRANSLATION,
        false,
        Boolean::class,
        mlUseAiTranslationLive
    )

    val mlUseDeepLTranslationLive: MutableLiveData<Boolean> = MutableLiveData(false)
    var mlUseDeepLTranslation by PreferenceDelegateLiveView(
        EPUB_ML_USEDEEPLTRANSLATION,
        false,
        Boolean::class,
        mlUseDeepLTranslationLive
    )

    val mlDeepLApiKeyLive: MutableLiveData<String> = MutableLiveData("")
    var mlDeepLApiKey by PreferenceDelegateLiveView(
        EPUB_ML_DEEPL_API_KEY,
        "",
        String::class,
        mlDeepLApiKeyLive
    )

    val mlAiApiKeyLive: MutableLiveData<String> = MutableLiveData("")
    var mlAiApiKey by PreferenceDelegateLiveView(
        EPUB_ML_AI_API_KEY,
        "",
        String::class,
        mlAiApiKeyLive
    )

    val mlAiModelLive: MutableLiveData<String> = MutableLiveData("")
    var mlAiModel by PreferenceDelegateLiveView(
        EPUB_ML_AI_MODEL,
        "gemini-1.5-flash",
        String::class,
        mlAiModelLive
    )

    val mlAiPromptLive: MutableLiveData<String> = MutableLiveData("")
    var mlAiPrompt by PreferenceDelegateLiveView(
        EPUB_ML_AI_PROMPT,
        "",
        String::class,
        mlAiPromptLive
    )

    // Thinking level for models that support it (Gemini 3.x uses "LOW"/"MEDIUM"/"HIGH" directly,
    // Gemini 2.5 maps these to an equivalent token budget). "AUTO" leaves the field out entirely
    // so the model uses its own default, "OFF" disables thinking where possible.
    val mlAiThinkingLevelLive: MutableLiveData<String> = MutableLiveData(GeminiThinkingLevel.AUTO.name)
    var mlAiThinkingLevel by PreferenceDelegateLiveView(
        EPUB_ML_AI_THINKING_LEVEL,
        GeminiThinkingLevel.AUTO.name,
        String::class,
        mlAiThinkingLevelLive
    )

    val mlAiTemperatureLive: MutableLiveData<Float> = MutableLiveData(0.1f)
    var mlAiTemperature by PreferenceDelegateLiveView(
        EPUB_ML_AI_TEMPERATURE,
        0.1f,
        Float::class,
        mlAiTemperatureLive
    )

    val mlAiTopPLive: MutableLiveData<Float> = MutableLiveData(0.95f)
    var mlAiTopP by PreferenceDelegateLiveView(
        EPUB_ML_AI_TOP_P,
        0.95f,
        Float::class,
        mlAiTopPLive
    )

    val mlAiTopKLive: MutableLiveData<Int> = MutableLiveData(40)
    var mlAiTopK by PreferenceDelegateLiveView(
        EPUB_ML_AI_TOP_K,
        40,
        Int::class,
        mlAiTopKLive
    )

    // Client-side throttle: maximum number of requests sent to the primary AI model within
    // any rolling 60 second window. Once reached, new requests are routed to mlAiBackupModel
    // (if configured) instead of waiting, so translation can keep going uninterrupted.
    val mlAiMaxRequestsPerMinuteLive: MutableLiveData<Int> = MutableLiveData(15)
    var mlAiMaxRequestsPerMinute by PreferenceDelegateLiveView(
        EPUB_ML_AI_MAX_RPM,
        15,
        Int::class,
        mlAiMaxRequestsPerMinuteLive
    )

    // Backup model used automatically once mlAiMaxRequestsPerMinute is reached for the primary
    // model. Manually typed, exactly like mlAiModel. Left blank to disable the fallback.
    val mlAiBackupModelLive: MutableLiveData<String> = MutableLiveData("")
    var mlAiBackupModel by PreferenceDelegateLiveView(
        EPUB_ML_AI_BACKUP_MODEL,
        "",
        String::class,
        mlAiBackupModelLive
    )

    // How much text (in characters) is sent to the model per request. The old hardcoded 5000
    // was very conservative for modern large-context/large-output models. 20000 is a safer
    // default; models with a bigger output budget (e.g. 65,536 tokens) can raise this in
    // Settings for far fewer requests. See GeminiTranslate.chunkByParagraphs for the reasoning.
    val mlAiMaxCharsPerRequestLive: MutableLiveData<Int> = MutableLiveData(20000)
    var mlAiMaxCharsPerRequest by PreferenceDelegateLiveView(
        EPUB_ML_AI_MAX_CHARS_PER_REQUEST,
        20000,
        Int::class,
        mlAiMaxCharsPerRequestLive
    )

    /*
   // Moved up to ensure correct initialization order. Having it lower caused a race condition  // where the default 'false' value was loaded before the actual saved preference.
    var mlSettings
        get() = getKey<MLSettings>(EPUB_CURRENT_ML, book.title()) ?: MLSettings("en", "en", false)
        set(value) = setKey(EPUB_CURRENT_ML, book.title(), value)

        */

    data class MLSettings(
        @JsonProperty("from")
        val from: String,
        @JsonProperty("to")
        val to: String,
        @JsonProperty("useOnlineTranslation")
        val useOnlineTranslation: Boolean = false,
        @JsonProperty("useAiTranslation")
        val useAiTranslation: Boolean = false,
        @JsonProperty("useDeepLTranslation")
        val useDeepLTranslation: Boolean = false
    ) {
        companion object {
            val map = mapOf(
                "af" to "Afrikaans",
                "ar" to "Arabic",
                "be" to "Belarusian",
                "bg" to "Bulgarian",
                "bn" to "Bengali",
                "ca" to "Catalan",
                "cs" to "Czech",
                "cy" to "Welsh",
                "da" to "Danish",
                "de" to "German",
                "el" to "Greek",
                "en" to "English",
                "eo" to "Esperanto",
                "es" to "Spanish",
                "et" to "Estonian",
                "fa" to "Persian",
                "fi" to "Finnish",
                "fr" to "French",
                "ga" to "Irish",
                "gl" to "Galician",
                "gu" to "Gujarati",
                "he" to "Hebrew",
                "hi" to "Hindi",
                "hr" to "Croatian",
                "ht" to "Haitian",
                "hu" to "Hungarian",
                "id" to "Indonesian",
                "is" to "Icelandic",
                "it" to "Italian",
                "ja" to "Japanese",
                "ka" to "Georgian",
                "kn" to "Kannada",
                "ko" to "Korean",
                "lt" to "Lithuanian",
                "lv" to "Latvian",
                "mk" to "Macedonian",
                "mr" to "Marathi",
                "ms" to "Malay",
                "mt" to "Maltese",
                "nl" to "Dutch",
                "no" to "Norwegian",
                "pl" to "Polish",
                "pt" to "Portuguese",
                "ro" to "Romanian",
                "ru" to "Russian",
                "sk" to "Slovak",
                "sl" to "Slovenian",
                "sq" to "Albanian",
                "sv" to "Swedish",
                "sw" to "Swahili",
                "ta" to "Tamil",
                "te" to "Telugu",
                "th" to "Thai",
                "tl" to "Tagalog",
                "tr" to "Turkish",
                "uk" to "Ukrainian",
                "ur" to "Urdu",
                "vi" to "Vietnamese",
                "zh" to "Chinese",
            )

            val list = map.toList()

            fun fromShortToDisplay(from: String): String {
                return map[from] ?: "Unknown"
            }
        }

        val fromDisplay get() = fromShortToDisplay(from)
        val toDisplay get() = fromShortToDisplay(to)

        fun isInvalid(): Boolean = !isValid()

        fun isValid(): Boolean {
            if (from.isBlank() || to.isBlank()) {
                // nonsense
                return false
            }

            val all = TranslateLanguage.getAllLanguages()

            if (useOnlineTranslation || useAiTranslation || useDeepLTranslation) return true

            if (!all.contains(to)) {
                // no translation
                return false
            }

            if (!all.contains(from)) {
                // no support for auto yet, see https://developers.google.com/ml-kit/language/identification/android
                return false
            }

            if (from == to) {
                // identity function
                return false
            }

            return true
        }
    }
}
