package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import app.daiyu.android.dismissDesktopEducationTooltip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import app.daiyu.android.data.model.AppearanceThemeMode
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.ui.app.AgentAppTheme

class ToolOperationCardTest : WiredActivityTestCase() {

    private fun show(turn: ChatTurnUi.Assistant, actions: ChatScreenActions) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { AssistantTurn(turn, actions) }
            }
        } }
        ui.waitForIdle()
    }
    private fun source(mixed: Boolean, fixtureKey: String = ""): Message {
        val messageId = "message$fixtureKey"
        val owner = MessageImageOwner("conversation", messageId)
        val image = MessageBlock.Image(MessageImageReference(owner, "00000000-0000-0000-0000-000000000001", "image/png", 10, "b".repeat(64), 2, 2))
        val blocks = buildList {
            add(MessageBlock.Text("before", "before operation"))
            if (mixed) add(image)
            add(MessageBlock.Tool(ToolInvocation(ToolCallId("card-call$fixtureKey"), "terminal_pty_read", "{}"), "{\"wait_status\":0,\"stdout\":\"secret-output\"}"))
            add(MessageBlock.Text("after", "final answer"))
        }
        return Message(MessageId(messageId), ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE, blocks, RunStatus.COMPLETED)
    }
    fun testBothProductionPathsRenderCallOnceAndDisclosureNeverRetries() {
        var executions = 0
        for (mixed in listOf(false, true)) {
            val turn = ChatTurnMapper.map(listOf(source(mixed)), toolState = { _, _ -> WorkStepState.FAILED }).single() as ChatTurnUi.Assistant
            show(turn, ChatScreenActions(onRetryStep = { executions++ }))
            if (!mixed) ui.onNodeWithTag("work-process-header").performClick()
            ui.onAllNodesWithTag("tool-step:card-call").assertCountEquals(1)
            ui.onNodeWithText("读取输出").scrollToVisible().assertIsDisplayed().performClick()
            ui.onNodeWithText("原始等待状态").assertDoesNotExist()
            ui.onNodeWithTag("tool-runtime").assertDoesNotExist()
            ui.onNodeWithText("secret-output", substring = true).assertIsDisplayed()
            ui.onNodeWithText("技术详情").assertDoesNotExist()
            ui.onNodeWithTag("tool-step-technical:card-call").assertDoesNotExist()
            ui.onNodeWithText("调用参数").assertDoesNotExist()
            ui.onNodeWithText("原始回执").assertDoesNotExist()
            ui.onNodeWithContentDescription("复制安全摘要").assertDoesNotExist()
            ui.onNodeWithContentDescription("重试 读取输出").assertDoesNotExist()
            ui.onNodeWithText("读取输出").scrollToVisible().assertIsDisplayed().performClick()
            assertEquals(0, executions)
            ui.onNodeWithText("final answer").assertIsDisplayed()
        }
    }
    fun testOrdinaryCommandToolsRenderTerminalHeadersInBothProductionPaths() {
        var executions = 0
        for (mixed in listOf(false, true)) for ((name, title) in listOf(
            "terminal_exec" to "执行命令", "terminal_read" to "读取命令输出")) {
            val fixture = source(mixed, "ordinary-$name-$mixed")
            val args = if (name == "terminal_exec") """{"command":"printf ordinary-output","environment":"android"}"""
                else """{"job_id":"00000000-0000-0000-0000-000000000001","offset":0,"limit":16384}"""
            val message = fixture.copy(blocks = fixture.blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(invocation = block.invocation.copy(name = name,
                    arguments = args),
                    result = """{"ok":true,"execution":"executed","state":"exited","exit_code":0,"stdout":"ordinary-output"}""")
                else block
            })
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            show(turn, ChatScreenActions(onRetryStep = { executions++ }))
            if (!mixed) {
                ui.onNodeWithText("终端命令").assertIsDisplayed()
                ui.onNodeWithTag("work-process-header").performClick()
            }
            ui.onNodeWithText("未知工具回退").assertDoesNotExist()
            ui.onNodeWithTag("tool-step-header:card-callordinary-$name-$mixed").scrollToVisible().assertIsDisplayed().assertTextContains(title).performClick()
            ui.onNodeWithText("ordinary-output").scrollToVisible().assertIsDisplayed()
            ui.onNodeWithText("技术详情").assertDoesNotExist()
            ui.onNodeWithText("未知工具回退").assertDoesNotExist()
            assertEquals(0, executions)
        }
    }
    fun testReceiptTargetLabelsRenderInBothProductionPathsWithoutReexecution() {
        var executions = 0
        for (mixed in listOf(false, true)) for ((name, target) in listOf(
            "terminal_exec" to Triple("android", 0, "Android • root"),
            "terminal_exec" to Triple("android", 10444, "Android • user"),
            "terminal_exec" to Triple("linux:debian", 0, "Debian • root"),
            "terminal_exec" to Triple("linux:debian", 0, "Debian • root（PRoot）"),
            "shizuku_execute" to Triple("shizuku", 2000, "Android • Shizuku"),
            "terminal_job_read" to Triple("linux:debian", 0, "Debian • root"),
            "terminal_job_read" to Triple("linux:debian", 0, "Debian • root（PRoot）"))) {
            val (backend, uid, label) = target
            val fixture = source(mixed, "target-$name-$backend-$uid-$label-$mixed")
            val payload = org.json.JSONObject().put("ok", true).put("backend", backend).put("uid", uid)
                .put(if (backend == "shizuku") "output" else "stdout", "target-output").put("exit_code", 0)
                .also { if (backend != "shizuku") it.put("root", uid == 0 && !label.contains("PRoot"))
                    .put("host_root", uid == 0 && !label.contains("PRoot")) }.toString()
            val args = if (name == "terminal_job_read") """{"job_id":"recorded-job"}"""
                else if (backend == "shizuku") """{"script":"printf target-output"}"""
                else org.json.JSONObject().put("command", "printf target-output")
                    .put("environment", if (backend.startsWith("linux:")) "linux" else "android").toString()
            val message = fixture.copy(blocks = fixture.blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(invocation = block.invocation.copy(name = name, arguments = args), result = payload)
                else block
            })
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            show(turn, ChatScreenActions(onRetryStep = { executions++ }))
            if (!mixed) {
                ui.onNodeWithText(label).assertIsDisplayed()
                assertEquals(toolHeaderText(label, true),
                    ui.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single())
                ui.onNodeWithTag("work-process-header").performClick()
            }
            val title = "${if (name == "terminal_job_read") "读取后台结果" else "执行命令"} · $label"
            ui.onNodeWithText(title).scrollToVisible().assertIsDisplayed()
            assertEquals(toolHeaderText(title, true),
                ui.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single())
            ui.onNodeWithText(title).performClick()
            ui.onNodeWithText("target-output").scrollToVisible().assertIsDisplayed()
            ui.onNodeWithText("执行环境", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithText("Android 宿主权限", substring = true, useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithText("技术详情").assertDoesNotExist()
            ui.onNodeWithText("未知工具回退").assertDoesNotExist()
            assertEquals(0, executions)
        }
    }
    fun testHistoricalUnknownAndEveryReservedCategoryHaveOnlyReadOnlyDisclosure() {
        var executions = 0
        ToolCardPresentations.categories.forEach { category ->
            val step = WorkStepUi("reserved", "fixture-$category", "安全摘要", WorkStepState.UNKNOWN,
                WorkStepOwnerUi("old", "old-run", category), "fixture-only", category,
                listOf(ToolDetailSectionUi("结果摘要", "结果未确认，不恢复执行权")))
            show(ChatTurnUi.Assistant("historical-$category", content = listOf(MessageContentUi.Tool(step))),
                ChatScreenActions(onRetryStep = { executions++ }))
            ui.onNodeWithText("fixture-$category").performClick()
            ui.onNodeWithText("结果未确认，不恢复执行权").assertIsDisplayed()
            ui.onNodeWithContentDescription("重试 fixture-$category").assertDoesNotExist()
        }
        assertEquals(0, executions)
    }
    fun testAllCategoriesHideAuxiliaryFooterInBothProductionPaths() {
        var executions = 0
        val activities = listOf(ToolActivityUi.STOPPING, ToolActivityUi.STOPPED, ToolActivityUi.INTERRUPTED)
        for (mixed in listOf(false, true)) {
            (ToolCardPresentations.categories + "future_category").forEachIndexed { index, category ->
                val activity = activities[index % activities.size]
                val stateLabel = when (activity) {
                    ToolActivityUi.STOPPING -> "所属运行停止中"
                    ToolActivityUi.STOPPED -> "所属运行已停止"
                    else -> "所属运行已中断"
                }
                val title = "footer-$category-$mixed"
                val result = "recorded-result-$category-$mixed"
                val step = WorkStepUi(title, title, "", if (index % 2 == 0) WorkStepState.UNKNOWN else WorkStepState.PENDING,
                    WorkStepOwnerUi("chat", "run", title), "fixture-only", category,
                    sections = listOf(ToolDetailSectionUi("结果", result)), activity = activity)
                val turn = if (mixed) ChatTurnUi.Assistant(title, content = listOf(MessageContentUi.Tool(step)))
                    else ChatTurnUi.Assistant(title, workProcess = WorkProcessUi(
                        listOf(WorkSegmentUi(steps = listOf(step))), "已停止"))
                show(turn, ChatScreenActions(onRetryStep = { executions++ }))
                if (!mixed) ui.onNodeWithTag("work-process-header").performClick()
                val header = ui.onNodeWithTag("tool-step-header:${step.id}")
                header.scrollToVisible().assertTextContains(stateLabel)
                ui.onNodeWithText("工具回执未确认 · 不重放", useUnmergedTree = true).assertDoesNotExist()
                header.performClick()
                ui.onNodeWithText(result).scrollToVisible().assertIsDisplayed()
                ui.onNodeWithText("工具回执未确认 · 不重放", useUnmergedTree = true).assertDoesNotExist()
                ui.onNodeWithText("Android 宿主权限", substring = true, useUnmergedTree = true).assertDoesNotExist()
                ui.onNodeWithContentDescription("重试 $title").assertDoesNotExist()
                header.scrollToVisible().assertTextContains(stateLabel).performClick()
                ui.onNodeWithText(result).assertDoesNotExist()
            }
        }
        assertEquals(0, executions)
    }

    fun testNonTerminalMemoryHasOneResultAndNoTerminalFieldsInBothProductionPaths() {
        var executions = 0
        for (mixed in listOf(false, true)) {
            val fixture = source(mixed, "memory-$mixed")
            val message = fixture.copy(blocks = fixture.blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(invocation = block.invocation.copy(name = "assistant_memory_read",
                    arguments = """{"id":"mem-02","command":"not a terminal command"}"""),
                    result = """{"ok":true,"text":"memory body","revision":"r7","stdout":"not terminal output"}""")
                else block
            })
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            show(turn, ChatScreenActions(onRetryStep = { executions++ }))
            if (!mixed) ui.onNodeWithTag("work-process-header").performClick()
            val header = ui.onNodeWithTag("tool-step-header:card-callmemory-$mixed")
            header.scrollToVisible().performClick()
            ui.onAllNodesWithText("memory body", useUnmergedTree = true).assertCountEquals(1)
            for (label in listOf("技术详情", "调用参数", "原始回执", "执行命令", "终端输入", "终端输出")) {
                ui.onNodeWithText(label).assertDoesNotExist()
            }
            ui.onNodeWithText("not a terminal command").assertDoesNotExist()
            ui.onNodeWithText("not terminal output").assertDoesNotExist()
            header.scrollToVisible().performClick()
            ui.onNodeWithText("memory body").assertDoesNotExist()
        }
        assertEquals(0, executions)
    }

    fun testTypedCategoryBodiesUseTheSharedReadOnlyDisclosure() {
        ToolCardPresentations.categories.forEach { category ->
            val details = ToolCategoryDetails.project(category, "{}", "{\"count\":0,\"state\":\"unknown\"}")
            val step = WorkStepUi("typed", "typed-$category", "安全摘要", WorkStepState.UNKNOWN,
                WorkStepOwnerUi("chat", "run", category), "fixture-only", category,
                categoryDetails = details)
            show(ChatTurnUi.Assistant("typed-$category", content = listOf(MessageContentUi.Tool(step))), ChatScreenActions())
            ui.onNodeWithTag("tool-category:$category").assertDoesNotExist()
            ui.onNodeWithText("typed-$category").performClick()
            ui.onNodeWithTag("tool-category:$category").assertIsDisplayed()
            ui.onNodeWithText(details.heading).assertDoesNotExist()
            ui.onNodeWithText(details.notice).assertDoesNotExist()
            ui.onNodeWithText(details.rich.source).assertDoesNotExist()
            ui.onNodeWithContentDescription("重试 typed-$category").assertDoesNotExist()
        }
    }

    fun testCompactDeviceAndActionBodiesHideTechnicalFieldsInBothProductionPaths() {
        var executions = 0
        for (mixed in listOf(false, true)) for (device in listOf(false, true)) {
            val name = if (device) "device_time" else "platform_set_wifi"
            val result = if (device) """{"ok":true,"data":{"local_iso":"2026-04-18T21:22:00+08:00","timezone":"Asia/Shanghai","epoch_ms":1}}"""
                else """{"ok":true,"message":"系统已记录请求"}"""
            val original = source(mixed, "-compact-$mixed-$device")
            val message = original.copy(blocks = original.blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(invocation = block.invocation.copy(name = name,
                    arguments = """{"enabled":true}"""), result = result) else block
            })
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            show(turn, ChatScreenActions(onRetryStep = { executions++ }))
            if (!mixed) ui.onNodeWithTag("work-process-header").performClick()
            ui.onNodeWithText(if (device) "查询时间" else "切换 Wi-Fi").scrollToVisible().performClick()
            ui.onNodeWithText(if (device) "2026-04-18 21:22:00\nAsia/Shanghai · UTC+08:00" else "系统已记录请求").assertIsDisplayed()
            ui.onNodeWithText("时间戳").assertDoesNotExist()
            ui.onNodeWithText("请求启用").assertDoesNotExist()
            ui.onNodeWithText("已记录返回").assertDoesNotExist()
            ui.onNodeWithText("调用参数").assertDoesNotExist()
            ui.onNodeWithText("技术详情").assertDoesNotExist()
            ui.onNodeWithText("调用参数").assertDoesNotExist()
            ui.onNodeWithText("原始回执").assertDoesNotExist()
        }
        assertEquals(0, executions)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testCommandAndShortLogKeepLavenderFramesAndMonospace() {
        for (dark in listOf(false, true)) {
            val backdrop = if (dark) Color(0xFF101218) else Color.White
            var observedSoft = Color.Unspecified
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    val soft = conversationColors().soft
                    androidx.compose.runtime.SideEffect { observedSoft = soft }
                    Box(Modifier.fillMaxSize().background(backdrop)) {
                        Column(Modifier.background(backdrop).systemBarsPadding().verticalScroll(rememberScrollState())) {
                            ToolCategoryContent("terminal", ToolCategoryDetails.project("terminal",
                                """{"command":"printf hello"}""", """{"stdout":"hello","exit_code":0}"""))
                            Row {
                                Box(Modifier.size(40.dp, 24.dp).background(backdrop).testTag("tool-swatch:backdrop"))
                                Box(Modifier.size(40.dp, 24.dp).background(soft).testTag("tool-swatch:soft"))
                                Box(Modifier.size(40.dp, 24.dp).drawBehind {
                                    drawRect(Color(if (dark) 0xFFBEA8D7 else 0xFF8A779A), alpha = (if (dark) 19f else 16f) / 255f)
                                }.testTag("tool-swatch:alpha"))
                                Box(Modifier.size(40.dp, 24.dp).background(
                                    Color(if (dark) 0x13BEA8D7 else 0x108A779A).compositeOver(backdrop)
                                ).testTag("tool-swatch:opaque"))
                            }
                        }
                        Box(Modifier.align(Alignment.CenterEnd).size(8.dp, 24.dp)
                            .testTag("tool-education-blank").pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            })
                    }
                }
            } }
            ui.waitForIdle()
            dismissDesktopEducationTooltip(instrumentation, ui, ui.onNodeWithTag("tool-education-blank"))
            val layoutsByTitle = mutableMapOf<String, androidx.compose.ui.text.TextLayoutResult>()
            for (tag in listOf("tool-document:执行命令", "tool-document:终端输出")) {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                ui.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
                assertEquals(13.sp, layouts.single().layoutInput.style.fontSize)
                layoutsByTitle[tag.substringAfter(':')] = layouts.single()
            }
            for (title in listOf("执行命令", "终端输出")) {
                val frame = ui.onNodeWithTag("tool-document-frame:$title", useUnmergedTree = true).scrollToVisible()
                awaitVisualIdle()
                val geometry = frame.fetchSemanticsNode()
                val size = geometry.layoutInfo.coordinates.size
                assertEquals("$title frame must not be vertically clipped", size.height.toFloat(), geometry.boundsInRoot.height, 1f)
                assertEquals("$title frame must not be horizontally clipped", size.width.toFloat(), geometry.boundsInRoot.width, 1f)
                val pixels = captureScreenNode(frame).toPixelMap()
                val sampleY = (4f * layoutsByTitle.getValue(title).layoutInput.density.density).roundToInt()
                assertTrue("sample must stay between the border and text padding", sampleY in 1 until pixels.height)
                val actual = pixels[pixels.width / 2, sampleY]
                val expected = Color(if (dark) 0x13BEA8D7 else 0x108A779A).compositeOver(backdrop)
                val sample = "dark=$dark title=$title sampleY=$sampleY frame=${pixels.width}x${pixels.height} " +
                    "actual=$actual expected=$expected bounds=${geometry.boundsInRoot}"
                if (kotlin.math.abs(expected.red - actual.red) > .025f ||
                    kotlin.math.abs(expected.green - actual.green) > .025f ||
                    kotlin.math.abs(expected.blue - actual.blue) > .025f) {
                    try {
                        val root = ui.onNode(isRoot() and hasAnyDescendant(hasTestTag("tool-document-frame:$title")), useUnmergedTree = true)
                        val rootNode = root.fetchSemanticsNode()
                        val native = (rootNode.root as androidx.compose.ui.platform.ViewRootForTest).view
                        val location = IntArray(2)
                        instrumentation.runOnMainSync { native.getLocationOnScreen(location) }
                        val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics").apply { mkdirs() }
                        val prefix = "tool-frame-${if (dark) "dark" else "light"}-$title-${android.os.SystemClock.elapsedRealtimeNanos()}"
                        android.util.Log.d("ToolFrameDiagnostic", "$sample prefix=$prefix soft=${observedSoft.toArgb()} " +
                            "alpha=${observedSoft.alpha} native=${location.toList()} root=${rootNode.boundsInRoot} " +
                            "nodeScreen=${geometry.positionOnScreen} nodeRoot=${geometry.positionInRoot}")
                        fun save(bitmap: android.graphics.Bitmap, name: String) {
                            java.io.File(directory, "$prefix-$name.png").outputStream().use {
                                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                            }
                        }
                        fun capture(name: String, block: () -> android.graphics.Bitmap) {
                            try {
                                val bitmap = block()
                                try {
                                    save(bitmap, name)
                                    if (name.endsWith("root")) {
                                        val x = (geometry.boundsInRoot.center.x - rootNode.boundsInRoot.left).roundToInt()
                                        val y = (geometry.boundsInRoot.top - rootNode.boundsInRoot.top).roundToInt() + sampleY
                                        val profile = (-8..8).filter { y + it in 0 until bitmap.height && x in 0 until bitmap.width }
                                            .joinToString { "$it:${bitmap.getPixel(x, y + it).toUInt().toString(16)}" }
                                        android.util.Log.d("ToolFrameDiagnostic", "$name size=${bitmap.width}x${bitmap.height} sample=$x,$y profile=$profile")
                                    }
                                } finally { bitmap.recycle() }
                            } catch (diagnostic: Throwable) {
                                android.util.Log.e("ToolFrameDiagnostic", "$name capture failed", diagnostic)
                            }
                        }
                        capture("display") { checkNotNull(instrumentation.uiAutomation.takeScreenshot()) }
                        capture("screen-root") { captureScreenNode(root).asAndroidBitmap() }
                        try {
                            var windowBitmap: android.graphics.Bitmap? = null
                            var requestFailure: Throwable? = null
                            val copied = java.util.concurrent.CountDownLatch(1)
                            val ownership = Any()
                            var completed = false
                            var abandoned = false
                            var copyResult = -1
                            instrumentation.runOnMainSync {
                                try {
                                    val bitmap = android.graphics.Bitmap.createBitmap(activity.window.decorView.width,
                                        activity.window.decorView.height, android.graphics.Bitmap.Config.ARGB_8888)
                                    windowBitmap = bitmap
                                    android.view.PixelCopy.request(activity.window, bitmap, {
                                        synchronized(ownership) {
                                            copyResult = it
                                            completed = true
                                            if (abandoned) bitmap.recycle()
                                        }
                                        copied.countDown()
                                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                                } catch (failure: Throwable) { requestFailure = failure }
                            }
                            if (requestFailure != null) {
                                windowBitmap?.recycle()
                                throw checkNotNull(requestFailure)
                            }
                            val arrived = try { copied.await(2, java.util.concurrent.TimeUnit.SECONDS) }
                            catch (interrupted: InterruptedException) {
                                synchronized(ownership) {
                                    if (completed) windowBitmap?.recycle() else abandoned = true
                                }
                                Thread.currentThread().interrupt()
                                throw interrupted
                            }
                            synchronized(ownership) {
                                if (completed) {
                                    val bitmap = checkNotNull(windowBitmap)
                                    try {
                                        android.util.Log.d("ToolFrameDiagnostic", "pixelCopy=$copyResult window=${bitmap.width}x${bitmap.height}")
                                        if (copyResult == android.view.PixelCopy.SUCCESS) save(bitmap, "window")
                                    } finally { bitmap.recycle() }
                                } else {
                                    abandoned = true
                                    android.util.Log.d("ToolFrameDiagnostic", "PixelCopy completion pending after bounded wait=$arrived")
                                }
                            }
                        } catch (diagnostic: Throwable) {
                            android.util.Log.e("ToolFrameDiagnostic", "window capture failed", diagnostic)
                        }
                        capture("compose-root") {
                            val enabled = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
                            try {
                                if (!enabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
                                root.captureToImage().asAndroidBitmap()
                            } finally {
                                if (!enabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false)
                            }
                        }
                        capture("display-after") { checkNotNull(instrumentation.uiAutomation.takeScreenshot()) }
                    } catch (diagnostic: Throwable) {
                        android.util.Log.e("ToolFrameDiagnostic", "capture diagnostic failed for $sample", diagnostic)
                    }
                }
                assertEquals(sample, expected.red, actual.red, .025f)
                assertEquals(sample, expected.green, actual.green, .025f)
                assertEquals(sample, expected.blue, actual.blue, .025f)
            }
            ui.onNodeWithTag("tool-output-hint:终端输出").assertDoesNotExist()
            ui.onNodeWithTag("tool-document:终端输出", useUnmergedTree = true).assertTextEquals("hello")
            ui.onNodeWithText("运行回执").assertDoesNotExist()
        }
    }

    fun testLongOutputDisclosureAndHostSlotNeverReplayTools() {
        var executions = 0
        val body = "long output " + "x".repeat(600)
        for (mixed in listOf(false, true)) {
            val message = source(mixed).copy(blocks = source(mixed).blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(result = org.json.JSONObject().put("stdout", body).toString()) else block
            })
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AssistantTurn(turn, ChatScreenActions(onRetryStep = { executions++ }), toolContent = {
                            top.yukonga.miuix.kmp.basic.Text("trusted-host-slot")
                        })
                    }
                }
            } }
            ui.waitForIdle()
            if (!mixed) ui.onNodeWithTag("work-process-header").performClick()
            ui.onNodeWithText("读取输出").scrollToVisible().assertIsDisplayed().performClick()
            ui.onAllNodesWithText("trusted-host-slot").assertCountEquals(1)
            ui.onNodeWithText(body).assertDoesNotExist()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.onNodeWithTag("tool-output-preview:终端输出", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().lineCount <= 3)
            ui.onNodeWithTag("tool-document:终端输出").performClick()
            ui.onNodeWithText(body).assertExists()
            ui.onNode(hasText("终端输出") and hasAnyAncestor(hasTestTag("tool-output-dialog"))).assertExists()
            ui.onNodeWithTag("tool-output-meta").assertTextEquals("1 行 · 成功")
            ui.onNodeWithTag("tool-output-dialog", useUnmergedTree = true).assert(
                SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
            ui.onNodeWithTag("tool-output-close", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithTag("tool-output-copy").assertHasClickAction().performClick()
            ui.onNodeWithText("已复制").assertExists()
            mainThread {
                val manager = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                assertEquals(body, manager.primaryClip!!.getItemAt(0).text.toString())
            }
            ui.onNodeWithTag("tool-output-content", useUnmergedTree = true).performClick()
            ui.onNodeWithTag("tool-output-dialog").assertExists()
            awaitNodeWindowFocus(node("tool-output-dialog"))
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodesWithTag("tool-output-dialog").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
            }
            ui.onNodeWithTag("tool-output-dialog").assertDoesNotExist()
            ui.onNodeWithText(body).assertDoesNotExist()
            assertEquals(0, executions)
        }
    }

    fun testRestrictedReceiptsNeverComposeAssetsOrControlsInEitherProductionPath() {
        var hostCompositions = 0
        for (mixed in listOf(false, true)) for (private in listOf(false, true)) {
            val key = "restricted-$mixed-$private"
            val state = if (private) "\"privacy_redacted\":true" else "\"ok\":false"
            val payload = "{\"data\":{" + state + ",\"text\":\"RESTRICTED_PAYLOAD\"}}"
            val original = source(mixed, key)
            val message = original.copy(blocks = original.blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(invocation = block.invocation.copy(name = "read_text_file"), result = payload) else block
            })
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AssistantTurn(turn, ChatScreenActions(), toolContent = {
                            hostCompositions++
                            top.yukonga.miuix.kmp.basic.Text("restricted-host-content")
                        })
                    }
                }
            } }
            ui.waitForIdle()
            if (!mixed) ui.onNodeWithTag("work-process-header").performClick()
            ui.onNodeWithTag("tool-step-header:card-call$key").scrollToVisible().performClick()
            ui.onNodeWithText(if (private) "结果未获展示授权" else "本次调用未完成").assertIsDisplayed()
            ui.onNodeWithText("restricted-host-content").assertDoesNotExist()
            ui.onNodeWithText("RESTRICTED_PAYLOAD", substring = true).assertDoesNotExist()
            if (private) {
                ui.onNodeWithTag("tool-step-technical:card-call$key").assertDoesNotExist()
                ui.onNodeWithText("RESTRICTED_PAYLOAD", substring = true).assertDoesNotExist()
            }
            assertEquals(0, hostCompositions)
        }
    }

    fun testDedicatedQuestionIsVisibleAtCallPositionInBothPathsAndMissingHostFallsBack() {
        for (mixed in listOf(false, true)) {
            val original = source(mixed).copy(blocks = source(mixed).blocks.map { block ->
                if (block is MessageBlock.Tool) block.copy(invocation = block.invocation.copy(name = "ask_user")) else block
            })
            val turn = ChatTurnMapper.map(listOf(original), dedicatedTool = { _, _ -> "validated-question" })
                .single() as ChatTurnUi.Assistant
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AssistantTurn(turn, ChatScreenActions(), dedicatedTool = { step ->
                            if (step.dedicatedKey == "validated-question") {
                                top.yukonga.miuix.kmp.basic.Text("Dedicated question")
                                true
                            } else false
                        })
                    }
                }
            } }
            ui.waitForIdle()
            ui.onAllNodesWithText("Dedicated question").assertCountEquals(1)
            ui.onNodeWithText("Dedicated question").assertIsDisplayed()
            ui.onNodeWithTag("work-process-header").assertDoesNotExist()
            ui.onAllNodesWithTag("tool-step:card-call").assertCountEquals(0)
            show(turn, ChatScreenActions())
            ui.onAllNodesWithTag("tool-step:card-call").assertCountEquals(1)
        }
    }

    fun testPublicListShowsFirstResultsAndPrivateListRemainsExplicit() {
        for (category in listOf("files", "personal_search")) {
            val result = "{\"items\":[" + (1..5).joinToString { "{\"name\":\"file$it\",\"content\":\"body$it\"}" } + "]}"
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ToolCategoryContent(category, ToolCategoryDetails.project(category, "{}", result))
                    }
                }
            } }
            ui.waitForIdle()
            if (category == "files") {
                ui.onNodeWithText("body1").assertIsDisplayed()
                ui.onNodeWithText("body4").assertDoesNotExist()
                ui.onNodeWithText("查看其余 2 项").scrollToVisible().performClick()
                ui.onNodeWithText("body4").scrollToVisible().assertIsDisplayed()
            } else {
                ui.onNodeWithText("body1").assertDoesNotExist()
                ui.onNodeWithTag("tool-entries").performClick()
                ui.onNodeWithText("body1").assertIsDisplayed()
            }
        }
    }
    fun testEmptySkillsListShowsZeroUnlessBodyRestricted() {
        for (restricted in listOf(false, true)) {
            val payload = if (restricted) """{"privacy_redacted":true,"skills":[]}"""
                else """{"skills":[]}"""
            val details = ToolCardPresentations.present("skills_list", "{}", payload).details
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ToolCategoryContent("skills", details)
                }
            } }
            ui.waitForIdle()
            if (restricted) {
                ui.onNodeWithText("结果未获展示授权").assertIsDisplayed()
                ui.onNodeWithText("技能数").assertDoesNotExist()
                ui.onNodeWithText("0").assertDoesNotExist()
            } else {
                ui.onNodeWithText("技能数").assertIsDisplayed()
                ui.onNodeWithText("0").assertIsDisplayed()
            }
        }
    }

    fun testPrivacyWithheldHidesAllCategoryDataAndUnknownCategoryIsNotBlank() {
        for (category in listOf("files", "apps", "browser", "personal_search", "future_category")) {
            val payload = if (category == "future_category") """{"new_result":"unknown visible receipt"}"""
                else """{"privacy_redacted":true,"status":"private_result_withheld","data":{"text":"PRIVATE_BODY","items":[{"content":"PRIVATE_ITEM"}]}}"""
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ToolCategoryContent(category, ToolCategoryDetails.project(category, "{}", payload))
                }
            } }
            ui.waitForIdle()
            if (category == "future_category") ui.onNodeWithText("unknown visible receipt", substring = true).assertExists()
            else {
                ui.onNodeWithText("结果未获展示授权").assertIsDisplayed()
                ui.onNodeWithText("PRIVATE_BODY", substring = true).assertDoesNotExist()
                ui.onNodeWithText("PRIVATE_ITEM", substring = true).assertDoesNotExist()
            }
        }
    }
    fun testSameCategoryAndMixedToolChainsUseCategoryDrivenTitles() {
        for ((names, heading) in listOf(
            listOf("device_time", "network_status") to "设备查询",
            listOf("terminal_pty_read", "terminal_pty_read") to "终端命令",
            listOf("device_time", "terminal_pty_read", "future_tool") to "工具链")) {
            val key = names.joinToString("-")
            val message = Message(MessageId(key), ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE,
                names.mapIndexed { index, name -> MessageBlock.Tool(ToolInvocation(ToolCallId("chain-$index"), name, "{}"), "unique receipt $index") }, RunStatus.COMPLETED)
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> WorkStepState.OK }).single() as ChatTurnUi.Assistant
            show(turn, ChatScreenActions())
            ui.onNodeWithText(heading).assertIsDisplayed()
            ui.onNodeWithTag("work-process-header").performClick()
            names.indices.forEach { ui.onAllNodesWithTag("tool-step:chain-$it").assertCountEquals(1) }
        }
    }

    fun testFinalControlsStayEnabledAndDispatchOnlyBoundIntent() = finalControlsStayEnabledAndDispatchOnlyBoundIntent()

    private fun finalControlsStayEnabledAndDispatchOnlyBoundIntent() {
        val step = WorkStepUi("final-call", "工作区", "", WorkStepState.UNKNOWN,
            WorkStepOwnerUi("chat", "old-run", "message"), "workspace-inspect", "workspace")
        val requests = mutableListOf<ToolCardActionRequest>()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ToolMediaSurface(step, null, true, 12000L, "asset") { requests += it }
                }
            }
        } }
        ui.waitForIdle()
        ui.onNodeWithTag("tool-action:MERGE_WORKSPACE").assertDoesNotExist()
        ui.onNodeWithTag("tool-action:DISCARD_WORKSPACE").assertDoesNotExist()
        ui.onNodeWithTag("tool-action:PLAY_VIDEO").scrollToVisible().assertIsEnabled().performClick()
        assertEquals(step.actionRequest(ToolCardAction.PLAY_VIDEO, "asset"), requests.last())
        assertEquals(1, requests.size)
        ui.onNodeWithText("暂未接入", substring = true).assertDoesNotExist()
        ui.onNodeWithText("宿主未提供", substring = true).assertDoesNotExist()
    }

}
