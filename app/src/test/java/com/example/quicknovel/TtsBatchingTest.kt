package com.example.quicknovel

import com.lagradost.quicknovel.util.tts.Batch
import com.lagradost.quicknovel.util.tts.BatchPlanner
import com.lagradost.quicknovel.util.tts.GeminiSpeechClient
import com.lagradost.quicknovel.util.tts.GeminiVoices
import com.lagradost.quicknovel.util.tts.PlanLine
import com.lagradost.quicknovel.util.tts.SilenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Suppress("UNCHECKED_CAST")
class TtsBatchingTest {
    private val prebuilt: (String) -> Boolean = { id -> GeminiVoices.prebuilt.any { it.id == id } }
    private fun line(i: Int, voice: String, w: Int = 40, style: String = "") = PlanLine(i, voice, style, w)

    // ------------------------------------------------------------------ planificador

    @Test
    fun everyLineIsInExactlyOneBatchInReadingOrder() {
        val voices = listOf("Charon", "Puck", "Kore", "Charon", "Puck", "Charon", "Kore", "Kore", "Charon")
        val lines = voices.mapIndexed { i, v -> line(i, v) }
        val batches = BatchPlanner.plan(lines, prebuilt)
        assertEquals(lines.map { it.index }, batches.flatMap { b -> b.lines.map { it.index } })
        assertTrue(batches.all { it.voices.size <= 2 })
        assertTrue(batches.size < lines.size) // se ahorra algo aunque haya 3 voces alternando
    }

    @Test
    fun twoVoicesConversationFillsEachRequest() {
        // Narrador y Subaru: dos voces -> una petición por tanda (3 la primera, luego 8)
        val lines = (0 until 20).map { line(it, if (it % 2 == 0) "Charon" else "Puck", 30) }
        val sizes = BatchPlanner.plan(lines, prebuilt).map { it.lines.size }
        assertEquals(listOf(3, 8, 8, 1), sizes)
    }

    @Test
    fun firstBatchIsSmallSoPlaybackStartsQuickly() {
        val lines = (0 until 10).map { line(it, "Charon", 100) }
        val b = BatchPlanner.plan(lines, prebuilt)
        assertTrue(b[0].chars <= BatchPlanner.FIRST_MAX_CHARS)
        assertTrue(b.drop(1).all { it.chars <= BatchPlanner.MAX_CHARS })
    }

    @Test
    fun customVoicesAreNeverMixedWithAnotherVoice() {
        // las voces de la Biblioteca/Voice design solo van solas en la petición
        val lines = (0 until 6).map { line(it, if (it % 2 == 0) "voice_abc" else "Kore") }
        val batches = BatchPlanner.plan(lines, prebuilt)
        assertTrue(batches.all { it.voices.size == 1 })
        assertEquals(6, batches.size)
    }

    @Test
    fun consecutiveLinesOfACustomVoiceCanStillShareARequest() {
        val lines = (0 until 5).map { line(it, "voice_abc", 30) }
        val batches = BatchPlanner.plan(lines, prebuilt, smallFirst = false)
        assertEquals(1, batches.size)
    }

    @Test
    fun capabilitiesLearnedAtRuntimeAreRespected() {
        val lines = (0 until 12).map { line(it, if (it % 2 == 0) "Charon" else "Puck") }
        assertTrue(BatchPlanner.plan(lines, prebuilt, twoVoices = false).all { it.voices.size == 1 })
        val noMulti = BatchPlanner.plan(
            (0 until 6).map { line(it, "Charon") }, prebuilt, singleVoiceMulti = false
        )
        assertTrue(noMulti.all { it.lines.size == 1 })
        // con dos voces y sin "varios turnos de una voz" siguen valiendo las tandas de 2 voces
        assertTrue(BatchPlanner.plan(lines, prebuilt, singleVoiceMulti = false).any { it.lines.size > 1 })
    }

    @Test
    fun aHugeLineGoesAloneAndNothingIsLost() {
        val lines = listOf(line(0, "Charon", 3000), line(1, "Charon", 20), line(2, "Puck", 20))
        val batches = BatchPlanner.plan(lines, prebuilt, smallFirst = false)
        assertEquals(listOf(0), batches[0].lines.map { it.index })
        assertEquals(3, batches.sumOf { it.lines.size })
    }

    @Test
    fun spokenWeightIgnoresTagsButCountsPauses() {
        assertEquals(6, BatchPlanner.weightOf("Hola, a"))   // 6 caracteres sin espacios
        assertTrue(BatchPlanner.weightOf("Hola <long pause> amigo") > BatchPlanner.weightOf("Hola <short pause> amigo"))
        assertTrue(BatchPlanner.weightOf("Hola <short pause> amigo") > BatchPlanner.weightOf("Hola amigo"))
        assertTrue(BatchPlanner.weightOf("<sigh>") >= 1) // nunca 0: evitaría dividir entre cero
    }

    // ------------------------------------------------------------------ petición

    @Test
    fun twoVoiceRequestUsesSpeakersAndTagsEveryTurn() {
        val body = GeminiSpeechClient.buildBatchRequest(
            "gemini-3.8-flash-tts",
            listOf(
                GeminiSpeechClient.BatchTurn("Charon", "", "A la espera del momento."),
                GeminiSpeechClient.BatchTurn("Puck", "", "Oye, Flop-san."),
                GeminiSpeechClient.BatchTurn("Charon", "surprised", "Abrió los ojos."),
            )
        )
        val cfg = (body["generation_config"] as Map<String, Any>)["speech_config"] as Map<String, Any>
        val speakers = cfg["speakers"] as List<Map<String, Any>>
        assertEquals(listOf("Charon", "Puck"), speakers.map { it["voice"] })
        val content = ((body["input"] as List<Map<String, Any>>)[0]["content"]) as List<Map<String, Any>>
        assertEquals(3, content.size)
        for (item in content) {
            val ann = (item["annotations"] as List<Map<String, Any>>)[0]
            assertEquals("speech_metadata", ann["type"])
            assertTrue(ann.containsKey("speaker")) // obligatorio en cada turno con 2 voces
        }
        assertEquals("surprised", ((content[2]["annotations"] as List<Map<String, Any>>)[0])["style"])
        assertFalse(((content[0]["annotations"] as List<Map<String, Any>>)[0]).containsKey("style"))
    }

    @Test
    fun turnsAreSeparatedByDoubleLongPauseExceptTheLast() {
        val turns = listOf(
            GeminiSpeechClient.BatchTurn("Kore", "", "Uno."),
            GeminiSpeechClient.BatchTurn("Kore", "", "Dos."),
        )
        val content = ((GeminiSpeechClient.buildBatchRequest("m", turns)["input"] as List<Map<String, Any>>)[0]["content"]) as List<Map<String, Any>>
        assertEquals("Uno." + GeminiSpeechClient.TURN_BREAK, content[0]["text"])
        assertEquals("Dos.", content[1]["text"])
    }

    @Test
    fun singleVoiceBatchUsesTheArrayFormAndNoSpeakerLabels() {
        val body = GeminiSpeechClient.buildBatchRequest(
            "m",
            listOf(
                GeminiSpeechClient.BatchTurn("voice_abc", "", "Uno."),
                GeminiSpeechClient.BatchTurn("voice_abc", "whispering", "Dos."),
            )
        )
        val cfg = (body["generation_config"] as Map<String, Any>)["speech_config"] as List<Map<String, Any>>
        assertEquals("voice_abc", cfg[0]["voice"])
        val content = ((body["input"] as List<Map<String, Any>>)[0]["content"]) as List<Map<String, Any>>
        assertFalse(content[0].containsKey("annotations")) // sin estilo ni hablantes: nada que anotar
        val ann = (content[1]["annotations"] as List<Map<String, Any>>)[0]
        assertEquals("whispering", ann["style"])
        assertFalse(ann.containsKey("speaker"))
    }

    @Test
    fun speakerNamesNeverAppearInTheSpokenText() {
        val body = GeminiSpeechClient.buildBatchRequest(
            "m",
            listOf(
                GeminiSpeechClient.BatchTurn("Charon", "", "Texto."),
                GeminiSpeechClient.BatchTurn("Puck", "", "Otro."),
            )
        )
        val content = ((body["input"] as List<Map<String, Any>>)[0]["content"]) as List<Map<String, Any>>
        assertTrue(content.none { (it["text"] as String).contains("Charon") || (it["text"] as String).contains("Puck") })
    }

    // ------------------------------------------------------------------ cortador de audio

    private val sr = 24_000
    private val out = ByteArrayOutputStream()

    private fun tone(ms: Int, amp: Int = 9000, freq: Double = 220.0) {
        val n = sr * ms / 1000
        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort((amp * sin(2 * PI * freq * i / sr)).toInt().toShort())
        out.write(b.array())
    }

    private fun quiet(ms: Int) {
        out.write(ByteArray(sr * ms / 1000 * 2)) // ceros; el ruido de fondo se prueba aparte
    }

    /** Frase hablada: palabras de ~5 caracteres separadas por huecos y una coma cada pocas palabras. */
    private fun spokenLine(weight: Int, rate: Double = 1.0, innerPauseMs: Int = 0) {
        val words = weight / 5
        for (w in 0 until words) {
            tone((5 * 65 * rate).toInt())
            quiet(if (w % 5 == 4) 230 else 90)
            if (innerPauseMs > 0 && w == words / 2) quiet(innerPauseMs)
        }
    }

    private fun speechMs(pcm: ByteArray, thr: Int = 150): Int {
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var loud = 0
        for (i in 0 until pcm.size / 2) if (abs(b.getShort(i * 2).toInt()) > thr) loud++
        return loud * 1000 / sr
    }

    /** ±3 % + 20 ms: la medición cuenta muestras por encima de un umbral, no es exacta. */
    private fun near(actual: Int, expected: Int) = abs(actual - expected) <= expected * 0.03 + 20

    private fun edgesAreQuiet(pcm: ByteArray): Boolean {
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val n = pcm.size / 2
        val k = sr / 100 // 10 ms
        return (0 until k).all { abs(b.getShort(it * 2).toInt()) < 400 } &&
                (n - k until n).all { abs(b.getShort(it * 2).toInt()) < 400 }
    }

    @Test
    fun splitsABatchIntoItsLinesAtTheTurnSilences() {
        val weights = listOf(60, 120, 40, 90)
        quiet(300)
        weights.forEachIndexed { i, w ->
            spokenLine(w)
            if (i < weights.lastIndex) quiet(1500) // dos <long pause>
        }
        quiet(400)
        val slices = SilenceSplitter.split(out.toByteArray(), weights)!!
        assertEquals(4, slices.size)
        // cada trozo contiene el habla de su frase y nada de las demás
        val expected = weights.map { w -> (w / 5) * 5 * 65 }
        slices.forEachIndexed { i, s ->
            assertTrue("trozo $i: ${speechMs(s)} vs ${expected[i]}", near(speechMs(s), expected[i]))
            assertTrue("trozo $i corta en silencio", edgesAreQuiet(s))
        }
    }

    @Test
    fun noSpeechIsLostWhenTheSlicesAreJoined() {
        val weights = listOf(80, 50, 70)
        quiet(200)
        weights.forEachIndexed { i, w -> spokenLine(w); if (i < 2) quiet(1500) }
        val pcm = out.toByteArray()
        val slices = SilenceSplitter.split(pcm, weights)!!
        assertTrue(abs(speechMs(pcm) - slices.sumOf { speechMs(it) }) <= 5) // ±5 ms de redondeo en los cortes
    }

    @Test
    fun differentSpeakingRatesPerLineStillSplitCorrectly() {
        val weights = listOf(80, 80, 80)
        spokenLine(80, 1.0); quiet(1500)
        spokenLine(80, 1.5); quiet(1500) // una frase dicha mucho más despacio
        spokenLine(80, 0.8)
        val slices = SilenceSplitter.split(out.toByteArray(), weights)!!
        assertEquals(3, slices.size)
        assertTrue(speechMs(slices[1]) > speechMs(slices[0]))
        assertTrue(speechMs(slices[2]) < speechMs(slices[0]))
    }

    @Test
    fun anExplicitLongPauseInsideALineIsNotMistakenForATurnBreak() {
        val weights = listOf(60, 100, 60)
        spokenLine(60); quiet(1500)
        spokenLine(100, innerPauseMs = 700) // <long pause> dentro de la frase
        quiet(1500)
        spokenLine(60)
        val slices = SilenceSplitter.split(out.toByteArray(), weights)!!
        assertEquals(3, slices.size)
        assertTrue(near(speechMs(slices[1]), 100 / 5 * 325)) // la frase del medio sigue entera
    }

    @Test
    fun missingTurnSilenceMakesTheSplitterGiveUpInsteadOfCuttingWords() {
        // el modelo no dejó pausa entre turnos: solo huecos normales de palabra/coma
        spokenLine(60); quiet(250); spokenLine(120); quiet(250); spokenLine(40)
        assertEquals(null, SilenceSplitter.split(out.toByteArray(), listOf(60, 120, 40)))
    }

    @Test
    fun oneMissingBoundaryIsDetected() {
        spokenLine(60); quiet(1500); spokenLine(120)
        assertEquals(null, SilenceSplitter.split(out.toByteArray(), listOf(60, 120, 40)))
    }

    @Test
    fun implausibleLengthsAreRejected() {
        // el silencio largo está donde no toca (frase 1 diminuta, frase 2 enorme)
        spokenLine(20); quiet(1500); spokenLine(200)
        assertEquals(null, SilenceSplitter.split(out.toByteArray(), listOf(200, 20)))
    }

    @Test
    fun silenceOrNoiseOnlyIsRejected() {
        quiet(5000)
        assertEquals(null, SilenceSplitter.split(out.toByteArray(), listOf(10, 10)))
    }

    @Test
    fun aSingleLineIsReturnedWhole() {
        spokenLine(40)
        val pcm = out.toByteArray()
        assertTrue(pcm.contentEquals(SilenceSplitter.split(pcm, listOf(40))!![0]))
    }
}
