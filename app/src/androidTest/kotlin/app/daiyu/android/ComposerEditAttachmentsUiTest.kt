package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.attachments.*
import app.daiyu.android.ui.screens.chat.conversation.ComposerEditAttachments
import app.daiyu.android.ui.screens.chat.conversation.MessageContentUi
import app.daiyu.android.ui.screens.chat.conversation.MessageImageLoader
import app.daiyu.android.ui.screens.chat.conversation.MessageImageUi
import java.util.concurrent.atomic.AtomicInteger

/** Shared chip geometry and selection-only removal; no draft import/delete capability is supplied. */
class ComposerEditAttachmentsUiTest : WiredActivityTestCase() {
    private val owner = MessageImageOwner("history-conversation", "history-message")
    private fun image(id: String) = MessageContentUi.Image(MessageImageUi(owner,
        MessageImageReference(owner, id, "image/png", 128, "a".repeat(64), 2048, 1536)))

    fun testHistoricalImageUsesSharedChipAndRemovalRenumbersAddedImages() {
        var retained by mutableStateOf(listOf(image("00000000-0000-0000-0000-000000000001")))
        val draftOwner = app.daiyu.android.media.ImageAttachmentOwner(owner.conversationId, "new-edit-draft")
        val holder = ImageAttachmentsState(ImageAttachmentDraft(draftOwner, 0))
        val key = holder.beginImport(requireNotNull(holder.requestSelection()), 1).single()
        holder.importSucceeded(key, app.daiyu.android.media.ImageAttachment(draftOwner,
            "00000000-0000-0000-0000-000000000003", "image/png", 64, "b".repeat(64), 640, 480))
        val removals = AtomicInteger()
        val loads = AtomicInteger()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column(Modifier.requiredWidth(400.dp)) {
                    ComposerEditAttachments(retained, emptyList(), MessageImageLoader { expected, reference, edge ->
                        check(expected == owner && reference.owner == owner && edge == 256)
                        loads.incrementAndGet(); ImageBitmap(8, 8)
                    }, enabled = true, onRemoveImage = { item ->
                        removals.incrementAndGet(); retained = retained.filterNot { it == item }
                    })
                    ImageAttachments(holder.state.value, ImageAttachmentsActions({}, {}, {}, {}),
                        { _, _ -> ImageBitmap(8, 8) }, compact = true, numberOffset = retained.size)
                }
            }
        } }
        awaitVisualIdle()
        node("message-edit-image:0").assertIsDisplayed().assertWidthIsEqualTo(188.dp).assertHeightIsEqualTo(44.dp)
        node("message-edit-image-thumbnail:0").assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp)
        node("message-image:1").assertDoesNotExist()
        node("image_attachments_item:${key.id}").assertWidthIsEqualTo(188.dp).assertHeightIsEqualTo(44.dp)
        ui.onNodeWithText("图片 1 · 2048 × 1536", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithText("图片 2 · 640 × 480", useUnmergedTree = true).assertIsDisplayed()
        node("message-edit-image-remove:0").performClick()
        awaitVisualIdle()
        node("message-edit-image:0").assertDoesNotExist()
        ui.onNodeWithText("图片 1 · 640 × 480", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, removals.get())
        assertTrue(loads.get() > 0)
        assertEquals(1, holder.state.value.ready.size)
    }

    fun testUnavailableOriginalRetainsMetadataAndDisabledRemoveCannotChangeSelection() {
        val removals = AtomicInteger()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ComposerEditAttachments(listOf(image("00000000-0000-0000-0000-000000000002")), emptyList(), null,
                    enabled = false, onRemoveImage = { removals.incrementAndGet() })
            }
        } }
        awaitVisualIdle()
        node("message-edit-image:0").assertWidthIsEqualTo(188.dp).assertHeightIsEqualTo(44.dp)
        node("message-edit-image-remove:0").assertIsNotEnabled()
        node("message-edit-image-unavailable:1").assertIsDisplayed()
        ui.onNodeWithText("图片 1 · 2048 × 1536", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, removals.get())
    }
}
