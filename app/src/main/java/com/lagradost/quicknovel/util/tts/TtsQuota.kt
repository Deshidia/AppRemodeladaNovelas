package com.lagradost.quicknovel.util.tts

import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.CommonActivity
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.TTS_RPD_LIMIT
import com.lagradost.quicknovel.TTS_RPM_LIMIT
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Limitador de peticiones por minuto (ventana deslizante de 60 s). Con 3 RPM, la 4.ª petición
 * espera a que la 1.ª cumpla el minuto en vez de estrellarse contra un 429 (que además cuenta).
 */
class RpmLimiter(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: (suspend (Long) -> Unit)? = null,
    /** Se avisa (con los ms que va a esperar) cuando hay que esperar de verdad. */
    private val onWait: (Long) -> Unit = {},
) {
    private val stamps = ArrayDeque<Long>()
    private val mutex = Mutex()

    /** Espera, si hace falta, hasta poder hacer otra petición. [rpm] <= 0 = sin límite. */
    suspend fun acquire(rpm: Int) {
        if (rpm <= 0) return
        mutex.withLock {
            while (true) {
                val t = now()
                while (stamps.isNotEmpty() && t - stamps.first() >= WINDOW_MS) stamps.removeFirst()
                if (stamps.size < rpm) {
                    stamps.addLast(t)
                    return
                }
                val wait = stamps.first() + WINDOW_MS - t + MARGIN_MS
                onWait(wait)
                pause(sleep, wait)
            }
        }
    }

    /**
     * El servidor dijo 429: por mucho que nuestra cuenta diga otra cosa, estamos al límite. Se da por
     * llena la ventana entera, así que la siguiente petición espera un minuto completo.
     */
    suspend fun penalize(rpm: Int) {
        if (rpm <= 0) return
        mutex.withLock {
            val t = now()
            while (stamps.isNotEmpty() && t - stamps.first() >= WINDOW_MS) stamps.removeFirst()
            while (stamps.size < rpm) stamps.addLast(t)
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L

        // el servidor cuenta por su reloj y con la latencia de la red: un margen holgado evita rozar el límite
        const val MARGIN_MS = 1_500L
    }
}

/**
 * Tu cuota del TTS (se ve en AI Studio > Límites de frecuencia). Por defecto la del nivel gratuito
 * de Gemini 3.8 Flash TTS: 3 peticiones/minuto y 10 al día. Si tu cuota es mayor, cámbiala en
 * Actores; 0 = sin límite.
 */
object TtsQuota {
    const val DEFAULT_RPM = 3
    const val DEFAULT_RPD = 10

    /**
     * UN solo limitador para TODAS las peticiones de esta función (audio e identificación de hablantes):
     * así nunca se pasa de [rpm] por minuto en total, sea cual sea el modelo que dé el 429.
     */
    val limiter = RpmLimiter(onWait = { ms ->
        // aviso, como mucho cada 20 s, para que se entienda por qué la lectura se queda esperando
        val now = System.currentTimeMillis()
        if (ms >= 5_000 && now - lastWaitToast > 20_000) {
            lastWaitToast = now
            BaseApplication.context?.let { c ->
                CommonActivity.showToast(c.getString(R.string.tts_rate_wait, ((ms + 999) / 1000).toInt()))
            }
        }
    })

    @Volatile
    private var lastWaitToast = 0L

    fun rpm(): Int = getKey<Int>(TTS_RPM_LIMIT) ?: DEFAULT_RPM
    fun rpd(): Int = getKey<Int>(TTS_RPD_LIMIT) ?: DEFAULT_RPD

    fun setLimits(rpm: Int, rpd: Int) {
        setKey(TTS_RPM_LIMIT, rpm.coerceAtLeast(0))
        setKey(TTS_RPD_LIMIT, rpd.coerceAtLeast(0))
    }

    /** Peticiones que quedan hoy según el contador local; null = sin límite configurado. */
    fun remainingToday(): Int? = rpd().takeIf { it > 0 }?.let { maxOf(0, it - TtsUsage.today()) }

    /** ¿El cuerpo de un 429 habla de la cuota DIARIA? Entonces reintentar es tirar peticiones. */
    fun isDailyQuotaError(body: String): Boolean {
        val t = body.lowercase()
        return "perday" in t || "per day" in t || "daily" in t || "per_day" in t
    }

    /** Qué cuota se pasó, según el cuerpo de un 429 (p. ej. "GenerateRequestsPerMinutePerProjectPerModel-FreeTier (gemini-3.8-flash)"). */
    fun describe429(body: String): String {
        val quota = Regex("\"quotaId\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: Regex("\"quotaMetric\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        val message = Regex("\"message\"\\s*:\\s*\"([^\"]{0,160})").find(body)?.groupValues?.get(1)
        return when {
            quota != null && model != null -> "$quota ($model)"
            quota != null -> quota
            else -> message ?: "HTTP 429"
        }
    }

    /**
     * Punto de paso ÚNICO de cada petición a Gemini: respeta el límite por minuto, y trata los fallos
     * sin gastar de más:
     *  - 429 de cuota DIARIA: error fatal inmediato (no hay nada que reintentar hasta mañana).
     *  - 429 de frecuencia: se "llena" la ventana (la siguiente espera un minuto entero) y se reintenta.
     *  - 5xx / fallo de red: reintento con espera creciente.
     * Devuelve la respuesta final (ok o un error no reintentable como 400/401/403) para que el
     * llamador la interprete. [onAttempt] cuenta cada intento contra la cuota del TTS.
     */
    suspend fun send(
        limiter: RpmLimiter = this.limiter,
        rpm: Int = rpm(),
        maxAttempts: Int = 4,
        sleep: (suspend (Long) -> Unit)? = null,
        onAttempt: () -> Unit = { TtsUsage.record() },
        request: suspend () -> HttpReply,
    ): HttpReply {
        var lastIo: IOException? = null
        var last429: HttpReply? = null
        for (attempt in 0 until maxAttempts) {
            limiter.acquire(rpm)
            onAttempt()
            val reply = try {
                request()
            } catch (e: IOException) {
                lastIo = e
                if (attempt < maxAttempts - 1) pause(sleep, 1_500L shl attempt)
                continue
            }
            when {
                reply.code == 429 -> {
                    if (isDailyQuotaError(reply.text)) {
                        throw GeminiApiException(
                            429, "Cuota diaria de Gemini agotada: ${describe429(reply.text)}", fatal = true
                        )
                    }
                    last429 = reply
                    limiter.penalize(rpm)
                }

                reply.code in 500..599 -> {
                    if (attempt < maxAttempts - 1) pause(sleep, 2_000L shl attempt) else return reply
                }

                else -> return reply
            }
        }
        last429?.let {
            throw GeminiApiException(
                429, "Límite de frecuencia de Gemini: ${describe429(it.text)}", fatal = false
            )
        }
        throw lastIo ?: IOException("Sin respuesta de Gemini")
    }
}

/** Espera con [sleep] si se inyectó uno (tests con reloj falso) o con delay() real. */
private suspend fun pause(sleep: (suspend (Long) -> Unit)?, ms: Long) {
    if (sleep != null) sleep(ms) else delay(ms)
}
