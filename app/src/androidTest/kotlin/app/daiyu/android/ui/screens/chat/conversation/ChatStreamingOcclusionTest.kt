package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.basic.Text

class ChatStreamingOcclusionTest : WiredActivityTestCase() {
    private var density = 1f
    private var height by mutableStateOf(600.dp)
    private var editorHeight by mutableStateOf(96.dp)
    private var viewportHeight by mutableStateOf(420.dp)
    private var request by mutableStateOf(ChatScrollRequest(1))

    private var running by mutableStateOf(true)
    private var showCompression by mutableStateOf(false)
    private var appendAssistant by mutableStateOf(false)

    private fun mount(interactive: Boolean = false, painted: Boolean = false) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    BoxWithConstraints(Modifier.size(320.dp, viewportHeight).testTag("occlusion-host")) {
                        ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { bottomInset ->
                            ChatMessageList(
                                List(6) { ChatTurnUi.User("u$it", "history $it") } +
                                    ChatTurnUi.Assistant("tail", status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED, isStreaming = running) +
                                    if (appendAssistant) listOf(ChatTurnUi.Assistant("next", finalText = "next reply")) else emptyList(),
                                ChatScreenActions(), scrollRequest = request,
                                compressionActivity = if (showCompression) app.daiyu.android.timeline.CompressionActivity(
                                    "tail-compression", "fixture", 6, null) else null,
                                composerBottomInset = bottomInset, turnExtras = { turn ->
                                  if (turn.id == "tail") {
                                    if (interactive) {
                                        Column(if (painted) Modifier.background(Color.Cyan) else Modifier) {
                                            Text("inner content start", Modifier.testTag("inner-start"))
                                            Box(Modifier.height(height - 160.dp))
                                            Text("manual content anchor", Modifier.testTag("manual-anchor"))
                                            Box(Modifier.height(140.dp))
                                        }
                                    } else Box(Modifier.height(height))
                                    Text("occlusion tail marker", Modifier.testTag("occlusion-tail"))
                                  }
                                },
                            )
                        }, composer = {
                            Box(Modifier.fillMaxWidth().height(editorHeight).testTag("occlusion-composer"))
                        })
                    }
                }
            }
        }
        ui.waitForIdle()
        assertTailClearance()
    }

    private fun tailTop(): Float = ui.onNodeWithTag("chat-turn:tail")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun assertTailClearance() {
        val coordinates = ui.onNodeWithTag("occlusion-tail").fetchSemanticsNode().layoutInfo.coordinates
        val tailBottom = coordinates.positionInRoot().y + coordinates.size.height
        val composerTop = ui.onNodeWithTag("occlusion-composer").fetchSemanticsNode().boundsInRoot.top
        val gap = (composerTop - tailBottom) / density
        assertTrue("Streaming tail must retain the fixed composer clearance: gap=${gap}dp",
            gap >= ChatTailClearance.value - 1f / density)
    }

    private fun frame() {
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
    }

    private fun shortWorkflow(count: Int): ChatTurnUi.Assistant {
        val message = Message(MessageId("short-workflow"), ConversationId("short-conversation"), RunId("short-run"),
            "assistant", List(count) { index ->
                MessageBlock.Tool(ToolInvocation(ToolCallId("short-step-$index"), "lookup", "{}"), "done")
            }, RunStatus.RUNNING)
        return ChatTurnMapper.map(listOf(message)).single() as ChatTurnUi.Assistant
    }

    private fun assertVerticallyUnclipped(node: SemanticsNode, tag: String) {
        assertEquals("The identity/header top must remain unclipped: $tag",
            node.positionInRoot.y, node.boundsInRoot.top, 1f)
        assertEquals("The whole identity/header must remain visible: $tag",
            node.size.height.toFloat(), node.boundsInRoot.height, 1f)
    }

    fun testSemanticVisibilityProbeDistinguishesPaddingFromTopClipping() {
        var shift by mutableStateOf(0.dp)
        mainThread {
            activity.setContent {
                Box(Modifier.size(64.dp).clipToBounds()) {
                    Box(Modifier.offset(y = shift).testTag("semantic-visibility-probe").padding(bottom = 2.dp)) {
                        Box(Modifier.size(32.dp, 26.dp))
                    }
                }
            }
        }
        ui.waitForIdle()
        val complete = ui.onNodeWithTag("semantic-visibility-probe").fetchSemanticsNode()
        assertTrue("The probe must distinguish tagged padding from inner layout geometry",
            complete.size.height > complete.layoutInfo.coordinates.size.height)
        assertVerticallyUnclipped(complete, "padded probe")

        ui.runOnUiThread { shift = (-4).dp }
        ui.waitForIdle()
        val clipped = ui.onNodeWithTag("semantic-visibility-probe").fetchSemanticsNode()
        assertTrue("The negative probe must really lose visible height",
            clipped.boundsInRoot.height < clipped.size.height - 1f)
        val failure = runCatching { assertVerticallyUnclipped(clipped, "clipped probe") }.exceptionOrNull()
        assertTrue("The same visibility assertion must reject real top clipping",
            failure is junit.framework.AssertionFailedError)

        ui.runOnUiThread { shift = 0.dp }
        ui.waitForIdle()
        assertVerticallyUnclipped(ui.onNodeWithTag("semantic-visibility-probe").fetchSemanticsNode(), "restored probe")
    }

    fun testAppendingShortWorkflowKeepsIdentityAndHeaderUnclippedInEveryFrame() {
        var count by mutableStateOf(1)
        val tail = shortWorkflow(1)
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    Box(Modifier.size(320.dp, 640.dp)) {
                        ChatConversationLayers(Modifier.size(320.dp, 640.dp), messages = { bottomInset ->
                            ChatMessageList(List(6) { ChatTurnUi.User("short-history-$it", "history $it") } + shortWorkflow(count),
                                ChatScreenActions(), scrollRequest = ChatScrollRequest(1), composerBottomInset = bottomInset)
                        }, composer = {
                            Box(Modifier.fillMaxWidth().height(96.dp).testTag("short-composer"))
                        })
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithTag("reply-identity:${tail.listKey}").assertIsDisplayed()
        ui.mainClock.autoAdvance = false
        try {
            var sawVisibleNeighbor = false
            for (next in 2..5) {
                ui.runOnUiThread { count = next }
                var sawNewTool = false
                repeat(48) {
                    frame()
                    val viewport = ui.onNodeWithTag("chat-stream-viewport:${tail.listKey}").fetchSemanticsNode()
                    val composer = ui.onNodeWithTag("short-composer").fetchSemanticsNode().boundsInRoot
                    val row = ui.onNodeWithTag("chat-turn:${tail.id}").fetchSemanticsNode()
                    val list = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot
                    assertTrue("This regression must remain shorter than a full reading viewport",
                        row.size.height < composer.top - list.top - ChatTailClearance.value * density)
                    val range = viewport.config[SemanticsProperties.VerticalScrollAxisRange]
                    assertEquals("A short workflow must never scroll its identity out of its own viewport",
                        0f, range.value(), 1f)
                    for (tag in listOf("reply-identity:${tail.listKey}", "work-process-header")) {
                        val node = ui.onNodeWithTag(tag).fetchSemanticsNode()
                        val top = node.positionInRoot.y
                        assertTrue("Existing workflow top must not be locally cropped: $tag",
                            top >= viewport.boundsInRoot.top - 1f)
                        assertVerticallyUnclipped(node, tag)
                    }
                    val identityTop = ui.onNodeWithTag("reply-identity:${tail.listKey}")
                        .fetchSemanticsNode().positionInRoot.y
                    val neighbors = ui.onAllNodesWithTag("chat-turn:short-history-5").fetchSemanticsNodes()
                    neighbors.singleOrNull()?.takeIf { it.boundsInRoot.height > 1f }?.let { neighbor ->
                        sawVisibleNeighbor = true
                        assertTrue("Keeping the header must not draw it over the previous message",
                            neighbor.boundsInRoot.bottom <= identityTop + 1f)
                    }
                    val tool = ui.onAllNodesWithTag("tool-step:short-step-${next - 1}").fetchSemanticsNodes().singleOrNull()
                    if (tool != null && tool.boundsInRoot.height >= tool.size.height - 1f) {
                        sawNewTool = true
                        assertTrue("A fully revealed new tool must still clear the composer",
                            tool.boundsInRoot.bottom <= composer.top - ChatTailClearance.value * density + 1f)
                    }
                }
                assertTrue("The outer follower must actually reveal each new tool", sawNewTool)
            }
            assertTrue("The regression must include a visible preceding message", sawVisibleNeighbor)
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        ui.onNodeWithTag("tool-step:short-step-4").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testTerminalPacedTextKeepsItsActualPaintedEndNearComposer() {
        val seed = "已显示的正文用于测量当前可见末行。".repeat(24)
        val full = seed + "最后一段还在逐字输出，不能预留空白或暂停绘制。".repeat(20)
        verifyTerminalPacedText(seed, full)
    }

    fun testTerminalPacedMarkdownKeepsItsActualPaintedEndNearComposer() {
        val seed = "**1. 第一项**\n" + "当前已经显示的说明应保持正常测量与绘制。".repeat(24)
        val full = seed + "\n\n**2. 第二项**\n" + "第二段说明随真实进度逐字增加，不应提前预占后续段落高度。".repeat(8) +
            "\n\n**3. 第三项**\n" + "第三段结束后仍需连续显示总述，不能等待终态才一次恢复。".repeat(8) +
            "\n\n**总体来看**：" + "总述逐字显示，正文末行始终贴近安全底边。".repeat(5) +
            "\n\n需要展开其中的某一项吗？"
        verifyTerminalPacedText(seed, full)
    }

    fun testTerminalPacedToolAnswerKeepsItsActualPaintedEndNearComposer() {
        verifyTerminalPacedToolAnswer()
    }

    fun testTerminalPacedToolAnswerKeepsItsPaintedEndAcrossResume() {
        verifyTerminalPacedToolAnswer(resumeAtFrame = 180)
    }

    private fun verifyTerminalPacedToolAnswer(resumeAtFrame: Int? = null) {
        val full = """
            搜到的内容主要集中在几起最知名的“外星人事件”：

            **1. 罗斯威尔事件（1947，美国新墨西哥州）**
            最著名的 UFO 事件。当年 7 月，美国陆军宣布回收了一个“飞碟”，一天内又改口说是气象气球。1978 年退休军官杰西·马塞尔称那是掩盖说法，并推测残骸来自外星文明。1994 年官方报告解释为绝密“莫古尔计划”的高空气球（用于侦测苏联核试验）。维基百科把它定性为阴谋论，相关“小灰人”“解剖外星人”等说法均无事实依据。

            **2. 希尔夫妇绑架事件（1961，美国）**
            巴尼·希尔与贝蒂·希尔夫妇声称在 1961 年 9 月 19–20 日夜间被外星人绑架，是“第三类接触”中最常被引用的案例之一。

            **3. 墨西哥议会“外星人遗骸”听证会（2023 年 9 月）**
            墨西哥议会首次就“不明异常现象”召开公开听证会，展示两具据称 2017 年在秘鲁发现的疑似“外星生物”遗骸，引发热议，但科学界普遍质疑其真实性。

            **总体来看**：这些都是知名度高、但缺乏可验证科学证据的案例，主流解释多指向军事机密项目、误认或人为炒作。

            需要我展开讲某一个，或者换成英文/其他语言的资料吗？
        """.trimIndent()
        verifyTerminalPacedText("", full, withTools = true, resumeAtFrame = resumeAtFrame)
    }

    private fun verifyTerminalPacedText(seed: String, full: String, withTools: Boolean = false, resumeAtFrame: Int? = null) {
        val preface = "我用浏览器帮你搜一下。"
        val expectedSeed = if (withTools) "" else markdownBlocksDisplayText(defaultMarkdownParser(seed))
        val expectedFull = markdownBlocksDisplayText(defaultMarkdownParser(full))
        for (parser in listOf(defaultMarkdownParser, streamingMarkdownParser)) {
            for (text in if (withTools) listOf(preface, seed, full) else listOf(seed, full)) {
                markdownProjectionCache.put(MarkdownProjection(listOf(text), mapOf(text to parser(text)), parser))
            }
        }
        fun turnFor(text: String, complete: Boolean, toolCount: Int = 2): ChatTurnUi.Assistant {
            if (!withTools) return ChatTurnUi.Assistant("paced-tail", finalText = text,
                status = if (complete) RunStatus.COMPLETED else RunStatus.RUNNING, isStreaming = !complete)
            val blocks = listOf(MessageBlock.Text("paced-preface", preface)) + List(toolCount) { index ->
                MessageBlock.Tool(ToolInvocation(ToolCallId("paced-tool-$index"), "lookup", "{}"), "done")
            } + if (text.isEmpty()) emptyList() else listOf(MessageBlock.Text("paced-answer", text))
            val message = Message(MessageId("paced-tail"), ConversationId("paced-conversation"), RunId("paced-run"),
                "assistant", blocks, if (complete) RunStatus.COMPLETED else RunStatus.RUNNING)
            return (ChatTurnMapper.map(listOf(message)).single() as ChatTurnUi.Assistant).copy(isStreaming = !complete)
        }
        var turn by mutableStateOf(turnFor(if (withTools) "" else seed, complete = false, toolCount = 0))
        val fixedRequest = ChatScrollRequest(1)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val reports = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        var contentActive by mutableStateOf(true)
        lateinit var registry: androidx.lifecycle.LifecycleRegistry
        var geometry: () -> String = { "" }
        mainThread {
            val owner = object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle = androidx.lifecycle.LifecycleRegistry(this).also { registry = it }
            }
            registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            activity.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.lifecycle.compose.LocalLifecycleOwner provides owner,
                    app.daiyu.android.ui.navigation.LocalPageContentActive provides contentActive) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    Box(Modifier.size(320.dp, 420.dp).background(Color.White).testTag("paced-host")) {
                        ChatPageHost("paced-visit") { _, report ->
                            ChatConversationLayers(Modifier.size(320.dp, 420.dp), messages = { inset ->
                                ChatMessageList(List(6) { ChatTurnUi.User("paced-history-$it", "history $it") } + turn,
                                    ChatScreenActions(), scrollRequest = fixedRequest, composerBottomInset = inset,
                                    turnExtras = {
                                        LocalWorkflowDisclosureContext.current?.viewport?.let { viewport ->
                                            geometry = { viewport.diagnostics() }
                                        }
                                    },
                                    onPresentationReady = { ready.set(it); reports += it; report(it) })
                            }, composer = {
                                Box(Modifier.fillMaxWidth().height(96.dp).testTag("paced-composer"))
                            })
                        }
                    }
                }
                }
            }
        }
        ui.waitForIdle()
        ui.waitUntil(5_000) { ready.get() }
        if (withTools) {
            for (count in 1..2) {
                mainThread { turn = turnFor("", complete = false, toolCount = count) }
                ui.waitForIdle()
            }
        }
        val textMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            (hasTestTag("message-content-text:0") or hasAnyAncestor(hasTestTag("message-content-text:0")))
        fun textLayouts(): List<Pair<SemanticsNode, androidx.compose.ui.text.TextLayoutResult>> {
            val nodes = ui.onAllNodes(textMatcher, useUnmergedTree = true).fetchSemanticsNodes()
            return nodes.map { node ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                ui.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action!!(results) }
                node to results.single()
            }.filterNot { (_, result) ->
                withTools && result.layoutInput.text.text.let { it.isNotEmpty() && preface.startsWith(it) }
            }
        }
        fun displayed(layouts: List<Pair<SemanticsNode, androidx.compose.ui.text.TextLayoutResult>>) =
            layouts.joinToString("") { it.second.layoutInput.text.text }
        assertEquals(expectedSeed, displayed(textLayouts()))
        fun saveDiagnostic(image: androidx.compose.ui.graphics.ImageBitmap, name: String, detail: String) {
            val directory = java.io.File(activity.getExternalFilesDir(null),
                "visual-diagnostics/streaming-blank/${if (withTools) "tool" else "plain"}-${resumeAtFrame ?: -1}-${full.hashCode()}")
                .apply { mkdirs() }
            java.io.File(directory, "$name.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            java.io.File(directory, "$name.txt").writeText(detail)
        }
        fun frameDiagnostic(index: Int, gap: Float, layouts: List<Pair<SemanticsNode, androidx.compose.ui.text.TextLayoutResult>>) =
            "frame=$index clock=${ui.mainClock.currentTime} gap=$gap density=$density ready=${ready.get()} active=$contentActive " +
                "length=${displayed(layouts).length} viewport=${geometry()} nodes=" + layouts.joinToString(" | ") { (node, result) ->
                    "position=${node.positionInRoot} size=${node.size} bounds=${node.boundsInRoot} " +
                        "lines=${result.lineCount} lineBottom=${result.getLineBottom(result.lineCount - 1)}"
                }
        fun pixels(layouts: List<Pair<SemanticsNode, androidx.compose.ui.text.TextLayoutResult>> = emptyList()): IntArray {
            val host = ui.onNodeWithTag("paced-host")
            val bounds = host.fetchSemanticsNode().boundsInRoot
            val bitmap = host.captureToImage()
            val image = bitmap.toPixelMap()
            val lastLine = layouts.mapNotNull { (node, result) ->
                (0 until result.lineCount).lastOrNull { line ->
                    result.layoutInput.text.text.substring(result.getLineStart(line), result.getLineEnd(line)).isNotBlank()
                }?.let { line -> Triple(node, result, line) }
            }.maxByOrNull { (node, result, line) -> node.positionInRoot.y + result.getLineBottom(line) }
            lastLine?.let { (node, result, line) ->
                val left = (node.positionInRoot.x + result.getLineLeft(line) - bounds.left).toInt().coerceIn(0, image.width)
                val right = (node.positionInRoot.x + result.getLineRight(line) - bounds.left).toInt().coerceIn(0, image.width)
                val top = (node.positionInRoot.y + result.getLineTop(line) - bounds.top).toInt().coerceIn(0, image.height)
                val bottom = (node.positionInRoot.y + result.getLineBottom(line) - bounds.top).toInt().coerceIn(0, image.height)
                var ink = 0
                for (y in top until bottom) for (x in left until right) {
                    val color = image[x, y]
                    if (color.red < .8f && color.green < .8f && color.blue < .8f) ink++
                }
                if (ink <= 2) saveDiagnostic(bitmap, "ink-${ui.mainClock.currentTime}",
                    "ink=$ink roi=$left,$top,$right,$bottom " + frameDiagnostic(-1, Float.NaN, layouts))
                assertTrue("The latest revealed line must actually paint in its own bounds: ${geometry()}", ink > 2)
            }
            return IntArray(image.width * image.height) { index ->
                image[index % image.width, index / image.width].hashCode()
            }
        }
        var previousPixels = pixels()
        var capturedLength = expectedSeed.length
        var previousText = expectedSeed
        var previousHeight = textLayouts().sumOf { it.second.size.height }
        var heightGrowth = 0
        var growthFrames = 0
        var unreadyFrames = 0
        var paintedGrowth = 0
        var finished = false
        var resumed = false
        val gapProblems = mutableListOf<String>()
        var firstGapFrame: Int? = null
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread {
                reports.clear()
                turn = turnFor(full, complete = true)
            }
            for (index in 0 until 640) {
                if (index == resumeAtFrame) {
                    ui.runOnUiThread {
                        registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
                        contentActive = false
                    }
                    frame()
                    ui.runOnUiThread {
                        registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                        contentActive = true
                    }
                    resumed = true
                }
                frame()
                val layouts = textLayouts()
                val displayed = displayed(layouts)
                val height = layouts.sumOf { it.second.size.height }
                assertTrue("Revealed text must remain an append-only prefix", expectedFull.startsWith(displayed) && displayed.startsWith(previousText))
                if (!ready.get()) unreadyFrames++
                if (displayed.isEmpty()) {
                    assertTrue("A completed tool answer must begin drawing without a long admission stall: ${geometry()}", index < 90)
                    continue
                }
                if (displayed.length > previousText.length) growthFrames++
                if (height > previousHeight) heightGrowth++
                val bottom = layouts.maxOf { (node, result) ->
                    node.positionInRoot.y + result.getLineBottom(result.lineCount - 1)
                }
                val composerTop = ui.onNodeWithTag("paced-composer").fetchSemanticsNode().boundsInRoot.top
                val gap = (composerTop - bottom) / density
                val settled = ready.get() && displayed == expectedFull
                val minimumGap = ChatTailClearance.value - 2f / density
                val maximumGap = ChatTailClearance.value + if (settled) 64f else 26f
                if (gap < minimumGap || gap > maximumGap) {
                    if (gapProblems.size < 8) gapProblems += frameDiagnostic(index, gap, layouts)
                    if (firstGapFrame == null) firstGapFrame = index
                }
                firstGapFrame?.let { first ->
                    if (index == first || index == first + 16 || index == first + 60 ||
                        index == resumeAtFrame?.plus(1) || settled) {
                        saveDiagnostic(ui.onNodeWithTag("paced-host").captureToImage(), "frame-$index",
                            frameDiagnostic(index, gap, layouts))
                    }
                }
                if (displayed.length >= capturedLength + 30) {
                    val nextPixels = pixels(layouts)
                    assertFalse("Text grew but the displayed page froze at frame=$index", previousPixels.contentEquals(nextPixels))
                    previousPixels = nextPixels
                    capturedLength = displayed.length
                    paintedGrowth++
                }
                previousText = displayed
                previousHeight = height
                if (settled) { finished = true; break }
            }
            assertTrue("Exercise terminal formatting before reveal is settled", reports.contains(false))
            assertTrue("Keep several seconds of terminal-paced output", unreadyFrames > 120 && growthFrames > 120)
            assertTrue("Wrap across several real text lines", heightGrowth >= 5)
            assertTrue("Check actual painted growth repeatedly", paintedGrowth >= 5)
            assertTrue("Exercise the requested foreground recovery", resumeAtFrame == null || resumed)
            assertTrue("Finish within the same visit without remounting", finished)
            assertEquals(expectedFull, displayed(textLayouts()))
            assertTrue("Every measured endpoint must retain the original composer gap; samples=" +
                gapProblems.joinToString(" ; "), gapProblems.isEmpty())
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        assertEquals(0, fixture.opens.get())
    }

    fun testStreamingGrowthKeepsTailAboveComposer() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            for (growth in listOf(26, 52, 300, 26, 78)) {
                ui.runOnUiThread { height += growth.dp }
                // One frame measures new content; the next lets the follower consume that layout.
                repeat(2) { frame() }
                assertTailClearance()
            }
            repeat(8) {
                frame()
                assertTailClearance()
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertTailClearance()
        assertEquals(0, fixture.opens.get())
    }

    fun testComposerAndViewportChangesKeepFollowVisible() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            for ((editor, viewport) in listOf(160 to 420, 72 to 300, 120 to 380, 96 to 420)) {
                ui.runOnUiThread { editorHeight = editor.dp; viewportHeight = viewport.dp }
                repeat(2) { frame() }
                assertTailClearance()
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertTailClearance()
        assertEquals(0, fixture.opens.get())
    }

    private fun innerOffset(): Float = ui.onNodeWithTag("chat-stream-viewport:tail")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun anchorTop(): Float = ui.onNodeWithTag("manual-anchor")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun manualNonzeroPosition() {
        val before = innerOffset()
        ui.onNodeWithTag("chat-stream-viewport:tail").performTouchInput {
            // Drag down INSIDE the finite viewport, away from its end. Slow
            // release avoids making this fixture depend on a fling's duration.
            swipe(Offset(center.x, top + 50f * density),
                Offset(center.x, top + 110f * density), durationMillis = 1000)
        }
        ui.waitForIdle()
        val after = innerOffset()
        assertTrue("Inner content really scrolled, not just the outer list", after < before - 20f * density)
        assertTrue("Manual position must remain nonzero", after > 20f * density)
        ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
    }

    fun testManualDragPastInnerStartUnwrapsWithoutEmptyExtent() {
        mount(interactive = true)
        var observedUnwrapped = false
        repeat(8) {
            ui.onNodeWithTag("chat-messages").performTouchInput {
                swipe(Offset(center.x, 60f * density), Offset(center.x, 220f * density), durationMillis = 500)
            }
            ui.waitForIdle()
            val tail = ui.onAllNodesWithTag("chat-stream-viewport:tail").fetchSemanticsNodes().singleOrNull()
            if (tail != null && tail.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() == 0f) {
                observedUnwrapped = true
            }
        }
        assertTrue("A reader crossing the inner edge must own an ordinary full-height item", observedUnwrapped)
        ui.onNodeWithText("history 0").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testRetiredViewportHasRealContentWhenReaderScrollsBackIntoIt() {
        mount(interactive = true)
        manualNonzeroPosition()
        mainThread { running = false; showCompression = true; appendAssistant = true }
        ui.waitForIdle()
        mainThread { request = ChatScrollRequest(6, messageId = "u0", followBottom = false) }
        ui.waitForIdle()
        ui.onNodeWithText("history 0").assertIsDisplayed()
        var sawRealStart = false
        repeat(16) {
            ui.onNodeWithTag("chat-messages").performTouchInput {
                swipe(Offset(center.x, 180f * density), Offset(center.x, 140f * density), durationMillis = 500)
            }
            ui.waitForIdle()
            if (ui.onNodeWithTag("inner-start").isDisplayed()) sawRealStart = true
        }
        assertTrue("Re-entering a retired row must show its real beginning, not an empty extent", sawRealStart)
        assertEquals(0, fixture.opens.get())
    }

    fun testRunningOwnerViewportHasRealContentWhenReaderScrollsBackIntoIt() {
        mainThread { height = 2400.dp }
        mount(interactive = true)
        manualNonzeroPosition()
        mainThread { request = ChatScrollRequest(6, messageId = "u0", followBottom = false) }
        ui.waitForIdle()
        ui.onNodeWithText("history 0").assertIsDisplayed()
        var sawRealStart = false
        var observedUnwrapped = false
        repeat(16) {
            ui.onNodeWithTag("chat-messages").performTouchInput {
                swipe(Offset(center.x, 180f * density), Offset(center.x, 140f * density), durationMillis = 500)
            }
            ui.waitForIdle()
            if (ui.onNodeWithTag("inner-start").isDisplayed()) sawRealStart = true
            val tail = ui.onAllNodesWithTag("chat-stream-viewport:tail").fetchSemanticsNodes().singleOrNull()
            if (tail != null && tail.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() == 0f) {
                observedUnwrapped = true
            }
        }
        assertTrue("The same running owner must not retain an empty offscreen extent", sawRealStart && observedUnwrapped)
        assertTrue(running)
        assertFalse(appendAssistant)
        assertEquals(0, fixture.opens.get())
    }

    fun testShortReplyGrowingPastViewportKeepsTailPinnedDuringOuterCatchup() {
        mainThread { height = 120.dp }
        mount()
        assertTrue("Short reply starts below the list top", tailTop() > 40f * density)
        ui.mainClock.autoAdvance = false
        try {
            val composer = ui.onNodeWithTag("occlusion-composer").fetchSemanticsNode().boundsInRoot
            for (shortHeight in listOf(160, 200)) {
                ui.runOnUiThread { height = shortHeight.dp }
                frame()
                val shortViewport = ui.onNodeWithTag("chat-stream-viewport:tail").fetchSemanticsNode()
                assertEquals("Short content retains its header instead of scrolling underneath it", 0f,
                    shortViewport.config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
                val shortTail = ui.onNodeWithTag("occlusion-tail").fetchSemanticsNode().layoutInfo.coordinates
                val shortBottom = shortTail.positionInRoot().y + shortTail.size.height
                assertEquals("Even short growth stays above the composer on its first drawn frame",
                    ChatTailClearance.value, (composer.top - shortBottom) / density, 2f / density)
            }
            ui.runOnUiThread { height = 900.dp }
            var observedPartialViewport = false
            repeat(24) {
                frame()
                if (tailTop() > 20f * density) observedPartialViewport = true
                ui.onNodeWithTag("occlusion-tail").assertIsDisplayed()
                assertTailClearance()
                val coordinates = ui.onNodeWithTag("occlusion-tail").fetchSemanticsNode().layoutInfo.coordinates
                val bottom = coordinates.positionInRoot().y + coordinates.size.height
                assertEquals("Latest content remains pinned while the outer origin catches up",
                    ChatTailClearance.value, (composer.top - bottom) / density, 2f / density)
            }
            assertTrue("Regression must exercise a positive outer origin", observedPartialViewport)
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        assertTailClearance()
        assertEquals(0, fixture.opens.get())
    }

    fun testProtectedTailClipsItsOwnBottomButManualReadingKeepsBackdrop() {
        var protectBottom by mutableStateOf(true)
        var hostCoordinates: LayoutCoordinates? = null
        val state = StreamingTailViewport(true)
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    Box(Modifier.size(320.dp, 300.dp).background(Color.White).clipToBounds()
                        .onPlaced { hostCoordinates = it }.testTag("bottom-clip-host")) {
                        StreamingTailContainer(state, { hostCoordinates }, "bottom-clip-probe",
                            userScrollEnabled = false, follows = { false },
                            protectClearance = { protectBottom },
                            clearance = { (20f * density).toInt() },
                            viewportHeight = { (120f * density).toInt() },
                            listViewportHeight = { (300f * density).toInt() }) {
                            Box(Modifier.fillMaxWidth().height(240.dp).background(Color.Cyan))
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        fun sampleBelowViewport(): Color {
            val host = ui.onNodeWithTag("bottom-clip-host")
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            val viewport = ui.onNodeWithTag("chat-stream-viewport:bottom-clip-probe").fetchSemanticsNode()
            val coordinates = viewport.layoutInfo.coordinates
            val bottom = coordinates.positionInRoot().y + coordinates.size.height
            val pixels = host.captureToImage().toPixelMap()
            val x = (viewport.boundsInRoot.center.x - hostBounds.left).toInt()
            val insideY = (bottom - hostBounds.top - 12f * density).toInt()
            val outsideY = (bottom - hostBounds.top + 12f * density).toInt()
            val inside = pixels[x, insideY]
            assertTrue("The bounded viewport must still draw its real content: $inside",
                inside.red < .1f && inside.green > .9f && inside.blue > .9f)
            return pixels[x, outsideY]
        }
        val clipped = sampleBelowViewport()
        assertTrue("Protected streaming content cannot paint below its own measured frame: $clipped",
            clipped.red > .9f && clipped.green > .9f && clipped.blue > .9f)
        mainThread { protectBottom = false }
        ui.waitForIdle()
        val manual = sampleBelowViewport()
        assertTrue("Detached reading must retain the deliberately extended backdrop: $manual",
            manual.red < .1f && manual.green > .9f && manual.blue > .9f)
        mainThread { protectBottom = true }
        ui.waitForIdle()
        val protectedAgain = sampleBelowViewport()
        assertTrue("The same live viewport must restore its protected bottom: $protectedAgain",
            protectedAgain.red > .9f && protectedAgain.green > .9f && protectedAgain.blue > .9f)
        assertEquals(0, fixture.opens.get())
    }

    fun testManualReadingDrawsThroughClearanceInsteadOfExposingPageBackground() {
        mount(interactive = true, painted = true)
        manualNonzeroPosition()
        val viewport = ui.onNodeWithTag("chat-stream-viewport:tail").fetchSemanticsNode()
        val composer = ui.onNodeWithTag("occlusion-composer").fetchSemanticsNode().boundsInRoot
        val host = ui.onNodeWithTag("occlusion-host")
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val sampleY = composer.top - ChatTailClearance.value * .5f * density
        assertTrue("Probe must be below the finite reading window", sampleY > viewport.boundsInRoot.bottom)
        val pixels = host.captureToImage().toPixelMap()
        val x = (viewport.boundsInRoot.center.x - hostBounds.left).toInt()
        val y = (sampleY - hostBounds.top).toInt()
        assertTrue("Probe lies within the captured message layer", x in 0 until pixels.width && y in 0 until pixels.height)
        val color = pixels[x, y]
        assertTrue("Message background continues through the editor clearance, not page white: $color",
            color.red < .1f && color.green > .9f && color.blue > .9f)
        assertEquals(0, fixture.opens.get())
    }

    fun testContinuousEveryFrameGrowthKeepsRealTailClearAndOuterBounded() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            var previous = tailTop()
            repeat(24) {
                ui.runOnUiThread { height += 52.dp }
                frame()
                assertTailClearance()
                val current = tailTop()
                val movement = previous - current
                assertTrue("Outer origin never reverses", movement >= -1f)
                assertTrue("Outer retains the original 14dp frame guard", movement <= 14f * density)
                previous = current
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        assertTailClearance()
    }

    fun testManualInnerScrollOwnsBothLayersDuringLaterGrowth() {
        mount(interactive = true)
        manualNonzeroPosition()
        val offset = innerOffset()
        val origin = tailTop()
        ui.mainClock.autoAdvance = false
        try {
            repeat(12) {
                ui.runOnUiThread { height += 26.dp }
                frame()
                assertEquals("Touch freezes outer follow", origin, tailTop(), 1f)
                assertEquals("Growth cannot steal inner ownership", offset, innerOffset(), 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        assertTailClearance()
    }

    fun testCompletionMigratesRealManualAnchorWithoutJumpOrDrift() {
        mount(interactive = true)
        manualNonzeroPosition()
        val anchor = anchorTop()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { running = false }
            // Includes readiness, stopped-fling capture, and subsequent frames;
            // two frames alone can miss the actual migration entirely.
            repeat(90) {
                frame()
                ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
                assertEquals("Completion preserves the real content anchor", anchor, anchorTop(), 2f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        assertEquals("Anchor remains stable after idle", anchor, anchorTop(), 2f)
        assertEquals(0f, innerOffset(), 1f)
    }

    fun testExactTailShowsContentAndExactHistoryPreemptsCompletionMigration() {
        mount(interactive = true)
        mainThread { request = ChatScrollRequest(2, messageId = "tail", followBottom = false) }
        ui.waitForIdle()
        assertEquals("Exact tail starts at actual content, not empty extent", 0f, innerOffset(), 1f)
        ui.onNodeWithTag("inner-start").assertIsDisplayed()
        assertTrue("Actual body origin is in the viewport", tailTop() >= -1f)
        mainThread { request = ChatScrollRequest(3) }
        ui.waitForIdle()
        manualNonzeroPosition()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { running = false }
            frame()
            ui.runOnUiThread { request = ChatScrollRequest(4, messageId = "u1", followBottom = false) }
            // Bound the exact animation AND check later frames for a stale
            // migration/follower taking ownership back after it has finished.
            repeat(180) { frame() }
            ui.onNodeWithText("history 1").assertIsDisplayed()
            val historyTop = ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top
            repeat(30) {
                frame()
                ui.onNodeWithText("history 1").assertIsDisplayed()
                assertEquals(historyTop, ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top, 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testCompressionAndNewAssistantRetainOldManualAnchor() {
        mount(interactive = true)
        manualNonzeroPosition()
        val anchor = anchorTop()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { running = false; showCompression = true }
            repeat(12) {
                frame()
                ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
                assertEquals("Compression must not unwrap the old viewport", anchor, anchorTop(), 2f)
            }
            val retainedOffset = innerOffset()
            ui.runOnUiThread { appendAssistant = true }
            repeat(12) {
                frame()
                ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
                assertEquals("New assistant must not unwrap the old viewport", anchor, anchorTop(), 2f)
                assertEquals("Retired inner position remains owned by the reader", retainedOffset, innerOffset(), 1f)
            }
            ui.runOnUiThread { request = ChatScrollRequest(5, messageId = "u1", followBottom = false) }
            repeat(180) { frame() }
            ui.onNodeWithText("history 1").assertIsDisplayed()
            val target = ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top
            repeat(30) {
                frame()
                assertEquals("Retired migration cannot steal a new exact request", target,
                    ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top, 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testTouchAndExactNavigationRetainScrollOwnership() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { height += 120.dp }
            repeat(2) { frame() }
            assertTailClearance()
            ui.onNodeWithTag("chat-messages").performTouchInput { click(center) }
            val stopped = tailTop()
            ui.runOnUiThread { height += 300.dp }
            repeat(4) {
                frame()
                assertEquals("Touch disables occlusion correction as well as smooth follow", stopped, tailTop(), 1f)
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        assertTailClearance()
        mainThread { request = ChatScrollRequest(serial = 3, messageId = "u1", followBottom = false) }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        mainThread { height += 300.dp }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        ui.onNodeWithTag("occlusion-tail").assertIsNotDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testFollowGapStaysFixedWhenNewContentArrivesDuringDisclosureAndAfterHandoff() {
        verifyDisclosureGap(500)
    }

    fun testShortDisclosureCatchupUsesTheSameFrameBudget() {
        verifyDisclosureGap(60)
    }

    fun testBoundedViewportPublishesFullExtentWhenOnlyTheContentHeightChanges() {
        var growth by mutableStateOf(0.dp)
        val state = StreamingTailViewport(true)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            val boundedHeight = with(LocalDensity.current) { 80.dp.roundToPx() }
            Box(Modifier.size(320.dp, 420.dp)) {
                Column(Modifier.testTag("extent-parent")) {
                    StreamingTailContainer(state, { null }, "extent", false, { false }, { 0 }, { boundedHeight }) {
                        Box(Modifier.fillMaxWidth().height(160.dp + growth))
                    }
                }
            }
        } } }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        try {
            repeat(12) { index ->
                ui.runOnUiThread { growth = ((index + 1) * 6).dp }
                frame()
                val expected = (160 + (index + 1) * 6) * density
                val parent = ui.onNodeWithTag("extent-parent").fetchSemanticsNode()
                val viewport = ui.onNodeWithTag("chat-stream-viewport:extent").fetchSemanticsNode()
                assertEquals("Full extent must be published in the same frame", expected, parent.size.height.toFloat(), 1f)
                assertEquals("The interactive viewport must remain bounded", 80f * density, viewport.size.height.toFloat(), 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
    }

    private fun verifyDisclosureGap(bodyHeight: Int) {
        var appended by mutableStateOf(0.dp)
        var streaming by mutableStateOf(true)
        val work = WorkProcessUi(emptyList(), "已完成")
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            Box(Modifier.size(320.dp, 420.dp).testTag("gap-host")) {
                ChatConversationLayers(Modifier.size(320.dp, 420.dp), messages = { inset ->
                    ChatMessageList(List(6) { ChatTurnUi.User("gap-history-$it", "history $it") } +
                        ChatTurnUi.Assistant("gap-tail", status = if (streaming) RunStatus.RUNNING else RunStatus.COMPLETED,
                            isStreaming = streaming),
                        ChatScreenActions(), scrollRequest = ChatScrollRequest(1), composerBottomInset = inset,
                        turnExtras = {
                            WorkProcessCard(work, "gap-work", content = {
                                Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Magenta))
                            })
                            Box(Modifier.fillMaxWidth().height(bodyHeight.dp + appended).background(Color.Magenta))
                            Box(Modifier.fillMaxWidth().height(8.dp).background(Color.Magenta).testTag("gap-end"))
                        })
                }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("gap-composer")) })
            }
        } } }
        ui.waitForIdle()
        // Navigate within the bounded tail without a new touch so opening preserves the following intent.
        // The long fixture's top header may be offscreen; use its semantic action to begin the same disclosure policy.
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("work-process-header").performSemanticsAction(SemanticsActions.OnClick) { it() }
            ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
            ui.onNodeWithTag("work-process-header").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "已展开"))
            ui.runOnUiThread { appended = 52.dp }
            var previous: Float? = null
            var sawUnbounded = false
            repeat(90) { index ->
                if (index == 2) ui.runOnUiThread { streaming = false }
                if (index == 60) {
                    val viewport = ui.onNodeWithTag("chat-stream-viewport:gap-tail").fetchSemanticsNode()
                    assertEquals("Terminal catchup must really unwrap before the next disclosure: " +
                        ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[WorkflowDisclosureGeometry].invoke(), 0f,
                        viewport.config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), 1f)
                    assertTrue("Unbounded viewport must retain its full content height",
                        viewport.layoutInfo.coordinates.size.height >= (bodyHeight + 60) * density)
                    sawUnbounded = true
                    ui.onNodeWithTag("work-process-header").performSemanticsAction(SemanticsActions.OnClick) { it() }
                    ui.runOnUiThread { appended += 52.dp }
                }
                frame()
                val host = ui.onNodeWithTag("gap-host")
                val hostBounds = host.fetchSemanticsNode().boundsInRoot
                val composer = ui.onNodeWithTag("gap-composer").fetchSemanticsNode().boundsInRoot
                val pixels = host.captureToImage().toPixelMap()
                val x = (hostBounds.width / 2f).toInt()
                val gapTop = (composer.top - hostBounds.top - ChatTailClearance.value * density + 2f).toInt()
                val gapBottom = (composer.top - hostBounds.top - 2f).toInt()
                for (y in gapTop..gapBottom) {
                    val sample = pixels[x, y]
                    if (sample.red > .9f && sample.blue > .9f && sample.green < .1f) {
                        fail("Following output cannot paint into any part of the fixed composer gap: frame=$index y=$y body=$bodyHeight " +
                            ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[WorkflowDisclosureGeometry].invoke())
                    }
                }
                val end = ui.onNodeWithTag("gap-end").fetchSemanticsNode().layoutInfo.coordinates
                val bottom = end.positionInRoot().y + end.size.height
                previous?.let { before ->
                    assertTrue("Disclosure handoff cannot snap the appended backlog in one frame: frame=$index body=$bodyHeight " +
                        "before=$before bottom=$bottom upDp=${(before - bottom) / density} streaming=$streaming appended=$appended unbounded=$sawUnbounded " +
                        ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[WorkflowDisclosureGeometry].invoke(),
                        before - bottom <= 14f * density)
                }
                previous = bottom
            }
            assertTrue("The fixture must exercise terminal unbounded handoff", sawUnbounded)
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        val end = ui.onNodeWithTag("gap-end").fetchSemanticsNode().layoutInfo.coordinates
        val composer = ui.onNodeWithTag("gap-composer").fetchSemanticsNode().boundsInRoot
        assertEquals("The final visible tail uses the same fixed gap", ChatTailClearance.value,
            (composer.top - end.positionInRoot().y - end.size.height) / density, 2f / density)
    }

}
