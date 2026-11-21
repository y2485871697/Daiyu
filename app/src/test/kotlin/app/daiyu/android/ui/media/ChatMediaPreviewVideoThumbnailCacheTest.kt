package app.daiyu.android.ui.media

import android.graphics.Bitmap
import android.graphics.Color
import app.daiyu.android.media.video.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatMediaPreviewVideoThumbnailCacheTest {
    private fun reference() = MessageVideoReference(MessageVideoOwner("chat", "message"), UUID.randomUUID().toString(),
        "video/mp4", 4, "a".repeat(64), 1_000, 32, 24)
    private fun bitmap() = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
    private fun worker(work: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try { executor.submit { work() }.get() } finally { executor.shutdownNow() }
    }

    @Test fun derivedCoverSurvivesNewCacheAndCorruptionRebuildsWithoutChangingTheReference() = worker {
        val root = Files.createTempDirectory("video-covers").toFile()
        try {
            val reference = reference()
            var decodes = 0
            fun read(cache: VideoThumbnailCache) = cache.load(reference, 128, { false }) { decodes++; bitmap() }
            read(VideoThumbnailCache(root)).also { assertEquals(Color.MAGENTA, it.getPixel(10, 10)); it.recycle() }
            read(VideoThumbnailCache(root)).also { assertEquals(Color.MAGENTA, it.getPixel(10, 10)); it.recycle() }
            assertEquals(1, decodes)
            root.listFiles()!!.single { it.name.endsWith(".cover") }.writeBytes(byteArrayOf(1, 2, 3))
            read(VideoThumbnailCache(root)).recycle()
            assertEquals(2, decodes)
        } finally { root.deleteRecursively() }
    }

    @Test fun trimsOnlySidecarsOwnedByThisCache() = worker {
        val root = Files.createTempDirectory("cover-sidecars").toFile()
        try {
            val owned = listOf("new", "bak").map { File(root, "${"a".repeat(64)}.cover.$it") }
            val neighbors = listOf("notes.cover.new", "notes.cover.bak", "keep.new").map { File(root, it) }
            (owned + neighbors).forEach { it.writeText("keep") }
            VideoThumbnailCache(root).load(reference(), 128, { false }) { bitmap() }.recycle()
            assertTrue(owned.none { it.exists() })
            neighbors.forEach { assertEquals("keep", it.readText()) }
        } finally { root.deleteRecursively() }
    }

    @Test fun concurrentControllersShareOneDecodeAndOnePublishedCover() {
        val root = Files.createTempDirectory("shared-covers").toFile()
        val pool = Executors.newFixedThreadPool(2)
        val ready = java.util.concurrent.CyclicBarrier(2)
        val decodes = java.util.concurrent.atomic.AtomicInteger()
        val ref = reference()
        try {
            val readers = List(2) {
                pool.submit {
                    val cache = VideoThumbnailCache(root)
                    ready.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    cache.load(ref, 128, { false }) { decodes.incrementAndGet(); bitmap() }.recycle()
                }
            }
            readers.forEach { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, decodes.get())
            assertEquals(1, root.listFiles()!!.count { it.extension == "cover" })
        } finally { pool.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun everyWarmLookupStillRequiresTheCurrentOriginalDigestAndMetadata() = worker {
        val root = Files.createTempDirectory("verified-video-covers").toFile()
        try {
            val source = byteArrayOf(1, 2, 3, 4)
            val originals = File(root, "originals")
            val store = VideoAssetStore(originals, VideoAttachmentLimits(),
                VideoInspector { _, _, _ -> VideoMetadata("video/mp4", 1_000, 32, 24) })
            val draft = store.import(VideoAttachmentOwner("chat", "draft"), source.inputStream())
            val reference = store.prepare(MessageVideoOwner("chat", "message"), draft)
            val cache = VideoThumbnailCache(File(root, "covers"))
            var decodes = 0
            fun read(): Bitmap = store.openOriginal(reference.owner, reference).use {
                cache.load(reference, 128, { false }) { decodes++; bitmap() }
            }
            read().recycle(); read().recycle()
            assertEquals(1, decodes)
            val original = originals.walkTopDown().single { it.isFile && it.name == "original.video" &&
                it.parentFile.name == reference.id && it.parentFile.parentFile.name == reference.owner.directoryName() }
            assertEquals(setOf("original.video", "metadata.json"), original.parentFile.list()!!.toSet())
            original.writeBytes(ByteArray(source.size))
            try { read().recycle(); fail("warm derivative must not bypass original hash") }
            catch (failure: VideoAttachmentException) { assertEquals(VideoAttachmentFailure.CORRUPT, failure.failure) }
            assertEquals(1, decodes)
        } finally { root.deleteRecursively() }
    }

    @Test fun boundedCoversSeparateExactReferencesAndDoNotPublishCancelledDecodes() = worker {
        val root = Files.createTempDirectory("bounded-video-covers").toFile()
        try {
            val cache = VideoThumbnailCache(root, maxEntries = 2)
            val reference = reference()
            var decodes = 0
            for (item in listOf(reference, reference.copy(sha256 = "b".repeat(64)),
                reference.copy(owner = MessageVideoOwner("other", "message")))) {
                cache.load(item, 128, { false }) { decodes++; bitmap() }.recycle()
            }
            assertEquals(3, decodes)
            assertTrue(root.listFiles()!!.count { it.name.endsWith(".cover") } <= 2)
            val before = root.listFiles()!!.filter { it.name.endsWith(".cover") }.map { it.name }.toSet()
            var cancelled = false
            var cancelledBitmap: Bitmap? = null
            try {
                cache.load(reference.copy(id = UUID.randomUUID().toString()), 256, { cancelled }) {
                    cancelled = true; bitmap().also { cancelledBitmap = it }
                }.recycle()
                fail("cancelled decode must not be delivered")
            } catch (failure: VideoAttachmentException) { assertEquals(VideoAttachmentFailure.CANCELLED, failure.failure) }
            assertTrue(checkNotNull(cancelledBitmap).isRecycled)
            assertEquals(before, root.listFiles()!!.filter { it.name.endsWith(".cover") }.map { it.name }.toSet())
        } finally { root.deleteRecursively() }
    }
}
