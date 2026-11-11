package com.lagradost.quicknovel.util

import java.util.Locale

/**
 * Detecta términos del capítulo que siguen en el idioma original tras la traducción: el
 * traductor los dejó tal cual (nombres propios, términos del universo de la novela, fallos
 * puntuales...). La idea es comparar el texto original con el traducido: toda palabra del
 * original que siga apareciendo en la traducción es un término sin traducir.
 *
 * Es Kotlin puro (sin Android) para poder razonarlo y probarlo aisladamente.
 */
object UntranslatedTerms {
    /** Un término sin traducir: cómo se escribe en el capítulo y cuántas veces aparece. */
    data class UntranslatedTerm(
        val term: String,
        val count: Int,
    )

    /** Máximo de palabras por término: frases más largas suelen ser ruido, no un "término". */
    private const val MAX_TERM_WORDS = 3

    /** Número máximo de términos que se devuelven (capítulos caóticos incluidos). */
    private const val MAX_TERMS = 200

    /**
     * Palabras de función del español y homónimos frecuentes español/inglés ("no", "real",
     * "doctor"...): si sobreviven a la traducción es porque son legítimas en el texto traducido,
     * no porque el traductor las dejara a medias. Sin esta lista el menú se llena de ruido.
     */
    private val spanishStopWords = setOf(
        "que", "los", "las", "del", "una", "unos",
        "unas", "por", "para", "con", "como", "pero",
        "sus", "este", "esta", "esto", "estos", "estas",
        "ese", "esa", "eso", "aquel", "aquellos", "aquellas",
        "aqui", "aquí", "alli", "allí", "asi", "así",
        "muy", "mucho", "mucha", "muchos", "muchas",
        "poco", "poca", "pocos", "pocas", "todo",
        "toda", "todos", "todas", "otro", "otra",
        "otros", "otras", "mismo", "misma", "mismos",
        "mismas", "tan", "tanto", "tanta", "tantos",
        "tantas", "algo", "alguien", "algun", "alguno",
        "alguna", "algunos", "algunas", "nada", "nadie",
        "ningun", "ninguna", "mas", "más", "ya",
        "aun", "aún", "solo", "sólo", "incluso",
        "entonces", "luego", "antes", "despues", "después",
        "ahora", "cuando", "donde", "porque", "puesto",
        "mientras", "según", "segun", "sin", "sobre",
        "entre", "hacia", "desde", "durante", "mediante",
        "salvo", "excepto", "ademas", "además", "adentro",
        "afuera", "dentro", "fuera", "cerca", "lejos",
        "junto", "juntos", "contra",
        // homónimos españoles/ingleses habituales en novelas
        "real", "total", "final", "general", "normal",
        "ideal", "hospital", "animal", "original", "similar",
        "regular", "actual", "digital", "natural", "familiar",
        "especial", "principal", "metal", "moral", "solar",
        "vital", "legal", "fatal", "mortal", "plural",
        "actor", "color", "doctor", "honor", "humor",
        "editor", "director", "exterior", "interior", "superior",
        "inferior", "mayor", "menor", "tutor", "vapor",
        "terror", "cargo", "radio", "video", "idea",
        "series", "crisis", "virus", "iris", "atlas",
        "campus", "simple", "terrible", "horrible", "notable",
        "vulnerable", "visible", "posible", "flexible", "sensible",
        "invisible", "china", "americano", "americana", "celular",
        "jefe", "famoso", "famosa", "central", "clima",
        "drama", "agenda", "arena", "base", "blusa",
        "cable", "cámara", "camera", "cereal", "chaleco",
        "chocolate", "cliente", "costo", "cultura", "dentista",
        "dieta", "diploma", "eclipse", "elefante", "evidente",
        "fase", "fecha", "ficha", "firma", "gota",
        "habitación", "hola", "iglesia", "igual", "limite",
        "límite", "lista", "macro", "mama", "mamá",
        "mapa", "mesa", "misterio", "modelo", "museo",
        "nano", "nota", "papá", "papa", "piano",
        "pila", "pizza", "planeta", "plaza", "problema",
        "programa", "puro", "raro", "revista", "rico",
        "ropa", "rosa", "secreto", "serie", "tierra",
        "tipo", "torta", "vino", "visa",
    )

    /**
     * Palabras de función del inglés: nunca son un término a sustituir, y si sobreviven a la
     * traducción es porque están dentro de frases mayores sin traducir ("The Spirit Stones").
     * Al excluirlas, esas frases se agrupan sin artículos ("Spirit Stones") y desaparece el
     * ruido de términos sueltos como "The".
     */
    private val englishStopWords = setOf(
        "the", "and", "that", "with", "this", "these", "those", "for", "was", "were", "are",
        "been", "have", "has", "had", "will", "would", "can", "could", "should", "did", "does",
        "not", "but", "from", "into", "onto", "over", "under", "after", "before", "while",
        "about", "around", "than", "then", "when", "what", "which", "who", "whom", "whose",
        "why", "how", "all", "any", "some", "such", "only", "just", "even", "also", "very",
        "more", "most", "other", "another", "same", "out", "off", "again", "being", "because",
        "him", "his", "hers", "their", "theirs", "its", "they", "them", "she", "you", "your",
        "yours", "our", "ours", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "yes", "okay", "here", "there", "where", "upon", "along", "across",
        "behind", "beyond", "within", "without", "toward", "towards", "through", "during",
        "each", "every", "either", "neither", "both", "few", "many", "much", "several", "unto",
        "yet", "hence", "thus", "therefore", "however", "although", "though", "unless", "until",
        "since", "am", "it", "he", "we", "of", "to", "in", "on", "at", "by", "as", "or", "if",
        "so", "be", "do", "no", "up", "an", "a", "i"
    )

    // Números romanos que suelen quedar en nombres ("Edward III"): nunca son términos que se
    // quieran sustituir.
    private val romanNumeral = Regex("^[ivxlcdm]{2,5}$")

    /**
     * Separadores que cortan una frase: dos palabras pegadas por uno de estos no forman un
     * término ("…the Spirit Stones.\nXiao Yan…" no debe proponer "Spirit Stones Xiao").
     */
    private val runBreakChars = setOf(
        '.', '!', '?', ';', ':', '"', '“', '”', '„', '«', '»', '(', ')', '[', ']', '—', '–', '…',
        '*', '\n', '\r'
    )

    /** Palabra con apóstrofo interno ("don't", "yan's"): letras + apóstrofo + letras. */
    private val wordRegex = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")

    private class Token(val display: String, val normalized: String, val start: Int, val end: Int)

    private fun tokensOf(text: String): List<Token> = wordRegex.findAll(text).map { match ->
        Token(match.value, normalize(match.value), match.range.first, match.range.last)
    }.toList()

    /**
     * Normaliza una palabra para comparar original y traducción: minúsculas y apóstrofos
     * unificados. La "s" posesiva ("Yan's") se quita porque en el texto traducido nunca aparece
     * y no forma parte del término.
     */
    private fun normalize(word: String): String =
        word.lowercase(Locale.ROOT).replace('’', '\'').removeSuffix("'s")

    /**
     * @param originalText texto del capítulo antes de traducir (inglés)
     * @param translatedText texto del capítulo tras traducir (español)
     * @param targetLanguage idioma de destino (p. ej. "es"); decide la lista de stopwords
     * @return términos sin traducir, ordenados por apariciones y después alfabéticamente
     */
    fun find(
        originalText: String,
        translatedText: String,
        targetLanguage: String = "es"
    ): List<UntranslatedTerm> {
        if (originalText.isBlank() || translatedText.isBlank()) return emptyList()

        val stopWords: Set<String> = when (targetLanguage) {
            "es" -> spanishStopWords
            else -> emptySet()
        }

        // palabras del original, con su posición para saber dónde cortan las frases, y las
        // presentes en la traducción
        val tokens = tokensOf(originalText)
        val displayWords = tokens.map { it.display }
        val normalizedWords = tokens.map { it.normalized }
        val translatedWords =
            tokensOf(translatedText).mapTo(HashSet()) { it.normalized }

        // breaks[i] = hay un separador de oración entre la palabra i-1 y la i
        val breaks = BooleanArray(tokens.size)
        for (i in 1 until tokens.size) {
            breaks[i] = originalText.substring(tokens[i - 1].end + 1, tokens[i].start)
                .any { it in runBreakChars }
        }

        // una palabra sobrevive si sigue en el texto traducido y no es ruido
        fun survives(normalized: String): Boolean {
            if (normalized.length < 3) return false
            if (romanNumeral.matches(normalized)) return false
            if (normalized in englishStopWords) return false
            if (normalized in stopWords) return false
            return normalized in translatedWords
        }

        // agrupar palabras supervivientes en frases contiguas (máx. [MAX_TERM_WORDS] palabras):
        // así "Xiao Yan" se propone junto en vez de como "Xiao" y "Yan" por separado
        data class Run(val start: Int, val end: Int) // [start, end) sobre las palabras

        val runs = ArrayList<Run>()
        var i = 0
        while (i < normalizedWords.size) {
            if (!survives(normalizedWords[i])) {
                i++
                continue
            }
            var j = i + 1
            while (j < normalizedWords.size && survives(normalizedWords[j]) && !breaks[j]) j++
            var s = i
            // una frase larga entera sin traducir se trocea en términos de 3 palabras
            while (s < j) {
                val e = minOf(s + MAX_TERM_WORDS, j)
                runs.add(Run(s, e))
                s = e
            }
            i = j
        }
        if (runs.isEmpty()) return emptyList()

        val candidates = ArrayList<UntranslatedTerm>()
        val seen = HashSet<String>()
        for (run in runs) {
            // la forma para mostrar se toma del original tal cual (con sus mayúsculas)
            val shown = displayWords.subList(run.start, run.end)
                .joinToString(" ") { it.normalizeDisplay() }
            if (shown.isBlank() || !seen.add(shown.lowercase(Locale.ROOT))) continue

            // conteo: cuántas veces aparece la frase (palabras contiguas y dentro de la misma
            // oración) en el capítulo
            val span = run.end - run.start
            var count = 0
            for (k in 0..normalizedWords.size - span) {
                var match = true
                for (m in 0 until span) {
                    if (normalizedWords[k + m] != normalizedWords[run.start + m] ||
                        (m > 0 && breaks[k + m])
                    ) {
                        match = false
                        break
                    }
                }
                if (match) count++
            }
            candidates.add(UntranslatedTerm(shown, count))
        }

        // quitar términos contenidos dentro de otro término más largo y con menos o igual uso
        // ("Yan" dentro de "Xiao Yan"): el usuario sustituiría igualmente la frase completa
        data class Words(val term: UntranslatedTerm, val words: List<String>)

        val withWords = candidates.map {
            Words(it, it.term.lowercase(Locale.ROOT).split(' '))
        }
        val filtered = withWords.filter { a ->
            withWords.none { b ->
                b !== a && b.words.size > a.words.size && b.term.count >= a.term.count &&
                        containsPhrase(b.words, a.words)
            }
        }

        return filtered
            .map { it.term }
            .sortedWith(
                compareByDescending<UntranslatedTerm> { it.count }
                    .thenBy { it.term.lowercase(Locale.ROOT) }
            )
            .take(MAX_TERMS)
    }

    /** ¿Está [needle] (lista de palabras) como frase contigua dentro de [haystack]? */
    private fun containsPhrase(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (k in 0..haystack.size - needle.size) {
            for (m in needle.indices) {
                if (haystack[k + m] != needle[m]) continue@outer
            }
            return true
        }
        return false
    }

    /** Mantiene la escritura original (mayúsculas incluidas) pero sin la posesiva. */
    private fun String.normalizeDisplay(): String = this.replace('’', '\'')
        .let { if (it.endsWith("'s") && it.length > 2) it.dropLast(2) else it }
        .let { if (it.endsWith("'") && it.length > 1) it.dropLast(1) else it }
}
