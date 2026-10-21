package com.lagradost.quicknovel

import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.mvvm.safeApiCall
import com.lagradost.quicknovel.util.Coroutines.threadSafeListOf
import com.lagradost.quicknovel.util.GoogleTranslateOnline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

data class OnGoingSearch(
    val apiName: String,
    val data: Resource<List<SearchResponse>>
)

// This function is somewhat like preParseHtml
private fun String?.removeAds(): String? {
    if (this.isNullOrBlank()) return null
    return try {
        val document = Jsoup.parse(this)
        //document.select("style").remove() // Style might be good, but is removed in the internal reader
        document.select("small.ads-title").remove()
        document.select("script").remove()
        document.select("iframe").remove()
        document.select(".adsbygoogle").remove()

        // Remove aside https://html.spec.whatwg.org/multipage/sections.html#the-aside-element?
        // https://stackoverflow.com/questions/14384431/html-element-for-ad

        document.html()
    } catch (t : Throwable) {
        logError(t)
        this
    }
}

class APIRepository(val api: MainAPI) {
    val unixTime: Long
        get() = System.currentTimeMillis() / 1000L

    companion object {
        var providersActive = HashSet<String>()

        data class SavedLoadResponse(
            val unixTime: Long,
            val response: LoadResponse,
            val hash: Pair<String, String>
        )

        private val cache = threadSafeListOf<SavedLoadResponse>()
        private var cacheIndex: Int = 0
        const val cacheSize = 20

        // 10min cache time should be plenty per session without fucking up anything for the user with outdated data
        const val cacheTimeSec: Int = 60 * 10

        // Peticiones simultáneas a Google Translate. Los metadatos son 1-2 trozos; los nombres de
        // capítulo pueden ser decenas, por eso se hacen en paralelo (y fuera de la ficha).
        private const val METADATA_TRANSLATION_PARALLELISM = 3
        private const val CHAPTER_TRANSLATION_PARALLELISM = 4

        // Scope de aplicación: la traducción de capítulos sigue aunque se cierre la ficha, así
        // queda ya traducida en la caché si se vuelve a abrir.
        private val translationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val name: String get() = api.name
    val mainUrl: String get() = api.mainUrl
    val hasReviews: Boolean get() = api.hasReviews
    val rateLimitTime: Long get() = api.rateLimitTime
    val hasMainPage: Boolean get() = api.hasMainPage

    val iconId: Int? get() = api.iconId
    val iconBackgroundId: Int get() = api.iconBackgroundId
    val mainCategories: List<Pair<String, String>> get() = api.mainCategories
    val orderBys: List<Pair<String, String>> get() = api.orderBys
    val tags: List<Pair<String, String>> get() = api.tags


    /**
     * @param awaitChapterTranslation si es `false`, no se espera a que se traduzcan los nombres de
     * capítulo: la ficha se devuelve en cuanto está lista y la traducción continúa en segundo plano
     * (ver [StreamResponse.chapterNamesTranslation]). Con `true` (por defecto) el resultado ya
     * trae los capítulos traducidos, como antes.
     */
    suspend fun load(
        url: String,
        allowCache: Boolean = true,
        awaitChapterTranslation: Boolean = true,
    ): Resource<LoadResponse> {
        val result = safeApiCall {
            try {
                if (api.hasRateLimit) {
                    api.rateLimitMutex.lock()
                }
                val fixedUrl = api.fixUrl(url)
                val lookingForHash = api.name to fixedUrl

                if (allowCache) {
                    synchronized(cache) {
                        for (item in cache) {
                            // 10 min save
                            if (item.hash == lookingForHash && (unixTime - item.unixTime) < cacheTimeSec) {
                                return@safeApiCall item.response
                            }
                        }
                    }
                }

                api.load(fixedUrl)?.also { response ->
                    // Título, sinopsis y etiquetas: una petición pequeña y necesaria ya, porque el
                    // título traducido forma parte del id de la novela.
                    translateMetadata(response)

                    // Los nombres de capítulo pueden ser miles: se traducen aparte, sin bloquear.
                    (response as? StreamResponse)?.let { stream ->
                        stream.chapterNamesTranslation = startChapterTranslation(stream)
                    }

                    // Remove all blank tags as early as possible
                    val add = SavedLoadResponse(unixTime, response, lookingForHash)
                    if (allowCache) {
                        synchronized(cache) {
                            if (cache.size > cacheSize) {
                                cache[cacheIndex] = add // rolling cache
                                cacheIndex = (cacheIndex + 1) % cacheSize
                            } else {
                                cache.add(add)
                            }
                        }
                    }
                } ?: throw ErrorLoadingException("No data")
            } finally {
                if (api.hasRateLimit) {
                    api.rateLimitMutex.unlock()
                }
            }
        }

        if (awaitChapterTranslation && result is Resource.Success) {
            (result.value as? StreamResponse)?.chapterNamesTranslation?.await()
        }
        return result
    }

    /** Traduce título, sinopsis y etiquetas al español (si la fuente no está ya en español). */
    private suspend fun translateMetadata(response: LoadResponse) {
        if (api.lang == "es") return

        // El título traducido forma parte del id de la novela y es lo que se muestra, pero para
        // buscarla en otros proveedores hace falta el original: se guarda antes de pisarlo.
        if (response.originalName == null) {
            response.originalName = response.name
        }

        try {
            val toTranslate = mutableListOf<String>()
            toTranslate.add(response.name)
            response.synopsis?.let { toTranslate.add(it) }
            val tags = response.tags
            if (tags != null) toTranslate.addAll(tags)

            val translated = GoogleTranslateOnline.onlineTranslate(
                toTranslate,
                api.lang,
                "es",
                parallelism = METADATA_TRANSLATION_PARALLELISM
            )

            if (translated.size == toTranslate.size) {
                var index = 0
                response.name = translated[index++]
                if (response.synopsis != null) {
                    response.synopsis = translated[index++]
                }
                if (tags != null) {
                    val newTags = mutableListOf<String>()
                    repeat(tags.size) {
                        newTags.add(translated[index++])
                    }
                    response.tags = newTags
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
        }
    }

    /**
     * Lanza en segundo plano la traducción de los nombres de capítulo al español.
     *
     * Los nombres se aplican en el hilo principal, que es donde la ficha usa esa lista, de una vez
     * y solo si se tradujeron todos (si no, se queda el original). Devuelve null si no hay nada
     * que traducir.
     *
     * Los enlaces abiertos desde la web conservan lang="es" para no cambiar el nombre de la ficha
     * ni romper la clave usada por el historial; sus capítulos se traducen aparte con detección
     * automática del idioma (chapterLang = "auto").
     */
    private fun startChapterTranslation(response: StreamResponse): Deferred<Boolean>? {
        val chapterLang = api.chapterLang ?: return null
        val chapters = response.data
        if (chapters.isEmpty()) return null
        val names = chapters.map { it.name }

        return translationScope.async {
            try {
                val translated = GoogleTranslateOnline.onlineTranslate(
                    names,
                    chapterLang,
                    "es",
                    parallelism = CHAPTER_TRANSLATION_PARALLELISM
                )
                if (translated.size != chapters.size) return@async false

                withContext(Dispatchers.Main) {
                    chapters.forEachIndexed { index, chapter ->
                        chapter.name = translated[index]
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
                false
            }
        }
    }

    suspend fun search(query: String): Resource<List<SearchResponse>> {
        return safeApiCall {
            api.search(query) ?: throw ErrorLoadingException("No data")
        }
    }

    /**
     * Automatically strips adsbygoogle
     * */
    suspend fun loadHtml(url: String, forceReload: Boolean = false): String? {
        return try {
            val fixed = api.fixUrl(url)
            (if (forceReload) api.reloadHtml(fixed) else api.loadHtml(fixed))?.removeAds()
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    suspend fun loadReviews(
        url: String,
        page: Int,
        showSpoilers: Boolean = false,
        data: String? = null
    ): Resource<List<UserReview>> {
        return safeApiCall {
            // Desde 3.8.0 los proveedores devuelven todas las reseñas marcando `containsSpoilers`
            val reviews = api.loadReviews(url, page, data)
            if (showSpoilers) reviews else reviews.filterNot { it.containsSpoilers }
        }
    }

    suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): Resource<HeadMainPageResponse> {
        return safeApiCall {
            api.loadMainPage(page, mainCategory, orderBy, tag)
        }
    }

}