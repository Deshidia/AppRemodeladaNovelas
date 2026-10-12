package com.lagradost.quicknovel.util

import androidx.annotation.StringRes
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass
import com.lagradost.quicknovel.EPUB_CURRENT_ML
import com.lagradost.quicknovel.EPUB_ML_AI_API_KEY
import com.lagradost.quicknovel.EPUB_ML_AI_BACKUP_MODEL
import com.lagradost.quicknovel.EPUB_ML_AI_MAX_CHARS_PER_REQUEST
import com.lagradost.quicknovel.EPUB_ML_AI_MAX_RPM
import com.lagradost.quicknovel.EPUB_ML_AI_MODEL
import com.lagradost.quicknovel.EPUB_ML_AI_PROMPT
import com.lagradost.quicknovel.EPUB_ML_AI_TEMPERATURE
import com.lagradost.quicknovel.EPUB_ML_AI_THINKING_LEVEL
import com.lagradost.quicknovel.EPUB_ML_AI_TOP_K
import com.lagradost.quicknovel.EPUB_ML_AI_TOP_P
import com.lagradost.quicknovel.EPUB_ML_DEEPL_API_KEY
import com.lagradost.quicknovel.EPUB_ML_FROM_LANGUAGE
import com.lagradost.quicknovel.EPUB_ML_TO_LANGUAGE
import com.lagradost.quicknovel.GeminiThinkingLevel
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ReadActivityViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Con qué se traduce una descarga. */
enum class DownloadTranslation(val tag: String) {
    AI("ai"),
    DEEPL("deepl");

    companion object {
        fun fromTag(tag: String?): DownloadTranslation? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * Traduce capítulos ya descargados (archivos `título\nhtml`) usando los mismos ajustes de
 * traducción del lector: idiomas, API keys, modelo de IA, sustituciones de la ficha...
 */
class DownloadTranslator private constructor(
    private val mode: DownloadTranslation,
    private val from: String,
    private val to: String,
    private val novelName: String,
    private val deeplApiKey: String,
    private val ai: AiSettings,
) {
    private class AiSettings(
        val apiKey: String,
        val model: String,
        val prompt: String,
        val thinkingLevel: GeminiThinkingLevel,
        val temperature: Float,
        val topP: Float,
        val topK: Int,
        val maxRequestsPerMinute: Int,
        val backupModel: String,
        val maxCharsPerRequest: Int,
    )

    /** Se guarda en cada capítulo traducido: motor + idioma de destino (p. ej. `deepl:es`). */
    val marker: String = "${mode.tag}:$to"

    private val beforeRules: List<TextReplacement>
    private val afterRules: List<TextReplacement>

    init {
        val rules = TextReplacements.get(novelName)
        beforeRules = TextReplacements.forTiming(rules, ReplaceTiming.BEFORE_TRANSLATION)
        afterRules = TextReplacements.forTiming(rules, ReplaceTiming.AFTER_TRANSLATION)
    }

    private suspend fun translateLines(lines: List<String>): List<String> = when (mode) {
        DownloadTranslation.DEEPL -> DeepLTranslate.deeplTranslate(lines, deeplApiKey, from, to)

        DownloadTranslation.AI -> GeminiTranslate.geminiTranslate(
            lines,
            ai.apiKey,
            ai.model,
            from,
            to,
            ai.prompt,
            thinkingLevel = ai.thinkingLevel,
            temperature = ai.temperature,
            topP = ai.topP,
            topK = ai.topK,
            maxRequestsPerMinute = ai.maxRequestsPerMinute,
            backupModel = ai.backupModel,
            maxCharsPerRequest = ai.maxCharsPerRequest
        )
    }

    /**
     * Traduce el archivo de un capítulo en su sitio. Se escribe a un archivo temporal que luego
     * sustituye al original, así que si algo falla el capítulo original queda intacto.
     * Lanza excepción si no se pudo traducir.
     */
    suspend fun translateFile(file: File) {
        val text = withContext(Dispatchers.IO) { file.readText() }
        val newline = text.indexOf('\n')
        if (newline == -1) throw IllegalStateException("Invalid chapter file")

        val (title, html) = HtmlChapterTranslator.translate(
            title = text.substring(0, newline),
            html = text.substring(newline + 1),
            marker = marker,
            before = { TextReplacements.apply(it, beforeRules) },
            after = { TextReplacements.apply(it, afterRules) },
            translateLines = { lines -> translateLines(lines) }
        )

        withContext(Dispatchers.IO) {
            // "5.txt.tmp" no se confunde con un capítulo: su nombre sin extensión es "5.txt"
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText("$title\n$html")
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    throw IllegalStateException("Unable to save the translated chapter")
                }
            }
        }
    }

    companion object {
        /** Con qué se tradujo este capítulo (null = sigue siendo el original). */
        suspend fun markerOf(file: File): String? = withContext(Dispatchers.IO) {
            HtmlChapterTranslator.readMarker(file.readText())
        }

        /**
         * Idiomas que usará la traducción: los de la novela en el lector si los hay, y si no los
         * generales del lector. Null si no hay un par origen/destino utilizable.
         */
        private fun languages(novelName: String): Pair<String, String>? {
            val global = Pair(
                getKeyClass(EPUB_ML_FROM_LANGUAGE, String::class.java).orEmpty(),
                getKeyClass(EPUB_ML_TO_LANGUAGE, String::class.java).orEmpty()
            )
            val perNovel = getKey<ReadActivityViewModel.MLSettings>(EPUB_CURRENT_ML, novelName)
                ?.let { it.from to it.to }

            return listOfNotNull(perNovel, global).firstOrNull { (from, to) ->
                from.isNotBlank() && to.isNotBlank() && from != to
            }
        }

        private fun readAiSettings() = AiSettings(
            apiKey = getKeyClass(EPUB_ML_AI_API_KEY, String::class.java).orEmpty(),
            model = getKeyClass(EPUB_ML_AI_MODEL, String::class.java) ?: "gemini-1.5-flash",
            prompt = getKeyClass(EPUB_ML_AI_PROMPT, String::class.java).orEmpty(),
            thinkingLevel = GeminiThinkingLevel.fromName(
                getKeyClass(EPUB_ML_AI_THINKING_LEVEL, String::class.java)
                    ?: GeminiThinkingLevel.AUTO.name
            ),
            temperature = getKeyClass(EPUB_ML_AI_TEMPERATURE, Float::class.java) ?: 0.1f,
            topP = getKeyClass(EPUB_ML_AI_TOP_P, Float::class.java) ?: 0.95f,
            topK = getKeyClass(EPUB_ML_AI_TOP_K, Int::class.java) ?: 40,
            maxRequestsPerMinute = getKeyClass(EPUB_ML_AI_MAX_RPM, Int::class.java) ?: 15,
            backupModel = getKeyClass(EPUB_ML_AI_BACKUP_MODEL, String::class.java).orEmpty(),
            maxCharsPerRequest = getKeyClass(EPUB_ML_AI_MAX_CHARS_PER_REQUEST, Int::class.java)
                ?: 20000,
        )

        /** null si se puede traducir; si no, el texto (recurso) que explica qué falta configurar. */
        @StringRes
        fun validate(mode: DownloadTranslation, novelName: String): Int? {
            if (languages(novelName) == null) return R.string.translation_languages_missing
            if (mode == DownloadTranslation.AI &&
                readAiSettings().apiKey.isBlank()
            ) {
                return R.string.translation_ai_key_missing
            }
            // DeepL funciona también sin API key (endpoint gratuito), así que no se exige.
            return null
        }

        /** Lee los ajustes actuales. Solo llamar después de comprobar [validate]. */
        fun create(mode: DownloadTranslation, novelName: String): DownloadTranslator? {
            val (from, to) = languages(novelName) ?: return null
            return DownloadTranslator(
                mode = mode,
                from = from,
                to = to,
                novelName = novelName,
                deeplApiKey = getKeyClass(EPUB_ML_DEEPL_API_KEY, String::class.java).orEmpty(),
                ai = readAiSettings()
            )
        }
    }
}
