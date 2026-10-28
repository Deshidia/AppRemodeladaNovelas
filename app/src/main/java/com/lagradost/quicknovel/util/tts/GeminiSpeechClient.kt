package com.lagradost.quicknovel.util.tts

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URLEncoder

/** Voz de la Biblioteca ampliada (GET /v1beta/voices). */
data class LibraryVoice(
    val id: String,
    val name: String,
    val language: String,
    val gender: String,
    val description: String,
)

/**
 * Cliente de Gemini 3.8 TTS (gemini-3.8-flash-tts y gemini-3.8-flash-lite-tts; mismo esquema).
 *
 * Usa la Interactions API, que es la que documenta Google para estos modelos:
 *  - El texto es una transcripción LITERAL: nada de "Subaru:" ni de "dilo triste:" dentro.
 *  - Quién habla y cómo (style) viaja como anotación `speech_metadata`.
 *  - Una petición por turno con UNA voz: la petición multi-hablante solo admite 2 voces
 *    prediseñadas, y aquí hay series con decenas de personajes (y voces de la biblioteca).
 *  - Se pide audio/l16 (PCM 24 kHz) para poder concatenar frases sin cabeceras WAV.
 */
object GeminiSpeechClient {
    const val MODEL_FLASH = "gemini-3.8-flash-tts"
    const val MODEL_FLASH_LITE = "gemini-3.8-flash-lite-tts"
    private const val MAX_ATTEMPTS = 4

    private val mapper = ObjectMapper()

    /** Cuerpo de la petición (separado para poder probarlo sin red). */
    fun buildRequest(model: String, voice: String, style: String, text: String): Map<String, Any> {
        val item = LinkedHashMap<String, Any>()
        item["type"] = "text"
        item["text"] = text
        if (style.isNotBlank()) {
            item["annotations"] = listOf(
                mapOf("type" to "speech_metadata", "style" to style.trim())
            )
        }
        return linkedMapOf(
            "model" to model,
            "input" to listOf(
                mapOf("type" to "user_input", "content" to listOf(item))
            ),
            "response_format" to mapOf(
                "type" to "audio",
                "mime_type" to "audio/l16",
                "sample_rate" to Pcm.SAMPLE_RATE
            ),
            "generation_config" to mapOf(
                "speech_config" to listOf(mapOf("voice" to voice))
            ),
        )
    }

    /** Un turno de una petición agrupada. [voice] identifica también al hablante. */
    data class BatchTurn(val voice: String, val style: String, val text: String)

    /**
     * Separador entre turnos de una misma petición: dos pausas largas seguidas dejan un silencio
     * mucho mayor que cualquier pausa dentro de una frase, y es por donde se corta después el audio.
     * (No suena a nada más: el ritmo real lo pone la app con sus propios silencios.)
     */
    const val TURN_BREAK = " <long pause> <long pause>"

    /**
     * Petición con varios turnos. Una voz: speech_config en array y un elemento por turno. Dos voces:
     * speech_config.speakers (máximo de la API, solo voces prediseñadas) con `speaker` en cada turno.
     */
    fun buildBatchRequest(
        model: String,
        turns: List<BatchTurn>,
        paddedSingle: Boolean = false,
    ): Map<String, Any> {
        val voices = turns.map { it.voice }.distinct()
        require(voices.size in 1..2) { "Una petición admite como máximo 2 voces" }
        // paddedSingle: una sola voz pero con la forma documentada de DOS hablantes (el segundo no
        // se usa). Es el plan B si la API rechaza varios turnos con speech_config de una voz.
        val padded = paddedSingle && voices.size == 1 && voices[0] in GeminiVoices.prebuilt.map { it.id }
        val multi = voices.size == 2 || padded

        val content = turns.mapIndexed { i, t ->
            val meta = linkedMapOf<String, Any>("type" to "speech_metadata")
            if (multi) meta["speaker"] = t.voice
            if (t.style.isNotBlank()) meta["style"] = t.style.trim()
            val item = linkedMapOf<String, Any>(
                "type" to "text",
                "text" to if (i < turns.lastIndex) t.text + TURN_BREAK else t.text
            )
            // en una sola voz sin estilo no hace falta anotación
            if (multi || t.style.isNotBlank()) item["annotations"] = listOf(meta)
            item
        }
        val speechConfig: Any = if (multi) {
            val listed = if (padded) {
                voices + GeminiVoices.prebuilt.first { it.id != voices[0] }.id
            } else voices
            mapOf("speakers" to listed.map { mapOf("speaker" to it, "voice" to it) })
        } else {
            listOf(mapOf("voice" to voices[0]))
        }
        return linkedMapOf(
            "model" to model,
            "input" to listOf(mapOf("type" to "user_input", "content" to content)),
            "response_format" to mapOf(
                "type" to "audio",
                "mime_type" to "audio/l16",
                "sample_rate" to Pcm.SAMPLE_RATE
            ),
            "generation_config" to mapOf("speech_config" to speechConfig),
        )
    }

    /** Devuelve PCM 16 bit / 24 kHz / mono. Reintenta errores transitorios con espera creciente. */
    suspend fun synthesize(
        apiKey: String,
        model: String,
        voice: String,
        style: String,
        text: String,
    ): ByteArray = postForAudio(apiKey, buildRequest(model, voice, style, text))

    /** Una sola petición con todos los [turns]; el audio sale seguido y hay que cortarlo después. */
    suspend fun synthesizeBatch(
        apiKey: String,
        model: String,
        turns: List<BatchTurn>,
        paddedSingle: Boolean = false,
    ): ByteArray = postForAudio(apiKey, buildBatchRequest(model, turns, paddedSingle))

    /**
     * Una petición de audio. El límite por minuto, los reintentos por 429/5xx/red y el contador de
     * cuota los lleva [TtsQuota.send]; aquí solo se interpreta la respuesta. El único reintento propio
     * es por una respuesta 200 sin audio (cuesta otra petición, así que como mucho una).
     */
    private suspend fun postForAudio(apiKey: String, request: Map<String, Any>): ByteArray {
        val body = mapper.writeValueAsString(request)
        var lastError: Exception = IOException("Sin respuesta")
        for (attempt in 0 until 2) {
            val reply = TtsQuota.send { GeminiHttp.postJson("${GeminiHttp.BASE}/interactions", apiKey, body) }
            if (reply.ok) {
                val audio = extractAudio(mapper.readTree(reply.text))
                if (audio != null && audio.isNotEmpty()) return audio
                lastError = IOException("La respuesta no contenía audio")
                continue
            }
            val message = errorMessage(reply)
            throw when (reply.code) {
                401, 403 -> GeminiApiException(reply.code, "Gemini: clave o región no válidas ($message)", fatal = true)
                else -> GeminiApiException(reply.code, "Gemini TTS: $message", fatal = false)
            }
        }
        throw lastError
    }

    /** Último bloque de audio de la respuesta (steps[].content[] con type == "audio"). */
    fun extractAudio(root: JsonNode): ByteArray? {
        var data: String? = null
        for (step in root.path("steps")) {
            for (content in step.path("content")) {
                if (content.path("type").asText() == "audio") {
                    content.path("data").asText(null)?.let { data = it }
                }
            }
        }
        val b64 = data ?: return null
        // por si la API devolviera WAV en vez de PCM crudo
        return Pcm.stripWavHeader(Base64.decode(b64, Base64.DEFAULT))
    }

    private fun errorMessage(reply: HttpReply): String = try {
        mapper.readTree(reply.text).path("error").path("message").asText(null)
            ?: "HTTP ${reply.code}"
    } catch (_: Exception) {
        "HTTP ${reply.code}"
    }

    /** Biblioteca ampliada de voces (más de 2000): filtrada por texto y/o idioma (es, en-US...). */
    suspend fun listVoices(
        apiKey: String,
        search: String?,
        languageCode: String?,
        pageSize: Int = 50,
    ): List<LibraryVoice> {
        val q = StringBuilder("page_size=$pageSize")
        if (!search.isNullOrBlank()) q.append("&search=").append(enc(search))
        if (!languageCode.isNullOrBlank()) q.append("&language_code=").append(enc(languageCode))
        val reply = GeminiHttp.get("${GeminiHttp.BASE}/voices?$q", apiKey)
        if (!reply.ok) throw GeminiApiException(reply.code, errorMessage(reply), reply.code == 403)

        fun JsonNode.str(vararg names: String): String =
            names.firstNotNullOfOrNull { n -> path(n).asText(null)?.takeIf { it.isNotBlank() } }.orEmpty()

        return mapper.readTree(reply.text).path("voices").mapNotNull { v ->
            val id = v.str("id", "name")
            if (id.isEmpty()) null else LibraryVoice(
                id = id,
                name = v.str("display_name", "displayName").ifEmpty { id },
                language = v.str("language_code", "languageCode"),
                gender = v.str("gender"),
                description = v.str("description"),
            )
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
}
