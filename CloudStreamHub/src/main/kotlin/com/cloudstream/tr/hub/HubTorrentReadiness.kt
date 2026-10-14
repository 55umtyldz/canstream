package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class TorrentProbeResponse(val code: Int, val range: String?, val bytes: ByteArray)
internal fun interface TorrentReadinessTransport {
    suspend fun get(url: String, range: String?, timeoutMs: Long, maxBytes: Int): TorrentProbeResponse
}

private val readinessClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).build()

/** Never follows redirects; cancellation remains registered while the bounded body is read. */
internal object LocalTorrentTransport : TorrentReadinessTransport {
    override suspend fun get(url: String, range: String?, timeoutMs: Long, maxBytes: Int): TorrentProbeResponse {
        require(isLocalTorrentStream(url))
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
        range?.let { request.header("Range", it) }
        val call = readinessClient.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(request.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val input = it.body?.byteStream() ?: throw IOException("Empty response")
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                if (output.size() > maxBytes) throw IOException("Response too large")
                            }
                            val bytes = output.toByteArray()
                            TorrentProbeResponse(it.code, it.header("Content-Range"), bytes)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}

internal fun torrentPreparationUrl(url: String, preload: Boolean): String {
    if (!isLocalTorrentStream(url)) throw ErrorLoadingException("Geçersiz yerel torrent bağlantısı.")
    val parsed = url.toHttpUrl()
    if (!parsed.encodedPath.startsWith("/stream/") ||
        parsed.queryParameterValues("index").size != 1 ||
        parsed.queryParameter("index")?.toIntOrNull()?.let { it >= 0 } != true ||
        parsed.queryParameterValues("link").size != 1 || parsed.queryParameter("link").isNullOrBlank()) {
        throw ErrorLoadingException("Torrent dosyası ve indeksi doğrulanamadı.")
    }
    val builder = parsed.newBuilder().fragment(null)
    listOf("play", "preload", "stat", "m3u", "save", "fromlast").forEach { builder.removeAllQueryParameters(it) }
    if (preload) builder.addQueryParameter("preload", "").addQueryParameter("stat", "")
    else builder.addQueryParameter("play", "")
    return builder.build().toString()
}

internal fun validateTorrentRange(response: TorrentProbeResponse, start: Long, end: Long, total: Long? = null): Long {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.range.orEmpty())
        ?: throw ErrorLoadingException("Torrent veri aralığı doğrulanamadı.")
    val values = match.groupValues.drop(1).map { it.toLongOrNull() }
    val size = values[2] ?: throw ErrorLoadingException("Torrent dosya boyutu doğrulanamadı.")
    val actualEnd = minOf(end, size - 1)
    if (response.code != 206 || size <= 0 || values[0] != start || values[1] != actualEnd ||
        (total != null && total != size) || response.bytes.size.toLong() != actualEnd - start + 1) {
        throw ErrorLoadingException("Torrent başlangıç/son verisi eksik veya geçersiz.")
    }
    return size
}

internal suspend fun awaitTorrentReadiness(
    url: String,
    transport: TorrentReadinessTransport = LocalTorrentTransport,
    budgetMs: Long = 90_000,
    diagnostic: (String) -> Unit = { android.util.Log.i("CloudStreamHub", "Torrent hazırlık: $it") }
) {
    val preloadUrl = torrentPreparationUrl(url, true)
    val playUrl = torrentPreparationUrl(url, false)
    val ready = withTimeoutOrNull(budgetMs) {
        diagnostic("ön yükleme")
        try {
            val result = transport.get(preloadUrl, null, minOf(60_000, budgetMs), 1_048_576)
            diagnostic("ön yükleme HTTP ${result.code}")
            if (result.code != 200 || result.bytes.firstOrNull()?.toInt() != '{'.code) {
                throw ErrorLoadingException("Torrent sunucusu ön yüklemeyi kabul etmedi.")
            }
        } catch (e: IOException) {
            // A timed out client does not stop the host's synchronous preload. Verify real bytes.
            diagnostic("ön yükleme yanıtı beklenemedi; veri kontrolü")
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                diagnostic("başlangıç verisi")
                val head = transport.get(playUrl, "bytes=0-65535", 15_000, 65_536)
                diagnostic("başlangıç HTTP ${head.code}")
                val size = validateTorrentRange(head, 0, 65_535)
                diagnostic("son veri")
                val start = maxOf(0, size - 65_536)
                val tail = transport.get(playUrl, "bytes=$start-${size - 1}", 15_000, 65_536)
                diagnostic("son HTTP ${tail.code}")
                validateTorrentRange(tail, start, size - 1, size)
                diagnostic("hazır")
                return@withTimeoutOrNull true
            } catch (e: IOException) {
                diagnostic("parçalar bekleniyor")
                delay(1_000)
            } catch (e: CancellationException) {
                throw e
            }
        }
        @Suppress("UNREACHABLE_CODE") false
    }
    currentCoroutineContext().ensureActive()
    if (ready != true) {
        diagnostic("süre doldu")
        throw ErrorLoadingException("Torrentin başlangıç ve son parçaları 90 saniyede hazırlanamadı. Başka bir kaynak deneyiniz.")
    }
}
