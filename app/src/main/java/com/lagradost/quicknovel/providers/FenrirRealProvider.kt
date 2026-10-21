package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.newChapterData
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.util.AppUtils
import com.lagradost.quicknovel.newReview
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlin.coroutines.cancellation.CancellationException
import kotlin.collections.map

class FenrirRealProvider : MainAPI() {
    override val name = "FenrirRealm"
    override val mainUrl = "https://fenrirealm.com"
    override val iconId = R.drawable.icon_fenrirealm
    override val hasMainPage = true

    //I don't know why, but this fixes the timeout issue for this provider
    override val rateLimitTime = 3000L
    override val hasReviews = true
    override val usesCloudFlareKiller = true

    override val mainCategories = listOf(
        "All" to "any",
        "Completed" to "completed",
        "Ongoing" to "on-going"
    )
    override val orderBys =
        listOf(
            "Popular" to "popular",
            "Latest" to "latest",
            "Updated" to "updated"
        )
    override val tags = listOf(
        "All" to "0",
        "Action" to "1",
        "Adult" to "2",
        "Adventure" to "3",
        "Comedy" to "4",
        "Drama" to "5",
        "Ecchi" to "6",
        "Fantasy" to "7",
        "Gender Bender" to "8",
        "Harem" to "9",
        "Historical" to "10",
        "Horror" to "11",
        "Josei" to "12",
        "Martial Arts" to "13",
        "Mature" to "14",
        "Mecha" to "15",
        "Mystery" to "16",
        "Psychological" to "17",
        "Romance" to "18",
        "School Life" to "19",
        "Sci-fi" to "20",
        "Seinen" to "21",
        "Shoujo" to "22",
        "Shoujo Ai" to "23",
        "Shounen" to "24",
        "Shounen Ai" to "25",
        "Slice of Life" to "26",
        "Smut" to "27",
        "Sports" to "28",
        "Supernatural" to "29",
        "Tragedy" to "30",
        "Wuxia" to "31",
        "Xianxia" to "32",
        "Xuanhuan" to "33",
        "Yaoi" to "34",
        "Yuri" to "35",
    )

    private companion object {
        const val API_CHAPTER_PATH = "/api/new/v2/chapters/"
        val CONTENT_KEY = Regex("(?i)(chapter_?)?(content|body|text|html)")
        val HTML_TAG = Regex("(?i)<\\s*(p|br|div|h[1-6]|span|em|strong|i|b|ul|ol|li|blockquote|hr)\\b")
    }

    fun String.getSlugFromUrl() = this.replace("$mainUrl/series/", "")

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url =
            if (tag != "0") "$mainUrl/api/new/v2/series?page=$page&per_page=12&status=$mainCategory&sort=$orderBy&tags%5B%5D=$tag"
            else "$mainUrl/api/new/v2/series?page=$page&per_page=24&status=$mainCategory&sort=$orderBy"
        val document = app.get(url).parsed<FenrirMainPageResponse>()
        val returnValue = document.data.map { novel ->
            newSearchResponse(
                name = novel.title,
                url = mainUrl + "/series/" + novel.slug
            ) {
                posterUrl = fixUrlNull(novel.cover)
            }

        }
        return HeadMainPageResponse(url, returnValue)
    }

    suspend fun getRelated(url: String): List<SearchResponse> {
        val name = url.substringAfter("/series/").removeSuffix("/")
        val url = "$mainUrl/api/new/v2/series/$name/recommendations?limit=10"
        val document = app.get(url).parsed<FenrirMainPageResponse>()
        return document.data.map { element ->
            val href = "$mainUrl/series/${element.slug}"
            val title = element.title
            newSearchResponse(
                name = title,
                url = href
            ) {
                posterUrl = fixUrlNull(
                    element.cover
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        document.select("style, iframe, svg, noscript").remove()//avoid out of memory
        val infoDiv = document.select("div#series-info")
        val chapters = app
            .get("$mainUrl/api/new/v2/series/${url.getSlugFromUrl()}/chapters")
            .parsed<Array<ChapterInf>>()
            .mapNotNull { ch ->
                if (ch.locked.price > 0) null
                else newChapterData(
                    "${ch.name} ${if (ch.title.isNullOrEmpty()) "" else "- ${ch.title}"}",
                    // The site now serves the chapter text from its own api, by chapter id.
                    // The SvelteKit __data.json url is only kept for chapters without an id.
                    if (ch.id != null) "$mainUrl$API_CHAPTER_PATH${ch.id}"
                    else "$url/${ch.slug}/__data.json?x-sveltekit-invalidated=10001"
                ) {
                    dateOfRelease = ch.updatedAt.split("T")[0]
                }
            }

        val title = infoDiv.selectFirst("h1")?.text() ?: throw Exception("Title not found")
        val script =
            document.select("script").find { it.html().contains("seriesData") }?.html() ?: ""

        return newStreamResponse(title, url, chapters) {
            infoDiv.select(" > div").forEachIndexed { index, inf ->
                when (index) {
                    1 -> {
                        setStatus(inf.selectFirst("span")?.text())
                    }

                    2 -> {
                        this.author = inf.selectFirst("a")?.text()
                    }

                    3 -> {
                        this.tags = infoDiv.select("a").mapNotNull {
                            it.text().trim().takeIf { text -> text.isNotEmpty() }
                        }
                    }
                }
            }
            this.synopsis = document.selectFirst("div.synopsis")?.text()
            this.posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            related = getRelated(url)
            reviewData = Regex("""seriesData:\{id:(\d+)""").find(script)?.groupValues?.get(1)
        }
    }


    override suspend fun loadReviews(url: String, page: Int, data: String?): List<UserReview> {
        val libraryId = data ?: return emptyList()

        val realUrl = "$mainUrl/api/new/v2/comments/series/$libraryId?page=$page&sort=latest"
        val res = app.get(realUrl).parsed<CommentsResponse>()
        return res.data.map { comment ->
            newReview(comment.content) {
                username = comment.user.username
                date = comment.createdAt.split("T").firstOrNull()
                avatarUrl = comment.user.avatar
            }
        }
    }

    override suspend fun loadHtml(url: String): String {
        val raw = (if (url.contains(API_CHAPTER_PATH)) {
            fetchOrNull { loadChapterFromApi(url) }
        } else {
            // Chapters saved with the old url format (SvelteKit data endpoint)
            val plainUrl = url.substringBefore("?")
            val pageUrl = plainUrl.substringBefore("/__data.json")
            // 1) endpoint as built by the old load()
            // 2) same endpoint without the partial-invalidation mask, so every node is sent
            // 3) the server rendered chapter page
            fetchOrNull { loadChapterFromData(url) }
                ?: (if (plainUrl != url) fetchOrNull { loadChapterFromData(plainUrl) } else null)
                ?: fetchOrNull { loadChapterFromPage(pageUrl) }
        }) ?: throw ErrorLoadingException("Can't access to chapter: $url")

        return cleanChapterHtml(tiptapToHtml(raw) ?: raw)
    }

    /** `/api/new/v2/chapters/{id}` -> `{ "content": "<html>" | "{\"type\":\"doc\",...}" }` */
    private suspend fun loadChapterFromApi(apiUrl: String): String? {
        val root = AppUtils.mapper.readTree(app.get(apiUrl).text) ?: return null
        val content = root.get("content") ?: root.path("data").get("content") ?: return null
        if (content.isNull) return null
        return if (content.isTextual) content.asText() else content.toString()
    }

    /** The chapter is sometimes a TipTap/ProseMirror document instead of html */
    private fun tiptapToHtml(content: String): String? {
        if (!content.trimStart().startsWith("{")) return null
        val root = try {
            AppUtils.mapper.readTree(content)
        } catch (e: Exception) {
            return null
        }
        if (root == null || root.path("type").asText() != "doc") return null
        val sb = StringBuilder()
        root.path("content").forEach { renderNode(it, sb) }
        return sb.toString()
    }

    private fun renderChildren(node: JsonNode): String {
        val sb = StringBuilder()
        node.path("content").forEach { renderNode(it, sb) }
        return sb.toString()
    }

    private fun renderNode(node: JsonNode, sb: StringBuilder) {
        when (node.path("type").asText()) {
            "text" -> {
                var text = escapeHtml(node.path("text").asText())
                node.path("marks").forEach { mark ->
                    text = when (mark.path("type").asText()) {
                        "bold" -> "<b>$text</b>"
                        "italic" -> "<i>$text</i>"
                        "underline" -> "<u>$text</u>"
                        "strike" -> "<s>$text</s>"
                        "link" -> "<a href=\"${escapeHtml(mark.path("attrs").path("href").asText())}\">$text</a>"
                        else -> text
                    }
                }
                sb.append(text)
            }

            "hardBreak" -> sb.append("<br>")
            "horizontalRule" -> sb.append("<hr>")
            "paragraph" -> {
                val inner = renderChildren(node)
                if (inner.isNotBlank()) sb.append("<p>").append(inner).append("</p>")
            }

            "heading" -> {
                val level = node.path("attrs").path("level").asInt(1).coerceIn(1, 6)
                val inner = renderChildren(node)
                if (inner.isNotBlank()) sb.append("<h$level>").append(inner).append("</h$level>")
            }

            "blockquote" -> sb.append("<blockquote>").append(renderChildren(node)).append("</blockquote>")
            "bulletList" -> sb.append("<ul>").append(renderChildren(node)).append("</ul>")
            "orderedList" -> sb.append("<ol>").append(renderChildren(node)).append("</ol>")
            "listItem" -> sb.append("<li>").append(renderChildren(node)).append("</li>")
            // Unknown node: keep whatever text it contains
            else -> node.path("content").forEach { renderNode(it, sb) }
        }
    }

    private fun escapeHtml(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private suspend fun fetchOrNull(block: suspend () -> String?): String? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    /**
     * The chapter used to be read from a hardcoded index (`nodes[1].data[6]`) of the flattened
     * SvelteKit payload. Any change in the payload (a new field, a different order, one more
     * layout node) made that index point to something else, so the chapter came out truncated or
     * wrong. Here the payload is un-flattened properly and the chapter text is looked up by name.
     */
    private suspend fun loadChapterFromData(dataUrl: String): String? {
        val response = app.get(dataUrl).parsed<ChapterResponse>()

        val keyed = ArrayList<String>()
        val others = ArrayList<String>()
        for (node in response.nodes) {
            if (node.type != "data") continue
            val flat = node.data ?: continue
            if (flat.isEmpty()) continue
            val root = FenrirDevalueReader(flat).read(0)
            collectTexts(root, null, keyed, others, 0)
        }

        // Prefer values stored under a "content"-like key, the longest one is the chapter body
        keyed.filter { it.trim().length >= 20 }.maxByOrNull { it.length }?.let { return it }

        // Last resort inside the payload: the longest long-enough string that is not a link
        return others
            .filter { it.length >= 200 && !it.trimStart().startsWith("http") }
            .maxByOrNull { it.length }
    }

    private fun collectTexts(
        node: Any?,
        key: String?,
        keyed: MutableList<String>,
        others: MutableList<String>,
        depth: Int
    ) {
        if (depth > 12) return
        when (node) {
            is String -> if (key != null && CONTENT_KEY.matches(key)) keyed.add(node) else others.add(node)
            is Map<*, *> -> node.forEach { (k, v) ->
                collectTexts(v, k?.toString(), keyed, others, depth + 1)
            }

            is List<*> -> {
                // Some payloads keep the chapter as a list of paragraphs
                if (key != null && CONTENT_KEY.matches(key) && node.size > 1 && node.all { it is String }) {
                    keyed.add(node.joinToString("\n"))
                } else {
                    node.forEach { collectTexts(it, key, keyed, others, depth + 1) }
                }
            }
        }
    }

    /** Fallback: the chapter page is server rendered, take the block with most paragraphs */
    private suspend fun loadChapterFromPage(pageUrl: String): String? {
        val document = app.get(pageUrl).document
        document.select("script, style, iframe, svg, noscript, nav, header, footer").remove()
        val best = document.select("div, article, section, main")
            .maxByOrNull { el -> el.children().count { it.tagName() == "p" } }
            ?: return null
        return if (best.children().count { it.tagName() == "p" } >= 3) best.html() else null
    }

    private fun cleanChapterHtml(raw: String): String {
        var text = raw.trim()
        // Escaped html ("&lt;p&gt;...")
        if (!text.contains('<') && text.contains("&lt;")) {
            text = Parser.unescapeEntities(text, false)
        }
        // Plain text chapter: without this the whole chapter is rendered as one single block
        val body = if (HTML_TAG.containsMatchIn(text)) text else text
            .split(Regex("\r?\n"))
            .filter { it.isNotBlank() }
            .joinToString("") { "<p>${escapeHtml(it.trim())}</p>" }

        val chapterHtml = Jsoup.parse(body)
        // Anti-scraping / watermark elements the site injects into the chapter
        chapterHtml.select("script, style, iframe, svg, noscript, .reader-attribution, [data-fr-attr]")
            .remove()
        chapterHtml.select("[aria-hidden=\"true\"]").remove()
        chapterHtml.select("p, div, span, small").forEach { el ->
            val t = el.text().trim()
            if (t.length < 200 && (t.startsWith("Copy reference:") || t.startsWith("Bookmark:"))) {
                el.remove()
            }
        }
        return chapterHtml.html()
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/new/v2/series?page=1&per_page=5&search=$query"
        val document = app.get(url).parsed<FenrirMainPageResponse>()

        return document.data.map { novel ->
            val title = novel.title
            val novelUrl = mainUrl + "/series/" + novel.slug
            newSearchResponse(title, novelUrl) {
                posterUrl = fixUrlNull(novel.cover)
            }
        }
    }


    data class FenrirMainPageResponse(
        @JsonProperty("data")
        val data: List<Daum>,
    )

    data class Daum(
        // Novel inf
        @JsonProperty("title")
        val title: String,
        @JsonProperty("slug")
        val slug: String,
        @JsonProperty("cover")
        val cover: String,
    )

    data class ChapterInf(
        @JsonProperty("id")
        val id: Long?,
        @JsonProperty("slug")
        val slug: String,
        @JsonProperty("name")
        val name: String,
        @JsonProperty("title")
        val title: String?,
        @JsonProperty("updated_at")
        val updatedAt: String,
        @JsonProperty("locked")
        val locked: Locked,
    )

    data class Locked(
        @JsonProperty("price")
        val price: Int,
    )

    data class CommentsResponse(
        @JsonProperty("data")
        val data: List<CommentData>
    )

    data class CommentData(
        @JsonProperty("content")
        val content: String,
        @JsonProperty("created_at")
        val createdAt: String,
        @JsonProperty("user")
        val user: CommentUser
    )

    data class CommentUser(
        @JsonProperty("username")
        val username: String,
        @JsonProperty("avatar")
        val avatar: String?
    )

    data class ChapterResponse(
        @JsonProperty("nodes")
        val nodes: List<ChapterResponseData>
    )
    data class ChapterResponseData(
        @JsonProperty("type")
        val type: String,
        @JsonProperty("data")
        val data: List<Any?>?
    )
}

/**
 * Minimal reader for the "devalue" format SvelteKit uses in `__data.json`: a flat array where
 * element 0 is the root and every object/array holds *indices* into that same array.
 * Negative indices are undefined / NaN / Infinity, which are returned as null.
 */
private class FenrirDevalueReader(private val values: List<Any?>) {
    private val cache = HashMap<Int, Any?>()
    private val inProgress = HashSet<Int>()

    fun read(index: Int): Any? {
        if (index < 0 || index >= values.size) return null
        if (cache.containsKey(index)) return cache[index]
        if (!inProgress.add(index)) return null // circular reference

        val result: Any? = when (val raw = values[index]) {
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                raw.forEach { (k, v) -> out[k.toString()] = ref(v) }
                out
            }

            is List<*> -> {
                val tag = raw.firstOrNull()
                if (tag is String) special(tag, raw) else raw.map { ref(it) }
            }

            else -> raw
        }
        inProgress.remove(index)
        cache[index] = result
        return result
    }

    private fun ref(value: Any?): Any? = (value as? Number)?.let { read(it.toInt()) }

    private fun special(tag: String, raw: List<*>): Any? = when (tag) {
        "Date", "BigInt" -> raw.getOrNull(1)?.toString()
        "Set" -> raw.drop(1).map { ref(it) }
        "Map" -> raw.drop(1).chunked(2).filter { it.size == 2 }.associate { (k, v) -> ref(k).toString() to ref(v) }
        "null" -> raw.drop(1).chunked(2).filter { it.size == 2 }.associate { (k, v) -> k.toString() to ref(v) }
        "Object" -> ref(raw.getOrNull(1))
        else -> raw.drop(1).map { ref(it) } // custom reducers
    }
}