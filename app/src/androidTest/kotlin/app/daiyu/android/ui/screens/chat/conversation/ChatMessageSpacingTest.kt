package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
    override val requiresWindowDraw: Boolean = true
    private var screen by mutableStateOf(ChatScreenState("间距回归"))
    private var attachmentHeight by mutableStateOf(0.dp)
    private var density = 1f
    private var liveGeometry: () -> String = { "not sampled" }
    private var tailGeometry: () -> String = { "not sampled" }
    private val paintSlots = MarkdownPaintSlotsProbe()
    private var nativeHostCoordinates: androidx.compose.ui.layout.LayoutCoordinates? = null

    private fun paintDump(key: String): String {
        var result = "not sampled"
        instrumentation.runOnMainSync {
            result = runCatching { paintSlots.sample(key) }.getOrElse { "probeFailure=${it.javaClass.simpleName}" }
        }
        return result
    }

    private fun traceFirstGrowth(label: String, block: () -> Unit) {
        // Main-thread-owned diagnostics only; this does not publish Compose state.
        val frames = java.util.ArrayDeque<String>(32)
        var remaining = 31
        lateinit var choreographer: android.view.Choreographer
        lateinit var admission: MarkdownPaintSlotsProbe.AdmissionTrace
        fun sample(stage: String, frameNanos: Long?) {
            val entry = runCatching {
                androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                    "$stage frameNanos=$frameNanos monoNanos=${System.nanoTime()} " +
                        "${tailGeometry()}\n${paintSlots.sample("tail")}"
                }
            }.getOrElse { "$stage probeFailure=${it.javaClass.name}: ${it.message}" }
            if (frames.size == 32) frames.removeFirst()
            frames.addLast(entry)
        }
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                // Entry sample, not an assertion that this frame has drawn.
                sample("preTraversal", frameTimeNanos)
                if (--remaining > 0) choreographer.postFrameCallback(this)
            }
        }
        instrumentation.runOnMainSync {
            admission = paintSlots.traceAdmission { ui.mainClock.currentTime }
            choreographer = android.view.Choreographer.getInstance()
            choreographer.postFrameCallback(callback)
        }
        var original: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            original = failure
            throw failure
        } finally {
            try {
                var dump = ""
                var admissionDump = ""
                instrumentation.runOnMainSync {
                    choreographer.removeFrameCallback(callback)
                    admissionDump = admission.finish()
                    if (original != null) {
                        sample("failure", null)
                        dump = frames.toList().asReversed().joinToString("\n---\n")
                    }
                }
                original?.addSuppressed(AssertionError(
                    "Same-item append diagnostics ($label; max 32 samples)\n$admissionDump\n$dump"))
            } catch (diagnosticFailure: Throwable) {
                val failure = original
                if (failure != null) failure.addSuppressed(diagnosticFailure) else throw diagnosticFailure
            }
        }
    }

    private fun answer(marker: String, lines: Int = 60) =
        List(lines) { "正文第${it + 1}行，检查长回复末尾与输入区的间距。" }.joinToString("\n") + "\n\n$marker"

    private fun show(scale: Float, dark: Boolean = false, running: Boolean = false, plainText: Boolean = false) {
        mainThread {
            attachmentHeight = 0.dp
            liveGeometry = { "not sampled" }
            tailGeometry = { "not sampled" }
            screen = ChatScreenState("间距回归", turns = listOf(
                ChatTurnUi.User("question", "请写一篇长回复"),
                ChatTurnUi.Assistant("tail", finalText = answer("初始末尾"),
                    status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED, isStreaming = running)),
                composer = ComposerUi(modelLabel = "gpt-6.1-sol",
                    phase = if (running) ComposerPhase.RUNNING else ComposerPhase.IDLE), scrollRequest = ChatScrollRequest(1))
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                    val currentDensity = LocalDensity.current.density
                    SideEffect { density = currentDensity }
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("spacing-host")) {
                        DaiyuChatScreen(screen, ChatScreenActions(), hasTurnExtras = { !plainText }, turnExtras = { turn ->
                            if (turn.id == "tail" || turn.id == "start-reply") paintSlots.Capture(turn.id)
                            if (turn.id == "start-reply" || turn.id == "tail") {
                                val viewport = LocalWorkflowDisclosureContext.current?.viewport
                                val clearance = LocalStreamingTextClearance.current
                                val playback = LocalReplyPlayback.current
                                val presentationActive = LocalChatPresentationActive.current
                                SideEffect {
                                    val geometry = {
                                        "paused=${playback?.paused} presentationActive=$presentationActive " +
                                            "held=${clearance?.held} ${viewport?.diagnostics()}"
                                    }
                                    if (turn.id == "start-reply") liveGeometry = geometry else tailGeometry = geometry
                                }
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
                    val complete = (screen.turns.lastOrNull() as? ChatTurnUi.Assistant)?.status == RunStatus.COMPLETED
                    val copy = ui.onNode(hasContentDescription("复制回答") and hasAnyAncestor(hasTestTag("chat-turn:tail")))
                    val actionExists = ui.onAllNodes(hasContentDescription("复制回答") and hasAnyAncestor(hasTestTag("chat-turn:tail")))
                        .fetchSemanticsNodes().isNotEmpty()
                    val actionsDisplayed = !complete || actionExists && copy.isDisplayed()
                    lastGeometry = "complete=$complete actionExists=$actionExists actionsDisplayed=$actionsDisplayed " +
                        "tailTop=${tail.positionInRoot().y} tailHeight=${tail.size.height} " +
                        "surface=$surface viewport=$viewport density=$density outerGapDp=$gap surfaceGapDp=${(surface.top - fullTailBottom) / density} markerDisplayed=$displayed"
                    displayed && actionsDisplayed && gap in (ChatTailClearance.value - 2f)..(ChatTailClearance.value + 2f)
                }
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Compact tail after composer growth: $lastGeometry\n${paintDump("tail")}", timeout)
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
            traceFirstGrowth("scale=$scale") {
                val first = screen.turns.last() as ChatTurnUi.Assistant
                mainThread { screen = screen.copy(turns = listOf(screen.turns.first(),
                    first.copy(finalText = first.finalText + "\n\n" + answer("流式增高末尾", 30)))) }
                assertCompactTail("流式增高末尾")
            }
            traceFirstGrowth("scale=$scale detached append") {
                ui.onNodeWithTag("chat-messages").performTouchInput { click(center) }
                val grown = screen.turns.last() as ChatTurnUi.Assistant
                mainThread { screen = screen.copy(turns = listOf(screen.turns.first(),
                    grown.copy(finalText = grown.finalText + "\n\n" + answer("触摸后末尾", 30)))) }
                ui.waitUntil(5_000) {
                    ui.onAllNodesWithText("触摸后末尾", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                awaitVisualIdle()
            }
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

    private fun awaitTypingDraw(view: android.view.View) {
        val clock = ui.mainClock.currentTime
        check(!ui.mainClock.autoAdvance)
        val drawn = java.util.concurrent.CountDownLatch(1)
        var observer: android.view.ViewTreeObserver? = null
        val listener = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() { view.post { drawn.countDown() } }
        }
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                check(view.isAttachedToWindow)
                observer = view.viewTreeObserver.also { it.addOnDrawListener(listener) }
                view.invalidate()
            }
            check(drawn.await(2, java.util.concurrent.TimeUnit.SECONDS)) { "Typing tick did not draw its native window frame" }
            ui.waitForIdle()
            assertEquals("A native draw fence must not advance typing time", clock, ui.mainClock.currentTime)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                instrumentation.runOnMainSync {
                    (observer?.takeIf { it.isAlive } ?: view.viewTreeObserver).removeOnDrawListener(listener)
                }
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testFirstLiveChunkTypesWithoutSnappingTheSentUserAndHistory() {
        show(1f, plainText = true)
        val nativeView = (ui.onNodeWithTag("spacing-host").fetchSemanticsNode().root as
            androidx.compose.ui.platform.ViewRootForTest).view
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
            var paintedSnapshot = false
            val trace = java.util.ArrayDeque<String>(32)
            fun traceDraw(frame: Int, phase: String) {
                val sample = paintDump("start-reply").lineSequence().filter {
                    it.startsWith("REVEAL ") || it.startsWith("MOTION ") || it.startsWith("PAINT ") ||
                        it.trimStart().startsWith("GEOMETRY ")
                }.joinToString("\n") { it.replace(Regex("@/[^ ]+"), "") }
                if (trace.size == 32) trace.removeFirst()
                trace.addLast("frame=$frame phase=$phase clock=${ui.mainClock.currentTime} ${liveGeometry()}\n$sample")
            }
            val typingClockStart = ui.mainClock.currentTime
            var received = first.take(5)
            var batchReached = false
            var previousSnapshot = ""
            ui.runOnUiThread {
                screen = screen.copy(turns = history + pending.copy(pending = false) +
                    ChatTurnUi.Assistant("start-reply", finalText = received, status = RunStatus.RUNNING, isStreaming = true))
            }
            fun sampleTypingFrame(frame: Int, phase: String) {
                if (frame !in 28..34) return
                var geometry = "not sampled"
                instrumentation.runOnMainSync {
                    geometry = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { liveGeometry() }
                }
                android.util.Log.i("SpacingRetryA", "frame=$frame phase=$phase clock=${ui.mainClock.currentTime} $geometry")
                paintDump("start-reply").chunked(3_000).forEachIndexed { part, text ->
                    android.util.Log.i("SpacingRetryA", "frame=$frame phase=$phase part=$part\n$text")
                }
            }
            repeat(90) { frame ->
                if (frame == 30 || frame == 60) {
                    assertTrue("The previous snapshot must reach the screen before the next delta", batchReached)
                    previousSnapshot = received
                    received = if (frame == 30) first.take(13) else first
                    batchReached = false
                    ui.runOnUiThread {
                        screen = screen.copy(turns = history + pending.copy(pending = false) +
                            ChatTurnUi.Assistant("start-reply", finalText = received, status = RunStatus.RUNNING, isStreaming = true))
                    }
                }
                sampleTypingFrame(frame, "before-advance")
                ui.mainClock.advanceTimeByFrame()
                sampleTypingFrame(frame, "before-idle")
                ui.waitForIdle()
                sampleTypingFrame(frame, "after-idle")
                traceDraw(frame, "before-draw")
                try { awaitTypingDraw(nativeView) }
                catch (failure: Throwable) {
                    failure.addSuppressed(AssertionError(trace.joinToString("\n---\n")))
                    throw failure
                }
                traceDraw(frame, "after-draw")
                assertEquals("Each typing tick advances exactly one original frame", typingClockStart + (frame + 1) * 16L,
                    ui.mainClock.currentTime)
                users += top("start-user")
                histories += top("tail")
                val textNodes = ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                    hasAnyAncestor(hasTestTag("chat-turn:start-reply")), useUnmergedTree = true).fetchSemanticsNodes()
                val texts = textNodes.flatMap { it.config[SemanticsProperties.Text] }.map { it.text }
                    .filter { it.isNotEmpty() && first.startsWith(it) }
                val shown = texts.maxByOrNull { it.length }.orEmpty()
                assertTrue("Unreceived text must never be shown: received=$received shown=$shown", received.startsWith(shown))
                if (batchReached) assertEquals("No new delta may change the displayed frontier", received, shown)
                if (shown == received) batchReached = true
                if (frame % 30 == 29) assertEquals("Every received batch must finish within its original frame budget: " +
                    "frame=$frame clock=${ui.mainClock.currentTime} lengths=$lengths received=${received.length} " +
                    "shown=${shown.length} geometry=${liveGeometry()}" +
                    if (received != shown) "\n${paintDump("start-reply")}\n${trace.joinToString("\n---\n")}" else "", received, shown)
                assertTrue("Only received documents may appear: previous=$previousSnapshot received=$received shown=$shown",
                    shown == previousSnapshot || shown == received)
                lengths += shown.length
                if (frame % 10 == 0 || frame == 89) geometryFrames += "frame=$frame ${liveGeometry()}"
                val receivedPrefixNode = textNodes.firstOrNull { node ->
                    node.config[SemanticsProperties.Text].any { it.text == received && received.isNotEmpty() }
                }
                if (!paintedSnapshot && receivedPrefixNode != null) {
                    val origin = receivedPrefixNode.layoutInfo.coordinates.positionInRoot()
                    val size = receivedPrefixNode.layoutInfo.coordinates.size
                    if (origin.y + size.height <= composer.top - (10f + ChatTailClearance.value) * density + 1f) {
                        val clock = ui.mainClock.currentTime
                        val host = ui.onNodeWithTag("spacing-host")
                        val pixels = captureScreenNode(host, advanceAnimationClock = false).toPixelMap()
                        assertEquals("A snapshot capture must not advance typing", clock, ui.mainClock.currentTime)
                        val hostOrigin = host.fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
                        val left = (origin.x - hostOrigin.x).toInt().coerceIn(0, pixels.width)
                        val top = (origin.y - hostOrigin.y).toInt().coerceIn(0, pixels.height)
                        val right = (origin.x - hostOrigin.x + size.width).toInt().coerceIn(left, pixels.width)
                        val bottom = (origin.y - hostOrigin.y + size.height).toInt().coerceIn(top, pixels.height)
                        for (y in top until bottom) for (x in left until right) {
                            val pixel = pixels[x, y]
                            if (pixel.red < .6f && pixel.green < .6f && pixel.blue < .6f) paintedSnapshot = true
                        }
                    }
                }
                assertEquals("The real composer must remain anchored", composer,
                    ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot)
            }
            assertTrue("The final received document must be visible", batchReached)
            val userSteps = users.zipWithNext { before, after -> before - after }
            val historySteps = histories.zipWithNext { before, after -> before - after }
            assertEquals("First chunk must finish: lengths=$lengths userSteps=$userSteps " +
                "historySteps=$historySteps geometry=$geometryFrames", first.length, lengths.last())
            assertTrue("A received snapshot must actually paint inside the body ROI", paintedSnapshot)
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
    private fun awaitNativePaint(text: String, accepts: (MarkdownPaintSlotsProbe.PaintSample) -> Boolean): MarkdownPaintSlotsProbe.PaintSample {
        repeat(8) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            val sample = ui.runOnIdle {
                var selected: MarkdownPaintSlotsProbe.PaintSample? = null
                paintSlots.sample("native-environment") {
                    if (it.candidate?.layoutInput?.text?.text == text) selected = it
                }
                selected
            }
            if (sample != null && accepts(sample)) return sample
        }
        throw AssertionError("Native paint did not settle in eight frames\n${paintDump("native-environment")}")
    }

    private fun assertNativeInk(color: Color) {
        val pixels = captureScreenNode(ui.onNodeWithTag("native-text"), advanceAnimationClock = false).toPixelMap()
        var ink = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (kotlin.math.abs(pixel.red - color.red) < .08f &&
                kotlin.math.abs(pixel.green - color.green) < .08f &&
                kotlin.math.abs(pixel.blue - color.blue) < .08f) ink++
        }
        assertTrue("The actual native draw must contain $color pixels, found $ink; " +
            "capture=${pixels.width}x${pixels.height}; " +
            "bounds=${ui.onNodeWithTag("native-text").fetchSemanticsNode().boundsInRoot}; " +
            "screen=${ui.onNodeWithTag("native-text").fetchSemanticsNode().positionOnScreen}; " +
            "decor=${activity.window.decorView.width}x${activity.window.decorView.height}\n" +
            paintDump("native-environment"), ink > 0)
    }

    private fun showNativePaint(text: String, clearance: StreamingTextClearance,
        publication: MarkdownPaintPublication, color: () -> Color, fontScale: () -> Float,
        heightPx: () -> Int) {
        mainThread {
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                val originalDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(originalDensity.density, fontScale()),
                    LocalStreamingTextClearance provides clearance,
                    LocalMarkdownPaintPublication provides publication,
                ) {
                    val density = LocalDensity.current
                    val style = TextStyle(color = color(), fontSize = 16.sp, lineHeight = 26.sp)
                    val measurer = rememberTextMeasurer()
                    val measured = measurer.measure(AnnotatedString(text), style,
                        constraints = Constraints(maxWidth = with(density) { 240.dp.roundToPx() }))
                    val fullHeight = heightPx().takeIf { it > 0 } ?: measured.size.height
                    paintSlots.Capture("native-environment")
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Box(Modifier.requiredWidth(240.dp).height(with(density) { fullHeight.toDp() })
                            .background(Color.Magenta).onGloballyPositioned {
                                nativeHostCoordinates = it
                                clearance.update(it, it, 0, 0, 0, protect = true, clampToViewport = true)
                            }.testTag("native-host")) {
                            ClearanceText(AnnotatedString(text), 0,
                                Modifier.requiredWidth(240.dp).wrapContentHeight(Alignment.Top, unbounded = true)
                                    .testTag("native-text"), style)
                        }
                    }
                }
            }
        }
        awaitNodeWindowFocus(ui.onNodeWithTag("native-host"))
    }

    fun testSameTextColorChangeRepaintsWithoutRemountingThePaintFrame() {
        val text = "Same native paint."
        val clearance = StreamingTextClearance()
        val publication = MarkdownPaintPublication()
        var color by mutableStateOf(Color.Red)
        val autoAdvance = ui.mainClock.autoAdvance
        ui.mainClock.autoAdvance = false
        try {
            showNativePaint(text, clearance, publication, { color }, { 1f }, { 0 })
            val before = awaitNativePaint(text) {
                val candidate = it.candidate
                candidate != null && it.drawn?.canPaintPrefixOf(candidate) == true && publication.ready
            }
            assertNativeInk(Color.Red)
            mainThread { color = Color.Blue }
            val after = awaitNativePaint(text) {
                val candidate = it.candidate
                candidate?.layoutInput?.style?.color == Color.Blue &&
                    it.drawn?.canPaintPrefixOf(candidate) == true && publication.ready
            }
            assertSame("Only BasicText may remount", before.frame, after.frame)
            assertEquals(before.candidate!!.size, after.candidate!!.size)
            assertEquals(before.candidate.layoutInput.text, after.candidate.layoutInput.text)
            assertNativeInk(Color.Blue)
        } finally {
            ui.mainClock.autoAdvance = autoAdvance
        }
    }

    fun testSameTextScaledPaintDebtClearsAfterPlacementOnlyGrowth() {
        val text = "Clearance one.\nClearance two.\nClearance three."
        val clearance = StreamingTextClearance()
        val publication = MarkdownPaintPublication()
        var fontScale by mutableStateOf(1f)
        var heightPx by mutableStateOf(0)
        val autoAdvance = ui.mainClock.autoAdvance
        ui.mainClock.autoAdvance = false
        try {
            showNativePaint(text, clearance, publication, { Color.Blue }, { fontScale }, { heightPx })
            val before = awaitNativePaint(text) {
                val candidate = it.candidate
                it.fits == true && candidate != null &&
                    it.drawn?.canPaintPrefixOf(candidate) == true && publication.ready
            }
            mainThread {
                heightPx = before.candidate!!.size.height
                fontScale = 1.6f
            }
            val blocked = awaitNativePaint(text) {
                it.candidate?.layoutInput?.density?.fontScale == 1.6f &&
                    it.candidate.size.height > before.candidate!!.size.height &&
                    it.fits == false && it.above == false && !publication.ready
            }
            assertSame(before.frame, blocked.frame)
            mainThread { heightPx = blocked.candidate!!.size.height }
            val after = awaitNativePaint(text) {
                val candidate = it.candidate
                it.fits == true && candidate != null &&
                    it.drawn?.canPaintPrefixOf(candidate) == true && publication.ready
            }
            assertSame(blocked.frame, after.frame)
            assertEquals(blocked.candidate!!.layoutInput.text, after.candidate!!.layoutInput.text)
            assertEquals(blocked.candidate.layoutInput.style, after.candidate.layoutInput.style)
            assertEquals(blocked.candidate.layoutInput.density.fontScale,
                after.candidate.layoutInput.density.fontScale, 0f)
            assertNativeInk(Color.Blue)
        } finally {
            ui.mainClock.autoAdvance = autoAdvance
        }
    }

    fun testNativePaintDebtClearsWhenProtectionIsReleasedWithoutPlacementGrowth() {
        val text = "Release one.\nRelease two.\nRelease three."
        val clearance = StreamingTextClearance()
        val publication = MarkdownPaintPublication()
        val autoAdvance = ui.mainClock.autoAdvance
        ui.mainClock.autoAdvance = false
        try {
            showNativePaint(text, clearance, publication, { Color.Blue }, { 1.6f }, { 1 })
            val blocked = awaitNativePaint(text) {
                it.candidate != null && it.fits == false && it.above == false && !publication.ready
            }
            val host = checkNotNull(nativeHostCoordinates)
            val size = host.size
            mainThread {
                clearance.update(host, host, 0, 0, 0, protect = false, clampToViewport = true)
            }
            val after = awaitNativePaint(text) {
                if (publication.ready) assertEquals(text, it.drawn?.layoutInput?.text?.text)
                val candidate = it.candidate
                candidate != null && it.fits == true &&
                    it.drawn?.canPaintPrefixOf(candidate) == true && publication.ready
            }
            assertSame(blocked.frame, after.frame)
            assertSame(host, nativeHostCoordinates)
            assertEquals(size, host.size)
            assertEquals(blocked.candidate!!.layoutInput.text, after.candidate!!.layoutInput.text)
            assertEquals(blocked.candidate.layoutInput.style, after.candidate.layoutInput.style)
            assertNativeInk(Color.Blue)
        } finally {
            ui.mainClock.autoAdvance = autoAdvance
        }
    }

}
