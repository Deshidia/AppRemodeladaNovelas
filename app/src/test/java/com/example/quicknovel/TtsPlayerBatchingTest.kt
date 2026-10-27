package com.example.quicknovel

import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.util.tts.BatchPlanner
import com.lagradost.quicknovel.util.tts.ChunkKind
import com.lagradost.quicknovel.util.tts.GeminiApiException
import com.lagradost.quicknovel.util.tts.GeminiSpeechClient
import com.lagradost.quicknovel.util.tts.GeminiTtsPlayer
import com.lagradost.quicknovel.util.tts.PauseKind
import com.lagradost.quicknovel.util.tts.TtsBackend
import com.lagradost.quicknovel.util.tts.VoiceLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Prueba el reproductor con un "Gemini" falso que devuelve audio sintético: cuenta las peticiones. */
class TtsPlayerBatchingTest {
    private enum class Mode { OK, NO_SILENCES, REJECT_400 }

    private val sr = 24_000

    private fun tone(out: ByteArrayOutputStream, ms: Int) {
        val n = sr * ms / 1000
        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort((9000 * sin(2 * PI * 220.0 * i / sr)).toInt().toShort())
        out.write(b.array())
    }

    private fun quiet(out: ByteArrayOutputStream, ms: Int) = out.write(ByteArray(sr * ms / 1000 * 2))

    /** Habla de una frase: palabras de 5 "caracteres" (325 ms) con huecos de palabra y de coma. */
    private fun speech(out: ByteArrayOutputStream, text: String) {
        val words = BatchPlanner.weightOf(text) / 5
        for (w in 0 until words) {
            tone(out, 325)
            quiet(out, if (w % 5 == 4) 230 else 90)
        }
    }

    private class Counters {
        var singles = 0
        var batches = 0
        val batchSizes = ArrayList<Int>()
        val requests get() = singles + batches
    }

    private fun fake(mode: Mode, c: Counters) = object : TtsBackend {
        override suspend fun single(apiKey: String, model: String, voice: String, style: String, text: String): ByteArray {
            c.singles++
            return ByteArrayOutputStream().also { speech(it, text) }.toByteArray()
        }

        override suspend fun batch(apiKey: String, model: String, turns: List<GeminiSpeechClient.BatchTurn>, paddedSingle: Boolean): ByteArray {
            c.batches++
            c.batchSizes.add(turns.size)
            if (mode == Mode.REJECT_400) throw GeminiApiException(400, "no multi", false)
            val out = ByteArrayOutputStream()
            quiet(out, 200)
            turns.forEachIndexed { i, t ->
                speech(out, t.text.replace(GeminiSpeechClient.TURN_BREAK, ""))
                // sin silencios: el modelo encadena los turnos con solo un hueco de coma
                quiet(out, if (mode == Mode.OK && i < turns.lastIndex) 1500 else 230)
            }
            return out.toByteArray()
        }
    }

    private var uid = 0
    private fun chapter(n: Int): List<TTSHelper.TTSLine> = (0 until n).map { i ->
        val speaker = if (i % 2 == 0) "Narrador" else "Subaru"
        // texto único por línea y de ~45 caracteres hablados
        val text = "Linea numero $i con bastante texto para pesar algo ${"x".repeat(5)}"
        val vl = VoiceLine(speaker, text, "", 220, i * 100, i * 100 + 90, ChunkKind.DIALOGUE, PauseKind.NONE)
        TTSHelper.TTSLine(text, vl.start, vl.end, 0, vl)
    }

    private fun player(c: Counters, mode: Mode, batching: Boolean = true, root: File? = null): GeminiTtsPlayer =
        GeminiTtsPlayer(
            root ?: java.nio.file.Files.createTempDirectory("ttsplayer").toFile(),
            "Novela de prueba ${uid++}", "key", "gemini-3.8-flash-tts", fake(mode, c), batching,
            chapterMode = false // estos tests son del modo ventanas; el modo capítulo tiene los suyos
        )

    /** Lee el capítulo como el lector: pide por adelantado y espera el audio de cada frase. */
    private fun readAll(p: GeminiTtsPlayer, lines: List<TTSHelper.TTSLine>): List<File?> = runBlocking {
        p.setChapter(lines) // el lector lo hace al empezar cada capítulo
        lines.map { l -> p.warmUp(l); p.audioFor(l) }
    }

    /** Duración (ms) del habla sintética de una frase, con sus huecos de palabra, sin el último hueco. */
    private fun renderedMs(text: String): Int {
        val out = ByteArrayOutputStream()
        speech(out, text)
        return out.size() / 48
    }

    @Test
    fun groupingCutsTheRequestsDramaticallyAndEveryLineGetsItsAudio() {
        val c = Counters()
        val lines = chapter(200)
        val files = readAll(player(c, Mode.OK), lines)
        assertTrue(files.all { it != null && it.length() > 0 })
        assertTrue("peticiones: ${c.requests} para ${lines.size} frases", c.requests <= lines.size / 4)
        // y sin degenerar en peticiones sueltas al avanzar la lectura frase a frase
        assertTrue("sueltas: ${c.singles}", c.singles <= 3)
        assertTrue(c.batchSizes.all { it <= BatchPlanner.MAX_ITEMS })
    }

    @Test
    fun cutAudioKeepsEachLinesOwnSpeechLength() {
        val c = Counters()
        val lines = chapter(30)
        val files = readAll(player(c, Mode.OK), lines)
        lines.forEachIndexed { i, l ->
            val expectedMs = renderedMs(l.speakOutMsg)
            val gotMs = (files[i]!!.length() / 48).toInt() // el corte recorta el hueco final y deja ~40 ms de margen
            assertTrue("línea $i: $gotMs vs $expectedMs", abs(gotMs - expectedMs) < expectedMs * 0.12 + 100)
        }
    }

    @Test
    fun withoutGroupingItIsOneRequestPerLineAsBefore() {
        val c = Counters()
        val files = readAll(player(c, Mode.OK, batching = false), chapter(20))
        assertTrue(files.all { it != null })
        assertEquals(20, c.singles)
        assertEquals(0, c.batches)
    }

    @Test
    fun ifTheModelLeavesNoTurnSilencesEverythingStillPlaysViaSingles() {
        val c = Counters()
        val lines = chapter(12)
        val files = readAll(player(c, Mode.NO_SILENCES), lines)
        assertTrue(files.all { it != null && it.length() > 0 }) // ninguna frase se pierde
        assertTrue(c.singles >= lines.size) // se rehizo frase a frase: peor caso = sin agrupar (+ lo gastado en la tanda)
    }

    @Test
    fun theWorstCaseCostsOnlyAFewWastedRequestsBeforeGroupingTurnsItselfOff() {
        val c = Counters()
        val lines = chapter(120)
        // el modelo nunca deja silencios de turno: las 2 primeras tandas se rechazan y se deja de agrupar
        val files = readAll(player(c, Mode.NO_SILENCES), lines)
        assertTrue(files.all { it != null && it.length() > 0 })
        assertEquals(2, c.batches) // solo 2 peticiones tiradas
        assertTrue("sueltas: ${c.singles}", c.singles >= lines.size) // todo lo demás, como antes
        assertTrue("total: ${c.requests}", c.requests <= lines.size + 4)
    }

    @Test
    fun anApiThatRejectsBatchesIsNotAskedAgain() {
        val c = Counters()
        val lines = chapter(60)
        val files = readAll(player(c, Mode.REJECT_400), lines)
        assertTrue(files.all { it != null && it.length() > 0 })
        // la primera tanda recibe el 400; después ya no se intenta agrupar dos voces
        assertEquals(1, c.batches)
    }

    @Test
    fun alreadyGeneratedAudioIsNeverRequestedAgain() {
        val root = java.nio.file.Files.createTempDirectory("ttsplayer").toFile()
        val c = Counters()
        val lines = chapter(40)
        val p1 = GeminiTtsPlayer(root, "Misma novela", "k", "m", fake(Mode.OK, c), true, false)
        readAll(p1, lines)
        val first = c.requests
        val p2 = GeminiTtsPlayer(root, "Misma novela", "k", "m", fake(Mode.OK, c), true, false)
        readAll(p2, lines) // volver a oír el capítulo (dentro de los 3 días de caché)
        assertEquals(first, c.requests)
    }

    @Test
    fun skippingAheadDoesNotBreakAnything() {
        val c = Counters()
        val lines = chapter(100)
        val p = player(c, Mode.OK)
        runBlocking {
            p.warmUp(lines[0]); p.audioFor(lines[0])
            p.warmUp(lines[70]); // el usuario salta
            assertTrue(p.audioFor(lines[70])!!.length() > 0)
            p.warmUp(lines[5]) // y vuelve atrás: lo que ya había se reutiliza
            assertTrue(p.audioFor(lines[5])!!.length() > 0)
        }
    }
}
