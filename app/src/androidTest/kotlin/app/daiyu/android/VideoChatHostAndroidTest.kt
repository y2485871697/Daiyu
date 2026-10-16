package app.daiyu.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import android.test.mock.MockContentResolver
import app.daiyu.android.media.video.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Real ContentResolver -> AndroidVideoAttachments -> private originals -> actual frame preview.
 * The small MP4 is encoded on-device, not a JPEG cover or a signature-only fake. No network/DB.
 * Receipt calls below simulate the parent's authoritative settlement boundary, NOT a send success.
 */
@Suppress("DEPRECATION")
class VideoChatHostAndroidTest : InstrumentationTestCase() {
    private class Documents(private val file: File) : ContentProvider() {
        val opens = AtomicInteger()
        var entered: CountDownLatch? = null
        var release: CountDownLatch? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4" // Untrusted hint, including the rejection test.
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(Looper.myLooper() != Looper.getMainLooper())
            opens.incrementAndGet()
            entered?.countDown()
            check(release?.await(15, TimeUnit.SECONDS) != false)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unused")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("unused")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("unused")
    }

    private lateinit var root: File
    private lateinit var source: File
    private lateinit var documents: Documents
    private lateinit var media: AndroidVideoAttachments
    private lateinit var host: VideoChatHost
    private lateinit var preferences: android.content.SharedPreferences
    private val uri = Uri.parse("content://host-video/source")
    private val publications = AtomicInteger()

    override fun setUp() {
        super.setUp()
        val context = instrumentation.targetContext
        root = File(context.filesDir, "video-host-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        source = File(root, "source.mp4")
        encodeSmallMp4(source)
        documents = Documents(source).apply {
            attachInfo(context, ProviderInfo().apply { authority = "host-video"; exported = false })
        }
        val resolver = MockContentResolver(context).apply { addProvider("host-video", documents) }
        media = AndroidVideoAttachments(resolver, VideoAssetStore(File(root, "private-originals")))
        preferences = context.getSharedPreferences("video-host-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        host = main { VideoChatHost(preferences, media).also { it.attach { publications.incrementAndGet() } } }
    }

    override fun tearDown() {
        try {
            documents.release?.countDown()
            main { host.detach() }
            preferences.edit().clear().commit()
            root.deleteRecursively()
        } finally { super.tearDown() }
    }

    private fun <T> main(work: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(work) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(description, condition())
    }
    private fun pick(count: Int = 1) = main {
        host.picked(checkNotNull(host.requestSelection()), List(count) { uri })
    }

    fun testOriginalAndPreviewRestoreAndOnlyAcceptedDraftCopyIsRemoved() {
        assertTrue(pick())
        await("real video import") { main { host.state.items.singleOrNull() is VideoChatHost.Item.Ready } }
        val original = main { (host.state.items.single() as VideoChatHost.Item.Ready).video }
        val owner = main { host.state.owner }
        media.store.openOriginal(owner, original).use { assertTrue(source.readBytes().contentEquals(it.readBytes())) }
        main {
            host.detach()
            host = VideoChatHost(preferences, media).also { it.attach { publications.incrementAndGet() } }
            assertEquals(owner, host.state.owner)
            assertEquals(original, (host.state.items.single() as VideoChatHost.Item.Ready).video)
        }
        val preview = runBlocking { withContext(Dispatchers.Main) { host.preview(original, 64) } }
        assertNotNull(preview)
        assertEquals(original, preview!!.original)
        assertEquals("image/jpeg", preview.mime)
        assertTrue(preview.bytes().isNotEmpty())
        assertEquals("video/mp4", original.mime)
        media.store.openOriginal(owner, original).use { assertTrue(source.readBytes().contentEquals(it.readBytes())) }
        await("restored owner reconciliation") { main { !host.state.recovering } }
        val request = main { checkNotNull(host.submit("actual video, not cover") {}) }
        assertEquals(listOf(original), request.videos)
        val messageOwner = MessageVideoOwner(owner.conversationId, "accepted-message")
        val messageCopy = media.prepare(messageOwner, original)
        assertTrue(main { host.settle(VideoDraftReceipt(request.conversation, owner, request.revision,
            request.videos, VideoDraftOutcome.UNKNOWN)) })
        assertEquals(listOf(original), media.store.list(owner))
        assertTrue(main { host.settle(VideoDraftReceipt(request.conversation, owner, request.revision,
            request.videos, VideoDraftOutcome.ACCEPTED, owner.conversationId)) })
        await("exact draft cleanup") { media.store.list(owner).isEmpty() }
        assertEquals(listOf(messageCopy), media.store.preparedReferences(messageOwner))
        media.store.openOriginal(messageOwner, messageCopy).use { assertTrue(source.readBytes().contentEquals(it.readBytes())) }
        assertEquals(owner.conversationId, main { host.state.conversation })
        assertEquals(owner, main { host.state.owner })
    }

    fun testRestartReconcilesRealOwnerInventoryWithoutDeletingUnknownOrMessageCopy() {
        // Seed exactly the durable boundary: reserved host owner, real import publication,
        // but no journal publication for three of four originals. No URI is journalled.
        val owner = main { host.state.owner }
        main { host.detach() }
        val originals = List(4) { media.import(owner, uri) }
        val messageOwner = MessageVideoOwner(owner.conversationId, "protected-message")
        val messageCopy = media.prepare(messageOwner, originals.last())
        val store = VideoHostDraftStore(preferences)
        val reserved = store.read().single()
        assertTrue(store.write(listOf(reserved.copy(revision = 5, videos = listOf(originals.first()),
            blockedSlots = 3, fenced = true))))
        main { host = VideoChatHost(preferences, media).also { it.attach() } }
        await("owner inventory recovery") { media.store.list(owner) == listOf(originals.first()) }
        await("durable orphan queue drained") { store.read().single().pendingDeletes.isEmpty() }
        assertEquals(listOf(originals.first()), main { host.pendingRecovery().single().videos })
        assertTrue(main { host.state.fenced }) // presence never authorizes acceptance/unsealing
        assertEquals(listOf(messageCopy), media.store.preparedReferences(messageOwner))
        media.store.openOriginal(messageOwner, messageCopy).use {
            assertTrue(source.readBytes().contentEquals(it.readBytes()))
        }
    }

    fun testAcceptedCrashQueueRealDeleteFailureAndRestartReclaimsAllFourSlots() {
        val store = VideoHostDraftStore(preferences)
        val attempted = CountDownLatch(1)
        val failingDelete = object : VideoDraftMedia {
            override fun import(owner: VideoAttachmentOwner, uri: Uri, cancellation: android.os.CancellationSignal) =
                media.import(owner, uri, cancellation)
            override fun preview(video: VideoAttachment, maxEdge: Int, cancellation: android.os.CancellationSignal) =
                media.preview(video.owner, video, maxEdge, cancellation)
            override fun list(owner: VideoAttachmentOwner) = media.store.list(owner)
            override fun remove(video: VideoAttachment) {
                attempted.countDown()
                throw IllegalStateException("simulated unavailable storage")
            }
        }
        main {
            host.detach()
            host = VideoChatHost(store, failingDelete).also { it.attach() }
        }
        await("restored owner inventory") { main { !host.state.recovering } }
        assertTrue(pick(4))
        await("four originals") { main { host.state.items.filterIsInstance<VideoChatHost.Item.Ready>().size == 4 } }
        val request = main { checkNotNull(host.submit("no actual send") {}) }
        val messageOwner = MessageVideoOwner(request.owner.conversationId, "accepted-copy")
        val messageCopy = media.prepare(messageOwner, request.videos.first())
        assertTrue(main { host.settle(VideoDraftReceipt(request.conversation, request.owner, request.revision,
            request.videos, VideoDraftOutcome.ACCEPTED, request.owner.conversationId)) })
        assertTrue(attempted.await(15, TimeUnit.SECONDS))
        assertEquals(request.videos, store.read().single().pendingDeletes)
        assertTrue(store.read().single().videos.isEmpty())
        // New process uses normal removal; old worker cannot remove anything or write a success.
        main {
            host.detach()
            host = VideoChatHost(preferences, media).also { it.attach(); it.enter(request.owner.conversationId) }
        }
        await("accepted originals removed after restart") { media.store.list(request.owner).isEmpty() }
        await("queue acknowledged after restart") {
            store.read().single { it.owner == request.owner }.pendingDeletes.isEmpty() && main { !host.state.recovering }
        }
        assertEquals(listOf(messageCopy), media.store.preparedReferences(messageOwner))
        assertTrue(pick(4))
        await("same owner can import four again") {
            main { host.state.items.filterIsInstance<VideoChatHost.Item.Ready>().size == 4 }
        }
    }

    fun testBlockedProviderCannotPublishIntoRevisitedConversation() {
        main { host.enter("A") }
        val owner = main { host.state.owner }
        documents.entered = CountDownLatch(1)
        documents.release = CountDownLatch(1)
        assertTrue(pick())
        assertTrue(documents.entered!!.await(15, TimeUnit.SECONDS))
        main { host.enter("B"); host.enter("A") }
        assertTrue(main { host.state.items.single() is VideoChatHost.Item.Failed })
        assertEquals(3, main { host.state.remaining })
        val beforeCompletion = publications.get()
        documents.release!!.countDown()
        // The host publishes even a rejected late completion; wait for that, not just an empty
        // store which could still be empty BEFORE the blocked provider operation has finished.
        await("late import completed") { publications.get() > beforeCompletion }
        await("cancelled original not retained") { media.store.list(owner).isEmpty() }
        assertFalse(main { host.state.canSubmit })
        assertTrue(main { host.state.items.none { it is VideoChatHost.Item.Ready } })
    }

    fun testStalePickerNoPermissionAndMalformedVideoDoesNotAutoFreeFourSlots() {
        main { host.enter("A") }
        val ticket = main { checkNotNull(host.requestSelection()) }
        main { host.enter("B"); host.enter("A") }
        assertFalse(main { host.picked(ticket, listOf(uri)) })
        assertEquals(0, documents.opens.get())
        assertFalse(pick(5))
        assertEquals(0, documents.opens.get())
        source.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
        assertTrue(pick(4))
        await("four failed video imports") { main {
            host.state.items.size == 4 && host.state.items.all { it is VideoChatHost.Item.Failed }
        } }
        assertEquals(4, documents.opens.get())
        assertEquals(0, main { host.state.remaining })
        assertNull(main { host.requestSelection() })
        assertNull(main { host.submit("do not silently omit videos") { fail("must not submit") } })
    }

    /** A tiny real AVC/MP4 generated by the device codec; codec availability is a test prerequisite. */
    private fun encodeSmallMp4(file: File) {
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var muxerStarted = false
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 64, 64).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
            val frame = ByteArray(64 * 64 * 3 / 2) { if (it < 64 * 64) 96.toByte() else 128.toByte() }
            val info = MediaCodec.BufferInfo()
            var inputCount = 0
            var outputEnded = false
            var track = -1
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!outputEnded && System.nanoTime() < deadline) {
                if (inputCount <= 3) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (inputCount == 3) codec.queueInputBuffer(index, 0, 0, 300_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            codec.getInputBuffer(index)!!.apply { clear(); put(frame) }
                            codec.queueInputBuffer(index, 0, frame.size, inputCount * 100_000L, 0)
                        }
                        inputCount++
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted)
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(muxerStarted)
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            check(outputEnded && muxerStarted) { "AVC test fixture encoder did not complete" }
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
            try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
        }
        check(file.length() > 0)
    }
}
