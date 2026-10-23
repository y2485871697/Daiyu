package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme

class ChatMessageSpacingTest : WiredActivityTestCase() {
    private var screen by mutableStateOf(ChatScreenState("间距回归"))
    private var attachmentHeight by mutableStateOf(0.dp)
    private var density = 1f

    private fun answer(marker: String, lines: Int = 60) =
        List(lines) { "正文第${it + 1}行，检查长回复末尾与输入区的间距。" }.joinToString("\n") + "\n\n$marker"

    private fun show(scale: Float, dark: Boolean = false, running: Boolean = false) {
        mainThread {
            attachmentHeight = 0.dp
            screen = ChatScreenState("间距回归", turns = listOf(
                ChatTurnUi.User("question", "请写一篇长回复"),
                ChatTurnUi.Assistant("tail", finalText = answer("初始末尾"),
                    status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED)),
                composer = ComposerUi(modelLabel = "gpt-6.1-sol"), scrollRequest = ChatScrollRequest(1))
            activity.setContent {
                AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                    val currentDensity = LocalDensity.current.density
                    SideEffect { density = currentDensity }
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("spacing-host")) {
                        DaiyuChatScreen(screen, ChatScreenActions(), composerAttachments = {
                            if (attachmentHeight > 0.dp) Box(Modifier.height(attachmentHeight).testTag("spacing-attachment"))
                        })
                    }
                }
            }
        }
        assertCompactTail("初始末尾")
    }

    private fun assertCompactTail(marker: String) {
        ui.waitUntil(5_000) {
            if (ui.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isEmpty()) {
                false
            } else {
                val tail = ui.onNodeWithTag("chat-turn:tail").fetchSemanticsNode().layoutInfo.coordinates
                val surface = ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot
                val fullTailBottom = tail.positionInRoot().y + tail.size.height
                val gap = (surface.top - fullTailBottom) / density
                ui.onNodeWithText(marker, substring = true).isDisplayed() && gap in 8f..28f
            }
        }
        awaitVisualIdle()
        awaitVisualIdle()
        ui.onNodeWithText(marker, substring = true).assertIsDisplayed()
        val tail = ui.onNodeWithTag("chat-turn:tail").fetchSemanticsNode().layoutInfo.coordinates
        val viewport = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot
        val surface = ui.onNodeWithTag("chat-composer-surface").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Fixture must overflow the actual viewport", tail.size.height > viewport.height)
        val fullTailBottom = tail.positionInRoot().y + tail.size.height
        val gap = (surface.top - fullTailBottom) / density
        assertTrue("Tail must clear the composer without the old empty strip: gap=${gap}dp", gap in 8f..28f)
        ui.onNodeWithContentDescription("上一条用户消息").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testCompletedLongReplyHasCompactSpacingAcrossThemesAndInterfaceScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(scale, dark)
            ui.onNode(hasContentDescription("复制回答") and hasAnyAncestor(hasTestTag("chat-turn:tail"))).assertIsDisplayed()
        }
    }

    fun testRealComposerGrowthAndAttachmentRemovalKeepTheTailAboveItsSurface() {
        for (scale in listOf(1f, .8f)) {
            show(scale)
            val database = fixture.database.exportBackup()
            fun surface() = ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot
            val before = surface()
            mainThread { screen = screen.copy(composer = screen.composer.copy(text = "多行草稿\n".repeat(5))) }
            assertCompactTail("初始末尾")
            assertTrue("Multiline text must actually grow the real composer", surface().height > before.height + 24f * density)
            val multiline = surface()
            mainThread { attachmentHeight = 48.dp }
            assertCompactTail("初始末尾")
            assertTrue("Attachment slot must contribute measured height", surface().height > multiline.height + 40f * density)
            assertEquals("Composer bottom stays anchored", before.bottom, surface().bottom, 2f)
            mainThread {
                attachmentHeight = 0.dp
                screen = screen.copy(composer = screen.composer.copy(text = ""))
            }
            assertCompactTail("初始末尾")
            assertEquals(before.height, surface().height, 2f)
            assertEquals(database, fixture.database.exportBackup())
        }
    }

    fun testSameItemStreamingGrowthFollowsUntilTouchAndExplicitBottomRestoresIt() {
        for (scale in listOf(1f, .8f)) {
            show(scale, running = true)
            val first = screen.turns.last() as ChatTurnUi.Assistant
            mainThread { screen = screen.copy(turns = listOf(screen.turns.first(),
                first.copy(finalText = first.finalText + "\n\n" + answer("流式增高末尾", 30)))) }
            assertCompactTail("流式增高末尾")
            ui.onNodeWithTag("chat-messages").performTouchInput { click(center) }
            val grown = screen.turns.last() as ChatTurnUi.Assistant
            mainThread { screen = screen.copy(turns = listOf(screen.turns.first(),
                grown.copy(finalText = grown.finalText + "\n\n" + answer("触摸后末尾", 30)))) }
            ui.waitUntil(5_000) {
                ui.onAllNodesWithText("触摸后末尾", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            awaitVisualIdle()
            ui.onNodeWithText("触摸后末尾", substring = true).assertIsNotDisplayed()
            mainThread { screen = screen.copy(scrollRequest = ChatScrollRequest(2)) }
            assertCompactTail("触摸后末尾")
            val terminal = screen.turns.last() as ChatTurnUi.Assistant
            mainThread { screen = screen.copy(turns = listOf(screen.turns.first(), terminal.copy(status = RunStatus.COMPLETED))) }
            assertCompactTail("触摸后末尾")
            ui.onNode(hasContentDescription("复制回答") and hasAnyAncestor(hasTestTag("chat-turn:tail"))).assertIsDisplayed()
        }
    }

    fun testShortReplyKeepsTopAlignmentRatherThanBeingForcedAgainstComposer() {
        show(1f)
        mainThread { screen = screen.copy(turns = listOf(ChatTurnUi.Assistant("short", finalText = "短回复"))) }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("短回复").fetchSemanticsNodes().isNotEmpty() }
        awaitVisualIdle()
        val viewport = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot
        val short = ui.onNodeWithTag("chat-turn:short").fetchSemanticsNode().boundsInRoot
        val surface = ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot
        assertTrue("Short content keeps normal top inset", (short.top - viewport.top) / density in 8f..14f)
        assertTrue("Short reply remains above the composer", short.bottom < surface.top)
        ui.onNodeWithContentDescription("上一条用户消息").assertDoesNotExist()
    }
}
