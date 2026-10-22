package app.daiyu.android

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun fetchVoiceResource(url: String, calls: Call.Factory): ByteArray {
    val uri = URI(url)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null)
    // This public resource request carries no provider headers, keys or synthesis text.
    val call = calls.newCall(Request.Builder().url(url).get().build())
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("语音资源读取失败"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        check(it.isSuccessful)
                        val body = checkNotNull(it.body)
                        require(body.contentLength() <= MAX_DEMO_BYTES)
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(continuation.isActive)
                                require(output.size() + count <= MAX_DEMO_BYTES)
                                output.write(buffer, 0, count)
                            }
                        }
                        output.toByteArray().also { data -> require(data.isNotEmpty()) }
                    }
                    if (continuation.isActive) continuation.resume(bytes) { _, abandoned, _ -> abandoned.fill(0) }
                    else bytes.fill(0)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("语音资源读取失败"))
                }
            }
        })
    }
}

private const val MAX_DEMO_BYTES = 8 * 1024 * 1024
