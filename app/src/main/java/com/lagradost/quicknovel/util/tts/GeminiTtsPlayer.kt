package com.lagradost.quicknovel.util.tts

import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.TTS_BATCHING
import com.lagradost.quicknovel.TTS_CHAPTER_MODE
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.safefile.SafeFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** Las dos peticiones que hace el reproductor. Se puede sustituir (tests) sin tocar la red. */
interface TtsBackend {
    suspend fun single(apiKey: String, model: String, voice: String, style: String, text: String): ByteArray

    /** [paddedSingle]: una sola voz con la forma de dos hablantes (plan B de la API). */
    suspend fun batch(
        apiKey: String,
        model: String,
        turns: List<GeminiSpeechClient.BatchTurn>,
        paddedSingle: Boolean = false,
    ): ByteArray
}

object GeminiBackend : TtsBackend {
    override suspend fun single(apiKey: String, model: String, voice: String, style: String, text: String) =
        GeminiSpeechClient.synthesize(apiKey, model, voice, style, text)

    override suspend fun batch(
        apiKey: String,
        model: String,
        turns: List<GeminiSpeechClient.BatchTurn>,
        paddedSingle: Boolean,
    ) = GeminiSpeechClient.synthesizeBatch(apiKey, model, turns, paddedSingle)
}

/**
 * Motor de voz de Gemini con la misma forma de uso que el TTS del sistema: el lector llama a
 * [speak] frase a frase y espera con [waitForOr]. Por dentro hay tres formas de pedir el audio:
 *  - **Capítulo por actores** (por defecto, pensado para cuotas pequeñas): al empezar el capítulo
 *    se juntan TODAS las frases de cada voz en pocas peticiones enormes y el audio se recoloca en
 *    el orden del texto. El capítulo se prepara entero antes de sonar.
 *  - **Ventanas**: tandas de frases vecinas (hasta 2 voces); empieza a sonar antes pero gasta más.
 *  - **Sueltas**: una petición por frase (el plan B si el agrupado no funciona).
 * El silencio entre frases (ritmo + pausas cortas/largas) lo añade la app, no el modelo.
 */
class GeminiTtsPlayer(
    /** cacheDir/gemini_tts (ver [TtsAudioCache.DIR]). */
    cacheRoot: File,
    private val novel: String,
    private val apiKey: String,
    private val model: String,
    private val backend: TtsBackend = GeminiBackend,
    batching: Boolean = getKey<Boolean>(TTS_BATCHING) ?: true,
    private val chapterMode: Boolean = getKey<Boolean>(TTS_CHAPTER_MODE) ?: true,
) {
    private companion object {
        const val PREFETCH = 3
        const val PARALLEL_REQUESTS = 2
        const val MAX_CONSECUTIVE_FAILURES = 3

        // Modo ventanas: solo se piden frases nuevas cuando lo ya cubierto por delante baja de
        // LOW_WATER caracteres y entonces se pide hasta HIGH_WATER de una vez. Sin esta histéresis
        // cada frase que se reproduce añadiría una petición suelta y no se ahorraría nada.
        const val LOW_WATER_CHARS = 700
        const val HIGH_WATER_CHARS = 1800

        // dos tandas seguidas sin silencios de turno limpios: el modelo no colabora, se deja de agrupar
        const val MAX_REJECTED_BATCHES = 2

        // modo capítulo: tras este nº de peticiones grandes fallidas se deja de intentar (cuota escasa)
        const val MAX_PACK_FAILURES = 2
    }

    private val cache = TtsAudioCache(cacheRoot)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limiter = Semaphore(PARALLEL_REQUESTS)

    private val lock = Any()
    private val inflight = HashMap<String, Deferred<File?>>()

    @Volatile
    private var chapter: List<TTSHelper.TTSLine> = emptyList()

    @Volatile
    private var speed = 1f

    @Volatile
    private var endedId = 0
    private var nextId = 0
    private var playJob: Job? = null

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var fatal = false

    // --- agrupar frases (ahorro de peticiones) ---
    @Volatile
    private var batching = batching

    // capacidades que se descubren al usar la API: si rechaza una forma de petición no se insiste
    @Volatile
    private var twoVoicesOk = true

    /** Forma de pedir varios turnos de UNA voz: 0 = speech_config en array, 1 = con dos hablantes, 2 = no se puede. */
    @Volatile
    private var singleVoiceForm = 0

    @Volatile
    private var rejectedBatches = 0

    // Tanda de prueba: hasta que una tanda de varias frases se corte bien, solo se lanza UNA cada vez.
    // Si la API/el modelo no las admite, se pierde 1-2 peticiones y no las 5-6 de toda la ventana.
    @Volatile
    private var proven = false
    private val probing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Capítulo (lista de líneas) ya planificado en modo capítulo; se compara por identidad. */
    @Volatile
    private var plannedChapter: List<TTSHelper.TTSLine>? = null

    private val isPrebuilt: (String) -> Boolean = { id -> GeminiVoices.prebuilt.any { it.id == id } }

    /** false tras un error que no tiene arreglo (clave mala) o varios fallos seguidos: el lector se para. */
    fun isReady(): Boolean = apiKey.isNotBlank() && !fatal

    fun setSpeed(value: Float) {
        speed = value
    }

    fun setChapter(lines: List<TTSHelper.TTSLine>) {
        chapter = lines
    }

    /** Una frase lista para pedir: su voz ya resuelta y la clave de caché. */
    private class Resolved(
        val line: TTSHelper.TTSLine,
        val vl: VoiceLine,
        val voice: String,
        val key: String,
        val weight: Int,
    )

    private fun resolve(line: TTSHelper.TTSLine): Resolved? {
        val vl = line.voice ?: return null
        val voice = TtsActors.voiceFor(novel, vl.speaker)
        return Resolved(
            line, vl, voice, cache.key(model, voice, vl.style, vl.text), BatchPlanner.weightOf(vl.text)
        )
    }

    private fun isCached(key: String): Boolean =
        cache.file(novel, key).let { it.isFile && it.length() > 0 }

    private fun isCoveredOrInflight(key: String): Boolean =
        synchronized(lock) { inflight.containsKey(key) } || isCached(key)

    private fun register(key: String, job: Deferred<File?>) {
        inflight[key] = job
        // un fallo no se queda "memorizado": al reintentar se vuelve a pedir
        job.invokeOnCompletion { synchronized(lock) { if (inflight[key] === job) inflight.remove(key) } }
    }

    /** Una petición para una sola frase (o la lee de caché). Es el camino de siempre y el plan B de las tandas. */
    private suspend fun singleFile(r: Resolved): File? {
        try {
            val existing = cache.file(novel, r.key)
            if (existing.isFile && existing.length() > 0) return existing
            if (fatal || quotaExhausted()) return null // parar: no se gasta cuota a ciegas
            val pcm = limiter.withPermit {
                backend.single(apiKey, model, r.voice, r.vl.style, r.vl.text)
            }
            consecutiveFailures = 0
            return cache.write(novel, r.key, pcm)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiApiException) {
            onFailure(e.message, e.fatal)
        } catch (t: Throwable) {
            logError(t)
            onFailure(t.message, false)
        }
        return null
    }

    private fun synth(line: TTSHelper.TTSLine): Deferred<File?> {
        val r = resolve(line) ?: return CompletableDeferred(null)
        synchronized(lock) {
            inflight[r.key]?.let { return it }
            val job = scope.async { singleFile(r) }
            register(r.key, job)
            return job
        }
    }

    private fun onFailure(message: String?, isFatal: Boolean) {
        consecutiveFailures++
        if (isFatal || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) fatal = true
        if (isFatal || consecutiveFailures == 1 || fatal) showToast(message ?: "Gemini TTS")
    }

    // ---------------------------------------------------------------- tandas

    /** Lanza una tanda: sus frases quedan "en vuelo" y se completan cuando llega el audio cortado. */
    private fun launchBatch(batch: Batch, byIndex: Map<Int, Resolved>): Boolean {
        val wanted = batch.lines.map { byIndex.getValue(it.index) }
        val deferreds = ArrayList<CompletableDeferred<File?>>()
        val rs = ArrayList<Resolved>()
        synchronized(lock) {
            for (r in wanted) {
                if (inflight.containsKey(r.key)) continue // otra petición ya se ocupa de esta frase
                val d = CompletableDeferred<File?>()
                register(r.key, d)
                rs.add(r); deferreds.add(d)
            }
        }
        if (rs.isEmpty()) return false
        if (rs.size == 1) {
            val only = rs[0]; val d = deferreds[0]
            scope.launch { d.complete(singleFile(only)) }
            return false
        }
        scope.launch { runBatch(rs, deferreds) }
        return true
    }

    /** Lanza las tandas; sin agrupado probado, solo la primera de varias frases (la de prueba). */
    private fun launchPlan(batches: List<Batch>, byIndex: Map<Int, Resolved>) {
        for (b in batches) {
            if (proven || b.lines.size == 1) {
                launchBatch(b, byIndex)
                continue
            }
            if (!probing.compareAndSet(false, true)) return // ya hay una prueba en marcha
            if (!launchBatch(b, byIndex)) probing.set(false)
            return
        }
    }

    /** Planifica y lanza las frases sin cubrir desde [from], hasta [maxChars] de texto. */
    private fun planAndLaunch(lines: List<TTSHelper.TTSLine>, from: Int, maxChars: Int, smallFirstIfWaiting: Int) {
        val byIndex = HashMap<Int, Resolved>()
        val plan = ArrayList<PlanLine>()
        var chars = 0
        for (k in from..lines.lastIndex) {
            val r = resolve(lines[k]) ?: continue
            if (chars >= maxChars) break
            chars += r.weight
            if (isCoveredOrInflight(r.key)) continue
            byIndex[k] = r
            plan.add(PlanLine(k, r.voice, r.vl.style, r.weight))
        }
        if (plan.isEmpty()) return
        // la primera tanda es corta solo si el audio está esperando a la frase que suena ahora
        val waiting = plan[0].index == smallFirstIfWaiting
        launchPlan(
            BatchPlanner.plan(plan, isPrebuilt, twoVoicesOk, singleVoiceForm < 2, smallFirst = waiting),
            byIndex
        )
    }

    /**
     * UNA petición con varios turnos y su corte en frases. Devuelve el audio de cada frase, o null si
     * no se pudo (rechazo de la API, cuota, o el audio no se deja cortar limpiamente).
     */
    private suspend fun requestPack(rs: List<Resolved>): List<ByteArray>? {
        val voices = rs.map { it.voice }.distinct()
        val weights = rs.map { it.weight }
        val turns = rs.map { GeminiSpeechClient.BatchTurn(it.voice, it.vl.style, it.vl.text) }
        while (true) {
            if (quotaExhausted()) return null
            val padded = voices.size == 1 && singleVoiceForm == 1
            try {
                val pcm = limiter.withPermit { backend.batch(apiKey, model, turns, padded) }
                val split = SilenceSplitter.splitDetailed(pcm, weights)
                if (split != null && split.slices.size == rs.size) {
                    proven = true
                    rejectedBatches = 0
                    consecutiveFailures = 0
                    return split.slices
                }
                onBatchRejected()
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiApiException) {
                if (e.fatal) {
                    onFailure(e.message, true)
                    return null
                }
                // 400: la API no acepta esta forma de petición; no se vuelve a intentar con ella
                if (voices.size > 1) {
                    twoVoicesOk = false
                    return null
                }
                val canPad = singleVoiceForm == 0 && isPrebuilt(voices[0])
                singleVoiceForm = if (canPad) 1 else 2
                if (!canPad) return null
                // se reintenta una vez con la otra forma de pedir una sola voz
            } catch (t: Throwable) {
                logError(t)
                return null
            }
        }
    }

    /** Modo ventanas: si la tanda no sale, se rehace frase a frase. */
    private suspend fun runBatch(rs: List<Resolved>, deferreds: List<CompletableDeferred<File?>>) {
        try {
            val slices = requestPack(rs)
            if (slices != null) {
                rs.forEachIndexed { i, r -> deferreds[i].complete(cache.write(novel, r.key, slices[i])) }
            } else {
                fallbackToSingles(rs, deferreds)
            }
        } catch (e: CancellationException) {
            deferreds.forEach { it.cancel() }
            throw e
        } finally {
            probing.set(false)
            deferreds.forEach { it.complete(null) } // nadie se queda esperando si algo falla
        }
    }

    // ------------------------------------------------------- modo capítulo por actores

    private fun uncovered(lines: List<TTSHelper.TTSLine>): Pair<List<PlanLine>, Map<Int, Resolved>> {
        val byIndex = HashMap<Int, Resolved>()
        val plan = ArrayList<PlanLine>()
        lines.forEachIndexed { k, line ->
            val r = resolve(line) ?: return@forEachIndexed
            if (isCoveredOrInflight(r.key)) return@forEachIndexed
            byIndex[k] = r
            plan.add(PlanLine(k, r.voice, r.vl.style, r.weight))
        }
        return plan to byIndex
    }

    /** Peticiones que hará falta gastar para tener el audio de todo el capítulo (lo ya generado no cuenta). */
    fun estimateRequests(): Int {
        val (plan, _) = uncovered(chapter)
        if (plan.isEmpty()) return 0
        if (!batching) return plan.size
        return if (chapterMode) RolePacker.pack(plan, isPrebuilt, twoVoicesOk).size
        else BatchPlanner.plan(plan, isPrebuilt, twoVoicesOk, singleVoiceForm < 2, smallFirst = false).size
    }

    /** ¿Quedan [n] peticiones de cuota hoy (o no hay límite configurado)? */
    private fun affordable(n: Int): Boolean = TtsQuota.remainingToday(model)?.let { it >= n } ?: true

    /** Sin cuota para ni una petición más: se avisa y el lector se detiene (no se insiste a ciegas). */
    private fun quotaExhausted(): Boolean {
        if (affordable(1)) return false
        if (!fatal) {
            fatal = true
            showToast(R.string.tts_quota_exhausted)
        }
        return true
    }

    /** Registra todas las frases sin audio del capítulo y las genera en segundo plano. */
    private fun planChapter(lines: List<TTSHelper.TTSLine>) {
        if (plannedChapter === lines) return
        plannedChapter = lines
        val (plan, byIndex) = uncovered(lines)
        if (plan.isEmpty()) return

        val pending = LinkedHashMap<Int, Pair<Resolved, CompletableDeferred<File?>>>()
        synchronized(lock) {
            for (pl in plan) {
                val r = byIndex.getValue(pl.index)
                if (inflight.containsKey(r.key)) continue
                val d = CompletableDeferred<File?>()
                register(r.key, d)
                pending[pl.index] = r to d
            }
        }
        if (pending.isEmpty()) return
        showToast(R.string.tts_generating_chapter)
        scope.launch { runChapter(pending) }
    }

    /**
     * Rondas: se empaqueta por actores lo que falta, se pide (la primera ronda, UNA sola petición de
     * prueba) y, si algo falla, se reduce el tamaño y se reempaqueta solo lo pendiente. Tras
     * [MAX_PACK_FAILURES] fallos se para: con una cuota de 10 peticiones al día no se puede apostar.
     */
    private suspend fun runChapter(pending: LinkedHashMap<Int, Pair<Resolved, CompletableDeferred<File?>>>) {
        try {
            var cap = RolePacker.CAP_SECONDS
            var failures = 0
            while (pending.isNotEmpty() && batching && !fatal && failures < MAX_PACK_FAILURES) {
                if (TtsQuota.remainingToday(model) == 0) {
                    showToast(R.string.tts_quota_exhausted)
                    break
                }
                val plan = pending.map { (k, v) -> PlanLine(k, v.first.voice, v.first.vl.style, v.first.weight) }
                val packs = RolePacker.pack(plan, isPrebuilt, twoVoicesOk, cap)
                val round = if (proven) packs else listOf(packs.first())

                val results = coroutineScope {
                    round.map { b ->
                        async { b to requestPack(b.lines.map { pending.getValue(it.index).first }) }
                    }.awaitAll()
                }
                var failed = false
                for ((b, slices) in results) {
                    if (slices == null) {
                        failed = true
                        continue
                    }
                    b.lines.forEachIndexed { i, pl ->
                        val (r, d) = pending.remove(pl.index)!!
                        d.complete(cache.write(novel, r.key, slices[i]))
                    }
                }
                if (failed) {
                    failures++
                    cap /= 2
                }
            }
            resolveLeftovers(pending.values.toList())
        } catch (e: CancellationException) {
            pending.values.forEach { it.second.cancel() }
            throw e
        } finally {
            pending.values.forEach { it.second.complete(null) }
        }
    }

    /** Lo que no se pudo agrupar: frase a frase solo si la cuota da de sobra; si no, se omite. */
    private suspend fun resolveLeftovers(left: List<Pair<Resolved, CompletableDeferred<File?>>>) {
        if (left.isEmpty()) return
        if (!affordable(left.size)) {
            // no hay cuota para rehacerlo frase a frase: se avisa y se detiene la lectura
            showToast(R.string.tts_quota_skipped)
            fatal = true
            left.forEach { it.second.complete(null) }
            return
        }
        for ((r, d) in left) d.complete(if (fatal) null else singleFile(r))
    }

    private suspend fun fallbackToSingles(rs: List<Resolved>, deferreds: List<CompletableDeferred<File?>>) {
        try {
            rs.forEachIndexed { i, r -> deferreds[i].complete(singleFile(r)) }
        } finally {
            deferreds.forEach { it.complete(null) } // nadie se queda esperando si algo falla
        }
    }

    private fun onBatchRejected() {
        rejectedBatches++
        if (rejectedBatches >= MAX_REJECTED_BATCHES && batching) {
            batching = false
            showToast(R.string.tts_batching_off)
        }
    }

    /**
     * Pide por adelantado lo que viene. Sin agrupar: las 3 siguientes. Agrupando: solo cuando lo
     * cubierto baja del mínimo, y entonces hasta el máximo en tandas de hasta 2 voces.
     */
    private fun prefetch(from: TTSHelper.TTSLine) {
        val lines = chapter
        val i = lines.indexOfFirst { it === from || (it.startChar == from.startChar && it.index == from.index) }
        if (i < 0) return
        if (!batching) {
            for (k in i..minOf(i + PREFETCH, lines.lastIndex)) synth(lines[k])
            return
        }
        if (chapterMode) {
            planChapter(lines) // todo el capítulo de una vez, por actores
            return
        }

        // 1) cuánto hay ya cubierto por delante
        var covered = 0
        var firstUncovered = -1
        for (k in i..lines.lastIndex) {
            val r = resolve(lines[k]) ?: continue
            if (!isCoveredOrInflight(r.key)) { firstUncovered = k; break }
            covered += r.weight
            if (covered >= LOW_WATER_CHARS) return // de sobra: no se pide nada
        }
        if (firstUncovered < 0) return

        // 2) se pide en bloque hasta el máximo
        planAndLaunch(lines, firstUncovered, HIGH_WATER_CHARS, smallFirstIfWaiting = i)
    }

    /** Pide por adelantado lo que viene a partir de [line] (lo hace [speak]; público para probarlo). */
    fun warmUp(line: TTSHelper.TTSLine) = prefetch(line)

    /** Audio (PCM en caché) de una frase; null si no se pudo generar. */
    suspend fun audioFor(line: TTSHelper.TTSLine): File? = synth(line).await()

    suspend fun speak(line: TTSHelper.TTSLine, next: TTSHelper.TTSLine?): Int? {
        if (!isReady()) return null
        playJob?.cancelAndJoin() // nunca dos frases a la vez
        val id = ++nextId
        prefetch(line)
        if (!batching) next?.let { synth(it) } // con tandas ya está cubierta por prefetch
        playJob = scope.launch {
            try {
                // el silencio previo corre mientras se espera al audio: cuenta desde que acabó la frase anterior
                val gap = launch { delay(((line.voice?.gapBeforeMs ?: 0) / speed).toLong()) }
                val file = synth(line).await()
                gap.join()
                val pcm = file?.readBytes()
                if (pcm != null) playPcm(pcm, speed)
            } finally {
                endedId = maxOf(endedId, id)
            }
        }
        return id
    }

    /** Espera a que termine la frase [id]; si [action] pasa a true corta el audio y avisa con [then]. */
    suspend fun waitForOr(id: Int?, action: () -> Boolean, then: () -> Unit) {
        if (id == null) return
        while (id > endedId) {
            delay(50)
            if (action()) {
                interrupt()
                then()
                break
            }
        }
    }

    fun interrupt() {
        playJob?.cancel()
    }

    fun release() {
        scope.cancel()
        synchronized(lock) { inflight.clear() }
    }

    /**
     * Guarda el capítulo entero como WAV en <ruta de descarga>/TTS/<novela>/<fileBase>.wav (más el
     * guion .txt al lado). Genera lo que falte (frases que se saltó el usuario). Devuelve false si
     * alguna frase no se pudo generar o no se pudo escribir.
     */
    suspend fun exportChapter(
        base: SafeFile,
        fileBase: String,
        lines: List<TTSHelper.TTSLine>,
        script: String,
    ): Boolean {
        if (batching && chapterMode) planChapter(lines) // no hace nada si ya está planificado
        val parts = ArrayList<PcmPart>()
        for ((k, line) in lines.withIndex()) {
            val vl = line.voice ?: continue
            val r = resolve(line) ?: continue
            if (batching && !chapterMode && !isCoveredOrInflight(r.key)) {
                // el capítulo entero de una vez (no hay prisa por oírlo): tandas lo más grandes posible
                while (probing.get()) delay(50) // espera a que se pruebe el agrupado
                if (!isCoveredOrInflight(r.key)) planAndLaunch(lines, k, Int.MAX_VALUE, -1)
            }
            val file = synth(line).await() ?: return false
            parts.add(PcmPart.Silence(vl.gapBeforeMs))
            parts.add(PcmPart.FromFile(file))
        }
        if (parts.isEmpty()) return false

        val dir = base.gotoDirectory("TTS")?.gotoDirectory(TtsAudioCache.safeName(novel))
            ?: return false
        val name = TtsAudioCache.safeName(fileBase, "capitulo")

        fun write(fileName: String, writer: (java.io.OutputStream) -> Unit): Boolean {
            dir.findFile(fileName)?.delete()
            val out = dir.createFile(fileName)?.openOutputStream(append = false) ?: return false
            out.use(writer)
            return true
        }
        return try {
            write("$name.wav") { WavWriter.write(it, parts) } &&
                    write("$name.txt") { it.write(script.toByteArray(Charsets.UTF_8)) }
        } catch (t: Throwable) {
            logError(t)
            false
        }
    }

    /** Frase de prueba con la voz de un actor (para elegir voz sin tener que abrir el lector). */
    suspend fun preview(voice: String, text: String, style: String = "") {
        val pcm = backend.single(apiKey, model, voice, style, text)
        playPcm(pcm, 1f)
    }
}
