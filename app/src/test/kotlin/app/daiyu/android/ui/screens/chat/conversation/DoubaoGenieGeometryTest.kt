package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class DoubaoGenieGeometryTest {
    private val anchor = DoubaoGenieGeometry.Bounds(170f, 720f, 20f, 20f)
    private val panel = DoubaoGenieGeometry.Bounds(10f, 210f, 340f, 374f)

    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, .015f)

    @Test fun previewTimingAndSymbolsAreExact() {
        assertEquals(420, DoubaoGenieGeometry.DURATION_MILLIS)
        assertEquals("m7 10 5 5 5-5M7 5h10", DoubaoGenieGeometry.COLLAPSE_PATH)
        assertEquals("M14 4h6v6m-6 0 6-6M10 20H4v-6m0 6 6-6", DoubaoGenieGeometry.EXPAND_PATH)
        near(66f, DoubaoGenieGeometry.ORB_DIAMETER)
        near(7f, DoubaoGenieGeometry.HALO_INSET)
        near(37f, DoubaoGenieGeometry.MINI_DIAMETER)
    }

    @Test fun wavesUsePreviewBaseHeightsNegativeDelaysAndCssEasing() {
        val base = listOf(12f, 21f, 29f, 20f, 11f)
        val delays = listOf(0f, 500f, 900f, 300f, 700f)
        base.indices.forEach { i ->
            near(base[i] * .55f, DoubaoGenieGeometry.orbFrame(1450f - delays[i]).barHeights[i])
            near(base[i], DoubaoGenieGeometry.orbFrame(2175f - delays[i]).barHeights[i])
            near(base[i], DoubaoGenieGeometry.orbFrame(1125f - delays[i], responding = true).barHeights[i])
        }
        val start = DoubaoGenieGeometry.orbFrame(0f)
        val later = DoubaoGenieGeometry.orbFrame(190f)
        assertNotEquals(start.barHeights, later.barHeights)
        assertNotEquals(start.barHeights, DoubaoGenieGeometry.orbFrame(0f, responding = true).barHeights)
        near(1f, start.haloScale); near(.45f, start.haloAlpha)
        near(1.055f, DoubaoGenieGeometry.orbFrame(1500f).haloScale)
        near(.8f, DoubaoGenieGeometry.orbFrame(1500f).haloAlpha)
        near(180f, DoubaoGenieGeometry.orbFrame(750f).spinnerDegrees)
        near(1f, DoubaoGenieGeometry.orbFrame(1500f, still = true).haloScale)
    }

    @Test fun miniUsesAnIndependentPoint65WaveNotThirtySevenOverSixtySix() {
        val full = DoubaoGenieGeometry.orbFrame(310f)
        val mini = DoubaoGenieGeometry.orbFrame(310f, compact = true)
        full.barHeights.zip(mini.barHeights).forEach { (a, b) -> near(a * .65f, b) }
        val connecting = DoubaoGenieGeometry.orbFrame(1150f, compact = true, connecting = true)
        listOf(12f, 21f, 29f, 20f, 11f).zip(connecting.barHeights).forEach { (a, b) -> near(a * .65f, b) }
        assertNotEquals(connecting.barHeights,
            DoubaoGenieGeometry.orbFrame(300f, compact = true, connecting = true).barHeights)
    }

    @Test fun consumedStateStylesMatchPreviewAndMiniNeverUsesSpinner() {
        val listening = DoubaoGenieGeometry.orbStyle(DoubaoGenieGeometry.OrbMode.LISTENING, false)
        assertEquals(0xFFEEE6FBL, listening.background)
        assertEquals(0xFFA18AC7L, listening.bars)
        assertEquals(0xFFF6F2FCL, listening.halo)
        assertTrue(listening.glow)
        val response = DoubaoGenieGeometry.orbStyle(DoubaoGenieGeometry.OrbMode.RESPONDING, false)
        assertEquals(0xFFE9DDF9L, response.background)
        assertEquals(0xFF9471BDL, response.bars)
        DoubaoGenieGeometry.OrbMode.values().forEach { state ->
            val mini = DoubaoGenieGeometry.orbStyle(state, true)
            assertEquals(0xFFEEE5F9L, mini.background)
            assertEquals(0xFFA58CC5L, mini.bars)
            assertEquals(0xFF9F87BAL, mini.icon)
            assertFalse(mini.spinner)
            val full = DoubaoGenieGeometry.orbStyle(state, false)
            if (state == DoubaoGenieGeometry.OrbMode.MUTED || state == DoubaoGenieGeometry.OrbMode.ENDED) {
                assertTrue(full.still); assertFalse(full.showWave); assertFalse(full.glow)
            }
            if (state == DoubaoGenieGeometry.OrbMode.CONNECTING || state == DoubaoGenieGeometry.OrbMode.RECONNECTING) {
                assertTrue(full.spinner); assertFalse(full.showWave); assertTrue(mini.showWave)
            }
        }
    }

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
