package app.daiyu.android.terminal.install

import junit.framework.TestCase
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fake HttpURLConnection only: no network sockets, server, shell or real rootfs. */
class VerifiedArtifactDownloaderTest : TestCase() {
    private val payload = "exact compressed bytes fixture".toByteArray()
    private val mirror = RootfsMirror("test", URI("https://example.invalid/rootfs.tar.gz"))
    private fun artifact() = LinuxRootfsArtifact("fixture", "alpine", "fixture", "arm64-v8a",
        RootfsArchive.TAR_GZIP, payload.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }, listOf(mirror))

    private class Connection(val body: InputStream, val declaredSize: Long = -1,
                             val code: Int = 200) : HttpURLConnection(URL("https://example.invalid/rootfs.tar.gz")) {
        val disconnects = AtomicInteger()
        override fun connect() {}
        override fun disconnect() { disconnects.incrementAndGet() }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentLengthLong() = declaredSize
        override fun getContentEncoding(): String? = null
        override fun getInputStream() = body
    }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("verified-download-test-").toFile()
        try { block(File(root, "archive")) } finally { root.deleteRecursively() }
    }

    fun testExactSizeAndHashVerifiedAndConnectionAlwaysDisconnected() = fixture { destination ->
        val connection = Connection(ByteArrayInputStream(payload), payload.size.toLong())
        VerifiedArtifactDownloader { connection }.download(artifact(), mirror, destination, LinuxInstallCancellation())
        assertTrue(payload.contentEquals(destination.readBytes()))
        assertTrue(connection.disconnects.get() > 0)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
    }

    fun testBadHashTruncationAndOverlongBodiesLeaveNoArtifact() = fixture { destination ->
        val bodies = listOf(ByteArray(payload.size), payload.copyOf(payload.size - 1), payload + byteArrayOf(1))
        bodies.forEach { body ->
            val connection = Connection(ByteArrayInputStream(body))
            rejected { VerifiedArtifactDownloader { connection }.download(artifact(), mirror, destination, LinuxInstallCancellation()) }
            assertFalse(destination.exists())
            assertTrue(connection.disconnects.get() > 0)
        }
    }

    fun testRedirectAndIncorrectContentLengthAreRejectedWithoutCreatingDestination() = fixture { destination ->
        listOf(Connection(ByteArrayInputStream(payload), code = 302),
            Connection(ByteArrayInputStream(payload), declaredSize = payload.size + 1L)).forEach { connection ->
            rejected { VerifiedArtifactDownloader { connection }.download(artifact(), mirror, destination, LinuxInstallCancellation()) }
            assertFalse(destination.exists())
        }
    }

    fun testExistingDestinationIsNeverTruncatedOrDeleted() = fixture { destination ->
        destination.writeText("keep")
        val connection = Connection(ByteArrayInputStream(payload))
        rejected { VerifiedArtifactDownloader { connection }.download(artifact(), mirror, destination, LinuxInstallCancellation()) }
        assertEquals("keep", destination.readText())
    }

    fun testAlreadyCancelledDoesNotEvenOpenConnection() = fixture { destination ->
        var opens = 0
        val token = LinuxInstallCancellation().apply { cancel() }
        rejected {
            VerifiedArtifactDownloader { opens++; Connection(ByteArrayInputStream(payload)) }
                .download(artifact(), mirror, destination, token)
        }
        assertEquals(0, opens)
        assertFalse(destination.exists())
    }

    fun testBlockedReadIsActuallyDisconnectedAndClosedOnCancellation() = fixture { destination ->
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val body = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown()
                if (!closed.await(5, TimeUnit.SECONDS)) throw IOException("test timeout")
                throw IOException("closed by cancellation")
            }
            override fun close() { closed.countDown() }
        }
        val connection = Connection(body)
        val token = LinuxInstallCancellation()
        val work = FutureTask<Unit> {
            VerifiedArtifactDownloader { connection }.download(artifact(), mirror, destination, token)
        }
        val worker = Thread(work, "fake-download-test").apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            token.cancel()
            try { work.get(2, TimeUnit.SECONDS); fail("expected cancellation/read error") }
            catch (_: ExecutionException) { }
            assertTrue(connection.disconnects.get() > 0)
            assertEquals(0L, closed.count)
            assertFalse(destination.exists())
        } finally {
            token.cancel()
            closed.countDown()
            worker.join(2000)
        }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalStateException) {
        } catch (_: IOException) { }
    }
}
