package app.daiyu.android

import android.content.Intent
import android.os.Bundle
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.ceil
import kotlin.math.floor
import android.test.InstrumentationTestCase
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Keeps the existing JUnit3 fixture lifecycle, with Compose's root registry around the whole test.
 * AndroidJUnitRunner registers the instrumentation and its arguments before tests run.
 */
@Suppress("DEPRECATION")
abstract class ComposeActivityTestCase : InstrumentationTestCase() {
    @OptIn(ExperimentalTestApi::class)
    protected val ui = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private var currentHost: MainActivity? = null
    private var voicePreferenceSnapshot: Pair<Boolean, String?>? = null
    protected open val engineeringUi: Boolean = false
    private fun runLegacy() = super.runBare()
    override fun runBare() {
        try {
            instrumentation.runOnMainSync {
                (instrumentation.targetContext.applicationContext as ChatApplication).engineeringUiForTests = engineeringUi
            }
            ui.apply(object : Statement() {
                override fun evaluate() {
                    var primary: Throwable? = null
                    try { runLegacy() }
                    catch (failure: Throwable) { primary = failure; throw failure }
                    finally {
                        try { cleanupAfterLegacy() }
                        catch (cleanup: Throwable) {
                            if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
                        }
                    }
                }
            }, Description.createTestDescription(javaClass, name)).evaluate()
        } finally {
            voicePreferenceSnapshot?.let { (hadValue, value) ->
                val editor = instrumentation.targetContext.getSharedPreferences("chat-voice-entry", 0).edit()
                if (hadValue) editor.putString("mode", value) else editor.remove("mode")
                check(editor.commit())
            }
            instrumentation.runOnMainSync {
                (instrumentation.targetContext.applicationContext as ChatApplication).engineeringUiForTests = false
            }
        }
    }
    protected open fun cleanupAfterLegacy() = Unit

    protected fun mainThread(action: () -> Unit) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        awaitVisualIdle()
    }
    protected fun awaitVisualIdle() {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        instrumentation.waitForIdleSync()
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    protected fun captureScreenNode(target: SemanticsNodeInteraction): ImageBitmap {
        awaitVisualIdle()
        val node = target.assertIsDisplayed().fetchSemanticsNode()
        val view = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
        awaitNodeWindowFocus(target)
        val token = view.windowToken
        val drawingEnabled = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        val frame = java.util.concurrent.CountDownLatch(1)
        var drawListener: android.view.ViewTreeObserver.OnDrawListener? = null
        var screen: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            if (!drawingEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
            fun sampleBounds() = node.boundsInRoot.translate(node.positionOnScreen - node.positionInRoot)
            val bounds = awaitStableWindowFrame(view) {
                if (view.windowToken != token || !node.layoutInfo.isPlaced ||
                    !node.layoutInfo.coordinates.isAttached) null else sampleBounds().takeIf {
                    it.width > 0f && it.height > 0f &&
                        listOf(it.left, it.top, it.right, it.bottom).all { value -> value.isFinite() }
                }
            }
            instrumentation.runOnMainSync {
                if (android.os.Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                    view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                } else {
                    drawListener = object : android.view.ViewTreeObserver.OnDrawListener {
                        override fun onDraw() { view.post { frame.countDown() } }
                    }
                    view.viewTreeObserver.addOnDrawListener(drawListener)
                }
                view.invalidate()
            }
            check(frame.await(2, java.util.concurrent.TimeUnit.SECONDS)) { "截图前未提交稳定几何后的绘制帧" }
            val captured = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "截图未返回图像" }
            screen = captured
            val after = ui.runOnIdle { sampleBounds() }
            check(view.windowToken == token && bounds == after) {
                "Screenshot geometry changed: before=$bounds after=$after screen=${captured.width}x${captured.height}"
            }
            val left = floor(bounds.left.toDouble()).toInt()
            val top = floor(bounds.top.toDouble()).toInt()
            val right = ceil(bounds.right.toDouble()).toInt()
            val bottom = ceil(bounds.bottom.toDouble()).toInt()
            check(left >= 0 && top >= 0 && right <= captured.width && bottom <= captured.height && right > left && bottom > top) {
                "Node crop $bounds outside ${captured.width}x${captured.height}"
            }
            return Bitmap.createBitmap(captured, left, top, right - left, bottom - top).also { cropped = it }.asImageBitmap()
        } finally {
            instrumentation.runOnMainSync { drawListener?.let { view.viewTreeObserver.removeOnDrawListener(it) } }
            if (!drawingEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false)
            if (cropped !== screen) screen?.recycle()
        }
    }

    protected fun node(tag: String): SemanticsNodeInteraction {
        val interaction = ui.onNodeWithTag(tag)
        val found = ui.onAllNodesWithTag(tag)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
        android.util.Log.d("DaiyuTestStep", "node-ancestors:$tag")
        if (found != null && generateSequence(found.parent) { it.parent }.any {
                it.config.getOrNull(SemanticsProperties.TestTag) == "chat-extra-actions"
            }) interaction.scrollToVisible()
        return interaction
    }
    protected fun awaitUniqueTag(tag: String, hostState: () -> String = { "" }) {
        ui.mainClock.advanceTimeByFrame()
        try {
            ui.waitUntil(5_000) {
                ui.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            fun nodes(unmerged: Boolean) = ui.onAllNodesWithTag(tag, useUnmergedTree = unmerged)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .joinToString(prefix = "[", postfix = "]") { "${it.id}:${it.parent?.id}" }
            var state = ""
            instrumentation.runOnMainSync { state = hostState() }
            throw AssertionError("tag=$tag merged=${nodes(false)} unmerged=${nodes(true)} $state", timeout)
        }
        node(tag).assertExists()
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    protected fun awaitNodeWindowFocus(target: SemanticsNodeInteraction) {
        val view = (target.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
        ui.waitUntil(5_000) {
            var focused = false
            instrumentation.runOnMainSync {
                focused = view.isAttachedToWindow && view.hasWindowFocus()
            }
            focused
        }
    }

    protected fun restoreOriginalHost(host: MainActivity) {
        check(!host.isDestroyed && !host.isFinishing)
        val component = android.content.ComponentName(instrumentation.targetContext, MainActivity::class.java)
            .flattenToShortString()
        val descriptor = java.util.concurrent.atomic.AtomicReference<android.os.ParcelFileDescriptor?>()
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
        val outputTask = reader.submit<String> {
            val pipe = instrumentation.uiAutomation.executeShellCommand("am start -n $component -f 0x30020000")
            descriptor.set(pipe)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).use { stream ->
                check(!Thread.currentThread().isInterrupted)
                stream.bufferedReader().use { it.readText() }
            }
        }
        val output = try {
            outputTask.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            outputTask.cancel(true)
            descriptor.get()?.close()
            reader.shutdownNow()
        }
        assertTrue("HOME return launch failed: $output", output.contains("Starting:") &&
            !output.contains("Error:") && !output.contains("Exception"))
    }

    protected fun awaitHostWindowFocus(host: MainActivity) {
        awaitControl("host resumed before focused-window check") {
            !host.isDestroyed && !host.isFinishing &&
                host.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
        }
        check(currentHost === host)
        awaitVisualIdle()
        focusedWindowRoot()
    }

    protected fun focusedWindowRoot(): android.view.View {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val host = checkNotNull(currentHost)
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var hostState = ""
        while (true) {
            var root: android.view.View? = null
            var failure: Throwable? = null
            // Do not enter Espresso's independent root-readiness/idle timeout inside this deadline.
            instrumentation.runOnMainSync {
                try {
                    check(currentHost === host && !host.isFinishing && !host.isDestroyed)
                    val hostDecor = host.window.decorView
                    val activityToken = host.window.attributes.token
                    root = androidx.test.platform.view.inspector.WindowInspectorCompat.getGlobalWindowViews()
                        .lastOrNull { decor ->
                            val params = decor.layoutParams as? android.view.WindowManager.LayoutParams
                                ?: return@lastOrNull false
                            var windowContext = decor.context
                            while (windowContext is android.content.ContextWrapper && windowContext !is android.app.Activity) {
                                windowContext = windowContext.baseContext
                            }
                            // Compose application Dialogs may have an independent layout token on API 26.
                            val owned = windowContext === host || decor === hostDecor ||
                                (windowContext !is android.app.Activity &&
                                    ((activityToken != null && params.token == activityToken) ||
                                        (hostDecor.windowToken != null && decor.applicationWindowToken == hostDecor.windowToken)))
                            owned && params.type in android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..
                                android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW &&
                                decor.isAttachedToWindow && decor.hasWindowFocus()
                        }
                    hostState = "host=${System.identityHashCode(host)} task=${host.taskId} " +
                        "attached=${hostDecor.isAttachedToWindow} focused=${hostDecor.hasWindowFocus()} " +
                        "token=${hostDecor.windowToken}"
                } catch (caught: Throwable) { failure = caught }
            }
            failure?.let { throw it }
            if (android.os.SystemClock.uptimeMillis() >= deadline) {
                throw AssertionError("No attached focused test-process window within focus wait: $hostState")
            }
            root?.let { return it }
            android.os.SystemClock.sleep(20)
        }
    }
    protected fun dismissIme(knownRoot: android.view.View? = null) {
        val root = knownRoot ?: run {
            ui.waitForIdle()
            focusedWindowRoot()
        }
        fun requestHide() {
            check(root.isAttachedToWindow && root.hasWindowFocus()) { "ime_window_changed" }
            val controller = androidx.core.view.ViewCompat.getWindowInsetsController(root)
            if (controller != null) controller.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            else {
                val focus = root.findFocus() ?: root
                checkNotNull(root.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java))
                    .hideSoftInputFromWindow(checkNotNull(focus.windowToken), 0)
            }
        }
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { requestHide() } catch (caught: Throwable) { failure = caught } }
        failure?.let { throw it }
        ui.waitUntil(5_000) {
            var hidden = false
            failure = null
            instrumentation.runOnMainSync {
                try {
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
                    hidden = root.isAttachedToWindow && insets != null &&
                        !insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
                    if (!hidden) requestHide()
                } catch (caught: Throwable) { failure = caught }
            }
            failure?.let { throw it }
            hidden
        }
        ui.waitForIdle()
    }
    /** Observe completed traversals, not Compose virtual time or transient IME visibility. */
    protected fun <T : Any> awaitStableWindowFrame(view: android.view.View, sample: () -> T?): T {
        val accepted = java.util.concurrent.atomic.AtomicReference<T?>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var recent: T? = null
        var previous: T? = null
        var stableFrames = 0
        var observedFrames = 0
        var lastWindowState = ""
        var lastFrame = Long.MIN_VALUE
        var frameNanos = Long.MIN_VALUE
        var active = true
        var observer: android.view.ViewTreeObserver? = null
        var choreographer: android.view.Choreographer? = null
        val frameCallback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!active) return
                frameNanos = frameTimeNanos
                view.postInvalidateOnAnimation()
                checkNotNull(choreographer).postFrameCallback(this)
            }
        }
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            if (active && frameNanos != Long.MIN_VALUE && frameNanos != lastFrame) {
                lastFrame = frameNanos
                try {
                    observedFrames++
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                    lastWindowState = "attached=${view.isAttachedToWindow} focused=${view.hasWindowFocus()} " +
                        "layoutRequested=${view.isLayoutRequested} token=${view.windowToken} " +
                        "imeVisible=${insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())} " +
                        "imeBottom=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom}"
                    val next = if (view.isAttachedToWindow && view.hasWindowFocus() && !view.isLayoutRequested) sample() else null
                    recent = next
                    stableFrames = if (next != null && next == previous) stableFrames + 1 else if (next != null) 1 else 0
                    previous = next
                    if (stableFrames >= 2) accepted.compareAndSet(null, next)
                } catch (caught: Throwable) { failure.set(caught) }
            }
            true
        }
        try {
            instrumentation.runOnMainSync {
                observer = view.viewTreeObserver
                choreographer = android.view.Choreographer.getInstance()
                checkNotNull(observer).addOnPreDrawListener(listener)
                checkNotNull(choreographer).postFrameCallback(frameCallback)
            }
            try {
                ui.waitUntil(5_000) { failure.get()?.let { throw it }; accepted.get() != null }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Window did not settle in two native frames: last=$recent frames=$stableFrames observedFrames=$observedFrames $lastWindowState", timeout)
            }
            return checkNotNull(accepted.get())
        } finally {
            instrumentation.runOnMainSync {
                active = false
                choreographer?.removeFrameCallback(frameCallback)
                val registered = observer
                if (registered?.isAlive == true) registered.removeOnPreDrawListener(listener)
                else view.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    protected fun awaitImeFrame(target: SemanticsNodeInteraction, visible: Boolean) {
        val view = (target.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
        awaitStableWindowFrame(view) {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
            val bottom = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom
            if (insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == visible &&
                bottom != null && (if (visible) bottom > 0 else bottom == 0)) bottom else null
        }
    }

    protected fun awaitDialogEnter(target: SemanticsNodeInteraction) {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            val current = target.fetchSemanticsNode()
            val signals = generateSequence(current) { it.parent }.mapNotNull {
                it.config.getOrNull(app.daiyu.android.ui.components.DialogEnterSettled)
            }.toList()
            assertTrue("Dialog entry signal missing from target parent chain", signals.isNotEmpty())
            signals.all { it }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    protected fun awaitDialogImeFrame(target: SemanticsNodeInteraction, visible: Boolean) {
        val view = (target.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
        val automation = instrumentation.uiAutomation
        val oldFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            awaitStableWindowFrame(view) {
                val shown = automation.windows.any { window ->
                    val bounds = android.graphics.Rect()
                    window.getBoundsInScreen(bounds)
                    window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && !bounds.isEmpty
                }
                shown.takeIf { it == visible }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
        }
    }

    protected fun reveal(tag: String, page: String = "模型设置", lazyKey: Any? = null): SemanticsNodeInteraction {
        dismissIme()
        val title = if (page == "模型设置" && ui.onAllNodesWithTag("page:主模型服务商").fetchSemanticsNodes().isNotEmpty())
            "主模型服务商" else page
        awaitUniqueTag("page:$title")
        val pageTag = "page:$title"
        val pageNode = ui.onNodeWithTag(pageTag).assert(hasScrollAction())
        if (lazyKey != null) {
            pageNode.performScrollToKey(lazyKey)
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
        }
        val matcher = hasTestTag(tag) and hasAnyAncestor(hasTestTag(pageTag))
        val target = ui.onNode(matcher)
        data class Frame(val viewport: Rect, val target: Rect?, val clipped: Rect?, val scroll: Float)
        fun rawBounds(node: androidx.compose.ui.semantics.SemanticsNode): Rect {
            val coordinates = node.layoutInfo.coordinates
            var root = coordinates
            while (true) root = root.parentLayoutCoordinates ?: break
            return root.localBoundingBoxOf(coordinates, clipBounds = false)
        }
        fun frame(): Frame {
            val container = pageNode.fetchSemanticsNode()
            val matches = ui.onAllNodes(matcher).fetchSemanticsNodes()
            assertTrue("Duplicate target $tag in $pageTag", matches.size <= 1)
            val found = matches.singleOrNull()?.takeIf { node ->
                generateSequence(node) { it.parent }.all { it.layoutInfo.isPlaced }
            }
            val bounds = container.boundsInRoot
            val bar = ui.onAllNodesWithTag("page-topbar:$title").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            val viewport = Rect(bounds.left, maxOf(bounds.top, bar?.bottom ?: bounds.top), bounds.right, bounds.bottom)
            assertTrue("Empty reachable viewport for $pageTag: $viewport", viewport.width > 0f && viewport.height > 0f)
            val axis = checkNotNull(container.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
            return Frame(viewport, found?.let(::rawBounds), found?.boundsInRoot, axis.value())
        }
        if (frame().target == null && pageNode.fetchSemanticsNode().config.getOrNull(SemanticsActions.ScrollToIndex) != null) {
            pageNode.performScrollToIndex(0)
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
        }
        val recent = java.util.ArrayDeque<Frame>(8)
        try {
            ui.waitUntil(timeoutMillis = 5_000) {
                val before = frame()
                if (recent.size == 8) recent.removeFirst()
                recent.addLast(before)
                val raw = before.target
                val clipped = before.clipped
                if (raw != null && clipped != null && clipped.width > 0f && clipped.height > 0f &&
                    before.viewport.contains(raw.center) && before.viewport.contains(clipped.center) && target.isDisplayed()) {
                    true
                } else {
                    val limit = before.viewport.height * 0.5f
                    val dy = if (raw == null) limit else (raw.center.y - before.viewport.center.y).coerceIn(-limit, limit)
                    assertTrue("Target not reachable by scrolling: $tag; $before", kotlin.math.abs(dy) >= 0.5f)
                    val axis = checkNotNull(pageNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
                    pageNode.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                        assertTrue("ScrollBy rejected for $tag", scroll(0f, if (axis.reverseScrolling) -dy else dy))
                    }
                    // ScrollBy accepts a queued coroutine; Espresso idle does not start that task.
                    ui.mainClock.advanceTimeByFrame()
                    ui.waitForIdle()
                    val after = frame()
                    assertTrue("Scroll made no progress for $tag: before=$before after=$after", after != before)
                    false
                }
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Reveal timed out: tag=$tag page=$pageTag frames=$recent", timeout)
        }
        return target.assertIsDisplayed()
    }
    /** Drives each accepted scroll before checking progress; the library loops omit that boundary. */
    protected fun SemanticsNodeInteraction.scrollToVisible(): SemanticsNodeInteraction {
        val current = fetchSemanticsNode()
        val parent = generateSequence(current.parent) { it.parent }
            .firstOrNull { hasScrollAction().matches(it) }
        assertNotNull("No scrollable ancestor for ${current.config}", parent)
        val parentId = checkNotNull(parent).id
        val container = ui.onNode(SemanticsMatcher("scroll ancestor $parentId") { it.id == parentId }, useUnmergedTree = true)
        scrollWithin(container, this)
        return this
    }

    protected fun SemanticsNodeInteraction.scrollToDescendant(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        assert(hasScrollAction())
        fun firstMatch(root: androidx.compose.ui.semantics.SemanticsNode): androidx.compose.ui.semantics.SemanticsNode? {
            if (!root.layoutInfo.isPlaced) return null
            if (matcher.matches(root)) return root
            return root.children.firstNotNullOfOrNull(::firstMatch)
        }
        fun matched() = fetchSemanticsNode().children.firstNotNullOfOrNull(::firstMatch)
        fun revealMatch(): Boolean {
            val found = matched() ?: return false
            ui.onNode(SemanticsMatcher("matched descendant ${found.id}") { it.id == found.id },
                useUnmergedTree = true).scrollToVisible()
            return true
        }
        fun advance() {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
        }
        advance()
        if (revealMatch()) return this
        val initial = fetchSemanticsNode()
        assertTrue("No node found that matches ${matcher.description} in non-lazy scroll container",
            initial.config.getOrNull(SemanticsActions.ScrollToIndex) != null)
        val x = initial.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        val y = initial.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        if ((x != null && x.value() != 0f) || (y != null && y.value() != 0f)) {
            performScrollToIndex(0)
            advance()
        }
        ui.waitUntil(timeoutMillis = 5_000) {
            if (revealMatch()) true else {
                val parent = fetchSemanticsNode()
                val horizontal = parent.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                val vertical = parent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                val before = Triple(parent.boundsInRoot, horizontal?.value?.invoke(), vertical?.value?.invoke())
                assertTrue("Target not found before end of scroll container: $before",
                    (horizontal != null && horizontal.value() < horizontal.maxValue()) ||
                        (vertical != null && vertical.value() < vertical.maxValue()))
                val viewport = parent.boundsInRoot
                assertTrue("Empty scroll viewport: $before", viewport.width > 0f && viewport.height > 0f)
                performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                    assertTrue("Scroll action rejected: $before", scroll(
                        if (horizontal != null) viewport.width else 0f,
                        if (vertical != null) viewport.height else 0f))
                }
                advance()
                val afterNode = fetchSemanticsNode()
                val after = Triple(afterNode.boundsInRoot,
                    afterNode.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke(),
                    afterNode.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke())
                assertTrue("Scroll made no progress: before=$before after=$after", after != before)
                false
            }
        }
        return this
    }

    private fun scrollWithin(container: SemanticsNodeInteraction, target: SemanticsNodeInteraction) {
        container.assert(hasScrollAction())
        data class ScrollFrame(val viewport: Rect, val target: Rect, val x: Float?, val y: Float?)
        fun frame(): ScrollFrame {
            val parent = container.fetchSemanticsNode()
            val targetNode = target.fetchSemanticsNode()
            val targetBounds = Rect(targetNode.positionInRoot,
                androidx.compose.ui.geometry.Size(targetNode.size.width.toFloat(), targetNode.size.height.toFloat()))
            val parentCoordinates = parent.layoutInfo.coordinates
            val bounds = parentCoordinates.boundsInParent().translate(
                parentCoordinates.parentLayoutCoordinates?.positionInRoot() ?: Offset.Zero)
            val page = parent.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.startsWith("page:") }
            val bar = page?.let { ui.onAllNodesWithTag("page-topbar:${it.removePrefix("page:")}")
                .fetchSemanticsNodes().singleOrNull()?.boundsInRoot }
            val viewport = Rect(bounds.left, maxOf(bounds.top, bar?.bottom ?: bounds.top), bounds.right, bounds.bottom)
            return ScrollFrame(viewport, targetBounds,
                parent.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke(),
                parent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke())
        }
        fun advance() {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
        }
        fun delta(start: Float, end: Float): Float =
            if (start > 0f && end > 0f) minOf(start, end)
            else if (start < 0f && end < 0f) maxOf(start, end) else 0f
        advance()
        ui.waitUntil(timeoutMillis = 5_000) {
            val before = frame()
            val viewport = before.viewport
            assertTrue("Empty scroll viewport: $before", viewport.width > 0f && viewport.height > 0f)
            val parent = container.fetchSemanticsNode()
            val horizontal = parent.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
            val vertical = parent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            var dx = 0f
            var dy = 0f
            val bounds = before.target
            if (horizontal != null) {
                dx = delta(bounds.left - viewport.left, bounds.right - viewport.right)
                if (horizontal.reverseScrolling) dx = -dx
                if (parent.layoutInfo.layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) dx = -dx
            }
            if (vertical != null) {
                dy = delta(bounds.top - viewport.top, bounds.bottom - viewport.bottom)
                if (vertical.reverseScrolling) dy = -dy
            }
            if (kotlin.math.abs(dx) < .5f && kotlin.math.abs(dy) < .5f) true else {
                container.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                    assertTrue("Scroll action rejected: $before", scroll(dx, dy))
                }
                advance()
                val after = frame()
                assertTrue("Scroll made no progress: before=$before after=$after", after != before)
                false
            }
        }
    }
    protected fun retainedSettingsEntry(id: String, page: String = "设置"): () -> Unit {
        val entry = app.daiyu.android.ui.screens.settings.SettingsCatalog.entry(id)
        val open = checkNotNull(node("page:$page").fetchSemanticsNode().config.getOrNull(
            app.daiyu.android.ui.screens.settings.RetainedSettingsEntryAction))
        return { open(entry) }
    }
    protected fun openRetainedSettingsEntry(id: String, expectedTag: String) {
        val open = retainedSettingsEntry(id)
        mainThread { open() }
        awaitUniqueTag(expectedTag)
    }
    protected fun editModel(label: String, text: String) {
        reveal("settings-field:$label").performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(androidx.compose.ui.text.AnnotatedString(text)))
        }
    }
    protected fun modelField(label: String) = reveal("settings-field:$label")
    protected fun inlineBodyControl(tag: String): SemanticsNodeInteraction = reveal(tag)
    protected fun openInlineBodyFields() {
        inlineBodyControl("model-body-fields")
        if (ui.onAllNodesWithTag("model-body-field-add").fetchSemanticsNodes().isEmpty()) {
            node("model-body-fields").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) {
                assertTrue(it())
            }
        }
        inlineBodyControl("model-body-field-add").assertExists()
    }
    protected fun addInlineBodyField(name: String, jsonValue: String, index: Int = 0) {
        openInlineBodyFields()
        inlineBodyControl("model-body-field-add").assertIsEnabled().performClick()
        inlineBodyControl("model-body-field-name:$index").performTextReplacement(name)
        inlineBodyControl("model-body-field-value:$index").performTextReplacement(jsonValue)
    }
    protected fun attemptBlockedInlineBodySave() {
        val button = reveal("model-save").assertIsEnabled()
        val submit = button.fetchSemanticsNode()
            .config[app.daiyu.android.ui.screens.settings.RetainedModelSaveAction]
        button.performClick()
        mainThread { submit() }
        ui.waitForIdle()
    }
    protected fun SemanticsNodeInteraction.assertEditableText(text: String): SemanticsNodeInteraction {
        ui.waitUntil(timeoutMillis = 5_000) {
            fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text == text
        }
        return assert(SemanticsMatcher("EditableText.text exactly equals <$text>") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
        })
    }
    protected fun assertModelSecret(expected: String) {
        val field = modelField("API Key")
        val hidden = field.fetchSemanticsNode().config.contains(SemanticsProperties.Password)
        if (hidden) reveal("settings-secret-visibility:API Key").performClick()
        field.assertEditableText(expected)
        if (hidden) {
            reveal("settings-secret-visibility:API Key").performClick()
            field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        }
    }
    protected fun saveModel() {
        reveal("model-save", lazyKey = "model_save").performClick()
        awaitControl("model settings completion") {
            !(instrumentation.targetContext.applicationContext as ChatApplication).controller.dataBusy
        }
        ui.waitForIdle()
    }
    protected fun openProviderManagement() {
        openSettings()
        reveal("settings-row:模型提供商", "设置").performClick()
        awaitUniqueTag("provider-management")
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("provider-add") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        reveal("provider-open-primary", "模型提供商").assertIsDisplayed().assertIsEnabled()
    }
    protected fun selectProviderTab(title: String) {
        val tag = "settings-radio:$title"
        val tab = reveal(tag).assertIsEnabled()
        if (tab.fetchSemanticsNode().config[SemanticsProperties.Selected] != true) {
            tab.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        }
        reveal(tag).assertIsSelected()
    }
    protected fun chooseProviderProtocol(label: String) {
        reveal("provider-endpoint-select").performClick()
        node("settings-radio:$label").assertIsEnabled().performClick()
    }
    protected fun assertProviderProtocol(label: String) {
        reveal("provider-endpoint-select").performClick()
        node("settings-radio:$label").assertIsSelected().performClick()
    }
    protected fun openModels() {
        openProviderManagement()
        reveal("provider-open-primary", "模型提供商").performClick()
        awaitModelEditor()
    }
    protected fun awaitModelEditor() {
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("page:主模型服务商").fetchSemanticsNodes().size == 1 ||
                ui.onAllNodesWithTag("page:模型设置").fetchSemanticsNodes().size == 1
        }
        reveal("settings-field:Base URL")
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("settings-field:Base URL").fetchSemanticsNodes()
                .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
        }
    }
    protected fun openSettings() {
        dismissIme()
        ui.onNode(hasContentDescription("打开侧边栏") and hasClickAction())
            .assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        val settings = ui.onNode(hasContentDescription(
            instrumentation.targetContext.getString(R.string.route_settings)) and hasClickAction())
        ui.waitUntil(timeoutMillis = 5_000) { settings.isDisplayed() }
        settings.assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithTag("page:设置").fetchSemanticsNodes().size == 1 }
    }
    protected fun openVoiceConversationPanel() {
        if (voicePreferenceSnapshot == null) {
            val preferences = instrumentation.targetContext.getSharedPreferences("chat-voice-entry", 0)
            voicePreferenceSnapshot = preferences.contains("mode") to preferences.getString("mode", null)
        }
        ui.onNodeWithContentDescription("语音输入").assertIsEnabled().performTouchInput { longClick() }
        node("chat-menu:VOICE_CHAT").assertIsEnabled().performClick()
        node("voice-universal-control").assertDoesNotExist()
        node("chat-menu:VOICE_CHAT").assertIsSelected()
        androidx.test.espresso.Espresso.pressBack()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("chat-menu:VOICE_CHAT").fetchSemanticsNodes().isEmpty()
        }
        ui.onNodeWithContentDescription("语音输入").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        awaitUniqueTag("voice-universal-control")
    }
    protected fun menu(label: String) {
        ui.onNodeWithContentDescription("更多").performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.onNodeWithText(label).scrollToVisible().performClick()
    }
    // sendText posts its receipt separately from runner/model events. Waiting for active or
    // Session.entered alone does not guarantee Activity consumption + acknowledgement.
    // Null also covers synchronous queue rejection (which intentionally has no callback).
    // Requires the retained result API introduced in f63a5f9.
    protected fun awaitTextSendResult(activity: MainActivity) {
        val controller = (instrumentation.targetContext.applicationContext as ChatApplication).controller
        ui.waitUntil(timeoutMillis = 5_000) {
            var complete = false
            instrumentation.runOnMainSync {
                complete = invokeHost(activity, "getPendingTextSend") == null && controller.textSendState() == null
            }
            complete
        }
        ui.waitForIdle()
    }
    protected fun setDraft(text: String) { node("chat-draft").performTextReplacement(text) }
    protected fun assertDraft(text: String) { node("chat-draft").assertEditableText(text) }
    protected fun setSelection(start: Int, end: Int, relativeToOriginalText: Boolean = false) {
        node("chat-draft").performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(start, end, relativeToOriginalText)) }
        ui.waitForIdle()
        assertSelection(start, end)
    }
    protected fun assertSelection(start: Int, end: Int) {
        val range = node("chat-draft").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals("selection expected=($start,$end) actual=(${range.start},${range.end})", start, range.start)
        assertEquals("selection expected=($start,$end) actual=(${range.start},${range.end})", end, range.end)
    }
    // Retain host capabilities or app-owned semantics, not ClickableNode wrappers.
    // Capture before navigation; never refetch a replacement callback after a visit changes.
    protected fun retainedClick(activity: MainActivity, tag: String): () -> Unit {
        if (tag == "model-save") reveal(tag) else node(tag).assertExists()
        return when (tag) {
            "platform-action-confirm" -> {
                val action = checkNotNull(node(tag).fetchSemanticsNode()
                    .config[SemanticsActions.OnClick].action);
                { assertTrue(action()) }
            }
            "dialog-confirm" -> {
                val prompt = invokeHost(activity, "getPrompt")!!
                when (prompt.javaClass.simpleName) {
                    "InputPrompt" -> {
                        val value = node("prompt-input").fetchSemanticsNode()
                            .config[SemanticsProperties.EditableText].text
                        val action: () -> Unit = { invokeHost(activity, "confirmInputPrompt", prompt, value); Unit }
                        action
                    }
                    "ConfirmPrompt" -> {
                        val action: () -> Unit = { invokeHost(activity, "confirmPrompt", prompt); Unit }
                        action
                    }
                    else -> error("Unsupported prompt: ${prompt.javaClass.name}")
                }
            }
            "model-save" -> checkNotNull(node(tag).fetchSemanticsNode()
                .config.getOrNull(app.daiyu.android.ui.screens.settings.RetainedModelSaveAction))
            "chat-control" -> retainedControls(activity).onPrimaryTap
            else -> {
                val title = tag.removePrefix("settings-row:")
                val entry = app.daiyu.android.ui.screens.settings.SettingsCatalog.entries.single { it.title == title }
                retainedSettingsEntry(entry.id)
            }
        }
    }
    protected fun retainedControls(activity: MainActivity): app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions {
        fun capture() = invokeHost(activity, "chatActions") as
            app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return capture()
        var controls: app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { controls = capture() }
            catch (caught: Throwable) { failure = caught }
        }
        failure?.let { throw it }
        return checkNotNull(controls)
    }
    protected fun invokeHost(activity: MainActivity, name: String, vararg arguments: Any): Any? {
        val method = MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
        try { return method.apply { isAccessible = true }.invoke(activity, *arguments) }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
    }
    protected fun backToChat(activity: MainActivity) {
        dismissIme()
        repeat(8) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            var chatExposed = false
            mainThread {
                assertFalse(activity.isFinishing); assertFalse(activity.isDestroyed)
                chatExposed = invokeHost(activity, "getRetainedPage") == "chat" &&
                    invokeHost(activity, "getSidebarVisible") == false
            }
            if (chatExposed &&
                ui.onAllNodesWithTag("chat-draft").fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1) {
                node("chat-draft").assertIsDisplayed()
                return
            }
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        }
        mainThread {
            assertEquals("chat", invokeHost(activity, "getRetainedPage"))
            assertEquals(false, invokeHost(activity, "getSidebarVisible"))
        }
        awaitUniqueTag("chat-draft")
    }
    protected fun backToPage(activity: MainActivity, expectedTag: String) {
        dismissIme()
        ui.waitForIdle()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
        mainThread { assertFalse(activity.isFinishing); assertFalse(activity.isDestroyed) }
        awaitUniqueTag(expectedTag) {
            "retainedPage=${invokeHost(activity, "getRetainedPage")} sidebarVisible=${invokeHost(activity, "getSidebarVisible")}"
        }
        node(expectedTag).assertIsDisplayed()
    }
    /** Real framework pause/resume, retaining this Activity without onStop or recreation. */
    protected fun realPauseResume(original: MainActivity, beforePause: () -> Unit = {}, atPause: () -> Unit = {}) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val debugCover = android.content.ComponentName(original.packageName, "app.daiyu.android.LifecycleCoverActivity")
        val debugCoverPresent = try { original.packageManager.getActivityInfo(debugCover, 0); true }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        if (!debugCoverPresent) {
            releasePauseResume(original, beforePause, atPause)
            return
        }
        val app = original.application
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var cover: android.app.Activity? = null
        var paused = false
        var resumed = false
        var stopped = false
        fun capture(action: () -> Unit) {
            try { action() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
        }
        val before = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) capture(beforePause)
        }
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) {
                if (activity.componentName.className == "app.daiyu.android.LifecycleCoverActivity") cover = activity
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (activity === original) {
                    paused = true
                    capture { assertFalse(stopped); atPause() }
                }
            }
            override fun onActivityResumed(activity: android.app.Activity) { if (activity === original) resumed = true }
            override fun onActivityStopped(activity: android.app.Activity) { if (activity === original) stopped = true }
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        mainThread {
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, original.lifecycle.currentState)
            app.registerActivityLifecycleCallbacks(callbacks)
            original.lifecycle.addObserver(before)
        }
        var primary: Throwable? = null
        try {
            val started = instrumentation.startActivitySync(Intent().setComponent(android.content.ComponentName(
                instrumentation.targetContext.packageName, "app.daiyu.android.LifecycleCoverActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            mainThread { assertSame(cover, started); assertEquals(original.taskId, started.taskId) }
            awaitControl("original paused under transparent cover") { paused && !stopped }
            failure.get()?.let { throw it }
        } catch (problem: Throwable) {
            primary = problem
            throw problem
        } finally {
            try {
                mainThread { cover?.let { if (!it.isDestroyed) it.finish() } }
                awaitControl("same Activity fully resumed after cover") {
                    (cover == null || cover!!.isDestroyed) && (!paused || resumed) &&
                        !original.isDestroyed && !original.isFinishing &&
                        original.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                }
                mainThread { assertFalse("pause-only cover must not stop original", stopped) }
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            } finally {
                mainThread { original.lifecycle.removeObserver(before); app.unregisterActivityLifecycleCallbacks(callbacks) }
            }
        }
        awaitHostWindowFocus(original)
        ui.waitForIdle()
    }

    /** Release keeps its APK free of test components; this cover belongs only to the test APK. */
    private fun releasePauseResume(original: MainActivity, beforePause: () -> Unit, atPause: () -> Unit) {
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var finishCover: android.os.ResultReceiver? = null
        var coverReady = false
        var coverDestroyed = false
        var coverTask = -1
        var coverPid = -1
        var coverUid = -1
        var cleanupRequested = false
        var paused = false
        var resumed = false
        var stopped = false
        fun capture(action: () -> Unit) {
            try { action() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
        }
        val before = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) capture(beforePause)
        }
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: android.app.Activity) {
                if (activity === original) { paused = true; capture { assertFalse(stopped); atPause() } }
            }
            override fun onActivityResumed(activity: android.app.Activity) { if (activity === original) resumed = true }
            override fun onActivityStopped(activity: android.app.Activity) { if (activity === original) stopped = true }
            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        val reply = object : android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
            @Suppress("DEPRECATION")
            override fun onReceiveResult(code: Int, data: Bundle?) {
                when (code) {
                    0 -> {
                        finishCover = data?.getParcelable("finish")
                        coverTask = data?.getInt("task", -1) ?: -1
                        coverPid = data?.getInt("pid", -1) ?: -1
                        coverUid = data?.getInt("uid", -1) ?: -1
                        if (cleanupRequested) finishCover?.send(0, null)
                    }
                    1 -> coverReady = true
                    2 -> coverDestroyed = true
                }
            }
        }
        mainThread {
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, original.lifecycle.currentState)
            original.application.registerActivityLifecycleCallbacks(callbacks)
            original.lifecycle.addObserver(before)
        }
        var primary: Throwable? = null
        var launched = false
        try {
            mainThread {
                val parcel = android.os.Parcel.obtain()
                val remoteReply = try {
                    reply.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    android.os.ResultReceiver.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
                original.startActivity(Intent().setComponent(android.content.ComponentName(
                    instrumentation.context.packageName, "app.daiyu.android.ReleaseLifecycleCoverActivity"))
                    .putExtra("reply", remoteReply))
                launched = true
            }
            awaitControl("original paused under test APK cover") {
                paused && !stopped && !resumed && coverReady && !coverDestroyed && finishCover != null &&
                    original.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.STARTED
            }
            mainThread {
                assertEquals(android.os.ResultReceiver::class.java, checkNotNull(finishCover).javaClass)
                assertEquals(original.taskId, coverTask)
                assertTrue("cover must use another process", coverPid > 0 && coverPid != android.os.Process.myPid())
                val installedTestUid = instrumentation.context.packageManager
                    .getApplicationInfo(instrumentation.context.packageName, 0).uid
                assertEquals("cover UID must match the installed test package", installedTestUid, coverUid)
                assertTrue("cover must use the test APK UID", coverUid != android.os.Process.myUid())
            }
            failure.get()?.let { throw it }
        } catch (problem: Throwable) {
            primary = problem
            throw problem
        } finally {
            try {
                mainThread { cleanupRequested = true }
                if (launched) {
                    awaitControl("test APK cover finish channel") { finishCover != null || coverDestroyed }
                    mainThread { finishCover?.send(0, null) }
                    awaitControl("same release Activity resumed after test cover") {
                        coverDestroyed && (!paused || resumed) && !original.isDestroyed && !original.isFinishing &&
                            original.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                    }
                }
                mainThread { assertFalse("pause-only cover must not stop original", stopped) }
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            } finally {
                mainThread { original.lifecycle.removeObserver(before); original.application.unregisterActivityLifecycleCallbacks(callbacks) }
            }
        }
        awaitHostWindowFocus(original)
        ui.waitForIdle()
    }

    protected fun recreateActivity(activity: MainActivity, afterImeHidden: () -> Unit = {},
        afterDestroyed: () -> Unit = {}): MainActivity {
        dismissIme()
        afterImeHidden()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            mainThread { androidx.core.app.ActivityCompat.recreate(activity) }
            awaitControl("original Activity destroyed before recreated UI waits") { activity.isDestroyed }
            afterDestroyed()
            val replacement = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? MainActivity
            assertNotNull("framework recreation must resume a new Activity", replacement)
            val created = checkNotNull(replacement)
            try {
                ui.waitForIdle()
                assertTrue(activity.isDestroyed)
                currentHost = created
                awaitHostWindowFocus(created)
                return created
            } catch (failure: Throwable) {
                try {
                    mainThread { created.finish() }
                    awaitControl("failed-recreation Activity destroyed") { created.isDestroyed }
                } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    protected fun finishExistingMainActivities() {
        val existing = linkedSetOf<MainActivity>()
        mainThread {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            androidx.test.runner.lifecycle.Stage.values().filter { it != androidx.test.runner.lifecycle.Stage.DESTROYED }
                .forEach { stage -> existing.addAll(monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>()) }
            existing.forEach { if (!it.isDestroyed) it.finish() }
        }
        awaitControl("previous MainActivities destroyed before fixture replacement") { existing.all { it.isDestroyed } }
    }

    protected fun startMain(intent: Intent = Intent(instrumentation.targetContext, MainActivity::class.java)): MainActivity {
        val app = instrumentation.targetContext.applicationContext as ChatApplication
        val created = mutableListOf<MainActivity>()
        var cleanupRequested = false
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) {
                if (activity is MainActivity) {
                    created.add(activity)
                    if (cleanupRequested) activity.finish()
                }
            }
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        mainThread { app.registerActivityLifecycleCallbacks(callbacks) }
        try {
            instrumentation.runOnMainSync { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
            awaitControl("MainActivity created by explicit launch") { created.isNotEmpty() }
            val activity = created.single()
            currentHost = activity
            awaitHostWindowFocus(activity)
            return activity
        } catch (failure: Throwable) {
            try {
                mainThread { cleanupRequested = true; created.forEach { if (!it.isDestroyed) it.finish() } }
                awaitControl("failed-start Activities destroyed") { created.all { it.isDestroyed } }
            } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        } finally {
            mainThread { app.unregisterActivityLifecycleCallbacks(callbacks) }
        }
    }
}

/** Shared real-controller fixture; tests still assert database bytes and model-call counts. */
@Suppress("DEPRECATION")
abstract class WiredActivityTestCase : ComposeActivityTestCase() {
    private var fixtureRef: RunControlFixture? = null
    internal var fixture: RunControlFixture
        get() = checkNotNull(fixtureRef)
        set(value) { fixtureRef = value }
    private var original: ChatController? = null
    private var activityRef: MainActivity? = null
    protected var activity: MainActivity
        get() = checkNotNull(activityRef)
        set(value) { activityRef = value }
    internal open fun createFixture() = RunControlFixture(instrumentation.targetContext)
    override fun setUp() {
        super.setUp()
        finishExistingMainActivities()
        fixture = createFixture()
        awaitControl("controller ready") { fixture.controller.ready }
        mainThread {
            val app = instrumentation.targetContext.applicationContext as ChatApplication
            original = app.controller; app.controller = fixture.controller
        }
        activity = startMain()
    }
    override fun cleanupAfterLegacy() { cleanupFixture() }
    override fun tearDown() {
        try { cleanupFixture() } finally { super.tearDown() }
    }
    protected open fun cleanupFixture() {
        if (activityRef == null && fixtureRef == null && original == null) return
        val closingActivity = activityRef
        mainThread { closingActivity?.finish() }
        awaitControl("fixture Activity destroyed") { closingActivity?.isDestroyed != false }
        mainThread {
            original?.let {
                (instrumentation.targetContext.applicationContext as ChatApplication).controller = it
            }
        }
        fixtureRef?.close()
        activityRef = null
        fixtureRef = null
        original = null
    }
}
