package app.daiyu.android.browser

import app.daiyu.android.browser.download.*
import app.daiyu.android.browser.session.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BrowserCompletedDownloadTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("completed-browser")
        val sessions = BrowserSessionRegistry()
        val owner = BrowserOwner("original-run", BrowserOwnerKind.MAIN)
        val lease: BrowserTabLease
        val shelf = BrowserDownloadShelf()
        val downloads = BrowserDownloadCoordinator(sessions, BrowserPathMapper(root), maxBytes = 64 * 1024L)
        val bytes = ByteArray(20001) { (it % 251).toByte() }
        init {
            sessions.open(BrowserGrant(owner, BrowserAccess.FULL))
            lease = sessions.newTab(owner)
            downloads.observeCompleted(shelf.recorder("conversation", owner))
        }
        fun complete(): Pair<BrowserDownloadReceipt, BrowserCompletedDownload> {
            val receipt = downloads.write(downloads.reserve(lease), ByteArrayInputStream(bytes))
            return receipt to shelf.list("conversation").last()
        }
        override fun close() { sessions.close(owner); root.toFile().deleteRecursively() }
    }
    private fun rejects(work: () -> Unit): Exception {
        try { work() } catch (e: Exception) { return e }
        throw AssertionError("Expected rejection")
    }

    @Test fun completedCapabilityRetainsOriginalLeaseAndExactBytesAfterNormalRunClose(): Unit = Fixture().use { f ->
        val (receipt, ref) = f.complete()
        assertEquals(f.lease, ref.lease)
        assertEquals(receipt.size, ref.size)
        assertEquals(receipt.sha256, ref.sha256)
        f.sessions.close(f.owner)
        val output = ByteArrayOutputStream()
        ref.saveOriginal({ output }) { f.shelf.assertOwned("conversation", ref) }
        assertArrayEquals(f.bytes, output.toByteArray())
        rejects { f.shelf.assertOwned("other-conversation", ref) }
    }

    @Test fun forgedReferenceWithSameReceiptMetadataCannotAuthorizeAPath() = Fixture().use { f ->
        val (_, ref) = f.complete()
        val forged = BrowserCompletedDownload(ref.id, ref.lease, ref.size, ref.sha256, f.downloads)
        var opens = 0
        rejects { forged.saveOriginal({ opens++; ByteArrayOutputStream() }) {} }
        rejects { f.shelf.assertOwned("conversation", forged) }
        assertEquals(0, opens)
    }

    @Test fun corruptionMissingFileSizeChangeAndSymlinkRejectBeforeDestinationOpen() {
        for (mode in 0..3) Fixture().use { f ->
            val (receipt, ref) = f.complete()
            when (mode) {
                0 -> Files.write(receipt.path, f.bytes.copyOf().apply { this[0] = 99 })
                1 -> Files.delete(receipt.path)
                2 -> Files.write(receipt.path, byteArrayOf(1))
                3 -> {
                    val other = Files.write(f.root.resolve("other"), f.bytes)
                    Files.delete(receipt.path); Files.createSymbolicLink(receipt.path, other)
                }
            }
            var opens = 0
            rejects { ref.saveOriginal({ opens++; ByteArrayOutputStream() }) {} }
            assertEquals(0, opens)
        }
    }

    @Test fun destinationFailuresAndMutationDuringCopyNeverReturnSuccess(): Unit = Fixture().use { f ->
        val (receipt, ref) = f.complete()
        rejects { ref.saveOriginal({ throw SecurityException("revoked provider") }) {} }
        rejects { ref.saveOriginal({ object : OutputStream() {
            override fun write(b: Int) { throw IOException("write") }
        } }) {} }
        rejects { ref.saveOriginal({ object : ByteArrayOutputStream() {
            override fun flush() { throw IOException("flush") }
        } }) {} }
        rejects { ref.saveOriginal({ object : ByteArrayOutputStream() {
            override fun close() { throw IOException("close") }
        } }) {} }
        rejects { ref.saveOriginal({
            Files.write(receipt.path, f.bytes.copyOf().apply { this[0] = 99 })
            ByteArrayOutputStream()
        }) {} }
    }

    @Test fun oldUiOwnerBeforeOpenAndRevocationDuringWriteFailClosed() = Fixture().use { f ->
        val (_, ref) = f.complete()
        var opens = 0
        rejects { ref.saveOriginal({ opens++; ByteArrayOutputStream() }) { error("old UI owner") } }
        assertEquals(0, opens)
        var current = true
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length); current = false
            }
        }
        rejects { ref.saveOriginal({ output }) { check(current) } }
        assertTrue(output.size() < f.bytes.size)
    }

    @Test fun failedCancelledOrInvalidatedDownloadsPublishNoReference() = Fixture().use { f ->
        rejects { f.downloads.write(f.downloads.reserve(f.lease), ByteArrayInputStream(byteArrayOf())) }
        val ticket = f.downloads.reserve(f.lease)
        f.sessions.closeTab(f.lease)
        rejects { f.downloads.write(ticket, ByteArrayInputStream(f.bytes)) }
        assertTrue(f.shelf.list("conversation").isEmpty())
    }

    @Test fun shelfBoundsReferencesAndRejectsWrongOriginalOwner() = Fixture().use { f ->
        val (_, first) = f.complete()
        val shelf = BrowserDownloadShelf(1)
        rejects { shelf.recorder("conversation", BrowserOwner("new-run", BrowserOwnerKind.MAIN))(first) }
        val record = shelf.recorder("conversation", f.owner)
        record(first)
        val (_, second) = f.complete(); record(second)
        rejects { shelf.assertOwned("conversation", first) }
        shelf.assertOwned("conversation", second)
    }
}
