package com.lagradost.quicknovel.util

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.reflect.KClass

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeepLTranslation(
    @JsonProperty("text") val text: String?,
    @JsonProperty("detected_source_language") val detectedSourceLanguage: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeepLResponse(
    @JsonProperty("translations") val translations: List<DeepLTranslation>? = null,
    // Error responses look like {"message": "..."} (sometimes with "detail" / "title").
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("detail") val detail: String? = null,
    @JsonProperty("title") val title: String? = null
)

/** Errors that retrying cannot fix (bad key, quota exhausted, IP blocked, unsupported language...). */
class DeepLFatalException(message: String) : Exception(message)

/** Temporary failures (server overloaded...) that are worth retrying. */
private class DeepLRetryException(message: String) : Exception(message)

/**
 * DeepL translation with two modes, chosen by whether the user typed an API key:
 *
 *  - **No key (default): the free web/app endpoint**, the same keyless route used by
 *    Translation-Aggregator and DeepLX. This is an *undocumented* endpoint (the one DeepL's own
 *    apps talk to), so it can change or start rejecting requests at any time and it is
 *    rate-limited per IP. Request shape copied from DeepLX (Aug 2026), which moved off the older
 *    `www2.deepl.com/jsonrpc` endpoint that Translation-Aggregator still uses because DeepL
 *    throttles it heavily for anonymous traffic.
 *  - **With a key: the official API** (https://developers.deepl.com). Free keys end in ":fx".
 */
object DeepLTranslate {
    private const val TAG = "DeepLTranslate"

    // ---- official API
    private const val FREE_URL = "https://api-free.deepl.com/v2/translate"
    private const val PRO_URL = "https://api.deepl.com/v2/translate"

    // DeepL accepts at most 50 texts per request and a request body of at most 128 KiB.
    // The body limit is in bytes (not characters) and JSON escaping adds overhead, so stay
    // comfortably under it.
    private const val MAX_TEXTS_PER_REQUEST = 50
    private const val MAX_BYTES_PER_REQUEST = 100_000

    // ---- keyless (free) endpoint
    private const val KEYLESS_URL = "https://oneshot-free.www.deepl.com/v1/translate"

    // The anonymous endpoint rejects requests whose `text` array totals more than 1500 chars.
    private const val KEYLESS_MAX_CHARS = 1500

    // Identity of the DeepL app the endpoint expects (copied from DeepLX, which pins the iOS
    // app 26.42). If DeepL starts answering 403 "outdated client" these need bumping.
    private const val APP_OS = "iOS"
    private const val APP_OS_VERSION = "26.0"
    private const val APP_VERSION = "26.42"
    private const val APP_BUILD = "5443737"
    private const val USER_AGENT = "DeepL/26.42 CFNetwork/3826.600.41 Darwin/25.0.0"

    // Stable for the life of the process, like a real app install/session.
    private val instanceId = UUID.randomUUID().toString()
    private val sessionId = UUID.randomUUID().toString()

    // DeepL temporarily bans IPs that hammer the free endpoint, so requests are spaced out.
    // (internal + var so tests can set it to 0)
    internal var keylessMinIntervalMs = 1000L
    private val throttleMutex = Mutex()
    private var lastKeylessRequestAt = 0L

    private const val MAX_RETRIES = 3

    private sealed class Mode {
        class Official(val key: String) : Mode()
        object Keyless : Mode()
    }

    // A dedicated client on purpose: the global MainActivity.app ignores SSL errors, which is
    // not acceptable for requests that may carry an API key.
    private val deeplApp = Requests(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build(),
        responseParser = object : ResponseParser {
            val mapper = jacksonObjectMapper().configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false
            )

            override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
                mapper.readValue(text, kClass.java)

            override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
                try {
                    parse(text, kClass)
                } catch (e: Exception) {
                    null
                }

            override fun writeValueAsString(obj: Any): String = mapper.writeValueAsString(obj)
        }
    )

    /**
     * Translates [paragraphs] and returns a list of exactly the same size, in the same order.
     * Blank paragraphs are never sent and are returned untouched.
     *
     * [apiKey] blank => free keyless endpoint, otherwise the official API.
     */
    suspend fun deeplTranslate(
        paragraphs: List<String>,
        apiKey: String,
        from: String,
        to: String,
        loading: suspend (Int, Int) -> Unit = { _, _ -> }
    ): List<String> {
        if (paragraphs.isEmpty()) return paragraphs

        // Only translate what has actual text; remember where each piece goes.
        val indices = paragraphs.indices.filter { paragraphs[it].isNotBlank() }
        if (indices.isEmpty()) return paragraphs

        val key = apiKey.trim()
        return if (key.isBlank()) {
            translateKeyless(paragraphs, indices, from, to, loading)
        } else {
            translateOfficial(paragraphs, indices, key, from, to, loading)
        }
    }

    // ------------------------------------------------------------------ official API

    private suspend fun translateOfficial(
        paragraphs: List<String>,
        indices: List<Int>,
        key: String,
        from: String,
        to: String,
        loading: suspend (Int, Int) -> Unit
    ): List<String> {
        val sourceLang = officialSourceLang(from)
        val targetLang = officialTargetLang(to)

        val batches = indices.chunkForOfficial(paragraphs)
        val result = paragraphs.toMutableList()

        batches.forEachIndexed { i, batch ->
            loading.invoke(i, batches.size)
            val translated = translateBatch(
                batch.map { paragraphs[it] }, Mode.Official(key), sourceLang, targetLang
            )
            batch.forEachIndexed { j, paragraphIndex ->
                result[paragraphIndex] = translated[j]
            }
        }
        return result
    }

    // ------------------------------------------------------------------ keyless endpoint

    private class Piece(val paragraphIndex: Int, val text: String)

    private suspend fun translateKeyless(
        paragraphs: List<String>,
        indices: List<Int>,
        from: String,
        to: String,
        loading: suspend (Int, Int) -> Unit
    ): List<String> {
        val sourceLang = keylessLang(from, isTarget = false)
        val targetLang = keylessLang(to, isTarget = true)

        // The free endpoint caps the *total* text per request, so a (rare) paragraph longer
        // than the cap is cut at sentence boundaries and stitched back together afterwards.
        val pieces = indices.flatMap { index ->
            splitLongText(paragraphs[index], KEYLESS_MAX_CHARS).map { Piece(index, it) }
        }

        val batches = pieces.chunkForKeyless()
        val translatedPieces = ArrayList<String>(pieces.size)

        batches.forEachIndexed { i, batch ->
            loading.invoke(i, batches.size)
            translatedPieces.addAll(
                translateBatch(batch.map { it.text }, Mode.Keyless, sourceLang, targetLang)
            )
        }

        // CJK targets are written without spaces between sentences.
        val joiner = if (to.lowercase() in setOf("ja", "zh")) "" else " "
        val rebuilt = HashMap<Int, StringBuilder>()
        pieces.forEachIndexed { i, piece ->
            val sb = rebuilt.getOrPut(piece.paragraphIndex) { StringBuilder() }
            if (sb.isNotEmpty()) sb.append(joiner)
            sb.append(translatedPieces[i])
        }

        val result = paragraphs.toMutableList()
        rebuilt.forEach { (index, sb) -> result[index] = sb.toString() }
        return result
    }

    private suspend fun throttleKeyless() {
        throttleMutex.withLock {
            val wait = keylessMinIntervalMs - (System.currentTimeMillis() - lastKeylessRequestAt)
            if (wait > 0) delay(wait)
            lastKeylessRequestAt = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------------ shared request logic

    private suspend fun translateBatch(
        texts: List<String>,
        mode: Mode,
        sourceLang: String,
        targetLang: String
    ): List<String> {
        val url: String
        val headers: Map<String, String>
        val body: Map<String, Any>

        when (mode) {
            is Mode.Official -> {
                url = if (mode.key.endsWith(":fx")) FREE_URL else PRO_URL
                headers = mapOf("Authorization" to "DeepL-Auth-Key ${mode.key}")
                body = mapOf(
                    "text" to texts,
                    "source_lang" to sourceLang,
                    "target_lang" to targetLang
                )
            }

            Mode.Keyless -> {
                url = KEYLESS_URL
                // "Authorization: None" is the literal value DeepL's apps send when logged out.
                headers = mapOf(
                    "Authorization" to "None",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "x-app-os-version" to APP_OS_VERSION,
                    "x-app-instance-id" to instanceId,
                    "x-app-session-id" to sessionId
                )
                body = mapOf(
                    "text" to texts,
                    "target_lang" to targetLang,
                    "source_lang" to sourceLang,
                    "usage_type" to "translate",
                    "app_information" to mapOf(
                        "os" to APP_OS,
                        "os_version" to APP_OS_VERSION,
                        "app_version" to APP_VERSION,
                        "app_build" to APP_BUILD,
                        "instance_id" to instanceId
                    )
                )
            }
        }
        val keyless = mode is Mode.Keyless

        var attempt = 0
        while (true) {
            try {
                if (keyless) throttleKeyless()

                val response = deeplApp.post(url = url, headers = headers, json = body)

                if (response.isSuccessful) {
                    val translations = response.parsed<DeepLResponse>().translations
                        ?.map { it.text ?: "" }

                    if (translations != null && translations.size == texts.size) {
                        return translations
                    }

                    // Not sure the free endpoint always honours several texts per request.
                    // Rather than guess which result belongs to which paragraph, fall back
                    // to one text per request (slower, but always correct).
                    if (keyless && texts.size > 1) {
                        Log.w(TAG, "Got ${translations?.size ?: 0} results for ${texts.size} texts, sending one by one")
                        return texts.flatMap { translateBatch(listOf(it), mode, sourceLang, targetLang) }
                    }
                    throw DeepLRetryException(
                        "DeepL devolvió ${translations?.size ?: 0} textos y se esperaban ${texts.size}"
                    )
                }

                val apiMessage = errorMessage(response.text)
                Log.e(TAG, "HTTP ${response.code}: $apiMessage")

                when (response.code) {
                    401, 403 -> throw DeepLFatalException(
                        if (keyless) {
                            "DeepL rechazó la petición gratuita (${response.code}): $apiMessage. " +
                                    "Puede que DeepL haya cambiado su servicio o te haya bloqueado. " +
                                    "Prueba más tarde o añade una API key de DeepL."
                        } else {
                            "API key de DeepL no válida (${response.code}). Revisa que esté bien copiada " +
                                    "y que sea de la API (no la contraseña de la cuenta)."
                        }
                    )

                    456 -> throw DeepLFatalException(
                        "Has agotado la cuota de caracteres de DeepL de este mes (456)."
                    )

                    400 -> throw DeepLFatalException(
                        "DeepL rechazó la petición (400): $apiMessage. " +
                                "Puede que DeepL no soporte el idioma $sourceLang → $targetLang."
                    )

                    413 -> {
                        // Body too large: split the batch in two instead of failing.
                        if (texts.size > 1) {
                            val mid = texts.size / 2
                            return translateBatch(texts.subList(0, mid), mode, sourceLang, targetLang) +
                                    translateBatch(texts.subList(mid, texts.size), mode, sourceLang, targetLang)
                        }
                        throw DeepLFatalException("Un párrafo es demasiado grande para DeepL (413).")
                    }

                    429, 529 -> {
                        // Retrying a blocked IP only makes the block last longer.
                        if (keyless) throw DeepLFatalException(
                            "DeepL ha bloqueado temporalmente tu conexión por demasiadas peticiones " +
                                    "(${response.code}). Espera unos minutos, cambia de red (wifi/datos) " +
                                    "o añade una API key de DeepL."
                        )
                        throw DeepLRetryException("DeepL: demasiadas peticiones (${response.code})")
                    }

                    else -> {
                        if (response.code >= 500) {
                            throw DeepLRetryException("DeepL no disponible (${response.code})")
                        }
                        throw DeepLFatalException("Error de DeepL (${response.code}): $apiMessage")
                    }
                }
            } catch (e: DeepLFatalException) {
                throw e
            } catch (e: UnknownHostException) {
                throw e
            } catch (t: Throwable) {
                attempt++
                if (attempt >= MAX_RETRIES) throw t
                Log.w(TAG, "Attempt $attempt failed: ${t.message}")
                delay(1000L * 2.0.pow(attempt).toLong())
            }
        }
    }

    private fun errorMessage(raw: String): String {
        val parsed = try {
            jacksonObjectMapper().readValue(raw, DeepLResponse::class.java)
        } catch (e: Exception) {
            null
        }
        return parsed?.message?.takeIf { it.isNotBlank() }
            ?: parsed?.title?.takeIf { it.isNotBlank() }
            ?: parsed?.detail?.takeIf { it.isNotBlank() }
            ?: raw.take(150)
    }

    // ------------------------------------------------------------------ language codes

    // The app uses ISO 639-1 codes (the ML Kit ones). The official API wants them uppercase,
    // plus a few exceptions: Norwegian is "NB", and for English/Portuguese a *target* must name
    // a variant. Everything else is passed through - if DeepL doesn't support a language it
    // answers 400, which is reported to the user instead of keeping a whitelist that would go
    // stale whenever DeepL adds languages.
    private fun officialSourceLang(code: String): String = when (val c = code.trim().lowercase()) {
        "no" -> "NB"
        else -> c.uppercase()
    }

    private fun officialTargetLang(code: String): String = when (val c = code.trim().lowercase()) {
        "en" -> "EN-US"
        "pt" -> "PT-BR"
        "no" -> "NB"
        else -> c.uppercase()
    }

    // The free endpoint uses the lowercase / BCP-47 spelling ("en-US", "pt-BR", "zh-Hans").
    private fun keylessLang(code: String, isTarget: Boolean): String = when (val c = code.trim().lowercase()) {
        "en" -> if (isTarget) "en-US" else "en"
        "pt" -> if (isTarget) "pt-BR" else "pt"
        "zh" -> "zh-Hans"
        "no" -> "nb"
        else -> c
    }

    // ------------------------------------------------------------------ batching / splitting

    /** Groups [this] (indices into [paragraphs]) into requests within the official API limits. */
    private fun List<Int>.chunkForOfficial(paragraphs: List<String>): List<List<Int>> {
        val batches = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var currentBytes = 0

        for (index in this) {
            val bytes = paragraphs[index].toByteArray(Charsets.UTF_8).size
            val wouldOverflow = current.size >= MAX_TEXTS_PER_REQUEST ||
                    currentBytes + bytes > MAX_BYTES_PER_REQUEST
            if (wouldOverflow && current.isNotEmpty()) {
                batches.add(current)
                current = mutableListOf()
                currentBytes = 0
            }
            current.add(index)
            currentBytes += bytes
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    /** Groups pieces so each request's texts total at most [KEYLESS_MAX_CHARS] characters. */
    private fun List<Piece>.chunkForKeyless(): List<List<Piece>> {
        val batches = mutableListOf<List<Piece>>()
        var current = mutableListOf<Piece>()
        var currentChars = 0

        for (piece in this) {
            val wouldOverflow = current.size >= MAX_TEXTS_PER_REQUEST ||
                    currentChars + piece.text.length > KEYLESS_MAX_CHARS
            if (wouldOverflow && current.isNotEmpty()) {
                batches.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            current.add(piece)
            currentChars += piece.text.length
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    /**
     * Cuts [text] into pieces of at most [max] chars, preferring sentence ends, then spaces,
     * and only as a last resort cutting mid-word. Text that already fits is returned as-is.
     */
    private fun splitLongText(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)

        val pieces = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            var cut = -1
            // sentence end within the window (but not absurdly early)
            for (k in max downTo max / 3) {
                if (rest[k - 1] in ".!?。！？…\n") {
                    cut = k
                    break
                }
            }
            if (cut == -1) {
                for (k in max downTo max / 3) {
                    if (rest[k - 1].isWhitespace()) {
                        cut = k
                        break
                    }
                }
            }
            if (cut == -1) {
                cut = max
                // never split a surrogate pair (emoji, rare CJK)
                if (Character.isHighSurrogate(rest[cut - 1])) cut--
            }
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) pieces.add(piece)
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) pieces.add(rest.trim())
        return pieces
    }
}
