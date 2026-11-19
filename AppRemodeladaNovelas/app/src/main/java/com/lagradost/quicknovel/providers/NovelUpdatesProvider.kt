package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.SearchFilter
import com.lagradost.quicknovel.SearchFilterOption
import com.lagradost.quicknovel.SearchFilterType
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.fixUrl
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.nicehttp.requestCreator
import com.lagradost.quicknovel.USER_AGENT
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.quicknovel.network.BackgroundWebView
import com.lagradost.quicknovel.network.LoginWebView
import com.lagradost.quicknovel.network.WebViewResolver
import com.lagradost.quicknovel.network.utils.CookiesUtils
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/**
 * NovelUpdates (https://www.novelupdates.com).
 *
 * NovelUpdates NO aloja los capítulos: es un índice de novelas traducidas por fans. Cada capítulo es
 * un enlace `/extnu/<id>/` que redirige a la web del traductor.
 *
 *  - Inicio   -> /series-ranking/?rank=<semana>   (solo el ranking semanal, "Activity (Week)")
 *  - Búsqueda -> /series-finder/?sf=1&...         (con los mismos filtros que el formulario web)
 *  - Ficha    -> HTML de /series/<slug>/ + lista de capítulos por admin-ajax.php (nd_getchapters)
 *  - Capítulo -> se sigue la redirección de /extnu/ y el texto se extrae de la web externa con el
 *                extractor genérico de [WebLinkProvider].
 *
 * Parámetros del finder contrastados con el plugin de NovelUpdates de LNReader y con el paquete
 * `novel-updates-scraper`.
 *
 * RED: las páginas se piden con el cliente normal (`MainActivity.app`, con las cookies y el
 * User-Agent del WebView) y NO con el interceptor de Cloudflare, que esperaba 60 s a una cookie
 * `cf_clearance` que no llega cuando Cloudflare no desafía al WebView (caso del ranking). Si la
 * página pedida no es la real (título sin "Novel Updates"), se usa [BackgroundWebView]: un WebView
 * montado en la ventana (sin verse), con el que el desafío Turnstile de las fichas se resuelve solo.
 *
 * CAPÍTULOS: `nd_getchapters` se pide SIN `mygrr`; con `mygrr=0` NovelUpdates responde "0".
 */
class NovelUpdatesProvider : MainAPI() {
    override val name = "NovelUpdates"
    override val mainUrl = "https://www.novelupdates.com"

    override val usesCloudFlareKiller = true
    override val rateLimitTime: Long = 1000L
    override val hasMainPage = true

    /** Si la petición directa fue rechazada, hasta cuándo no se vuelve a intentar. */
    @Volatile
    private var directBlockedUntil = 0L

    /** Última ficha abierta: es el `Referer` natural al abrir un capítulo. */
    @Volatile
    private var lastSeriesUrl: String? = null

    private fun dbg(message: String) {
        Log.d(TAG, message)
    }

    // El visor de reseñas de la app llama siempre a esto; sin implementarlo lanza NotImplementedError
    override suspend fun loadReviews(url: String, page: Int, data: String?): List<UserReview> =
        emptyList()

    // ---------------------------------------------------------------------------------------------
    // Inicio: solo el ranking semanal
    // ---------------------------------------------------------------------------------------------

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        val url = rankingUrl(WEEKLY_RANK, page)
        var list = parseSeriesRows(getDocument(url))

        if (list.isEmpty()) {
            // Si el valor de `rank` hubiese cambiado, se lee de la propia página el que corresponde
            // a "Activity (Week)" en vez de enseñar otro ranking.
            val discovered = findWeeklyRank(getDocument("$mainUrl/series-ranking/"))
            if (discovered != null && discovered != WEEKLY_RANK) {
                list = parseSeriesRows(getDocument(rankingUrl(discovered, page)))
            }
        }
        return HeadMainPageResponse(url, list)
    }

    private fun rankingUrl(rank: String, page: Int): String {
        val pg = if (page > 1) "&pg=$page" else ""
        return "$mainUrl/series-ranking/?rank=$rank$pg"
    }

    /** Busca en el selector "Ranking Type" la opción "Activity (Week)" y devuelve su `rank`. */
    private fun findWeeklyRank(document: Document): String? {
        val element = document.select("option, a").firstOrNull {
            it.text().trim().equals("Activity (Week)", ignoreCase = true)
        } ?: return null
        val raw = element.attr("value").ifBlank { element.attr("href") }
        return Regex("[?&]rank=([^&#]+)").find(raw)?.groupValues?.get(1)
            ?: raw.takeIf { it.isNotBlank() && !it.contains('/') }
    }

    // ---------------------------------------------------------------------------------------------
    // Búsqueda: series-finder con todos sus filtros
    // ---------------------------------------------------------------------------------------------

    override val searchFilters: List<SearchFilter> by lazy { buildSearchFilters() }

    override suspend fun search(query: String): List<SearchResponse> = search(query, emptyMap())

    override suspend fun search(
        query: String,
        filters: Map<String, List<String>>
    ): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()
        for (page in 1..SEARCH_PAGES) {
            val rows = try {
                // Solo la primera página puede recurrir al WebView (lento); el resto es "extra"
                parseSeriesRows(getDocument(finderUrl(query, filters, page), allowWebView = page == 1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (page == 1) throw e // sin primera página no hay nada que enseñar
                break
            }
            if (rows.isEmpty()) break
            val before = results.size
            rows.forEach { results.putIfAbsent(it.url, it) }
            if (results.size == before) break // la web repitió la página
        }
        return results.values.toList()
    }

    private fun finderUrl(query: String, filters: Map<String, List<String>>, page: Int): String {
        val text = query.trim()
        val params = LinkedHashMap<String, String>()
        params["sf"] = "1"
        if (text.isNotEmpty()) params["sh"] = text

        fun values(key: String): List<String> = filters[key].orEmpty().filter { it.isNotBlank() }

        for (filter in searchFilters) {
            if (filter.key in HANDLED_APART) continue
            val v = values(filter.key)
            if (v.isEmpty()) continue
            params[filter.key] = v.joinToString(",")
            // Los filtros numéricos y de fecha llevan siempre su operador (mín./máx.)
            filter.operatorKey?.let { op ->
                params[op] = values(op).firstOrNull() ?: filter.defaultOperator
            }
        }

        // Operadores de géneros y etiquetas: solo tienen sentido si hay algo que combinar
        if ("gi" in params || "ge" in params) params["mgi"] = values("mgi").firstOrNull() ?: "and"
        if ("tgi" in params || "tge" in params) params["mtgi"] = values("mtgi").firstOrNull() ?: "and"

        // Orden: con texto, por relevancia ("rank" asc); sin texto, lo último actualizado
        val sort = values("sort").firstOrNull() ?: if (text.isEmpty()) "sdate" else "srank"
        val order = values("order").firstOrNull()
            ?: if (sort == "abc" || sort == "srank") "asc" else "desc"
        params["sort"] = sort
        params["order"] = order
        if (page > 1) params["pg"] = page.toString()

        val queryString = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$mainUrl/series-finder/?$queryString"
    }

    /** Las etiquetas son cientos: se leen del propio formulario de la web (select#tags_include). */
    override suspend fun loadSearchFilterOptions(filter: SearchFilter): List<SearchFilterOption> {
        if (!filter.lazyOptions) return filter.options
        cachedTags?.let { return it }

        val document = getDocument("$mainUrl/series-finder/?sf=1")
        val options = document
            .select("select#tags_include option, select[name=tgi] option, select#tags_exclude option")
            .mapNotNull { option ->
                val value = option.attr("value").trim()
                val label = option.text().trim()
                if (value.isBlank() || label.isBlank()) null else SearchFilterOption(label, value)
            }
            .distinctBy { it.value }

        if (options.isEmpty()) throw ErrorLoadingException("No se encontró la lista de etiquetas")
        cachedTags = options
        return options
    }

    private fun buildSearchFilters(): List<SearchFilter> {
        fun options(vararg pairs: Pair<String, String>) =
            pairs.map { SearchFilterOption(it.first, it.second) }

        val genres = options(
            "Action" to "8", "Adult" to "280", "Adventure" to "13", "Comedy" to "17",
            "Drama" to "9", "Ecchi" to "292", "Fantasy" to "5", "Gender Bender" to "168",
            "Harem" to "3", "Historical" to "330", "Horror" to "343", "Josei" to "324",
            "Martial Arts" to "14", "Mature" to "4", "Mecha" to "10", "Mystery" to "245",
            "Psychological" to "486", "Romance" to "15", "School Life" to "6", "Sci-fi" to "11",
            "Seinen" to "18", "Shoujo" to "157", "Shoujo Ai" to "851", "Shounen" to "12",
            "Shounen Ai" to "1692", "Slice of Life" to "7", "Smut" to "281", "Sports" to "1357",
            "Supernatural" to "16", "Tragedy" to "132", "Wuxia" to "479", "Xianxia" to "480",
            "Xuanhuan" to "3954", "Yaoi" to "560", "Yuri" to "922",
        )
        val andOr = options("And" to "and", "Or" to "or")

        return listOf(
            SearchFilter(
                "sort", "Sort by", SearchFilterType.SINGLE,
                options(
                    "Last updated" to "sdate", "Rank" to "srank", "Rating" to "srate",
                    "Reviews" to "sreview", "Chapters" to "srel", "Title" to "abc",
                    "Readers" to "sread", "Frequency" to "sfrel",
                ),
                emptyLabel = "Default",
            ),
            SearchFilter(
                "order", "Order", SearchFilterType.SINGLE,
                options("Descending" to "desc", "Ascending" to "asc"),
                emptyLabel = "Default",
            ),
            SearchFilter(
                "ss", "Story status", SearchFilterType.SINGLE,
                options("Completed" to "2", "Ongoing" to "3", "Hiatus" to "4"),
            ),
            SearchFilter(
                "org", "Original language", SearchFilterType.MULTI,
                options(
                    "Chinese" to "495", "Filipino" to "9181", "Indonesian" to "9179",
                    "Japanese" to "496", "Khmer" to "18657", "Korean" to "497",
                    "Malaysian" to "9183", "Thai" to "9954", "Vietnamese" to "9177",
                ),
            ),
            SearchFilter(
                "nt", "Novel type", SearchFilterType.MULTI,
                options(
                    "Light Novel" to "2443", "Published Novel" to "26874", "Web Novel" to "2444",
                ),
            ),
            SearchFilter("gi", "Genres (include)", SearchFilterType.MULTI, genres),
            SearchFilter("ge", "Genres (exclude)", SearchFilterType.MULTI, genres),
            SearchFilter(
                "mgi", "Genres: combine with", SearchFilterType.SINGLE, andOr,
                emptyLabel = "And (default)",
            ),
            SearchFilter(
                "tgi", "Tags (include)", SearchFilterType.MULTI, lazyOptions = true,
            ),
            SearchFilter(
                "tge", "Tags (exclude)", SearchFilterType.MULTI, lazyOptions = true,
            ),
            SearchFilter(
                "mtgi", "Tags: combine with", SearchFilterType.SINGLE, andOr,
                emptyLabel = "And (default)",
            ),
            SearchFilter(
                "rl", "Chapters / releases", SearchFilterType.NUMBER,
                operatorKey = "mrl",
            ),
            SearchFilter(
                "rf", "Release frequency (days)", SearchFilterType.NUMBER,
                operatorKey = "mrf", defaultOperator = "max",
            ),
            SearchFilter(
                "rt", "Rating", SearchFilterType.NUMBER,
                operatorKey = "mrt",
            ),
            SearchFilter(
                "rtc", "Number of ratings", SearchFilterType.NUMBER,
                operatorKey = "mrtc",
            ),
            SearchFilter(
                "rvc", "Number of reviews", SearchFilterType.NUMBER,
                operatorKey = "mrvc",
            ),
            SearchFilter(
                "rct", "Readers", SearchFilterType.NUMBER,
                operatorKey = "mrct",
            ),
            SearchFilter(
                "dtf", "First release date", SearchFilterType.DATE,
                operatorKey = "mdtf",
            ),
            SearchFilter(
                "dt", "Last release date", SearchFilterType.DATE,
                operatorKey = "mdt",
            ),
        )
    }

    /**
     * Series-ranking y series-finder comparten el bloque `div.search_main_box_nu` por serie.
     */
    private fun parseSeriesRows(document: Document): List<SearchResponse> {
        return document.select("div.search_main_box_nu").mapNotNull { row ->
            val a = row.selectFirst(".search_title > a[href]")
                ?: row.selectFirst("div.search_title a[href*=/series/]")
                ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { return@mapNotNull null }

            newSearchResponse(name = title, url = a.attr("href")) {
                posterUrl = fixUrlNull(
                    row.selectFirst("img")?.let { img ->
                        img.attr("src").ifBlank { img.attr("data-src") }
                    }
                )
                // NovelUpdates puntúa sobre 5; la app usa 0-1000
                rating = row.selectFirst("span.search_ratings")?.text()
                    ?.let { RATING_REGEX.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
                    ?.times(200)?.toInt()
                latestChapter = CHAPTERS_REGEX.find(row.selectFirst("div.search_stats")?.text().orEmpty())
                    ?.groupValues?.get(1)?.let { "$it Chapters" }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ficha de la serie
    // ---------------------------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        lastSeriesUrl = url

        // Sin sesión NovelUpdates no sirve los enlaces de capítulo (desde septiembre de 2023): se pide
        // iniciar sesión UNA vez, antes de gastar nada en cargar la ficha.
        if (!isLoggedIn() && !askLogin()) throw ErrorLoadingException(LOGIN_REQUIRED_MESSAGE)

        val (document, chaptersRaw) = fetchSeries(url)
        val parsed = parseChapters(document, chaptersRaw)

        val title = document.selectFirst(".seriestitlenu")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("No se encontró el título de la serie en $url")

        if (parsed.chapters.isEmpty()) {
            when {
                !isLoggedIn() -> throw ErrorLoadingException(LOGIN_REQUIRED_MESSAGE)
                // Con sesión y la tabla sin filas: serie sin capítulos publicados todavía
                parsed.rowCount == 0 -> Unit
                else -> throw ErrorLoadingException(
                    "Con la sesión iniciada no se encontraron enlaces de capítulo " +
                            "(filas: ${parsed.rowCount ?: "tabla no encontrada"}; muestra: ${parsed.sample ?: "-"})"
                )
            }
        }
        val chapters = parsed.chapters

        return newStreamResponse(name = title, url = url, data = chapters) {
            posterUrl = fixUrlNull(
                document.selectFirst(".seriesimg img")?.attr("src")
                    ?: document.selectFirst(".wpb_wrapper img")?.attr("src")
                    ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            )
            author = document.select("#showauthors a, a#authtag")
                .joinToString(", ") { it.text().trim() }
                .takeIf { it.isNotBlank() }
            synopsis = document.selectFirst("#editdescription")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            tags = (document.select("#seriesgenre a") + document.select("#showtags a"))
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            val votes = document.selectFirst(".seriesother .uvotes, span.uvotes")?.text().orEmpty()
            rating = VOTES_RATING_REGEX.find(votes)?.groupValues?.get(1)?.toFloatOrNull()
                ?.times(200)?.toInt()
            peopleVoted = VOTES_COUNT_REGEX.find(votes)?.groupValues?.get(1)
                ?.replace(",", "")?.toIntOrNull()

            status = parseStatus(document.selectFirst("#editstatus")?.text())
        }
    }

    private fun parseStatus(raw: String?): ReleaseStatus? {
        val text = raw?.lowercase() ?: return null
        return when {
            "complete" in text -> ReleaseStatus.Completed
            "hiatus" in text -> ReleaseStatus.Paused
            "dropped" in text -> ReleaseStatus.Dropped
            "ongoing" in text -> ReleaseStatus.Ongoing
            else -> null
        }
    }

    /**
     * Ficha + lista de capítulos. Todo en un WebView ([browserFetch]) porque la ficha lleva el
     * desafío de Cloudflare y los capítulos se leen desde dentro de la propia página.
     *
     * La lista se saca de la tabla de la ficha (`table#myTable`, paginada con `?pg=N`), que es lo
     * que ve cualquier visitante. `admin-ajax.php?action=nd_getchapters` ya no la entrega a quien no
     * ha iniciado sesión (responde "0" o una página HTML), así que solo se usa como último recurso.
     */
    private suspend fun fetchSeries(url: String): Pair<Document, String?> {
        fetchSeriesDirect(url)?.let { return it }

        dbg("ficha por WebView: $url")
        val started = System.currentTimeMillis()
        val combined = browserFetch(url, SERIES_TIMEOUT_SECONDS) { polls -> seriesScript(polls) }
            ?: throw blockedError()
        dbg("WebView devolvió ${combined.length} bytes en ${System.currentTimeMillis() - started} ms")
        val page = Jsoup.parse(combined.substringBefore(CHAPTERS_MARKER), url)
        if (!isRealPage(page)) throw blockedError()
        return page to combined.substringAfter(CHAPTERS_MARKER, "").takeIf { it.isNotBlank() }
    }

    /**
     * Camino barato: dos peticiones HTTP normales con las cookies del WebView (ficha + API de
     * capítulos), sin cargar ninguna página con su publicidad. Devuelve null si Cloudflare no lo
     * deja pasar o la API no entrega la lista; entonces se usa el WebView. Si fallara, no se vuelve
     * a intentar durante unos minutos para no perder tiempo en cada novela.
     */
    private suspend fun fetchSeriesDirect(url: String): Pair<Document, String?>? {
        if (System.currentTimeMillis() < directBlockedUntil) {
            dbg("directo omitido (falló hace poco)")
            return null
        }
        val started = System.currentTimeMillis()
        val document = directDocument(url)
        if (document == null || !isRealPage(document)) {
            directBlockedUntil = System.currentTimeMillis() + DIRECT_RETRY_MS
            dbg("la ficha directa no es la real; se usará el WebView")
            return null
        }
        val postId = document.selectFirst("input#mypostid")?.attr("value")?.takeIf { it.isNotBlank() }
        if (postId == null) {
            dbg("la ficha directa no trae mypostid")
            return null
        }
        val list = postChapters(postId, url) ?: return null
        dbg("ficha + API directas en ${System.currentTimeMillis() - started} ms (${list.length} bytes)")
        return document to list
    }

    /**
     * Lista completa de capítulos en UNA petición (`nd_getchapters`, sin `mygrr`: con él responde
     * "0"). Solo la entrega con la sesión iniciada. Devuelve el HTML o null si no trae capítulos.
     */
    private suspend fun postChapters(postId: String, seriesUrl: String): String? {
        return try {
            val body = MainActivity.app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                // `headers` sustituye a los headers por defecto (User-Agent incluido)
                headers = browserHeaders() + mapOf(
                    "x-requested-with" to "XMLHttpRequest",
                    "origin" to mainUrl,
                    "accept" to "*/*",
                ),
                cookies = CookiesUtils.getAllCookiesForUrl(mainUrl),
                referer = seriesUrl,
                data = mapOf("action" to "nd_getchapters", "mypostid" to postId)
            ).text
            if (body.contains("/extnu/")) body else {
                dbg("la API respondió sin capítulos: ${body.take(40).replace('\n', ' ')}")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dbg("la API falló: ${e.message}")
            null
        }
    }

    /** Resultado de leer la lista: capítulos, nº de filas de la tabla y muestra de la primera fila. */
    private class ParsedChapters(
        val chapters: List<ChapterData>,
        val rowCount: Int?,
        val sample: String?
    )

    /**
     * [raw] es la tabla leída por el script (primera línea [ROWS_HEADER], luego
     * `href<TAB>capítulo<TAB>grupo` por fila y, si no hubo filas, una línea `#DIAG<TAB>filas<TAB>muestra`)
     * o, como último recurso, el HTML de la API AJAX.
     */
    private fun parseChapters(page: Document, raw: String?): ParsedChapters {
        val text = raw?.trim().orEmpty()
        var rowCount: Int? = null
        var sample: String? = null

        if (text.startsWith(ROWS_HEADER)) {
            val chapters = text.lineSequence().drop(1).mapNotNull { line ->
                if (line.startsWith("#DIAG\t")) {
                    val parts = line.split('\t')
                    rowCount = parts.getOrNull(1)?.toIntOrNull()
                    sample = parts.getOrNull(2)
                    return@mapNotNull null
                }
                val parts = line.split('\t')
                val href = parts.getOrNull(0)?.trim().orEmpty()
                if (href.isBlank()) return@mapNotNull null
                val name = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: "Chapter"
                val group = parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
                newChapterData(
                    name = if (group != null) "$name [$group]" else name,
                    url = absoluteUrl(href)
                )
            }.distinctBy { it.url }.toList().reversed() // la web lista del más nuevo al más antiguo
            return ParsedChapters(chapters, rowCount, sample)
        }

        val anchors = Jsoup.parse(text, mainUrl).select("a[href*=/extnu/]")
        if (anchors.isNotEmpty()) return ParsedChapters(parseChapterItems(anchors), null, null)

        val table = page.selectFirst("table#myTable")
        return ParsedChapters(emptyList(), table?.select("> tbody > tr")?.size, null)
    }

    // ---------------------------------------------------------------------------------------------
    // Sesión: NovelUpdates no da los enlaces de capítulo a quien no ha iniciado sesión
    // ---------------------------------------------------------------------------------------------

    /** Cookie de sesión de WordPress; también la ve la parte de WebView de fondo. */
    private fun isLoggedIn(): Boolean =
        CookieManager.getInstance().getCookie(mainUrl)?.contains("wordpress_logged_in") == true

    /**
     * Abre el login una sola vez (la app no ve ni guarda usuario ni contraseña) y espera a que
     * aparezca la cookie de sesión. `wp-login.php` lo redirige a su página de acceso el plugin
     * Theme My Login, sea cual sea el nombre de esa página.
     */
    private suspend fun askLogin(): Boolean =
        LoginWebView.login("$mainUrl/wp-login.php", isDone = { isLoggedIn() })

    private fun absoluteUrl(href: String): String = when {
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> mainUrl + href
        else -> href
    }

    // ---------------------------------------------------------------------------------------------
    // Red: cliente normal -> WebView invisible -> WebView visible (verificación de Cloudflare)
    // ---------------------------------------------------------------------------------------------

    /** El User-Agent del WebView: la cookie `cf_clearance` solo vale con el mismo que la obtuvo. */
    private fun browserHeaders(): Map<String, String> {
        val ua = WebViewResolver.webViewUserAgent
            ?: WebViewResolver.getWebViewUserAgent()
            ?: USER_AGENT
        return MainActivity.app.defaultHeaders + mapOf("user-agent" to ua)
    }

    /** GET con el cliente normal (sin CloudflareKiller); null si falla o no devuelve 200. */
    private suspend fun directDocument(url: String): Document? {
        return try {
            val response = MainActivity.app.get(
                url,
                headers = browserHeaders(),
                cookies = CookiesUtils.getAllCookiesForUrl(url),
                referer = "$mainUrl/"
            )
            dbg("GET ${url.substringAfter(mainUrl)} -> ${response.code}")
            if (response.code == 200) response.document else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Una página real de NovelUpdates lleva "Novel Updates" en el título; un desafío no. */
    private fun isRealPage(document: Document): Boolean =
        document.title().contains("Novel Updates", ignoreCase = true)

    private fun blockedError() = ErrorLoadingException(
        "NovelUpdates no respondió (Cloudflare). Inténtalo de nuevo en unos segundos"
    )

    /**
     * Carga [url] en un navegador y devuelve lo que entregue el script de [script].
     *
     * Se usa [BackgroundWebView] (WebView montado en la ventana, sin verse): ahí Turnstile se
     * resuelve solo. Sin pantalla disponible se recurre a [WebViewResolver] (WebView desacoplado,
     * que no pasa Turnstile pero sirve para las páginas sin desafío). NO se usa
     * `resolveUsingWebView(url: String)` porque borra las cookies del dominio.
     */
    private suspend fun browserFetch(
        url: String,
        timeoutSeconds: Long = BROWSER_TIMEOUT_SECONDS,
        referer: String? = null,
        script: (Int) -> String
    ): String? {
        if (BackgroundWebView.isAvailable()) {
            return BackgroundWebView.run(url, script(BROWSER_POLLS), timeoutSeconds, referer)
        }
        return WebViewResolver(
            scriptToFinish = script(DETACHED_POLLS),
            useOkhttp = false,
            timeoutSeconds = DETACHED_TIMEOUT_SECONDS
        ).resolveUsingWebView(requestCreator("GET", url)).first as? String
    }

    private suspend fun getDocument(url: String, allowWebView: Boolean = true): Document {
        directDocument(url)?.let { if (isRealPage(it)) return it }
        if (!allowWebView) throw blockedError()

        val html = browserFetch(url) { polls -> pageScript(polls) }
        val document = html?.let { Jsoup.parse(it, url) }
        if (document == null || !isRealPage(document)) throw blockedError()
        return document
    }

    /** Respuesta de la API AJAX: cada capítulo es un `<a data-id href="//www.novelupdates.com/extnu/<id>/">`. */
    private fun parseChapterItems(anchors: List<org.jsoup.nodes.Element>): List<ChapterData> {
        return anchors.mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // El texto visible es abreviado ("v1c5"); el atributo title, si existe, trae el completo.
            val chapterName = a.selectFirst("[title]")?.attr("title")?.takeIf { it.isNotBlank() }
                ?: a.text().trim().takeIf { it.isNotBlank() }
                ?: "Chapter"
            newChapterData(name = chapterName, url = absoluteUrl(href))
        }.distinctBy { it.url }.reversed()
    }

    // ---------------------------------------------------------------------------------------------
    // Capítulo: /extnu/<id>/ -> web del traductor
    // ---------------------------------------------------------------------------------------------

    override suspend fun loadHtml(url: String): String? {
        val target = if (url.contains("/extnu/")) resolveExternalUrl(url) else url
        val host = hostOf(target) ?: "web externa"

        return try {
            WebLinkProvider.fetchPage(target).html
        } catch (e: ErrorLoadingException) {
            throw ErrorLoadingException("No se pudo leer el capítulo en $host: ${e.message}")
        }
    }

    /**
     * `/extnu/<id>/` redirige a la web del traductor. Se sigue la redirección con una petición
     * normal (como hace LNReader) y se toma la URL final; si seguimos en novelupdates.com, la
     * redirección es por meta refresh o JavaScript y se lee del HTML. Si todo eso falla (bloqueo),
     * un navegador ([browserFetch]) llega hasta la web externa y devuelve su URL.
     */
    private suspend fun resolveExternalUrl(extnuUrl: String): String {
        val referer = lastSeriesUrl ?: "$mainUrl/"
        try {
            // Sin seguir la redirección: basta la cabecera Location y no se descarga la web externa
            val response = MainActivity.app.get(
                extnuUrl,
                headers = browserHeaders(),
                cookies = CookiesUtils.getAllCookiesForUrl(extnuUrl),
                referer = referer,
                allowRedirects = false
            )
            val location = response.okhttpResponse.header("Location")
            dbg("extnu directo -> ${response.code} ${location.orEmpty().take(80)}")
            if (!location.isNullOrBlank()) {
                val target = absolutize(location, extnuUrl)
                if (!target.contains("novelupdates.com", ignoreCase = true)) return target
                dbg("la redirección vuelve a NovelUpdates (¿sesión?): $target")
            }

            // Sin Location: redirección por meta refresh o JavaScript en el HTML
            val html = response.text
            Jsoup.parse(html, extnuUrl).selectFirst("meta[http-equiv~=(?i)refresh]")?.attr("content")
                ?.let { Regex("url\\s*=\\s*['\"]?([^'\"\\s>]+)", RegexOption.IGNORE_CASE).find(it) }
                ?.groupValues?.get(1)
                ?.let { return absolutize(it, extnuUrl) }

            Regex(
                "(?:window\\.)?location(?:\\.href|\\.replace\\()?\\s*=?\\s*\\(?\\s*['\"](https?:[^'\"]+)['\"]",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.get(1)?.let { return it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dbg("extnu directo falló: ${e.message}")
        }

        dbg("extnu por WebView")
        val href = browserFetch(extnuUrl, EXTNU_TIMEOUT_SECONDS, referer) { polls -> extnuScript(polls) }
        return href?.takeIf { it.startsWith("http") && !it.contains("novelupdates.com", true) }
            ?: throw ErrorLoadingException(
                "No se pudo abrir el enlace del capítulo (Cloudflare o sesión caducada). " +
                        "Inténtalo de nuevo; si persiste, vuelve a abrir la novela"
            )
    }

    private fun absolutize(location: String, base: String): String {
        return try {
            URI(base).resolve(location).toString()
        } catch (e: Exception) {
            fixUrl(location)
        }
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.removePrefix("www.")
    } catch (e: Exception) {
        null
    }

    private companion object {
        /** Valor de `rank` de "Activity (Week)"; si cambia, se descubre en la propia página. */
        const val WEEKLY_RANK = "week"

        /** Páginas del finder que se juntan por búsqueda (la app no pagina los resultados). */
        const val SEARCH_PAGES = 3

        /** Claves que `finderUrl` calcula aparte (orden) en vez de copiarlas tal cual. */
        val HANDLED_APART = setOf("sort", "order", "mgi", "mtgi")

        /** Separa la ficha de la lista de capítulos en lo que devuelve el navegador. */
        const val CHAPTERS_MARKER = "<!--NU_CHAPTERS_MARKER-->"

        /** WebView de fondo: da margen para que Turnstile se resuelva solo (suele tardar unos segundos). */
        const val BROWSER_TIMEOUT_SECONDS = 45L

        /** Ficha + todas las páginas de capítulos (una serie larga tiene decenas de páginas) */
        const val SERIES_TIMEOUT_SECONDS = 100L

        /** Resolver /extnu/ (una redirección): no merece esperar más */
        const val EXTNU_TIMEOUT_SECONDS = 40L

        /** Tras un rechazo de la petición directa, minutos sin reintentarla */
        const val DIRECT_RETRY_MS = 120_000L

        const val TAG = "NovelUpdates"

        const val LOGIN_REQUIRED_MESSAGE =
            "NovelUpdates solo muestra los enlaces de capítulo con la sesión iniciada. " +
                    "Abre la novela de nuevo e inicia sesión cuando se te pida"

        /** Primera línea de la lista de capítulos que entrega el script */
        const val ROWS_HEADER = "#ROWS"
        const val BROWSER_POLLS = 70 // consultas cada 600 ms (~42 s)

        /** WebView desacoplado (sin pantalla): sirve solo para páginas sin desafío */
        const val DETACHED_TIMEOUT_SECONDS = 15L
        const val DETACHED_POLLS = 24

        /** Devuelve el HTML renderizado cuando la página ya es la real de NovelUpdates. */
        fun pageScript(maxPolls: Int) = """
            (function() {
                var n = 0;
                var t = setInterval(function() {
                    n++;
                    if (document.title.indexOf('Novel Updates') >= 0 && document.readyState !== 'loading') {
                        clearInterval(t);
                        NativeAndroid.onElementFound(document.documentElement.outerHTML);
                    } else if (n > $maxPolls) { clearInterval(t); }
                }, 600);
            })();
        """.trimIndent()

        /**
         * Espera a que la ficha sea la real, lee su tabla de capítulos (`table#myTable`), recorre las
         * demás páginas (`?pg=N`, 4 a la vez) con `fetch` del mismo origen y devuelve la ficha + la
         * lista. Si la tabla no da filas, prueba la API AJAX (sin `mygrr`; solo funciona con sesión) y,
         * si tampoco, devuelve una línea `#DIAG` con el nº de filas y una muestra para depurar.
         */
        fun seriesScript(maxPolls: Int) = """
            (function() {
                var n = 0;
                var t = setInterval(function() {
                    n++;
                    if (document.title.indexOf('Novel Updates') >= 0 && document.readyState !== 'loading') {
                        clearInterval(t);
                        if (window.__nuStarted) return;
                        window.__nuStarted = true;
                        run();
                    } else if (n > $maxPolls) { clearInterval(t); }
                }, 600);

                function rowsFrom(doc) {
                    var out = [];
                    var rows = doc.querySelectorAll('table#myTable > tbody > tr');
                    for (var i = 0; i < rows.length; i++) {
                        var a = rows[i].querySelector('a.chp-release') || rows[i].querySelector('a[data-id]');
                        if (!a) continue;
                        var href = a.getAttribute('href') || '';
                        if (href.indexOf('/extnu/') < 0) {
                            var id = a.getAttribute('data-id');
                            if (!id) continue;
                            href = '/extnu/' + id + '/';
                        }
                        var g = rows[i].querySelector('a');
                        out.push(href + '\t' + (a.textContent || '').trim() + '\t' +
                            ((g && g !== a) ? (g.textContent || '').trim() : ''));
                    }
                    return out;
                }
                function maxPage(doc) {
                    var m = 1;
                    var links = doc.querySelectorAll('div.digg_pagination a[href]');
                    for (var i = 0; i < links.length; i++) {
                        var r = /[?&]pg=(\d+)/.exec(links[i].getAttribute('href') || '');
                        if (r) { var v = parseInt(r[1], 10); if (v > m) m = v; }
                    }
                    return m;
                }
                async function loadPage(p) {
                    for (var attempt = 0; attempt < 2; attempt++) {
                        try {
                            var r = await fetch(location.pathname + '?pg=' + p, { credentials: 'same-origin' });
                            var d = new DOMParser().parseFromString(await r.text(), 'text/html');
                            var rows = rowsFrom(d);
                            if (rows.length > 0) return rows;
                        } catch (e) {}
                        await new Promise(function(res) { setTimeout(res, 800); });
                    }
                    return [];
                }
                async function ajaxChapters() {
                    var idEl = document.querySelector('input#mypostid');
                    if (!idEl) return null;
                    try {
                        var p = new URLSearchParams();
                        p.append('action', 'nd_getchapters');
                        p.append('mypostid', idEl.value);
                        var r = await fetch('/wp-admin/admin-ajax.php', {
                            method: 'POST', body: p, credentials: 'same-origin',
                            headers: { 'X-Requested-With': 'XMLHttpRequest' }
                        });
                        var x = await r.text();
                        return x.indexOf('/extnu/') >= 0 ? x : null;
                    } catch (e) { return null; }
                }
                async function run() {
                    var page = document.documentElement.outerHTML;
                    try {
                        // 1) Una sola petición con la lista completa (solo con sesión iniciada)
                        var chapters = await ajaxChapters();
                        if (chapters === null) {
                            // 2) Tabla de la ficha + las demás páginas, 4 a la vez
                            var lines = rowsFrom(document);
                            var max = maxPage(document);
                            var results = {};
                            var next = 2;
                            var worker = async function() {
                                while (next <= max) { var p = next++; results[p] = await loadPage(p); }
                            };
                            var workers = [];
                            for (var k = 0; k < 4; k++) workers.push(worker());
                            await Promise.all(workers);
                            for (var q = 2; q <= max; q++) { if (results[q]) lines = lines.concat(results[q]); }
                            if (lines.length > 0) {
                                chapters = '$ROWS_HEADER\n' + lines.join('\n');
                            } else {
                                var all = document.querySelectorAll('table#myTable > tbody > tr');
                                var sample = all.length > 0 ? all[0].outerHTML.replace(/\s+/g, ' ').slice(0, 400) : '';
                                chapters = '$ROWS_HEADER\n#DIAG\t' + all.length + '\t' + sample;
                            }
                        }
                        NativeAndroid.onElementFound(page + '$CHAPTERS_MARKER' + chapters);
                    } catch (e) {
                        NativeAndroid.onElementFound(page);
                    }
                }
            })();
        """.trimIndent()

        /** En /extnu/ devuelve la URL en cuanto el navegador ya está en la web externa. */
        fun extnuScript(maxPolls: Int) = """
            (function() {
                var n = 0;
                var t = setInterval(function() {
                    n++;
                    if (location.hostname.indexOf('novelupdates.com') < 0 && location.href.indexOf('http') === 0) {
                        clearInterval(t);
                        NativeAndroid.onElementFound(location.href);
                    } else if (n > $maxPolls) { clearInterval(t); }
                }, 600);
            })();
        """.trimIndent()

        @Volatile
        var cachedTags: List<SearchFilterOption>? = null

        // "(4.4)"
        val RATING_REGEX = Regex("(\\d+(?:\\.\\d+)?)")

        // "209 Chapters Every 0.9 Day(s) ..."
        val CHAPTERS_REGEX = Regex("(\\d[\\d,]*)\\s+Chapters", RegexOption.IGNORE_CASE)

        // "(4.4 / 5.0, 1234 votes)"
        val VOTES_RATING_REGEX = Regex("(\\d+(?:\\.\\d+)?)\\s*/\\s*5")
        val VOTES_COUNT_REGEX = Regex("(\\d[\\d,]*)\\s+votes", RegexOption.IGNORE_CASE)
    }
}
