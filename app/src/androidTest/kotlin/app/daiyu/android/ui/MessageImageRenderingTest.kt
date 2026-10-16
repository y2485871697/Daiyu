package app.daiyu.android.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.*
import java.util.concurrent.CopyOnWriteArrayList

class MessageImageRenderingTest : WiredActivityTestCase() {
    private val owner = MessageImageOwner("historical-chat", "historical-user")
    private val reference = MessageImageReference(owner, "00000000-0000-0000-0000-000000000001",
        "image/png", 10, "b".repeat(64), 2, 2)
    private val image = MessageImageUi(owner, reference)

    private fun show(content: @Composable () -> Unit) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
        ui.waitForIdle()
    }

    fun testMissingLoaderIsExplicitAndMixedTextToolImageOrderIsPreserved() {
        val content = listOf(
            MessageContentUi.Text("before", "before image"),
            MessageContentUi.Image(image),
            MessageContentUi.Tool(WorkStepUi("call", "tool marker", "tool detail", WorkStepState.OK)),
            MessageContentUi.Text("after", "after tool"),
        )
        show { MessageContent(content, ChatScreenActions(), null, markdown = false) }
        ui.onNodeWithTag("message-image-unavailable:1", useUnmergedTree = true).assertTextEquals("图片不可用")
        val positions = listOf(
            ui.onNodeWithText("before image", useUnmergedTree = true),
            ui.onNodeWithTag("message-image:1", useUnmergedTree = true),
            ui.onNodeWithText("tool marker", useUnmergedTree = true),
            ui.onNodeWithText("after tool", useUnmergedTree = true),
        ).map { it.fetchSemanticsNode().boundsInRoot.top }
        assertTrue(positions.zipWithNext().all { (first, next) -> first < next })
        ui.onNodeWithContentDescription("消息图片 1").assertDoesNotExist()
        ui.onNodeWithText(reference.id, substring = true).assertDoesNotExist()
        ui.onNodeWithText(owner.conversationId, substring = true).assertDoesNotExist()
    }

    fun testMissingAssetOrResolverFailureNeverRendersDiagnosticsAsAnImage() {
        for (loader in listOf(
            MessageImageLoader { _, _, _ -> null },
            MessageImageLoader { _, _, _ -> error("content://private/path") },
        )) {
            show { MessageImage(image, 1, loader) }
            ui.waitUntil(timeoutMillis = 5_000) {
                ui.onAllNodesWithTag("message-image-unavailable:1").fetchSemanticsNodes().size == 1
            }
            ui.onNodeWithContentDescription("消息图片 1").assertDoesNotExist()
            ui.onNodeWithText("content://", substring = true).assertDoesNotExist()
            ui.onNodeWithText("private/path", substring = true).assertDoesNotExist()
        }
    }

    fun testInjectedReadOnlyLoaderReceivesHistoricalOwnerAndBoundedPreviewRequest() {
        val requests = CopyOnWriteArrayList<Triple<MessageImageOwner, MessageImageReference, Int>>()
        val bitmap = ImageBitmap(2, 2)
        val loader = MessageImageLoader { expected, ref, maxEdge ->
            requests += Triple(expected, ref, maxEdge)
            bitmap
        }
        show { MessageImage(image, 1, loader) }
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodesWithContentDescription("消息图片 1").fetchSemanticsNodes().size == 1
        }
        assertEquals(listOf(Triple(owner, reference, 1024)), requests.toList())
        ui.onNodeWithTag("message-image-unavailable:1").assertDoesNotExist()
    }
}
