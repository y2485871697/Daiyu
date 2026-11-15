package app.daiyu.android.pet

import android.graphics.Rect
import android.view.View
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
    private fun stable(): Rect {
        val frame = checkNotNull(root)
        if (overlay == null) return awaitStableWindowFrame(frame) {
            if (frame.getChildAt(0).alpha == 0f || !sprite().isShown || sprite().isLayoutRequested || sprite().width == 0) null else bounds()
        }
        // Application overlays deliberately never take keyboard/window focus.
        data class Frame(val sprite: Rect, val rootX: Int, val rootY: Int,
            val width: Int, val height: Int, val spriteLeft: Int, val spriteTop: Int)
        val accepted = java.util.concurrent.atomic.AtomicReference<Frame?>()
        var previous: Frame? = null
        var frameNanos = Long.MIN_VALUE
        var sampledNanos = Long.MIN_VALUE
        var active = true
        lateinit var choreographer: android.view.Choreographer
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (!active) return
                frameNanos = time
                frame.postInvalidateOnAnimation()
                choreographer.postFrameCallback(this)
            }
        }
        val listener = android.view.ViewTreeObserver.OnDrawListener {
            if (frameNanos != Long.MIN_VALUE && sampledNanos != frameNanos) {
                sampledNanos = frameNanos
                val panel = frame.getChildAt(0)
                val ready = frame.alpha == 1f && frame.isAttachedToWindow && !frame.isLayoutRequested &&
                    panel?.alpha == 1f && !panel.isLayoutRequested && !sprite().isLayoutRequested && sprite().width > 0
                if (ready) {
                    val origin = IntArray(2); frame.getLocationOnScreen(origin)
                    val next = Frame(bounds(), origin[0], origin[1], frame.width, frame.height, sprite().left, sprite().top)
                    if (next == previous) accepted.compareAndSet(null, next)
                    previous = next
                } else previous = null
            }
        }
        instrumentation.runOnMainSync {
            choreographer = android.view.Choreographer.getInstance()
            frame.viewTreeObserver.addOnDrawListener(listener)
            choreographer.postFrameCallback(callback)
        }
        try {
            ui.waitUntil(5_000) { accepted.get() != null }
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
        mainThread {
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

    fun testRealOverlayFirstDrawSpeechGrowthAndDismissalPreserveAnchor() {
        val packageName = instrumentation.targetContext.packageName
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        ).use { it.readBytes() }
        mainThread {
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
        val expected = stable()
        repeat(3) {
            speak("主人，米饭已经吃完啦，我会继续陪着你。")
            assertEquals(expected, stable())
            mainThread { checkNotNull(controller).dismissSpeech() }
            assertEquals(expected, stable())
        }
        mainThread {
            assertTrue(visibleFrames.isNotEmpty())
            assertTrue("Overlay visible frames moved: $visibleFrames", visibleFrames.all { it == expected })
        }
    }
}
