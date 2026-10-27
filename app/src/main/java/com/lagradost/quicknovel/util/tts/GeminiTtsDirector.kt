package com.lagradost.quicknovel.util.tts

import com.lagradost.quicknovel.TTSHelper
import java.io.File

/**
 * Convierte el texto de un capítulo en las líneas que lee el motor Gemini:
 *  1. trocea en narración / diálogo,
 *  2. identifica a los hablantes (con la lista de actores de la novela como referencia),
 *  3. registra los personajes nuevos como actores (misma voz en todos los capítulos),
 *  4. calcula pausas y emociones y deja el guion legible.
 */
object GeminiTtsDirector {
    class Prepared(
        val lines: List<TTSHelper.TTSLine>,
        val voiceLines: List<VoiceLine>,
        /** Guion "hablantes identificados": Narrador: ... / Subaru: [...] */
        val script: String,
        /** false = no se pudo identificar a nadie (sin red, clave...): todo lo dicho es "Desconocido". */
        val identified: Boolean,
    )

    suspend fun prepare(
        cacheRoot: File,
        novel: String,
        chapterIndex: Int,
        text: String,
        apiKey: String,
        annotatorModel: String,
    ): Prepared {
        val cache = TtsAudioCache(cacheRoot)
        val chunks = DialogueChunker.chunk(text)
        val known = TtsActors.get(novel).map { it.name }

        // la identificación cuesta una petición: se guarda para no repetirla al volver a oír el capítulo
        val annKey = TtsAudioCache.sha1("ann", annotatorModel, text)
        var raw: Map<Int, RawAnnotation>? = cache.readText(novel, annKey, "json")
            ?.let { runCatching { GeminiSpeakerAnnotator.parse(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?.associateBy { it.id }

        if (raw == null && chunks.isNotEmpty()) {
            raw = GeminiSpeakerAnnotator.annotate(chunks, known, apiKey, annotatorModel)
            if (!raw.isNullOrEmpty()) {
                cache.write(
                    novel, annKey,
                    GeminiSpeakerAnnotator.serialize(raw.values).toByteArray(Charsets.UTF_8), "json"
                )
            }
        }

        val annotated = ScriptBuilder.annotate(chunks, raw ?: emptyMap(), known)

        val gender = LinkedHashMap<String, String?>()
        for (a in annotated) if (gender[a.speaker] == null) gender[a.speaker] = a.gender
        TtsActors.ensure(novel, gender)

        val voiceLines = ScriptBuilder.toLines(annotated)
        return Prepared(
            lines = voiceLines.map {
                TTSHelper.TTSLine(it.text, it.start, it.end, chapterIndex, it)
            },
            voiceLines = voiceLines,
            script = ScriptBuilder.render(voiceLines),
            identified = raw != null || chunks.isEmpty(),
        )
    }
}
