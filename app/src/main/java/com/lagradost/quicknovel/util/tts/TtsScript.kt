package com.lagradost.quicknovel.util.tts

/*
 * Lógica pura (sin Android) del TTS con actores:
 *  1. DialogueChunker  -> trocea el capítulo en narración / diálogo conservando las posiciones.
 *  2. TranscriptBuilder -> deja cada trozo como transcripción literal para el TTS de Gemini:
 *                          sin delimitadores, con <short pause>/<long pause> donde el texto
 *                          muestra una duda o un silencio.
 *  3. ScriptBuilder    -> une trozos + anotaciones (hablante, emoción, pausas) en "líneas de voz"
 *                          y en el guion legible "Hablante: [texto]".
 *
 * Importante: el hablante NUNCA forma parte del texto que se envía al TTS (la voz del actor ya lo
 * identifica), solo viaja en los metadatos.
 */

const val NARRATOR_NAME = "Narrador"
const val UNKNOWN_SPEAKER = "Desconocido"

enum class PauseKind {
    NONE, SHORT, LONG;

    val ms: Int
        get() = when (this) {
            NONE -> 0
            SHORT -> SHORT_PAUSE_MS
            LONG -> LONG_PAUSE_MS
        }

    companion object {
        // Silencio EXTRA que se suma al ritmo natural entre frases (ver ScriptBuilder): así una
        // duda ("…Oye") se oye siempre, incluso entre dos párrafos que ya tienen su propio respiro.
        const val SHORT_PAUSE_MS = 450
        const val LONG_PAUSE_MS = 1100

        fun parse(value: String?): PauseKind = when (value?.trim()?.lowercase()) {
            "short", "corta", "s" -> SHORT
            "long", "larga", "l" -> LONG
            else -> NONE
        }

        fun max(a: PauseKind, b: PauseKind): PauseKind = if (a.ordinal >= b.ordinal) a else b
    }
}

enum class ChunkKind { NARRATION, DIALOGUE }

/** Fragmento de un trozo (frases sueltas si el trozo es muy largo). Posiciones en el capítulo. */
data class ChunkPiece(val start: Int, val end: Int, val spoken: String)

/**
 * Un trozo homogéneo del capítulo: o bien narración, o bien lo que dice un personaje.
 * [start]/[end] incluyen los delimitadores ([ ] « » “ ” 「 」 —) para poder subrayarlo en el lector.
 */
data class TextChunk(
    val id: Int,
    val kind: ChunkKind,
    val start: Int,
    val end: Int,
    /** Texto sin delimitadores, tal cual aparece en el capítulo. */
    val spoken: String,
    val pieces: List<ChunkPiece>,
    val paragraphEnd: Boolean,
)

object DialogueChunker {
    private val closers = mapOf(
        '[' to ']', '「' to '」', '『' to '』', '“' to '”', '«' to '»', '"' to '"',
    )
    private val dashes = charArrayOf('—', '–')
    private const val MAX_PIECE = 420
    private val sentenceEnders = charArrayOf('.', '!', '?', '…', '。', '！', '？', ';', ':')

    fun chunk(text: String): List<TextChunk> {
        val out = ArrayList<TextChunk>()
        var paragraphStart = 0
        while (paragraphStart <= text.length) {
            var paragraphEnd = text.indexOf('\n', paragraphStart)
            if (paragraphEnd == -1) paragraphEnd = text.length
            if (paragraphEnd > paragraphStart) {
                chunkParagraph(text, paragraphStart, paragraphEnd, out)
            }
            paragraphStart = paragraphEnd + 1
        }
        return out
    }

    private fun hasContent(s: CharSequence): Boolean = s.any { it.isLetterOrDigit() }

    /** Trozo sin identificar aún: rango de texto + tipo + rango interior. */
    private data class Raw(
        val kind: ChunkKind,
        val start: Int,
        val end: Int,
        val innerStart: Int,
        val innerEnd: Int,
    )

    private fun chunkParagraph(text: String, from: Int, to: Int, out: MutableList<TextChunk>) {
        val raws = ArrayList<Raw>()
        val firstNonSpace = (from until to).firstOrNull { !text[it].isWhitespace() } ?: return

        if (text[firstNonSpace] in dashes) {
            splitDashDialogue(text, firstNonSpace, to, raws)
        } else {
            splitBracketed(text, from, to, raws)
        }

        // sin trozos con contenido no se añade nada (p. ej. un párrafo solo de signos)
        val valid = raws.filter { hasContent(text.subSequence(it.innerStart, it.innerEnd)) }
        valid.forEachIndexed { i, raw ->
            val isLast = i == valid.lastIndex
            out.add(build(text, out.size, raw, paragraphEnd = isLast))
        }
    }

    private fun splitBracketed(text: String, from: Int, to: Int, raws: MutableList<Raw>) {
        var narrationStart = from
        var i = from
        while (i < to) {
            val open = text[i]
            val close = closers[open]
            if (close == null) {
                i++
                continue
            }
            // comillas rectas: la de cierre es la siguiente; el resto, su pareja
            val closeIdx = text.indexOf(close, i + 1).let { if (it == -1 || it >= to) -1 else it }
            val innerEnd = if (closeIdx == -1) to else closeIdx
            val end = if (closeIdx == -1) to else closeIdx + 1

            addNarration(text, narrationStart, i, raws)
            raws.add(Raw(ChunkKind.DIALOGUE, i, end, i + 1, innerEnd))
            narrationStart = end
            i = end
        }
        addNarration(text, narrationStart, to, raws)
    }

    /**
     * Diálogo con raya (—Hola —dijo Juan—. ¿Qué tal?): la primera raya abre el diálogo, la
     * siguiente abre una aclaración del narrador, la siguiente vuelve a ser diálogo...
     */
    private fun splitDashDialogue(text: String, first: Int, to: Int, raws: MutableList<Raw>) {
        var segStart = first + 1
        var isDialogue = true
        var i = first + 1
        while (i <= to) {
            val atEnd = i == to
            if (atEnd || text[i] in dashes) {
                val kind = if (isDialogue) ChunkKind.DIALOGUE else ChunkKind.NARRATION
                val start = if (raws.isEmpty()) first else segStart
                raws.add(Raw(kind, start, i, segStart, i))
                segStart = i + 1
                isDialogue = !isDialogue
            }
            i++
        }
    }

    private fun addNarration(text: String, from: Int, to: Int, raws: MutableList<Raw>) {
        if (to <= from) return
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e > s) raws.add(Raw(ChunkKind.NARRATION, s, e, s, e))
    }

    private fun build(text: String, id: Int, raw: Raw, paragraphEnd: Boolean): TextChunk {
        // recorta espacios del interior
        var s = raw.innerStart
        var e = raw.innerEnd
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--

        val pieces = splitPieces(text, s, e)
        // el primer fragmento arranca en el delimitador de apertura y el último acaba en el de cierre
        val fixed = pieces.mapIndexed { i, p ->
            ChunkPiece(
                if (i == 0) minOf(raw.start, p.start) else p.start,
                if (i == pieces.lastIndex) maxOf(raw.end, p.end) else p.end,
                p.spoken
            )
        }
        return TextChunk(
            id = id,
            kind = raw.kind,
            start = raw.start,
            end = raw.end,
            spoken = text.substring(s, e),
            pieces = fixed,
            paragraphEnd = paragraphEnd,
        )
    }

    /** Parte un trozo muy largo en frases (≈ [MAX_PIECE] caracteres) para no esperar audios enormes. */
    private fun splitPieces(text: String, s: Int, e: Int): List<ChunkPiece> {
        if (e - s <= MAX_PIECE) return listOf(ChunkPiece(s, e, text.substring(s, e)))
        val pieces = ArrayList<ChunkPiece>()
        var start = s
        var i = s
        while (i < e) {
            val c = text[i]
            val boundary = c in sentenceEnders && (i + 1 >= e || text[i + 1].isWhitespace())
            if (boundary && i + 1 - start >= MAX_PIECE * 0.6) {
                pieces.add(trimPiece(text, start, i + 1))
                start = i + 1
            } else if (i + 1 - start >= MAX_PIECE * 1.6 && c.isWhitespace()) {
                // frase interminable sin puntuación: corta en el espacio
                pieces.add(trimPiece(text, start, i))
                start = i
            }
            i++
        }
        if (start < e) pieces.add(trimPiece(text, start, e))
        return pieces.filter { hasContent(it.spoken) }.ifEmpty { listOf(ChunkPiece(s, e, text.substring(s, e))) }
    }

    private fun trimPiece(text: String, from: Int, to: Int): ChunkPiece {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return ChunkPiece(s, e, text.substring(s, e))
    }
}

/** Resultado de limpiar un fragmento: texto literal + pausas que caen fuera del audio generado. */
data class Transcript(val text: String, val leadPause: PauseKind, val trailPause: PauseKind) {
    val isSpeakable: Boolean get() = text.any { it.isLetterOrDigit() }
}

object TranscriptBuilder {
    private const val SHORT_TAG = "<short pause>"
    private const val LONG_TAG = "<long pause>"

    // puntos suspensivos: "…", "..", "...", "……", "......" (cualquier mezcla)
    private val ellipsisRun = Regex("[.…]{2,}|…")

    /** "…" pesa 3 puntos. 6 o más (p. ej. "……" o "......") = pausa larga; menos = corta. */
    private fun kindOf(run: String): PauseKind {
        val weight = run.sumOf { c -> if (c == '…') 3 else 1 }
        return if (weight >= 6) PauseKind.LONG else PauseKind.SHORT
    }

    fun build(spoken: String): Transcript {
        // < y > son nuestras etiquetas: nunca deben venir del texto. El resto son restos de
        // formato (markdown, espacios de ancho cero) que el TTS leería o trabaría.
        var s = spoken
            .replace('<', ' ').replace('>', ' ')
            .replace('*', ' ').replace('_', ' ').replace('~', ' ').replace('^', ' ')
            .replace("\u200c", "").replace("\u200b", "")
            .replace(Regex("\\s+"), " ")
            .trim()

        var lead = PauseKind.NONE
        var trail = PauseKind.NONE

        // dudas al empezar ("…Oye, Flop-san"): se convierten en silencio ANTES del audio
        ellipsisRun.matchAt(s, 0)?.let { m ->
            lead = kindOf(m.value)
            s = s.substring(m.range.last + 1).trimStart(' ', ',')
        }
        // restos de puntuación al inicio (p. ej. tras una aclaración con rayas: ". ¿Qué tal?")
        s = s.trimStart('.', ',', ';', ':', ' ')

        // puntos suspensivos al final (voz que se apaga): se conserva "..." para la entonación
        // y se añade silencio DESPUÉS
        val trailMatch = Regex("(${ellipsisRun.pattern})[\\s\\p{Punct}¡¿\"”»」』\\]]*$").find(s)
        if (trailMatch != null && trailMatch.range.first > 0) {
            trail = kindOf(trailMatch.groupValues[1])
            s = s.substring(0, trailMatch.range.first).trimEnd(' ', ',') + "..."
        }

        // puntos suspensivos en medio: pausa explícita, corta o larga
        val sb = StringBuilder()
        var last = 0
        // el "..." final recién añadido no debe volver a convertirse en etiqueta
        val searchEnd = if (trail != PauseKind.NONE) s.length - 3 else s.length
        for (m in ellipsisRun.findAll(s.substring(0, searchEnd))) {
            sb.append(s, last, m.range.first)
            sb.append(' ').append(if (kindOf(m.value) == PauseKind.LONG) LONG_TAG else SHORT_TAG).append(' ')
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)

        val text = sb.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,;.!?])"), "$1")
            .trim()
        return Transcript(text, lead, trail)
    }
}

/** Anotación cruda del modelo para un trozo. */
data class RawAnnotation(
    val id: Int,
    val speaker: String?,
    val style: String?,
    val pre: String?,
    val post: String?,
    val gender: String? = null,
)

/** Trozo ya identificado. */
data class AnnotatedChunk(
    val chunk: TextChunk,
    val speaker: String,
    val style: String,
    val pre: PauseKind,
    val post: PauseKind,
    val gender: String?,
)

/**
 * Una línea de voz = una petición al TTS (un fragmento con un único hablante).
 * [text] es la transcripción literal (con etiquetas de pausa) y NO lleva el nombre del hablante.
 */
data class VoiceLine(
    val speaker: String,
    val text: String,
    val style: String,
    /** Silencio que se reproduce antes de esta línea (ritmo natural + pausa explícita). */
    val gapBeforeMs: Int,
    val start: Int,
    val end: Int,
    val kind: ChunkKind,
    /** Pausa explícita anterior (para el guion legible). */
    val leadPause: PauseKind,
)

object ScriptBuilder {
    const val GAP_SAME_SPEAKER_MS = 220
    const val GAP_SPEAKER_CHANGE_MS = 380
    const val GAP_PARAGRAPH_MS = 600
    const val GAP_CONTINUATION_MS = 120

    /** Ajusta nombres de hablante: quita adornos y respeta mayúsculas de actores ya conocidos. */
    fun canonicalSpeaker(raw: String?, known: Collection<String>): String? {
        val cleaned = raw?.trim()?.trim('"', '\'', ':', '[', ']', '*')?.trim()
            ?.replace(Regex("\\s+"), " ")
        if (cleaned.isNullOrEmpty()) return null
        if (cleaned.equals(NARRATOR_NAME, true) || cleaned.equals("narrator", true)) {
            return NARRATOR_NAME
        }
        return known.firstOrNull { it.equals(cleaned, true) } ?: cleaned
    }

    /**
     * Combina trozos y anotaciones. Sin anotación: narración -> Narrador, diálogo -> Desconocido.
     * Las anotaciones con id inexistente se ignoran.
     */
    fun annotate(
        chunks: List<TextChunk>,
        raw: Map<Int, RawAnnotation>,
        knownActors: Collection<String>,
    ): List<AnnotatedChunk> = chunks.map { chunk ->
        val a = raw[chunk.id]
        val default = if (chunk.kind == ChunkKind.NARRATION) NARRATOR_NAME else UNKNOWN_SPEAKER
        AnnotatedChunk(
            chunk = chunk,
            speaker = canonicalSpeaker(a?.speaker, knownActors) ?: default,
            style = a?.style?.trim().orEmpty().take(80),
            pre = PauseKind.parse(a?.pre),
            post = PauseKind.parse(a?.post),
            gender = a?.gender?.trim()?.lowercase()?.takeIf { it == "m" || it == "f" },
        )
    }

    fun toLines(annotated: List<AnnotatedChunk>): List<VoiceLine> {
        val out = ArrayList<VoiceLine>()
        var prev: VoiceLine? = null
        var prevPost = PauseKind.NONE
        var prevParagraphEnd = false

        for (a in annotated) {
            val pieces = a.chunk.pieces
            pieces.forEachIndexed { pi, piece ->
                val t = TranscriptBuilder.build(piece.spoken)
                if (!t.isSpeakable) return@forEachIndexed

                val first = pi == 0
                val last = pi == pieces.lastIndex
                // las pausas del trozo solo valen en su primer / último fragmento
                val explicitBefore = if (first) PauseKind.max(a.pre, t.leadPause) else t.leadPause
                val explicitPrev = prevPost

                val base = when {
                    prev == null -> 0
                    !first -> GAP_CONTINUATION_MS
                    prevParagraphEnd -> GAP_PARAGRAPH_MS
                    prev.speaker != a.speaker -> GAP_SPEAKER_CHANGE_MS
                    else -> GAP_SAME_SPEAKER_MS
                }
                // la pausa explícita se SUMA al respiro natural. Si coinciden la del final del
                // trozo anterior y la del principio de este, vale la mayor (no se duplican)
                val explicit = maxOf(explicitBefore.ms, explicitPrev.ms)
                val gap = if (prev == null) explicitBefore.ms else base + explicit

                val line = VoiceLine(
                    speaker = a.speaker,
                    text = t.text,
                    style = a.style,
                    gapBeforeMs = gap,
                    start = piece.start,
                    end = piece.end,
                    kind = a.chunk.kind,
                    leadPause = PauseKind.max(explicitBefore, explicitPrev),
                )
                out.add(line)
                prev = line
                prevParagraphEnd = last && a.chunk.paragraphEnd
                prevPost = if (last) PauseKind.max(a.post, t.trailPause) else t.trailPause
            }
        }
        return out
    }

    /**
     * Guion legible, con el formato "hablantes identificados":
     *   Narrador: Texto de narración.
     *   Subaru: [<short pause>Oye, Flop-san, ...]
     *   Flop (surprised): [¡Oh, vaya! ...]
     * Las pausas explícitas y las emociones se ven; nada de esto se pronuncia.
     */
    fun render(lines: List<VoiceLine>): String = buildString {
        for (l in lines) {
            append(l.speaker)
            if (l.style.isNotBlank()) append(" (").append(l.style).append(')')
            append(": ")
            val lead = when (l.leadPause) {
                PauseKind.SHORT -> "<short pause>"
                PauseKind.LONG -> "<long pause>"
                PauseKind.NONE -> ""
            }
            if (l.kind == ChunkKind.DIALOGUE) {
                append('[').append(lead).append(l.text).append(']')
            } else {
                append(lead).append(l.text)
            }
            append('\n')
        }
    }
}
