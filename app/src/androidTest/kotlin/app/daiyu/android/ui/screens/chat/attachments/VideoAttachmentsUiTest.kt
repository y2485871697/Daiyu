package app.daiyu.android.ui.screens.chat.attachments

import android.content.Intent
import android.app.Activity
import android.content.ClipData
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.VideoChatHost
import app.daiyu.android.VideoDraftOutcome
import app.daiyu.android.VideoDraftPersistence
import app.daiyu.android.VideoDraftReceipt
import app.daiyu.android.VideoDraftRequest
import app.daiyu.android.VideoDraftMedia
import app.daiyu.android.VideoHostDraftStore
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.media.video.VideoAttachment
import app.daiyu.android.media.video.VideoAttachmentException
import app.daiyu.android.media.video.VideoAttachmentFailure
import app.daiyu.android.media.video.VideoAttachmentOwner
import app.daiyu.android.media.video.VideoPreview
import app.daiyu.android.ui.app.AgentAppTheme
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose buttons -> real ActivityResultRegistry contract parser -> captured Selection -> host.
 * Only provider IO and parent receipt authority are fakes. AndroidVideoAttachments' actual original
 * bytes/decoder are covered by VideoChatHostAndroidTest. Never retain a detached semantics callback.
 */
class VideoAttachmentsUiTest : WiredActivityTestCase() {
    private class ViewLife : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class PickerRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val launches = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.GetMultipleContents)
            check(input == "video/*")
            launches.add(requestCode)
        }
        fun result(code: Int, count: Int = 1) {
            val result = Intent()
            if (count > 0) {
                result.data = Uri.parse("content://video-ui/0")
                if (count > 1) result.clipData = ClipData.newRawUri("videos", result.data).apply {
                    repeat(count - 1) { addItem(ClipData.Item(Uri.parse("content://video-ui/${it + 1}"))) }
                }
            }
            dispatchResult(code, Activity.RESULT_OK, result)
        }
        fun cancel(code: Int) {
            dispatchResult(code, Activity.RESULT_CANCELED, null)
        }
    }
    private class MemoryJournal : VideoDraftPersistence {
        var rows = emptyList<VideoHostDraftStore.Draft>()
        override fun read() = rows
        override fun write(drafts: List<VideoHostDraftStore.Draft>): Boolean { rows = drafts.toList(); return true }
    }
    private class Media : VideoDraftMedia {
        val imports = AtomicInteger()
        val removals = AtomicInteger()
        val originals = ConcurrentHashMap<String, VideoAttachment>()
        var failure: VideoAttachmentFailure? = null
        var wrongPreview = false
        var gate: CountDownLatch? = null
        val jpeg = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                bitmap.recycle()
            }
        }.toByteArray()
        override fun import(owner: VideoAttachmentOwner, uri: Uri, cancellation: CancellationSignal): VideoAttachment {
            check(Looper.myLooper() != Looper.getMainLooper())
            imports.incrementAndGet()
            check(gate?.await(5, TimeUnit.SECONDS) != false)
            failure?.let { throw VideoAttachmentException(it) }
            return VideoAttachment(owner, UUID.randomUUID().toString(), "video/mp4", 256, "a".repeat(64), 1000, 32, 24)
                .also { originals[it.id] = it }
        }
        override fun preview(video: VideoAttachment, maxEdge: Int, cancellation: CancellationSignal): VideoPreview {
            check(Looper.myLooper() != Looper.getMainLooper())
            check(maxEdge in 1..1024)
            return VideoPreview(if (wrongPreview) video.copy(id = UUID.randomUUID().toString()) else video, 8, 8, jpeg)
        }
        override fun list(owner: VideoAttachmentOwner) = originals.values.filter { it.owner == owner }
        override fun remove(video: VideoAttachment) {
            originals.remove(video.id)
            removals.incrementAndGet()
        }
    }

    private lateinit var host: VideoChatHost
    private lateinit var media: Media
    private lateinit var picker: PickerRegistry
    private lateinit var life: ViewLife
    private val mounted = mutableStateOf(true)
    private val text = mutableStateOf("first exact text")
    private val busy = mutableStateOf(false)
    private val conversation = mutableStateOf<String?>(null)
    private var handed: VideoDraftRequest? = null
    private var frozenEditor: VideoDraftText? = null
    private val textRevision = mutableStateOf(1L)
    private var deliver: ((VideoDraftReceipt) -> Boolean)? = null

    override fun setUp() {
        super.setUp()
        mounted.value = true; text.value = "first exact text"; busy.value = false; conversation.value = null
        handed = null; frozenEditor = null; deliver = null; textRevision.value = 1L
        mainThread {
            media = Media()
            host = VideoChatHost(MemoryJournal(), media)
            picker = PickerRegistry()
            life = ViewLife().also { it.registry.currentState = Lifecycle.State.STARTED }
        }
        show()
    }

    override fun tearDown() {
        try {
            media.gate?.countDown()
            mainThread { life.registry.currentState = Lifecycle.State.DESTROYED }
        } finally { super.tearDown() }
    }

    private fun show() {
        mainThread {
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        Column(Modifier.requiredWidth(360.dp)) {
                            if (mounted.value) VideoAttachments(host, conversation.value,
                                VideoDraftText(text.value, 7L, textRevision.value),
                                onHandoff = { request, editor, settle -> handed = request; frozenEditor = editor; deliver = settle },
                                parentBusy = busy.value, viewLifecycleOwner = life)
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }
    private fun pick(count: Int = 1): Int {
        node("video_attachments_select").performClick()
        val code = picker.launches.last()
        mainThread { picker.result(code, count) }
        return code
    }
    private fun awaitReady(): String {
        ui.waitUntil(5_000) {
            var ready = false
            instrumentation.runOnMainSync { ready = host.state.items.singleOrNull() is VideoChatHost.Item.Ready }
            ready
        }
        var id = ""
        mainThread { id = host.state.items.single().key }
        node("video_attachments_ready:$id").assertIsDisplayed()
        return id
    }
    private fun receipt(outcome: VideoDraftOutcome, revisionDelta: Long = 0): VideoDraftReceipt {
        val request = checkNotNull(handed)
        return VideoDraftReceipt(request.conversation, request.owner, request.revision + revisionDelta, request.videos,
            outcome, if (outcome == VideoDraftOutcome.ACCEPTED) request.owner.conversationId else null)
    }
    private fun confirm() {
        node("video_attachments_send").performClick()
        node("video_attachments_confirmation").assertIsDisplayed()
        assertNull(handed)
        node("video_attachments_confirm").performClick()
    }

    fun testActualSelectionContractAndImportingReadyCoverAndRemoval() {
        media.gate = CountDownLatch(1)
        pick()
        var key = ""
        mainThread { key = host.state.items.single().key }
        node("video_attachments_importing:$key").assertIsDisplayed()
        node("video_attachments_send").assertIsNotEnabled()
        media.gate!!.countDown()
        val ready = awaitReady()
        node("video_attachments_thumbnail:$ready").performClick()
        node("video_attachments_preview").assertIsDisplayed()
        node("video_attachments_close_preview").performClick()
        assertEquals(1, media.originals.size)
        node("video_attachments_remove:$ready").performClick()
        node("video_attachments_item:$ready").assertDoesNotExist()
        ui.waitUntil(5_000) { media.originals.isEmpty() }
    }

    fun testImportRemovalButtonHonorsBusyGateAndDiscardsLateOriginal() {
        media.gate = CountDownLatch(1)
        pick()
        ui.waitUntil(5_000) { media.imports.get() == 1 }
        var key = ""
        mainThread { key = host.state.items.single().key }
        node("video_attachments_importing:$key").assertIsDisplayed()
        node("video_attachments_select").assertIsNotEnabled()
        node("video_attachments_send").assertIsNotEnabled()
        mainThread { busy.value = true }
        node("video_attachments_remove:$key").assertIsNotEnabled()
        mainThread { busy.value = false }
        node("video_attachments_remove:$key").assertIsEnabled().performClick()
        node("video_attachments_item:$key").assertDoesNotExist()
        // The fake provider deliberately completes despite cancellation. Wait for cleanup, not
        // just an initially empty originals map, to prove the late result cannot resurrect an item.
        media.gate!!.countDown()
        ui.waitUntil(5_000) { media.removals.get() == 1 }
        assertTrue(media.originals.isEmpty())
        mainThread { assertTrue(host.state.items.isEmpty()) }
        node("video_attachments_send").assertIsNotEnabled()
        assertNull(handed)
    }

    fun testPickerCancellationReleasesButtonGatesWithoutChangingDraft() {
        pick(); val key = awaitReady()
        node("video_attachments_select").performClick()
        val code = picker.launches.last()
        node("video_attachments_selecting").assertIsDisplayed()
        node("video_attachments_select").assertIsNotEnabled()
        node("video_attachments_send").assertIsNotEnabled()
        node("video_attachments_remove:$key").assertIsNotEnabled()
        mainThread { picker.cancel(code) }
        node("video_attachments_selecting").assertDoesNotExist()
        node("video_attachments_notice").assertDoesNotExist()
        node("video_attachments_ready:$key").assertIsDisplayed()
        node("video_attachments_select").assertIsEnabled()
        node("video_attachments_send").assertIsEnabled()
        node("video_attachments_remove:$key").assertIsEnabled()
        assertEquals(1, media.imports.get())
        assertEquals(1, media.originals.size)
        assertNull(handed)
        // A fresh launch after RESULT_CANCELED must work, including an OK-but-empty result.
        pick(0)
        node("video_attachments_selecting").assertDoesNotExist()
        node("video_attachments_send").assertIsEnabled()
        assertEquals(1, media.imports.get())
    }

    fun testConfirmationCancelKeepsDraftAndNextConfirmationFreezesNewText() {
        pick(); val key = awaitReady()
        node("video_attachments_send").performClick()
        node("video_attachments_confirm_text").assertTextEquals("first exact text")
        mainThread { text.value = "edited while confirmation was open"; textRevision.value++ }
        node("video_attachments_cancel_confirmation").performClick()
        node("video_attachments_confirmation").assertDoesNotExist()
        node("video_attachments_ready:$key").assertIsDisplayed()
        node("video_attachments_send").assertIsEnabled()
        assertEquals(1, media.originals.size)
        assertNull(handed)
        assertNull(frozenEditor)
        assertNull(deliver)
        node("video_attachments_send").performClick()
        node("video_attachments_confirm_text").assertTextEquals("edited while confirmation was open")
        node("video_attachments_confirm").performClick()
        assertEquals("edited while confirmation was open", handed!!.text)
        assertEquals(VideoDraftText("edited while confirmation was open", 7L, 2L), frozenEditor)
    }

    fun testLongFrozenCaptionScrollsToEndAndUploadsOriginalText() {
        val original = (1..60).joinToString("\n") { "Frozen caption line $it" } + "\nFINAL FROZEN CAPTION LINE"
        mainThread { text.value = original; textRevision.value = 2L }
        pick(); awaitReady()
        node("video_attachments_send").performClick()
        node("video_attachments_confirm_text").assertTextEquals(original)
        val before = node("video_attachments_confirm_text").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Long caption must overflow its bounded viewport", before.maxValue() > 0f)
        assertEquals(0f, before.value(), 0.5f)
        mainThread { text.value = "new live editor text"; textRevision.value = 3L }
        node("video_attachments_confirm_text").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            assertTrue(scroll(0f, 100_000f))
        }
        ui.waitForIdle()
        val after = node("video_attachments_confirm_text").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(after.maxValue() > 0f)
        assertEquals("The final frozen line must be reachable", after.maxValue(), after.value(), 0.5f)
        node("video_attachments_confirm_text").assertTextEquals(original)
        node("video_attachments_confirm").assertIsDisplayed().performClick()
        assertEquals(original, handed!!.text)
        assertEquals(VideoDraftText(original, 7L, 2L), frozenEditor)
        assertEquals("new live editor text", text.value)
    }

    fun testMismatchedCoverCannotReplaceOrConsumeOriginal() {
        media.wrongPreview = true
        pick(); val ready = awaitReady()
        ui.waitUntil(5_000) {
            ui.onAllNodes(androidx.compose.ui.test.hasTestTag("video_attachments_cover_failed:$ready"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The clickable thumbnail merges its descendants in the default tree.
        ui.onNodeWithTag("video_attachments_cover_failed:$ready", useUnmergedTree = true)
            .assertTextContains("原视频仍保留", substring = true)
        confirm()
        assertEquals(media.originals.values.single(), handed!!.videos.single())
        assertEquals("video/mp4", handed!!.videos.single().mime)
    }

    fun testPickerExcursionBridgesOnlyItsExactStopStart() {
        node("video_attachments_select").performClick()
        val code = picker.launches.last()
        mainThread {
            life.registry.currentState = Lifecycle.State.CREATED
            assertFalse(host.state.visible)
            life.registry.currentState = Lifecycle.State.STARTED
            picker.result(code)
        }
        awaitReady()
        assertEquals(1, media.imports.get())
    }

    fun testSecondStopMakesStillRegisteredCallbackStale() {
        node("video_attachments_select").performClick()
        val old = picker.launches.last()
        mainThread {
            life.registry.currentState = Lifecycle.State.CREATED
            life.registry.currentState = Lifecycle.State.STARTED
            // Only ONE picker excursion can bridge a stop/start. A second is real departure.
            life.registry.currentState = Lifecycle.State.CREATED
            life.registry.currentState = Lifecycle.State.STARTED
            picker.result(old) // Callback is still registered: exercises host ticket rejection.
        }
        assertEquals(0, media.imports.get())
        node("video_attachments_notice").assertTextContains("旧草稿", substring = true)
        pick(); awaitReady()
    }

    fun testLeaveReturnAndOldResultCannotBecomeNewTicket() {
        node("video_attachments_select").performClick()
        val old = picker.launches.last()
        mainThread { mounted.value = false }
        ui.waitForIdle()
        mainThread { mounted.value = true }
        ui.waitForIdle()
        node("video_attachments_select").performClick()
        val fresh = picker.launches.last()
        assertTrue(old != fresh)
        mainThread { picker.result(old) }
        assertEquals(0, media.imports.get())
        node("video_attachments_selecting").assertIsDisplayed()
        mainThread { picker.result(fresh) }
        awaitReady()
        assertEquals(1, media.imports.get())
    }

    fun testConversationRoundTripRejectsLateSelection() {
        node("video_attachments_select").performClick()
        val old = picker.launches.last()
        mainThread { conversation.value = "other" }
        ui.waitForIdle()
        mainThread { conversation.value = null }
        ui.waitForIdle()
        mainThread { picker.result(old) }
        assertEquals(0, media.imports.get())
        pick(); awaitReady()
    }

    fun testRealActivityRecreationRetainsHostButNeverRebindsOldPicker() {
        node("video_attachments_select").performClick()
        val old = picker.launches.last()
        activity = recreateActivity(activity)
        show() // Same parent-owned host; no second journal writer, new route binding.
        node("video_attachments_select").performClick()
        val fresh = picker.launches.last()
        mainThread { picker.result(old) }
        assertEquals(0, media.imports.get())
        mainThread { picker.result(fresh) }
        awaitReady()
    }

    fun testParentRejectionAndUnknownKeepOriginalsAndTextIsFrozen() {
        pick(); val key = awaitReady()
        node("video_attachments_send").performClick()
        mainThread { text.value = "edited after confirmation opened"; textRevision.value++ }
        node("video_attachments_confirm_text").assertTextContains("first exact text")
        node("video_attachments_confirm").performClick()
        assertEquals("first exact text", handed!!.text)
        assertEquals(VideoDraftText("first exact text", 7L, 1L), frozenEditor)
        assertEquals("video/mp4", handed!!.videos.single().mime)
        mainThread { assertFalse(deliver!!(receipt(VideoDraftOutcome.ACCEPTED, 1))) }
        node("video_attachments_fenced").assertIsDisplayed()
        mainThread { assertTrue(deliver!!(receipt(VideoDraftOutcome.UNKNOWN))) }
        node("video_attachments_remove:$key").assertIsNotEnabled()
        assertEquals(1, media.originals.size)
        mainThread { assertTrue(deliver!!(receipt(VideoDraftOutcome.REJECTED))) }
        node("video_attachments_ready:$key").assertIsDisplayed()
        assertEquals(1, media.originals.size)
        assertEquals("edited after confirmation opened", text.value)
    }

    fun testBusyAndAcceptedAfterDepartureNeverClearEditor() {
        pick(); awaitReady()
        mainThread { busy.value = true }
        node("video_attachments_select").assertIsNotEnabled()
        node("video_attachments_send").assertIsNotEnabled()
        mainThread { busy.value = false }
        confirm()
        mainThread { mounted.value = false; text.value = "new editor revision" }
        ui.waitForIdle()
        mainThread { assertTrue(deliver!!(receipt(VideoDraftOutcome.ACCEPTED))) }
        ui.waitUntil(5_000) { media.originals.isEmpty() }
        assertEquals("new editor revision", text.value)
        mainThread { conversation.value = handed!!.owner.conversationId; mounted.value = true }
        ui.waitForIdle()
        node("video_attachments_send").assertIsNotEnabled()
    }

    fun testOversizedSelectionRejectsAllAndFailureTextDoesNotMislabelLimits() {
        pick(5)
        node("video_attachments_notice").assertTextContains("本次最多还能选择 4 个视频；未导入任何视频")
        assertEquals(0, media.imports.get())
        media.failure = VideoAttachmentFailure.UNSUPPORTED_VIDEO
        pick()
        ui.waitUntil(5_000) {
            var failed = false
            instrumentation.runOnMainSync { failed = host.state.items.singleOrNull() is VideoChatHost.Item.Failed }
            failed
        }
        var key = ""
        mainThread { key = host.state.items.single().key }
        node("video_attachments_failed:$key").assertTextContains("暂不支持此视频类型", substring = true)
        assertTrue(videoFailureText(VideoAttachmentFailure.TOO_LARGE).contains("时长或分辨率"))
        node("video_attachments_remove:$key").performClick()
        node("video_attachments_item:$key").assertDoesNotExist()
    }
}
