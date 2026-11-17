package com.lagradost.quicknovel.providers

import android.net.Uri
import android.util.Log
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.fixUrl
import org.jsoup.nodes.Document

/**
 * Proveedor para https://fucknovelpia.com.
 *
 * El sitio dejó de usar el listado antiguo index.php y ahora publica los
 * resultados en search.php, las fichas en #chapter-list y el texto en .reader.
 */
class FuckNovelpiaProvider : MainAPI() {

    override val name = "FuckNovelpia"
    override val mainUrl = "https://fucknovelpia.com"
    override val lang = "en"
    override val hasMainPage = true
    override val usesCloudFlareKiller = true

    override val iconId = R.drawable.ic_baseline_public_24
    override val iconBackgroundId = R.color.subColorBg

    override val orderBys: List<Pair<String, String>> = listOf(
        "Newest" to "newest",
        "Popular" to "popular",
        "Oldest" to "oldest",
    )

    override val tags: List<Pair<String, String>> = listOf("Any" to "")

    private fun logD(message: String) = Log.d("FuckNovelpia", message)
    private fun logE(message: String, error: Throwable? = null) =
        Log.e("FuckNovelpia", message, error)

    /**
     * Las respuestas de Cloudflare/anti-scraping no contienen el HTML del sitio.
     * Detectarlas aquí evita interpretar esas páginas como fichas vacías.
     */
    private suspend fun getDocument(url: String): Document {
        val document = app.get(url).document
        val title = document.title().lowercase()
        val body = document.body()?.text()?.lowercase().orEmpty()
        val plainBody = body.trim()

        val blocked = title.contains("just a moment") ||
            title.contains("attention required") ||
            title.contains("access denied") ||
            title.contains("403 forbidden") ||
            title.contains("429 too many requests") ||
            plainBody == "blocked" ||
            body.contains("enable javascript and cookies to continue") ||
            body.contains("request blocked") ||
            (body.contains("cloudflare") && body.contains("ray id"))

        if (blocked) {
            logE("Página de bloqueo al solicitar $url (título: ${document.title()})")
            throw ErrorLoadingException(
                "FuckNovelpia está bloqueando la petición. Abre el proveedor de nuevo para " +
                    "permitir que se resuelva la comprobación anti-bots y vuelve a intentarlo."
            )
        }

        return document
    }

    private fun listingUrl(query: String, orderBy: String?, page: Int): String {
        val allowedSorts = setOf("newest", "popular", "oldest", "title", "year_desc", "year_asc")
        val sort = orderBy?.takeIf { it in allowedSorts } ?: "newest"
        val builder = Uri.parse("$mainUrl/search.php").buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("author", "")
            .appendQueryParameter("uploader", "")
            .appendQueryParameter("translator_group", "")
            .appendQueryParameter("country", "")
            .appendQueryParameter("year_from", "")
            .appendQueryParameter("year_to", "")
            .appendQueryParameter("status", "")
            .appendQueryParameter("language", "")
            .appendQueryParameter("read_only", "any")
            .appendQueryParameter("sort", sort)
            .appendQueryParameter("tag_mode", "AND")
            .appendQueryParameter("genre_mode", "AND")

        if (page > 1) builder.appendQueryParameter("page", page.toString())
        return builder.build().toString()
    }

    /** Soporta el HTML actual y conserva selectores del formato anterior como fallback. */
    private fun parseNovels(document: Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        return document.select(".card-book a, div.card a.card-link").mapNotNull { card ->
            val href = card.attr("href").trim().ifBlank { return@mapNotNull null }
            val url = fixUrl(href)
            if (!seen.add(url)) return@mapNotNull null

            val image = card.selectFirst("img")
            val title = image?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: card.selectFirst(".title, .book-title, strong.book-title")
                    ?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: card.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val poster = image?.let {
                val imageUrl = it.attr("src").ifBlank { it.attr("data-src") }
                    .ifBlank { it.attr("data-lazy-src") }
                imageUrl.takeIf { value -> value.isNotBlank() }?.let { value -> fixUrl(value) }
            }

            SearchResponse(
                name = title,
                url = url,
                apiName = name,
                posterUrl = poster,
            )
        }.also { logD("parseNovels → ${it.size} novelas") }
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        val url = listingUrl(query = "", orderBy = orderBy, page = page)
        logD("loadMainPage → $url")
        val document = getDocument(url)
        return HeadMainPageResponse(url = url, list = parseNovels(document))
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = listingUrl(query = query.trim(), orderBy = "newest", page = 1)
        logD("search → $url")
        val document = getDocument(url)
        return parseNovels(document).ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        logD("load → $url")
        val document = getDocument(url)

        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                ?.removeSuffix(" · Read on FUCKNOVELPIA")
                ?.removeSuffix(" · FUCKNOVELPIA")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("No se encontró el título de la novela en FuckNovelpia.")

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".cover img, .hero-cover img, .novel-cover img")?.let {
                val imageUrl = it.attr("src").ifBlank { it.attr("data-src") }
                    .ifBlank { it.attr("data-lazy-src") }
                imageUrl.takeIf { value -> value.isNotBlank() }?.let { value -> fixUrl(value) }
            }

        val synopsis = document.selectFirst(".hero-summary, .description, .summary, .synopsis, section#description")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name='description']")?.attr("content")
                ?.trim()?.takeIf { it.isNotBlank() }

        val statusText = document.selectFirst(".status-badge, .status, .novel-status")
            ?.text()?.lowercase()?.trim().orEmpty()
        val status: ReleaseStatus? = when {
            statusText.contains("completed") -> ReleaseStatus.Completed
            statusText.contains("ongoing") -> ReleaseStatus.Ongoing
            statusText.contains("hiatus") -> ReleaseStatus.Paused
            statusText.contains("dropped") -> ReleaseStatus.Dropped
            else -> null
        }

        val author = document.selectFirst(".info-list li")?.text()
            ?.replace(Regex("(?i)^author:\\s*"), "")?.trim()?.ifBlank { null }
            ?: document.selectFirst(".author, [rel='author']")?.text()?.trim()?.ifBlank { null }

        val genres = document.select(".genre-pill, a[href*='genres'], a[href*='tags[']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val chapterEntries = document.select("#chapter-list li").ifEmpty {
            document.select("a[href*='chapter.php']")
        }
        val seenChapterUrls = mutableSetOf<String>()
        val chapters = chapterEntries.mapIndexedNotNull { index, entry ->
            val link = if (entry.tagName() == "a") entry else entry.selectFirst("a[href]")
                ?: return@mapIndexedNotNull null
            val href = link.attr("href").trim().ifBlank { return@mapIndexedNotNull null }
            val chapterUrl = fixUrl(href)
            if (!seenChapterUrls.add(chapterUrl)) return@mapIndexedNotNull null

            val chapterTitle = entry.selectFirst(".chapter-item-main")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: link.text().trim().ifBlank { "Chapter ${index + 1}" }
            val imageMarker = if (entry.selectFirst(".chapter-item-flag") != null) " [IMG]" else ""
            ChapterData(name = chapterTitle + imageMarker, url = chapterUrl)
        }

        if (chapters.isEmpty()) {
            logE("No se encontraron capítulos en $url")
            throw ErrorLoadingException(
                "No se encontraron capítulos en FuckNovelpia. La página puede estar bloqueada " +
                    "o la estructura del sitio puede haber cambiado."
            )
        }

        logD("load → ${chapters.size} capítulos")
        return StreamResponse(
            url = url,
            name = title,
            data = chapters,
            apiName = name,
            posterUrl = poster,
            author = author,
            synopsis = synopsis,
            tags = genres.ifEmpty { null },
            status = status,
        )
    }

    override suspend fun loadHtml(url: String): String? {
        logD("loadHtml → $url")
        val document = getDocument(url)
        val reader = document.selectFirst(".reader, #chapter-content, .chapter-content")
            ?: throw ErrorLoadingException(
                "No se encontró el texto del capítulo en FuckNovelpia. " +
                    "La página puede estar bloqueada o el lector puede haber cambiado."
            )

        reader.select(".reader-nav, script, style").remove()
        val html = reader.html().trim()
        if (html.isBlank()) {
            throw ErrorLoadingException("FuckNovelpia devolvió un capítulo vacío.")
        }
        return html
    }
}
