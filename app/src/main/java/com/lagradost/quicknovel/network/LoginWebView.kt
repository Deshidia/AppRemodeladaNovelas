package com.lagradost.quicknovel.network

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import com.lagradost.quicknovel.CommonActivity
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.util.Coroutines.main
import com.lagradost.quicknovel.util.Coroutines.runOnMainThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Inicio de sesión en una web mediante un WebView en un diálogo. La app NO ve ni guarda usuario ni
 * contraseña: la persona los escribe directamente en la página (que además resuelve captchas o
 * verificaciones), y la sesión queda en las cookies del sistema, compartidas con [BackgroundWebView].
 *
 * Se cierra solo en cuanto [isDone] pasa a ser true (p. ej. aparece la cookie de sesión).
 */
object LoginWebView {
    private val mutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun login(loginUrl: String, isDone: () -> Boolean, timeoutSeconds: Long = 300): Boolean {
        return mutex.withLock {
            // Otra petición pudo completar el login mientras esperaba el turno
            if (isDone()) return@withLock true

            val cancelled = CompletableDeferred<Unit>()
            var dialog: AlertDialog? = null
            var webView: WebView? = null

            main {
                val activity = CommonActivity.activity
                if (activity == null || activity.isFinishing) {
                    cancelled.complete(Unit)
                    return@main
                }
                try {
                    val view = WebView(activity).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        // Terceros: el captcha (reCAPTCHA / Turnstile) los necesita
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // "Recordarme" marcado: sin él la sesión se pierde al cerrar la app
                                view?.evaluateJavascript(
                                    "(function(){var c=document.querySelectorAll('input[name=rememberme]');" +
                                            "for(var i=0;i<c.length;i++){c[i].checked=true;}})();",
                                    null
                                )
                            }
                        }
                    }
                    webView = view

                    val created = AlertDialog.Builder(activity)
                        .setTitle(R.string.nu_login_title)
                        .setView(view)
                        .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.complete(Unit) }
                        .setOnCancelListener { cancelled.complete(Unit) }
                        .create()
                    dialog = created
                    created.show()
                    created.window?.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (activity.resources.displayMetrics.heightPixels * 0.85).toInt()
                    )
                    view.loadUrl(loginUrl)
                } catch (e: Exception) {
                    cancelled.complete(Unit)
                }
            }

            val ok = withTimeoutOrNull(timeoutSeconds.seconds) { waitForLogin(isDone, cancelled) } == true

            runOnMainThread {
                runCatching { dialog?.dismiss() }
                webView?.let { view ->
                    runCatching { view.stopLoading() }
                    runCatching { view.destroy() }
                }
                webView = null
                dialog = null
            }
            // Que la cookie de sesión llegue a disco
            if (ok) runCatching { CookieManager.getInstance().flush() }
            ok
        }
    }

    private suspend fun waitForLogin(
        isDone: () -> Boolean,
        cancelled: CompletableDeferred<Unit>
    ): Boolean {
        while (true) {
            if (isDone()) return true
            if (cancelled.isCompleted) return false
            delay(600)
        }
    }
}
