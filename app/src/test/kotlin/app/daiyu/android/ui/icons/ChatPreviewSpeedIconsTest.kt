package app.daiyu.android.ui.icons

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPreviewSpeedIconsTest {
    @Test
    fun fastMatchesApprovedWindArtwork() {
        assertArtwork(ChatPreviewIcons.ThoughtFast,
            "M3.25 8.25H14a3 3 0 1 0-2.9-3.75",
            "M2.25 12h19",
            "M5 15.75h9a3 3 0 1 1-2.9 3.75")
    }

    @Test
    fun ultraFastMatchesApprovedLightningAndTrails() {
        assertArtwork(ChatPreviewIcons.ThoughtUltraFast,
            "m14.2 2.6-9 10.7h6.4l-1.1 8.1 9.3-11.1h-6.5z",
            "M2.5 7.5h3M2.5 17.5h3")
    }

    @Test
    fun standardThoughtRetainsItsExistingGeometry() {
        val vector = ChatPreviewIcons.Thought
        assertFrame(vector)
        val expected = listOf(
            "M5 15C2 10 5 4 10 4c6 0 9 5 6 9-2 3-7 2-7-1s4-4 5-1M8 20c6 2 12-2 12-8",
            "M6.15 16.5a1.15 1.15 0 1 0 -2.3 0a1.15 1.15 0 1 0 2.3 0Z",
            "M21 7.5a1 1 0 1 0 -2 0a1 1 0 1 0 2 0Z")
        assertEquals(expected.size, vector.root.size)
        expected.forEachIndexed { index, data ->
            assertEquals(PathParser().parsePathString(data).toNodes(), (vector.root[index] as VectorPath).pathData)
        }
        assertEquals(1.7f, (vector.root[0] as VectorPath).strokeLineWidth, 0f)
        assertNotNull((vector.root[1] as VectorPath).fill)
        assertNotNull((vector.root[2] as VectorPath).fill)
    }

    private fun assertArtwork(vector: ImageVector, vararg expected: String) {
        assertFrame(vector)
        assertEquals(expected.size, vector.root.size)
        expected.forEachIndexed { index, data ->
            val path = vector.root[index] as VectorPath
            assertEquals(PathParser().parsePathString(data).toNodes(), path.pathData)
            assertNull(path.fill)
            assertNotNull(path.stroke)
            assertEquals(1.8f, path.strokeLineWidth, 0f)
            assertEquals(StrokeCap.Round, path.strokeLineCap)
            assertEquals(StrokeJoin.Round, path.strokeLineJoin)
        }
    }

    private fun assertFrame(vector: ImageVector) {
        assertEquals(24.dp, vector.defaultWidth)
        assertEquals(24.dp, vector.defaultHeight)
        assertEquals(24f, vector.viewportWidth, 0f)
        assertEquals(24f, vector.viewportHeight, 0f)
    }
}
