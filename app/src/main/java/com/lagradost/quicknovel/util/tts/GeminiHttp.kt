package com.lagradost.quicknovel.util.tts

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Error de la API de Gemini. [fatal] = reintentar no sirve (clave inválida, región, etc.). */
class GeminiApiException(val code: Int, message: String, val fatal: Boolean) : Exception(message)

data class HttpReply(val code: Int, val body: ByteArray, val retryAfterSeconds: Long?) {
    val text: String get() = String(body, Charsets.UTF_8)
    val ok: Boolean get() = code in 200..299
}

/** OkHttp con corrutinas: cancelar la corrutina cancela la petición. */
object GeminiHttp {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS) // el audio de una frase larga tarda en generarse
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun postJson(url: String, apiKey: String, json: String): HttpReply = execute(
        Request.Builder().url(url)
            .header("x-goog-api-key", apiKey) // cabecera, no ?key=: así no acaba en los logs
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()
    )

    suspend fun get(url: String, apiKey: String): HttpReply = execute(
        Request.Builder().url(url).header("x-goog-api-key", apiKey).get().build()
    )

    private suspend fun execute(request: Request): HttpReply =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val bytes = r.body?.bytes() ?: ByteArray(0)
                        cont.resume(
                            HttpReply(r.code, bytes, r.header("Retry-After")?.trim()?.toLongOrNull())
                        )
                    }
                }
            })
        }
}
