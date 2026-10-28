package app.daiyu.android.terminal.install

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Downloads only the selected catalog mirror and its bounded HTTPS redirects. GitHub releases may
 * redirect to their fixed asset CDN. Size and hash remain pinned to the catalog; no mirror fallback.
 * Cancellation uses HttpURLConnection.disconnect + closing the live stream (not a polling-only
 * coroutine). Call on a parent IO worker, never on the UI thread.
 */
class VerifiedArtifactDownloader internal constructor(
    private val connect: (URL) -> HttpURLConnection
) : LinuxArtifactDownload {
    constructor() : this({ it.openConnection() as HttpURLConnection })

    override fun download(artifact: LinuxRootfsArtifact, mirror: RootfsMirror, destination: File,
                          cancellation: LinuxInstallCancellation) {
        require(artifact.mirrors.contains(mirror)) { "linux_install_unknown_mirror" }
        cancellation.check()
        val activeConnection = AtomicReference<HttpURLConnection?>()
        val input = AtomicReference<InputStream?>()
        var created = false
        var complete = false
        val subscription = cancellation.register {
            // Some providers unblock read on close, others on disconnect; always attempt both.
            runCatching { activeConnection.get()?.disconnect() }
            runCatching { input.getAndSet(null)?.close() }
        }
        try {
            cancellation.check()
            var uri = mirror.uri
            var redirects = 0
            val connection: HttpURLConnection
            while (true) {
                cancellation.check()
                val current = connect(uri.toURL())
                activeConnection.set(current)
                cancellation.check()
                current.instanceFollowRedirects = false
                current.connectTimeout = 30_000
                current.readTimeout = 30_000
                current.requestMethod = "GET"
                current.setRequestProperty("Accept-Encoding", "identity")
                val status = current.responseCode
                cancellation.check()
                if (status == HttpURLConnection.HTTP_OK) {
                    connection = current
                    break
                }
                check(status in setOf(301, 302, 303, 307, 308)) { "linux_download_http_$status" }
                check(redirects++ < 5) { "linux_download_redirect_limit" }
                val location = current.getHeaderField("Location")
                check(!location.isNullOrBlank() && location.length <= 8192) { "linux_download_redirect_location" }
                val next = try { uri.resolve(URI(location)) }
                    catch (_: IllegalArgumentException) { error("linux_download_redirect_location") }
                    catch (_: java.net.URISyntaxException) { error("linux_download_redirect_location") }
                check(redirectAllowed(mirror.uri, next)) { "linux_download_redirect_origin" }
                current.disconnect()
                activeConnection.compareAndSet(current, null)
                uri = next
            }
            val declared = connection.contentLengthLong
            check(declared == -1L || declared == artifact.compressedBytes) { "linux_download_size" }
            val encoding = connection.contentEncoding
            check(encoding == null || encoding.equals("identity", true)) { "linux_download_encoding" }
            cancellation.check()
            val body = connection.inputStream
            input.set(body)
            cancellation.check()
            val digest = MessageDigest.getInstance("SHA-256")
            FileChannel.open(destination.toPath(), CREATE_NEW, WRITE).use { channel ->
                created = true
                val output = Channels.newOutputStream(channel)
                val bytes = ByteArray(64 * 1024)
                var total = 0L
                body.use {
                    while (true) {
                        cancellation.check()
                        val count = it.read(bytes)
                        if (count < 0) break
                        if (count == 0) continue
                        check(count.toLong() <= artifact.compressedBytes - total) { "linux_download_size" }
                        output.write(bytes, 0, count)
                        digest.update(bytes, 0, count)
                        total += count
                    }
                }
                check(total == artifact.compressedBytes) { "linux_download_size" }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == artifact.sha256) { "linux_download_hash" }
                cancellation.check()
                output.flush()
                channel.force(true)
            }
            cancellation.publish { complete = true }
        } finally {
            subscription.close()
            runCatching { input.getAndSet(null)?.close() }
            runCatching { activeConnection.get()?.disconnect() }
            if (created && !complete) Files.deleteIfExists(destination.toPath())
        }
    }

    private fun redirectAllowed(origin: URI, target: URI): Boolean {
        if (!target.scheme.equals("https", true) || target.rawUserInfo != null ||
            target.rawFragment != null || target.port !in setOf(-1, 443)) return false
        val host = target.host ?: return false
        return host.equals(origin.host, true) ||
            (origin.host.equals("github.com", true) &&
                host.equals("release-assets.githubusercontent.com", true))
    }
}
