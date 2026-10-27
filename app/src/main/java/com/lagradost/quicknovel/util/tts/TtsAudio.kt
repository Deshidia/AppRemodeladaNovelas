package com.lagradost.quicknovel.util.tts

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Audio de Gemini TTS: PCM 16 bit, mono, 24 kHz (lo que devuelve la API con audio/l16).
 * Todo en java.io para poder probarlo sin Android.
 */
object Pcm {
    const val SAMPLE_RATE = 24_000
    const val CHANNELS = 1
    const val BITS = 16
    private const val BYTES_PER_MS = SAMPLE_RATE * CHANNELS * BITS / 8 / 1000 // 48

    /** Bytes de silencio para [ms] milisegundos (siempre par: muestras de 16 bit). */
    fun silenceBytes(ms: Int): Int = (maxOf(ms, 0) * BYTES_PER_MS).let { it - it % 2 }

    fun durationMs(pcmBytes: Long): Long = pcmBytes / BYTES_PER_MS

    /** Cabecera RIFF/WAVE de 44 bytes para [dataLen] bytes de PCM. */
    fun wavHeader(dataLen: Long): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt((36 + dataLen).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)
        h.putShort(1) // PCM
        h.putShort(CHANNELS.toShort())
        h.putInt(SAMPLE_RATE)
        h.putInt(byteRate)
        h.putShort((CHANNELS * BITS / 8).toShort())
        h.putShort(BITS.toShort())
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(dataLen.toInt())
        return h.array()
    }

    /**
     * Si [bytes] es un WAV (la API devuelve WAV si no se pide otra cosa) devuelve solo el PCM;
     * si ya es PCM crudo lo devuelve igual.
     */
    fun stripWavHeader(bytes: ByteArray): ByteArray {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return bytes
        // recorre los "chunks" hasta encontrar "data" (no siempre mide 44 bytes)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = java.nio.ByteBuffer.wrap(bytes, pos + 4, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val start = pos + 8
                val len = if (size < 0 || start + size > bytes.size) bytes.size - start else size
                return bytes.copyOfRange(start, start + len)
            }
            pos += 8 + size + (size and 1)
        }
        return bytes
    }
}

/** Trozo de un capítulo completo: silencio o un PCM guardado en la caché. */
sealed class PcmPart {
    abstract val length: Long

    class Silence(val ms: Int) : PcmPart() {
        override val length: Long get() = Pcm.silenceBytes(ms).toLong()
    }

    class FromFile(val file: File) : PcmPart() {
        override val length: Long get() = file.length() - file.length() % 2
    }
}

object WavWriter {
    /**
     * Escribe un WAV con todos los [parts], uno tras otro, sin cargarlos a la vez en memoria
     * (un capítulo de media hora son ~90 MB de PCM). Devuelve los bytes de audio escritos.
     */
    fun write(out: OutputStream, parts: List<PcmPart>): Long {
        val total = parts.sumOf { it.length }
        out.write(Pcm.wavHeader(total))
        val zeros = ByteArray(8192)
        val buf = ByteArray(64 * 1024)
        for (part in parts) {
            when (part) {
                is PcmPart.Silence -> {
                    var left = part.length
                    while (left > 0) {
                        val n = minOf(left, zeros.size.toLong()).toInt()
                        out.write(zeros, 0, n)
                        left -= n
                    }
                }

                is PcmPart.FromFile -> part.file.inputStream().use { input ->
                    copyExactly(input, out, part.length, buf)
                }
            }
        }
        out.flush()
        return total
    }

    private fun copyExactly(input: InputStream, out: OutputStream, length: Long, buf: ByteArray) {
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        // un archivo más corto de lo esperado no debe descuadrar la cabecera
        if (left > 0) {
            val zeros = ByteArray(minOf(left, buf.size.toLong()).toInt())
            while (left > 0) {
                val n = minOf(left, zeros.size.toLong()).toInt()
                out.write(zeros, 0, n)
                left -= n
            }
        }
    }
}

/**
 * Caché de audio por frase. Se guarda en cacheDir/gemini_tts/<novela>/<hash>.pcm y caduca a los
 * 3 días (se borra con [sweep]). El audio definitivo, si el usuario lo pide, va a la carpeta
 * TTS de la ruta de descarga y NO caduca.
 */
class TtsAudioCache(private val root: File) {
    companion object {
        const val DIR = "gemini_tts"
        const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

        fun sha1(vararg parts: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            for (p in parts) {
                md.update(p.toByteArray(Charsets.UTF_8))
                md.update(0) // separador: ("ab","c") != ("a","bc")
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Nombre válido como carpeta/archivo en cualquier sistema de archivos. */
        fun safeName(name: String, fallback: String = "novela"): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .replace(Regex("\\s+"), " ").trim().trim('.')
            return cleaned.take(80).trim().ifEmpty { fallback }
        }
    }

    private fun dir(novel: String) = File(root, safeName(novel))

    fun key(model: String, voice: String, style: String, text: String) =
        sha1(model, voice, style, text)

    fun file(novel: String, key: String, ext: String = "pcm") = File(dir(novel), "$key.$ext")

    fun read(novel: String, key: String): ByteArray? {
        val f = file(novel, key)
        return if (f.isFile && f.length() > 0) f.readBytes() else null
    }

    /** Escribe de forma atómica: nunca queda un PCM a medias si se corta la app. */
    fun write(novel: String, key: String, bytes: ByteArray, ext: String = "pcm"): File {
        val target = file(novel, key, ext)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        return target
    }

    fun readText(novel: String, key: String, ext: String): String? =
        file(novel, key, ext).takeIf { it.isFile }?.readText()

    /** Borra lo que lleve más de [maxAgeMs] en la caché y las carpetas que queden vacías. */
    fun sweep(now: Long = System.currentTimeMillis(), maxAgeMs: Long = MAX_AGE_MS): Int {
        var deleted = 0
        val novels = root.listFiles() ?: return 0
        for (novelDir in novels) {
            if (!novelDir.isDirectory) continue
            for (f in novelDir.listFiles().orEmpty()) {
                if (f.isFile && now - f.lastModified() > maxAgeMs && f.delete()) deleted++
            }
            if (novelDir.listFiles().isNullOrEmpty()) novelDir.delete()
        }
        return deleted
    }
}
