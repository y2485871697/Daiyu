package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import app.daiyu.android.data.model.AppearancePaletteStyle
import app.daiyu.android.data.model.AppearanceSettings
import org.junit.Assert.*
import org.junit.Test

class AppearanceColorsTest {
    @Test fun defaultTokensAreRestoredInBothThemes() {
        for (dark in listOf(false, true)) {
            val base = if (dark) DaiyuPalette.dark else DaiyuPalette.light
            val resolved = resolveAppearanceColors(AppearanceSettings(), dark)
            assertEquals(base.primary, resolved.primary)
            assertEquals(base.onPrimaryContainer, resolved.onPrimaryContainer)
            assertEquals(base.onPrimaryVariant, resolved.onPrimaryVariant)
            assertEquals(base.secondaryContainer, resolved.secondaryContainer)
            assertEquals(base.onSecondaryContainer, resolved.onSecondaryContainer)
            assertEquals(base.background, resolved.background)
            assertEquals(base.surface, resolved.surface)
        }
    }

    @Test fun styleResetPreservesBothAccentsAndUnrelatedSettings() {
        val custom = AppearanceSettings(paletteStyle = AppearancePaletteStyle.EXPRESSIVE,
            paletteSeedColor = 0xFF449988.toInt(), primaryAccentColor = 0xFF905040.toInt(),
            secondaryAccentColor = 0xFF405090.toInt(), interfaceScale = .9f, pureBlackEnabled = true)
        val reset = custom.withColorStyle(AppearanceSettings())
        assertNull(reset.paletteSeedColor)
        assertEquals(AppearancePaletteStyle.NEUTRAL, reset.paletteStyle)
        assertEquals(custom, reset.withColorStyle(custom))
        assertEquals(custom.primaryAccentColor, reset.primaryAccentColor)
        assertEquals(custom.secondaryAccentColor, reset.secondaryAccentColor)
    }

    @Test fun accentResetPreservesStyleAndUnrelatedSettings() {
        val custom = AppearanceSettings(paletteStyle = AppearancePaletteStyle.VIBRANT,
            paletteSeedColor = 0xFF408080.toInt(), primaryAccentColor = 0xFF504090.toInt(),
            secondaryAccentColor = 0xFF805090.toInt(), interfaceScale = .8f)
        val reset = custom.withAccentColors(AppearanceSettings())
        assertNull(reset.primaryAccentColor)
        assertNull(reset.secondaryAccentColor)
        assertEquals(custom.paletteStyle, reset.paletteStyle)
        assertEquals(custom.paletteSeedColor, reset.paletteSeedColor)
        assertEquals(custom, reset.withAccentColors(custom))
    }

    @Test fun whiteSurfacesAndAlphaFollowStyleWhileDefaultIsUnchanged() {
        val original = Color.White.copy(alpha = .4f)
        assertEquals(original, AppearanceSettings().styleColor(original))
        val recolored = AppearanceSettings(paletteStyle = AppearancePaletteStyle.VIBRANT,
            paletteSeedColor = 0xFF229944.toInt()).styleColor(original)
        assertNotEquals(original, recolored)
        assertEquals(original.alpha, recolored.alpha, .001f)
        assertTrue(recolored.green > recolored.red)
    }

    @Test fun allStylesProduceDistinctColoredSurfaceSamples() {
        val samples = AppearancePaletteStyle.entries.map { style ->
            AppearanceSettings(paletteStyle = style, paletteSeedColor = 0xFF407FCF.toInt())
                .styleColor(Color(0xFFDAC9EF))
        }
        assertEquals(AppearancePaletteStyle.entries.size, samples.toSet().size)
    }

    @Test fun customAccentsDoNotAffectSurfaceStyleAndBlackModeStaysBlack() {
        val custom = AppearanceSettings(paletteSeedColor = 0xFF448866.toInt(), pureBlackEnabled = true,
            primaryAccentColor = 0xFF229944.toInt(), secondaryAccentColor = 0xFF8844BB.toInt())
        val resolved = resolveAppearanceColors(custom, true)
        assertEquals(Color.Black, resolved.background)
        assertEquals(Color.Black, resolved.surface)
        assertEquals(Color(custom.primaryAccentColor!!), resolved.primary)
        assertEquals(resolveAppearanceColors(custom.withAccentColors(AppearanceSettings()), true).surfaceVariant,
            resolved.surfaceVariant)
        assertNotEquals(resolveAppearanceColors(custom.copy(secondaryAccentColor = null), true).secondaryContainer,
            resolved.secondaryContainer)
    }
    @Test fun extremeCustomAccentsKeepContainerTextReadableWithoutChangingTheFill() {
        for (dark in listOf(false, true)) for (accent in listOf(Color.White, Color.Black)) {
            val custom = AppearanceSettings(primaryAccentColor = accent.toArgb())
            val palette = resolveAppearanceColors(custom, dark)
            assertEquals(accent, palette.primary)
            val base = if (dark) Color(0xFF191B23) else Color.White
            val background = palette.primaryContainer.compositeOver(base)
            val foreground = palette.onPrimaryContainer
            val ratio = (maxOf(foreground.luminance(), background.luminance()) + .05f) /
                (minOf(foreground.luminance(), background.luminance()) + .05f)
            assertTrue("dark=$dark accent=$accent ratio=$ratio", ratio >= 4.5f)
            assertEquals(foreground, app.daiyu.android.ui.components.ConversationPanePreviewPalette(dark, custom).accent)
            assertEquals(foreground, app.daiyu.android.ui.components.subagent.ConversationAgentColors(dark, custom).accent)
        }
    }

}
