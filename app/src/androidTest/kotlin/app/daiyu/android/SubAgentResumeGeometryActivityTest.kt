package app.daiyu.android

import android.content.Intent
import android.view.Choreographer
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.components.subagent.LocalConversationAgentStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID

class SubAgentResumeGeometryActivityTest : WiredActivityTestCase() {
    private var preferencesName: String? = null
    private lateinit var source: SavedCatalogSubAgentModelSource
    private lateinit var editor: ConversationSubAgentEditor
    private lateinit var session: SubAgentProfileDraftSession
    private lateinit var repository: ConversationSubAgentPreferences
    private val owner = SubAgentConfigKey.Conversation("resume-fixture")
    private val provider = SubAgentProvider("provider", "Provider", listOf(
        SubAgentModel("selection", "gpt-resume-model", supportsGptSpeed = true)))

    override fun tearDown() {
        try { super.tearDown() }
        finally { preferencesName?.let { instrumentation.targetContext.deleteSharedPreferences(it) } }
    }

    private fun reload(loaded: List<SubAgentProvider>) = runBlocking {
        withContext(Dispatchers.Main) { source.refreshAll { loaded } }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun show(dark: Boolean = false, scale: Float = 1f) {
        preferencesName?.let { instrumentation.targetContext.deleteSharedPreferences(it) }
        val name = "subagent-resume-${UUID.randomUUID()}"
        preferencesName = name
        repository = ConversationSubAgentPreferences(instrumentation.targetContext.getSharedPreferences(name, 0))
        val profile = SubAgentProfile("profile", "Worker", providerId = provider.id, modelId = provider.models.single().id)
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        source = SavedCatalogSubAgentModelSource()
        reload(listOf(provider))
        mainThread {
            editor = ConversationSubAgentEditor(owner, repository, source) { true }
            session = checkNotNull(SubAgentProfileDraftSession.open(editor, profile))
            activity.setContent {
                AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = true) {
                    val providers by source.providers.collectAsState()
                    CompositionLocalProvider(LocalConversationAgentStyle provides true) {
                        SubAgentProfileConfigDialog(session, editor, providers, onDismiss = {})
                    }
                }
            }
        }
        awaitUniqueTag("conversation-agent-sheet")
        val sheet = node("conversation-agent-sheet")
        awaitNodeWindowFocus(sheet)
        val view = (sheet.fetchSemanticsNode().root as ViewRootForTest).view
        dismissIme(view.rootView)
        awaitImeFrame(sheet, visible = false)
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
        mainThread { session.name = "Unsaved worker"; session.editParallel("7") }
        val surface = sheet.fetchSemanticsNode()
        awaitStableWindowFrame(view) {
            val insets = ViewCompat.getRootWindowInsets(view)
            if (insets == null || insets.isVisible(WindowInsetsCompat.Type.ime()) ||
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom != 0) null
            else view.rootView.height to Rect(surface.positionInRoot,
                Size(surface.size.width.toFloat(), surface.size.height.toFloat()))
        }
    }

    private fun geometry(): Rect {
        val surface = node("conversation-agent-sheet").assertIsDisplayed().fetchSemanticsNode()
        return Rect(surface.positionInRoot, Size(surface.size.width.toFloat(), surface.size.height.toFloat()))
    }

    private fun assertGeometry(expected: Rect) {
        val actual = geometry()
        assertEquals("top remains fixed", expected.top, actual.top, 2f)
        assertEquals("bottom remains fixed", expected.bottom, actual.bottom, 2f)
        assertEquals("width remains fixed", expected.width, actual.width, 2f)
        assertEquals("height remains fixed", expected.height, actual.height, 2f)
        assertEquals("Unsaved worker", session.name)
        assertEquals("7", session.parallelValue)
        assertEquals("Worker", repository.snapshot(owner).profiles.single().name)
        assertEquals(0, fixture.opens.get())
    }

    fun testRefreshRetainsRowsAndGeometryInBothThemesAndScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(dark, scale)
            val before = geometry()
            repeat(2) {
                mainThread { source.clear() }
                assertTrue(source.providers.value.isEmpty())
                assertFalse(session.canCommit(editor, true))
                ui.onNodeWithContentDescription("草稿GPT速度").assertExists().assertIsNotEnabled()
                ui.onNodeWithContentDescription("草稿模型").assertExists().assertIsNotEnabled()
                repeat(3) { ui.mainClock.advanceTimeByFrame(); assertGeometry(before) }
                reload(listOf(provider))
                awaitVisualIdle()
                assertTrue(session.canCommit(editor, true))
                ui.onNodeWithContentDescription("草稿GPT速度").assertIsEnabled()
                assertGeometry(before)
            }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testRealBackgroundResumeKeepsTheSameDraftWhileReloadIsPending() {
        show()
        val before = geometry()
        val surface = node("conversation-agent-sheet").fetchSemanticsNode()
        val root = surface.root
        val view = (root as ViewRootForTest).view
        data class Frame(val bounds: Rect, val refreshing: Boolean, val windowHeight: Int, val imeBottom: Int)
        val frames = java.util.Collections.synchronizedList(mutableListOf<Frame>())
        var stopped = false
        var frameTime = Long.MIN_VALUE
        var sampledFrame = Long.MIN_VALUE
        val lifecycleObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stopped = true }
        val choreographer = mainChoreographer()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameTime = frameTimeNanos
                view.invalidate()
                choreographer.postFrameCallback(this)
            }
        }
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (stopped && activity.lifecycle.currentState == Lifecycle.State.RESUMED && view.isShown &&
                frameTime != Long.MIN_VALUE && frameTime != sampledFrame) {
                sampledFrame = frameTime
                val bounds = Rect(surface.positionInRoot, Size(surface.size.width.toFloat(), surface.size.height.toFloat()))
                val imeBottom = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                frames += Frame(bounds, source.refreshing.value, view.rootView.height, imeBottom)
            }
            true
        }
        instrumentation.runOnMainSync {
            activity.lifecycle.addObserver(lifecycleObserver)
            view.viewTreeObserver.addOnPreDrawListener(preDraw)
            choreographer.postFrameCallback(callback)
        }
        try {
            instrumentation.runOnMainSync {
                source.clear()
                assertTrue(activity.moveTaskToBack(true))
            }
            ui.waitUntil(5_000) { activity.lifecycle.currentState == Lifecycle.State.CREATED }
            assertFalse(session.canCommit(editor, true))
            instrumentation.runOnMainSync {
                instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            ui.waitUntil(5_000) { synchronized(frames) { frames.count { it.refreshing } >= 2 } }
            assertSame(root, node("conversation-agent-sheet").fetchSemanticsNode().root)
            assertGeometry(before)
            reload(listOf(provider))
            ui.waitUntil(5_000) { synchronized(frames) { frames.count { !it.refreshing } >= 2 } }
        } finally {
            instrumentation.runOnMainSync {
                activity.lifecycle.removeObserver(lifecycleObserver)
                view.viewTreeObserver.removeOnPreDrawListener(preDraw)
                choreographer.removeFrameCallback(callback)
            }
        }
        val samples = synchronized(frames) { frames.toList() }
        assertTrue("real return frames captured", samples.size >= 4)
        for (sample in samples) {
            assertEquals("return-frame top: $sample", before.top, sample.bounds.top, 2f)
            assertEquals("return-frame height: $sample", before.height, sample.bounds.height, 2f)
            assertEquals("return-frame bottom: $sample", before.bottom, sample.bounds.bottom, 2f)
        }
        assertGeometry(before)
    }

    private fun mainChoreographer(): Choreographer {
        lateinit var value: Choreographer
        instrumentation.runOnMainSync { value = Choreographer.getInstance() }
        return value
    }

    fun testARealRemovedModelIsNotKeptAsAValidBinding() {
        show()
        mainThread { source.clear() }
        assertFalse(session.canCommit(editor, true))
        reload(emptyList())
        awaitVisualIdle()
        ui.onNodeWithContentDescription("草稿GPT速度").assertDoesNotExist()
        ui.onNodeWithContentDescription("草稿思考深度").assertIsNotEnabled()
        ui.onNodeWithText("确认").assertIsNotEnabled()
        assertTrue(source.displayProviders.value.isEmpty())
        assertEquals("Unsaved worker", session.name)
        assertEquals("7", session.parallelValue)
        val result = runBlocking {
            editor.commitProfileDraft(session.draft.copy(name = session.name), session.expectedProfile,
                session.expectedApplicationToken, null) { session.canCommit(editor, true) }
        }
        assertTrue(result is ConversationSubAgentPreferences.WriteResult.Rejected)
        assertEquals("Worker", repository.snapshot(owner).profiles.single().name)
        reload(listOf(provider))
        awaitVisualIdle()
        assertEquals("Unsaved worker", session.name)
        assertEquals("7", session.parallelValue)
        assertEquals(0, fixture.opens.get())
    }
}
