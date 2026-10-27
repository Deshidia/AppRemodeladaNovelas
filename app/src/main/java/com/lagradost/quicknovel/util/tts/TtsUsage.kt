package com.lagradost.quicknovel.util.tts

import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.TTS_USAGE_TODAY
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Contador LOCAL de peticiones al TTS de hoy (cada intento cuenta, también los reintentos por
 * 429). Es solo una guía para vigilar la cuota: Google lleva su propia cuenta, y su día termina
 * a medianoche hora del Pacífico, no a la tuya.
 */
object TtsUsage {
    private fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun record(n: Int = 1, model: String = "") {
        val today = day()
        val key = if (model.isBlank()) TTS_USAGE_TODAY else "${TTS_USAGE_TODAY}_${model.replace('-', '_')}"
        setKey(key, "$today|${count(today, key) + n}")
    }

    fun today(model: String = ""): Int {
        val key = if (model.isBlank()) TTS_USAGE_TODAY else "${TTS_USAGE_TODAY}_${model.replace('-', '_')}"
        return count(day(), key)
    }

    private fun count(today: String, key: String): Int {
        val stored = getKey<String>(key) ?: return 0
        val parts = stored.split('|')
        return if (parts.size == 2 && parts[0] == today) parts[1].toIntOrNull() ?: 0 else 0
    }
}
