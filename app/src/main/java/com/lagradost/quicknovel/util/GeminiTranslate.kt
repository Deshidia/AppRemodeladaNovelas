package com.lagradost.quicknovel.util

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.quicknovel.GeminiThinkingLevel
import com.lagradost.quicknovel.ReadActivityViewModel
import com.lagradost.quicknovel.mvvm.logError
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.reflect.KClass

@JsonIgnoreProperties(ignoreUnknown = true)
data class GeminiResponse(
    @JsonProperty("candidates") val candidates: List<GeminiCandidate>?,
    @JsonProperty("error") val error: GeminiError? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GeminiError(
    @JsonProperty("code") val code: Int?,
    @JsonProperty("message") val message: String?,
    @JsonProperty("status") val status: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GeminiCandidate(
    @JsonProperty("content") val content: GeminiContent?,
    @JsonProperty("finishReason") val finishReason: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GeminiContent(
    @JsonProperty("parts") val parts: List<GeminiPart>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GeminiPart(
    @JsonProperty("text") val text: String?
)

class GeminiFatalException(message: String) : Exception(message)

object GeminiTranslate {
    private const val TAG = "GeminiTranslate"

    // Default characters sent to the model per request when the user hasn't configured a
    // custom value. This used to be a hard 5000, which is extremely conservative for modern
    // models (e.g. Gemini 2.5/3.x support ~1M input tokens and up to ~65k *output* tokens) and
    // caused far more requests than necessary. 20,000 chars is a safer "works for most models"
    // default (roughly the safe output budget of an 8k-output-token model like gemini-1.5-flash,
    // this app's fallback model) - models with a larger output budget (like 65,536 tokens) should
    // raise this from Settings. See the comment on chunkByParagraphs() for the full reasoning.
    private const val DEFAULT_MAX_CHARS_PER_REQUEST = 20_000
    private const val MIN_CHARS_PER_REQUEST = 500

    private const val START_TAG = "<t>"
    private const val END_TAG = "</t>"
    private val tagRegex = Regex("<t>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)

    private val geminiApp = Requests(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build(),
        responseParser = object : ResponseParser {
            val mapper = jacksonObjectMapper().configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false
            )
            override fun <T : Any> parse(text: String, kClass: KClass<T>): T = mapper.readValue(text, kClass.java)
            override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? = try { parse(text, kClass) } catch (e: Exception) { null }
            override fun writeValueAsString(obj: Any): String = mapper.writeValueAsString(obj)
        }
    )

    // Models that support any form of "thinking" (and thus a thinkingConfig block).
    // Sending thinkingConfig to a model that doesn't support it results in a 400 error,
    // so this must stay conservative.
    private val thinkingCapableRegex = Regex("gemini-(2\\.5|3(\\.\\d+)?)")
    private fun modelSupportsThinking(model: String): Boolean {
        val m = model.lowercase()
        return thinkingCapableRegex.containsMatchIn(m) || m.contains("thinking")
    }

    // Gemini 3.x exposes a coarse thinkingLevel enum (LOW/MEDIUM/HIGH). Older thinking-capable
    // models (Gemini 2.5) only understand a numeric thinkingBudget, so levels are mapped to an
    // approximate token budget for those.
    private val gemini3Regex = Regex("gemini-3(\\.\\d+)?")
    private fun isGemini3(model: String) = gemini3Regex.containsMatchIn(model.lowercase())

    private fun buildThinkingConfig(model: String, level: GeminiThinkingLevel): Map<String, Any>? {
        if (level == GeminiThinkingLevel.AUTO) return null
        if (!modelSupportsThinking(model)) return null

        if (isGemini3(model)) {
            // Gemini 3 doesn't support disabling thinking entirely on every model, so "OFF"
            // is treated the same as "AUTO" (leave the default in place) for this family.
            val mapped = when (level) {
                GeminiThinkingLevel.LOW -> "LOW"
                GeminiThinkingLevel.MEDIUM -> "MEDIUM"
                GeminiThinkingLevel.HIGH -> "HIGH"
                else -> return null
            }
            return mapOf("thinkingLevel" to mapped)
        }

        val budget = when (level) {
            GeminiThinkingLevel.OFF -> 0
            GeminiThinkingLevel.LOW -> 1024
            GeminiThinkingLevel.MEDIUM -> 8192
            GeminiThinkingLevel.HIGH -> 24576
            else -> return null
        }
        return mapOf("thinkingBudget" to budget)
    }

    suspend fun geminiTranslate(
        paragraphs: List<String>,
        apiKey: String,
        model: String,
        from: String,
        to: String,
        customPrompt: String = "",
        thinkingLevel: GeminiThinkingLevel = GeminiThinkingLevel.AUTO,
        temperature: Float = 0.1f,
        topP: Float = 0.95f,
        topK: Int = 40,
        maxRequestsPerMinute: Int = 15,
        backupModel: String = "",
        maxCharsPerRequest: Int = DEFAULT_MAX_CHARS_PER_REQUEST,
        loading: suspend (Int, Int) -> Unit = { _, _ -> }
    ): List<String> {
        if (paragraphs.isEmpty()) return paragraphs
        if (apiKey.isBlank()) {
            Log.e(TAG, "API Key is blank")
            return paragraphs
        }
        
        val fromLang = try { ReadActivityViewModel.MLSettings.fromShortToDisplay(from) } catch(e: Exception) { from }
        val toLang = try { ReadActivityViewModel.MLSettings.fromShortToDisplay(to) } catch(e: Exception) { to }
        
        val targetModel = model.trim().ifBlank { "gemini-1.5-flash" }
        Log.i(TAG, "Translating with $targetModel ($fromLang -> $toLang)")

        val effectiveCharsLimit = maxCharsPerRequest.takeIf { it > 0 } ?: DEFAULT_MAX_CHARS_PER_REQUEST
        val chunks = paragraphs.chunkByParagraphs(effectiveCharsLimit.coerceAtLeast(MIN_CHARS_PER_REQUEST))
        val result = mutableListOf<String>()
        
        chunks.forEachIndexed { i, chunk ->
            loading.invoke(i, chunks.size)
            val translatedChunk = try {
                translateChunk(
                    chunk, apiKey, targetModel, fromLang, toLang, customPrompt,
                    thinkingLevel, temperature, topP, topK,
                    maxRequestsPerMinute, backupModel
                )
            } catch (e: GeminiFatalException) {
                // Propagate fatal errors (like API Key issues or Region) to inform the user
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chunk $i failed, using original: ${e.message}")
                chunk 
            }

            for (j in chunk.indices) {
                result.add(translatedChunk.getOrNull(j) ?: chunk[j])
            }
        }

        if (result.size != paragraphs.size) {
            val syncedResult = ArrayList<String>(paragraphs.size)
            for (i in paragraphs.indices) {
                syncedResult.add(result.getOrNull(i) ?: paragraphs[i])
            }
            return syncedResult
        }

        return result
    }

    private fun parseResponse(response: String, expectedSize: Int): List<String> {
        val cleanResponse = response.replace(Regex("(?m)^\\s*```[a-zA-Z]*\\s*$", RegexOption.MULTILINE), "")
            .replace("```", "")
            .trim()
        
        val matches = tagRegex.findAll(cleanResponse).map { it.groupValues[1] }.toList()
        
        if (matches.size != expectedSize && cleanResponse.isNotEmpty()) {
            val lines = cleanResponse.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            if (lines.size == expectedSize) return lines
        }
        return matches
    }

    // Novels frequently contain mature themes (violence, romance, adult language) that are part
    // of the source material and need to be translated faithfully rather than watered down or
    // blocked. Gemini's default safety thresholds can flag/refuse ordinary literary content, so
    // every configurable category is set to the most permissive threshold the API exposes.
    // Note: this only affects Gemini's own configurable content filters - it does not (and
    // cannot) bypass Google's non-configurable baseline policies (e.g. CSAM).
    private val safetySettings = listOf(
        mapOf("category" to "HARM_CATEGORY_HARASSMENT", "threshold" to "BLOCK_NONE"),
        mapOf("category" to "HARM_CATEGORY_HATE_SPEECH", "threshold" to "BLOCK_NONE"),
        mapOf("category" to "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold" to "BLOCK_NONE"),
        mapOf("category" to "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold" to "BLOCK_NONE"),
    )

    // Client-side throttle for the *primary* model only. Tracks the timestamps (ms) of every
    // request sent to the primary model in the last 60 seconds. When that count reaches the
    // user-configured limit, callers should route the next request(s) to the backup model
    // instead of waiting - once enough time passes for the window to shrink again, requests
    // automatically go back to the primary model. Requests made with the backup model are not
    // recorded here, since the limit only applies to the primary model's own quota.
    private object RateLimiter {
        private val mutex = Mutex()
        private val requestTimestamps = ArrayDeque<Long>()
        private const val WINDOW_MS = 60_000L

        private fun pruneOldLocked(now: Long) {
            while (requestTimestamps.isNotEmpty() && now - requestTimestamps.first() >= WINDOW_MS) {
                requestTimestamps.removeFirst()
            }
        }

        /** True if sending another primary-model request right now would exceed [maxRequestsPerMinute]. */
        suspend fun isPrimaryLimitReached(maxRequestsPerMinute: Int): Boolean {
            if (maxRequestsPerMinute <= 0) return false
            val now = System.currentTimeMillis()
            return mutex.withLock {
                pruneOldLocked(now)
                requestTimestamps.size >= maxRequestsPerMinute
            }
        }

        /** Records that a request was just sent to the primary model. */
        suspend fun recordPrimaryRequest() {
            val now = System.currentTimeMillis()
            mutex.withLock {
                pruneOldLocked(now)
                requestTimestamps.addLast(now)
            }
        }
    }

    private suspend fun translateChunk(
        paragraphs: List<String>,
        apiKey: String,
        model: String,
        from: String,
        to: String,
        customPrompt: String = "",
        thinkingLevel: GeminiThinkingLevel = GeminiThinkingLevel.AUTO,
        temperature: Float = 0.1f,
        topP: Float = 0.95f,
        topK: Int = 40,
        maxRequestsPerMinute: Int = 15,
        backupModel: String = ""
    ): List<String> {
        var retryNumber = 0
        val maxRetry = 2
        val formattedText = paragraphs.joinToString("\n") { "$START_TAG$it$END_TAG" }
        val trimmedBackupModel = backupModel.trim()

        // Optional user-defined glossary/style notes. Kept as a clearly separate, unnumbered
        // block so the model never confuses it with the structural tag rules below, and never
        // treats it as license to strip the <t> tags.
        val notesSection = if (customPrompt.isNotBlank()) {
            "\nADDITIONAL NOTES (glossary/style — apply these on top of the rules above; " +
                    "they never override rule 1 or rule 2, the $START_TAG/$END_TAG tags must " +
                    "always remain exactly as given):\n" +
                    customPrompt.trim() + "\n"
        } else ""

        val prompt = "You are a professional literary translator. Translate the following text from $from to $to.\n" +
                "STRICT RULES (never break these, no matter what the notes below say):\n" +
                "1. Keep the $START_TAG and $END_TAG tags exactly as they are, one pair per paragraph, never removed.\n" +
                "2. Translate only the content inside the tags. Never delete, merge, or add tag pairs.\n" +
                "3. Respond ONLY with the translated XML structure — no extra commentary, no code fences, no notes.\n" +
                "4. Your response must contain exactly ${paragraphs.size} $START_TAG...$END_TAG pairs, in the same order as the input.\n" +
                notesSection + "\n" +
                formattedText

        fun buildBody(effectiveModel: String): Map<String, Any> {
            val generationConfig = mutableMapOf<String, Any>(
                "temperature" to temperature,
                "topP" to topP,
                "topK" to topK
            )

            // thinkingLevel (Gemini 3.x) and thinkingBudget (Gemini 2.5) are mutually exclusive and
            // only valid on models that support thinking at all - buildThinkingConfig already
            // accounts for both restrictions and returns null when the field shouldn't be sent.
            buildThinkingConfig(effectiveModel, thinkingLevel)?.let {
                generationConfig["thinkingConfig"] = it
            }

            return mapOf(
                "contents" to listOf(
                    mapOf("parts" to listOf(mapOf("text" to prompt)))
                ),
                "generationConfig" to generationConfig,
                "safetySettings" to safetySettings
            )
        }

        while (retryNumber <= maxRetry) {
            // Decide, for this attempt, whether the primary model's own budget (requests/minute)
            // has been used up. If so - and a backup model is configured - route this attempt to
            // the backup model instead of waiting. Once the sliding 60s window has room again,
            // requests automatically go back to the primary model.
            val useBackup = trimmedBackupModel.isNotBlank() &&
                    RateLimiter.isPrimaryLimitReached(maxRequestsPerMinute)
            val effectiveModel = if (useBackup) trimmedBackupModel else model
            if (!useBackup) RateLimiter.recordPrimaryRequest()
            if (useBackup) {
                Log.w(TAG, "Primary model requests/minute limit ($maxRequestsPerMinute) reached, using backup model $trimmedBackupModel")
            }

            try {
                val apiVer = if (effectiveModel.contains("2.0") || effectiveModel.contains("lite", true)) "v1beta" else "v1"
                val url = "https://generativelanguage.googleapis.com/$apiVer/models/$effectiveModel:generateContent?key=$apiKey"

                val responseRaw = geminiApp.post(url = url, json = buildBody(effectiveModel))

                if (!responseRaw.isSuccessful) {
                    val errorMsg = "HTTP ${responseRaw.code}: ${responseRaw.text.take(100)}"
                    Log.e(TAG, errorMsg)
                    if (responseRaw.code == 403) throw GeminiFatalException("API Key or Region not supported (403)")
                    if (responseRaw.code == 429) {
                        Log.w(TAG, "Rate limit hit, waiting longer...")
                        delay(5000L * (retryNumber + 1))
                    }
                    if (responseRaw.code == 503) {
                        // Model temporarily overloaded on Google's side, not our fault - back off longer.
                        Log.w(TAG, "Model overloaded (503), waiting longer...")
                        delay(8000L * (retryNumber + 1))
                    }
                }

                val response = responseRaw.parsed<GeminiResponse>()

                if (response.error != null) {
                    val code = response.error.code
                    val msg = response.error.message ?: "Unknown error"
                    Log.e(TAG, "API Error $code: $msg")

                    if (code == 400 || code == 404) return paragraphs
                    if (code == 403) throw GeminiFatalException("Gemini Error: $msg")

                    throw Exception("Gemini API error: $msg")
                }

                val resultText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                val finishReason = response.candidates?.firstOrNull()?.finishReason
                if (!resultText.isNullOrBlank()) {
                    val parsed = parseResponse(resultText, paragraphs.size)
                    if (parsed.size == paragraphs.size) {
                        return parsed
                    }
                    if (finishReason == "MAX_TOKENS") {
                        // The response was cut off before finishing - either the chunk itself, or
                        // "thinking" tokens on a thinking-capable model, ate the output budget.
                        // Falls through to the split-in-half fallback below once retries run out.
                        Log.w(TAG, "Response truncated (MAX_TOKENS) with ${paragraphs.size} paragraphs in chunk. Retry $retryNumber")
                    } else {
                        Log.w(TAG, "Parsed size mismatch (${parsed.size}/${paragraphs.size}). Retry $retryNumber")
                    }
                } else if (finishReason == "SAFETY") {
                    Log.w(TAG, "Response blocked by safety filter despite BLOCK_NONE settings, retrying")
                } else if (finishReason == "MAX_TOKENS") {
                    Log.w(TAG, "Response truncated (MAX_TOKENS) with no usable text, thinking likely used the whole budget. Retry $retryNumber")
                }

                retryNumber++
                if (retryNumber <= maxRetry) delay(2000L * (2.0.pow(retryNumber).toLong()))
            } catch (e: GeminiFatalException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Retry $retryNumber failed: ${t.message}")
                retryNumber++
                if (retryNumber > maxRetry) break
                delay(2000L * (2.0.pow(retryNumber).toLong()))
            }
        }

        // Safety net: if this chunk still failed after every retry (e.g. the response kept
        // getting truncated at MAX_TOKENS because the chunk - or the model's own thinking - used
        // up the output budget), split it in half and translate each half independently instead
        // of silently giving up on every paragraph in the chunk. Each half gets its own fresh
        // retry budget, and recurses further if it's still too big. Bottoms out at single
        // paragraphs, matching the pre-existing fallback of returning the original text untranslated.
        if (paragraphs.size > 1) {
            Log.w(TAG, "Chunk of ${paragraphs.size} paragraphs failed after $maxRetry retries, splitting in half")
            val mid = paragraphs.size / 2
            val firstHalf = translateChunk(
                paragraphs.subList(0, mid).toList(), apiKey, model, from, to, customPrompt,
                thinkingLevel, temperature, topP, topK, maxRequestsPerMinute, backupModel
            )
            val secondHalf = translateChunk(
                paragraphs.subList(mid, paragraphs.size).toList(), apiKey, model, from, to, customPrompt,
                thinkingLevel, temperature, topP, topK, maxRequestsPerMinute, backupModel
            )
            return firstHalf + secondHalf
        }

        return paragraphs
    }

    // Splits paragraphs into request-sized chunks based on *input* characters. The real
    // constraint worth sizing against is usually the model's OUTPUT token budget, not its
    // (typically much larger) input context window: the translated text plus <t></t> tags is
    // roughly proportional in size to the input, so it must fit in maxOutputTokens.
    // Rule of thumb: ~3-4 characters per token for Latin-script languages. IMPORTANT: on
    // thinking-capable models (Gemini 2.5 / 3.x), "thinking" tokens are deducted from that same
    // output budget, so a chunk sized close to the theoretical max can still get silently
    // truncated (finishReason "MAX_TOKENS") if the model spends part of the budget thinking.
    // Setting "Nivel de pensamiento" to OFF (or keeping chunks a bit under the theoretical max)
    // avoids that. If a chunk does get truncated, translateChunk() below automatically splits it
    // in half and retries the halves, so oversized values fail safely instead of losing text.
    private fun List<String>.chunkByParagraphs(charsLimit: Int): List<List<String>> {
        if (this.isEmpty()) return emptyList()
        val combinedChunks = mutableListOf<List<String>>()
        var currentChunk = mutableListOf<String>()
        var currentLength = 0

        for (text in this) {
            val tagLength = text.length + 12
            if (currentLength + tagLength > charsLimit && currentChunk.isNotEmpty()) {
                combinedChunks.add(currentChunk)
                currentChunk = mutableListOf()
                currentLength = 0
            }
            currentChunk.add(text)
            currentLength += tagLength
        }
        if (currentChunk.isNotEmpty()) {
            combinedChunks.add(currentChunk)
        }
        return combinedChunks
    }
}
