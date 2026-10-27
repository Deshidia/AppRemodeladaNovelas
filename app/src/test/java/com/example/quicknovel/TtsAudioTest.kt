package com.example.quicknovel

import com.lagradost.quicknovel.util.tts.Pcm
import com.lagradost.quicknovel.util.tts.PcmPart
import com.lagradost.quicknovel.util.tts.TtsAudioCache
import com.lagradost.quicknovel.util.tts.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TtsAudioTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("ttstest").toFile()

    @Test
    fun silenceHasTheRightLengthAndIsSampleAligned() {
        assertEquals(48_000, Pcm.silenceBytes(1000)) // 24 kHz * 2 bytes
        assertEquals(0, Pcm.silenceBytes(0))
        assertEquals(0, Pcm.silenceBytes(-5))
        assertEquals(0, Pcm.silenceBytes(1000) % 2)
    }

    @Test
    fun wavHeaderDescribes24kHzMono16bit() {
        val h = ByteBuffer.wrap(Pcm.wavHeader(48_000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.capacity())
        assertEquals("RIFF", String(h.array(), 0, 4))
        assertEquals(36 + 48_000, h.getInt(4))
        assertEquals("WAVE", String(h.array(), 8, 4))
        assertEquals(1, h.getShort(20).toInt()) // PCM
        assertEquals(1, h.getShort(22).toInt()) // mono
        assertEquals(24_000, h.getInt(24))
        assertEquals(48_000, h.getInt(28)) // byte rate
        assertEquals(16, h.getShort(34).toInt())
        assertEquals("data", String(h.array(), 36, 4))
        assertEquals(48_000, h.getInt(40))
    }

    @Test
    fun wavFromTheApiIsStrippedAndRawPcmIsLeftAlone() {
        val pcm = ByteArray(1000) { (it % 100).toByte() }
        val wav = Pcm.wavHeader(pcm.size.toLong()) + pcm
        assertTrue(pcm.contentEquals(Pcm.stripWavHeader(wav)))
        assertTrue(pcm.contentEquals(Pcm.stripWavHeader(pcm)))
    }

    @Test
    fun wavWithExtraChunksIsStrippedCorrectly() {
        val pcm = ByteArray(200) { 7 }
        val extra = ByteBuffer.allocate(8 + 4).order(ByteOrder.LITTLE_ENDIAN)
        extra.put("LIST".toByteArray()).putInt(4).put("abcd".toByteArray())
        val h = Pcm.wavHeader(pcm.size.toLong())
        val wav = h.copyOfRange(0, 36) + extra.array() + h.copyOfRange(36, 44) + pcm
        assertTrue(pcm.contentEquals(Pcm.stripWavHeader(wav)))
    }

    @Test
    fun chapterWavConcatenatesAudioAndSilenceInOrder() {
        val dir = tmp()
        val a = File(dir, "a.pcm").also { it.writeBytes(ByteArray(96) { 1 }) }
        val b = File(dir, "b.pcm").also { it.writeBytes(ByteArray(48) { 2 }) }
        val out = ByteArrayOutputStream()
        val total = WavWriter.write(
            out,
            listOf(PcmPart.Silence(0), PcmPart.FromFile(a), PcmPart.Silence(10), PcmPart.FromFile(b))
        )
        val bytes = out.toByteArray()
        assertEquals(96L + 480 + 48, total)
        assertEquals(44 + total.toInt(), bytes.size)
        assertEquals(total.toInt(), ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        assertEquals(1, bytes[44].toInt())            // primero el audio de a
        assertEquals(0, bytes[44 + 96].toInt())       // luego 10 ms de silencio
        assertEquals(2, bytes[44 + 96 + 480].toInt()) // y por último b
    }

    @Test
    fun truncatedCacheFileNeverBreaksTheHeader() {
        val dir = tmp()
        val a = File(dir, "a.pcm").also { it.writeBytes(ByteArray(101) { 1 }) } // impar
        val out = ByteArrayOutputStream()
        val total = WavWriter.write(out, listOf(PcmPart.FromFile(a)))
        assertEquals(100L, total)
        assertEquals(44 + 100, out.size())
    }

    @Test
    fun cacheKeyChangesWithEveryInput() {
        val c = TtsAudioCache(tmp())
        val base = c.key("m", "Kore", "", "hola")
        assertFalse(base == c.key("m2", "Kore", "", "hola"))
        assertFalse(base == c.key("m", "Puck", "", "hola"))   // otra voz = otro audio
        assertFalse(base == c.key("m", "Kore", "sad", "hola"))
        assertFalse(base == c.key("m", "Kore", "", "hola!"))
        assertEquals(base, c.key("m", "Kore", "", "hola"))
        assertFalse(c.key("ab", "c", "", "") == c.key("a", "bc", "", ""))
    }

    @Test
    fun cacheRoundTripsAndOldEntriesExpireAfterThreeDays() {
        val root = tmp()
        val c = TtsAudioCache(root)
        c.write("Re:Zero", "fresh", byteArrayOf(1, 2, 3, 4))
        val old = c.write("Re:Zero", "old", byteArrayOf(9, 9))
        val now = System.currentTimeMillis()
        old.setLastModified(now - 3 * 24 * 3600 * 1000L - 60_000) // 3 días y un minuto

        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(c.read("Re:Zero", "fresh")!!))
        assertEquals(1, c.sweep(now))
        assertTrue(c.read("Re:Zero", "old") == null)
        assertTrue(c.read("Re:Zero", "fresh") != null) // lo reciente se conserva
    }

    @Test
    fun emptyNovelFoldersAreRemovedByTheSweep() {
        val root = tmp()
        val c = TtsAudioCache(root)
        val f = c.write("Vieja", "k", byteArrayOf(1, 2))
        f.setLastModified(System.currentTimeMillis() - 4 * 24 * 3600 * 1000L)
        c.sweep()
        assertFalse(File(root, "Vieja").exists())
    }

    @Test
    fun novelNamesBecomeSafeFolderNames() {
        assertEquals("Re Zero Kara", TtsAudioCache.safeName("Re:Zero  Kara"))
        assertEquals("novela", TtsAudioCache.safeName("///"))
        assertFalse(TtsAudioCache.safeName("a/b\\c*d?e").any { it in "/\\*?" })
    }
}
