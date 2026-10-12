package com.lagradost.quicknovel.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** El traductor devolvió (casi) el mismo texto: falló en silencio o el idioma de origen no es el correcto. */
class TranslationNotAppliedException : Exception("Translation returned the original text")

/**
 * Traduce el HTML de un capítulo descargado cambiando solo el texto de los nodos de texto:
 * imágenes, enlaces y etiquetas se quedan exactamente donde estaban.
 *
 * Es independiente de Android y de qué traductor se use (se le pasa como función), para que
 * sirva igual para IA y DeepL.
 */
object HtmlChapterTranslator {
    /**
     * Atributo invisible que se añade al primer elemento del capítulo para saber con qué se
     * tradujo (p. ej. `deepl:es`). Va dentro del propio archivo para que la marca y el texto
     * se escriban a la vez: si la app se cierra a mitad, nunca queda un capítulo traducido
     * sin marcar (que se volvería a traducir encima de sí mismo).
     */
    const val MARKER_ATTR = "data-qn-tr"

    private val markerRegex = Regex("$MARKER_ATTR=\"([^\"]*)\"")
    private val fullDocumentRegex = Regex("<\\s*(html|body)\\b", RegexOption.IGNORE_CASE)
    private val whitespaceRun = Regex("\\s+")
    private val skippedTags = setOf("script", "style", "noscript")

    // Un texto largo que vuelve idéntico es señal clara de que el traductor falló.
    private const val LONG_TEXT_CHARS = 30

    /** Con qué se tradujo este capítulo, o null si sigue siendo el original. */
    fun readMarker(html: String): String? = markerRegex.find(html)?.groupValues?.get(1)

    private class Item(val source: String, val apply: (String) -> Unit)

    /**
     * @param marker  valor que se guarda en [MARKER_ATTR]
     * @param before  sustituciones "antes de traducir" de la ficha de la novela
     * @param after   sustituciones "después de traducir" de la ficha de la novela
     * @param translateLines traduce una lista de textos y devuelve otra del mismo tamaño y orden
     * @return el título y el HTML ya traducidos
     */
    suspend fun translate(
        title: String,
        html: String,
        marker: String,
        before: (String) -> String,
        after: (String) -> String,
        translateLines: suspend (List<String>) -> List<String>
    ): Pair<String, String> {
        val isFullDocument = fullDocumentRegex.containsMatchIn(html)
        val doc = if (isFullDocument) Jsoup.parse(html) else Jsoup.parseBodyFragment(html)
        // sin esto jsoup reindenta el HTML y cambia los espacios entre etiquetas
        doc.outputSettings().prettyPrint(false)
        val body = doc.body()

        val items = ArrayList<Item>()
        for (element in body.allElements) {
            collect(element, items)
        }

        // Lo que realmente se manda al traductor: texto limpio, con las sustituciones "antes"
        // ya aplicadas, sin repetidos y sin los textos que no tienen letras ("***", "—", "123"...).
        fun prepare(text: String): String = before(text.replace(whitespaceRun, " ").trim())
        fun needsTranslation(text: String): Boolean = text.any { it.isLetter() }

        val prepared = items.map { prepare(it.source) }
        val titleTrimmed = title.trim()
        val titlePrepared = prepare(titleTrimmed)

        // El título entra en el mismo lote que el cuerpo: si el capítulo repite su título como
        // encabezado, ambos salen con exactamente la misma traducción.
        val translations = LinkedHashMap<String, String>()
        val toSend = LinkedHashSet<String>()
        prepared.filterTo(toSend) { needsTranslation(it) }
        if (needsTranslation(titlePrepared)) toSend.add(titlePrepared)

        if (toSend.isNotEmpty()) {
            val sources = toSend.toList()
            val translated = translateLines(sources)
            if (translated.size != sources.size) {
                throw IllegalStateException(
                    "El traductor devolvió ${translated.size} textos y se esperaban ${sources.size}"
                )
            }
            ensureTranslated(sources, translated)
            sources.forEachIndexed { i, source -> translations[source] = translated[i] }
        }

        fun finalText(preparedText: String): String =
            if (preparedText.isBlank()) "" else after(translations[preparedText] ?: preparedText)

        items.forEachIndexed { i, item -> item.apply(finalText(prepared[i])) }

        val newTitle = if (titleTrimmed.isEmpty()) {
            title
        } else {
            finalText(titlePrepared).replace('\n', ' ').trim().ifEmpty { title }
        }

        // mejor sobre un elemento "de verdad" que sobre un <style> o <script> inicial
        val first = body.children().firstOrNull { it.normalName() !in skippedTags }
            ?: body.children().firstOrNull()
        if (first != null) {
            first.attr(MARKER_ATTR, marker)
        } else {
            body.appendElement("span").attr(MARKER_ATTR, marker)
        }

        return newTitle to (if (isFullDocument) doc.html() else body.html())
    }

    private fun collect(element: Element, items: MutableList<Item>) {
        if (element.normalName() in skippedTags) return
        val nodes = element.childNodes()
        if (nodes.none { it is TextNode && !it.isBlank }) return

        val onlyTextAndBreaks =
            nodes.all { it is TextNode || (it is Element && it.normalName() == "br") }

        if (onlyTextAndBreaks) {
            // Párrafo "limpio" (solo texto y saltos <br>): cada tramo entre <br> se traduce
            // entero para que el traductor vea la frase completa.
            val groups = ArrayList<List<TextNode>>()
            var current = ArrayList<TextNode>()
            for (node in nodes) {
                if (node is TextNode) {
                    current.add(node)
                } else {
                    groups.add(current)
                    current = ArrayList()
                }
            }
            groups.add(current)

            for (group in groups) {
                if (group.none { !it.isBlank }) continue
                val original = group.joinToString("") { it.wholeText }
                items.add(Item(original) { translated ->
                    group.first().text(withSpacing(original, translated))
                    group.drop(1).forEach { it.remove() }
                })
            }
        } else {
            // Párrafo con etiquetas dentro (negritas, imágenes, enlaces...): se traduce cada
            // trozo de texto por separado para no perder ninguna etiqueta.
            for (node in nodes) {
                if (node is TextNode && !node.isBlank) {
                    val original = node.wholeText
                    items.add(Item(original) { translated ->
                        node.text(withSpacing(original, translated))
                    })
                }
            }
        }
    }

    /** Conserva los espacios del principio y el final: en HTML separan palabras de etiquetas vecinas. */
    private fun withSpacing(original: String, translated: String): String {
        val lead = original.takeWhile { it.isWhitespace() }
        val trail = original.takeLastWhile { it.isWhitespace() }
        return lead + translated.trim() + trail
    }

    /**
     * Gemini, por ejemplo, devuelve el texto original si un lote falla. Si la mitad o más de los
     * textos largos vuelven idénticos se considera fallo, para no guardar como "traducido" un
     * capítulo que sigue en el idioma original.
     */
    private fun ensureTranslated(sources: List<String>, translated: List<String>) {
        val long = sources.indices.filter { sources[it].length >= LONG_TEXT_CHARS }
        if (long.isEmpty()) return
        val unchanged = long.count { translated[it].trim() == sources[it].trim() }
        if (unchanged * 2 >= long.size) throw TranslationNotAppliedException()
    }
}
