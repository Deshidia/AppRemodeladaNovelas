package com.lagradost.quicknovel.util

import android.text.Spanned
import android.text.SpannableStringBuilder
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.NOVEL_TEXT_REPLACEMENTS
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.RESULT_BOOKMARK_STATE
import com.lagradost.quicknovel.ui.LibraryListManager

/** Cuándo se aplica una sustitución respecto a la traducción del lector. */
enum class ReplaceTiming {
    /** Sobre el texto original, justo antes de enviarlo al traductor. */
    BEFORE_TRANSLATION,

    /** Sobre el texto ya traducido (o sobre el original si no hay traducción activa). */
    AFTER_TRANSLATION
}

data class TextReplacement(
    @JsonProperty("original")
    val original: String,
    @JsonProperty("replacement")
    val replacement: String,
    @JsonProperty("timing")
    val timing: ReplaceTiming = ReplaceTiming.AFTER_TRANSLATION,
)

/** Envoltorio necesario: getKey<List<...>> perdería el tipo de los elementos por el borrado de tipos. */
data class NovelReplacementRules(
    @JsonProperty("rules")
    val rules: List<TextReplacement> = emptyList()
)

/** Resultado de [mergeReplacementRules]: la lista final y cuántas reglas se añadieron o cambiaron. */
data class ReplacementMergeResult(
    val rules: List<TextReplacement>,
    val added: Int,
    val updated: Int,
) {
    val changed: Boolean get() = added + updated > 0
}

/**
 * Mezcla [incoming] dentro de [base] sin duplicar. Dos reglas son "la misma" si tienen igual
 * término original y mismo momento (antes/después de traducir):
 *  - si no existe en [base] se añade al final (el orden importa, las reglas se aplican en orden);
 *  - si existe con otra sustitución se actualiza en su sitio;
 *  - si ya es idéntica no se toca.
 * Si [incoming] repite una regla, solo cuenta la primera: es la única que llegaría a aplicarse.
 */
fun mergeReplacementRules(
    base: List<TextReplacement>,
    incoming: List<TextReplacement>
): ReplacementMergeResult {
    val out = base.toMutableList()
    val seen = HashSet<Pair<String, ReplaceTiming>>()
    var added = 0
    var updated = 0
    for (rule in incoming) {
        if (rule.original.isEmpty()) continue
        if (!seen.add(rule.original to rule.timing)) continue
        val index = out.indexOfFirst { it.original == rule.original && it.timing == rule.timing }
        when {
            index < 0 -> {
                out.add(rule)
                added++
            }

            out[index].replacement != rule.replacement -> {
                out[index] = rule
                updated++
            }
        }
    }
    return ReplacementMergeResult(out, added, updated)
}

object TextReplacements {
    fun get(novelName: String): List<TextReplacement> {
        return getKey<NovelReplacementRules>(NOVEL_TEXT_REPLACEMENTS, novelName)?.rules
            ?: emptyList()
    }

    fun set(novelName: String, rules: List<TextReplacement>) {
        if (rules.isEmpty()) {
            removeKey(NOVEL_TEXT_REPLACEMENTS, novelName)
        } else {
            setKey(NOVEL_TEXT_REPLACEMENTS, novelName, NovelReplacementRules(rules))
        }
    }

    /** Novelas que ya tienen reglas guardadas, con su número de reglas. */
    fun novelsWithRules(): Map<String, Int> {
        val prefix = "$NOVEL_TEXT_REPLACEMENTS/"
        val out = HashMap<String, Int>()
        for (key in getKeys(NOVEL_TEXT_REPLACEMENTS).orEmpty()) {
            if (!key.startsWith(prefix)) continue
            val name = key.removePrefix(prefix)
            val count = get(name).size
            if (count > 0) out[name] = count
        }
        return out
    }

    /**
     * Nombres de las novelas que están en la biblioteca (en alguna de sus listas). Las novelas
     * que solo están en el historial o en descargas no cuentan. Es lectura de disco: no llamar
     * desde el hilo principal.
     */
    fun libraryNovelNames(): Set<String> {
        // RESULT_BOOKMARK_STATE es lo que identifica a las novelas de la biblioteca. getKeys filtra
        // por prefijo, así que se comprueba también la barra para no coger otras carpetas
        val prefix = "$RESULT_BOOKMARK_STATE/"
        val names = HashSet<String>()
        for (key in getKeys(RESULT_BOOKMARK_STATE).orEmpty()) {
            if (!key.startsWith(prefix)) continue
            val id = key.removePrefix(prefix).toIntOrNull() ?: continue
            if (LibraryListManager.getNovelLists(id).isEmpty()) continue
            val name = getKey<ResultCached>(RESULT_BOOKMARK, id.toString())?.name
            if (!name.isNullOrBlank()) names.add(name)
        }
        return names
    }

    /**
     * Copia [rules] a cada novela de [targets] (mezclando con lo que ya tuvieran).
     * Devuelve cuántas novelas cambiaron realmente.
     */
    fun copyTo(targets: Collection<String>, rules: List<TextReplacement>): Int {
        var changed = 0
        for (target in targets) {
            val result = mergeReplacementRules(get(target), rules)
            if (result.changed) {
                set(target, result.rules)
                changed++
            }
        }
        return changed
    }

    fun forTiming(rules: List<TextReplacement>, timing: ReplaceTiming): List<TextReplacement> =
        rules.filter { it.timing == timing && it.original.isNotEmpty() }

    /** Aplica las reglas en orden (distingue mayúsculas/minúsculas). */
    fun apply(text: String, rules: List<TextReplacement>): String {
        var out = text
        for (rule in rules) {
            if (rule.original.isEmpty()) continue
            out = out.replace(rule.original, rule.replacement)
        }
        return out
    }

    /** Igual que [apply] pero conservando el formato (negrita, cursiva...) del texto. */
    fun applyKeepingFormat(text: Spanned, rules: List<TextReplacement>): Spanned {
        var current: Spanned = text
        for (rule in rules) {
            val original = rule.original
            if (original.isEmpty()) continue
            val str = current.toString()

            // coincidencias sin solaparse, igual que String.replace
            val starts = ArrayList<Int>()
            var i = str.indexOf(original)
            while (i >= 0) {
                starts.add(i)
                i = str.indexOf(original, i + original.length)
            }
            if (starts.isEmpty()) continue

            val builder = SpannableStringBuilder(current)
            // de atrás hacia delante para que los índices anteriores sigan siendo válidos
            for (start in starts.asReversed()) {
                builder.replace(start, start + original.length, rule.replacement)
            }
            current = builder
        }
        return current
    }

    /** Huella de las reglas, para que la caché de traducción dependa de ellas. */
    fun hash(rules: List<TextReplacement>): String {
        val joined = rules.joinToString("\u0000") { "${it.original}\u0001${it.replacement}" }
        return Integer.toHexString(joined.hashCode())
    }
}
