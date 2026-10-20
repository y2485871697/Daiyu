package app.daiyu.android.ui.screens.chat.attachments

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import app.daiyu.android.ui.screens.chat.conversation.ChatComposer
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuLayer
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
import app.daiyu.android.ui.screens.chat.conversation.ComposerUi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.concurrent.atomic.AtomicBoolean

/** Component-only instrumentation. No SAF launcher, model request or production controller is used. */
class ImageAttachmentsUiTest : WiredActivityTestCase() {
    private val owner = ImageAttachmentOwner("component-conversation", "component-draft")
    private val draft = ImageAttachmentDraft(owner, 1)
    private var selection: ImageAttachmentSelection? = null

    override fun setUp() {
        super.setUp()
        selection = null
    }

    private fun image(id: String) = ImageAttachment(owner, id, "image/png", 128, "digest-$id", 32, 24)

    private fun show(holder: ImageAttachmentsState, loader: ImageAttachmentPreviewLoader = { _, _ -> ImageBitmap(8, 8) }) {
        mainThread {
            activity.setContent {
                val state by holder.state.collectAsState()
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.requiredWidth(320.dp)) {
                        ImageAttachments(state, ImageAttachmentsActions(
                            onSelect = { renderedDraft -> selection = holder.requestSelection(renderedDraft) },
                            onPreview = { holder.openPreview(it) },
                            onRemove = { holder.remove(it) },
                            onDismissPreview = { holder.closePreview(it) },
                        ), loader)
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun showComposer(holder: ImageAttachmentsState) {
        mainThread { activity.setContent {
            val state by holder.state.collectAsState()
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ChatMenuLayer { Box(Modifier.fillMaxSize()) {
                    ChatComposer(ComposerUi(imageSendAvailable = state.canSubmit,
                        imageSendBlocked = state.items.isNotEmpty() && !state.canSubmit), ChatScreenActions(),
                        Modifier.requiredWidth(320.dp).align(Alignment.BottomCenter), attachmentContent = {
                            ImageAttachments(state, ImageAttachmentsActions(
                                onSelect = { selection = holder.requestSelection(it) },
                                onPreview = { holder.openPreview(it) }, onRemove = { holder.remove(it) },
                                onDismissPreview = { holder.closePreview(it) },
                            ), { _, _ -> ImageBitmap(8, 8) }, compact = true)
                        })
                } }
            }
        } }
        ui.waitForIdle()
    }

    fun testCompactImagesRaiseOnlyComposerTopAndLastRemovalRestoresHeight() {
        val holder = ImageAttachmentsState(draft)
        showComposer(holder)
        node("image_attachments").assertDoesNotExist()
        val empty = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
        lateinit var key: ImageAttachmentItemKey
        mainThread {
            key = holder.beginImport(requireNotNull(holder.requestSelection()), 1).single()
            holder.importSucceeded(key, image("inline"))
        }
        val filled = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
        val item = node("image_attachments_item:${key.id}").assertIsDisplayed()
            .assertHeightIsEqualTo(44.dp).assertWidthIsEqualTo(188.dp).fetchSemanticsNode().boundsInRoot
        val input = node("chat-draft").fetchSemanticsNode().boundsInRoot
        assertTrue("Attachment adds only a compact row", filled.top < empty.top && filled.height > empty.height)
        assertTrue(kotlin.math.abs(filled.bottom - empty.bottom) <= 1f)
        assertTrue(item.top >= filled.top && item.bottom <= input.top)
        ui.onNode(hasTestTag("image_attachments") and hasAnyAncestor(hasTestTag("chat-composer-surface"))).assertExists()
        node("image_attachments_select").assertDoesNotExist()
        node("chat-send").assertIsEnabled()
        node("image_attachments_thumbnail:${key.id}").performClick()
        node("image_attachments_preview").assertIsDisplayed()
        node("image_attachments_close_preview").performClick()
        node("image_attachments_remove:${key.id}").performClick()
        node("image_attachments").assertDoesNotExist()
        val restored = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(restored.top - empty.top) <= 1f)
        node("chat-send").assertIsNotEnabled()
    }

    fun testCompactImportFailureRemainsRemovableAndSelectionCancelHasNoEmptyPanel() {
        val holder = ImageAttachmentsState(draft)
        showComposer(holder)
        lateinit var request: ImageAttachmentSelection
        mainThread { request = requireNotNull(holder.requestSelection()) }
        node("image_attachments_selecting").assertIsDisplayed()
        mainThread { holder.cancelSelection(request) }
        node("image_attachments").assertDoesNotExist()
        lateinit var key: ImageAttachmentItemKey
        mainThread { key = holder.beginImport(requireNotNull(holder.requestSelection()), 1).single() }
        node("image_attachments_importing:${key.id}").assertIsDisplayed()
        node("chat-send").assertIsNotEnabled()
        mainThread { holder.importFailed(key, ImageAttachmentFailure.SOURCE_UNAVAILABLE) }
        node("image_attachments_failed:${key.id}").assertIsDisplayed()
        node("image_attachments_remove:${key.id}").performClick()
        node("image_attachments").assertDoesNotExist()
    }

    fun testSelectionImportFailureAndRemovalAreRealBusinessUi() {
        val holder = ImageAttachmentsState(draft)
        show(holder)
        node("image_attachments_select").assertIsDisplayed().assertTextContains("选择图片").performClick()
        node("image_attachments_selecting").assertIsDisplayed()
        node("image_attachments_select").assertIsNotEnabled()
        lateinit var keys: List<ImageAttachmentItemKey>
        mainThread { keys = holder.beginImport(requireNotNull(selection), 2) }
        node("image_attachments_importing:${keys[0].id}").assertIsDisplayed()
        node("image_attachments_importing:${keys[1].id}").assertExists()
        mainThread {
            holder.importSucceeded(keys[0], image("success"))
            holder.importFailed(keys[1], ImageAttachmentFailure.SOURCE_UNAVAILABLE)
        }
        node("image_attachments_failed:${keys[1].id}").assertTextContains("无法读取图片，请重新选择")
        node("image_attachments_remove:${keys[1].id}").assertTextContains("移除第 2 项").performClick()
        node("image_attachments_item:${keys[1].id}").assertDoesNotExist()
        node("image_attachments_item:${keys[0].id}").assertIsDisplayed()
        assertEquals(listOf("success"), holder.state.value.ready.map { it.attachment.id })
    }

    fun testPreviewOpensAndClosesWithoutConsumingImageAndRemoveIsPerItem() {
        val holder = ImageAttachmentsState(draft, listOf(image("first"), image("second")))
        val first = holder.state.value.ready.first().key
        val second = holder.state.value.ready.last().key
        show(holder)
        node("image_attachments_thumbnail:${first.id}").performClick()
        node("image_attachments_preview").assertIsDisplayed()
        ui.onNode(androidx.compose.ui.test.hasText("重置缩放")).assertIsDisplayed().performClick()
        node("image_attachments_preview").assertIsDisplayed()
        node("image_attachments_close_preview").assertTextContains("关闭").performClick()
        node("image_attachments_preview").assertDoesNotExist()
        assertEquals(2, holder.state.value.ready.size)
        node("image_attachments_remove:${first.id}").performClick()
        node("image_attachments_item:${first.id}").assertDoesNotExist()
        node("image_attachments_item:${second.id}").assertIsDisplayed()
        assertEquals(listOf("second"), holder.state.value.ready.map { it.attachment.id })
    }

    fun testPreviewFailureIsRecoverableAndNeverDropsAttachment() {
        val holder = ImageAttachmentsState(draft, listOf(image("kept")))
        val key = holder.state.value.ready.single().key
        val allowDecode = AtomicBoolean(false)
        show(holder) { _, maxEdge ->
            if (maxEdge == 2048 && !allowDecode.get()) throw IllegalStateException("test full-preview decode failure")
            ImageBitmap(8, 8)
        }
        node("image_attachments_thumbnail:${key.id}").performClick()
        mainThread { assertEquals("thumbnail click must open the full preview", key, holder.state.value.preview) }
        node("image_attachments_preview").assertIsDisplayed()
        val inDialog = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("image_attachments_preview"))
        val failed = androidx.compose.ui.test.hasTestTag("image_attachments_preview_failed:${key.id}") and inDialog
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodes(failed).fetchSemanticsNodes().isNotEmpty() }
        node("image_attachments_preview").assertIsDisplayed()
        allowDecode.set(true)
        ui.onNode(androidx.compose.ui.test.hasText("重试预览") and inDialog).performClick()
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodes(failed).fetchSemanticsNodes().isEmpty() }
        node("image_attachments_close_preview").performClick()
        assertEquals(listOf("kept"), holder.state.value.ready.map { it.attachment.id })
    }

    fun testSendReceiptLocksRemovalAndOnlyAcceptedConsumes() {
        val holder = ImageAttachmentsState(draft, listOf(image("kept")))
        val key = holder.state.value.ready.single().key
        show(holder)
        lateinit var submission: ImageAttachmentSubmission
        mainThread { submission = requireNotNull(holder.beginSubmission()) }
        node("image_attachments_submitting").assertIsDisplayed()
        node("image_attachments_remove:${key.id}").assertIsNotEnabled()
        mainThread { holder.finishSubmission(submission, ImageAttachmentAcceptance.Unknown) }
        node("image_attachments_item:${key.id}").assertIsDisplayed()
        mainThread {
            submission = requireNotNull(holder.beginSubmission())
            holder.finishSubmission(submission, ImageAttachmentAcceptance.Accepted)
        }
        node("image_attachments_item:${key.id}").assertDoesNotExist()
    }

    fun testActivityRecreationRestoresOnlyReadyImagesAndRejectsOldCallbacks() {
        val before = ImageAttachmentsState(draft, listOf(image("persisted")))
        val sent = requireNotNull(before.beginSubmission())
        val importKey = before.beginImport(requireNotNull(before.requestSelection()), 1).single()
        val picker = requireNotNull(before.requestSelection())
        val saved = before.state.value.ready.map { it.attachment }
        show(before)
        activity = recreateActivity(activity)
        val restored = ImageAttachmentsState(draft, saved)
        show(restored)
        mainThread {
            assertFalse(restored.finishSubmission(sent, ImageAttachmentAcceptance.Accepted))
            assertFalse(restored.importSucceeded(importKey, image("late")))
            assertTrue(restored.beginImport(picker, 1).isEmpty())
        }
        node("image_attachments_selecting").assertDoesNotExist()
        node("image_attachments_submitting").assertDoesNotExist()
        assertEquals(listOf("persisted"), restored.state.value.ready.map { it.attachment.id })
    }

    fun testOwnerChangeClosesOldPreviewAndOldImportCannotPopulateNewDraft() {
        val holder = ImageAttachmentsState(draft, listOf(image("old")))
        val key = holder.state.value.ready.single().key
        val oldSlot = holder.beginImport(requireNotNull(holder.requestSelection()), 1).single()
        show(holder)
        node("image_attachments_thumbnail:${key.id}").performClick()
        node("image_attachments_preview").assertIsDisplayed()
        mainThread {
            holder.activate(ImageAttachmentDraft(ImageAttachmentOwner("new-conversation", "new-draft"), 1))
            assertFalse(holder.importSucceeded(oldSlot, image("late")))
        }
        node("image_attachments_preview").assertDoesNotExist()
        node("image_attachments_item:${key.id}").assertDoesNotExist()
        node("image_attachments_item:${oldSlot.id}").assertDoesNotExist()
        assertTrue(holder.state.value.ready.isEmpty())
    }
}
