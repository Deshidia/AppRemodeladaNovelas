package com.lagradost.quicknovel.util

import android.text.Spanned
import android.text.SpannableStringBuilder
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.NOVEL_TEXT_REPLACEMENTS

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
