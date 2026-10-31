package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class DoubaoGenieGeometryTest {
    private val anchor = DoubaoGenieGeometry.Bounds(170f, 720f, 20f, 20f)
    private val panel = DoubaoGenieGeometry.Bounds(10f, 210f, 340f, 374f)

    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, .015f)

    @Test fun endpointsAreTheTwentyPixelAnchorAndTheFullPanel() {
        for (i in 0..64) {
            val u = i / 64f
            val closed = DoubaoGenieGeometry.edge(u, 0f, anchor, panel)
            near(anchor.left, closed.left)
            near(anchor.width, closed.width)
            near(anchor.top + u * anchor.height, closed.y)
            val open = DoubaoGenieGeometry.edge(u, 1f, anchor, panel)
            near(panel.left, open.left)
            near(panel.width, open.width)
            near(panel.top + u * panel.height, open.y)
        }
    }

    @Test fun quarterHalfAndThreeQuartersWarpContentNonUniformly() {
        for (p in listOf(.25f, .5f, .75f)) {
            val strips = DoubaoGenieGeometry.strips(p, anchor, panel)
            val first = strips.first()
            val last = strips.last()
            assertTrue("p=$p must not be a uniform width scale", first.top.width > last.bottom.width + 1f)
            val firstHeight = first.bottom.y - first.top.y
            val lastHeight = last.bottom.y - last.top.y
            assertTrue("p=$p must not be a uniform height scale", kotlin.math.abs(firstHeight - lastHeight) > .01f)
            assertTrue("p=$p needs perspective within strips", strips.any { kotlin.math.abs(it.matrix[7]) > .000001f })
        }
    }

    @Test fun allFourPerspectiveCornersMapOntoEachTrapezoid() {
        for (p in listOf(0f, .01f, .25f, .5f, .75f, .99f, 1f)) {
            for (strip in DoubaoGenieGeometry.strips(p, anchor, panel)) {
                val tl = strip.map(0f, 0f)
                val tr = strip.map(panel.width, 0f)
                val bl = strip.map(0f, strip.sourceHeight)
                val br = strip.map(panel.width, strip.sourceHeight)
                near(strip.top.left, tl.x); near(strip.top.y, tl.y)
                near(strip.top.left + strip.top.width, tr.x); near(strip.top.y, tr.y)
                near(strip.bottom.left, bl.x); near(strip.bottom.y, bl.y)
                near(strip.bottom.left + strip.bottom.width, br.x); near(strip.bottom.y, br.y)
            }
        }
    }

    @Test fun matricesStayFiniteAndAdjacentStripsShareExactlyOneEdge() {
        for (step in 0..100) {
            val strips = DoubaoGenieGeometry.strips(step / 100f, anchor, panel)
            assertEquals(64, strips.size)
            strips.forEach {
                assertTrue(it.matrix.all(Float::isFinite))
                assertTrue(it.top.width > 0f)
                assertTrue(it.bottom.width > 0f)
                assertTrue(1f + it.matrix[7] * it.sourceHeight > 0f)
            }
            strips.zipWithNext().forEach { (a, b) ->
                assertEquals(a.bottom, b.top)
                near(a.sourceY + a.sourceHeight, b.sourceY)
                val rightA = a.map(panel.width, a.sourceHeight)
                val rightB = b.map(panel.width, 0f)
                near(rightA.x, rightB.x); near(rightA.y, rightB.y)
            }
        }
    }

    @Test fun reverseMotionUsesExactlyTheSameSurfaceAtTheSameProgress() {
        val forward = (0..100).associateWith { DoubaoGenieGeometry.strips(it / 100f, anchor, panel) }
        for (step in 100 downTo 0) {
            val reversed = DoubaoGenieGeometry.strips(step / 100f, anchor, panel)
            forward.getValue(step).zip(reversed).forEach { (a, b) ->
                assertEquals(a.top, b.top)
                assertEquals(a.bottom, b.bottom)
                assertArrayEquals(a.matrix, b.matrix, 0f)
            }
        }
    }

    @Test fun resizedAndDraggedPanelStillMapsCapturedSourceCorners() {
        val moved = DoubaoGenieGeometry.Bounds(3f, 8f, 297f, 190f)
        val strips = DoubaoGenieGeometry.strips(1f, anchor, moved, sourceWidth = 340f, sourceHeight = 374f)
        near(moved.left, strips.first().map(0f, 0f).x)
        near(moved.top, strips.first().map(0f, 0f).y)
        val end = strips.last().map(340f, strips.last().sourceHeight)
        near(moved.left + moved.width, end.x)
        near(moved.top + moved.height, end.y)
    }

    @Test fun clampAndTinyHostSizesDoNotProduceNaN() {
        near(0f, DoubaoGenieGeometry.smooth(-1f))
        near(1f, DoubaoGenieGeometry.smooth(2f))
        val tiny = DoubaoGenieGeometry.Bounds(0f, 0f, 1f, 1f)
        listOf(-1f, 0f, .5f, 1f, 2f).forEach { p ->
            DoubaoGenieGeometry.strips(p, anchor, tiny).forEach {
                assertTrue(it.matrix.all(Float::isFinite))
            }
        }
    }
}
