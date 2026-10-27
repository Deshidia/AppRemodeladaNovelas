package com.lagradost.quicknovel.util.tts

import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.TTS_RPD_LIMIT
import com.lagradost.quicknovel.TTS_RPM_LIMIT
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Limitador de peticiones por minuto (ventana deslizante de 60 s). Con 3 RPM, la 4.ª petición
 * espera a que la 1.ª cumpla el minuto en vez de estrellarse contra un 429 (que además cuenta).
 */
class RpmLimiter(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val stamps = ArrayDeque<Long>()
    private val mutex = Mutex()
    private var lastScheduled: Long = Long.MIN_VALUE / 2

    /** Espera, si hace falta, hasta poder hacer otra petición. [rpm] <= 0 = sin límite. */
    suspend fun acquire(rpm: Int) {
        if (rpm <= 0) return
        val waitTime: Long = mutex.withLock {
            val t = now()
            while (stamps.isNotEmpty() && t - stamps.first() >= WINDOW_MS) {
                stamps.removeFirst()
            }

            val minSpacing = 1_000L
            val windowTime = if (stamps.size >= rpm) {
                stamps.first() + WINDOW_MS
            } else {
                t
            }

            val scheduledTime = maxOf(t, lastScheduled + minSpacing, windowTime)
            lastScheduled = scheduledTime

            stamps.addLast(scheduledTime)
            while (stamps.size > rpm && stamps.first() <= scheduledTime - WINDOW_MS) {
                stamps.removeFirst()
            }

            scheduledTime - t
        }

        if (waitTime > 0) {
            sleep(waitTime + MARGIN_MS)
        }
    }

    /** Si la API devuelve un 429 de ritmo, se aplaza la siguiente petición programada. */
    suspend fun onRateLimited(retryAfterMs: Long? = null, rpm: Int = TtsQuota.rpm()) {
        mutex.withLock {
            val t = now()
            val minInterval = if (rpm > 0) WINDOW_MS / rpm + 1_000L else 20_000L
            val penalty = retryAfterMs?.coerceAtLeast(1_000L) ?: minInterval
            lastScheduled = maxOf(lastScheduled, t + penalty)
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        const val MARGIN_MS = 250L
    }
}

/**
 * Tu cuota del TTS (se ve en AI Studio > Límites de frecuencia). Por defecto la del nivel gratuito
 * de Gemini 3.8 Flash TTS: 3 peticiones/minuto y 10 al día. Si tu cuota es mayor, cámbiala en
 * Actores; 0 = sin límite.
 */
object TtsQuota {
    const val DEFAULT_RPM_FLASH = 3
    const val DEFAULT_RPD_FLASH = 10

    const val DEFAULT_RPM_LITE = 15
    const val DEFAULT_RPD_LITE = 1500

    const val DEFAULT_RPM = DEFAULT_RPM_FLASH
    const val DEFAULT_RPD = DEFAULT_RPD_FLASH

    val limiter = RpmLimiter()

    fun defaultRpm(model: String? = null): Int =
        if (model != null && "lite" in model.lowercase()) DEFAULT_RPM_LITE else DEFAULT_RPM_FLASH

    fun defaultRpd(model: String? = null): Int =
        if (model != null && "lite" in model.lowercase()) DEFAULT_RPD_LITE else DEFAULT_RPD_FLASH

    fun rpm(model: String? = null): Int = getKey<Int>(TTS_RPM_LIMIT) ?: defaultRpm(model)
    fun rpd(model: String? = null): Int = getKey<Int>(TTS_RPD_LIMIT) ?: defaultRpd(model)

    fun setLimits(rpm: Int, rpd: Int) {
        setKey(TTS_RPM_LIMIT, rpm.coerceAtLeast(0))
        setKey(TTS_RPD_LIMIT, rpd.coerceAtLeast(0))
    }

    /** Peticiones que quedan hoy según el contador local; null = sin límite configurado (0). */
    fun remainingToday(model: String = ""): Int? =
        rpd(model).takeIf { it > 0 }?.let { maxOf(0, it - TtsUsage.today(model)) }

    /** ¿El cuerpo de un 429 habla de la cuota DIARIA? Entonces reintentar es tirar peticiones. */
    fun isDailyQuotaError(body: String): Boolean {
        val t = body.lowercase()
        return "perday" in t || "per day" in t || "daily" in t || "per_day" in t
    }
}
