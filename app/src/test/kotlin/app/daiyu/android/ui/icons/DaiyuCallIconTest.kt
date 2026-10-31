package app.daiyu.android.ui.icons

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.*
import org.junit.Test

class DaiyuCallIconTest {
    @Test fun callKeepsTheExistingOutlineSizeAndStroke() {
        val call = DaiyuIcons.Call
        assertEquals("Daiyu.Call", call.name)
        assertEquals(24f, call.defaultWidth.value, 0f)
        assertEquals(24f, call.defaultHeight.value, 0f)
        assertEquals(24f, call.viewportWidth, 0f)
        assertEquals(24f, call.viewportHeight, 0f)
        assertEquals(1, call.root.size)
        val path = call.root[0] as VectorPath
        assertNull(path.fill)
        assertNotNull(path.stroke)
        assertEquals(1.75f, path.strokeLineWidth, 0f)
        assertEquals(StrokeCap.Round, path.strokeLineCap)
        assertEquals(StrokeJoin.Round, path.strokeLineJoin)
    }

    @Test fun callIsNotTheMicrophoneOrSpeakerGeometry() {
        val call = DaiyuIcons.Call
        assertSame(call, DaiyuIcons.Call)
        assertNotSame(call, DaiyuIcons.Voice)
        assertNotSame(call, DaiyuIcons.Speak)
        val path = (call.root[0] as VectorPath).pathData
        assertNotEquals(path, (DaiyuIcons.Voice.root[0] as VectorPath).pathData)
        assertNotEquals(path, (DaiyuIcons.Speak.root[0] as VectorPath).pathData)
    }
}
