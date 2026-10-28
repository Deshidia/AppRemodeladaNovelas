package com.lagradost.quicknovel.util.tts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException

/**
 * Paso previo al TTS: un modelo de texto de Gemini lee el capítulo y dice, para cada trozo,
 * quién habla, con qué emoción (solo si el texto la muestra) y si hay pausas.
 *
 * NO reescribe el texto: devuelve únicamente metadatos por número de trozo. Así lo que se
 * pronuncia es siempre el texto original (el TTS lo trata como transcripción literal) y no hay
 * forma de que el modelo "mejore" o recorte una frase.
 */
object GeminiSpeakerAnnotator {
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    /** Trozos por petición. Cada ventana lleva además unos trozos previos solo como contexto. */
    private const val WINDOW = 150 // un capítulo normal cabe en 1-2 peticiones
    private const val CONTEXT = 6
    private const val MAX_CHARS_IN_PROMPT = 700

    private val mapper = ObjectMapper()

    private val safetySettings = listOf(
        "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
        "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"
    ).map { mapOf("category" to it, "threshold" to "BLOCK_NONE") }

    val SYSTEM_PROMPT = """
You prepare a chapter of a novel to be read aloud by a cast of voice actors.
You receive the chapter split into numbered chunks. Each chunk is either NARRATION [N] or the words of a character [D] (the quotation marks/brackets were already removed).
For every chunk marked "TARGET", decide:

s = SPEAKER. Who says it.
  - [D]: the character speaking, deduced from context (names, vocatives such as "Oye, Flop-san" are said TO someone else, dialogue tags like "dijo X", turn-taking, who is present).
  - [N]: "$NARRATOR_NAME" for an impersonal narrator. If the narration is told in first person by a character (I/me/yo/me), use THAT character's name instead.
  - If a KNOWN ACTOR matches (also by nickname or honorific), use its exact name from the list.
  - For a new character use ONE short canonical name, the same in every chunk and without honorifics (write "Flop", not "Flop-san").
  - If it is really impossible to know, use "$UNKNOWN_SPEAKER".

e = EMOTION / DELIVERY, in English, 1-4 words (e.g. "surprised", "whispering", "angry", "sobbing"). 
  - ONLY when the text states it explicitly: in the line itself, in a dialogue tag, or in the narration right before OR AFTER it (a reaction described in the next paragraph counts).
  - Exclamation or question marks alone are NOT an explicit emotion.
  - Otherwise "" (neutral voice). Narration is almost always "".

b = PAUSE BEFORE this chunk, a = PAUSE AFTER it. Values: "none", "short", "long".
  - "short": a brief hesitation (the character hesitates, a beat before answering, a comma-sized silence told by the text).
  - "long": a clear, explicit silence ("tras un largo silencio", "tardó en responder", a character is speechless, scene break feeling).
  - Otherwise "none". Do not add pauses just because of ellipses inside the text: those are handled elsewhere.

g = GENDER of the speaker, "m" or "f", ONLY for speakers that are not in the KNOWN ACTORS list and only if clear from the text, else "".

Reply with ONLY a JSON array, one object per TARGET chunk, in order:
[{"i":12,"s":"Subaru","e":"","b":"short","a":"none","g":"m"}]
No comments, no markdown, no extra keys.

Example (chunks):
#0 [N] A la espera del momento oportuno, el plan de Todd debería seguir en marcha.
#1 [D] …Oye, Flop-san, ¿me podrías hacer un favor?
#2 [D] ¿Medicina? ¿Estás muy enfermo, esposo?
#3 [D] ¡Oh, vaya! ¡Nunca había oído hablar de eso!
#4 [N] Al oír el nombre de una enfermedad al azar, Flop abrió los ojos de par en par con sorpresa.
Answer: [{"i":0,"s":"$NARRATOR_NAME","e":"","b":"none","a":"none"},{"i":1,"s":"Subaru","e":"","b":"short","a":"none","g":"m"},{"i":2,"s":"Flop","e":"","b":"none","a":"none","g":"f"},{"i":3,"s":"Flop","e":"surprised","b":"none","a":"none"},{"i":4,"s":"$NARRATOR_NAME","e":"","b":"none","a":"none"}]
""".trim()

    /** Texto del usuario para una ventana. Separado para poder probarlo. */
    fun buildUserPrompt(
        chunks: List<TextChunk>,
        from: Int,
        to: Int,
        knownActors: List<String>,
    ): String = buildString {
        append("KNOWN ACTORS: ")
        append(if (knownActors.isEmpty()) "(none yet)" else knownActors.joinToString(", "))
        append("\n\nCHUNKS (lines starting with CONTEXT are only for reference, do not answer them):\n")
        val ctxFrom = maxOf(0, from - CONTEXT)
        for (i in ctxFrom until to) {
            val c = chunks[i]
            val tag = if (c.kind == ChunkKind.DIALOGUE) "D" else "N"
            val label = if (i < from) "CONTEXT" else "TARGET"
            val text = c.spoken.replace('\n', ' ').take(MAX_CHARS_IN_PROMPT)
            append(label).append(" #").append(c.id).append(" [").append(tag).append("] ")
                .append(text).append('\n')
        }
    }

    /** Recorta lo que rodee al JSON (```json ... ```, frases previas...) y deja el array. */
    fun extractJsonArray(response: String): String? {
        val start = response.indexOf('[')
        val end = response.lastIndexOf(']')
        return if (start in 0 until end) response.substring(start, end + 1) else null
    }

    fun parse(json: String): List<RawAnnotation> {
        val node = mapper.readTree(extractJsonArray(json) ?: return emptyList())
        if (!node.isArray) return emptyList()
        return node.mapNotNull { n ->
            val id = n.path("i").takeIf { it.canConvertToInt() }?.asInt() ?: return@mapNotNull null
            RawAnnotation(
                id = id,
                speaker = n.text("s"),
                style = n.text("e"),
                pre = n.text("b"),
                post = n.text("a"),
                gender = n.text("g"),
            )
        }
    }

    /** Formato compacto (el mismo que devuelve el modelo) para guardar el resultado en la caché. */
    fun serialize(annotations: Collection<RawAnnotation>): String = mapper.writeValueAsString(
        annotations.map {
            linkedMapOf(
                "i" to it.id, "s" to (it.speaker ?: ""), "e" to (it.style ?: ""),
                "b" to (it.pre ?: "none"), "a" to (it.post ?: "none"), "g" to (it.gender ?: "")
            )
        }
    )

    private fun JsonNode.text(name: String): String? =
        path(name).takeIf { it.isTextual }?.asText()

    /**
     * Identifica los hablantes de todo el capítulo. Devuelve null si ninguna ventana se pudo
     * resolver (sin red, clave mala...): el llamador decide el plan B. Las ventanas que fallan
     * por separado simplemente quedan sin anotar.
     */
    suspend fun annotate(
        chunks: List<TextChunk>,
        knownActors: List<String>,
        apiKey: String,
        model: String,
    ): Map<Int, RawAnnotation>? {
        if (chunks.isEmpty()) return emptyMap()
        val result = HashMap<Int, RawAnnotation>()
        var anyOk = false
        // los personajes descubiertos en una ventana se pasan como "conocidos" a la siguiente
        val known = knownActors.toMutableList()

        var from = 0
        while (from < chunks.size) {
            val to = minOf(from + WINDOW, chunks.size)
            val parsed = requestWindow(chunks, from, to, known, apiKey, model)
            if (parsed != null) {
                anyOk = true
                val ids = (from until to).map { chunks[it].id }.toSet()
                for (a in parsed) {
                    if (a.id !in ids) continue
                    result[a.id] = a
                    val name = ScriptBuilder.canonicalSpeaker(a.speaker, known)
                    if (name != null && name != NARRATOR_NAME && name != UNKNOWN_SPEAKER &&
                        known.none { it.equals(name, true) }
                    ) known.add(name)
                }
            }
            from = to
        }
        return if (anyOk) result else null
    }

    private suspend fun requestWindow(
        chunks: List<TextChunk>,
        from: Int,
        to: Int,
        known: List<String>,
        apiKey: String,
        model: String,
    ): List<RawAnnotation>? {
        val generationConfig = linkedMapOf<String, Any>(
            "temperature" to 0.1,
            "responseMimeType" to "application/json",
        )
        // Gemini 3.x: poco razonamiento basta (es clasificar) y va mucho más rápido
        if (model.lowercase().startsWith("gemini-3")) {
            generationConfig["thinkingConfig"] = mapOf("thinkingLevel" to "LOW")
        }
        val body = mapper.writeValueAsString(
            linkedMapOf(
                "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to SYSTEM_PROMPT))),
                "contents" to listOf(
                    mapOf(
                        "role" to "user",
                        "parts" to listOf(mapOf("text" to buildUserPrompt(chunks, from, to, known)))
                    )
                ),
                "generationConfig" to generationConfig,
                "safetySettings" to safetySettings,
            )
        )

        // Pasa por TtsQuota.send: mismo límite de peticiones/minuto que el audio, y un 429 espera un minuto
        // entero (antes reintentaba a los 2 y 4 s y provocaba justo el error que quería evitar). Un 429 que
        // no se arregla, o de cuota diaria, sale como GeminiApiException y el lector se detiene con el motivo.
        repeat(2) { // el segundo intento solo para un JSON de respuesta ilegible (cuesta otra petición)
            val reply = try {
                TtsQuota.send(onAttempt = {}) { // otra cuota distinta a la del TTS: no cuenta en su contador
                    GeminiHttp.postJson("${GeminiHttp.BASE}/models/$model:generateContent", apiKey, body)
                }
            } catch (_: IOException) {
                return null // sin red: esta ventana queda sin identificar
            }
            when {
                reply.ok -> try {
                    val text = mapper.readTree(reply.text)
                        .path("candidates").path(0).path("content").path("parts")
                        .filter { !it.path("thought").asBoolean(false) }
                        .joinToString("") { (it.path("text").asText("") ?: "") }
                    val parsed = parse(text)
                    if (parsed.isNotEmpty()) return parsed
                } catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
                    // JSON mal formado: se reintenta una vez
                }

                reply.code == 401 || reply.code == 403 ->
                    throw GeminiApiException(reply.code, "Gemini: clave o región no válidas", true)

                else -> return null // modelo inexistente, petición mala, 5xx persistente: reintentar no ayuda
            }
        }
        return null
    }
}
