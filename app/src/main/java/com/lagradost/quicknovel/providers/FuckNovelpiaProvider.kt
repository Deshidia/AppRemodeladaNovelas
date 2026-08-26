package com.lagradost.quicknovel.providers

import android.util.Log
import com.lagradost.quicknovel.ChapterData
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
 * Proveedor para https://fucknovelpia.com
 */
class FuckNovelpiaProvider : MainAPI() {

    override val name    = "FuckNovelpia"
    override val mainUrl = "https://fucknovelpia.com"
    override val lang    = "ko"
    override val hasMainPage = true

    // Usamos un icono de red global y el color subColorBg (rosado/rojo) que sí existe en el proyecto
    override val iconId = R.drawable.ic_baseline_public_24
    override val iconBackgroundId = R.color.subColorBg

    override val orderBys: List<Pair<String, String>> = listOf(
        Pair("Newest",  "newest"),
        Pair("Popular", "popular"),
        Pair("Oldest",  "oldest"),
    )

    override val tags: List<Pair<String, String>> = listOf(
        Pair("Any", ""),
    )

    private fun logD(msg: String) = Log.d("FuckNovelpia", msg)
    private fun logE(msg: String, t: Throwable? = null) = Log.e("FuckNovelpia", msg, t)

    // ── Parseo de listado ─────────────────────────────────────────────────────
    private fun parseNovels(doc: Document): List<SearchResponse> {
        val seen    = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        for (card in doc.select("div.card")) {
            val href = card.selectFirst("a.card-link")?.attr("href") ?: continue
            if (href.isBlank()) continue
            val url = fixUrl(href)
            if (!seen.add(url)) continue

            val title = card.selectFirst("strong.book-title")?.text()?.trim() ?: continue
            if (title.isBlank()) continue

            val img = card.selectFirst("div.cover img")?.attr("src")?.ifBlank { null }

            results.add(
                SearchResponse(
                    name      = title,
                    url       = url,
                    apiName   = this.name,
                    posterUrl = img,
                )
            )
        }

        logD("parseNovels → ${results.size} novelas")
        return results
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        val sb = StringBuilder("$mainUrl/index.php?page=$page")
        if (!orderBy.isNullOrBlank() && orderBy != "newest") {
            sb.append("&sort=$orderBy")
        }
        val url = sb.toString()

        logD("loadMainPage → $url")
        val doc = app.get(url).document
        return HeadMainPageResponse(url = url, list = parseNovels(doc))
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encoded = query.trim().replace(" ", "+")
        val url = "$mainUrl/index.php?q=$encoded"

        logD("search → $url")
        val doc = app.get(url).document
        return parseNovels(doc).ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        logD("load → $url")
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?.removeSuffix(" · Read on FUCKNOVELPIA")
                ?.removeSuffix(" · FUCKNOVELPIA")
                ?.trim()

        if (title.isNullOrBlank()) {
            logE("Sin título en $url")
            return null
        }

        val posterUrl = doc.selectFirst("meta[property='og:image']")
            ?.attr("content")?.ifBlank { null }

        val synopsis = doc.selectFirst(".description, .summary, .synopsis, section#description")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val statusText = doc.selectFirst(".status, .novel-status")?.text()?.lowercase()?.trim() ?: ""
        val status: ReleaseStatus? = when {
            statusText.contains("completed") -> ReleaseStatus.Completed
            statusText.contains("ongoing")   -> ReleaseStatus.Ongoing
            statusText.contains("hiatus")    -> ReleaseStatus.Paused
            statusText.contains("dropped")   -> ReleaseStatus.Dropped
            else                             -> null
        }

        val author = doc.selectFirst("li:contains(Author) span, .author")
            ?.text()?.removePrefix("Author:")?.trim()?.ifBlank { null }

        val genreTags: List<String> = doc.select("a[href*='tags[']")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        val seenUrls = mutableSetOf<String>()
        val data: List<ChapterData> = doc.select("a[href*='chapter.php']")
            .mapIndexedNotNull { idx, el ->
                val chUrl = fixUrl(el.attr("href"))
                if (!seenUrls.add(chUrl)) return@mapIndexedNotNull null
                val chTitle = el.text().trim().ifBlank { "Chapter ${idx + 1}" }
                ChapterData(name = chTitle, url = chUrl)
            }

        logD("load → ${data.size} capítulos")
        return StreamResponse(
            url       = url,
            name      = title,
            data      = data,
            apiName   = this.name,
            posterUrl = posterUrl,
            author    = author,
            synopsis  = synopsis,
            tags      = genreTags.ifEmpty { null },
            status    = status,
        )
    }

    override suspend fun loadHtml(url: String): String? {
        logD("loadHtml → $url")
        val doc = app.get(url).document

        val reader = doc.selectFirst("div.reader") ?: return null

        reader.select("div.reader-nav").remove()

        val html = reader.html().trim()
        return html.ifBlank { null }
    }
}
