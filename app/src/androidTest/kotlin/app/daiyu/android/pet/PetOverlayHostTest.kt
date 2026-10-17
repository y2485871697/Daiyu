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
            val controller = PetController(InMemoryPetStore(), PetModelPort { _, _ -> PetCancel {} },
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
                    constrainPetOverlayPosition(Int.MAX_VALUE, Int.MAX_VALUE, screenWidth, screenHeight, frame.width, frame.height))
                assertEquals(0 to 0, constrainPetOverlayPosition(-50, -50, screenWidth, screenHeight, frame.width, frame.height))
                panel.getChildAt(0).visibility = View.GONE
                val sprite = panel.getChildAt(1)
                sprite.layoutParams = sprite.layoutParams.apply {
                    width = (128 * 1.8f * density).roundToInt(); height = width
                }
                measure()
                assertEquals(sprite.layoutParams.width, frame.width)
                assertEquals(sprite.layoutParams.height, frame.height)
                assertEquals(screenWidth - frame.width to screenHeight - frame.height,
                    constrainPetOverlayPosition(screenWidth, screenHeight, screenWidth, screenHeight, frame.width, frame.height))
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

    fun testOverlayBoundsClampOversizedWindowAndReclampAfterGrowth() {
        assertEquals(0 to 0, constrainPetOverlayPosition(800, 900, 400, 600, 500, 700))
        assertEquals(280 to 470, constrainPetOverlayPosition(280, 470, 400, 600, 100, 100))
        assertEquals(180 to 370, constrainPetOverlayPosition(280, 470, 400, 600, 220, 230))
        assertEquals(0 to 0, constrainPetOverlayPosition(10, 10, 0, 0, 0, 0))
    }
}
