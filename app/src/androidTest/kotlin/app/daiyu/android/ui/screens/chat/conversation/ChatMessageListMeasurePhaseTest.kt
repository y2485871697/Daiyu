package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.basic.Text

@Suppress("DEPRECATION")
class ChatMessageListMeasurePhaseTest : WiredActivityTestCase() {


    private fun mount(content: @Composable () -> Unit) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BoxWithConstraints(Modifier.size(320.dp, 240.dp)) {
                        androidx.compose.foundation.layout.Box(Modifier.size(maxWidth, maxHeight)) { content() }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun mountStreamingViewport(
        state: StreamingTailViewport,
        measures: java.util.concurrent.atomic.AtomicInteger,
        viewportHeight: () -> androidx.compose.ui.unit.Dp = { 120.dp },
        contentHeight: () -> androidx.compose.ui.unit.Dp = { 1000.dp },
    ) {
        mount {
            val density = androidx.compose.ui.platform.LocalDensity.current
            StreamingTailContainer(state, { null }, "measure-probe", true, { false }, { 0 },
                viewportHeight = { with(density) { viewportHeight().roundToPx() } },
                onViewportMeasure = { measures.incrementAndGet() }) {
                Box(Modifier.size(100.dp, contentHeight()))
            }
        }
    }

    fun testStreamingViewportOffsetScrollDoesNotRemeasureViewport() {
        val state = StreamingTailViewport(initiallyBounded = true)
        val measures = java.util.concurrent.atomic.AtomicInteger()
        mountStreamingViewport(state, measures)
        val settledMeasures = measures.get()
        assertTrue(settledMeasures > 0)
        mainThread { assertEquals(0f, state.scrollOffset, 0.001f); assertTrue(state.maxOffset > 84) }
        repeat(12) { step ->
            mainThread {
                val before = state.scrollOffset
                assertEquals(-7f, checkNotNull(state.scroller).dispatchRawDelta(-7f), 0.001f)
                assertEquals(before + 7f, state.scrollOffset, 0.001f)
            }
            ui.waitForIdle()
            assertEquals("offset-only scroll must not remeasure viewport at step ${step + 1}", settledMeasures, measures.get())
        }
    }

    fun testStreamingViewportGeometryChangesStillRemeasureAndClampOffset() {
        val state = StreamingTailViewport(initiallyBounded = true)
        val measures = java.util.concurrent.atomic.AtomicInteger()
        var viewportHeight by mutableStateOf(120.dp)
        var contentHeight by mutableStateOf(1000.dp)
        mountStreamingViewport(state, measures, { viewportHeight }, { contentHeight })
        var maximum = 0
        var bodyHeight = 0
        mainThread {
            maximum = state.maxOffset; bodyHeight = state.contentHeight
            assertTrue(maximum > 0)
            val delta = -maximum.toFloat()
            assertEquals(delta, checkNotNull(state.scroller).dispatchRawDelta(delta), 0.001f)
            assertEquals(maximum.toFloat(), state.scrollOffset, 0.001f)
        }
        ui.waitForIdle()
        val beforeResize = measures.get()
        mainThread { viewportHeight = 200.dp }
        ui.waitForIdle()
        assertTrue(measures.get() > beforeResize)
        ui.onNodeWithTag("chat-stream-viewport:measure-probe").assertHeightIsEqualTo(200.dp)
        mainThread {
            assertEquals(bodyHeight, state.contentHeight)
            assertTrue(state.maxOffset in 1 until maximum)
            assertEquals(state.maxOffset.toFloat(), state.scrollOffset, 0.001f)
        }
        val beforeShrink = measures.get()
        mainThread { contentHeight = 80.dp }
        ui.waitForIdle()
        assertTrue(measures.get() > beforeShrink)
        ui.onNodeWithTag("chat-stream-viewport:measure-probe").assertHeightIsEqualTo(80.dp)
        mainThread { assertEquals(0, state.maxOffset); assertEquals(0f, state.scrollOffset, 0.001f) }
    }

    fun testBoundedContentGrowthUpdatesLazyItemExtentWithoutChangingViewportHeight() {
        val state = StreamingTailViewport(initiallyBounded = true)
        val listState = androidx.compose.foundation.lazy.LazyListState()
        var contentHeight by mutableStateOf(400.dp)
        var testDensity: androidx.compose.ui.unit.Density? = null
        mount {
            val density = androidx.compose.ui.platform.LocalDensity.current
            testDensity = density
            androidx.compose.foundation.lazy.LazyColumn(state = listState) {
                item("bounded-tail") {
                    StreamingTailContainer(state, { null }, "growth-probe", true,
                        follows = { false }, clearance = { 0 },
                        viewportHeight = { with(density) { 120.dp.roundToPx() } }) {
                        Box(Modifier.size(100.dp, contentHeight))
                    }
                }
            }
        }
        for (height in listOf(400.dp, 900.dp, 500.dp)) {
            val expectedHeight = with(checkNotNull(testDensity)) { height.roundToPx() }
            mainThread { contentHeight = height }
            ui.waitForIdle()
            ui.onNodeWithTag("chat-stream-viewport:growth-probe").assertHeightIsEqualTo(120.dp)
            mainThread {
                assertEquals(expectedHeight, state.contentHeight)
                assertEquals("LazyColumn must retain the new full extent while V stays fixed",
                    expectedHeight, listState.layoutInfo.visibleItemsInfo.single().size)
            }
        }
    }

    fun testPrefetchedStreamingTailMeasuresWithoutPlacingItsContent() {
        val state = StreamingTailViewport(initiallyBounded = true)
        val subcomposition = androidx.compose.ui.layout.SubcomposeLayoutState()
        val placements = java.util.concurrent.atomic.AtomicInteger()
        val measures = java.util.concurrent.atomic.AtomicInteger()
        var activate by mutableStateOf(false)
        var widthPx = 0
        var viewportPx = 0
        var bodyPx = 0
        val prefetchedBody: @Composable () -> Unit = {
            StreamingTailContainer(state, { null }, "prefetch-probe", true,
                follows = { false }, clearance = { 0 }, viewportHeight = { viewportPx },
                onViewportMeasure = { measures.incrementAndGet() }) {
                androidx.compose.ui.layout.Layout(content = {}) { _, _ ->
                    layout(widthPx, bodyPx) { placements.incrementAndGet() }
                }
            }
        }
        mount {
            val density = androidx.compose.ui.platform.LocalDensity.current
            widthPx = with(density) { 100.dp.roundToPx() }
            viewportPx = with(density) { 120.dp.roundToPx() }
            bodyPx = with(density) { 1000.dp.roundToPx() }
            androidx.compose.ui.layout.SubcomposeLayout(state = subcomposition) { constraints ->
                val body = if (activate) subcompose("prefetched-tail", prefetchedBody).single()
                    .measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                    else null
                layout(constraints.maxWidth, constraints.maxHeight) { body?.placeRelative(0, 0) }
            }
        }
        var disposePrefetch: () -> Unit = { }
        try {
            mainThread {
                val slot = subcomposition.precompose("prefetched-tail", prefetchedBody)
                disposePrefetch = { slot.dispose() }
                assertEquals(1, slot.placeablesCount)
                slot.premeasure(0, androidx.compose.ui.unit.Constraints.fixedWidth(widthPx))
                assertTrue("The prefetch must really measure the viewport", measures.get() > 0)
                assertEquals(bodyPx, state.contentHeight)
                assertEquals("Premeasure must not force placement to resolve a height alignment line",
                    0, placements.get())
            }
            mainThread { activate = true }
            ui.waitForIdle()
            assertTrue("The same prefetched body must place when its slot becomes visible", placements.get() > 0)
        } finally {
            mainThread { disposePrefetch() }
        }
    }

    fun testFollowGateChangeAfterMeasureCannotPublishHalfATailTransform() {
        val state = StreamingTailViewport(initiallyBounded = true)
        val drawnOrigins = java.util.Collections.synchronizedList(mutableListOf<Float>())
        var bodyCoordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
        var following = true
        mount {
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(Modifier.onPlaced { following = false }) {
                StreamingTailContainer(state, { null }, "follow-gate", true,
                    follows = { following }, clearance = { 0 },
                    viewportHeight = { with(density) { 120.dp.roundToPx() } }) {
                    Box(Modifier.size(100.dp, 1000.dp).testTag("follow-gate-body")
                        .onPlaced { bodyCoordinates = it }.drawWithContent {
                            drawContent()
                            drawnOrigins.add(checkNotNull(state.viewportCoordinates).localPositionOf(
                                checkNotNull(bodyCoordinates), androidx.compose.ui.geometry.Offset.Zero).y)
                        })
                }
            }
        }
        mainThread {
            assertTrue(state.maxOffset > 0)
            assertEquals("No measure-only follow offset may leak into reading placement", 0f,
                state.viewportOffset - state.scrollOffset, 0.001f)
        }
        val draws = synchronized(drawnOrigins) { drawnOrigins.toList() }
        assertTrue("Must observe the real body draw", draws.isNotEmpty())
        draws.forEach { assertEquals("A follow gate cannot expose half a placed transform", 0f, it, 1f) }
        val body = ui.onNodeWithTag("follow-gate-body").fetchSemanticsNode()
        val viewport = ui.onNodeWithTag("chat-stream-viewport:follow-gate").fetchSemanticsNode()
        assertEquals("The actual body, not just its outer item, keeps the reading anchor",
            viewport.layoutInfo.coordinates.positionInRoot().y,
            body.layoutInfo.coordinates.positionInRoot().y, 1f)
    }

    fun testFollowingCatchupKeepsGrowingCardAndLastLineInsidePhysicalSafeEdge() {
        val state = StreamingTailViewport(initiallyBounded = true)
        var following by mutableStateOf(false)
        var protect by mutableStateOf(true)
        var phase by mutableStateOf(0)
        var hostCoordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
        var pixelsPerDp = 1f
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    pixelsPerDp = androidx.compose.ui.platform.LocalDensity.current.density
                    Box(Modifier.size(320.dp, 240.dp)
                        .background(androidx.compose.ui.graphics.Color.White).clipToBounds()
                        .onPlaced { hostCoordinates = it }.testTag("catchup-clip-host")) {
                        StreamingTailContainer(state, { hostCoordinates }, "catchup-clip", false,
                            follows = { following }, protectClearance = { protect },
                            clearance = { (ChatTailClearance.value * pixelsPerDp).toInt() },
                            viewportHeight = { (120f * pixelsPerDp).toInt() },
                            listViewportHeight = { (240f * pixelsPerDp).toInt() }) {
                            WorkProcessCard(WorkProcessUi(emptyList(), summary = "进行中", running = true),
                                ownerKey = "catchup-clip-card", content = {
                                    Box(Modifier.fillMaxWidth().height((104 + phase * 16).dp)
                                        .background(androidx.compose.ui.graphics.Color.Cyan),
                                        contentAlignment = androidx.compose.ui.Alignment.BottomStart) {
                                        Text(List(phase + 1) { "新增末行文字完整显示" }.joinToString("\n"),
                                            Modifier.padding(8.dp).testTag("catchup-last-lines"),
                                            color = androidx.compose.ui.graphics.Color.Black)
                                    }
                                })
                            Box(Modifier.fillMaxWidth().height(160.dp)
                                .background(androidx.compose.ui.graphics.Color.Cyan))
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        fun physical(tag: String): androidx.compose.ui.geometry.Rect {
            val coordinates = ui.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
            val origin = coordinates.positionInRoot()
            return androidx.compose.ui.geometry.Rect(origin.x, origin.y,
                origin.x + coordinates.size.width, origin.y + coordinates.size.height)
        }
        fun save(name: String, image: androidx.compose.ui.graphics.ImageBitmap) {
            val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics/workflow-catchup")
                .apply { mkdirs() }
            java.io.File(directory, "$name.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val failures = mutableListOf<String>()
        repeat(3) { next ->
            mainThread { following = false; protect = true; phase = next }
            ui.waitForIdle()
            mainThread { following = true }
            ui.waitForIdle()
            val host = physical("catchup-clip-host")
            val viewport = physical("chat-stream-viewport:catchup-clip")
            val card = physical("work-process-surface")
            val text = physical("catchup-last-lines")
            val safeBottom = host.bottom - ChatTailClearance.value * pixelsPerDp
            assertTrue("fixture must keep a real protected catchup", state.followCatchup && following && protect)
            assertEquals(0f, state.scrollOffset, .001f)
            assertEquals(0, state.viewportOffset)
            assertTrue("full content must still be awaiting outer catchup", state.contentHeight > host.height)
            assertTrue("the new last line must cross the measured boundary", text.bottom > viewport.bottom + 8f * pixelsPerDp)
            assertTrue("the complete card must fit inside the physical safe edge", card.bottom < safeBottom - 4f * pixelsPerDp)
            val observed = captureScreenNode(ui.onNodeWithTag("catchup-clip-host"))
            save("phase-$next-following", observed)
            mainThread { following = false; protect = false }
            ui.waitForIdle()
            assertEquals("reference must keep the same card position", card, physical("work-process-surface"))
            assertEquals("reference must keep the same text layout", text, physical("catchup-last-lines"))
            val reference = captureScreenNode(ui.onNodeWithTag("catchup-clip-host"))
            save("phase-$next-reference", reference)
            val actual = observed.toPixelMap()
            val expected = reference.toPixelMap()
            fun mismatches(region: androidx.compose.ui.geometry.Rect): Int {
                var count = 0
                for (y in kotlin.math.ceil(region.top - host.top).toInt() until kotlin.math.floor(region.bottom - host.top).toInt()) {
                    for (x in kotlin.math.ceil(region.left - host.left).toInt() until kotlin.math.floor(region.right - host.left).toInt()) {
                        val a = actual[x, y]
                        val b = expected[x, y]
                        if (maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green),
                                kotlin.math.abs(a.blue - b.blue)) > .025f) count++
                    }
                }
                return count
            }
            val missingText = mismatches(text)
            val missingBorder = mismatches(androidx.compose.ui.geometry.Rect(card.left, card.bottom - 3f * pixelsPerDp,
                card.right, card.bottom))
            var clearanceInk = 0
            for (y in kotlin.math.ceil(safeBottom - host.top + 1f).toInt() until actual.height) {
                for (x in 0 until actual.width) {
                    val color = actual[x, y]
                    if (minOf(color.red, color.green, color.blue) < .98f) clearanceInk++
                }
            }
            android.util.Log.i("WorkflowCatchupClip", "phase=$next measuredBottom=${viewport.bottom} " +
                "textBottom=${text.bottom} cardBottom=${card.bottom} safeBottom=$safeBottom " +
                "missingText=$missingText missingBorder=$missingBorder clearanceInk=$clearanceInk")
            if (missingText != 0 || missingBorder != 0 || clearanceInk != 0) {
                failures += "phase=$next missingText=$missingText missingBorder=$missingBorder clearanceInk=$clearanceInk"
            }
        }
        assertTrue("Following catchup must not cut new text or the rounded card inside the safe edge: $failures", failures.isEmpty())
        assertEquals(0, fixture.opens.get())
    }

    fun testRealDisclosureGateCannotClipSafeTailWithNonzeroFollowTransform() {
        var count by mutableStateOf(18)
        var geometry: () -> String = { "" }
        var pixelsPerDp = 1f
        fun turn(): ChatTurnUi.Assistant {
            val blocks = buildList<app.daiyu.android.domain.MessageBlock> {
                repeat(count) { index ->
                    add(app.daiyu.android.domain.MessageBlock.Tool(app.daiyu.android.domain.ToolInvocation(
                        app.daiyu.android.domain.ToolCallId("gate-tool-$index"), "lookup", "{}"), "done"))
                    if (index % 3 == 2) add(app.daiyu.android.domain.MessageBlock.Text(
                        "gate-narration-$index", "这是一段夹在工具节点之间的普通叙述。"))
                }
            }
            val message = app.daiyu.android.domain.Message(app.daiyu.android.domain.MessageId("gate-tail"),
                app.daiyu.android.domain.ConversationId("gate-conversation"), app.daiyu.android.domain.RunId("gate-run"),
                "assistant", blocks, app.daiyu.android.domain.RunStatus.RUNNING)
            return (ChatTurnMapper.map(listOf(message)).single() as ChatTurnUi.Assistant).copy(isStreaming = true)
        }
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    pixelsPerDp = androidx.compose.ui.platform.LocalDensity.current.density
                    Box(Modifier.size(320.dp, 420.dp).background(androidx.compose.ui.graphics.Color.White)
                        .testTag("gate-real-host")) {
                        ChatConversationLayers(Modifier.size(320.dp, 420.dp), messages = { inset ->
                            ChatMessageList(List(6) { ChatTurnUi.User("gate-history-$it", "history $it") } + turn(),
                                ChatScreenActions(), scrollRequest = ChatScrollRequest(1), composerBottomInset = inset,
                                turnExtras = {
                                    LocalWorkflowDisclosureContext.current?.viewport?.let { viewport ->
                                        geometry = { viewport.diagnostics() }
                                    }
                                    Box(Modifier.testTag("gate-real-disclosure")) {
                                        WorkProcessCard(WorkProcessUi(emptyList(), "已完成"), ownerKey = "gate-disclosure",
                                            content = { Box(Modifier.fillMaxWidth().height(80.dp)) })
                                    }
                                    Text("末行应当完整绘制", Modifier.testTag("gate-real-last-line"))
                                    Box(Modifier.fillMaxWidth().height(2.dp).background(androidx.compose.ui.graphics.Color.Magenta)
                                        .testTag("gate-real-end"))
                                })
                        }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("gate-real-composer")) })
                    }
                }
            }
        }
        ui.waitForIdle()
        fun number(diagnostic: String, name: String): Float =
            Regex("\\b$name=([0-9]+(?:\\.[0-9]+)?)").find(diagnostic)?.groupValues?.get(1)?.toFloat()
                ?: error("Missing $name: $diagnostic")
        val settled = geometry()
        assertTrue("Must start as the actual follower: $settled", settled.contains("follow=true canFollow=true"))
        assertTrue("Must not repeat the old zero-offset fixture: $settled", number(settled, "a") > 1f && number(settled, "q") > 1f)
        fun physical(tag: String): androidx.compose.ui.geometry.Rect {
            val c = ui.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
            val p = c.positionInRoot()
            return androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
        }
        var eligibleFrames = 0
        val failures = mutableListOf<String>()
        ui.mainClock.autoAdvance = false
        try {
            ui.onNode(hasTestTag("work-process-header") and hasAnyAncestor(hasTestTag("gate-real-disclosure")))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            repeat(64) { index ->
                if (index == 1) ui.runOnUiThread { count = 19 }
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                val beforeCapture = geometry()
                if (!beforeCapture.contains("follow=true canFollow=false") || !beforeCapture.contains("catchup=true") ||
                    number(beforeCapture, "a") <= 1f || number(beforeCapture, "q") <= 1f) return@repeat
                val image = captureScreenNode(ui.onNodeWithTag("gate-real-host"))
                val diagnostic = geometry()
                if (!diagnostic.contains("follow=true canFollow=false") || !diagnostic.contains("catchup=true") ||
                    number(diagnostic, "a") <= 1f || number(diagnostic, "q") <= 1f) return@repeat
                val end = physical("gate-real-end")
                val viewport = physical("chat-stream-viewport:${turn().listKey}")
                val safeBottom = physical("gate-real-composer").top - ChatTailClearance.value * pixelsPerDp
                val sampleY = end.center.y
                val line = "frame=$index end=$end V=$viewport safe=$safeBottom $diagnostic"
                android.util.Log.i("WorkflowDisclosureGateClip", line)
                if (end.bottom > safeBottom + 1f || sampleY <= viewport.bottom + 1f) return@repeat
                eligibleFrames++
                val root = physical("gate-real-host")
                val pixels = image.toPixelMap()
                val color = pixels[(end.center.x - root.left).toInt(), (sampleY - root.top).toInt()]
                if (!(color.red > .9f && color.blue > .9f && color.green < .1f)) {
                    failures += line
                    val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics/workflow-gated-catchup").apply { mkdirs() }
                    java.io.File(directory, "frame-$index.png").outputStream().use {
                        image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            assertTrue("Must observe real disclosure gating with nonzero a/q and safe content below V", eligibleFrames > 0)
            assertTrue("A fully measured tail inside the physical safe edge was clipped: $failures", failures.isEmpty())
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    fun testActiveShortCardKeepsItsBottomCornersAndLastLineAboveGrowingComposer() {
        var composerHeight by mutableStateOf(96.dp)
        var negativeClip by mutableStateOf<Float?>(null)
        var pixelsPerDp = 1f
        var diagnostics: () -> String = { "" }
        var anchorActive: () -> Boolean = { false }
        val tail = ChatTurnUi.Assistant("short-active-tail", finalText = "当前终端任务",
            status = app.daiyu.android.domain.RunStatus.RUNNING, isStreaming = true)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                pixelsPerDp = androidx.compose.ui.platform.LocalDensity.current.density
                Box(Modifier.size(320.dp, 420.dp).background(androidx.compose.ui.graphics.Color.White)
                    .drawWithContent {
                        clipRect(bottom = negativeClip ?: size.height) { this@drawWithContent.drawContent() }
                    }.testTag("short-active-host")) {
                    ChatConversationLayers(Modifier.fillMaxSize(), messages = { inset ->
                        ChatMessageList(List(6) { ChatTurnUi.User("short-active-history-$it", "history $it") } + tail,
                            ChatScreenActions(), scrollRequest = ChatScrollRequest(1), composerBottomInset = inset,
                            turnExtras = {
                                LocalWorkflowDisclosureContext.current?.viewport?.let { viewport ->
                                    diagnostics = { viewport.diagnostics() }
                                    anchorActive = { viewport.active != null }
                                }
                                WorkProcessCard(WorkProcessUi(emptyList(), "进行中", running = true), "short-active-card",
                                    content = {
                                        WorkStepRow(WorkStepUi("short-active-tool", "执行命令 · Android • user", "", category = "terminal",
                                            executionTargetLabel = "Android • user"),
                                            toolContent = { Box(Modifier.fillMaxWidth().height(1.dp)) })
                                        Text("最后一行必须完整显示", Modifier.testTag("short-active-last-line"),
                                            color = androidx.compose.ui.graphics.Color.Black)
                                    })
                            })
                    }, composer = {
                        Box(Modifier.fillMaxWidth().height(composerHeight).testTag("short-active-composer"))
                    })
                }
            }
        } }
        ui.waitForIdle()
        fun physical(tag: String): androidx.compose.ui.geometry.Rect {
            val c = ui.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
            val p = c.positionInRoot()
            return androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
        }
        fun save(name: String, image: androidx.compose.ui.graphics.ImageBitmap) {
            val dir = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics/short-active-card").apply { mkdirs() }
            java.io.File(dir, "$name.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("tool-step-header:short-active-tool")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            ui.mainClock.advanceTimeBy(64); ui.waitForIdle()
            assertTrue("The actual disclosure must own scrolling: ${diagnostics()}", anchorActive())
            assertTrue("The normal follower must really be gated: ${diagnostics()}",
                diagnostics().contains("follow=true canFollow=false"))
            val referenceCard = physical("work-process-surface")
            val referenceText = physical("short-active-last-line")
            val host = physical("short-active-host")
            val reference = captureScreenNode(ui.onNodeWithTag("short-active-host"))
            save("reference-before-inset", reference)
            mainThread { composerHeight = 136.dp }
            ui.waitForIdle()
            val card = physical("work-process-surface")
            val text = physical("short-active-last-line")
            val safe = physical("short-active-composer").top - ChatTailClearance.value * pixelsPerDp
            assertTrue("This must remain a short bounded tail: ${diagnostics()}",
                physical("chat-turn:${tail.id}").height < safe - physical("chat-messages").top - 11f * pixelsPerDp)
            assertTrue("The fix must work before the disclosure gate releases: ${diagnostics()}", anchorActive())
            assertEquals("Inset correction must not change the frozen card height", referenceCard.height, card.height, 1f)
            assertTrue("The whole card, not merely its indicator, must clear the composer: card=$card safe=$safe ${diagnostics()}",
                card.bottom <= safe + 1f)
            for (tag in listOf("reply-identity:${tail.listKey}", "work-process-header", "short-active-last-line")) {
                val node = ui.onNodeWithTag(tag).fetchSemanticsNode()
                assertEquals("No header or last line may be cropped: $tag", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
            }
            val observed = captureScreenNode(ui.onNodeWithTag("short-active-host"))
            save("protected-after-inset", observed)
            val expected = reference.toPixelMap()
            fun mismatches(image: androidx.compose.ui.graphics.ImageBitmap, region: androidx.compose.ui.geometry.Rect,
                referenceRegion: androidx.compose.ui.geometry.Rect): Int {
                val actual = image.toPixelMap()
                var count = 0
                val dx = (referenceRegion.left - region.left).toInt()
                val dy = (referenceRegion.top - region.top).toInt()
                for (y in kotlin.math.ceil(region.top - host.top).toInt() until kotlin.math.floor(region.bottom - host.top).toInt()) {
                    for (x in kotlin.math.ceil(region.left - host.left).toInt() until kotlin.math.floor(region.right - host.left).toInt()) {
                        val a = actual[x, y]; val b = expected[x + dx, y + dy]
                        if (maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue)) > .025f) count++
                    }
                }
                return count
            }
            val border = card.copy(top = card.bottom - 3f * pixelsPerDp)
            val referenceBorder = referenceCard.copy(top = referenceCard.bottom - 3f * pixelsPerDp)
            assertEquals("The full bottom strip, including both rounded corners, must match its unclipped reference", 0,
                mismatches(observed, border, referenceBorder))
            assertEquals("Every last-line ink pixel must survive the ancestor clips", 0, mismatches(observed, text, referenceText))
            mainThread { negativeClip = card.bottom - host.top - 2f }
            ui.waitForIdle()
            val clipped = captureScreenNode(ui.onNodeWithTag("short-active-host"))
            save("negative-bottom-two-pixels", clipped)
            assertTrue("The same pixel probe must reject an actual 2px bottom clip",
                mismatches(clipped, border, referenceBorder) > 0)
            mainThread { negativeClip = null }
            repeat(32) {
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertTrue("Rebased anchor must not pull the card back below the safe edge: ${diagnostics()}",
                    physical("work-process-surface").bottom <= safe + 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    fun testCurrentPlacementOriginDefinesTheFirstDrawnTailSlice() {
        val state = StreamingTailViewport(initiallyBounded = true)
        val origins = java.util.Collections.synchronizedList(mutableListOf<Pair<Float, Float>>())
        var host: androidx.compose.ui.layout.LayoutCoordinates? = null
        var body: androidx.compose.ui.layout.LayoutCoordinates? = null
        var expected = 0f
        mainThread { state.itemOrigin = 100f }
        mount {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val viewportPx = with(density) { 120.dp.roundToPx() }
            val bodyPx = with(density) { 1000.dp.roundToPx() }
            expected = 100f - (bodyPx - viewportPx)
            Box(Modifier.onPlaced { host = it; state.itemOrigin = -100f }) {
                Box(Modifier.wrapContentHeight(align = androidx.compose.ui.Alignment.Top, unbounded = true)) {
                    StreamingTailContainer(state, { null }, "current-origin", true,
                        follows = { true }, clearance = { 0 }, viewportHeight = { viewportPx }) {
                        Box(Modifier.size(100.dp, 1000.dp).onPlaced { body = it }.drawWithContent {
                            drawContent()
                            val actual = checkNotNull(host).localPositionOf(checkNotNull(body),
                                androidx.compose.ui.geometry.Offset.Zero).y
                            origins.add(actual to (state.viewportOffset - state.scrollOffset))
                        })
                    }
                }
            }
        }
        val drawn = synchronized(origins) { origins.toList() }
        assertTrue("Observe the first draw, not only settled state", drawn.isNotEmpty())
        drawn.forEach { (actual, requested) ->
            assertEquals("The prior positive origin cannot shrink a new negative-origin slice", expected, requested, 1f)
            assertEquals("The actual body must use the same transform in every draw", requested, actual, 1f)
        }
    }

    private fun rows(): List<ChatTurnUi> = List(20) { ChatTurnUi.User("u$it", "row $it") }

    fun testInitialBottomRequestUnderSubcompositionMeasuresWithoutReentry() {
        mount { ChatMessageList(rows(), ChatScreenActions(), scrollRequest = ChatScrollRequest(1)) }
        ui.onNodeWithText("row 19").assertIsDisplayed()
        ui.onNodeWithText("row 0").assertIsNotDisplayed()
    }

    fun testInitialExactTargetRequestDoesNotGetOverriddenByFollow() {
        mount {
            ChatMessageList(rows(), ChatScreenActions(),
                scrollRequest = ChatScrollRequest(serial = 1, messageId = "u2", followBottom = false))
        }
        ui.onNodeWithText("row 2").assertIsDisplayed()
        ui.onNodeWithText("row 19").assertIsNotDisplayed()
    }

    fun testBottomRequestRemainsPendingUntilRowsArrive() {
        var turns by mutableStateOf<List<ChatTurnUi>>(emptyList())
        mount { ChatMessageList(turns, ChatScreenActions(), scrollRequest = ChatScrollRequest(1)) }
        mainThread { turns = rows() }
        ui.waitForIdle()
        ui.onNodeWithText("row 19").assertIsDisplayed()
    }

    fun testSameCountTailHeightGrowthStillFollowsItsMeasuredBottom() {
        var extraHeight by mutableStateOf(40.dp)
        mount {
            ChatMessageList(listOf(ChatTurnUi.Assistant("a", finalText = "answer")),
                ChatScreenActions(), scrollRequest = ChatScrollRequest(1), turnExtras = {
                    Box(Modifier.height(extraHeight))
                    Text("measured tail")
                })
        }
        ui.onNodeWithText("measured tail").assertIsDisplayed()
        mainThread { extraHeight = 900.dp }
        ui.waitForIdle()
        ui.onNodeWithText("measured tail").assertIsDisplayed()
    }
    fun testTouchStopsHeightGrowthFollowAndExplicitBottomRequestRestoresIt() {
        var extraHeight by mutableStateOf(40.dp)
        var request by mutableStateOf(ChatScrollRequest(1))
        mount {
            ChatMessageList(listOf(ChatTurnUi.Assistant("a", finalText = "answer")),
                ChatScreenActions(), scrollRequest = request, turnExtras = {
                    Box(Modifier.height(extraHeight))
                    Text("touch controlled tail")
                })
        }
        ui.onNodeWithText("touch controlled tail").assertIsDisplayed()
        ui.onNodeWithTag("chat-messages").performTouchInput { click() }
        mainThread { extraHeight = 900.dp }
        ui.waitForIdle()
        ui.onNodeWithText("touch controlled tail").assertIsNotDisplayed()
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        ui.onNodeWithText("touch controlled tail").assertIsDisplayed()
    }

    fun testReadAuthorizationChangeRechecksAlreadyMeasuredCompletedTurn() {
        val terminal = ChatTurnUi.Assistant("completed", finalText = "already measured answer",
            status = app.daiyu.android.domain.RunStatus.COMPLETED)
        var allowed by mutableStateOf(false)
        val refused = java.util.concurrent.atomic.AtomicInteger()
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val deny: (Set<String>) -> Unit = { keys ->
            if (terminal.listKey in keys) refused.incrementAndGet()
        }
        val accept: (Set<String>) -> Unit = { keys ->
            if (terminal.listKey in keys) accepted.incrementAndGet()
        }
        mount {
            ChatMessageList(listOf(terminal),
                ChatScreenActions(onVisibleCompleted = if (allowed) accept else deny))
        }
        ui.waitUntil(5_000) { refused.get() > 0 }
        assertEquals(0, accepted.get())
        ui.onNodeWithText("already measured answer").assertIsDisplayed()
        mainThread { allowed = true }
        ui.waitUntil(5_000) { accepted.get() > 0 }
        ui.onNodeWithText("already measured answer").assertIsDisplayed()
    }

    fun testUnfocusedComposerAcceptsExplicitRangesAndFreshEditor() {
        var composer by mutableStateOf(ComposerUi("abcdef", selectionStart = 0, selectionEnd = 0))
        val actions = ChatScreenActions(onSelectionChange = { start, end ->
            composer = composer.copy(selectionStart = start, selectionEnd = end)
        })
        mount { ChatComposer(composer, actions) }
        ui.onNodeWithTag("chat-draft").assertIsNotFocused()
        for (range in listOf(androidx.compose.ui.text.TextRange(2, 4),
            androidx.compose.ui.text.TextRange(4), androidx.compose.ui.text.TextRange(4, 2))) {
            ui.onNodeWithTag("chat-draft").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.SetSelection) {
                assertTrue(it(range.start, range.end, false))
            }
            ui.onNodeWithTag("chat-draft").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, range))
        }
        val oldSelection = checkNotNull(ui.onNodeWithTag("chat-draft").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.SetSelection].action)
        mainThread { composer = composer.copy(editorGeneration = 1, selectionStart = 1, selectionEnd = 3) }
        ui.onNodeWithTag("chat-draft").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(1, 3)))
        mainThread { oldSelection(0, 0, false) }
        ui.onNodeWithTag("chat-draft").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(1, 3)))
    }

    fun testReadConfirmationDoesNotStarveWhileCallbackChangesEveryFrame() {
        val terminal = ChatTurnUi.Assistant("stable", finalText = "stable measured answer")
        var churn by mutableStateOf(false)
        var epoch by mutableStateOf(0)
        val received = java.util.concurrent.atomic.AtomicInteger()
        mount {
            androidx.compose.runtime.LaunchedEffect(churn) {
                if (churn) repeat(20) {
                    androidx.compose.runtime.withFrameNanos { }
                    epoch++
                }
            }
            val callback = androidx.compose.runtime.remember(epoch) {
                { keys: Set<String> -> if (terminal.listKey in keys) received.incrementAndGet(); Unit }
            }
            ChatMessageList(listOf(terminal), ChatScreenActions(onVisibleCompleted = callback))
        }
        ui.waitUntil(5_000) { received.get() > 0 }
        ui.mainClock.autoAdvance = false
        try {
            mainThread { churn = true; received.set(0) }
            repeat(8) { ui.mainClock.advanceTimeByFrame() }
            assertTrue("read confirmation must complete before callback churn stops", received.get() > 0)
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testViewportRemeasureDoesNotRecomposeUnchangedComposerSlot() {
        var viewportHeight by mutableStateOf(160.dp)
        val compositions = java.util.concurrent.atomic.AtomicInteger()
        val measuredHeight = java.util.concurrent.atomic.AtomicInteger()
        val editor: @Composable (top.yukonga.miuix.kmp.blur.LayerBackdrop?) -> Unit = {
            androidx.compose.runtime.SideEffect { compositions.incrementAndGet() }
            Box(Modifier.height(40.dp))
        }
        mount {
            ChatConversationLayers(
                modifier = Modifier.height(viewportHeight).onSizeChanged { measuredHeight.set(it.height) },
                messages = {}, composer = editor,
            )
        }
        val initial = compositions.get()
        val originalHeight = measuredHeight.get()
        assertTrue(initial > 0)
        mainThread { viewportHeight = 200.dp }
        ui.waitForIdle()
        assertTrue(measuredHeight.get() > originalHeight)
        assertEquals(initial, compositions.get())
    }

    fun testRememberedComposerSlotStillUpdatesMessageInsetInTheSameLayout() {
        var editorHeight by mutableStateOf(40.dp)
        var messageInset = 0.dp
        mount {
            ChatConversationLayers(
                messages = { inset -> androidx.compose.runtime.SideEffect { messageInset = inset } },
                composer = { Box(Modifier.height(editorHeight)) },
            )
        }
        assertEquals(40.dp, messageInset)
        mainThread { editorHeight = 60.dp }
        ui.waitForIdle()
        assertEquals(60.dp, messageInset)
    }

    fun testViewportRemeasureDoesNotRecomposeUnchangedMessagesSlot() {
        var viewportHeight by mutableStateOf(160.dp)
        val compositions = java.util.concurrent.atomic.AtomicInteger()
        val measuredHeight = java.util.concurrent.atomic.AtomicInteger()
        val messages: @Composable (androidx.compose.ui.unit.Dp) -> Unit = {
            androidx.compose.runtime.SideEffect { compositions.incrementAndGet() }
            Box(Modifier.fillMaxSize().onSizeChanged { size -> measuredHeight.set(size.height) })
        }
        val editor: @Composable (top.yukonga.miuix.kmp.blur.LayerBackdrop?) -> Unit = {
            Box(Modifier.height(40.dp))
        }
        mount {
            ChatConversationLayers(Modifier.height(viewportHeight), messages = messages, composer = editor)
        }
        val initial = compositions.get()
        var previousHeight = measuredHeight.get()
        assertTrue(initial > 0)
        for (height in listOf(200.dp, 120.dp, 180.dp, 160.dp)) {
            mainThread { viewportHeight = height }
            ui.waitForIdle()
            assertTrue("viewport still remeasures at $height", measuredHeight.get() != previousHeight)
            assertEquals("viewport-only frames preserve messages slot content", initial, compositions.get())
            previousHeight = measuredHeight.get()
        }
    }

    fun testRealStreamingListViewportResizeDoesNotRecomposeTurnContent() {
        var viewportHeight by mutableStateOf(200.dp)
        val compositions = java.util.concurrent.atomic.AtomicInteger()
        val turns: List<ChatTurnUi> = rows() + ChatTurnUi.Assistant(
            "measure-tail", status = app.daiyu.android.domain.RunStatus.RUNNING)
        val actions = ChatScreenActions()
        val request = ChatScrollRequest(1)
        val extras: @Composable (ChatTurnUi.Assistant) -> Unit = { Box(Modifier.height(1000.dp)) }
        val observe: (String) -> Unit = { if (it == "measure-tail") compositions.incrementAndGet() }
        mount {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.ui.layout.Layout(modifier = Modifier.fillMaxSize(), content = {
                ChatMessageList(turns, actions, scrollRequest = request, composerBottomInset = 40.dp,
                    turnExtras = extras, onTurnComposed = observe)
            }) { measurables, constraints ->
                val px = with(density) { viewportHeight.roundToPx() }
                val child = measurables.single().measure(
                    androidx.compose.ui.unit.Constraints.fixed(constraints.maxWidth, px))
                layout(constraints.maxWidth, constraints.maxHeight) { child.placeRelative(0, 0) }
            }
        }
        ui.onNodeWithTag("chat-messages").assertHeightIsEqualTo(200.dp)
        ui.onNodeWithTag("chat-stream-viewport:measure-tail").assertHeightIsEqualTo(200.dp - 40.dp - 11.dp - ChatTailClearance)
        val initial = compositions.get()
        assertTrue(initial > 0)
        for (height in listOf(200.dp, 120.dp, 200.dp, 160.dp)) {
            mainThread { viewportHeight = height }
            ui.waitForIdle()
            ui.onNodeWithTag("chat-messages").assertHeightIsEqualTo(height)
            ui.onNodeWithTag("chat-stream-viewport:measure-tail")
                .assertHeightIsEqualTo(height - 40.dp - 11.dp - ChatTailClearance)
            assertEquals("unchanged turn content at $height", initial, compositions.get())
        }
    }

}
