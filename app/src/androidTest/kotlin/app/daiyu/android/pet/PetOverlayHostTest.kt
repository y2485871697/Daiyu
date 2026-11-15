package app.daiyu.android.pet

import android.test.InstrumentationTestCase
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.daiyu.android.domain.pet.*
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
class PetOverlayHostTest : InstrumentationTestCase() {
    fun testInstalledPackageDeclaresOverlaySpecialAccess() {
        val context = instrumentation.targetContext
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("Pet application overlays require a manifest declaration",
            "android.permission.SYSTEM_ALERT_WINDOW" in permissions)
    }

    fun testOverlayChildWrapsInsteadOfFillingScreenAndRetainsDragRange() = verifyOverlayChildWrapsInsteadOfFillingScreenAndRetainsDragRange()

    private fun verifyOverlayChildWrapsInsteadOfFillingScreenAndRetainsDragRange() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val controller = PetController(InMemoryPetStore(PetState(settings = PetSettings(enabled = true))), PetModelPort { _, _ -> PetCancel {} },
                AndroidPetScheduler(), PetClock { 0 })
            val host = PetOverlayHost(context, controller, WhaleMaidResources(context), {}, onState = { _, _ -> })
            try {
                val frame = host.createWindow()
                val panel = frame.getChildAt(0) as LinearLayout
                val params = panel.layoutParams as FrameLayout.LayoutParams
                assertEquals(FrameLayout.LayoutParams.WRAP_CONTENT, params.width)
                assertEquals(FrameLayout.LayoutParams.WRAP_CONTENT, params.height)
                val density = context.resources.displayMetrics.density
                val screenWidth = (600 * density).roundToInt()
                val screenHeight = (900 * density).roundToInt()
                fun measure() {
                    frame.measure(View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST))
                    frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
                }
                measure()
                assertTrue(frame.width > 0); assertTrue(frame.height > 0)
                assertTrue(frame.width < screenWidth); assertTrue(frame.height < screenHeight)
                assertEquals(screenWidth - frame.width to screenHeight - frame.height,
                    petPlacement(Int.MAX_VALUE, Int.MAX_VALUE, screenWidth, screenHeight, frame.width, frame.width, frame.height, 0).let { it.x to it.y })
                assertEquals(0 to 0, petPlacement(0, 0, screenWidth, screenHeight, frame.width, frame.width, frame.height, 0).let { it.x to it.y })
                panel.getChildAt(0).visibility = View.GONE
                val sprite = panel.getChildAt(1)
                sprite.layoutParams = sprite.layoutParams.apply {
                    width = (128 * 1.8f * density).roundToInt(); height = width
                }
                measure()
                assertEquals(sprite.layoutParams.width, frame.width)
                assertEquals(sprite.layoutParams.height, frame.height)
                assertEquals(screenWidth - frame.width to screenHeight - frame.height,
                    petPlacement(screenWidth, screenHeight, screenWidth, screenHeight, frame.width, frame.width, frame.height, 0).let { it.x to it.y })
            } finally { host.close(); controller.close() }
        }
    }
    private class WindowPort : PetOverlayWindowPort<View> {
        val active = linkedSetOf<View>()
        val removed = mutableListOf<View>()
        var adds = 0
        var updates = 0
        var failAdd = false
        var failUpdate = false
        var failRemove = false
        override fun add(window: View) {
            adds++; active += window
            if (failAdd) throw IllegalStateException("attached then threw")
        }
        override fun update(window: View) {
            updates++
            if (failUpdate) throw IllegalStateException("update failed")
        }
        override fun remove(window: View): PetOverlayRemoval {
            removed += window
            // The real child Views must be unmounted before even attempting root removal.
            check((window as FrameLayout).childCount == 0)
            if (failRemove) throw IllegalStateException("still attached")
            active -= window
            return PetOverlayRemoval.DETACHED
        }
    }
    private inner class Fixture : AutoCloseable {
        val context = instrumentation.targetContext
        val controller = PetController(InMemoryPetStore(), PetModelPort { _, _ -> PetCancel {} },
            AndroidPetScheduler(), PetClock { 0 })
        val port = WindowPort()
        var allowed = true
        var state: PetOverlayState? = null
        var error: String? = null
        val host = PetOverlayHost(context, controller, WhaleMaidResources(context), {},
            windowPort = port, canDrawOverlays = { allowed }, onState = { next, message ->
                state = next; error = message
            })
        val visible = PetSnapshot(PetState(settings = PetSettings(enabled = true, globalVisible = true)))
        override fun close() { port.failRemove = false; host.close(); controller.close() }
    }

    fun testUnknownRemovalBlocksAddAndPermissionRevocationRetriesSameWindow() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                f.host.sync(f.visible)
                assertEquals(PetOverlayState.VISIBLE, f.state)
                val first = f.port.active.single() as FrameLayout
                assertEquals(1, first.childCount)
                f.port.failRemove = true; f.allowed = false
                f.host.sync(f.visible)
                assertEquals(PetOverlayState.ERROR, f.state)
                assertTrue(f.error!!.contains("清理结果未知"))
                assertEquals(0, first.childCount)
                f.allowed = true
                repeat(3) { f.host.sync(f.visible) }
                assertEquals(1, f.port.adds); assertEquals(1, f.port.updates)
                assertSame(first, f.port.active.single())
                f.port.failRemove = false; f.allowed = false
                f.host.sync(f.visible)
                assertEquals(PetOverlayState.PERMISSION_REQUIRED, f.state)
                assertEquals(listOf(first, first), f.port.removed)
                assertTrue(f.port.active.isEmpty())
                f.allowed = true; f.host.sync(f.visible)
                assertEquals(PetOverlayState.VISIBLE, f.state); assertEquals(2, f.port.adds)
            } finally { f.close() }
        }
    }

    fun testPartialAddAndCleanupFailureStayErrorUntilRepeatedCloseSucceeds() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                f.port.failAdd = true; f.port.failRemove = true
                f.host.sync(f.visible)
                val first = f.port.active.single() as FrameLayout
                assertEquals(0, first.childCount)
                assertEquals(PetOverlayState.ERROR, f.state)
                assertTrue(f.error!!.contains("清理结果未知"))
                f.port.failAdd = false
                f.host.sync(f.visible)
                assertEquals(1, f.port.adds); assertEquals(0, f.port.updates)
                f.host.close()
                assertEquals(PetOverlayState.ERROR, f.state)
                f.port.failRemove = false; f.host.close()
                assertEquals(PetOverlayState.CLOSED, f.state); assertNull(f.error)
                assertTrue(f.port.active.isEmpty())
                assertEquals(listOf(first, first, first), f.port.removed)
                f.host.sync(f.visible); f.host.close()
                assertEquals(1, f.port.adds); assertEquals(3, f.port.removed.size)
            } finally { f.close() }
        }
    }

    fun testDisableAndInAppDoNotHideUnknownCleanupWithSuccessState() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                f.host.sync(f.visible); f.port.failRemove = true
                val disabled = f.visible.copy(state = f.visible.state.copy(settings = PetSettings()))
                val inApp = f.visible.copy(state = f.visible.state.copy(settings = PetSettings(enabled = true)))
                f.host.sync(disabled); assertEquals(PetOverlayState.ERROR, f.state)
                f.host.sync(inApp); assertEquals(PetOverlayState.ERROR, f.state)
                f.host.sync(f.visible); assertEquals(1, f.port.adds)
                f.port.failRemove = false; f.host.sync(inApp)
                assertEquals(PetOverlayState.IN_APP, f.state)
                assertTrue(f.port.active.isEmpty())
                f.host.sync(disabled); assertEquals(PetOverlayState.DISABLED, f.state)
            } finally { f.close() }
        }
    }

    fun testUpdateFailureRetiresContentAndKeepsCleanupOwner() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                f.host.sync(f.visible)
                val first = f.port.active.single() as FrameLayout
                f.port.failUpdate = true; f.port.failRemove = true
                f.host.sync(f.visible)
                assertEquals(PetOverlayState.ERROR, f.state); assertEquals(0, first.childCount)
                f.host.sync(f.visible)
                assertEquals(1, f.port.adds); assertEquals(2, f.port.updates)
                f.port.failRemove = false; f.host.close()
                assertEquals(PetOverlayState.CLOSED, f.state)
                assertEquals(listOf(first, first), f.port.removed)
            } finally { f.close() }
        }
    }

    fun testFailedAddWithConfirmedCleanupCanBeRetried() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                f.port.failAdd = true; f.host.sync(f.visible)
                assertEquals(PetOverlayState.ERROR, f.state)
                assertTrue(f.port.active.isEmpty()); assertEquals(0, f.port.updates)
                f.port.failAdd = false; f.host.sync(f.visible)
                assertEquals(PetOverlayState.VISIBLE, f.state); assertEquals(2, f.port.adds)
                f.host.close(); assertEquals(PetOverlayState.CLOSED, f.state)
            } finally { f.close() }
        }
    }

    private fun requestedPosition(host: PetOverlayHost): Pair<Int, Int> {
        val field = PetOverlayHost::class.java.getDeclaredField("params").apply { isAccessible = true }
        val params = field.get(host) as android.view.WindowManager.LayoutParams
        return params.x to params.y
    }
    private fun stagePlacement(f: Fixture): FrameLayout {
        f.host.sync(f.visible.copy(state = f.visible.state.copy(settings =
            f.visible.state.settings.copy(x = 70, y = 120))))
        val frame = f.port.active.single() as FrameLayout
        val side = (128f * f.context.resources.displayMetrics.density).roundToInt()
        frame.layout(0, 0, side + 80, side + 60)
        assertTrue(frame.viewTreeObserver.dispatchOnPreDraw())
        return frame
    }

    fun testPreDrawPlacementIsDeferredCoalescedAndUsesLatestSize() = verifyDeferredPlacement()
    private fun verifyDeferredPlacement() {
        lateinit var f: Fixture
        try {
            instrumentation.runOnMainSync {
                f = Fixture()
                val frame = stagePlacement(f)
                repeat(3) { assertTrue(frame.viewTreeObserver.dispatchOnPreDraw()) }
                assertEquals(1, f.port.updates)
                assertEquals(0f, frame.alpha)
                val side = (128f * f.context.resources.displayMetrics.density).roundToInt()
                frame.layout(0, 0, side + 100, side + 80)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(2, f.port.updates)
                assertEquals(20 to 40, requestedPosition(f.host))
                assertEquals(0f, f.port.active.single().alpha)
            }
        } finally { instrumentation.runOnMainSync { f.close() } }
    }

    fun testDeferredPlacementCannotUpdateRetiredOrReplacementRoot() = verifyRetiredPlacement()
    private fun verifyRetiredPlacement() {
        lateinit var f: Fixture
        lateinit var retired: FrameLayout
        try {
            instrumentation.runOnMainSync {
                f = Fixture()
                retired = stagePlacement(f)
                f.host.sync(f.visible.copy(state = f.visible.state.copy(settings = PetSettings())))
                assertTrue(f.port.active.isEmpty())
                stagePlacement(f)
                assertEquals(2, f.port.updates)
                assertFalse(f.port.active.contains(retired))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(3, f.port.updates)
                assertEquals(0, retired.childCount)
                assertEquals(1, f.port.active.size)
                val replacement = f.port.active.single() as FrameLayout
                val side = (128f * f.context.resources.displayMetrics.density).roundToInt()
                replacement.layout(0, 0, side + 100, side + 80)
                assertTrue(replacement.viewTreeObserver.dispatchOnPreDraw())
                f.host.close()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(3, f.port.updates)
                assertTrue(f.port.active.isEmpty())
            }
        } finally { instrumentation.runOnMainSync { f.close() } }
    }

    fun testDeferredUpdateFailureKeepsUnknownCleanupOwner() = verifyDeferredFailure()
    private fun verifyDeferredFailure() {
        lateinit var f: Fixture
        lateinit var frame: FrameLayout
        try {
            instrumentation.runOnMainSync {
                f = Fixture()
                frame = stagePlacement(f)
                f.port.failUpdate = true
                f.port.failRemove = true
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(2, f.port.updates)
                assertEquals(PetOverlayState.ERROR, f.state)
                assertTrue(checkNotNull(f.error).contains("清理结果未知"))
                assertEquals(0, frame.childCount)
                assertSame(frame, f.port.active.single())
                f.host.sync(f.visible)
                assertEquals(1, f.port.adds)
                assertEquals(2, f.port.updates)
            }
        } finally { instrumentation.runOnMainSync { f.close() } }
    }

    fun testOverlayBoundsKeepSpriteNotBubbleInsideScreen() {
        val bare = petPlacement(0, 0, 400, 600, 128, 128, 128, 16)
        val speech = petPlacement(0, 0, 400, 600, 128, 220, 230, 16)
        assertEquals(0 to 0, bare.spriteX to bare.spriteY)
        assertEquals(-46 to -102, speech.x to speech.y)
        assertEquals(bare.spriteX to bare.spriteY, speech.spriteX to speech.spriteY)
        val bottomRight = petPlacement(Int.MAX_VALUE, Int.MAX_VALUE, 400, 600, 128, 220, 230, 16)
        assertEquals(272 to 472, bottomRight.spriteX to bottomRight.spriteY)
        assertEquals(226 to 370, bottomRight.x to bottomRight.y)
    }
}
