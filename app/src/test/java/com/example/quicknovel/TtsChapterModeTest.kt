package com.example.quicknovel

import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.util.tts.Batch
import com.lagradost.quicknovel.util.tts.BatchPlanner
import com.lagradost.quicknovel.util.tts.ChunkKind
import com.lagradost.quicknovel.util.tts.GeminiApiException
import com.lagradost.quicknovel.util.tts.GeminiSpeechClient
import com.lagradost.quicknovel.util.tts.GeminiTtsPlayer
import com.lagradost.quicknovel.util.tts.GeminiVoices
import com.lagradost.quicknovel.util.tts.PauseKind
import com.lagradost.quicknovel.util.tts.PlanLine
import com.lagradost.quicknovel.util.tts.RolePacker
import com.lagradost.quicknovel.util.tts.RpmLimiter
import com.lagradost.quicknovel.util.tts.SilenceSplitter
import com.lagradost.quicknovel.util.tts.TtsBackend
import com.lagradost.quicknovel.util.tts.TtsQuota
import com.lagradost.quicknovel.util.tts.VoiceLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

@Suppress("UNCHECKED_CAST")
class TtsChapterModeTest {
    private val prebuilt: (String) -> Boolean = { id -> GeminiVoices.prebuilt.any { it.id == id } }
    private fun pl(i: Int, voice: String, w: Int) = PlanLine(i, voice, "", w)

    // ------------------------------------------------------------------ empaquetado por actores

    @Test
    fun chapterOfThreeRolesNeedsAFewRequestsNotOnePerLine() {
        // narrador (frases largas) + Subaru + Flop, 200 frases
        val rnd = Random(1)
        val lines = (0 until 200).map {
            when (rnd.nextInt(10)) {
                in 0..5 -> pl(it, "Charon", 110)
                in 6..7 -> pl(it, "Puck", 35)
                else -> pl(it, "Kore", 35)
            }
        }
        val packs = RolePacker.pack(lines, prebuilt)
        assertTrue("peticiones: ${packs.size}", packs.size <= 5)
        assertTrue(packs.any { it.voices.size == 2 }) // los personajes comparten petición
        // todas las frases, exactamente una vez
        assertEquals(lines.map { it.index }.sorted(), packs.flatMap { b -> b.lines.map { it.index } }.sorted())
    }

    @Test
    fun pairingStillHappensNextToAHugeNarratorRole() {
        // el narrador llena varias peticiones, pero Subaru y Flop deben ir JUNTOS en una (2 voces)
        val lines = (0 until 92).map { pl(it, "Charon", 100) } +
                (0 until 31).map { pl(1000 + it, "Puck", 40) } +
                (0 until 27).map { pl(2000 + it, "Kore", 35) }
        val packs = RolePacker.pack(lines, prebuilt)
        val pair = packs.filter { it.voices.toSet() == setOf("Puck", "Kore") }
        assertEquals(1, pair.size)
        assertEquals(58, pair[0].lines.size)
        assertEquals(4, packs.size) // 3 del narrador (~1000 s) + 1 de los personajes, no 5
    }

    @Test
    fun everyRequestRespectsTheVoiceAndOutputLimits() {
        val rnd = Random(2)
        val voices = listOf("Charon", "Puck", "Kore", "Fenrir", "voice_x")
        val lines = (0 until 400).map { pl(it, voices[rnd.nextInt(voices.size)], 20 + rnd.nextInt(200)) }
        for (b in RolePacker.pack(lines, prebuilt)) {
            assertTrue(b.voices.size <= 2)
            if (b.voices.size == 2) assertTrue(b.voices.all(prebuilt)) // la biblioteca va sola
            val seconds = b.lines.sumOf { RolePacker.seconds(it.weight) }
            assertTrue("segundos: $seconds", seconds <= RolePacker.CAP_SECONDS || b.lines.size == 1)
        }
    }

    @Test
    fun linesOfEachRequestStayInReadingOrderAndRequestsAreOrderedByFirstLine() {
        val lines = (0 until 60).map { pl(it, if (it % 3 == 0) "Charon" else "Puck", 80) }
        val packs = RolePacker.pack(lines, prebuilt)
        for (b in packs) assertEquals(b.lines.map { it.index }.sorted(), b.lines.map { it.index })
        assertEquals(packs.map { it.lines.first().index }.sorted(), packs.map { it.lines.first().index })
    }

    @Test
    fun lighterRolesArePairedSoNoRequestIsWasted() {
        // 3 voces cortas caben en 2 peticiones (una con 2 voces, otra con la tercera), no en 3
        val lines = (0 until 30).map { pl(it, listOf("Charon", "Puck", "Kore")[it % 3], 40) }
        assertEquals(2, RolePacker.pack(lines, prebuilt).size)
    }

    @Test
    fun withoutTwoVoiceSupportEachVoiceGetsItsOwnRequests() {
        val lines = (0 until 30).map { pl(it, listOf("Charon", "Puck", "Kore")[it % 3], 40) }
        val packs = RolePacker.pack(lines, prebuilt, twoVoices = false)
        assertEquals(3, packs.size)
        assertTrue(packs.all { it.voices.size == 1 })
    }

    @Test
    fun aVeryLongChapterIsSplitByCapacityPerVoice() {
        val lines = (0 until 100).map { pl(it, "Charon", 300) } // ~30 s por frase -> ~50 min
        val packs = RolePacker.pack(lines, prebuilt)
        assertTrue(packs.size >= 6) // no cabe en una petición (16.384 tokens de salida)
        assertEquals(100, packs.sumOf { it.lines.size })
    }

    // ------------------------------------------------------------------ cortador con muchos turnos

    private val sr = 24_000
    private fun tone(o: ByteArrayOutputStream, ms: Int) {
        val n = sr * ms / 1000
        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort((9000 * sin(2 * PI * 220.0 * i / sr)).toInt().toShort())
        o.write(b.array())
    }

    private fun quiet(o: ByteArrayOutputStream, ms: Int) = o.write(ByteArray(sr * ms / 1000 * 2))

    private fun line(o: ByteArrayOutputStream, weight: Int, rate: Double = 1.0, innerPause: Int = 0) {
        val words = maxOf(1, weight / 5)
        for (w in 0 until words) {
            tone(o, (5 * 65 * rate).toInt())
            quiet(o, if (w % 5 == 4) 230 else 90)
            if (innerPause > 0 && w == words / 2) quiet(o, innerPause)
        }
    }

    private fun speechMs(pcm: ByteArray, thr: Int = 150): Int {
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var loud = 0
        for (i in 0 until pcm.size / 2) if (abs(b.getShort(i * 2).toInt()) > thr) loud++
        return loud * 1000 / sr
    }

    private fun near(a: Int, e: Int) = abs(a - e) <= e * 0.03 + 20

    private class Built(val pcm: ByteArray, val weights: List<Int>, val truthMs: List<Int>)

    /** Una petición enorme: n turnos con ritmos distintos, pausas internas y silencios de turno. */
    private fun bigRequest(n: Int, seed: Int, internalPauseMs: Int, boundaryMs: Int): Built {
        val rnd = Random(seed)
        val out = ByteArrayOutputStream()
        val weights = ArrayList<Int>()
        val truth = ArrayList<Int>()
        quiet(out, 250)
        for (i in 0 until n) {
            val w = 20 + rnd.nextInt(130)
            val rate = 0.8 + rnd.nextDouble() * 0.5
            val pause = if (rnd.nextInt(5) == 0) internalPauseMs else 0
            val single = ByteArrayOutputStream().also { line(it, w, rate, pause) }
            truth.add(speechMs(single.toByteArray()))
            out.write(single.toByteArray())
            weights.add(w)
            if (i < n - 1) quiet(out, boundaryMs)
        }
        quiet(out, 300)
        return Built(out.toByteArray(), weights, truth)
    }

    @Test
    fun splitsOneHundredTwentyTurnsOfOneRequest() {
        val b = bigRequest(120, seed = 11, internalPauseMs = 700, boundaryMs = 1500)
        val slices = SilenceSplitter.split(b.pcm, b.weights)!!
        assertEquals(120, slices.size)
        slices.forEachIndexed { i, s ->
            assertTrue("turno $i: ${speechMs(s)} vs ${b.truthMs[i]}", near(speechMs(s), b.truthMs[i]))
        }
    }

    @Test
    fun theLongestSilencesWinEvenWhenTheRatesDriftALot() {
        // 80 turnos con ritmos 0.8x–1.3x: la posición esperada por caracteres se desvía, los silencios no
        val b = bigRequest(80, seed = 5, internalPauseMs = 900, boundaryMs = 1800)
        val slices = SilenceSplitter.split(b.pcm, b.weights)!!
        slices.forEachIndexed { i, s -> assertTrue("turno $i", near(speechMs(s), b.truthMs[i])) }
    }

    @Test
    fun ambiguousSilencesNeverProduceAWrongSplit() {
        // pausas internas casi tan largas como las de turno: o se corta bien o se rechaza, jamás mal
        val b = bigRequest(40, seed = 9, internalPauseMs = 1400, boundaryMs = 1500)
        val slices = SilenceSplitter.split(b.pcm, b.weights)
        if (slices != null) {
            assertEquals(40, slices.size)
            slices.forEachIndexed { i, s -> assertTrue("turno $i", near(speechMs(s), b.truthMs[i])) }
        }
    }

    @Test
    fun splitDetailedReportsTheTurnSilences() {
        val b = bigRequest(10, seed = 3, internalPauseMs = 0, boundaryMs = 1500)
        val r = SilenceSplitter.splitDetailed(b.pcm, b.weights)!!
        assertEquals(9, r.boundarySilenceMs.size)
        assertTrue(r.boundarySilenceMs.all { it in 1500..1800 }) // 1500 ms + el último hueco de palabra
    }

    // ------------------------------------------------------------------ limitador por minuto

    @Test
    fun theFourthRequestOfAThreePerMinuteQuotaWaitsForTheWindow() {
        var now = 0L
        val limiter = RpmLimiter(now = { now }, sleep = { now += it })
        runBlocking {
            repeat(3) { limiter.acquire(3); now += 1_000 } // 3 peticiones en 3 s
            val t0 = now
            limiter.acquire(3) // la 4.ª
            assertTrue("esperó ${now - t0} ms", now >= 60_000)
            assertTrue(now < 62_000)
        }
    }

    @Test
    fun zeroMeansNoRateLimit() {
        var now = 0L
        val limiter = RpmLimiter(now = { now }, sleep = { now += it })
        runBlocking { repeat(50) { limiter.acquire(0) } }
        assertEquals(0L, now)
    }

    @Test
    fun dailyQuotaErrorsAreRecognisedAndRateErrorsAreNot() {
        assertTrue(TtsQuota.isDailyQuotaError("""{"error":{"message":"Quota exceeded for metric: GenerateRequestsPerDayPerProjectPerModel-FreeTier"}}"""))
        assertTrue(TtsQuota.isDailyQuotaError("You exceeded your daily quota"))
        assertFalse(TtsQuota.isDailyQuotaError("""{"error":{"message":"Quota exceeded for GenerateRequestsPerMinutePerProjectPerModel"}}"""))
    }

    // ------------------------------------------------------------------ petición de una sola voz, plan B

    @Test
    fun paddedFormListsTwoSpeakersButOnlyTheRealOneSpeaks() {
        val body = GeminiSpeechClient.buildBatchRequest(
            "m",
            listOf(
                GeminiSpeechClient.BatchTurn("Charon", "", "Uno."),
                GeminiSpeechClient.BatchTurn("Charon", "", "Dos."),
            ),
            paddedSingle = true
        )
        val cfg = (body["generation_config"] as Map<String, Any>)["speech_config"] as Map<String, Any>
        val speakers = cfg["speakers"] as List<Map<String, Any>>
        assertEquals(2, speakers.size)
        assertEquals("Charon", speakers[0]["voice"])
        assertTrue(speakers[1]["voice"] != "Charon")
        val content = ((body["input"] as List<Map<String, Any>>)[0]["content"]) as List<Map<String, Any>>
        assertTrue(content.all { ((it["annotations"] as List<Map<String, Any>>)[0])["speaker"] == "Charon" })
    }

    @Test
    fun customVoicesCannotUseThePaddedForm() {
        val body = GeminiSpeechClient.buildBatchRequest(
            "m",
            listOf(GeminiSpeechClient.BatchTurn("voice_abc", "", "Uno."), GeminiSpeechClient.BatchTurn("voice_abc", "", "Dos.")),
            paddedSingle = true
        )
        assertTrue((body["generation_config"] as Map<String, Any>)["speech_config"] is List<*>)
    }

    // ------------------------------------------------------------------ el reproductor en modo capítulo

    private enum class Mode { OK, NO_SILENCES, REJECT_SINGLE_ARRAY }

    private class Counters {
        var singles = 0
        var batches = 0
        val sizes = ArrayList<Int>()
        val requests get() = singles + batches
    }

    private fun speechOf(o: ByteArrayOutputStream, text: String) = line(o, com.lagradost.quicknovel.util.tts.BatchPlanner.weightOf(text))

    private fun fake(mode: Mode, c: Counters) = object : TtsBackend {
        override suspend fun single(apiKey: String, model: String, voice: String, style: String, text: String): ByteArray {
            c.singles++
            return ByteArrayOutputStream().also { speechOf(it, text) }.toByteArray()
        }

        override suspend fun batch(apiKey: String, model: String, turns: List<GeminiSpeechClient.BatchTurn>, paddedSingle: Boolean): ByteArray {
            c.batches++
            c.sizes.add(turns.size)
            if (mode == Mode.REJECT_SINGLE_ARRAY && turns.map { it.voice }.distinct().size == 1 && !paddedSingle) {
                throw GeminiApiException(400, "una voz con varios turnos no vale", false)
            }
            val out = ByteArrayOutputStream()
            quiet(out, 200)
            turns.forEachIndexed { i, t ->
                speechOf(out, t.text.replace(GeminiSpeechClient.TURN_BREAK, ""))
                quiet(out, if (mode != Mode.NO_SILENCES && i < turns.lastIndex) 1500 else 230)
            }
            return out.toByteArray()
        }
    }

    private var uid = 0

    /** Capítulo con narrador (frases largas) y dos personajes; el texto de cada frase es único. */
    private fun chapter(n: Int, seed: Int = 4): List<TTSHelper.TTSLine> {
        val rnd = Random(seed)
        return (0 until n).map { i ->
            val (speaker, words) = when (rnd.nextInt(10)) {
                in 0..5 -> "Narrador" to (14 + rnd.nextInt(10)) // ~100 caracteres
                in 6..7 -> "Subaru" to (4 + rnd.nextInt(5))
                else -> "Flop" to (4 + rnd.nextInt(5))
            }
            val text = "n$i " + (0 until words).joinToString(" ") { "palab" }
            val vl = VoiceLine(speaker, text, "", 220, i * 100, i * 100 + 90, ChunkKind.DIALOGUE, PauseKind.NONE)
            TTSHelper.TTSLine(text, vl.start, vl.end, 0, vl)
        }
    }

    private fun player(c: Counters, mode: Mode, root: File? = null) = GeminiTtsPlayer(
        root ?: java.nio.file.Files.createTempDirectory("ttschapter").toFile(),
        "Novela capitulo ${uid++}", "key", "gemini-3.8-flash-tts", fake(mode, c),
        batching = true, chapterMode = true
    )

    private fun readAll(p: GeminiTtsPlayer, lines: List<TTSHelper.TTSLine>): List<File?> = runBlocking {
        p.setChapter(lines)
        lines.map { l -> p.warmUp(l); p.audioFor(l) }
    }

    private fun renderedMs(text: String): Int {
        val o = ByteArrayOutputStream()
        speechOf(o, text)
        return o.size() / 48
    }

    @Test
    fun aWholeChapterCostsAFewRequestsAndEveryLineGetsItsOwnAudioBack() {
        val c = Counters()
        val lines = chapter(150)
        val files = readAll(player(c, Mode.OK), lines)
        assertEquals(0, c.singles)
        assertTrue("peticiones: ${c.requests}", c.requests in 1..4)
        // el audio recolocado corresponde a SU frase (las longitudes son distintas para cada una)
        lines.forEachIndexed { i, l ->
            val expected = renderedMs(l.speakOutMsg)
            val got = (files[i]!!.length() / 48).toInt()
            assertTrue("frase $i: $got vs $expected", abs(got - expected) <= expected * 0.12 + 100)
        }
    }

    @Test
    fun theEstimateMatchesWhatIsActuallySpentAndShrinksWhenAudioIsAlreadyCached() {
        val root = java.nio.file.Files.createTempDirectory("ttschapter").toFile()
        val c = Counters()
        val lines = chapter(150)
        val p = player(c, Mode.OK, root)
        p.setChapter(lines)
        val estimate = p.estimateRequests()
        readAll(p, lines)
        assertEquals(estimate, c.requests)
        // el mismo capítulo otra vez (caché de 3 días): cero peticiones
        val p2 = GeminiTtsPlayer(root, "Novela capitulo ${uid - 1}", "key", "gemini-3.8-flash-tts", fake(Mode.OK, c), true, true)
        p2.setChapter(lines)
        assertEquals(0, p2.estimateRequests())
    }

    @Test
    fun ifTheModelNeverLeavesSilencesOnlyTwoRequestsAreWastedAndNoQuotaIsBurnedOnSingles() {
        val c = Counters()
        val lines = chapter(150)
        val files = readAll(player(c, Mode.NO_SILENCES), lines)
        assertTrue("peticiones: ${c.requests}", c.requests <= 2)
        assertEquals(0, c.singles) // 150 frases sueltas no caben en 10 peticiones al día: se omiten
        assertTrue(files.all { it == null })
    }

    @Test
    fun anApiThatRejectsSeveralTurnsOfOneVoiceIsSolvedWithThePaddedForm() {
        val c = Counters()
        val lines = chapter(150)
        val files = readAll(player(c, Mode.REJECT_SINGLE_ARRAY), lines)
        assertTrue(files.all { it != null && it.length() > 0 })
        assertEquals(0, c.singles)
        assertTrue("peticiones: ${c.requests}", c.requests <= 8) // + el 400 de la forma en array
    }
}
