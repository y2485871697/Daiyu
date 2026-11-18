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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
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
    private var liveGeometry: () -> String = { "not sampled" }

    private fun answer(marker: String, lines: Int = 60) =
        List(lines) { "正文第${it + 1}行，检查长回复末尾与输入区的间距。" }.joinToString("\n") + "\n\n$marker"

    private fun show(scale: Float, dark: Boolean = false, running: Boolean = false, plainText: Boolean = false) {
        mainThread {
            attachmentHeight = 0.dp
            liveGeometry = { "not sampled" }
            screen = ChatScreenState("间距回归", turns = listOf(
                ChatTurnUi.User("question", "请写一篇长回复"),
                ChatTurnUi.Assistant("tail", finalText = answer("初始末尾"),
                    status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED, isStreaming = running)),
                composer = ComposerUi(modelLabel = "gpt-6.1-sol",
                    phase = if (running) ComposerPhase.RUNNING else ComposerPhase.IDLE), scrollRequest = ChatScrollRequest(1))
            activity.setContent {
                AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                    val currentDensity = LocalDensity.current.density
                    SideEffect { density = currentDensity }
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("spacing-host")) {
                        DaiyuChatScreen(screen, ChatScreenActions(), hasTurnExtras = { !plainText }, turnExtras = { turn ->
                            if (turn.id == "start-reply") {
                                val viewport = LocalWorkflowDisclosureContext.current?.viewport
                                val clearance = LocalStreamingTextClearance.current
                                SideEffect { liveGeometry = { "held=${clearance?.held} ${viewport?.diagnostics()}" } }
                            }
                        }, composerAttachments = {
                            if (attachmentHeight > 0.dp) Box(Modifier.height(attachmentHeight).testTag("spacing-attachment"))
                        })
                    }
                }
            }
        }
        val host = ui.onNodeWithTag("spacing-host")
        awaitNodeWindowFocus(host)
        dismissIme()
        awaitImeFrame(host, visible = false)
        assertCompactTail("初始末尾")
    }

    private fun assertCompactTail(marker: String) {
        awaitImeFrame(ui.onNodeWithTag("spacing-host"), visible = false)
        // The reserved composer includes its 10dp outer top padding; the surface does not.
        var lastGeometry = "marker not sampled"
        try {
            ui.waitUntil(5_000) {
                if (ui.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isEmpty()) {
                    lastGeometry = "marker absent"
                    false
                } else {
                    val tail = ui.onNodeWithTag("chat-turn:tail").fetchSemanticsNode().layoutInfo.coordinates
                    val surface = ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot
                    val viewport = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot
                    val fullTailBottom = tail.positionInRoot().y + tail.size.height
                    val composerOuterTop = surface.top - 10f * density
                    val gap = (composerOuterTop - fullTailBottom) / density
                    val displayed = ui.onNodeWithText(marker, substring = true).isDisplayed()
                    lastGeometry = "tailTop=${tail.positionInRoot().y} tailHeight=${tail.size.height} " +
                        "surface=$surface viewport=$viewport density=$density outerGapDp=$gap surfaceGapDp=${(surface.top - fullTailBottom) / density} markerDisplayed=$displayed"
                    displayed && gap in (ChatTailClearance.value - 2f)..(ChatTailClearance.value + 2f)
                }
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Compact tail after composer growth: $lastGeometry", timeout)
        }
        awaitVisualIdle()
        awaitVisualIdle()
        ui.onNodeWithText(marker, substring = true).assertIsDisplayed()
        val tail = ui.onNodeWithTag("chat-turn:tail").fetchSemanticsNode().layoutInfo.coordinates
        val viewport = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot
        val surface = ui.onNodeWithTag("chat-composer-surface").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Fixture must overflow the actual viewport", tail.size.height > viewport.height)
        val fullTailBottom = tail.positionInRoot().y + tail.size.height
        val composerOuterTop = surface.top - 10f * density
        val gap = (composerOuterTop - fullTailBottom) / density
        assertTrue("Tail must retain compact clearance above the outer composer: gap=${gap}dp", gap in (ChatTailClearance.value - 2f)..(ChatTailClearance.value + 2f))
        val visibleGap = (surface.top - fullTailBottom) / density
        assertTrue("Visible reply-to-composer spacing stays near 18dp: gap=${visibleGap}dp", visibleGap in 16f..20f)
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
            mainThread { screen = screen.copy(turns = listOf(screen.turns.first(), terminal.copy(status = RunStatus.COMPLETED, isStreaming = false))) }
            assertCompactTail("触摸后末尾")
            ui.onNode(hasContentDescription("复制回答") and hasAnyAncestor(hasTestTag("chat-turn:tail"))).assertIsDisplayed()
        }
    }

    fun testRealStreamingTextKeepsClearanceDuringIntermediateFrames() {
        for (scale in listOf(1f, .8f)) {
            show(scale, running = true)
            val originalComposer = ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot
            ui.mainClock.autoAdvance = false
            try {
                fun assertLiveClearance(marker: String): Boolean {
                    val textNodes = ui.onAllNodes(
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                            hasAnyAncestor(hasTestTag("chat-turn:tail")),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    assertTrue("Running reply must expose actual text", textNodes.isNotEmpty())
                    val lastTextBottom = textNodes.maxOf { node ->
                        val coordinates = node.layoutInfo.coordinates
                        coordinates.positionInRoot().y + coordinates.size.height
                    }
                    val composer = ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot
                    val gap = (composer.top - lastTextBottom) / density
                    assertTrue("Live final text must clear the composer: gap=${gap}dp", gap >= ChatTailClearance.value - 1f / density)
                    assertEquals("Text growth must not move composer left", originalComposer.left, composer.left, 2f)
                    assertEquals("Text growth must not move composer top", originalComposer.top, composer.top, 2f)
                    assertEquals("Text growth must not move composer right", originalComposer.right, composer.right, 2f)
                    assertEquals("Text growth must not move composer bottom", originalComposer.bottom, composer.bottom, 2f)
                    return ui.onNode(hasText(marker, substring = true) and
                        hasAnyAncestor(hasTestTag("chat-turn:tail")), useUnmergedTree = true).isDisplayed()
                }
                repeat(6) { line ->
                    val marker = "流式安全间距第${line + 1}行"
                    ui.runOnUiThread {
                        val tail = screen.turns.last() as ChatTurnUi.Assistant
                        screen = screen.copy(turns = listOf(screen.turns.first(),
                            tail.copy(finalText = tail.finalText + "\n" + marker)))
                    }
                    var sawNewestLine = false
                    repeat(2) {
                        ui.mainClock.advanceTimeByFrame()
                        ui.waitForIdle()
                        sawNewestLine = assertLiveClearance(marker) || sawNewestLine
                    }
                    repeat(6) {
                        ui.mainClock.advanceTimeByFrame()
                        ui.waitForIdle()
                        sawNewestLine = assertLiveClearance(marker) || sawNewestLine
                    }
                    var revealFrames = 0
                    while (!sawNewestLine && revealFrames++ < 90) {
                        ui.mainClock.advanceTimeByFrame()
                        ui.waitForIdle()
                        sawNewestLine = assertLiveClearance(marker)
                    }
                    assertTrue("Newest streaming line must actually be displayed: $marker", sawNewestLine)
                }
            } finally {
                ui.mainClock.autoAdvance = true
            }
            ui.waitForIdle()
            assertCompactTail("流式安全间距第6行")
            assertEquals(0, fixture.opens.get())
        }
    }

    fun testFirstLiveChunkTypesWithoutSnappingTheSentUserAndHistory() {
        show(1f, plainText = true)
        val history = screen.turns
        val pending = ChatTurnUi.User("start-user", "检查首段逐字输出", pending = true)
        val first = "一二三四五六七八九十一二三四五六七八九十一二三"
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread {
                screen = screen.copy(turns = history + pending,
                    composer = screen.composer.copy(phase = ComposerPhase.RUNNING), scrollRequest = ChatScrollRequest(2))
            }
            repeat(60) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
            fun top(id: String): Float = ui.onNodeWithTag("chat-turn:$id").fetchSemanticsNode()
                .layoutInfo.coordinates.positionInRoot().y
            val composer = ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot
            val users = mutableListOf(top("start-user"))
            val histories = mutableListOf(top("tail"))
            val lengths = mutableListOf<Int>()
            val geometryFrames = mutableListOf<String>()
            var paintedPartial = false
            ui.runOnUiThread {
                screen = screen.copy(turns = history + pending.copy(pending = false) +
                    ChatTurnUi.Assistant("start-reply", finalText = first, status = RunStatus.RUNNING, isStreaming = true))
            }
            repeat(90) { frame ->
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                users += top("start-user")
                histories += top("tail")
                val textNodes = ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                    hasAnyAncestor(hasTestTag("chat-turn:start-reply")), useUnmergedTree = true).fetchSemanticsNodes()
                val texts = textNodes.flatMap { it.config[SemanticsProperties.Text] }.map { it.text }
                    .filter { it.isNotEmpty() && first.startsWith(it) }
                lengths += texts.maxOfOrNull { it.length } ?: 0
                if (frame % 10 == 0 || frame == 89) geometryFrames += "frame=$frame ${liveGeometry()}"
                val partial = textNodes.firstOrNull { node ->
                    node.config[SemanticsProperties.Text].any { it.text.length in 1 until first.length && first.startsWith(it.text) }
                }
                if (!paintedPartial && partial != null) {
                    val origin = partial.layoutInfo.coordinates.positionInRoot()
                    val size = partial.layoutInfo.coordinates.size
                    if (origin.y + size.height <= composer.top - (10f + ChatTailClearance.value) * density + 1f) {
                        val clock = ui.mainClock.currentTime
                        val host = ui.onNodeWithTag("spacing-host")
                        val pixels = captureScreenNode(host, advanceAnimationClock = false).toPixelMap()
                        assertEquals("A partial-body screenshot must not advance typing", clock, ui.mainClock.currentTime)
                        val hostOrigin = host.fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
                        val left = (origin.x - hostOrigin.x).toInt().coerceIn(0, pixels.width)
                        val top = (origin.y - hostOrigin.y).toInt().coerceIn(0, pixels.height)
                        val right = (origin.x - hostOrigin.x + size.width).toInt().coerceIn(left, pixels.width)
                        val bottom = (origin.y - hostOrigin.y + size.height).toInt().coerceIn(top, pixels.height)
                        for (y in top until bottom) for (x in left until right) {
                            val pixel = pixels[x, y]
                            if (pixel.red < .6f && pixel.green < .6f && pixel.blue < .6f) paintedPartial = true
                        }
                    }
                }
                assertEquals("The real composer must remain anchored", composer,
                    ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot)
            }
            val userSteps = users.zipWithNext { before, after -> before - after }
            val historySteps = histories.zipWithNext { before, after -> before - after }
            assertTrue("The first chunk must actually type, not become a full baseline: $lengths",
                lengths.any { it in 1 until first.length })
            assertEquals("First chunk must finish: lengths=$lengths userSteps=$userSteps " +
                "historySteps=$historySteps geometry=$geometryFrames", first.length, lengths.last())
            assertTrue("A partial prefix must actually paint inside the body ROI", paintedPartial)
            ui.onNode(hasText(first) and hasAnyAncestor(hasTestTag("chat-turn:start-reply")),
                useUnmergedTree = true).assertIsDisplayed()
            val reply = ui.onNodeWithTag("chat-turn:start-reply").fetchSemanticsNode().layoutInfo.coordinates
            assertTrue("The completed first chunk must retain the original outer-composer clearance",
                reply.positionInRoot().y + reply.size.height <= composer.top - (10f + ChatTailClearance.value) * density + 1f)
            assertTrue("A live reply must not pay its entire new height in one frame: $userSteps",
                userSteps.all { it <= 14f * density + 1f })
            assertTrue("Following must not reverse the sent row: $userSteps", userSteps.all { it >= -1f })
            assertTrue("History and the sent row must share the same outer motion",
                userSteps.zip(historySteps).all { (user, older) -> kotlin.math.abs(user - older) <= 1f })
            assertEquals(0, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = true }
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
