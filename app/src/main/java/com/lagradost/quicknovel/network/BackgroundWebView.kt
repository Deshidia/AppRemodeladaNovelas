package com.lagradost.quicknovel.network

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.quicknovel.CommonActivity
import com.lagradost.quicknovel.util.Coroutines.main
import com.lagradost.quicknovel.util.Coroutines.runOnMainThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.seconds

/**
 * WebView que corre en segundo plano pero **montado en la ventana** de la app, detrás del contenido
 * y sin capturar toques: la persona no ve ni toca nada.
 *
 * Por qué no vale [WebViewResolver]: su WebView nunca se añade a la ventana. Cloudflare Turnstile (el
 * desafío que NovelUpdates pone en las fichas de serie) no se completa en un WebView desacoplado, y
 * en el log aparece como un timeout; en cuanto el WebView está en pantalla, el desafío se resuelve
 * solo y las cookies quedan en el CookieManager del sistema.
 *
 * El script recibido debe llamar a `NativeAndroid.onElementFound(texto)` con el resultado.
 */
object BackgroundWebView {
    // Una página a la vez: así se comparten cookies/sesión sin carreras
    private val mutex = Mutex()

    /** Hay una pantalla a la que montar el WebView (si no, hay que usar [WebViewResolver]). */
    fun isAvailable(): Boolean {
        val activity = CommonActivity.activity
        return activity != null && !activity.isFinishing
    }

    private val STATIC_EXTENSIONS = listOf(
        ".css", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".svg", ".ico",
        ".woff", ".woff2", ".ttf", ".otf", ".mp4", ".webm", ".mp3"
    )

    /**
     * Solo se permite NovelUpdates (menos sus recursos estáticos) y Cloudflare, que necesita para
     * el desafío Turnstile. Todo lo demás (anuncios, analítica, fuentes de terceros) se bloquea.
     */
    private fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return true
        if (host == "cloudflare.com" || host.endsWith(".cloudflare.com")) return false
        if (host != "novelupdates.com" && !host.endsWith(".novelupdates.com")) return true
        val path = uri.path?.lowercase().orEmpty()
        return STATIC_EXTENSIONS.any { path.endsWith(it) }
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private class Bridge(private val deferred: CompletableDeferred<String?>) {
        @JavascriptInterface
        fun onElementFound(result: String) {
            if (result.isNotEmpty()) deferred.complete(result)
        }
    }

    /** Carga [url], inyecta [script] en cada página y devuelve lo que éste entregue (null si vence). */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(
        url: String,
        script: String,
        timeoutSeconds: Long = 45,
        referer: String? = null
    ): String? {
        return mutex.withLock {
            val deferred = CompletableDeferred<String?>()
            var webView: WebView? = null

            main {
                val activity = CommonActivity.activity
                val host = activity?.window?.decorView as? ViewGroup
                if (activity == null || activity.isFinishing || host == null) {
                    deferred.complete(null)
                    return@main
                }
                try {
                    val view = WebView(activity).apply {
                        // Casi transparente y no interactivo; no puede ser INVISIBLE/GONE porque
                        // entonces Chromium lo trata como oculto y Turnstile no avanza
                        alpha = 0.01f
                        isClickable = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadsImagesAutomatically = false
                        settings.blockNetworkImage = true
                        // El user agent no se toca: Cloudflare se rompe si se cambia
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(Bridge(deferred), "NativeAndroid")
                        webViewClient = object : WebViewClient() {
                            // Sin publicidad ni rastreadores ni CSS/imágenes/fuentes: la ficha de
                            // NovelUpdates carga decenas de scripts de anuncios que, en un móvil
                            // modesto o un emulador, la dejan minutos sin responder
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val req = request ?: return null
                                if (req.isForMainFrame) return null
                                return if (shouldBlock(req.url)) blockedResponse() else null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(script, null)
                            }
                        }
                    }
                    // Índice 0 = por debajo de todo el contenido de la app
                    host.addView(
                        view, 0,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    webView = view
                    if (referer != null) view.loadUrl(url, mapOf("Referer" to referer))
                    else view.loadUrl(url)
                } catch (e: Exception) {
                    deferred.complete(null)
                }
            }

            val result = withTimeoutOrNull(timeoutSeconds.seconds) { deferred.await() }

            runOnMainThread {
                webView?.let { view ->
                    runCatching { view.stopLoading() }
                    runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                    runCatching { view.destroy() }
                }
                webView = null
            }
            result
        }
    }
}
