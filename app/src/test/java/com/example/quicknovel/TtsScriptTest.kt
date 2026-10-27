package com.example.quicknovel

import com.lagradost.quicknovel.util.tts.ChunkKind
import com.lagradost.quicknovel.util.tts.DialogueChunker
import com.lagradost.quicknovel.util.tts.GeminiVoices
import com.lagradost.quicknovel.util.tts.NARRATOR_NAME
import com.lagradost.quicknovel.util.tts.PauseKind
import com.lagradost.quicknovel.util.tts.RawAnnotation
import com.lagradost.quicknovel.util.tts.ScriptBuilder
import com.lagradost.quicknovel.util.tts.TranscriptBuilder
import com.lagradost.quicknovel.util.tts.UNKNOWN_SPEAKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsScriptTest {
    // El capítulo tal y como lo ve el lector (preParseHtml cambia "..." por "…")
    private val chapter = """
A la espera del momento oportuno para atacar, el plan de Todd debería seguir en marcha en este preciso instante.
[…Oye, Flop-san, ¿me podrías hacer un favor? ¿Podrías volver a la posada y conseguir la medicina para mi enfermedad crónica de Rem?]
[¿Medicina? ¿Estás muy enfermo, esposo?]
[Sí, mi enfermedad del tobillo brillante está empeorando bastante.]
[¡Oh, vaya! ¡Nunca había oído hablar de eso!]
Al oír el nombre de una enfermedad al azar, Flop abrió los ojos de par en par con sorpresa.
""".trim()

    private val annotations = mapOf(
        0 to RawAnnotation(0, "Narrador", "", "none", "none"),
        1 to RawAnnotation(1, "Subaru", "", "short", "none"),
        2 to RawAnnotation(2, "Flop", "", "none", "none"),
        3 to RawAnnotation(3, "Subaru", "", "none", "none"),
        // la sorpresa solo aparece escrita en el párrafo siguiente
        4 to RawAnnotation(4, "Flop", "surprised", "none", "none"),
        5 to RawAnnotation(5, "Narrador", "", "none", "none"),
    )

    @Test
    fun splitsNarrationAndDialogueKeepingPositions() {
        val chunks = DialogueChunker.chunk(chapter)
        assertEquals(
            listOf(
                ChunkKind.NARRATION, ChunkKind.DIALOGUE, ChunkKind.DIALOGUE,
                ChunkKind.DIALOGUE, ChunkKind.DIALOGUE, ChunkKind.NARRATION
            ),
            chunks.map { it.kind }
        )
        // el rango incluye los corchetes, para poder subrayarlo en el lector
        assertEquals(
            "[¿Medicina? ¿Estás muy enfermo, esposo?]",
            chapter.substring(chunks[2].start, chunks[2].end)
        )
        assertEquals("¿Medicina? ¿Estás muy enfermo, esposo?", chunks[2].spoken)
    }

    @Test
    fun hesitationAtTheStartBecomesSilenceBeforeTheVoice() {
        val t = TranscriptBuilder.build("…Oye, Flop-san, ¿me podrías hacer un favor?")
        assertEquals(PauseKind.SHORT, t.leadPause)
        assertTrue(t.text.startsWith("Oye, Flop-san"))
        assertFalse(t.text.contains("…"))

        val long = TranscriptBuilder.build("……Está bien.")
        assertEquals(PauseKind.LONG, long.leadPause)
    }

    @Test
    fun shortAndLongPausesInsideTheTextAreDistinguished() {
        assertEquals(
            "Yo <short pause> no sé.",
            TranscriptBuilder.build("Yo… no sé.").text
        )
        assertEquals(
            "Yo <long pause> no sé.",
            TranscriptBuilder.build("Yo…… no sé.").text
        )
        assertEquals(
            "Yo <long pause> no sé.",
            TranscriptBuilder.build("Yo...... no sé.").text
        )
    }

    @Test
    fun trailingEllipsisKeepsTheTrailOffAndAddsSilenceAfter() {
        val t = TranscriptBuilder.build("No sé qué decir…")
        assertEquals("No sé qué decir...", t.text)
        assertEquals(PauseKind.SHORT, t.trailPause)
    }

    @Test
    fun textFromTheNovelCanNeverInjectTags() {
        assertEquals("a b", TranscriptBuilder.build("a <laugh> b").text.replace("laugh", "").replace(Regex("\\s+"), " ").trim())
        assertFalse(TranscriptBuilder.build("<short pause> hola").text.contains("<"))
    }

    @Test
    fun speakerIsNeverPartOfTheSpokenText() {
        val chunks = DialogueChunker.chunk(chapter)
        val lines = ScriptBuilder.toLines(
            ScriptBuilder.annotate(chunks, annotations, listOf("Subaru", "Flop"))
        )
        assertEquals(6, lines.size)
        for (l in lines) {
            assertFalse(l.text.contains("Subaru:"))
            assertFalse(l.text.contains("Flop:"))
            assertFalse(l.text.contains("["))
        }
        assertEquals(
            listOf("Narrador", "Subaru", "Flop", "Subaru", "Flop", "Narrador"),
            lines.map { it.speaker }
        )
    }

    @Test
    fun explicitEmotionIsAttachedAndEverythingElseStaysNeutral() {
        val lines = ScriptBuilder.toLines(
            ScriptBuilder.annotate(DialogueChunker.chunk(chapter), annotations, emptyList())
        )
        assertEquals("surprised", lines[4].style)
        assertEquals(listOf("", "", "", "", "surprised", ""), lines.map { it.style })
    }

    @Test
    fun hesitationBeforeTheFirstReplyIsAudiblyLongerThanANormalParagraphBreak() {
        val lines = ScriptBuilder.toLines(
            ScriptBuilder.annotate(DialogueChunker.chunk(chapter), annotations, emptyList())
        )
        assertEquals(0, lines[0].gapBeforeMs) // primera frase: sin silencio inicial
        // narración -> Subaru con "…" delante: respiro de párrafo + pausa corta
        assertEquals(PauseKind.SHORT, lines[1].leadPause)
        assertEquals(ScriptBuilder.GAP_PARAGRAPH_MS + PauseKind.SHORT_PAUSE_MS, lines[1].gapBeforeMs)
        // cambio de hablante sin pausa explícita: solo el respiro normal
        assertEquals(ScriptBuilder.GAP_PARAGRAPH_MS, lines[2].gapBeforeMs)
        assertTrue(lines[1].gapBeforeMs > lines[2].gapBeforeMs)
    }

    @Test
    fun longPauseIsClearlyLongerThanShortPause() {
        assertTrue(PauseKind.LONG.ms >= 2 * PauseKind.SHORT.ms)
        assertEquals(0, PauseKind.NONE.ms)
    }

    @Test
    fun narrationCanCreateALongPauseBeforeTheNextReply() {
        // "Tras un largo silencio..." -> el modelo marca post = long en la narración
        val ann = annotations + (0 to RawAnnotation(0, "Narrador", "", "none", "long"))
        val lines = ScriptBuilder.toLines(
            ScriptBuilder.annotate(DialogueChunker.chunk(chapter), ann, emptyList())
        )
        // la pausa larga de la narración y la corta de "…Oye" coinciden: vale la mayor, sin duplicar
        assertEquals(ScriptBuilder.GAP_PARAGRAPH_MS + PauseKind.LONG_PAUSE_MS, lines[1].gapBeforeMs)
    }

    @Test
    fun readableScriptUsesTheHablantesIdentificadosFormat() {
        val script = ScriptBuilder.render(
            ScriptBuilder.toLines(
                ScriptBuilder.annotate(DialogueChunker.chunk(chapter), annotations, emptyList())
            )
        ).lines().filter { it.isNotBlank() }
        assertEquals(
            "Narrador: A la espera del momento oportuno para atacar, el plan de Todd debería seguir en marcha en este preciso instante.",
            script[0]
        )
        assertEquals(
            "Subaru: [<short pause>Oye, Flop-san, ¿me podrías hacer un favor? ¿Podrías volver a la posada y conseguir la medicina para mi enfermedad crónica de Rem?]",
            script[1]
        )
        assertEquals("Flop: [¿Medicina? ¿Estás muy enfermo, esposo?]", script[2])
        assertEquals("Flop (surprised): [¡Oh, vaya! ¡Nunca había oído hablar de eso!]", script[4])
        assertTrue(script[5].startsWith("Narrador: Al oír el nombre"))
    }

    @Test
    fun withoutAnnotationsDialogueIsNotSpokenByTheNarrator() {
        val lines = ScriptBuilder.toLines(
            ScriptBuilder.annotate(DialogueChunker.chunk(chapter), emptyMap(), emptyList())
        )
        assertEquals(NARRATOR_NAME, lines[0].speaker)
        assertEquals(UNKNOWN_SPEAKER, lines[1].speaker)
    }

    @Test
    fun speakerNamesAreMatchedToKnownActorsIgnoringCase() {
        assertEquals("Subaru", ScriptBuilder.canonicalSpeaker("  subaru ", listOf("Subaru")))
        assertEquals(NARRATOR_NAME, ScriptBuilder.canonicalSpeaker("narrator", emptyList()))
        assertEquals("Rem", ScriptBuilder.canonicalSpeaker("\"Rem\":", emptyList()))
    }

    @Test
    fun dashDialogueSeparatesTheNarratorsAside() {
        val chunks = DialogueChunker.chunk("—Hola —dijo Juan—. ¿Qué tal?")
        assertEquals(
            listOf(ChunkKind.DIALOGUE, ChunkKind.NARRATION, ChunkKind.DIALOGUE),
            chunks.map { it.kind }
        )
        assertEquals("Hola", chunks[0].spoken)
        assertEquals("dijo Juan", chunks[1].spoken)
        assertEquals("¿Qué tal?", TranscriptBuilder.build(chunks[2].spoken).text)
    }

    @Test
    fun otherQuoteStylesAreRecognised() {
        for (text in listOf("«Hola» dijo", "“Hola” dijo", "\"Hola\" dijo", "「Hola」 dijo")) {
            val chunks = DialogueChunker.chunk(text)
            assertEquals(text, ChunkKind.DIALOGUE, chunks[0].kind)
            assertEquals(text, "Hola", chunks[0].spoken)
            assertEquals(text, ChunkKind.NARRATION, chunks[1].kind)
        }
    }

    @Test
    fun veryLongTextIsSplitIntoSentencesWithOffsetsInsideTheChapter() {
        val sentence = "Esta es una frase de relleno bastante larga para el test. "
        val text = sentence.repeat(30).trim()
        val chunks = DialogueChunker.chunk(text)
        assertEquals(1, chunks.size)
        val pieces = chunks[0].pieces
        assertTrue(pieces.size > 3)
        for (p in pieces) {
            assertEquals(p.spoken, text.substring(p.start, p.end))
            assertTrue(p.spoken.length <= 700)
        }
        // todos los fragmentos menos el primero se enlazan con un respiro corto
        val lines = ScriptBuilder.toLines(ScriptBuilder.annotate(chunks, emptyMap(), emptyList()))
        assertEquals(0, lines[0].gapBeforeMs)
        assertTrue(lines.drop(1).all { it.gapBeforeMs == ScriptBuilder.GAP_CONTINUATION_MS })
    }

    @Test
    fun paragraphsWithoutLettersProduceNothing() {
        assertTrue(DialogueChunker.chunk("* * *\n---\n…").isEmpty())
    }

    @Test
    fun newCharactersGetDistinctVoicesOfTheRightGender() {
        val used = mutableSetOf<String>()
        val female = GeminiVoices.prebuilt.filter { it.female }.map { it.id }.toSet()
        repeat(10) { i ->
            val v = GeminiVoices.suggest("Personaje$i", "f", used)
            assertTrue(v in female)
            assertFalse(v in used) // quedan libres: no se repite
            used.add(v)
        }
        // determinista
        assertEquals(
            GeminiVoices.suggest("Rem", "f", emptySet()),
            GeminiVoices.suggest("Rem", "f", emptySet())
        )
        // el narrador conserva su voz propia
        assertFalse(GeminiVoices.suggest("Cualquiera", null, emptySet()) == GeminiVoices.NARRATOR_VOICE)
    }
}
