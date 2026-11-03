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

    private fun assertRealChatReturn(swipe: Boolean, nested: Boolean = false, rtl: Boolean = false, topLevel: Boolean = false) {
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
        val draft = ComposerUi("retained draft", selectionStart = 2, selectionEnd = 5)
        val actions = ChatScreenActions()
        val turns = List(12) { ChatTurnUi.User("u$it", "earlier row $it") } +
            ChatTurnUi.Assistant("retained-answer", finalText = "**Retained formatted answer**")
        try {
            mainThread {
                parent = Parent().also { it.registry.currentState = Lifecycle.State.RESUMED }
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        CompositionLocalProvider(LocalLifecycleOwner provides parent,
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
                                        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(32.dp).background(marker).testTag("retained-live-marker"))
                                    }
                                },
                            ) {
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
                    moveTo(Offset(width * if (rtl) .35f else .65f, height * .6f), 288)
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
                    moveTo(Offset(width * if (rtl) .10f else .90f, height * .6f), 32)
                    moveTo(Offset(width * if (rtl) .06f else .94f, height * .6f), 16)
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
                    moveTo(Offset(width * if (rtl) .04f else .96f, height * .6f), 4)
                    moveTo(Offset(width * if (rtl) .01f else .99f, height * .6f), 4)
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
        } finally {
            ui.mainClock.autoAdvance = true
            if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

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
