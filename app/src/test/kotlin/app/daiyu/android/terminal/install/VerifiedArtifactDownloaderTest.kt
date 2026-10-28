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
                             val code: Int = 200, val location: String? = null, val encoding: String? = null) : HttpURLConnection(URL("https://example.invalid/rootfs.tar.gz")) {
        val disconnects = AtomicInteger()
        override fun connect() {}
        override fun disconnect() { disconnects.incrementAndGet() }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentLengthLong() = declaredSize
        override fun getContentEncoding(): String? = encoding
        override fun getHeaderField(name: String): String? = if (name.equals("Location", true)) location else null
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

    fun testRedirectWithoutLocationAndIncorrectContentLengthAreRejectedWithoutCreatingDestination() = fixture { destination ->
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

    fun testRelativeHttpsRedirectStatusesPreserveVerificationAndCloseEveryConnection() = fixture { destination ->
        for (status in listOf(301, 302, 303, 307, 308)) {
            val redirect = Connection(ByteArrayInputStream(byteArrayOf()), code = status, location = "/asset")
            val asset = Connection(ByteArrayInputStream(payload))
            val requested = mutableListOf<URL>()
            VerifiedArtifactDownloader { url ->
                requested += url
                if (requested.size == 1) redirect else asset
            }.download(artifact(), mirror, destination, LinuxInstallCancellation())
            assertEquals(listOf(mirror.uri.toString(), "https://example.invalid/asset"), requested.map { it.toString() })
            assertTrue(payload.contentEquals(destination.readBytes()))
            assertTrue(redirect.disconnects.get() > 0)
            assertTrue(asset.disconnects.get() > 0)
            assertFalse(redirect.instanceFollowRedirects)
            assertFalse(asset.instanceFollowRedirects)
            assertTrue(destination.delete())
        }
    }

    fun testBothDebianAbisAcceptOnlyFixedGithubReleaseAssetCdn() = fixture { destination ->
        val debian = LinuxRootfsCatalog.artifacts.filter { it.distribution == "debian" }
        assertEquals(2, debian.size)
        for (catalog in debian) {
            val selected = catalog.mirrors.first()
            val pinned = LinuxRootfsArtifact("fixture", catalog.distribution, catalog.version, catalog.abi,
                catalog.archive, payload.size.toLong(), artifact().sha256, listOf(selected))
            val redirect = Connection(ByteArrayInputStream(byteArrayOf()), code = 302,
                location = "https://release-assets.githubusercontent.com/github-production-release-asset/asset?signature=fixture")
            val asset = Connection(ByteArrayInputStream(payload))
            var opens = 0
            VerifiedArtifactDownloader { url ->
                opens++
                if (opens == 1) redirect else {
                    assertEquals("release-assets.githubusercontent.com", url.host)
                    asset
                }
            }.download(pinned, selected, destination, LinuxInstallCancellation())
            assertEquals(2, opens)
            assertTrue(payload.contentEquals(destination.readBytes()))
            assertTrue(destination.delete())
        }
    }

    fun testBothDistributionsAbisAndAllSelectedMirrorsUseTheSameVerifiedDownloader() = fixture { destination ->
        assertEquals(4, LinuxRootfsCatalog.artifacts.size)
        assertEquals(8, LinuxRootfsCatalog.artifacts.sumOf { it.mirrors.size })
        for (catalog in LinuxRootfsCatalog.artifacts) for (selected in catalog.mirrors) {
            val pinned = LinuxRootfsArtifact("fixture", catalog.distribution, catalog.version, catalog.abi,
                catalog.archive, payload.size.toLong(), artifact().sha256, listOf(selected))
            val connection = Connection(ByteArrayInputStream(payload))
            VerifiedArtifactDownloader { url ->
                assertEquals(selected.uri.toURL(), url)
                connection
            }.download(pinned, selected, destination, LinuxInstallCancellation())
            assertTrue(payload.contentEquals(destination.readBytes()))
            assertTrue(destination.delete())
        }
    }

    fun testUntrustedRedirectsAreRejectedBeforeOpeningTarget() = fixture { destination ->
        for (target in listOf("http://example.invalid/asset", "https://other.invalid/asset",
            "https://example.invalid:444/asset", "https://user:password@example.invalid/asset",
            "https://example.invalid/asset#fragment", "https://release-assets.githubusercontent.com/asset",
            "https://github.com.evil.invalid/asset", "file:///tmp/archive", "http://[")) {
            val redirect = Connection(ByteArrayInputStream(byteArrayOf()), code = 302, location = target)
            var opens = 0
            rejected { VerifiedArtifactDownloader { opens++; redirect }
                .download(artifact(), mirror, destination, LinuxInstallCancellation()) }
            assertEquals(target, 1, opens)
            assertFalse(destination.exists())
            assertTrue(redirect.disconnects.get() > 0)
        }
    }

    fun testRedirectLoopStopsAfterFiveHopsAndDisconnectsEveryOpenedConnection() = fixture { destination ->
        val connections = mutableListOf<Connection>()
        rejected {
            VerifiedArtifactDownloader {
                Connection(ByteArrayInputStream(byteArrayOf()), code = 302, location = "/again").also(connections::add)
            }.download(artifact(), mirror, destination, LinuxInstallCancellation())
        }
        assertEquals(6, connections.size)
        assertTrue(connections.all { it.disconnects.get() > 0 })
        assertFalse(destination.exists())
    }

    fun testBadHashAfterRedirectStillDeletesPartialArtifact() = fixture { destination ->
        var opens = 0
        rejected {
            VerifiedArtifactDownloader {
                if (++opens == 1) Connection(ByteArrayInputStream(byteArrayOf()), code = 302, location = "/asset")
                else Connection(ByteArrayInputStream(ByteArray(payload.size)))
            }.download(artifact(), mirror, destination, LinuxInstallCancellation())
        }
        assertEquals(2, opens)
        assertFalse(destination.exists())
    }

    fun testCancellationDuringRedirectConnectionOpenClosesNewConnectionAndDoesNotOpenAsset() = fixture { destination ->
        val token = LinuxInstallCancellation()
        val first = Connection(ByteArrayInputStream(byteArrayOf()), code = 302, location = "/next")
        val second = Connection(ByteArrayInputStream(payload))
        var opens = 0
        try {
            VerifiedArtifactDownloader {
                if (++opens == 1) first else second.also { token.cancel() }
            }.download(artifact(), mirror, destination, token)
            fail("expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(2, opens)
        assertTrue(first.disconnects.get() > 0)
        assertTrue(second.disconnects.get() > 0)
        assertFalse(destination.exists())
    }

    fun testExactlyFiveRedirectsThenVerifiedPayloadSucceeds() = fixture { destination ->
        var opens = 0
        VerifiedArtifactDownloader {
            if (++opens <= 5) Connection(ByteArrayInputStream(byteArrayOf()), code = 302, location = "/hop$opens")
            else Connection(ByteArrayInputStream(payload))
        }.download(artifact(), mirror, destination, LinuxInstallCancellation())
        assertEquals(6, opens)
        assertTrue(payload.contentEquals(destination.readBytes()))
    }

    fun testEncodedResponseAndOversizedLocationAreRejected() = fixture { destination ->
        for (connection in listOf(Connection(ByteArrayInputStream(payload), encoding = "gzip"),
            Connection(ByteArrayInputStream(byteArrayOf()), code = 302, location = "/" + "x".repeat(8192)))) {
            rejected { VerifiedArtifactDownloader { connection }
                .download(artifact(), mirror, destination, LinuxInstallCancellation()) }
            assertTrue(connection.disconnects.get() > 0)
            assertFalse(destination.exists())
        }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalStateException) {
        } catch (_: IOException) { }
    }
}
