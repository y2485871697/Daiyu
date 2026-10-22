package app.daiyu.android

import android.content.Intent
import android.view.Choreographer
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class ProviderDirectoryResumeGeometryActivityTest : WiredActivityTestCase() {
    private val summary = "支持 ChatGPT、DeepSeek、Kimi、GLM、Qwen 等"
    private fun row(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true)
    private fun text(tag: String, value: String) = ui.onNode(
        hasText(value) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
    private fun rect(node: SemanticsNode) = Rect(node.positionInRoot,
        Size(node.size.width.toFloat(), node.size.height.toFloat()))
    private fun bounds(tags: List<String>) = tags.associateWith { rect(row(it).fetchSemanticsNode()) }
    private fun assertRect(label: String, expected: Rect, actual: Rect) {
        assertEquals("$label left", expected.left, actual.left, 1f)
        assertEquals("$label top", expected.top, actual.top, 1f)
        assertEquals("$label right", expected.right, actual.right, 1f)
        assertEquals("$label bottom", expected.bottom, actual.bottom, 1f)
    }
    private fun assertBounds(expected: Map<String, Rect>, actual: Map<String, Rect>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (tag, before) -> assertRect(tag, before, actual.getValue(tag)) }
    }
    private data class TextGeometry(val bounds: Rect, val size: androidx.compose.ui.unit.IntSize,
        val lineEnds: List<Int>)
    private fun textGeometry(tag: String, value: String): TextGeometry {
        val target = text(tag, value)
        val layouts = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        return TextGeometry(rect(target.fetchSemanticsNode()), layout.size,
            (0 until layout.lineCount).map { layout.getLineEnd(it, visibleEnd = true) })
    }
    private fun assertTextGeometry(expected: TextGeometry, actual: TextGeometry) {
        assertRect("summary", expected.bounds, actual.bounds)
        assertEquals(expected.size, actual.size)
        assertEquals(expected.lineEnds, actual.lineEnds)
    }
    private fun show(dark: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        val visit = Any()
        mainThread { activity.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(370.6.dp, 900.dp))) {
                val platform = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platform.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK
                        else AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                        key(visit) { content() }
                    }
                }
            }
        } }
    }

    fun testDirectoryDisableKeepsRowsTextAndFollowingCardInPlace() {
        val registry = fixture.controller.providers.snapshot()
        val slot = registry.providers.single().slotId
        val summaries = mapOf(slot to ProviderCardSummary("https://example.invalid", ProviderProtocol.RESPONSES, 1))
        val tags = listOf("provider-add", "provider-add-anthropic", "provider-add-speech", "provider-open-$slot")
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            val enabled = mutableStateOf(true)
            val calls = IntArray(4)
            show(dark, fontScale) { ProviderSettingsTheme {
                ProviderDirectoryPage(registry, summaries, "", {}, enabled.value, null, {},
                    { calls[0]++ }, { calls[1]++ }, { calls[2]++ }, { calls[3]++ })
            } }
            awaitUniqueTag("provider-add")
            val before = bounds(tags)
            val textBefore = textGeometry("provider-add", summary)
            row("provider-add").assertHasClickAction().performClick()
            row("provider-add-anthropic").assertHasClickAction().performClick()
            mainThread { enabled.value = false }
            row("provider-add").assertHasNoClickAction().performTouchInput { click() }
            row("provider-add-anthropic").assertHasNoClickAction().performTouchInput { click() }
            row("provider-open-$slot").assertIsNotEnabled().performTouchInput { click() }
            assertEquals(listOf(1, 1, 0, 0), calls.toList())
            repeat(3) {
                ui.mainClock.advanceTimeByFrame()
                assertBounds(before, bounds(tags))
                assertTextGeometry(textBefore, textGeometry("provider-add", summary))
            }
            row("provider-add-speech").assertHasNoClickAction()
            mainThread { enabled.value = true }
            assertBounds(before, bounds(tags))
            assertTextGeometry(textBefore, textGeometry("provider-add", summary))
            row("provider-add").performClick()
            row("provider-add-anthropic").performClick()
            assertEquals(listOf(2, 2, 0, 0), calls.toList())
        }
        assertEquals(registry, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }

    fun testAuthenticationDisableKeepsApiKeyAndReadOnlyOauthInPlace() {
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            val enabled = mutableStateOf(true)
            var calls = 0
            show(dark, fontScale) { ProviderSettingsTheme {
                ProviderAuthenticationPage(enabled.value, null, {}, { calls++ })
            } }
            val tags = listOf("provider-auth-api-key", "provider-auth-oauth")
            val before = bounds(tags)
            val description = "OpenAI 官方接口或兼容的第三方站点"
            val textBefore = textGeometry(tags.first(), description)
            row(tags.first()).performClick()
            mainThread { enabled.value = false }
            row(tags.first()).assertHasNoClickAction().performTouchInput { click() }
            row(tags.last()).assertHasNoClickAction()
            assertEquals(1, calls)
            assertBounds(before, bounds(tags))
            assertTextGeometry(textBefore, textGeometry(tags.first(), description))
            mainThread { enabled.value = true }
            assertBounds(before, bounds(tags))
            assertTextGeometry(textBefore, textGeometry(tags.first(), description))
            row(tags.first()).performClick()
            assertEquals(2, calls)
        }
        assertEquals(0, fixture.opens.get())
    }

    private fun walk(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(walk(it)) }
    }
    private data class Frame(val geometry: Map<String, Rect>, val summary: Rect,
        val actionable: Boolean, val gateReleased: Boolean, val windowHeight: Int, val imeBottom: Int)

    fun testRealBackgroundResumeKeepsGeometryAcrossBlockedRegistryRefresh() {
        val registry = fixture.controller.providers.snapshot()
        val saved = fixture.settings.load()
        val slot = registry.providers.single().slotId
        val tags = listOf("provider-add", "provider-add-anthropic", "provider-add-speech", "provider-open-$slot")
        dismissIme()
        show(false, .9f) { ProviderManagementRoute(fixture.controller, { true }, {}) }
        awaitUniqueTag("provider-add")
        ui.waitUntil(5_000) { row("provider-add").fetchSemanticsNode().config.contains(SemanticsActions.OnClick) }
        awaitImeFrame(row("provider-add"), visible = false)
        val before = bounds(tags)
        val textBefore = textGeometry("provider-add", summary)
        val testRoot = row("provider-add").fetchSemanticsNode().root as ViewRootForTest
        val view = testRoot.view
        val frames = Collections.synchronizedList(mutableListOf<Frame>())
        val failure = AtomicReference<Throwable?>()
        val gateReleased = AtomicBoolean(false)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var blocked: Future<Unit>? = null
        var stopped = false
        var frameTime = Long.MIN_VALUE
        var sampledFrame = Long.MIN_VALUE
        val lifecycleObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stopped = true }
        lateinit var choreographer: Choreographer
        instrumentation.runOnMainSync { choreographer = Choreographer.getInstance() }
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameTime = frameTimeNanos
                view.postInvalidateOnAnimation()
                choreographer.postFrameCallback(this)
            }
        }
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (stopped && activity.lifecycle.currentState == Lifecycle.State.RESUMED && view.isShown &&
                frameTime != Long.MIN_VALUE && frameTime != sampledFrame) {
                sampledFrame = frameTime
                try {
                    val tagged = walk(testRoot.semanticsOwner.unmergedRootSemanticsNode).mapNotNull { node ->
                        node.config.getOrNull(SemanticsProperties.TestTag)?.let { it to node }
                    }.toMap()
                    val add = checkNotNull(tagged["provider-add"])
                    val summaryNode = walk(add).single { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == summary } == true
                    }
                    frames += Frame(tags.associateWith { rect(checkNotNull(tagged[it])) }, rect(summaryNode),
                        add.config.contains(SemanticsActions.OnClick), gateReleased.get(), view.rootView.height,
                        ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
                } catch (caught: Throwable) { failure.compareAndSet(null, caught) }
            }
            true
        }
        instrumentation.runOnMainSync {
            activity.lifecycle.addObserver(lifecycleObserver)
            view.viewTreeObserver.addOnPreDrawListener(preDraw)
            choreographer.postFrameCallback(callback)
        }
        try {
            instrumentation.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
            ui.waitUntil(5_000) { activity.lifecycle.currentState == Lifecycle.State.CREATED }
            blocked = executor.submit<Unit> {
                fixture.settings.modelCatalog.readConfiguration {
                    held.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "provider refresh gate was not released" }
                }
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                failure.get()?.let { throw it }
                synchronized(frames) { frames.count { !it.gateReleased && !it.actionable } >= 2 }
            }
            row("provider-add").assertHasNoClickAction().performTouchInput { click() }
            row("provider-add-anthropic").assertHasNoClickAction().performTouchInput { click() }
            row("provider-auth-api-key").assertDoesNotExist()
            gateReleased.set(true)
            release.countDown()
            blocked.get(5, TimeUnit.SECONDS)
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                failure.get()?.let { throw it }
                synchronized(frames) { frames.count { it.gateReleased && it.actionable } >= 2 }
            }
            awaitHostWindowFocus(activity)
            val recorded = synchronized(frames) { frames.toList() }
            assertTrue(recorded.map { it.windowHeight }.distinct().size == 1)
            recorded.forEach { frame ->
                assertEquals("return frame=$frame", 0, frame.imeBottom)
                assertBounds(before, frame.geometry)
                assertRect("return-frame summary", textBefore.bounds, frame.summary)
            }
            assertTextGeometry(textBefore, textGeometry("provider-add", summary))
            assertEquals(registry, fixture.controller.providers.snapshot())
            assertEquals(saved, fixture.settings.load())
            assertEquals(0, fixture.opens.get())
        } finally {
            release.countDown()
            instrumentation.runOnMainSync {
                activity.lifecycle.removeObserver(lifecycleObserver)
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(preDraw)
                choreographer.removeFrameCallback(callback)
            }
            try { blocked?.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }
}
