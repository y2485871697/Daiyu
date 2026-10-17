package app.daiyu.android.media

import app.daiyu.android.media.video.VideoAttachmentLimits
import app.daiyu.android.media.video.VideoInspector
import app.daiyu.android.media.video.VideoMetadata
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.storage.GenerationAttemptBinding
import app.daiyu.android.storage.StaticGenerationDestination
import app.daiyu.android.storage.StaticGenerationOutput
import app.daiyu.android.storage.StaticGenerationProvenance
import app.daiyu.android.storage.staticDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** Injected inspectors accept NON-media fixtures. These tests prove lifecycle/file contracts,
 * not real image/video decoding. Real PNG decoder coverage is separate device test source.
 */
class StaticGenerationAssetsTest {
    private lateinit var root: File
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val inspector = ImageInspector { require(it[0] == 1.toByte()); ImageDimensions("image/png", 2, 2) }
    private val videoInspector = VideoInspector { file, _, _ ->
        require(file.readBytes()[0] == 2.toByte()); VideoMetadata("video/mp4", 1000, 2, 2)
    }
    @Before fun setUp() { root = Files.createTempDirectory("static-generation").toFile() }
    @After fun tearDown() { root.deleteRecursively() }
    private fun assets(afterMove: () -> Unit = {}) = StaticGenerationAssets(root, inspector,
        videoInspector, ImageAttachmentLimits(), VideoAttachmentLimits(), afterMove)
    private fun output(branch: String = "branch", original: ByteArray = bytes): StaticGenerationOutput {
        val b = GenerationAttemptBinding("source", "source-assistant", "source-run", "source-call", "generate_images")
        val hash = staticDigest(original)
        val source = StaticGenerationProvenance("source-account", b.operationId, "item", 0, b.conversationId,
            b.assistantMessageId, b.runId, b.toolCallId, b.toolName, "a".repeat(64), GenerationProtocol.OPENAI_IMAGES_JSON,
            "genasset-v1-${"b".repeat(64)}-$hash", GenerationKind.IMAGE, "image/png", original.size.toLong(), hash, 2, 2, 0)
        return StaticGenerationOutput(source, StaticGenerationDestination("target-account", branch, "$branch-assistant",
            "$branch-run", "$branch-call", source.toolName))
    }
    private fun reader(value: ByteArray = bytes) = StaticGenerationOriginalReader { ByteArrayInputStream(value) }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }
    private fun directory(value: StaticGenerationOutput, suffix: String = "") =
        File(File(root, "generation-static-v1"), value.identity.removePrefix("genstatic-v1-") + suffix)
    private fun marker(value: StaticGenerationOutput) = File(directory(value).parentFile, directory(value).name + ".lease")
    private fun commit(store: StaticGenerationAssets, value: StaticGenerationOutput) {
        val lease = store.prepare(value, reader())
        assertEquals(value, store.publish(lease))
        store.settle(lease, StaticGenerationSettlement.COMMITTED)
    }
    private fun read(store: StaticGenerationAssets, value: StaticGenerationOutput) =
        store.openVerified(value, { value }).use { it.readBytes() }

    @Test fun twoBranchesAndBranchOfStaticRemainReadableAfterSourceDeletion() {
        val sourceFile = File(root, "source-original").apply { writeBytes(bytes) }
        val untouchedShared = File(root, "shared-hash-blob").apply { writeBytes(bytes) }
        val first = output("first"); val second = output("second")
        val store = assets()
        for (value in listOf(first, second)) {
            val lease = store.prepare(value, StaticGenerationOriginalReader { sourceFile.inputStream() })
            store.publish(lease); store.settle(lease, StaticGenerationSettlement.COMMITTED)
        }
        assertTrue(sourceFile.delete())
        assertArrayEquals(bytes, read(assets(), first))
        assertArrayEquals(bytes, read(assets(), second))
        val third = output("third")
        val lease = store.prepare(third, StaticGenerationOriginalReader { store.openVerified(first, { first }) })
        store.publish(lease); store.settle(lease, StaticGenerationSettlement.COMMITTED)
        assertEquals(first.source, third.source)
        assertArrayEquals(bytes, read(assets(), third))
        assertArrayEquals(bytes, untouchedShared.readBytes())
        assertEquals(setOf("original", "record-v1"), directory(first).list()!!.toSet())
        assertFalse(marker(first).exists())
    }

    @Test fun rollbackCleansOnlyOwnedStageOrNewCopyAndNeverAnExistingPublishedCopy() {
        val source = File(root, "source-blob").apply { writeBytes(bytes) }
        val store = assets(); val value = output()
        var lease = store.prepare(value, reader())
        assertTrue(directory(value, ".pending").isDirectory)
        store.settle(lease, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED)
        assertFalse(directory(value, ".pending").exists()); assertFalse(marker(value).exists())
        lease = store.prepare(value, reader()); store.publish(lease)
        store.settle(lease, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED)
        assertFalse(directory(value).exists()); assertFalse(marker(value).exists())
        commit(store, value)
        val reused = assets().let { second ->
            val same = second.prepare(value, reader())
            second.publish(same); second.settle(same, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED)
            same
        }
        assertTrue(reused.reused)
        assertArrayEquals(bytes, read(store, value))
        assertArrayEquals(bytes, source.readBytes())
        rejects { store.publish(lease) } // settled lease cannot be reused
    }

    @Test fun boundedInputRejectsTruncationTrailingDigestAndClosesEveryReader() {
        val store = assets(); val value = output()
        for (bad in listOf(bytes.copyOf(3), bytes + byteArrayOf(9), byteArrayOf(1, 2, 3, 9))) {
            var closed = false
            rejects {
                store.prepare(value, StaticGenerationOriginalReader {
                    object : ByteArrayInputStream(bad) { override fun close() { closed = true; super.close() } }
                })
            }
            assertTrue(closed)
            assertFalse(directory(value).exists()); assertFalse(directory(value, ".pending").exists())
            assertFalse(marker(value).exists())
        }
        val tooLarge = StaticGenerationAssets(root, inspector, videoInspector,
            ImageAttachmentLimits(maxImageBytes = 3), VideoAttachmentLimits())
        var opened = false
        rejects { tooLarge.prepare(value, StaticGenerationOriginalReader { opened = true; ByteArrayInputStream(bytes) }) }
        assertFalse(opened)
    }

    @Test fun checksActualMediaAndFullRecordEqualityRatherThanDigestOnly() {
        val store = assets(); val value = output()
        val wrongDimensions = value.copy(source = value.source.copy(width = 3))
        rejects { store.prepare(wrongDimensions, reader()) }
        assertFalse(directory(value, ".pending").exists())
        commit(store, value)
        val changed = value.copy(source = value.source.copy(fingerprint = "c".repeat(64)))
        assertEquals(value.identity, changed.identity)
        rejects { store.prepare(changed, reader()) }
        assertArrayEquals(bytes, read(store, value))
        rejects { store.openVerified(value, { null }) }
        rejects { store.openVerified(value, { changed }) }
        val videoBytes = byteArrayOf(2, 3, 4, 5)
        val video = output("video", videoBytes).let { it.copy(source = it.source.copy(kind = GenerationKind.VIDEO,
            protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART, mimeType = "video/mp4", durationMs = 1000)) }
        val lease = store.prepare(video, reader(videoBytes)); store.publish(lease)
        store.settle(lease, StaticGenerationSettlement.COMMITTED)
        assertArrayEquals(videoBytes, read(store, video))
        rejects { store.prepare(video.copy(source = video.source.copy(durationMs = 999)), reader(videoBytes)) }
    }

    @Test fun cancellationBeforeReaderDuringReadBeforePublishAndAfterCommit() {
        val store = assets(); val value = output(); var cancelled = true; var opened = false
        rejects { store.prepare(value, StaticGenerationOriginalReader { opened = true; ByteArrayInputStream(bytes) }) { cancelled } }
        assertFalse(opened)
        cancelled = false; var closed = false
        rejects { store.prepare(value, StaticGenerationOriginalReader {
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len); cancelled = true; return n
                }
                override fun close() { closed = true }
            }
        }) { cancelled } }
        assertTrue(closed)
        cancelled = false
        val prepared = store.prepare(value, reader()) { cancelled }; cancelled = true
        rejects { store.publish(prepared) }
        store.settle(prepared, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED)
        assertFalse(marker(value).exists())
        cancelled = false
        val committed = store.prepare(value, reader()) { cancelled }; store.publish(committed)
        cancelled = true
        store.settle(committed, StaticGenerationSettlement.COMMITTED)
        assertArrayEquals(bytes, read(assets(), value)) // durable success wins after cancellation
    }

    @Test fun renameUnknownAndSqlUnknownRetainBothEvidenceAndReservation() {
        val value = output(); val store = assets { throw java.io.IOException("injected after rename") }
        val lease = store.prepare(value, reader())
        try { store.publish(lease); fail("Expected UNKNOWN") }
        catch (unknown: StaticGenerationPublicationUnknown) { assertEquals(value.identity, unknown.identity) }
        store.settle(lease, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED) // must NOT clean ambiguous rename
        assertTrue(directory(value).isDirectory); assertTrue(marker(value).exists())
        assertArrayEquals(bytes, read(assets(), value)) // only a real registry member may do this in production
        rejects { assets().prepare(value, reader()) }
        val sqlValue = output("sql-unknown"); val second = assets()
        val sqlLease = second.prepare(sqlValue, reader()); second.publish(sqlLease)
        second.settle(sqlLease, StaticGenerationSettlement.UNKNOWN)
        assertTrue(directory(sqlValue).isDirectory); assertTrue(marker(sqlValue).exists())
        rejects { second.settle(sqlLease, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED) }
        val pendingValue = output("pending-unknown")
        val pending = second.prepare(pendingValue, reader()); second.settle(pending, StaticGenerationSettlement.UNKNOWN)
        assertTrue(directory(pendingValue, ".pending").exists())
        rejects { assets().prepare(pendingValue, reader()) }
    }

    @Test fun reservationExcludesAnotherPreparerAndForeignStoreCannotSettleLease() {
        val value = output(); val store = assets(); val second = assets()
        val lease = store.prepare(value, reader()); store.publish(lease)
        rejects { second.prepare(value, reader()) }
        rejects { second.settle(lease, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED) }
        store.settle(lease, StaticGenerationSettlement.COMMITTED)
        val reused = second.prepare(value, reader()); second.publish(reused)
        second.settle(reused, StaticGenerationSettlement.COMMITTED)
        assertArrayEquals(bytes, read(store, value))
    }

    @Test fun rollbackWithUnknownDirectoryEntryRetainsEvidenceRatherThanDeletingForeignContent() {
        val value = output(); val store = assets()
        val lease = store.prepare(value, reader()); store.publish(lease)
        val foreign = File(directory(value), "foreign").apply { writeText("keep") }
        store.settle(lease, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED)
        assertEquals(StaticGenerationAssets.State.UNKNOWN, lease.state)
        assertTrue(foreign.exists()); assertTrue(marker(value).exists())
        assertArrayEquals(bytes, File(directory(value), "original").readBytes())
        rejects { assets().prepare(value, reader()) }
    }

    @Test fun recordOriginalAndNamespaceSymlinksOrCorruptionFailClosed() {
        val value = output(); val store = assets(); commit(store, value)
        val original = File(directory(value), "original")
        original.appendBytes(byteArrayOf(8))
        rejects { read(store, value) }
        original.writeBytes(bytes)
        val record = File(directory(value), "record-v1")
        val saved = record.readBytes(); record.appendText(" ")
        rejects { read(store, value) }; record.writeBytes(saved)
        val shared = File(root, "unrelated-original").apply { writeBytes(bytes) }
        assertTrue(original.delete())
        Files.createSymbolicLink(original.toPath(), shared.toPath())
        rejects { read(store, value) }; assertArrayEquals(bytes, shared.readBytes())
        Files.delete(original.toPath()); original.writeBytes(bytes)
        val unknown = File(directory(value), "do-not-delete").apply { writeText("foreign") }
        rejects { read(store, value) }; assertTrue(unknown.exists())
        val otherRoot = File(root, "symlink-case").apply { mkdir() }
        Files.createSymbolicLink(File(otherRoot, "generation-static-v1").toPath(), directory(value).toPath())
        rejects { StaticGenerationAssets(otherRoot, inspector, videoInspector, ImageAttachmentLimits(), VideoAttachmentLimits()) }
    }
}
