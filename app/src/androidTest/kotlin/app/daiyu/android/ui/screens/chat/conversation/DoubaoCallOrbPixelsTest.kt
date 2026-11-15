package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.ComposeActivityTestCase

@Suppress("DEPRECATION")
class DoubaoCallOrbPixelsTest : ComposeActivityTestCase() {
    fun testPreviewRadiiGlowHairlinesAndDifferentWavePhasesIncludingMini() {
        val activity = startMain()
        var phase by mutableFloatStateOf(0f)
        var compact by mutableStateOf(false)
        try {
            mainThread {
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        Box(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding(),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                                CallWave(DoubaoCallVisualState.LISTENING,
                                    Modifier.size(if (compact) 37.dp else 80.dp), compact = compact, phaseMillis = phase)
                            }
                        }
                    }
                }
            }
            fun pixels() = ui.onNodeWithTag(
                if (compact) "doubao-call-mini-orb" else "doubao-call-orb-pixels").captureToImage().toPixelMap()
            fun nearColor(expected: Color, actual: Color, tolerance: Float = .025f) {
                assertEquals(expected.red, actual.red, tolerance)
                assertEquals(expected.green, actual.green, tolerance)
                assertEquals(expected.blue, actual.blue, tolerance)
            }
            val start = pixels()
            assertEquals(80, start.width)
            nearColor(Color(0xFFA18AC7), start[40, 40]) // purple, not white bars
            nearColor(Color(0xFFEEE6FB), start[49, 40]) // exact base at unlit centre
            nearColor(Color(0xFFF6F2FC), start[40, 3]) // 7px halo outside 33px ball
            assertTrue("inset light must brighten the outer ball", start[40, 8].red > start[49, 40].red)
            assertTrue("inner hairline must remain visible", start[40, 14].red < start[40, 13].red)
            mainThread { phase = 725f }
            val later = pixels()
            assertTrue(start[40, 28] != later[40, 28]) // centre bar changes with its -.9s phase
            mainThread { compact = true; phase = 0f }
            val mini = pixels()
            assertEquals(37, mini.width)
            nearColor(Color(0xFFEEE5F9), mini[18, 3])
            nearColor(Color(0xFFA58CC5), mini[18, 18])
            mainThread { phase = 725f }
            val miniLater = pixels()
            assertTrue(mini[18, 11] != miniLater[18, 11])
        } finally { mainThread { activity.finish() } }
    }
}

