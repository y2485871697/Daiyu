package app.daiyu.android.ui.navigation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.test.platform.graphics.HardwareRendererCompat
import app.daiyu.android.ui.screens.chat.conversation.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.awaitControl
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme

class PageRetainedContentActivityTest : WiredActivityTestCase() {
    private class Parent : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private lateinit var parent: Parent
    private var route by mutableStateOf("chat")
    private var scopeKey by mutableStateOf(0)
    private var mounts = 0
    private var disposals = 0
    private var rootBacks = 0
    private var detailBacks = 0
    private var rootActive = false
    private var rootOwner: LifecycleOwner? = null
    private val owners = mutableListOf<LifecycleOwner>()

    private fun mount() {
        mainThread {
            parent = Parent().also { it.registry.currentState = Lifecycle.State.RESUMED }
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalLifecycleOwner provides parent) {
                        val rendered = route
                        PageBackGestureHost(
                            routeKey = rendered, contentKey = rendered,
                            canNavigateBack = rendered != "chat",
                            swipeDismissEnabled = true, predictiveBackEnabled = true,
                            animateBackEnabled = false,
                            dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                            backTargetKey = if (rendered == "chat") null else "chat",
                            retainedRootKey = "chat", retainedRootScopeKey = scopeKey,
                            retainedRootContent = { active ->
                                val owner = LocalLifecycleOwner.current
                                DisposableEffect(Unit) {
                                    mounts++
                                    owners.add(owner)
                                    onDispose { disposals++ }
                                }
                                SideEffect { rootOwner = owner; rootActive = active }
                                PageBackHandler { rootBacks++ }
                                Box(Modifier.fillMaxSize().background(Color.Red).testTag("retained-root"))
                            },
                        ) {
                            if (rendered != "chat") {
                                PageBackHandler { detailBacks++; route = "chat" }
                                Box(Modifier.fillMaxSize().background(Color.Blue).testTag("detail-root"))
                            }
                        }
                    }
                }
            }
        }
        dismissIme()
        ui.waitForIdle()
    }

    fun testRootRemainsOneMeasuredInstanceAcrossHiddenPagesAndImmediateReturn() {
        mount()
        val original = ui.onNodeWithTag("retained-root").fetchSemanticsNode().id
        val owner = checkNotNull(rootOwner)
        assertTrue(rootActive)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        for (next in listOf("settings", "providers", "models")) {
            mainThread { route = next }
            ui.onNodeWithTag("detail-root").assertIsDisplayed()
            ui.onNodeWithTag("retained-root").assertDoesNotExist()
            assertEquals(1, mounts)
            assertEquals(0, disposals)
            assertFalse(rootActive)
            assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
        }
        mainThread {
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals("chat", route)
        }
        ui.onNodeWithTag("retained-root").assertIsDisplayed()
        assertEquals(original, ui.onNodeWithTag("retained-root").fetchSemanticsNode().id)
        assertSame(owner, rootOwner)
        assertEquals(1, mounts)
        assertEquals(0, disposals)
        assertEquals(1, detailBacks)
        assertEquals(0, rootBacks)
        assertTrue(rootActive)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
    }

    private val localCompositions = IntArray(5)
    private var observedActive = true

    @androidx.compose.runtime.Composable
    private fun ActiveLocalProbe() {
        val value = LocalPageContentActive.current
        SideEffect { localCompositions[0]++; observedActive = value }
    }

    @androidx.compose.runtime.Composable
    private fun GestureLocalProbe() {
        val value = LocalPageBackGesture.current
        SideEffect { localCompositions[1]++; observedGesture = value }
    }

    private var observedGesture: PageBackGestureState? = null

    @androidx.compose.runtime.Composable
    private fun StackLocalProbe() {
        val value = LocalPageBackStackPath.current
        SideEffect { localCompositions[2]++; observedPath = value }
    }

    private var observedPath: PageBackStackPath? = null

    @androidx.compose.runtime.Composable
    private fun PreviewLocalProbe() {
        val value = LocalPageBackPreviewFrame.current
        SideEffect { localCompositions[3]++; observedPreview = value }
    }

    private var observedPreview: PageBackPreviewLayers.Frame? = null

    @androidx.compose.runtime.Composable
    private fun UnreadLocalProbe() {
        SideEffect { localCompositions[4]++ }
    }

    fun testDynamicNavigationLocalsOnlyRecomposeTheirSeparateConsumerScopes() {
        var active by mutableStateOf(true)
        var gestureEnabled by mutableStateOf(true)
        var stackEnabled by mutableStateOf(true)
        var previewEnabled by mutableStateOf(true)
        // A stable provider child with five independent restart scopes. None reads the
        // control state; static locals would invalidate all five, including unread.
        val probes: @androidx.compose.runtime.Composable () -> Unit = {
            ActiveLocalProbe()
            GestureLocalProbe()
            StackLocalProbe()
            PreviewLocalProbe()
            UnreadLocalProbe()
        }
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    PageBackGestureHost("local-probes", canNavigateBack = false,
                        swipeDismissEnabled = false, predictiveBackEnabled = false,
                        animateBackEnabled = false, dispatchBack = {}, captureFromContent = true) {
                        val gesture = checkNotNull(LocalPageBackGesture.current)
                        val stack = checkNotNull(LocalPageBackStackPath.current)
                        val preview = checkNotNull(LocalPageBackPreviewFrame.current)
                        CompositionLocalProvider(
                            LocalPageContentActive provides active,
                            LocalPageBackGesture provides gesture.takeIf { gestureEnabled },
                            LocalPageBackStackPath provides stack.takeIf { stackEnabled },
                            LocalPageBackPreviewFrame provides preview.takeIf { previewEnabled },
                            content = probes,
                        )
                    }
                }
            }
        }
        dismissIme()
        ui.waitForIdle()
        assertNotNull(observedGesture)
        assertNotNull(observedPath)
        assertNotNull(observedPreview)
        val changes: List<() -> Unit> = listOf(
            { active = !active }, { gestureEnabled = !gestureEnabled },
            { stackEnabled = !stackEnabled }, { previewEnabled = !previewEnabled },
        )
        // Both directions, no emulator timing threshold: only actual committed compositions.
        repeat(2) {
            changes.forEachIndexed { consumer, change ->
                val before = localCompositions.copyOf()
                mainThread { change() }
                ui.waitForIdle()
                mainThread {
                    localCompositions.indices.forEach { scope ->
                        if (scope == consumer) assertTrue("consumer $scope must observe its local change",
                            localCompositions[scope] > before[scope])
                        else assertEquals("unrelated scope $scope changed for local $consumer",
                            before[scope], localCompositions[scope])
                    }
                }
            }
        }
        assertNotNull(observedGesture)
        assertNotNull(observedPath)
        assertNotNull(observedPreview)
    }

    fun testRealMainChatReturnReusesRowsAndSidebarDismissStillRefreshesImmediately() {
        val runtime = app.daiyu.android.diagnostics.RuntimeDiagnostics
        val recorder = app.daiyu.android.diagnostics.BackPerformance.recorder
        val previousEnabled = runtime.enabled
        assertFalse(app.daiyu.android.BuildConfig.JANK_DIAGNOSTICS)
        runtime.enabled = false
        runtime.clear()
        fun recordSynchronous(startWindow: Boolean, expectedRefresh: Int = 1, action: () -> Unit): org.json.JSONObject {
            runtime.clear()
            mainThread {
                if (startWindow) recorder.start(Any(), app.daiyu.android.diagnostics.BackPerformanceRecorder.Kind.BACK_PRESS)
                action()
                assertNotNull("return callback must start the ordinary automatic recorder", recorder.token())
                // Deterministic synthetic sample: count/path only, not an emulator timing assertion.
                recorder.frame(System.nanoTime(), 40_000_000L, 16_666_667L, 60f, LongArray(9))
                // Close in the same main-thread turn: later IO publications are not part of this assertion.
                recorder.end(app.daiyu.android.diagnostics.BackPerformanceRecorder.End.PAUSED)
            }
            val rows = runtime.export()
            val incidents = (0 until rows.length()).map { rows.getJSONObject(it) }
                .filter { it.optString("stage") == "BACK_PERFORMANCE" }
            assertEquals(1, incidents.size)
            val incident = incidents.single()
            val phases = incident.getJSONArray("phases")
            val refreshCount = (0 until phases.length()).map { phases.getJSONObject(it) }
                .filter { it.getString("phase") == "CHAT_REFRESH" }.sumOf { it.getInt("count") }
            val publish = (0 until phases.length()).map { phases.getJSONObject(it) }
                .single { it.getString("phase") == "CHAT_PUBLISH" }
            assertEquals("count synchronous refreshes, not merely visible compositions", expectedRefresh, refreshCount)
            assertEquals(1, publish.getInt("count"))
            assertFalse("unchanged rendered history must not perform another cold SHA", (0 until phases.length())
                .any { phases.getJSONObject(it).getString("phase") == "MESSAGE_ACTION_REVISION" })
            return incident
        }
        try {
            // Quiet, non-empty rendered history; no pending receipts/attachments/provider work.
            val conversation = fixture.database.create("default")
            val user = app.daiyu.android.domain.Message(app.daiyu.android.domain.MessageId("return-user"),
                conversation.id, app.daiyu.android.domain.RunId("return-run"), "user",
                listOf(app.daiyu.android.domain.MessageBlock.Text("body", "retained history ".repeat(256))),
                app.daiyu.android.domain.RunStatus.COMPLETED)
            val assistant = user.copy(id = app.daiyu.android.domain.MessageId("return-assistant"), role = "assistant",
                blocks = listOf(app.daiyu.android.domain.MessageBlock.Text("answer", "retained answer")),
                status = app.daiyu.android.domain.RunStatus.RUNNING)
            fixture.database.send(user, assistant)
            fixture.database.save(assistant.copy(status = app.daiyu.android.domain.RunStatus.COMPLETED))
            mainThread { activity.select(conversation) }
            awaitControl("message action snapshot admitted before warm returns") {
                !fixture.controller.dataBusy && app.daiyu.android.MainActivity::class.java
                    .getDeclaredField("messageActionSnapshot").apply { isAccessible = true }.get(activity) != null
            }
            setDraft("return refresh count draft")
            openSettings() // Keep production MainActivity content and its real returnToChat wiring.
            ui.waitForIdle()
            fun returnWithRetainedRows(): org.json.JSONObject = recordSynchronous(startWindow = false, expectedRefresh = 0) {
                val before = invokeHost(activity, "getChatState") as ChatScreenState
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals("chat", invokeHost(activity, "getRetainedPage"))
                assertEquals(false, invokeHost(activity, "getSidebarVisible"))
                val after = invokeHost(activity, "getChatState") as ChatScreenState
                assertSame("pure navigation must keep the published turns", before.turns, after.turns)
                assertEquals(before.contentRevision, after.contentRevision)
                assertEquals(before.scrollRequest, after.scrollRequest)
                assertEquals(before.composer.text, after.composer.text)
            }
            val returned = returnWithRetainedRows()
            assertEquals("BACK_PRESS", returned.getString("kind"))
            assertTrue(returned.getBoolean("commit_attempted"))
            val returnedPhases = returned.getJSONArray("phases")
            val route = (0 until returnedPhases.length()).map { returnedPhases.getJSONObject(it) }
                .single { it.getString("phase") == "ROUTE" }
            assertEquals(1, route.getInt("count"))
            // route_changed is a later Compose observation; the synchronous route is asserted above.
            awaitUniqueTag("chat-draft")
            assertDraft("return refresh count draft")
            for (page in listOf("permission-check", "skills")) {
                mainThread {
                    if (page == "skills") invokeHost(activity, "openSkillManagement")
                    else invokeHost(activity, "setRetainedPage", page)
                }
                ui.waitForIdle()
                returnWithRetainedRows()
                awaitUniqueTag("chat-draft")
                assertDraft("return refresh count draft")
            }
            recordSynchronous(startWindow = true) { invokeHost(activity, "returnToChat") }
            repeat(3) {
                recordSynchronous(startWindow = true) { invokeHost(activity, "refresh") }
            }
            // Keep the window open through real Compose work, not just the synchronous refresh.
            ui.waitForIdle()
            repeat(3) {
                runtime.clear()
                mainThread {
                    recorder.start(Any(), app.daiyu.android.diagnostics.BackPerformanceRecorder.Kind.BACK_PRESS)
                    retainedControls(activity).onTextChange("return warm binding $it")
                }
                ui.waitForIdle()
                assertDraft("return warm binding $it")
                mainThread {
                    recorder.frame(System.nanoTime(), 40_000_000L, 16_666_667L, 60f, LongArray(9))
                    recorder.end(app.daiyu.android.diagnostics.BackPerformanceRecorder.End.PAUSED)
                }
                val rows = runtime.export()
                val incident = (0 until rows.length()).map { rows.getJSONObject(it) }
                    .single { it.optString("stage") == "BACK_PERFORMANCE" }
                assertTrue("draft change must execute real compiler callbacks", incident.getBoolean("compose_observed"))
                val phases = incident.getJSONArray("phases")
                assertFalse("warm Compose binding must reuse the unchanged content match", (0 until phases.length())
                    .any { phases.getJSONObject(it).getString("phase") in setOf("MESSAGE_ACTION_MATCH", "MESSAGE_ACTION_REVISION") })
            }
            mainThread { retainedControls(activity).onTextChange("return refresh count draft") }
            ui.waitForIdle()
            dismissIme()
            ui.onNode(hasContentDescription("打开侧边栏") and hasClickAction()).performClick()
            ui.waitForIdle()
            recordSynchronous(startWindow = true) {
                assertEquals(true, invokeHost(activity, "getSidebarVisible"))
                invokeHost(activity, "dismissSidebar")
                assertEquals(false, invokeHost(activity, "getSidebarVisible"))
            }
            awaitUniqueTag("chat-draft")
            assertDraft("return refresh count draft")
            assertEquals(0, fixture.opens.get())
        } finally { runtime.clear(); runtime.enabled = previousEnabled }
    }

    fun testRealSettingsScrollImmediatelyIntoReconnectAndBackKeepsSavedPosition() {
        openSettings()
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        try {
            val list = ui.onNodeWithTag("page:设置")
            list.performTouchInput {
                val start = Offset(width * .5f, height * .55f)
                down(start)
                moveTo(start - Offset(0f, 90f), 160)
                advanceEventTime(200)
                up()
            }
            ui.mainClock.advanceTimeByFrame()
            val row = ui.onNodeWithTag("settings-row:错误重连")
            val saved = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val rowTop = row.fetchSemanticsNode().boundsInRoot.top
            assertTrue("test must leave the initial list position", saved > 0f)
            val click = checkNotNull(row.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            mainThread {
                assertTrue(click())
                assertEquals("reconnect-settings", invokeHost(activity, "getRetainedPage"))
            }
            ui.mainClock.advanceTimeBy(700)
            ui.onNodeWithTag("page-back-host").performTouchInput {
                val start = Offset(width * .1f, height * .5f)
                down(start); moveTo(start + Offset(width * .6f, 0f), 180); up()
            }
            repeat(4) {
                ui.mainClock.advanceTimeByFrame()
                assertEquals("settings", invokeHost(activity, "getRetainedPage"))
                assertEquals("saved list offset stays fixed during return", saved,
                    list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .00002f)
                assertEquals("real row does not move vertically", rowTop, row.fetchSemanticsNode().boundsInRoot.top, 2f)
            }
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(saved, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .00002f)
            assertEquals(rowTop, row.fetchSemanticsNode().boundsInRoot.top, 2f)
            assertEquals(0, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testPureChatReturnStillAdoptsVideoReceiptThatRequiredVisibleEditor() {
        val db = fixture.database
        val conversation = db.create("default")
        mainThread { activity.select(conversation) }
        awaitControl("selected conversation ready") {
            invokeHost(activity, "getChatState").let { it as ChatScreenState }.conversationKey == conversation.id.value
        }
        setDraft("accepted video caption")
        lateinit var editor: app.daiyu.android.ControllerVideoEditor
        mainThread {
            editor = (app.daiyu.android.MainActivity::class.java.getDeclaredField("videoUi").apply { isAccessible = true }
                .get(activity) as app.daiyu.android.ControllerVideoUi).editor()
        }
        assertTrue(editor.visible)
        openSettings()
        mainThread {
            val owner = app.daiyu.android.media.video.VideoAttachmentOwner(conversation.id.value, "return-video")
            val video = app.daiyu.android.media.video.VideoAttachment(owner,
                "12345678-1234-1234-1234-123456789abc", "video/mp4", 3, "a".repeat(64), 1000, 32, 24)
            val request = app.daiyu.android.VideoDraftRequest(conversation.id.value, owner, 1, editor.text, listOf(video))
            val user = app.daiyu.android.domain.Message(app.daiyu.android.domain.MessageId("return-video-user"),
                conversation.id, app.daiyu.android.domain.RunId("return-video-run"), "user",
                listOf(app.daiyu.android.domain.MessageBlock.Text("text", editor.text)),
                app.daiyu.android.domain.RunStatus.COMPLETED)
            val messageOwner = app.daiyu.android.media.video.MessageVideoOwner(conversation.id.value, user.id.value)
            val assistant = user.copy(id = app.daiyu.android.domain.MessageId("return-video-assistant"),
                role = "assistant", status = app.daiyu.android.domain.RunStatus.RUNNING)
            val saved = db.send(user, assistant, videoInputs = listOf(app.daiyu.android.model.videos.MessageVideoInput(
                messageOwner, listOf(app.daiyu.android.media.video.MessageVideoReference(messageOwner,
                    video.id, video.mime, video.bytes, video.sha256, video.durationMs, video.width, video.height)))))
            db.save(assistant.copy(status = app.daiyu.android.domain.RunStatus.COMPLETED))
            val receipt = app.daiyu.android.VideoDraftReceipt(request.conversation, owner, request.revision,
                request.videos, app.daiyu.android.VideoDraftOutcome.ACCEPTED, conversation.id.value)
            app.daiyu.android.ChatController::class.java.getDeclaredField("pendingVideoState")
                .apply { isAccessible = true }.set(fixture.controller,
                    app.daiyu.android.ChatController.VideoSendState(request, editor, receipt, saved))
            invokeHost(activity, "settleRetainedVideoReceipt")
            assertNotNull("hidden chat cannot adopt the receipt", fixture.controller.videoSendState())
            assertNull(app.daiyu.android.MainActivity::class.java.getDeclaredField("videoReceiptRequest")
                .apply { isAccessible = true }.get(activity))
            assertEquals(editor.text, (invokeHost(activity, "getChatState") as ChatScreenState).composer.text)
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals("chat", invokeHost(activity, "getRetainedPage"))
            assertNull("navigation must take the business refresh fallback", fixture.controller.videoSendState())
        }
        awaitUniqueTag("chat-draft")
        assertDraft("")
        assertEquals(0, fixture.opens.get())
    }

    fun testRealChatOrdinaryReturnDrawsLiveContentWithoutRemount() = assertRealChatReturn(swipe = false)

    fun testRealChatSwipeReturnDrawsLiveContentWithoutRemount() = assertRealChatReturn(swipe = true)

    private fun assertRealChatReturn(swipe: Boolean, nested: Boolean = false, rtl: Boolean = false, topLevel: Boolean = false, resumePixels: Boolean = false) {
        val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
        var marker by mutableStateOf(Color.Green)
        var messagesIdentity: Any? = null
        var composerIdentity: Any? = null
        var liveDraws = 0
        var chatFrame: PageBackPreviewLayers.Frame? = null
        var settingsFrame: PageBackPreviewLayers.Frame? = null
        var detailFrame: PageBackPreviewLayers.Frame? = null
        var settingsDetail by mutableStateOf(false)
        var observedStack: PageBackStackRegistry? = null
        val exactPixels = mutableMapOf<PageBackPreviewLayers.Frame, androidx.compose.ui.graphics.ImageBitmap>()
        var immediateCaptureRequests = 0
        val draft = ComposerUi("retained draft", selectionStart = 2, selectionEnd = 5)
        val actions = ChatScreenActions()
        val turns = List(12) { ChatTurnUi.User("u$it", "earlier row $it") } +
            ChatTurnUi.Assistant("retained-answer", finalText = "**Retained formatted answer**")
        try {
            mainThread {
                parent = Parent().also { it.registry.currentState = Lifecycle.State.RESUMED }
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        CompositionLocalProvider(LocalLifecycleOwner provides if (resumePixels) activity else parent,
                            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                            val rendered = route
                            PageBackGestureHost(rendered, contentKey = rendered,
                                canNavigateBack = rendered != "chat",
                                swipeDismissEnabled = true, predictiveBackEnabled = false,
                                dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                                backTargetKey = when (rendered) { "chat" -> null; "appearance" -> "settings"; else -> "chat" },
                                retainedRootKey = "chat", retainedRootScopeKey = scopeKey,
                                previewCapture = { frame ->
                                    when (frame.key) {
                                        "chat" -> chatFrame = frame
                                        "settings" -> settingsFrame = frame
                                        else -> detailFrame = frame
                                    }
                                    if (!swipe && frame.navigationFrozen) {
                                        immediateCaptureRequests++
                                        checkNotNull(exactPixels[frame])
                                    } else frame.layer.toImageBitmap().also { exactPixels[frame] = it }
                                },
                                retainedRootContent = {
                                    DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                                    Box(Modifier.fillMaxSize().drawWithContent { liveDraws++; drawContent() }) {
                                        ChatConversationLayers(
                                            messages = { inset ->
                                                val identity = remember { Any() }
                                                SideEffect { messagesIdentity = identity }
                                                ChatMessageList(turns, actions,
                                                    composerBottomInset = inset,
                                                    scrollRequest = ChatScrollRequest(1))
                                            },
                                            composer = {
                                                val identity = remember { Any() }
                                                SideEffect { composerIdentity = identity }
                                                ChatComposer(draft, actions)
                                            },
                                        )
                                        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(32.dp).background(marker).testTag("retained-live-marker"))
                                    }
                                },
                            ) {
                                val registry = LocalPageBackStackPath.current?.registry
                                SideEffect { observedStack = registry }
                                if (rendered != "chat") {
                                    PageBackHandler { detailBacks++; route = if (rendered == "appearance") "settings" else "chat" }
                                    PageBackStack(if (settingsDetail) "detail" else null) {
                                        PageBackHandler(enabled = settingsDetail) { detailBacks++; settingsDetail = false }
                                        Box(Modifier.fillMaxSize().background(if (settingsDetail || rendered == "appearance") Color.Yellow else Color.Blue).testTag("detail-root"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            dismissIme()
            val answer = ui.onNodeWithText("Retained formatted answer")
            ui.waitUntil(5_000) { ui.onAllNodesWithText("Retained formatted answer").fetchSemanticsNodes().isNotEmpty() }
            answer.assertIsDisplayed()
            val baselinePixels = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
            val baselineHost = ui.onNodeWithTag("page-back-host").fetchSemanticsNode().boundsInRoot
            val baselineMarker = ui.onNodeWithTag("retained-live-marker").fetchSemanticsNode().boundsInRoot
            assertTrue("fixed baseline sample is inside the live marker", baselineMarker.contains(Offset(
                baselineHost.left + (baselinePixels.width * .05f).toInt(), baselineHost.top + baselinePixels.height / 2)))
            val baseline = baselinePixels[(baselinePixels.width * .05f).toInt(), baselinePixels.height / 2]
            assertTrue("the baseline probe must actually hit the green live-chat marker: $baseline",
                baseline.green > .85f && baseline.red < .15f && baseline.blue < .15f)
            ui.waitUntil(5_000) { chatFrame?.let { !it.frozen && !it.releaseRequested && it.ready != null && it.readySize == it.size } == true }
            val originalMessage = answer.fetchSemanticsNode().id
            val originalBounds = answer.fetchSemanticsNode().boundsInRoot
            val originalComposer = ui.onNodeWithTag("chat-draft").fetchSemanticsNode().id
            val originalMessagesIdentity = checkNotNull(messagesIdentity)
            val originalComposerIdentity = checkNotNull(composerIdentity)
            mainThread { route = "settings" }
            ui.mainClock.advanceTimeBy(1000)
            captureScreenNode(ui.onNodeWithTag("page-back-host"))
            ui.waitUntil(5_000) { settingsFrame?.let { !it.frozen && !it.releaseRequested && it.ready != null && it.readySize == it.size } == true }
            if (nested) {
                mainThread { if (topLevel) route = "appearance" else settingsDetail = true }
                ui.mainClock.advanceTimeBy(1000)
                captureScreenNode(ui.onNodeWithTag("page-back-host"))
                ui.waitUntil(5_000) { detailFrame?.let { !it.frozen && !it.releaseRequested && it.ready != null && it.readySize == it.size } == true }
            }
            mainThread { marker = Color.Magenta }
            val beforeReturnDraws = liveDraws
            ui.mainClock.autoAdvance = false
            if (nested) {
                val preparedDetail = checkNotNull(detailFrame)
                val preparedSettings = checkNotNull(settingsFrame)
                assertTrue("prepared child owns this visit: key=${preparedDetail.key} frozen=${preparedDetail.frozen} released=${preparedDetail.releaseRequested}",
                    !preparedDetail.frozen && !preparedDetail.releaseRequested && preparedDetail.readySize == preparedDetail.size)
                assertTrue("settings target owns frozen pixels before first down: key=${preparedSettings.key} frozen=${preparedSettings.frozen} ready=${preparedSettings.ready != null} size=${preparedSettings.size}",
                    !preparedSettings.releaseRequested && preparedSettings.canDrawPreview)
                ui.onNodeWithTag("page-back-host").performTouchInput {
                    down(Offset(width * if (rtl) .95f else .05f, height * .6f))
                    moveTo(Offset(width * if (rtl) .85f else .15f, height * .6f), 32)
                    for (step in 1..18) {
                        val fraction = .15f + .50f * step / 18f
                        moveTo(Offset(width * if (rtl) 1f - fraction else fraction, height * .6f), 16)
                    }
                }
                ui.mainClock.advanceTimeByFrame()
                assertTrue("child gesture locks its prepared pixels: key=${preparedDetail.key} frozen=${preparedDetail.frozen} image=${preparedDetail.image != null} animate=${preparedDetail.animateReturn} target=${preparedDetail.backTargetKey}",
                    preparedDetail.frozen && preparedDetail.canDrawPreview && preparedDetail.animateReturn)
                ui.onNodeWithTag("page-back-target").assertExists()
                val firstDragHost = ui.onNodeWithTag("page-back-host").fetchSemanticsNode().boundsInRoot
                val firstDrag = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
                val firstDragProgress = if (rtl) (firstDragHost.right - firstDrag.right) / firstDragHost.width
                    else (firstDrag.left - firstDragHost.left) / firstDragHost.width
                assertTrue("first gesture has a prepared interactive preview: $firstDragProgress", firstDragProgress > .5f && firstDragProgress < .6f)
                ui.onNodeWithTag("page-back-host").performTouchInput { up() }
                assertEquals("first pop stays in settings", "settings", route)
                assertFalse(settingsDetail)
                assertEquals(1, detailBacks)
                ui.mainClock.advanceTimeByFrame()
                if (!topLevel) {
                    assertEquals("local pop publishes the parent path before the host observes it", "settings",
                        checkNotNull(observedStack).routeKey("settings"))
                    ui.mainClock.advanceTimeByFrame()
                }
                val firstHost = ui.onNodeWithTag("page-back-host").fetchSemanticsNode().boundsInRoot
                val firstStart = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
                ui.mainClock.advanceTimeBy(32)
                val first = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
                assertTrue("first departure must also move before handoff", if (rtl) first.right < firstStart.right else first.left > firstStart.left)
                val firstProgress = if (rtl) (firstHost.right - first.right) / firstHost.width
                    else (first.left - firstHost.left) / firstHost.width
                assertTrue("first departure is still running: $firstProgress", firstProgress > 0f && firstProgress < 1f)
                val secondDown = firstHost.left + firstHost.width * if (rtl) .20f else .80f
                assertTrue("second down hits the running departure", secondDown > first.left && secondDown < first.right)
                ui.onNodeWithTag("page-back-host").performTouchInput {
                    down(Offset(width * if (rtl) .20f else .80f, height * .6f))
                    moveTo(Offset(width * if (rtl) .14f else .86f, height * .6f), 32)
                    moveTo(Offset(width * if (rtl) .10f else .90f, height * .6f), 16)
                }
                ui.mainClock.advanceTimeByFrame()
                assertEquals("second gesture must not pop before up", "settings", route)
                assertEquals(1, detailBacks)
                ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
                ui.onNodeWithTag("page-back-target").assertExists()
                val dragging = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
                val dragProgress = if (rtl) (firstHost.right - dragging.right) / firstHost.width
                    else (dragging.left - firstHost.left) / firstHost.width
                assertTrue("second gesture immediately owns visible motion: $dragProgress", dragProgress > 0f && dragProgress < .18f)
                val targetPixels = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
                val target = targetPixels[(targetPixels.width * if (rtl) .95f else .05f).toInt(), targetPixels.height / 2]
                assertTrue("second gesture reveals cached green chat, not the child or blank: $target",
                    target.green > .35f && target.red < .15f && target.blue < .15f)
                ui.onNodeWithTag("page-back-host").performTouchInput {
                    // Re-establish a constant 1.25 screens/s release after the pixel observation.
                    for (fraction in listOf(.92f, .94f, .96f, .98f)) {
                        moveTo(Offset(width * if (rtl) 1f - fraction else fraction, height * .6f), 16)
                    }
                    up()
                }
            } else if (swipe) {
                ui.onNodeWithTag("page-back-host").performTouchInput {
                    down(Offset(width * .02f, height * .6f))
                    moveTo(Offset(width * .12f, height * .6f), 16)
                    moveTo(Offset(width * .70f, height * .6f), 160)
                }
                ui.mainClock.advanceTimeByFrame()
                ui.onNodeWithTag("page-back-host").performTouchInput { up() }
            } else ui.runOnIdle {
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals("business return is synchronous", "chat", route)
            }
            if (!swipe) assertTrue("ordinary-motion fixture accepted its unchanged final source", immediateCaptureRequests > 0)
            assertEquals("both committed releases invoke their callbacks", if (nested) 2 else 1, detailBacks)
            assertEquals("chat", route)
            assertEquals(if (nested) 2 else 1, detailBacks)
            ui.mainClock.advanceTimeByFrame()
            val departure = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
            ui.mainClock.advanceTimeBy(64)
            val advanced = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
            assertTrue("departure must animate after synchronous pop", if (rtl) advanced.right < departure.right else advanced.left > departure.left)
            val hostBounds = ui.onNodeWithTag("page-back-host").fetchSemanticsNode().boundsInRoot
            val returnProgress = if (rtl) (hostBounds.right - advanced.right) / hostBounds.width
                else (advanced.left - hostBounds.left) / hostBounds.width
            assertTrue("second departure stays in an intermediate frame: $returnProgress", returnProgress > .05f && returnProgress < 1f)
            val liveBounds = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            val parallax = hostBounds.width * .25f * (1f - returnProgress)
            if (rtl) assertEquals(hostBounds.left + parallax, liveBounds.left, 1.1f)
            else assertEquals(hostBounds.right - parallax, liveBounds.right, 1.1f)
            ui.onNodeWithTag("page-back-departure-scrim").assertExists()
            val pixels = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
            val returnMarker = ui.onNodeWithTag("retained-live-marker").fetchSemanticsNode().boundsInRoot
            val sampleX = (pixels.width * if (rtl) .95f else .05f).toInt()
            assertTrue("fixed return sample is inside the live marker", returnMarker.contains(Offset(
                hostBounds.left + sampleX, hostBounds.top + pixels.height / 2)))
            val sample = pixels[sampleX, pixels.height / 2]
            if (nested) {
                val outgoingX = (pixels.width * if (rtl) .05f else .95f).toInt()
                assertTrue("outgoing probe is inside the departing settings", advanced.contains(Offset(
                    hostBounds.left + outgoingX, hostBounds.top + pixels.height / 2)))
                val outgoing = pixels[outgoingX, pixels.height / 2]
                assertTrue("second departure owns blue settings pixels, not yellow child: $outgoing",
                    outgoing.blue > .85f && outgoing.red < .15f && outgoing.green < .15f)
            }
            assertTrue("live chat must draw underneath the departure, not an old target bitmap", liveDraws > beforeReturnDraws)
            assertTrue("return must reveal current magenta chat pixels, not old green or white: $sample",
                sample.red > .35f && sample.blue > .35f && sample.green < .15f)
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            answer.assertIsDisplayed()
            assertEquals(originalMessage, answer.fetchSemanticsNode().id)
            assertEquals(originalBounds, answer.fetchSemanticsNode().boundsInRoot)
            assertSame(originalMessagesIdentity, messagesIdentity)
            assertSame(originalComposerIdentity, composerIdentity)
            assertEquals(originalComposer, ui.onNodeWithTag("chat-draft").fetchSemanticsNode().id)
            ui.onNodeWithTag("chat-draft").assertTextContains("retained draft")
            ui.onNodeWithTag("chat-draft").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange,
                androidx.compose.ui.text.TextRange(2, 5)))
            ui.onAllNodesWithTag("markdown-pending").assertCountEquals(0)
            assertEquals(if (nested) 2 else 1, detailBacks)
            assertEquals(0, rootBacks)
            assertEquals(1, mounts)
            assertEquals(0, disposals)
            if (resumePixels) {
                val beforeResume = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
                realPauseResume(activity)
                ui.mainClock.advanceTimeBy(64)
                ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
                ui.onNodeWithTag("page-back-target").assertDoesNotExist()
                val afterResume = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
                assertEquals(beforeResume.width, afterResume.width)
                assertEquals(beforeResume.height, afterResume.height)
                // Sample only the stable message area, outside system bars and the editor caret.
                for (y in beforeResume.height / 5 until beforeResume.height * 3 / 5 step 4) {
                    for (x in beforeResume.width / 5 until beforeResume.width * 4 / 5 step 4) {
                        val before = beforeResume[x, y]
                        val after = afterResume[x, y]
                        assertEquals("resume red at $x,$y", before.red, after.red, .015f)
                        assertEquals("resume green at $x,$y", before.green, after.green, .015f)
                        assertEquals("resume blue at $x,$y", before.blue, after.blue, .015f)
                    }
                }
                assertEquals(1, mounts)
                assertEquals(0, disposals)
            }
        } finally {
            ui.mainClock.autoAdvance = true
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    fun testRealChatIdlePauseResumeKeepsOneTextFrame() = assertRealChatReturn(swipe = true, resumePixels = true)

    fun testRealChatRapidNestedSwipeReturnsKeepBothAnimations() = assertRealChatReturn(swipe = true, nested = true)

    fun testRealChatRapidNestedSwipeReturnsKeepBothAnimationsInRtl() = assertRealChatReturn(swipe = true, nested = true, rtl = true)

    fun testRealChatRapidTopLevelSwipeReturnsKeepBothAnimations() = assertRealChatReturn(swipe = true, nested = true, topLevel = true)

    fun testHiddenRootReleasesOnPauseAndIsNotRemountedUntilVisited() {
        mount()
        val original = checkNotNull(rootOwner)
        mainThread { route = "settings" }
        mainThread { parent.registry.currentState = Lifecycle.State.CREATED }
        assertEquals(1, mounts)
        assertEquals(1, disposals)
        assertEquals(Lifecycle.State.DESTROYED, original.lifecycle.currentState)
        mainThread { parent.registry.currentState = Lifecycle.State.RESUMED }
        assertEquals(1, mounts)
        ui.onNodeWithTag("retained-root").assertDoesNotExist()
        mainThread { route = "chat" }
        ui.onNodeWithTag("retained-root").assertIsDisplayed()
        assertEquals(2, mounts)
        assertNotSame(original, rootOwner)
        assertEquals(Lifecycle.State.RESUMED, checkNotNull(rootOwner).lifecycle.currentState)
    }

    fun testScopeChangeDisposesTheOldRootRatherThanRetainingTwoInstances() {
        mount()
        val original = checkNotNull(rootOwner)
        mainThread { route = "settings" }
        mainThread { scopeKey++ }
        assertEquals(1, disposals)
        assertEquals(Lifecycle.State.DESTROYED, original.lifecycle.currentState)
        assertEquals(1, mounts)
        mainThread { route = "chat" }
        assertEquals(2, mounts)
        assertEquals(1, disposals)
        assertNotSame(original, rootOwner)
        assertEquals(2, owners.size)
        ui.onNodeWithTag("retained-root").assertIsDisplayed()
    }
}
