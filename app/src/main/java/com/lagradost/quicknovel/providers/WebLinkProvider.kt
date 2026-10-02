package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.appWithInterceptor
import com.lagradost.quicknovel.USER_AGENT
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

/**
 * Proveedor genérico: abre cualquier enlace web en el lector extrayendo el texto principal de la página
 * y detectando el enlace al "siguiente capítulo" para encadenarlo al final.
 * No aparece en la lista de proveedores (Apis.apis); solo se resuelve por nombre, igual que Reddit.
 */
class WebLinkProvider : MainAPI() {
    override val mainUrl = "https://"
    override val name = NAME

    // Cloudflare solo se resuelve (WebView) cuando la respuesta es realmente un desafío; en webs normales no cuesta nada
    override val usesCloudFlareKiller = true

    data class WebPage(val title: String, val host: String, val html: String, val nextUrl: String?)

    companion object {
        const val NAME = "Web"
        private const val NEXT_FOLDER = "web_link_next"

        // Última página descargada, para reutilizarla una vez (título + lector)
        @Volatile
        private var prefetched: Pair<String, WebPage>? = null

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
                if (cachedUrl == url) return page
            }

            val response = appWithInterceptor.get(
                url,
                headers = mapOf(
                    "user-agent" to USER_AGENT,
                    "accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                    "accept-language" to "es,en;q=0.8"
                )
            )
            val document = Jsoup.parse(response.text, url)

            val host = hostOf(url) ?: "Web"
            val title = (document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()).trim().ifBlank { host }

            // IMPORTANTE: buscar el enlace antes de limpiar el documento (nav/footer se eliminan)
            val nextUrl = findNextUrl(document, url)

            val html = extractContent(document)
            if (Jsoup.parse(html).text().length < 80) {
                throw ErrorLoadingException("No content found")
            }

            if (nextUrl != null) {
                setKey(NEXT_FOLDER, url.hashCode().toString(), nextUrl)
            }

            val page = WebPage(title, host, html, nextUrl)
            prefetched = url to page
            return page
        }

        // ---------- Siguiente capítulo ----------

        private val nextSelectors = listOf(
            "a#next_chap", "a#next-chap", "a#btn-next", "a.btn-next", "a.next_chap",
            "a.next-chap", "a.nextchap", "a.next-chapter", ".next-chapter a", ".nav-next a",
            ".next_page a", "a.next"
        )

        private val nextTextExact = Regex(
            "^(next|next chapter|next part|next page|next episode|siguiente|siguiente capitulo|" +
                    "capitulo siguiente|siguiente parte|siguiente cap|proximo|proximo capitulo|sig|" +
                    "suivant|chapitre suivant|nachstes kapitel|nächstes kapitel|weiter|" +
                    "下一章|下一页|下一頁|下章|следующая глава|следующая)$"
        )
        private val nextTextPrefix = Regex(
            "^(next chapter|siguiente capitulo|capitulo siguiente|proximo capitulo|chapitre suivant|下一章)"
        )
        private val arrowOnly = setOf("→", "»", ">", "›", ">>", "»»", "»»»")
        private const val excludedAncestors =
            "#comments, .comments, #respond, .comment-list, .sidebar, .widget, .related-posts"

        private fun hostOf(url: String): String? = try {
            URI(url).host?.removePrefix("www.")
        } catch (e: Exception) {
            null
        }

        private fun normalizeText(raw: String): String = raw.lowercase()
            .replace('í', 'i').replace('ó', 'o').replace('á', 'a').replace('é', 'e').replace('ú', 'u')
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        private fun looksLikeNextText(a: Element): Boolean {
            val candidates = listOf(a.text(), a.attr("title"), a.attr("aria-label"))
            return candidates.any { raw ->
                val text = normalizeText(raw)
                text.isNotEmpty() && (nextTextExact.matches(text) || nextTextPrefix.containsMatchIn(text))
            }
        }

        private fun isValidNext(a: Element?, currentUrl: String): String? {
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

        private fun findNextUrl(doc: Document, currentUrl: String): String? {
            // 1. <link rel="next"> en la cabecera y <a rel="next">
            doc.selectFirst("link[rel=next]")?.absUrl("href")
                ?.let { validateUrl(it, currentUrl) }?.let { return it }
            for (a in doc.select("a[rel~=(?i)next]")) {
                isValidNext(a, currentUrl)?.let { return it }
            }
            // 2. Selectores habituales en webs de novelas
            for (selector in nextSelectors) {
                for (a in doc.select(selector)) {
                    isValidNext(a, currentUrl)?.let { return it }
                }
            }
            val anchors = doc.select("a[href]")
            // 3. Por el texto del enlace ("Siguiente capítulo", "Next", "下一章"…)
            for (a in anchors) {
                if (looksLikeNextText(a)) isValidNext(a, currentUrl)?.let { return it }
            }
            // 4. Último recurso: enlaces que son solo una flecha
            for (a in anchors) {
                if (a.text().trim() in arrowOnly) isValidNext(a, currentUrl)?.let { return it }
            }
            return null
        }

        // ---------- Extracción del contenido ----------

        private val contentSelectors = listOf(
            "#chr-content", "#chapter-content", ".chapter-content", ".reading-content",
            ".text-left", "[itemprop=articleBody]", ".entry-content", ".post-content",
            "article", "#content", ".content", "main"
        )

        private val junkSelectors = listOf(
            "script", "style", "noscript", "iframe", "form", "nav", "header", "footer", "aside",
            "svg", "button", "select", "ins.adsbygoogle", ".ads", ".advertisement",
            "#comments", ".comments", "#respond", ".sidebar", ".widget", ".sharedaddy",
            ".share-buttons", ".nav-links", ".navigation", ".related-posts"
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

            val blocks = main.select("h1, h2, h3, h4, p")
                .filter { it.text().isNotBlank() }

            return if (blocks.size >= 3) {
                blocks.joinToString("") { it.outerHtml() }
            } else {
                main.html()
            }
        }
    }

    override suspend fun loadHtml(url: String): String? {
        return fetchPage(url).html
    }
}
