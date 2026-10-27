package com.lagradost.quicknovel.util.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Ahorro de peticiones: en vez de una petición por frase, se agrupan frases consecutivas en una
 * sola (hasta 2 voces prediseñadas por petición, límite de la API) y el audio resultante se
 * corta de nuevo en frases por los silencios que el modelo deja entre turnos.
 *
 * Todo esto es lógica pura (sin Android) para poder probarla con audio sintético.
 */

/** Una frase pendiente de sintetizar. [weight] ≈ lo que dura hablada (caracteres + pausas). */
data class PlanLine(val index: Int, val voice: String, val style: String, val weight: Int)

/** Frases que irán en una misma petición. Con una sola frase es una petición normal. */
class Batch(val lines: List<PlanLine>) {
    val voices: List<String> get() = lines.map { it.voice }.distinct()
    val chars: Int get() = lines.sumOf { it.weight }
}

object BatchPlanner {
    /** ≈ 1 minuto de audio: tarda poco en generarse y un fallo cuesta poco. */
    const val MAX_CHARS = 1000
    const val MAX_ITEMS = 8

    /** La primera tanda es pequeña para que el audio empiece a sonar enseguida. */
    const val FIRST_MAX_CHARS = 280
    const val FIRST_MAX_ITEMS = 3

    /**
     * Parte [lines] (en orden de lectura) en el mínimo de tandas contiguas posible: una tanda
     * admite hasta 2 voces distintas, ambas prediseñadas; con una voz de la Biblioteca/Voice
     * design solo admite esa voz. Como "poder agruparse" se conserva al recortar una tanda,
     * ampliarla todo lo posible (voraz) da el mínimo de peticiones.
     */
    fun plan(
        lines: List<PlanLine>,
        isPrebuilt: (String) -> Boolean,
        twoVoices: Boolean = true,
        singleVoiceMulti: Boolean = true,
        smallFirst: Boolean = true,
    ): List<Batch> {
        val out = ArrayList<Batch>()
        var cur = ArrayList<PlanLine>()
        var chars = 0

        fun flush() {
            if (cur.isNotEmpty()) out.add(Batch(cur))
            cur = ArrayList()
            chars = 0
        }

        for (l in lines) {
            val first = smallFirst && out.isEmpty()
            val maxChars = if (first) FIRST_MAX_CHARS else MAX_CHARS
            val maxItems = if (first) FIRST_MAX_ITEMS else MAX_ITEMS
            if (cur.isNotEmpty() && !fits(cur, l, chars, maxChars, maxItems, isPrebuilt, twoVoices)) {
                flush()
            }
            cur.add(l)
            chars += l.weight
        }
        flush()

        if (singleVoiceMulti) return out
        // la API ha rechazado varios turnos de una sola voz: esas tandas vuelven a ser sueltas
        return out.flatMap { b ->
            if (b.voices.size == 1 && b.lines.size > 1) b.lines.map { Batch(listOf(it)) } else listOf(b)
        }
    }

    private fun fits(
        cur: List<PlanLine>,
        l: PlanLine,
        chars: Int,
        maxChars: Int,
        maxItems: Int,
        isPrebuilt: (String) -> Boolean,
        twoVoices: Boolean,
    ): Boolean {
        if (cur.size + 1 > maxItems || chars + l.weight > maxChars) return false
        val voices = (cur.map { it.voice } + l.voice).distinct()
        if (voices.size > (if (twoVoices) 2 else 1)) return false
        if (voices.size == 2 && !voices.all(isPrebuilt)) return false
        return true
    }

    /** Duración aproximada de una frase hablada: caracteres sin etiquetas + peso de las pausas. */
    fun weightOf(text: String): Int {
        val tags = Regex("<[^>]*>").findAll(text).map { it.value }.toList()
        val plain = Regex("<[^>]*>").replace(text, "")
        val pauses = tags.sumOf {
            when (it) {
                "<long pause>" -> 18
                "<short pause>" -> 8
                else -> 3
            }.toInt()
        }
        return maxOf(1, plain.count { !it.isWhitespace() } + pauses)
    }
}

/**
 * Empaquetado por actores para cuotas muy bajas (p. ej. 10 peticiones al día): en vez de agrupar
 * frases vecinas, se juntan TODAS las frases de cada voz del capítulo en pocas peticiones enormes
 * (hasta lo que cabe en la salida de la API) y luego se recolocan en el orden del texto.
 */
object RolePacker {
    /**
     * Máximo de audio por petición. La API genera como mucho 16.384 tokens de salida (~25 tokens/s
     * = ~655 s); se deja margen porque la estimación de duración no es exacta.
     */
    const val CAP_SECONDS = 480.0

    /** Caracteres hablados (sin espacios) por segundo; conservador (más bajo = tandas más pequeñas). */
    const val WEIGHT_PER_SEC = 11.0

    /** Silencio que añade cada separador de turno (2 pausas largas) y ocupa salida de la API. */
    const val TURN_OVERHEAD_SEC = 2.2

    fun seconds(weight: Int): Double = weight / WEIGHT_PER_SEC + TURN_OVERHEAD_SEC

    private class Chunk(val voice: String, val lines: List<PlanLine>) {
        val seconds: Double = lines.sumOf { seconds(it.weight) }
    }

    /**
     * 1) Cada voz se parte, en orden de lectura, en trozos que caben en una petición.
     * 2) Los trozos (de voces distintas, máx. 2 por petición y ambas prediseñadas) se emparejan
     *    con "best fit decreasing" para gastar el mínimo de peticiones.
     * 3) Las peticiones salen ordenadas por la primera frase que contienen: la que hace falta
     *    antes se genera antes.
     */
    fun pack(
        lines: List<PlanLine>,
        isPrebuilt: (String) -> Boolean,
        twoVoices: Boolean = true,
        capSeconds: Double = CAP_SECONDS,
    ): List<Batch> {
        val chunks = ArrayList<Chunk>()
        for ((voice, group) in lines.groupBy { it.voice }) {
            var cur = ArrayList<PlanLine>()
            var sec = 0.0
            for (l in group) {
                val s = seconds(l.weight)
                if (cur.isNotEmpty() && sec + s > capSeconds) {
                    chunks.add(Chunk(voice, cur)); cur = ArrayList(); sec = 0.0
                }
                cur.add(l); sec += s
            }
            if (cur.isNotEmpty()) chunks.add(Chunk(voice, cur))
        }

        // (la propiedad se llama "items" y no "chunks" para no confundirla con la lista de arriba)
        class Bin(val items: MutableList<Chunk>) { val seconds: Double get() = items.sumOf { it.seconds } }
        val bins = ArrayList<Bin>()
        for (c in chunks.sortedByDescending { it.seconds }) {
            val target = if (!twoVoices || !isPrebuilt(c.voice)) null else bins
                .filter { bin ->
                    bin.items.size == 1 && bin.items[0].voice != c.voice &&
                            isPrebuilt(bin.items[0].voice) && bin.seconds + c.seconds <= capSeconds
                }
                .minByOrNull { capSeconds - (it.seconds + c.seconds) } // el hueco más ajustado
            if (target != null) target.items.add(c) else bins.add(Bin(mutableListOf(c)))
        }
        return bins
            .map { bin -> Batch(bin.items.flatMap { it.lines }.sortedBy { it.index }) }
            .sortedBy { it.lines.first().index }
    }
}

/** Resultado del corte: los trozos y cuánto duró cada silencio de turno (para calibrar). */
class SplitResult(val slices: List<ByteArray>, val boundarySilenceMs: List<Int>)

/**
 * Corta el audio de una tanda en sus frases buscando los silencios que las separan.
 *
 * Es deliberadamente desconfiado: devuelve null (y la tanda no se da por buena) si no encuentra
 * EXACTAMENTE los silencios esperados en posiciones coherentes con la longitud de cada frase.
 * Mejor gastar una petición de más que partir una palabra por la mitad.
 *
 * Con cientos de turnos la posición esperada (por nº de caracteres) acumula error, así que primero
 * se prueba lo más fiable: los K silencios MÁS LARGOS (los de turno), si destacan claramente del
 * resto; solo si no destacan se recurre a elegir por posición esperada.
 */
object SilenceSplitter {
    private const val FRAME_MS = 10
    private const val FRAME_SAMPLES = Pcm.SAMPLE_RATE / 1000 * FRAME_MS // 240

    /** Los silencios de turno deben ser al menos así de largos que el siguiente silencio más largo. */
    private const val SEPARATION = 1.25

    private class Run(val start: Int, val end: Int) { // [start, end) en fotogramas
        val len: Int get() = end - start
        val mid: Double get() = (start + end) / 2.0
    }

    fun split(
        pcm: ByteArray,
        weights: List<Int>,
        minBoundaryMs: Int = 350,
        padMs: Int = 40,
    ): List<ByteArray>? = splitDetailed(pcm, weights, minBoundaryMs, padMs)?.slices

    fun splitDetailed(
        pcm: ByteArray,
        weights: List<Int>,
        minBoundaryMs: Int = 350,
        padMs: Int = 40,
    ): SplitResult? {
        val n = weights.size
        if (n == 0) return SplitResult(emptyList(), emptyList())
        if (n == 1) return SplitResult(listOf(pcm), emptyList())

        val frames = pcm.size / 2 / FRAME_SAMPLES
        if (frames < n * 10) return null

        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val rms = DoubleArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            val base = f * FRAME_SAMPLES * 2
            for (s in 0 until FRAME_SAMPLES) {
                val v = buf.getShort(base + s * 2).toDouble()
                sum += v * v
            }
            rms[f] = sqrt(sum / FRAME_SAMPLES)
        }

        val peak = rms.max()
        if (peak < 300) return null // casi silencio: la respuesta no es voz
        // "nivel de voz" = mediana de los fotogramas claramente sonoros; silencio = muy por debajo
        val loud = rms.filter { it > peak * 0.1 }.sorted()
        if (loud.size < n * 5) return null
        val level = loud[loud.size / 2]
        val threshold = maxOf(60.0, level * 0.06)

        val silent = BooleanArray(frames) { rms[it] < threshold }
        val firstSound = silent.indexOfFirst { !it }
        val lastSound = silent.indexOfLast { !it }
        if (firstSound < 0 || lastSound <= firstSound) return null

        // silencios del interior (los de los extremos no separan frases)
        val minLen = minBoundaryMs / FRAME_MS
        val candidates = ArrayList<Run>()
        var i = firstSound
        while (i <= lastSound) {
            if (silent[i]) {
                var j = i
                while (j <= lastSound && silent[j]) j++
                if (j - i >= minLen) candidates.add(Run(i, j))
                i = j
            } else i++
        }
        val k = n - 1
        if (candidates.size < k) return null

        val totalW = weights.sumOf { it.toDouble() }.coerceAtLeast(1.0)
        val attempts = listOfNotNull(
            chooseLongest(candidates, k),
            chooseByPosition(candidates, k, weights, totalW, firstSound, lastSound),
        )
        for (chosen in attempts) {
            if (verify(chosen, weights, totalW, firstSound, lastSound)) {
                return build(pcm, chosen, firstSound, lastSound, frames, padMs / FRAME_MS)
            }
        }
        return null
    }

    /** Los k silencios más largos, si destacan claramente del siguiente. */
    private fun chooseLongest(candidates: List<Run>, k: Int): List<Run>? {
        if (candidates.size == k) return candidates
        val sorted = candidates.sortedByDescending { it.len }
        if (sorted[k - 1].len < SEPARATION * sorted[k].len) return null
        return sorted.take(k).sortedBy { it.start }
    }

    /** k silencios en orden, lo más cerca posible de donde tocan por la longitud de cada frase. */
    private fun chooseByPosition(
        candidates: List<Run>,
        k: Int,
        weights: List<Int>,
        totalW: Double,
        firstSound: Int,
        lastSound: Int,
    ): List<Run>? {
        val span = (lastSound - firstSound + 1).toDouble()
        val expected = DoubleArray(k + 1)
        var acc = 0.0
        for (b in 1..k) {
            acc += weights[b - 1]
            expected[b] = firstSound + span * acc / totalW
        }
        fun cost(b: Int, c: Run) = abs(c.mid - expected[b]) / span - 0.15 * minOf(c.len, 200) / 200.0

        val m = candidates.size
        val inf = Double.MAX_VALUE / 4
        val dp = Array(k + 1) { DoubleArray(m) { inf } }
        val parent = Array(k + 1) { IntArray(m) { -1 } }
        for (j in 0 until m) dp[1][j] = cost(1, candidates[j])
        for (b in 2..k) {
            // mínimo acumulado de dp[b-1][0 until j]: O(k·m) en vez de O(k·m²)
            var best = inf
            var arg = -1
            for (j in 0 until m) {
                if (arg >= 0) {
                    dp[b][j] = best + cost(b, candidates[j])
                    parent[b][j] = arg
                }
                if (dp[b - 1][j] < best) { best = dp[b - 1][j]; arg = j }
            }
        }
        var bestJ = -1
        var bestCost = inf
        for (j in 0 until m) if (dp[k][j] < bestCost) { bestCost = dp[k][j]; bestJ = j }
        if (bestJ < 0) return null
        val chosen = ArrayList<Run>()
        var cursor = bestJ
        for (b in k downTo 1) {
            chosen.add(candidates[cursor])
            cursor = parent[b][cursor]
        }
        chosen.reverse()
        return chosen
    }

    /** Cada trozo debe durar algo coherente con su texto. */
    private fun verify(chosen: List<Run>, weights: List<Int>, totalW: Double, firstSound: Int, lastSound: Int): Boolean {
        val n = weights.size
        val starts = IntArray(n) { if (it == 0) firstSound else chosen[it - 1].end }
        val ends = IntArray(n) { if (it == n - 1) lastSound + 1 else chosen[it].start }
        val durations = IntArray(n) { ends[it] - starts[it] }
        if (durations.any { it < 8 }) return false
        val speechTotal = durations.sum().toDouble()
        for (b in 0 until n) {
            val exp = speechTotal * weights[b] / totalW
            // las frases muy cortas ("Sí.") varían demasiado en proporción: solo se exige una duración mínima
            if (exp < 120) continue
            val ratio = durations[b] / exp
            if (ratio < 0.35 || ratio > 2.8) return false
        }
        return true
    }

    private fun build(pcm: ByteArray, chosen: List<Run>, firstSound: Int, lastSound: Int, frames: Int, pad: Int): SplitResult {
        val n = chosen.size + 1
        val starts = IntArray(n) { if (it == 0) firstSound else chosen[it - 1].end }
        val ends = IntArray(n) { if (it == n - 1) lastSound + 1 else chosen[it].start }
        val slices = List(n) { b ->
            val from = if (b == 0) maxOf(0, starts[b] - pad)
            else maxOf(starts[b] - pad, chosen[b - 1].mid.toInt())
            val to = if (b == n - 1) minOf(frames, ends[b] + pad)
            else minOf(ends[b] + pad, chosen[b].mid.toInt())
            pcm.copyOfRange(from * FRAME_SAMPLES * 2, to * FRAME_SAMPLES * 2)
        }
        return SplitResult(slices, chosen.map { it.len * FRAME_MS })
    }
}
