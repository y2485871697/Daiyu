package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import app.daiyu.android.data.model.AppearancePaletteStyle
import app.daiyu.android.data.model.UserBubbleMode
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
            assertEquals(readableAccent(accent, dark), app.daiyu.android.ui.components.ConversationPanePreviewPalette(dark, custom).accent)
            assertEquals(readableAccent(accent, dark), app.daiyu.android.ui.components.subagent.ConversationAgentColors(dark, custom).accent)
        }
    }

    private fun contrast(foreground: Color, background: Color): Float =
        (maxOf(foreground.luminance(), background.luminance()) + .05f) /
            (minOf(foreground.luminance(), background.luminance()) + .05f)

    @Test fun seedBrightnessIsEffectiveWithoutCrossingThemeBoundaries() {
        for (dark in listOf(false, true)) for (style in AppearancePaletteStyle.entries) {
            val bright = resolveAppearanceColors(AppearanceSettings(paletteStyle = style,
                paletteSeedColor = Color.hsv(145f, .8f, 1f).toArgb()), dark)
            val dim = resolveAppearanceColors(AppearanceSettings(paletteStyle = style,
                paletteSeedColor = Color.hsv(145f, .8f, .15f).toArgb()), dark)
            assertNotEquals("$dark $style", bright.surface, dim.surface)
            assertEquals(dark, bright.surface.luminance() < .5f)
            assertEquals(dark, dim.surface.luminance() < .5f)
        }
    }

    @Test fun customActionFillsAndBothContainersHaveReadableContent() {
        for (dark in listOf(false, true)) for (accent in listOf(Color.Black, Color.White, Color.Yellow, Color(0xFF767676))) {
            val appearance = AppearanceSettings(primaryAccentColor = accent.toArgb(), secondaryAccentColor = accent.toArgb(),
                paletteSeedColor = 0xFF449977.toInt(), paletteStyle = AppearancePaletteStyle.VIBRANT)
            val actions = resolveAppearanceActions(appearance, dark)
            assertEquals(accent, actions.primary)
            assertTrue(contrast(actions.onPrimary, actions.primary) >= 4.5f)
            assertTrue(contrast(actions.onSecondary, actions.secondaryContainer) >= 4.5f)
            assertTrue(contrast(actions.onPrimaryContainer, actions.primaryContainer) >= 4.5f)
            val theme = resolveAppearanceColors(appearance, dark)
            assertEquals(accent, theme.secondary)
            assertTrue(contrast(theme.onSecondary, theme.secondary) >= 4.5f)
            assertTrue(contrast(theme.onTertiaryContainer, theme.tertiaryContainer) >= 4.5f)
            assertNotEquals((if (dark) DaiyuPalette.dark else DaiyuPalette.light).disabledPrimary, theme.disabledPrimary)
        }
    }

    @Test fun userBubbleModesAffectOnlyTheirOwnFillAndText() {
        for (dark in listOf(false, true)) {
            val initial = AppearanceSettings(paletteSeedColor = 0xFF439965.toInt(), primaryAccentColor = 0xFF556699.toInt(),
                secondaryAccentColor = 0xFF886644.toInt())
            val themed = resolveUserBubbleColors(initial, dark)
            assertTrue(contrast(themed.content, themed.container) >= 4.5f)
            val off = resolveUserBubbleColors(initial.copy(userBubbleMode = UserBubbleMode.OFF), dark)
            assertEquals(Color.Transparent, off.container)
            assertEquals(themed.content, off.content)
            for (fill in listOf(Color.Black, Color.White, Color.Yellow, Color.Blue)) {
                val custom = initial.copy(userBubbleMode = UserBubbleMode.CUSTOM, userBubbleColor = fill.toArgb())
                val bubble = resolveUserBubbleColors(custom, dark)
                assertEquals(fill, bubble.container)
                assertTrue(contrast(bubble.content, fill) >= 4.5f)
                assertEquals(resolveAppearanceActions(initial, dark), resolveAppearanceActions(custom, dark))
                assertEquals(resolveAppearanceColors(initial, dark).surface, resolveAppearanceColors(custom, dark).surface)
            }
            assertEquals(themed, resolveUserBubbleColors(initial.copy(userBubbleMode = UserBubbleMode.CUSTOM), dark))
        }
    }

    @Test fun sidebarSelectionIsIndependentOfUtilityAndUserBubbles() {
        for (dark in listOf(false, true)) {
            val base = AppearanceSettings(paletteSeedColor = 0xFF449977.toInt())
            val original = app.daiyu.android.ui.components.ConversationPanePreviewPalette(dark, base)
            val changed = app.daiyu.android.ui.components.ConversationPanePreviewPalette(dark,
                base.copy(primaryAccentColor = Color.Red.toArgb(), userBubbleMode = UserBubbleMode.CUSTOM,
                    userBubbleColor = Color.Blue.toArgb()))
            assertNotEquals(original.selectionContainer, changed.selectionContainer)
            assertEquals(original.utilityContainer, changed.utilityContainer)
            assertEquals(original.action, changed.action)
            assertTrue(contrast(changed.selectionContent, changed.selectionContainer) >= 4.5f)
            assertEquals(original.danger, changed.danger)
        }
    }

    @Test fun dangerAndPrimarySelectionDoNotFollowSecondaryAccent() {
        for (dark in listOf(false, true)) {
            val base = AppearanceSettings(primaryAccentColor = Color.Blue.toArgb())
            val changed = base.copy(secondaryAccentColor = Color.Green.toArgb(), paletteSeedColor = Color.Red.toArgb())
            val defaults = if (dark) DaiyuPalette.dark else DaiyuPalette.light
            val theme = resolveAppearanceColors(changed, dark)
            assertEquals(defaults.error, theme.error)
            assertEquals(defaults.errorContainer, theme.errorContainer)
            assertEquals(defaults.onErrorContainer, theme.onErrorContainer)
            assertEquals(destructiveColors(dark).container,
                app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette(dark, changed).dangerContainer)
            assertTrue(contrast(destructiveColors(dark).content, destructiveColors(dark).container) >= 4.5f)
            assertEquals(resolveAppearanceColors(base, dark).primaryContainer,
                resolveAppearanceColors(base.copy(secondaryAccentColor = Color.Green.toArgb()), dark).primaryContainer)
        }
    }

}
