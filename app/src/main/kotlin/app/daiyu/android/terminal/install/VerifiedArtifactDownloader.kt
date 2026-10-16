package app.daiyu.android.terminal.install

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * New transport adapter for the install boundary. It does not discover mirrors, follow redirects,
 * retry on a different origin, cache credentials, or trust Content-Length instead of actual bytes.
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
        val connection = connect(mirror.uri.toURL())
        val input = AtomicReference<InputStream?>()
        var created = false
        var complete = false
        val subscription = cancellation.register {
            // Some providers unblock read on close, others on disconnect; always attempt both.
            runCatching { connection.disconnect() }
            runCatching { input.getAndSet(null)?.close() }
        }
        try {
            cancellation.check()
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "linux_download_http" }
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
                complete = true
            }
        } finally {
            subscription.close()
            runCatching { input.getAndSet(null)?.close() }
            runCatching { connection.disconnect() }
            if (created && !complete) Files.deleteIfExists(destination.toPath())
        }
    }
}
