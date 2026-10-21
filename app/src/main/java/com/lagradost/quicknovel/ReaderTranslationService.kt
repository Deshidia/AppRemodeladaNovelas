package com.lagradost.quicknovel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.util.Coroutines.runOnMainThread

/**
 * Mantiene vivo el proceso mientras el lector está traduciendo un capítulo.
 *
 * La traducción en sí corre en el ViewModel del lector y no depende de la Activity, pero al salir
 * de la app Android congela el proceso y pausa la red, así que sin esto la traducción se queda
 * parada hasta volver a abrir la app (el mismo problema que ya resuelve [DownloadFileWorkManager]
 * para las descargas).
 *
 * Uso: [begin] justo antes de traducir de verdad (no en aciertos de caché), [update] con el
 * progreso y [end] al terminar, siempre en un `finally`. Es un contador: con varios capítulos a la
 * vez el servicio solo se detiene cuando acaba el último. Si Android no permite arrancar el
 * servicio (p. ej. la app ya está en segundo plano) la traducción continúa igualmente, solo que
 * sin esta protección.
 */
object ReaderTranslationKeepAlive {
    /** Espera antes de parar, para no arrancar y parar el servicio entre capítulos seguidos. */
    private const val STOP_DELAY_MS = 3_000L

    class Progress(val book: String, val chapter: String, val done: Int, val total: Int)

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Todo lo que sigue se protege con [lock]
    private var active = 0
    private var startRequested = false
    private var book = ""
    private var chapter = ""
    private var done = 0
    private var total = 0

    @Volatile
    private var service: ReaderTranslationService? = null

    private val stopIfIdle = Runnable {
        if (!isActive()) service?.shutdown()
    }

    fun isActive(): Boolean = synchronized(lock) { active > 0 }

    internal fun snapshot(): Progress = synchronized(lock) { Progress(book, chapter, done, total) }

    fun begin(bookTitle: String) {
        mainHandler.removeCallbacks(stopIfIdle)
        val shouldStart = synchronized(lock) {
            active++
            book = bookTitle
            chapter = ""
            done = 0
            total = 0
            if (startRequested) {
                false
            } else {
                startRequested = true
                true
            }
        }
        if (shouldStart) startService()
    }

    fun update(chapterTitle: String, progress: Int, progressTotal: Int) {
        synchronized(lock) {
            chapter = chapterTitle
            done = progress
            total = progressTotal
        }
        service?.refresh()
    }

    fun end() {
        val idle = synchronized(lock) {
            if (active > 0) active--
            active == 0
        }
        if (idle) mainHandler.postDelayed(stopIfIdle, STOP_DELAY_MS)
    }

    internal fun onServiceCreated(created: ReaderTranslationService) {
        service = created
    }

    internal fun onServiceDestroyed(destroyed: ReaderTranslationService, restartIfActive: Boolean) {
        if (service === destroyed) service = null
        // Cubre la carrera "se paró justo cuando empezaba otra traducción"
        val restart = synchronized(lock) {
            startRequested = restartIfActive && active > 0
            startRequested
        }
        if (restart) startService()
    }

    private fun startService() {
        val ctx = BaseApplication.context
        if (ctx == null) {
            synchronized(lock) { startRequested = false }
            return
        }
        try {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ReaderTranslationService::class.java)
            )
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException y similares: se traduce igualmente
            logError(t)
            synchronized(lock) { startRequested = false }
        }
    }
}

class ReaderTranslationService : Service() {
    companion object {
        private const val CHANNEL_ID = "quicknovel.reader.translation"
        private const val NOTIFICATION_ID = 6661

        /** Red de seguridad: si el progreso se estanca el wake lock caduca solo. Se renueva con cada avance. */
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val NOTIFY_INTERVAL_MS = 1_000L

        /**
         * Android 15+ limita los servicios dataSync a 6 h en segundo plano y, si no se paran a tiempo,
         * cierra la app. Nos paramos nosotros antes de llegar ahí (una traducción dura minutos).
         */
        private const val MAX_RUN_MS = 5 * 60 * 60 * 1000L + 30 * 60 * 1000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var enteredForeground = false
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var hitMaxRun = false

    private val maxRunReached = Runnable {
        hitMaxRun = true
        shutdown()
    }

    @Volatile
    private var stopped = false

    @Volatile
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReaderTranslationKeepAlive.onServiceCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService obliga a llamar a startForeground enseguida, aunque la
        // traducción ya haya terminado para cuando llegamos aquí
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            enteredForeground = true
        } catch (t: Throwable) {
            logError(t)
            stopped = true
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ReaderTranslationKeepAlive.isActive()) {
            shutdown()
            return START_NOT_STICKY
        }

        renewWakeLock()
        mainHandler.removeCallbacks(maxRunReached)
        mainHandler.postDelayed(maxRunReached, MAX_RUN_MS)
        // Si matan el proceso la traducción en curso se pierde igualmente, así que no se reinicia
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopped = true
        mainHandler.removeCallbacks(maxRunReached)
        releaseWakeLock()
        // Si paramos por el tope de duración no se reinicia solo: volvería a chocar con el límite
        ReaderTranslationKeepAlive.onServiceDestroyed(
            this,
            restartIfActive = enteredForeground && !hitMaxRun
        )
        super.onDestroy()
    }

    /** Pide parar el servicio (se puede llamar desde cualquier hilo). */
    fun shutdown() {
        stopped = true
        runOnMainThread {
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (t: Throwable) {
                logError(t)
            }
            stopSelf()
        }
    }

    /** Llega progreso nuevo: renueva el wake lock y, como mucho una vez por segundo, la notificación. */
    fun refresh() {
        if (stopped) return
        renewWakeLock()

        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
        lastNotifyAt = now

        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            logError(t)
        }
    }

    @Synchronized
    private fun renewWakeLock() {
        if (stopped) return
        try {
            val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickNovel:ReaderTranslation")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (t: Throwable) {
            logError(t)
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            logError(t)
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.translation_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.translation_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val progress = ReaderTranslationKeepAlive.snapshot()

        // Un intent de lanzador devuelve al usuario a la tarea ya abierta (el lector) en lugar de
        // crear otra instancia
        val pendingIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        }

        val text = buildString {
            append(progress.chapter.ifBlank { progress.book })
            if (progress.total > 0) append(" (${progress.done}/${progress.total})")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle(getString(R.string.translation_in_background))
            .setContentText(text)
            .apply {
                if (progress.chapter.isNotBlank() && progress.book.isNotBlank()) {
                    setSubText(progress.book)
                }
                if (progress.total > 0) {
                    setProgress(progress.total, progress.done, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .build()
    }
}
