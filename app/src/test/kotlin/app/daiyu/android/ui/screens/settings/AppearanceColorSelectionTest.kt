package app.daiyu.android.ui.screens.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class AppearanceColorSelectionTest {
    private fun echo(selection: AppearanceColorSelection) = selection.synchronize(Color(selection.color.toArgb()))

    @Test fun quantizedDraggingNeverChangesTheSelectedHueOrCoordinates() {
        for (hue in listOf(1.7f, 43.2f, 129.7f, 276.4f, 359.99f, 360f)) {
            var selection = AppearanceColorSelection(hue, .6f, .8f)
            for (step in 0..400) {
                val saturation = (step % 23) / 22f
                val value = (step % 31) / 30f
                selection = echo(selection.selectSaturationValue(saturation, value))
                assertEquals(hue, selection.hue, 0f)
                assertEquals(saturation, selection.saturation, 0f)
                assertEquals(value, selection.value, 0f)
            }
        }
    }

    @Test fun whiteGrayAndBlackKeepHueAndBlackKeepsSaturation() {
        val initial = AppearanceColorSelection(278.5f, .73f, .82f)
        val white = echo(initial.selectSaturationValue(0f, 1f))
        val gray = echo(white.selectSaturationValue(0f, .4f))
        val black = echo(gray.selectSaturationValue(.73f, 0f))
        assertEquals(initial.hue, white.hue, 0f)
        assertEquals(initial.hue, gray.hue, 0f)
        assertEquals(initial.hue, black.hue, 0f)
        assertEquals(.73f, black.saturation, 0f)
        assertEquals(initial, echo(black.selectSaturationValue(.73f, .82f)))
    }

    @Test fun hueAtBlackAndGraySurvivesTheParentsUnchangedRgbEcho() {
        for ((saturation, value) in listOf(0f to .4f, .83f to 0f)) {
            val selected = echo(AppearanceColorSelection(20f, saturation, value).selectHue(193.7f))
            assertEquals(193.7f, selected.hue, 0f)
            assertEquals(saturation, selected.saturation, 0f)
            assertEquals(value, selected.value, 0f)
            assertEquals(Color.hsv(193.7f, saturation, .8f).toArgb(), selected.selectSaturationValue(saturation, .8f).color.toArgb())
        }
    }

    @Test fun hueChangesOnlyHueAndKeepsBothRedEndpointsStable() {
        val initial = AppearanceColorSelection(240f, .72f, .61f)
        for (hue in listOf(0f, 60f, 180f, 300f, 360f)) {
            val selected = echo(initial.selectHue(hue))
            assertEquals(hue, selected.hue, 0f)
            assertEquals(initial.saturation, selected.saturation, 0f)
            assertEquals(initial.value, selected.value, 0f)
        }
    }

    @Test fun externalDefaultsAndSwatchesReplaceAllCoordinates() {
        val initial = AppearanceColorSelection(278f, .31f, .22f)
        for (color in listOf(Color(0xFF8E7C9E), Color(0xFF407FCE), Color.White, Color.Black)) {
            val synced = initial.synchronize(color)
            assertEquals(AppearanceColorSelection.fromColor(color), synced)
            assertEquals(color.toArgb(), synced.color.toArgb())
        }
    }

    @Test fun selectionClampsFingerPositionsOutsideThePlane() {
        val initial = AppearanceColorSelection(250f, .5f, .6f)
        assertEquals(initial.copy(saturation = 0f, value = 1f), initial.selectSaturationValue(-.4f, 1.4f))
        assertEquals(initial.copy(saturation = 1f, value = 0f), initial.selectSaturationValue(1.4f, -.4f))
        assertEquals(0f, initial.selectHue(-1f).hue, 0f)
        assertEquals(360f, initial.selectHue(400f).hue, 0f)
    }

    @Test fun independentPickersCannotMoveEachOthersHue() {
        val primary = AppearanceColorSelection(12f, .7f, .8f)
        val secondary = AppearanceColorSelection(215f, .6f, .4f)
        echo(primary.selectSaturationValue(0f, 0f).selectHue(83f))
        assertEquals(215f, secondary.hue, 0f)
    }
}
