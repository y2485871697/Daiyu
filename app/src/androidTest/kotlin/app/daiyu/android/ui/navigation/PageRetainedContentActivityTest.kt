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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.test.platform.graphics.HardwareRendererCompat
import app.daiyu.android.ui.screens.chat.conversation.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.WiredActivityTestCase
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

    fun testRealChatOrdinaryReturnDrawsLiveContentWithoutRemount() = assertRealChatReturn(swipe = false)

    fun testRealChatSwipeReturnDrawsLiveContentWithoutRemount() = assertRealChatReturn(swipe = true)

    private fun assertRealChatReturn(swipe: Boolean) {
        val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
        var marker by mutableStateOf(Color.Green)
        var messagesIdentity: Any? = null
        var composerIdentity: Any? = null
        var liveDraws = 0
        var chatFrame: PageBackPreviewLayers.Frame? = null
        var settingsFrame: PageBackPreviewLayers.Frame? = null
        val draft = ComposerUi("retained draft", selectionStart = 2, selectionEnd = 5)
        val actions = ChatScreenActions()
        val turns = List(12) { ChatTurnUi.User("u$it", "earlier row $it") } +
            ChatTurnUi.Assistant("retained-answer", finalText = "**Retained formatted answer**")
        try {
            mainThread {
                parent = Parent().also { it.registry.currentState = Lifecycle.State.RESUMED }
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        CompositionLocalProvider(LocalLifecycleOwner provides parent) {
                            val rendered = route
                            PageBackGestureHost(rendered, contentKey = rendered,
                                canNavigateBack = rendered != "chat",
                                swipeDismissEnabled = true, predictiveBackEnabled = false,
                                dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                                backTargetKey = if (rendered == "chat") null else "chat",
                                retainedRootKey = "chat", retainedRootScopeKey = scopeKey,
                                previewCapture = { frame ->
                                    if (frame.key == "chat") chatFrame = frame else settingsFrame = frame
                                    frame.layer.toImageBitmap()
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
                                        Box(Modifier.fillMaxWidth().height(32.dp).background(marker))
                                    }
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
            val answer = ui.onNodeWithText("Retained formatted answer")
            ui.waitUntil(5_000) { ui.onAllNodesWithText("Retained formatted answer").fetchSemanticsNodes().isNotEmpty() }
            answer.assertIsDisplayed()
            captureScreenNode(ui.onNodeWithTag("page-back-host"))
            ui.waitUntil(5_000) { chatFrame?.ready != null }
            val originalMessage = answer.fetchSemanticsNode().id
            val originalBounds = answer.fetchSemanticsNode().boundsInRoot
            val originalComposer = ui.onNodeWithTag("chat-draft").fetchSemanticsNode().id
            val originalMessagesIdentity = checkNotNull(messagesIdentity)
            val originalComposerIdentity = checkNotNull(composerIdentity)
            mainThread { route = "settings" }
            ui.mainClock.advanceTimeBy(1000)
            captureScreenNode(ui.onNodeWithTag("page-back-host"))
            ui.waitUntil(5_000) { settingsFrame?.ready != null }
            mainThread { marker = Color.Magenta }
            val beforeReturnDraws = liveDraws
            ui.mainClock.autoAdvance = false
            if (swipe) {
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
            assertEquals("chat", route)
            assertEquals(1, detailBacks)
            ui.mainClock.advanceTimeByFrame()
            val departure = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
            ui.mainClock.advanceTimeBy(64)
            val advanced = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
            assertTrue("departure must animate after synchronous pop", advanced.left > departure.left)
            val hostBounds = ui.onNodeWithTag("page-back-host").fetchSemanticsNode().boundsInRoot
            assertTrue("sample must be left of the departing settings", advanced.left > hostBounds.left + hostBounds.width * .05f)
            val pixels = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
            val sample = pixels[(pixels.width * .05f).toInt(), 8]
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
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        } finally {
            ui.mainClock.autoAdvance = true
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

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
