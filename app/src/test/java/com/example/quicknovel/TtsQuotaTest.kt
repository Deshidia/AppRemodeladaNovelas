package com.example.quicknovel

import com.lagradost.quicknovel.util.tts.GeminiApiException
import com.lagradost.quicknovel.util.tts.HttpReply
import com.lagradost.quicknovel.util.tts.RpmLimiter
import com.lagradost.quicknovel.util.tts.TtsQuota
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Todo con un reloj falso: los "minutos de espera" no tardan nada en los tests. */
class TtsQuotaTest {
    private class Clock { var now = 0L }

    private val perMinute429 = """{"error":{"code":429,"message":"You exceeded your current quota.","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_requests","quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier","quotaDimensions":{"location":"global","model":"gemini-3.8-flash-tts"},"quotaValue":"3"}]}]}}"""
    private val perDay429 = """{"error":{"code":429,"message":"Quota exceeded","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaDimensions":{"model":"gemini-3.8-flash-tts"}}]}]}}"""

    private fun reply(code: Int, body: String = "") = HttpReply(code, body.toByteArray(), null)

    private class Rig(rpm: Int = 3) {
        val clock = Clock()
        val waits = ArrayList<Long>()
        val stamps = ArrayList<Long>() // instante de cada petición REAL
        var attempts = 0
        val limiter = RpmLimiter(now = { clock.now }, sleep = { clock.now += it }, onWait = { waits.add(it) })
        val rpmValue = rpm

        suspend fun send(maxAttempts: Int = 4, request: suspend (Int) -> HttpReply): HttpReply =
            TtsQuota.send(
                limiter = limiter, rpm = rpmValue, maxAttempts = maxAttempts,
                sleep = { clock.now += it }, onAttempt = { attempts++ },
            ) {
                stamps.add(clock.now)
                request(stamps.size)
            }
    }

    // ------------------------------------------------------------ límite por minuto

    @Test
    fun neverMoreThanThreeRequestsInAnySixtySecondWindow() = runBlocking {
        val rig = Rig(3)
        repeat(12) { rig.send { reply(200) } }
        assertEquals(12, rig.stamps.size)
        for (i in 3 until rig.stamps.size) {
            assertTrue("peticiones ${i - 3}..$i en ${rig.stamps[i] - rig.stamps[i - 3]} ms",
                rig.stamps[i] - rig.stamps[i - 3] >= 60_000)
        }
    }

    @Test
    fun concurrentRequestsStillRespectTheLimit() = runBlocking {
        val rig = Rig(3)
        (1..10).map { async { rig.send { reply(200) } } }.awaitAll()
        val s = rig.stamps.sorted()
        for (i in 3 until s.size) assertTrue("ventana $i: ${s[i] - s[i - 3]} ms", s[i] - s[i - 3] >= 60_000)
    }

    @Test
    fun theFirstThreeGoOutImmediately() = runBlocking {
        val rig = Rig(3)
        repeat(3) { rig.send { reply(200) } }
        assertEquals(0L, rig.clock.now)
        assertTrue(rig.waits.isEmpty())
    }

    @Test
    fun theFourthRequestAnnouncesHowLongItWaits() = runBlocking {
        val rig = Rig(3)
        repeat(4) { rig.send { reply(200) } }
        assertEquals(1, rig.waits.size)
        assertTrue(rig.waits[0] in 60_000L..62_000L)
    }

    @Test
    fun zeroRpmMeansNoWaiting() = runBlocking {
        val rig = Rig(0)
        repeat(20) { rig.send { reply(200) } }
        assertEquals(0L, rig.clock.now)
    }

    // ------------------------------------------------------------ 429

    @Test
    fun aRateLimit429MakesTheNextAttemptWaitAWholeMinuteEvenIfWeThoughtWeHadRoom() = runBlocking {
        val rig = Rig(3)
        val r = rig.send { n -> if (n == 1) reply(429, perMinute429) else reply(200) }
        assertEquals(200, r.code)
        assertEquals(2, rig.attempts)
        assertTrue("esperó ${rig.stamps[1] - rig.stamps[0]} ms", rig.stamps[1] - rig.stamps[0] >= 60_000)
    }

    @Test
    fun aDailyQuota429FailsImmediatelyAndNeverRetries() {
        val rig = Rig(3)
        val e = runCatching { runBlocking { rig.send { reply(429, perDay429) } } }.exceptionOrNull()
        assertTrue(e is GeminiApiException)
        e as GeminiApiException
        assertTrue(e.fatal)
        assertEquals(1, rig.attempts)
        assertTrue(e.message!!.contains("PerDay"))
        assertEquals(0L, rig.clock.now) // ni un segundo de espera inútil
    }

    @Test
    fun persistentRateLimitGivesUpAfterTheAttemptsWithAClearMessage() {
        val rig = Rig(3)
        val e = runCatching { runBlocking { rig.send(maxAttempts = 3) { reply(429, perMinute429) } } }.exceptionOrNull()
        e as GeminiApiException
        assertFalse(e.fatal)
        assertEquals(3, rig.attempts) // no se insiste más: cada intento gasta cuota
        assertTrue(e.message!!.contains("PerMinute") && e.message!!.contains("gemini-3.8-flash-tts"))
    }

    @Test
    fun theWaitAfterA429AppliesToEveryoneNotJustTheCaller() = runBlocking {
        val rig = Rig(3)
        rig.send { n -> if (n == 1) reply(429, perMinute429) else reply(200) } // aprende del 429
        val before = rig.clock.now
        rig.send { reply(200) } // otro llamador distinto (p. ej. el identificador de hablantes)
        assertTrue(rig.clock.now >= before) // no puede colarse antes del hueco
        for (i in 3 until rig.stamps.size) assertTrue(rig.stamps[i] - rig.stamps[i - 3] >= 60_000)
    }

    // ------------------------------------------------------------ otros fallos

    @Test
    fun aServerErrorRetriesQuicklyWithoutBlockingAWholeMinute() = runBlocking {
        val rig = Rig(3)
        val r = rig.send { n -> if (n == 1) reply(503) else reply(200) }
        assertEquals(200, r.code)
        assertEquals(2, rig.attempts)
        assertTrue(rig.stamps[1] - rig.stamps[0] < 5_000)
    }

    @Test
    fun aNetworkErrorIsRetriedAndEveryAttemptCounts() = runBlocking {
        val rig = Rig(3)
        val r = rig.send { n -> if (n == 1) throw IOException("sin red") else reply(200) }
        assertEquals(200, r.code)
        assertEquals(2, rig.attempts)
    }

    @Test
    fun badRequestsAreReturnedWithoutRetrying() = runBlocking {
        val rig = Rig(3)
        assertEquals(400, rig.send { reply(400, "{}") }.code)
        assertEquals(403, rig.send { reply(403, "{}") }.code)
        assertEquals(2, rig.attempts)
    }

    // ------------------------------------------------------------ penalize / descripción

    @Test
    fun penalizeFillsTheWindow() = runBlocking {
        var now = 0L
        val waits = ArrayList<Long>()
        val l = RpmLimiter(now = { now }, sleep = { now += it }, onWait = { waits.add(it) })
        l.acquire(3)
        l.penalize(3)
        l.acquire(3)
        assertTrue(now >= 60_000)
        assertEquals(1, waits.size)
    }

    @Test
    fun the429DescriptionSaysWhichQuotaAndModel() {
        assertEquals(
            "GenerateRequestsPerMinutePerProjectPerModel-FreeTier (gemini-3.8-flash-tts)",
            TtsQuota.describe429(perMinute429)
        )
        assertEquals("HTTP 429", TtsQuota.describe429(""))
    }

    @Test
    fun onlyDailyQuotasAreTreatedAsDaily() {
        assertTrue(TtsQuota.isDailyQuotaError(perDay429))
        assertFalse(TtsQuota.isDailyQuotaError(perMinute429))
        assertFalse(TtsQuota.isDailyQuotaError("""{"error":{"message":"input tokens per minute"}}"""))
    }
}
