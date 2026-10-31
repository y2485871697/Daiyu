package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.media.LocalOpenChatMediaPreview
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.abs

/** Cloud/device geometry declarations; no emulator or local build is required to author these. */
class MessageMediaLayoutGeometryTest : WiredActivityTestCase() {
    private val owner = MessageImageOwner("layout-chat", "layout-message")
    private var density = 1f
    private val opened = mutableListOf<String>()

    private fun image(number: Int, expected: MessageImageOwner = owner): MessageImageUi = MessageImageUi(expected,
        MessageImageReference(owner, "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
            "image/png", 24, "a".repeat(64), 2, 3))

    private fun mount(content: @Composable () -> Unit) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    CompositionLocalProvider(LocalOpenChatMediaPreview provides { key: String -> opened += key }) {
                        Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { content() }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun SemanticsNodeInteraction.unclippedBounds(): Rect {
        val bounds = getUnclippedBoundsInRoot()
        return Rect(bounds.left.value * density, bounds.top.value * density,
            bounds.right.value * density, bounds.bottom.value * density)
    }

    private fun bounds(number: Int): Rect = ui.onNodeWithTag("message-image:$number", useUnmergedTree = true)
        .unclippedBounds()

    private fun waitForImages(count: Int) {
        ui.waitUntil(timeoutMillis = 5_000) {
            (1..count).all { number ->
                ui.onAllNodesWithContentDescription("消息图片 $number", useUnmergedTree = true)
                    .fetchSemanticsNodes().size == 1
            }
        }
    }

    private fun nearDp(expected: Float, actualPixels: Float) = assertTrue(
        "expected ${expected}dp, got ${actualPixels / density}dp", abs(expected * density - actualPixels) <= 2f)

    fun testLongTextExpandsIndependentlyAndSingleUsesDecodedNotReferenceDimensions() {
        val content = listOf(MessageContentUi.Text("long", "长文字保持正常宽度与完整行数，不受窄图约束。".repeat(12)),
            MessageContentUi.Image(image(1)), MessageContentUi.Text("after", "图片之后的文字"))
        mount {
            MessageContent(content, ChatScreenActions(), MessageImageLoader { _, _, _ -> ImageBitmap(100, 200) },
                markdown = false, textBody = { text, modifier -> Text(text.text, modifier) })
        }
        waitForImages(1)
        val text = ui.onNodeWithTag("message-content-text:0", useUnmergedTree = true)
        val textBounds = text.unclippedBounds()
        val photo = bounds(1)
        nearDp(180f, photo.width); nearDp(360f, photo.height)
        assertTrue(textBounds.width > photo.width)
        assertTrue(abs(textBounds.left - photo.left) <= 1f)
        assertTrue(photo.top >= textBounds.bottom)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertFalse(layouts.single().hasVisualOverflow)
        val after = ui.onNodeWithTag("message-content-text:2", useUnmergedTree = true).unclippedBounds()
        assertTrue(after.top >= photo.bottom)
    }

    fun testTwoPortraitsShareARowButTextSeparatesTheNextImage() {
        val content = listOf(MessageContentUi.Image(image(1)), MessageContentUi.Image(image(2)),
            MessageContentUi.Text("barrier", "不可跨越的文字"), MessageContentUi.Image(image(3)))
        mount {
            MessageContent(content, ChatScreenActions(), MessageImageLoader { _, _, _ -> ImageBitmap(100, 200) },
                markdown = false, textBody = { text, modifier -> Text(text.text, modifier) })
        }
        waitForImages(3)
        val a = bounds(1); val b = bounds(2); val c = bounds(3)
        nearDp(8f, b.left - a.right)
        assertTrue(abs(a.top - b.top) <= 1f)
        assertTrue(a.left < b.left)
        val barrier = ui.onNodeWithTag("message-content-text:2", useUnmergedTree = true).unclippedBounds()
        assertTrue(barrier.top >= maxOf(a.bottom, b.bottom))
        assertTrue(c.top >= barrier.bottom)
        assertTrue(abs(c.left - a.left) <= 1f)
    }

    fun testFourImagesFormRowMajorTwoColumnGrid() {
        mount {
            MessageContent((1..4).map { MessageContentUi.Image(image(it)) }, ChatScreenActions(),
                MessageImageLoader { _, _, _ -> ImageBitmap(100, 100) }, markdown = false)
        }
        waitForImages(4)
        val a = bounds(1); val b = bounds(2); val c = bounds(3); val d = bounds(4)
        nearDp(8f, b.left - a.right); nearDp(8f, c.top - a.bottom)
        assertTrue(abs(a.top - b.top) <= 1f && abs(c.top - d.top) <= 1f)
        assertTrue(abs(a.left - c.left) <= 1f && abs(b.left - d.left) <= 1f)
        nearDp(156f, a.width); nearDp(156f, a.height)
    }

    fun testTwoLandscapeImagesStackWithoutFullWidthBackingOrAspectDistortion() {
        mount {
            MessageContent((1..2).map { MessageContentUi.Image(image(it)) }, ChatScreenActions(),
                MessageImageLoader { _, _, _ -> ImageBitmap(200, 100) }, markdown = false)
        }
        waitForImages(2)
        val a = bounds(1); val b = bounds(2)
        nearDp(320f, a.width); nearDp(160f, a.height)
        nearDp(8f, b.top - a.bottom)
        assertTrue(abs(a.left - b.left) <= 1f)
    }

    fun testReadyImageEntersMixedOriginalViewerAndCompactStaysSeventyTwoDp() {
        val item = image(1)
        mount {
            Box(Modifier.width(72.dp)) {
                MessageImage(item, 1, MessageImageLoader { _, _, _ -> ImageBitmap(100, 200) }, compact = true)
            }
        }
        waitForImages(1)
        nearDp(72f, bounds(1).width); nearDp(72f, bounds(1).height)
        ui.onNodeWithTag("message-image:1", useUnmergedTree = true).performClick()
        ui.runOnIdle { assertEquals(listOf(messageImagePreviewKey(item)), opened) }
        ui.onNodeWithTag("message-image-expanded:1").assertDoesNotExist()
    }

    fun testOwnerMismatchAndUnavailableNeverOpenViewerOrExposeResolverDetails() {
        var calls = 0
        mount {
            Column {
                MessageImage(image(1, MessageImageOwner("forged-chat", "forged-message")), 1,
                    MessageImageLoader { _, _, _ -> calls++; ImageBitmap(2, 2) })
                MessageImage(image(2), 2, MessageImageLoader { _, _, _ -> error("content://private/path") })
            }
        }
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodesWithTag("message-image-unavailable:2", useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        ui.onNodeWithTag("message-image:1", useUnmergedTree = true).assertIsNotEnabled()
        ui.onNodeWithTag("message-image:2", useUnmergedTree = true).assertIsNotEnabled()
        ui.onNodeWithText("private/path", substring = true).assertDoesNotExist()
        ui.onNodeWithText(owner.conversationId, substring = true).assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, calls); assertTrue(opened.isEmpty()) }
    }
}
