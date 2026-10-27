package com.example.quicknovel

import com.lagradost.quicknovel.util.tts.ChunkKind
import com.lagradost.quicknovel.util.tts.DialogueChunker
import com.lagradost.quicknovel.util.tts.GeminiSpeakerAnnotator
import com.lagradost.quicknovel.util.tts.GeminiSpeechClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class TtsGeminiRequestTest {
    private fun content(body: Map<String, Any>): Map<String, Any> =
        ((((body["input"] as List<Map<String, Any>>)[0])["content"]) as List<Map<String, Any>>)[0]

    @Test
    fun requestFollowsTheGemini38Schema() {
        val body = GeminiSpeechClient.buildRequest(
            "gemini-3.8-flash-tts", "Kore", "surprised", "¡Oh, vaya! <short pause> ¿De verdad?"
        )
        assertEquals("gemini-3.8-flash-tts", body["model"])
        val rf = body["response_format"] as Map<String, Any>
        assertEquals("audio", rf["type"])
        assertEquals("audio/l16", rf["mime_type"]) // PCM crudo: se concatena sin cabeceras
        assertEquals(24000, rf["sample_rate"])
        val cfg = (body["generation_config"] as Map<String, Any>)["speech_config"] as List<Map<String, Any>>
        assertEquals("Kore", cfg[0]["voice"])
        val item = content(body)
        assertEquals("¡Oh, vaya! <short pause> ¿De verdad?", item["text"])
        val ann = (item["annotations"] as List<Map<String, Any>>)[0]
        assertEquals("speech_metadata", ann["type"])
        assertEquals("surprised", ann["style"])
    }

    @Test
    fun neutralVoiceSendsNoStyleAndSpeakerNameNeverReachesTheText() {
        val body = GeminiSpeechClient.buildRequest(
            "gemini-3.8-flash-lite-tts", "voice_abc123", "  ", "Sí, mi enfermedad está empeorando."
        )
        val item = content(body)
        assertFalse(item.containsKey("annotations"))
        assertFalse((item["text"] as String).contains("Subaru"))
        assertEquals(
            "voice_abc123",
            ((body["generation_config"] as Map<String, Any>)["speech_config"] as List<Map<String, Any>>)[0]["voice"]
        )
    }

    @Test
    fun annotatorPromptMarksTargetsAndContextAndListsKnownActors() {
        val text = (0 until 10).joinToString("\n") { "[Línea $it]" }
        val chunks = DialogueChunker.chunk(text)
        val prompt = GeminiSpeakerAnnotator.buildUserPrompt(chunks, 8, 10, listOf("Subaru", "Flop"))
        assertTrue(prompt.contains("KNOWN ACTORS: Subaru, Flop"))
        assertTrue(prompt.contains("CONTEXT #2 [D] Línea 2"))
        assertTrue(prompt.contains("TARGET #8 [D] Línea 8"))
        assertTrue(prompt.contains("TARGET #9 [D] Línea 9"))
        assertFalse(prompt.contains("#1 [D]")) // fuera del contexto de 6
        assertEquals(ChunkKind.DIALOGUE, chunks[0].kind)
    }

    @Test
    fun annotatorOutputIsExtractedFromFencesAndChatter() {
        val raw = "Claro:\n```json\n[{\"i\":1,\"s\":\"Rem\"}]\n```"
        assertEquals("[{\"i\":1,\"s\":\"Rem\"}]", GeminiSpeakerAnnotator.extractJsonArray(raw))
        assertEquals(null, GeminiSpeakerAnnotator.extractJsonArray("sin json"))
    }

    @Test
    fun systemPromptEncodesTheRulesOfTheFeature() {
        val p = GeminiSpeakerAnnotator.SYSTEM_PROMPT
        assertTrue(p.contains("ONLY when the text states it explicitly"))
        assertTrue(p.contains("first person"))
        assertTrue(p.contains("\"short\"") && p.contains("\"long\""))
    }
}
