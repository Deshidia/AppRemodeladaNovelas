package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.appWithInterceptor
import com.lagradost.quicknovel.USER_AGENT
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Proveedor genérico: abre cualquier enlace web en el lector extrayendo el texto principal de la página
 * y detectando el enlace al "siguiente capítulo" para encadenarlo al final.
 * No aparece en la lista de proveedores (Apis.apis); solo se resuelve por nombre, igual que Reddit.
 */
class WebLinkProvider : MainAPI() {
    override val mainUrl = "https://"
    override val name = NAME

    // "es" evita que APIRepository.load traduzca el nombre de la ficha: el id (generateId) depende del nombre
    // y tiene que ser el mismo que el de la tarjeta guardada en el historial.
    override val lang = "es"
    override val chapterLang: String? = "auto"

    // Cloudflare solo se resuelve (WebView) cuando la respuesta es realmente un desafío; en webs normales no cuesta nada
    override val usesCloudFlareKiller = true

    data class WebPage(
        val title: String,
        val host: String,
        val html: String,
        val nextUrl: String?,
        val prevUrl: String?,
        val posterUrl: String? = null,
        val indexUrl: String? = null
    )

    companion object {
        const val NAME = "Web"
        private const val NEXT_FOLDER = "web_link_next"
        private const val PREV_FOLDER = "web_link_prev"
        private const val TITLE_FOLDER = "web_link_title"

        /** Tope de capítulos al seguir la cadena de "siguiente" (evita recorridos infinitos). */
        const val MAX_CHAPTERS = 3000

        // Última página descargada, para reutilizarla una vez (título + lector)
        @Volatile
        private var prefetched: Pair<String, WebPage>? = null
        @Volatile
        private var prefetchedAt = 0L
        private const val PREFETCH_TTL_MS = 60_000L

        /** Añade https:// si falta y comprueba que sea una URL válida. Devuelve null si no lo es. */
        fun normalizeUrl(raw: String): String? {
            var url = raw.trim()
            if (url.isEmpty() || url.contains(' ')) return null
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "https://$url"
            }
            return try {
                val host = URI(url).host
                if (host.isNullOrBlank() || !host.contains('.')) null else url
            } catch (e: Exception) {
                null
            }
        }

        /** Enlace al siguiente capítulo de [url], guardado al descargarla (sobrevive a reiniciar la app). */
        fun getNextUrl(url: String): String? = getKey<String>(NEXT_FOLDER, url.hashCode().toString())

        /** Capítulo en el que debe arrancar el lector tras añadir uno al principio (se consume una vez). */
        @Volatile
        var pendingStartUrl: String? = null

        fun takePendingStart(): String? = pendingStartUrl.also { pendingStartUrl = null }

        /** Título de la página [url], guardado al descargarla. */
        fun getTitle(url: String): String? = getKey<String>(TITLE_FOLDER, url.hashCode().toString())

        /** Enlace al capítulo anterior de [url], guardado al descargarla. */
        fun getPrevUrl(url: String): String? = getKey<String>(PREV_FOLDER, url.hashCode().toString())

        /** Nombre legible para un capítulo a partir del final de su URL ("capitulo-12" -> "Capitulo 12"). */
        fun chapterNameFromUrl(url: String): String? {
            return try {
                val slug = URI(url).path.orEmpty().trimEnd('/').substringAfterLast('/')
                    .removeSuffix(".html").removeSuffix(".htm").removeSuffix(".php")
                URLDecoder.decode(slug, "UTF-8")
                    .replace(Regex("[-_+]+"), " ").trim()
                    .replaceFirstChar { it.uppercase() }
                    .ifBlank { null }
            } catch (e: Exception) {
                null
            }
        }

        suspend fun fetchPage(url: String): WebPage {
            // Un solo uso: evita descargar dos veces al abrir, pero nunca sirve datos viejos al recargar
            prefetched?.let { (cachedUrl, page) ->
                prefetched = null
                if (cachedUrl == url && System.currentTimeMillis() - prefetchedAt < PREFETCH_TTL_MS) return page
            }

            // si la acabamos de descargar al verificar un "siguiente" adivinado, no la pedimos otra vez
            val body = guessedBody?.takeIf { it.first == url }?.second?.also { guessedBody = null }
                ?: getHtml(url)
            val document = Jsoup.parse(body, url)

            val host = hostOf(url) ?: "Web"
            val title = (document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()).trim().ifBlank { host }

            // IMPORTANTE: buscar el enlace antes de limpiar el documento (nav/footer se eliminan)
            val indexUrl = findIndexUrl(document, url)
            var nextUrl = findAdjacentUrl(document, url, forward = true)
            var prevUrl = findAdjacentUrl(document, url, forward = false)

            val html = extractContent(document)
            if (Jsoup.parse(html).text().length < 80) {
                throw ErrorLoadingException("No content found")
            }

            // Si la web no ofrece un enlace fiable al siguiente, probamos el capítulo +1 de la propia URL.
            // (El anterior solo se adivina cuando se va hacia atrás, ver guessPrevUrl, para no duplicar peticiones.)
            if (nextUrl == null) nextUrl = guessAdjacentUrl(url, forward = true)

            if (nextUrl != null) {
                setKey(NEXT_FOLDER, url.hashCode().toString(), nextUrl)
            }
            if (prevUrl != null) {
                setKey(PREV_FOLDER, url.hashCode().toString(), prevUrl)
            }

            setKey(TITLE_FOLDER, url.hashCode().toString(), title)

            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.startsWith("http", true) }

            val page = WebPage(title, host, html, nextUrl, prevUrl, poster, indexUrl)
            prefetched = url to page
            prefetchedAt = System.currentTimeMillis()
            PageCache.put(url, html)
            return page
        }

        // ---------- Recorrer la cadena de capítulos ----------

        private suspend fun getHtml(url: String): String {
            val response = appWithInterceptor.get(
                url,
                headers = mapOf(
                    "user-agent" to USER_AGENT,
                    "accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                    "accept-language" to "es,en;q=0.8"
                )
            )
            return response.text
        }

        // ---------- Índice (tabla de contenidos) ----------

        private const val MAX_INDEX_PAGES = 60

        private val indexTexts = setOf(
            "目次", "目录", "目錄", "index", "table of contents", "contents", "toc", "indice",
            "lista de capitulos", "capitulos", "chapter list", "chapters", "sommaire",
            "inhaltsverzeichnis", "оглавление", "содержание"
        )

        /** Enlace al índice de capítulos ("目次", "Table of Contents", "Índice"…) de la página, si lo hay. */
        private fun findIndexUrl(doc: Document, currentUrl: String): String? {
            for (a in doc.select("a[href]")) {
                val matches = listOf(a.text(), a.attr("title"), a.attr("aria-label"))
                    .any { normalizeText(it) in indexTexts }
                if (matches) isValidLink(a, currentUrl)?.let { return it }
            }
            return null
        }

        private fun urlKey(url: String) = url.substringBefore('#').trimEnd('/')

        /** Mismo "formato" de último tramo: ambos numéricos o con el mismo prefijo antes del número. */
        private fun sameShape(a: String, b: String): Boolean {
            val sa = lastSegment(a)
            val sb = lastSegment(b)
            if (sa.isEmpty() || sb.isEmpty()) return false
            if (sa.all { it.isDigit() } && sb.all { it.isDigit() }) return true
            return slugPrefix(a).equals(slugPrefix(b), ignoreCase = true) && sa.any { it.isDigit() }
        }

        private fun cleanTitle(raw: String) = raw.replace(Regex("\\s+"), " ").trim()

        /**
         * Lee todos los capítulos del índice de la web (siguiendo su paginación) en pocas peticiones, en vez de
         * seguir el enlace "siguiente" página a página. Devuelve null si no hay índice utilizable; entonces se
         * recorre la cadena. Solo se acepta si encaja con lo que dice la propia página (el capítulo siguiente al
         * de partida debe coincidir con su enlace "siguiente").
         */
        private suspend fun crawlFromIndex(startUrl: String, first: WebPage): List<ChapterData>? {
            val indexUrl = first.indexUrl ?: return null
            val startKey = urlKey(startUrl)
            val startPath = try {
                URI(startUrl).path.orEmpty().trimEnd('/')
            } catch (e: Exception) {
                return null
            }
            val dirPrefix = startPath.substringBeforeLast('/') + "/"

            val entries = LinkedHashMap<String, Pair<String, String>>() // clave -> (url, título)
            var pageUrl: String? = indexUrl
            val seenPages = hashSetOf<String>()
            while (pageUrl != null && seenPages.size < MAX_INDEX_PAGES && seenPages.add(pageUrl)) {
                val doc = try {
                    Jsoup.parse(getHtml(pageUrl), pageUrl)
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    break
                }
                for (a in doc.select("a[href]")) {
                    if (a.closest(excludedAncestors) != null) continue
                    val href = a.absUrl("href").substringBefore('#')
                    if (!href.startsWith("http", true) || href.contains('?')) continue
                    if (hostOf(href) != hostOf(startUrl)) continue
                    val path = try {
                        URI(href).path.orEmpty()
                    } catch (e: Exception) {
                        continue
                    }
                    if (!path.startsWith(dirPrefix)) continue
                    val rest = path.removePrefix(dirPrefix).trimEnd('/')
                    if (rest.isEmpty() || rest.contains('/')) continue
                    if (!sameShape(href, startUrl)) continue
                    val key = urlKey(href)
                    val title = cleanTitle(a.text())
                    val existing = entries[key]
                    if (existing == null || (existing.second.isEmpty() && title.isNotEmpty())) {
                        entries[key] = href to title
                    }
                }
                pageUrl = findAdjacentUrl(doc, pageUrl, forward = true)
            }
            if (entries.size < 2 || startKey !in entries) return null

            var list = entries.values.toList()
            val numbers = list.map { lastNumber.find(lastSegment(it.first))?.value?.toLongOrNull() }
            if (numbers.all { it != null } && numbers.first()!! > numbers.last()!!) list = list.reversed()

            // el siguiente del capítulo de partida según el índice debe ser el que enlaza la propia página
            fun nextInList(l: List<Pair<String, String>>): String? =
                l.indexOfFirst { urlKey(it.first) == startKey }.let { l.getOrNull(it + 1)?.first }
            val expectedNext = first.nextUrl
            if (expectedNext != null && nextInList(list)?.let { urlKey(it) } != urlKey(expectedNext)) {
                val reversed = list.reversed()
                list = if (nextInList(reversed)?.let { urlKey(it) } == urlKey(expectedNext)) reversed
                else return null
            }

            val from = list.indexOfFirst { urlKey(it.first) == startKey }
            val slice = list.drop(from).take(MAX_CHAPTERS)
            if (slice.size < 2) return null

            val result = slice.mapIndexed { i, (url, title) ->
                // el primero conserva la URL tal cual la escribió el usuario
                val chapterUrl = if (i == 0) startUrl else url
                ChapterData(
                    title.ifBlank { chapterNameFromUrl(url) ?: "Chapter ${i + 1}" },
                    chapterUrl, null, null
                )
            }
            // enlaces y títulos conocidos: el lector y "Abrir enlace" podrán mostrar la lista completa sin red
            for (i in result.indices) {
                val h = result[i].url.hashCode().toString()
                setKey(TITLE_FOLDER, h, result[i].name)
                if (i + 1 < result.size) setKey(NEXT_FOLDER, h, result[i + 1].url)
            }
            return result
        }

        /**
         * Sigue los enlaces "siguiente capítulo" desde [startUrl] y devuelve todos los capítulos encontrados.
         * Las páginas ya visitadas se saltan usando los enlaces guardados, así que repetirlo solo descarga lo nuevo.
         */
        suspend fun crawlForward(startUrl: String, first: WebPage): List<ChapterData> {
            val chapters = arrayListOf(ChapterData(first.title, startUrl, null, null))
            val seen = hashSetOf(startUrl.substringBefore('#').trimEnd('/'))
            var nextUrl = first.nextUrl

            while (chapters.size < MAX_CHAPTERS) {
                val url = nextUrl ?: break
                if (!seen.add(url.substringBefore('#').trimEnd('/'))) break // bucle

                var title = getTitle(url)
                var following = getNextUrl(url)
                if (title == null || following == null) {
                    // no la teníamos o era la última: hay que bajarla para saber su título y su siguiente
                    try {
                        val page = fetchPage(url)
                        title = page.title
                        following = page.nextUrl
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        break // si una página falla nos quedamos con lo recorrido hasta aquí
                    }
                }
                chapters.add(
                    ChapterData(title ?: chapterNameFromUrl(url) ?: "Chapter ${chapters.size + 1}", url, null, null)
                )
                nextUrl = following
            }
            return chapters
        }

        /** Cadena ya conocida desde [startUrl] según los enlaces guardados; no hace ninguna petición. */
        fun knownChain(startUrl: String, startTitle: String): MutableList<ChapterData> {
            val chapters = mutableListOf(ChapterData(startTitle, startUrl, null, null))
            val seen = hashSetOf(startUrl.substringBefore('#').trimEnd('/'))
            var next = getNextUrl(startUrl)
            while (next != null && chapters.size < MAX_CHAPTERS) {
                if (!seen.add(next.substringBefore('#').trimEnd('/'))) break
                chapters.add(
                    ChapterData(
                        getTitle(next) ?: chapterNameFromUrl(next) ?: "Chapter ${chapters.size + 1}",
                        next, null, null
                    )
                )
                next = getNextUrl(next)
            }
            return chapters
        }

        /** Páginas ya descargadas al recorrer la cadena, para no volver a pedirlas al descargar los capítulos. */
        private object PageCache {
            private const val TTL_MS = 24L * 60 * 60 * 1000
            @Volatile
            private var writes = 0

            private fun file(url: String): File? {
                val dir = context?.cacheDir?.let { File(it, "weblink") } ?: return null
                dir.mkdirs()
                val hash = MessageDigest.getInstance("MD5").digest(url.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                return File(dir, "$hash.html")
            }

            fun put(url: String, html: String) {
                try {
                    file(url)?.writeText(html)
                    if (++writes % 50 == 0) sweep()
                } catch (e: Exception) {
                    // la caché es opcional
                }
            }

            /** Borra la página guardada de [url], si hay (lo usa "recargar capítulo"). */
            fun discard(url: String) {
                try {
                    file(url)?.delete()
                } catch (e: Exception) {
                    // la caché es opcional
                }
            }

            /** Devuelve y borra la página guardada (un solo uso). Para recargar hay que usar [discard]. */
            fun take(url: String): String? = try {
                file(url)?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < TTL_MS }
                    ?.let { f -> f.readText().also { f.delete() } }
            } catch (e: Exception) {
                null
            }

            private fun sweep() {
                val now = System.currentTimeMillis()
                context?.cacheDir?.let { File(it, "weblink") }?.listFiles()
                    ?.filter { now - it.lastModified() > TTL_MS }?.forEach { it.delete() }
            }
        }

        // ---------- Capítulo siguiente / anterior ----------

        private class Dir(
            val relValue: String,
            val selectors: List<String>,
            val textExact: Regex,
            val textPrefix: Regex,
            val arrows: Set<String>
        )

        private val nextDir = Dir(
            relValue = "next",
            selectors = listOf(
                "a#next_chap", "a#next-chap", "a#btn-next", "a.btn-next", "a.next_chap",
                "a.next-chap", "a.nextchap", "a.next-chapter", ".next-chapter a", ".nav-next a",
                ".next_page a", "a.next"
            ),
            textExact = Regex(
                "^(next|next chapter|next part|next page|next episode|siguiente|siguiente capitulo|" +
                        "capitulo siguiente|siguiente parte|siguiente cap|proximo|proximo capitulo|sig|" +
                        "suivant|chapitre suivant|nachstes kapitel|nächstes kapitel|weiter|" +
                        "下一章|下一页|下一頁|下章|次へ|次の話|次話|次のページ|次の章|次のエピソード|" +
                        "следующая глава|следующая)$"
            ),
            textPrefix = Regex(
                "^(next chapter|siguiente capitulo|capitulo siguiente|proximo capitulo|chapitre suivant|下一章|次の話)"
            ),
            arrows = setOf("→", "»", ">", "›", ">>", "»»", "»»»")
        )

        private val prevDir = Dir(
            relValue = "prev",
            selectors = listOf(
                "a#prev_chap", "a#prev-chap", "a#btn-prev", "a.btn-prev", "a.prev_chap",
                "a.prev-chap", "a.prevchap", "a.prev-chapter", ".prev-chapter a", ".nav-previous a",
                ".prev_page a", "a.prev", "a.previous"
            ),
            textExact = Regex(
                "^(previous|previous chapter|previous part|previous page|previous episode|prev|prev chapter|" +
                        "anterior|capitulo anterior|anterior capitulo|parte anterior|cap anterior|" +
                        "precedent|chapitre precedent|vorheriges kapitel|zuruck|" +
                        "上一章|上一页|上一頁|上章|前へ|前の話|前話|前のページ|前の章|前のエピソード|" +
                        "предыдущая глава|предыдущая)$"
            ),
            textPrefix = Regex(
                "^(previous chapter|capitulo anterior|anterior capitulo|chapitre precedent|上一章|前の話)"
            ),
            arrows = setOf("←", "«", "<", "‹", "<<", "««", "«««")
        )

        private const val excludedAncestors =
            "#comments, .comments, #respond, .comment-list, .sidebar, .widget, .related-posts"

        private fun hostOf(url: String): String? = try {
            URI(url).host?.removePrefix("www.")
        } catch (e: Exception) {
            null
        }

        private fun normalizeText(raw: String): String = raw.lowercase()
            .replace('í', 'i').replace('ó', 'o').replace('á', 'a').replace('é', 'e').replace('ú', 'u')
            .replace('è', 'e').replace('ü', 'u')
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        private fun looksLikeDirText(a: Element, dir: Dir): Boolean {
            val candidates = listOf(a.text(), a.attr("title"), a.attr("aria-label"))
            return candidates.any { raw ->
                val text = normalizeText(raw)
                text.isNotEmpty() && (dir.textExact.matches(text) || dir.textPrefix.containsMatchIn(text))
            }
        }

        private fun isValidLink(a: Element?, currentUrl: String): String? {
            if (a == null) return null
            if (a.closest(excludedAncestors) != null) return null
            if (a.hasClass("disabled") || a.hasClass("page-numbers")) return null
            val href = a.attr("href").trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:", true) ||
                href.startsWith("mailto:", true)
            ) return null
            return validateUrl(a.absUrl("href"), currentUrl)
        }

        private fun validateUrl(candidate: String, currentUrl: String): String? {
            if (!candidate.startsWith("http", true)) return null
            val clean = candidate.substringBefore('#')
            if (clean.trimEnd('/') == currentUrl.substringBefore('#').trimEnd('/')) return null
            // Solo seguimos enlaces del mismo sitio para no acabar en anuncios u otras webs
            if (hostOf(clean) != hostOf(currentUrl)) return null
            return clean
        }

        // ---------- Series (blogs que intercalan novelas) y adivinación por número ----------

        private val lastNumber = Regex("(\\d+)(?!.*\\d)")
        private val yearMonthDir = Regex("^(.*/)(\\d{4})/(\\d{2})$")

        private val bloggerNewerTexts = setOf(
            "newer post", "newer posts", "entrada mas reciente", "entradas mas recientes"
        )
        private val bloggerOlderTexts = setOf(
            "older post", "older posts", "entrada mas antigua", "entradas mas antiguas",
            "entrada antigua", "entradas antiguas"
        )

        private fun lastSegment(url: String): String = try {
            URLDecoder.decode(URI(url).path.orEmpty().trimEnd('/').substringAfterLast('/'), "UTF-8")
                .removeSuffix(".html").removeSuffix(".htm").removeSuffix(".php")
        } catch (e: Exception) {
            ""
        }

        /** Parte del final de la URL anterior al primer número ("against-gods-capitulo-1828" -> "against-gods-capitulo-"). */
        private fun slugPrefix(url: String) = lastSegment(url).takeWhile { !it.isDigit() }

        /** En blogs que mezclan varias novelas, "siguiente/anterior" solo vale si es de la misma serie. */
        private fun sameSeries(candidate: String, current: String): Boolean {
            if (lastSegment(current).none { it.isDigit() }) return true
            return slugPrefix(candidate).equals(slugPrefix(current), ignoreCase = true)
        }

        private suspend fun verifyPage(url: String): Boolean {
            return try {
                val response = appWithInterceptor.get(
                    url,
                    headers = mapOf("user-agent" to USER_AGENT, "accept" to "text/html,*/*;q=0.8")
                )
                val ok = response.okhttpResponse
                // evita webs que devuelven la portada (200) o redirigen cuando la página no existe
                val finalPath = URI(ok.request.url.toString()).path.orEmpty().trimEnd('/')
                val valid = ok.isSuccessful && finalPath == URI(url).path.orEmpty().trimEnd('/') &&
                        response.text.length > 1500
                if (valid) guessedBody = url to response.text
                valid
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                false
            }
        }

        @Volatile
        private var guessedBody: Pair<String, String>? = null

        /** Capítulo anterior de [url]: el enlace guardado o, si no hay, el capítulo -1 de la URL si existe. */
        suspend fun guessPrevUrl(url: String): String? {
            getPrevUrl(url)?.let { return it }
            val prev = guessAdjacentUrl(url, forward = false) ?: return null
            setKey(PREV_FOLDER, url.hashCode().toString(), prev)
            return prev
        }

        /** Prueba el capítulo ±1 cambiando el último número de la URL (y el mes contiguo en rutas /AAAA/MM/). */
        private suspend fun guessAdjacentUrl(currentUrl: String, forward: Boolean): String? {
            val uri = try {
                URI(currentUrl)
            } catch (e: Exception) {
                return null
            }
            val path = uri.path.orEmpty()
            val dir = path.substringBeforeLast('/', "")
            val file = path.substringAfterLast('/')
            val match = lastNumber.find(file) ?: return null
            val target = (match.value.toLongOrNull() ?: return null) + if (forward) 1 else -1
            if (target < 1) return null
            val newFile = file.replaceRange(match.range, target.toString().padStart(match.value.length, '0'))

            val dirs = mutableListOf(dir)
            yearMonthDir.find(dir)?.let { m ->
                var year = m.groupValues[2].toInt()
                var month = m.groupValues[3].toInt() + if (forward) 1 else -1
                if (month > 12) { month = 1; year++ } else if (month < 1) { month = 12; year-- }
                dirs.add("${m.groupValues[1]}$year/${month.toString().padStart(2, '0')}")
            }

            val base = "${uri.scheme}://${uri.authority}"
            for (d in dirs) {
                val candidate = "$base$d/$newFile"
                if (candidate != currentUrl && verifyPage(candidate)) return candidate
            }
            return null
        }

        private fun findAdjacentUrl(doc: Document, currentUrl: String, forward: Boolean): String? {
            val dir = if (forward) nextDir else prevDir
            // 1. <link rel="next|prev"> en la cabecera y <a rel="next|prev">
            doc.selectFirst("link[rel=${dir.relValue}]")?.absUrl("href")
                ?.let { validateUrl(it, currentUrl) }?.let { return it }
            for (a in doc.select("a[rel~=(?i)^${dir.relValue}]")) {
                isValidLink(a, currentUrl)?.let { return it }
            }
            // 1b. Paginador de Blogger (Newer/Older Post), solo si pertenece a la misma novela
            val pagerTexts = if (forward) bloggerNewerTexts else bloggerOlderTexts
            val pagerSelector = if (forward) "a.blog-pager-newer-link" else "a.blog-pager-older-link"
            val pagerLinks = doc.select(pagerSelector) +
                    doc.select("a[href]").filter { normalizeText(it.text()) in pagerTexts }
            for (a in pagerLinks) {
                val candidate = isValidLink(a, currentUrl) ?: continue
                if (sameSeries(candidate, currentUrl)) return candidate
            }
            // 2. Selectores habituales en webs de novelas
            for (selector in dir.selectors) {
                for (a in doc.select(selector)) {
                    isValidLink(a, currentUrl)?.let { return it }
                }
            }
            val anchors = doc.select("a[href]")
            // 3. Por el texto del enlace ("Siguiente capítulo", "Capítulo anterior", "下一章"…)
            for (a in anchors) {
                if (looksLikeDirText(a, dir)) isValidLink(a, currentUrl)?.let { return it }
            }
            // 4. Último recurso: enlaces que son solo una flecha
            for (a in anchors) {
                if (a.text().trim() in dir.arrows) isValidLink(a, currentUrl)?.let { return it }
            }
            return null
        }

        // ---------- Extracción del contenido ----------

        private val contentSelectors = listOf(
            ".post-body", "[itemprop*=articleBody]", ".p-novel__body", "#novel_honbun", "#chr-content", "#chapter-content", ".chapter-content", ".reading-content",
            ".text-left", "[itemprop=articleBody]", ".entry-content", ".post-content",
            "article", "#content", ".content", "main"
        )

        private val junkSelectors = listOf(
            "script", "style", "noscript", "iframe", "form", "nav", "header", "footer", "aside",
            "svg", "button", "select", "ins.adsbygoogle", ".ads", ".advertisement",
            "#comments", ".comments", "#respond", ".sidebar", ".widget", ".sharedaddy",
            ".share-buttons", ".nav-links", ".navigation", ".related-posts",
            ".post-footer", ".post-share-buttons", "#blog-pager", ".blog-pager", ".post-labels"
        ).joinToString(",")

        private fun textLength(e: Element) = e.text().length

        private fun findMainElement(doc: Document): Element {
            for (selector in contentSelectors) {
                val el = doc.selectFirst(selector)
                if (el != null && textLength(el) > 500) return el
            }
            // Heurística: el contenedor con más texto en sus párrafos directos
            return doc.select("div, section, article, main, td")
                .maxByOrNull { container ->
                    container.children().filter { it.tagName() == "p" }
                        .sumOf { it.text().length }
                } ?: doc.body()
        }

        private fun extractContent(doc: Document): String {
            doc.select(junkSelectors).remove()
            val main = findMainElement(doc)

            // Muchas webs dejan las ilustraciones en src relativo o en atributos de carga diferida.
            // Markwon/Coil recibe la URL sin el contexto de Jsoup, por lo que aquí la convertimos
            // a absoluta antes de devolver el HTML del capítulo.
            for (img in main.select("img")) {
                val sourceAttribute = listOf(
                    "src", "data-src", "data-lazy-src", "data-original", "data-url"
                ).firstOrNull { attribute ->
                    val value = img.attr(attribute).trim()
                    value.isNotEmpty() && !value.startsWith("data:", ignoreCase = true)
                }
                if (sourceAttribute != null) {
                    val absolute = img.absUrl(sourceAttribute)
                    if (absolute.isNotBlank()) {
                        img.attr("src", absolute)
                    }
                }
            }

            // Si hay imágenes no reducimos el contenido a bloques con texto: así no se pierden
            // ilustraciones en <p><img ...></p>, <figure> o bloques sin texto.
            if (main.select("img").isNotEmpty()) return main.html()

            val blocks = main.select("h1, h2, h3, h4, p")
                .filter { it.text().isNotBlank() }

            // Solo nos quedamos con los bloques si contienen la mayor parte del texto; en muchos blogs el texto
            // va suelto separado por <br> y con unos pocos <p> sueltos se perdería casi todo el capítulo.
            val blocksText = blocks.sumOf { it.text().length }
            return if (blocks.size >= 3 && blocksText >= main.text().length * 0.6) {
                blocks.joinToString("") { it.outerHtml() }
            } else {
                main.html()
            }
        }
    }

    /**
     * Ficha de la web: la página del enlace y todos los capítulos siguientes encontrados.
     * Nombre y autor deben coincidir con los de "Abrir enlace" (título de la página y host) para que el id
     * (generateId) sea el mismo que el de la tarjeta guardada en el historial.
     */
    override suspend fun load(url: String): LoadResponse? {
        val start = normalizeUrl(url) ?: throw ErrorLoadingException("Invalid link")
        val first = fetchPage(start)
        // 1) índice de la web (pocas peticiones)  2) si no hay, seguir "siguiente" página a página
        val chapters = crawlFromIndex(start, first) ?: crawlForward(start, first)
        prefetched = null // la página ya está en PageCache para la descarga; así recargar la ficha siempre va a la red
        return StreamResponse(
            url = url,
            name = first.title,
            data = chapters,
            apiName = NAME,
            author = first.host,
            posterUrl = first.posterUrl
        )
    }

    override suspend fun loadHtml(url: String): String? {
        PageCache.take(url)?.let { cached ->
            if (prefetched?.first == url) prefetched = null
            return cached
        }
        return fetchPage(url).html
    }

    /**
     * "Recargar capítulo": siempre va a la red. Hay que descartar antes las tres copias que
     * [loadHtml] y [fetchPage] reutilizan, porque cualquiera de ellas puede ser anterior a un cambio
     * en la web (p. ej. un capítulo premium que pasa a ser gratuito): la guardada al recorrer la
     * cadena (hasta 24 h), la última página descargada y la adivinada al buscar el siguiente.
     */
    override suspend fun reloadHtml(url: String): String? {
        PageCache.discard(url)
        if (prefetched?.first == url) prefetched = null
        if (guessedBody?.first == url) guessedBody = null
        return fetchPage(url).html
    }
}
