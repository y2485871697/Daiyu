package app.daiyu.android.pet

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.viewinterop.AndroidView
import app.daiyu.android.R
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.domain.pet.*

class PetPresentationTest : WiredActivityTestCase() {
    private var controller: PetController? = null
    private var root: FrameLayout? = null
    private var overlay: PetOverlayHost? = null
    private val visibleFrames = mutableListOf<Rect>()
    private var completion: ((PetModelResult) -> Unit)? = null

    private fun newController(x: Int, y: Int, global: Boolean = false): PetController = PetController(
        InMemoryPetStore(PetState(settings = PetSettings(enabled = true, globalVisible = global, x = x, y = y,
            model = PetModelSelection("maid", "provider", "model")))),
        PetModelPort { _, callback -> completion = callback; PetCancel {} },
        PetScheduler { _, _ -> PetCancel {} }, PetClock { 0 })

    private fun sprite(): View = checkNotNull(root).findViewById(R.id.pet_sprite)
    private fun bounds(): Rect {
        val view = sprite()
        val point = IntArray(2); view.getLocationOnScreen(point)
        return Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }
    private fun recordFrames(frame: FrameLayout) {
        frame.viewTreeObserver.addOnDrawListener {
            val panel = frame.getChildAt(0)
            if (frame.isShown && frame.alpha > 0f && panel != null && panel.isShown && panel.alpha > 0f && sprite().width > 0)
                visibleFrames.add(bounds())
        }
    }
    private fun mount(x: Int = 70, y: Int = 120) {
        mainThread {
            controller = newController(x, y)
            val frame = FrameLayout(activity).apply { clipChildren = false; clipToPadding = false }
            frame.addView(PetViewFactory.createPet(activity, checkNotNull(controller), WhaleMaidResources(activity)),
                FrameLayout.LayoutParams(-2, -2))
            root = frame; recordFrames(frame)
            activity.setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { frame }) }
        }
    }
    private fun speak(text: String) {
        mainThread {
            checkNotNull(controller).feed(PetFood.GRAIN)
            checkNotNull(completion)(PetModelResult.Completed("{\"mood\":\"happy\",\"speech\":\"$text\"}"))
        }
        ui.onNodeWithText(text).assertExists()
    }
    @OptIn(ExperimentalComposeUiApi::class)
    private fun overlaySpeechReady(text: String?): Boolean {
        val panel = checkNotNull(root).getChildAt(0) as ViewGroup
        val bubble = (0 until panel.childCount).map(panel::getChildAt).filterIsInstance<PetSpeechBubbleView>().single()
        if (text == null) return bubble.visibility == View.GONE
        if (!bubble.isShown || !bubble.isAttachedToWindow || bubble.isLayoutRequested ||
            bubble.width <= 0 || bubble.height <= 0) return false
        fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
            return null
        }
        fun hasText(node: SemanticsNode): Boolean {
            val found = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            return (found && node.layoutInfo.isPlaced && node.layoutInfo.coordinates.isAttached &&
                node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f) || node.children.any(::hasText)
        }
        return findRoot(bubble)?.semanticsOwner?.unmergedRootSemanticsNode?.let(::hasText) == true
    }
    private fun stable(expectedSpeech: String? = null): Rect {
        val frame = checkNotNull(root)
        if (overlay == null) return awaitStableWindowFrame(frame) {
            if (frame.getChildAt(0).alpha == 0f || !sprite().isShown || sprite().isLayoutRequested || sprite().width == 0) null else bounds()
        }
        // Application overlays deliberately never take keyboard/window focus.
        ui.mainClock.advanceTimeByFrame()
        data class Frame(val sprite: Rect, val rootX: Int, val rootY: Int,
            val width: Int, val height: Int, val spriteLeft: Int, val spriteTop: Int)
        val accepted = java.util.concurrent.atomic.AtomicReference<Frame?>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val completed = java.util.concurrent.CountDownLatch(1)
        val frameSignals = java.util.concurrent.Semaphore(0)
        val lastFrameState = java.util.concurrent.atomic.AtomicReference("no native frame")
        var previous: Frame? = null
        var frameNanos = Long.MIN_VALUE
        var sampledNanos = Long.MIN_VALUE
        var active = true
        lateinit var choreographer: android.view.Choreographer
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (!active) return
                frameNanos = time
                val screen = IntArray(2); frame.getLocationOnScreen(screen)
                val local = IntArray(2); frame.getLocationInWindow(local)
                val params = frame.layoutParams as android.view.WindowManager.LayoutParams
                lastFrameState.set("alpha=${frame.alpha} attached=${frame.isAttachedToWindow} " +
                    "layout=${frame.isLayoutRequested} size=${frame.width}x${frame.height} " +
                    "requested=${params.x},${params.y} actual=${screen[0] - local[0]},${screen[1] - local[1]} " +
                    "sprite=${bounds()} speechReady=${overlaySpeechReady(expectedSpeech)} previous=$previous")
                frameSignals.release()
                frame.postInvalidateOnAnimation()
                choreographer.postFrameCallback(this)
            }
        }
        val listener = android.view.ViewTreeObserver.OnDrawListener {
            if (active && frameNanos != Long.MIN_VALUE && sampledNanos != frameNanos) {
                sampledNanos = frameNanos
                try {
                    val panel = frame.getChildAt(0)
                    val ready = frame.alpha == 1f && frame.isAttachedToWindow && !frame.isLayoutRequested &&
                        panel?.alpha == 1f && !panel.isLayoutRequested && !sprite().isLayoutRequested && sprite().width > 0 && overlaySpeechReady(expectedSpeech)
                    if (ready) {
                        val origin = IntArray(2); frame.getLocationOnScreen(origin)
                        val next = Frame(bounds(), origin[0], origin[1], frame.width, frame.height, sprite().left, sprite().top)
                        if (next == previous && accepted.compareAndSet(null, next)) {
                            active = false
                            choreographer.removeFrameCallback(callback)
                            completed.countDown()
                            frameSignals.release()
                        }
                        previous = next
                    } else previous = null
                } catch (caught: Throwable) {
                    failure.set(caught)
                    active = false
                    choreographer.removeFrameCallback(callback)
                    completed.countDown()
                    frameSignals.release()
                }
            }
        }
        instrumentation.runOnMainSync {
            choreographer = android.view.Choreographer.getInstance()
            frame.viewTreeObserver.addOnDrawListener(listener)
            choreographer.postFrameCallback(callback)
        }
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (completed.count != 0L) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0 || !frameSignals.tryAcquire(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) break
                if (completed.count != 0L) ui.mainClock.advanceTimeByFrame()
            }
            assertTrue("Overlay did not produce two equal ready draw frames: ${lastFrameState.get()}",
                completed.count == 0L)
            failure.get()?.let { throw it }
            return checkNotNull(accepted.get()).sprite
        } finally {
            instrumentation.runOnMainSync {
                active = false
                choreographer.removeFrameCallback(callback)
                frame.viewTreeObserver.removeOnDrawListener(listener)
            }
        }
    }
    override fun cleanupFixture() {
        instrumentation.runOnMainSync {
            overlay?.close(); overlay = null
            root?.removeAllViews(); root = null
            controller?.close(); controller = null
        }
        super.cleanupFixture()
    }

    fun testFirstVisibleDrawAndRepeatedEnableUseSavedSpritePosition() {
        mount()
        val expected = stable()
        repeat(3) {
            mainThread { checkNotNull(controller).setEnabled(false) }
            mainThread { checkNotNull(controller).setEnabled(true) }
            assertEquals(expected, stable())
        }
        mainThread {
            assertTrue(visibleFrames.isNotEmpty())
            assertTrue("First/re-enabled frames: $visibleFrames", visibleFrames.all { it == expected })
            val origin = IntArray(2); checkNotNull(root).getLocationOnScreen(origin)
            assertEquals(origin[0] + 70, expected.left)
            assertEquals(origin[1] + 120, expected.top)
        }
    }

    fun testDefaultFirstDrawStartsAtOriginalRightInsetAndHeight() {
        mount(-1, -1)
        val expected = stable()
        mainThread {
            val frame = checkNotNull(root)
            val origin = IntArray(2); frame.getLocationOnScreen(origin)
            val margin = (16 * frame.resources.displayMetrics.density).toInt()
            assertEquals(origin[0] + (frame.width - sprite().width - margin).coerceAtLeast(0), expected.left)
            assertEquals(origin[1] + (frame.height * .62f).toInt().coerceAtMost(frame.height - sprite().height), expected.top)
            assertTrue(visibleFrames.isNotEmpty())
            assertTrue(visibleFrames.all { it == expected })
        }
    }

    fun testDismissDuringDragDoesNotChangeDraggedSpriteOrSavedAnchor() {
        mount()
        speak("主人，米饭已经吃完啦，我会继续陪着你。")
        val before = stable()
        fun touch(action: Int, x: Float, y: Float) {
            val time = android.os.SystemClock.uptimeMillis()
            val event = android.view.MotionEvent.obtain(time, time, action, x, y, 0)
            try { sprite().dispatchTouchEvent(event) } finally { event.recycle() }
        }
        mainThread {
            touch(android.view.MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(android.view.MotionEvent.ACTION_MOVE, 40f, 45f)
        }
        val dragged = Rect(before).apply { offset(20, 25) }
        assertEquals(dragged, stable())
        mainThread { checkNotNull(controller).dismissSpeech() }
        assertEquals(dragged, stable())
        mainThread { touch(android.view.MotionEvent.ACTION_UP, 40f, 45f) }
        assertEquals(dragged, stable())
        mainThread {
            assertEquals(90, checkNotNull(controller).snapshot().state.settings.x)
            assertEquals(145, checkNotNull(controller).snapshot().state.settings.y)
        }
    }

    fun testShortAndWrappedSpeechUseOriginalBubbleAndDoNotMoveSprite() {
        mount()
        val expected = stable()
        speak("嗯。")
        val shortWidth = ui.onNodeWithText("嗯。").fetchSemanticsNode().boundsInRoot.width
        assertEquals(expected, stable())
        mainThread { checkNotNull(controller).dismissSpeech() }
        assertEquals(expected, stable())
        val text = "主人，米饭已经吃完啦，我会继续陪着你。"
        speak(text)
        val speech = ui.onNodeWithText(text)
        assertTrue(speech.fetchSemanticsNode().boundsInRoot.width > shortWidth)
        val image = speech.captureToImage().toPixelMap()
        var blue = 0; var white = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = image[x, y]
            if (color.blue > .85f && color.red in 0.19f..0.28f && color.green in 0.46f..0.56f) blue++
            if (color.red > .98f && color.green > .98f && color.blue > .98f && color.alpha > .98f) white++
        }
        assertTrue("Original blue border missing", blue > image.width)
        assertTrue("Original white fill missing", white > image.width)
        assertEquals(expected, stable())
        speech.performClick()
        ui.onNodeWithText(text).assertDoesNotExist()
        assertEquals(expected, stable())
    }

    fun testBubbleMayLeaveTopAndLeftWithoutMovingOrResettingSprite() {
        mount(0, 0)
        val expected = stable()
        speak("主人，米饭已经吃完啦，我会继续陪着你。")
        assertEquals(expected, stable())
        mainThread {
            val panel = checkNotNull(root).getChildAt(0)
            assertTrue(panel.x < 0f)
            assertTrue(panel.y < 0f)
            assertEquals(0, checkNotNull(controller).snapshot().state.settings.x)
            assertEquals(0, checkNotNull(controller).snapshot().state.settings.y)
        }
        mainThread { checkNotNull(controller).dismissSpeech() }
        assertEquals(expected, stable())
    }

    private fun mountOverlay() {
        val packageName = instrumentation.targetContext.packageName
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        ).use { it.readBytes() }
        instrumentation.runOnMainSync {
            controller = newController(70, 120, true)
            val host = PetOverlayHost(activity, checkNotNull(controller), WhaleMaidResources(activity), {}, onState = { _, error ->
                assertNull(error)
            })
            overlay = host
            host.sync(checkNotNull(controller).snapshot())
            val field = PetOverlayHost::class.java.getDeclaredField("owner").apply { isAccessible = true }
            val frame = (field.get(host) as PetOverlayWindowOwner<*>).window as FrameLayout
            root = frame; recordFrames(frame)
        }
    }

    fun testRealOverlayFirstDrawSpeechGrowthAndDismissalPreserveAnchor() {
        mountOverlay()
        val expected = stable()
        val text = "主人，米饭已经吃完啦，我会继续陪着你。"
        repeat(3) {
            instrumentation.runOnMainSync {
                completion = null
                checkNotNull(controller).feed(PetFood.GRAIN)
                checkNotNull(completion)(PetModelResult.Completed("{\"mood\":\"happy\",\"speech\":\"$text\"}"))
            }
            assertEquals(expected, stable(text))
            instrumentation.runOnMainSync { checkNotNull(controller).dismissSpeech() }
            assertEquals(expected, stable())
        }
        instrumentation.runOnMainSync {
            assertTrue(visibleFrames.isNotEmpty())
            assertTrue("Overlay visible frames moved: $visibleFrames", visibleFrames.all { it == expected })
        }
    }

    private fun overlayTouch(action: Int, screenX: Float, screenY: Float) {
        val frame = checkNotNull(root)
        val origin = IntArray(2); frame.getLocationOnScreen(origin)
        val time = android.os.SystemClock.uptimeMillis()
        val event = android.view.MotionEvent.obtain(time, time, action, screenX, screenY, 0)
        event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
        try { assertTrue(frame.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    fun testRealOverlayDragAtNonzeroWindowOriginSavesAndRestoresPosition() {
        mountOverlay()
        val before = stable()
        val downX = before.right - 4f
        val downY = before.bottom - 4f
        instrumentation.runOnMainSync {
            overlayTouch(android.view.MotionEvent.ACTION_DOWN, downX, downY)
            overlayTouch(android.view.MotionEvent.ACTION_MOVE, downX - 14f, downY - 16f)
            overlayTouch(android.view.MotionEvent.ACTION_MOVE, downX - 28f, downY - 34f)
            assertEquals(-28f, checkNotNull(root).translationX)
            assertEquals(-34f, checkNotNull(root).translationY)
            overlayTouch(android.view.MotionEvent.ACTION_UP, downX - 28f, downY - 34f)
            val settings = checkNotNull(controller).snapshot().state.settings
            assertEquals(42, settings.x)
            assertEquals(86, settings.y)
        }
        val expected = Rect(before).apply { offset(-28, -34) }
        assertEquals(expected, stable())
        instrumentation.runOnMainSync {
            val host = checkNotNull(overlay)
            val current = checkNotNull(controller)
            current.updateSettings(current.snapshot().state.settings.copy(globalVisible = false))
            host.sync(current.snapshot())
            current.updateSettings(current.snapshot().state.settings.copy(globalVisible = true))
            host.sync(current.snapshot())
            val field = PetOverlayHost::class.java.getDeclaredField("owner").apply { isAccessible = true }
            root = (field.get(host) as PetOverlayWindowOwner<*>).window as FrameLayout
        }
        assertEquals(expected, stable())
    }

    fun testRealOverlayCancelRestoresPositionAndSmallMovementStillClicks() {
        mountOverlay()
        val before = stable()
        val downX = before.right - 4f
        val downY = before.bottom - 4f
        instrumentation.runOnMainSync {
            var clicks = 0
            sprite().setOnClickListener { clicks++ }
            overlayTouch(android.view.MotionEvent.ACTION_DOWN, downX, downY)
            overlayTouch(android.view.MotionEvent.ACTION_MOVE, downX - 14f, downY - 16f)
            overlayTouch(android.view.MotionEvent.ACTION_MOVE, downX - 28f, downY - 34f)
            overlayTouch(android.view.MotionEvent.ACTION_CANCEL, downX - 28f, downY - 34f)
            assertEquals(0f, checkNotNull(root).translationX)
            assertEquals(0f, checkNotNull(root).translationY)
            assertEquals(70, checkNotNull(controller).snapshot().state.settings.x)
            assertEquals(120, checkNotNull(controller).snapshot().state.settings.y)
            assertEquals(0, clicks)
            overlayTouch(android.view.MotionEvent.ACTION_DOWN, downX, downY)
            overlayTouch(android.view.MotionEvent.ACTION_MOVE, downX - 2f, downY - 2f)
            overlayTouch(android.view.MotionEvent.ACTION_UP, downX - 2f, downY - 2f)
            assertEquals(1, clicks)
        }
        assertEquals(before, stable())
    }

}
