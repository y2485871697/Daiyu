package app.daiyu.android.ui.components

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import app.daiyu.android.data.repository.heatmapAlpha
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class UsageHeatmapPaletteTest {
    @Test fun emptyCellsHaveOpaqueContrastingShadeInBothThemes() {
        for (dark in listOf(false, true)) {
            val palette = UsagePalette(dark)
            val empty = palette.heatmapEmpty
            assertEquals(1f, empty.alpha, 0f)
            val difference = maxOf(abs(empty.red - palette.bubble.red),
                abs(empty.green - palette.bubble.green), abs(empty.blue - palette.bubble.blue))
            assertTrue("Empty cells must not reuse a near-identical card shade", difference >= .07f)
            if (dark) assertTrue(empty.red > palette.bubble.red)
            else assertTrue(empty.red < palette.bubble.red)
        }
    }

    @Test fun zeroAndAllFourHeatLevelsRemainDistinct() {
        for (dark in listOf(false, true)) {
            val palette = UsagePalette(dark)
            val backing = palette.bubble.copy(alpha = .62f).compositeOver(palette.background)
            val levels = listOf(palette.heatmapEmpty) + listOf(.25f, .5f, .75f, 1f)
                .map { palette.accent.copy(alpha = it).compositeOver(backing) }
            assertEquals(5, levels.map { it.toArgb() }.toSet().size)
        }
    }

    @Test fun classificationKeepsFutureZeroAndFourPositiveLevelsSeparate() {
        assertEquals(-1f, heatmapAlpha(5, 1, 2, 3, isFuture = true), 0f)
        assertEquals(listOf(0f, .25f, .5f, .75f, 1f),
            (0..4).map { heatmapAlpha(it, 1, 2, 3, isFuture = false) })
    }
}
