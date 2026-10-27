package com.lagradost.quicknovel.util.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import kotlinx.coroutines.delay

/**
 * Reproduce un PCM 16 bit / 24 kHz / mono completo y vuelve cuando termina. Si la corrutina se
 * cancela (pausa, salto de frase, parar) el audio se corta al instante y se libera el AudioTrack.
 */
suspend fun playPcm(pcm: ByteArray, speed: Float = 1f) {
    if (pcm.size < 2) return
    val frames = pcm.size / 2
    val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    val format = AudioFormat.Builder()
        .setSampleRate(Pcm.SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    // MODE_STATIC: cada frase cabe entera en memoria (≲ 1,5 MB) y así no hay que ir alimentando
    // el buffer; el constructor con AudioAttributes existe desde la API 21 (minSdk del proyecto).
    @Suppress("DEPRECATION")
    val track = AudioTrack(
        attributes, format, pcm.size, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
    )
    try {
        if (track.write(pcm, 0, pcm.size) <= 0) return
        if (speed != 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                track.playbackParams = PlaybackParams().setSpeed(speed.coerceIn(0.25f, 4f))
            } catch (_: IllegalArgumentException) {
                // velocidad no soportada por este dispositivo: se lee a velocidad normal
            }
        }
        track.play()
        // la duración real depende de la velocidad; margen por si el sistema no avanza la posición
        val expectedMs = (Pcm.durationMs(pcm.size.toLong()) / speed.coerceAtLeast(0.25f)).toLong()
        val deadline = System.currentTimeMillis() + expectedMs + 3000
        while (track.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
            delay(25)
        }
    } finally {
        try {
            track.stop()
        } catch (_: IllegalStateException) {
        }
        track.release()
    }
}
