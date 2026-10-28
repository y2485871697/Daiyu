package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
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
            assertEquals(base.onSurface, resolved.onSurface)
            assertEquals(base.onSurfaceSecondary, resolved.onSurfaceSecondary)
            assertEquals(base.onBackground, resolved.onBackground)
            assertEquals(base.surfaceVariant, resolved.surfaceVariant)
            assertEquals(base.surfaceContainer, resolved.surfaceContainer)
            assertEquals(base.surfaceContainerHigh, resolved.surfaceContainerHigh)
            assertEquals(base.surfaceContainerHighest, resolved.surfaceContainerHighest)
            assertEquals(base.outline, resolved.outline)
            assertEquals(base.dividerLine, resolved.dividerLine)
        }
    }

    @Test fun customRgbIsOpaqueAndDoesNotTreatZeroAsMissing() {
        for (dark in listOf(false, true)) for (input in listOf(0, 0x003377CC, 0x803377CC.toInt())) {
            val appearance = AppearanceSettings(paletteSeedColor = input)
            val expected = Color(input or 0xFF000000.toInt())
            assertEquals(expected, resolveAppearanceColors(appearance, dark).surface)
            assertEquals(expected, resolveAppearanceColors(appearance, dark).background)
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
    @Test fun extremeCustomAccentsKeepSelectedRgbForFillAndAccentText() {
        for (dark in listOf(false, true)) for (accent in listOf(Color.White, Color.Black)) {
            val custom = AppearanceSettings(primaryAccentColor = accent.toArgb())
            val palette = resolveAppearanceColors(custom, dark)
            assertEquals(accent, palette.primary)
            assertEquals(accent, palette.onPrimaryContainer)
            assertEquals(accent, app.daiyu.android.ui.components.ConversationPanePreviewPalette(dark, custom).accent)
            assertEquals(accent, app.daiyu.android.ui.components.subagent.ConversationAgentColors(dark, custom).accent)
        }
    }

    private fun contrast(foreground: Color, background: Color): Float =
        (maxOf(foreground.luminance(), background.luminance()) + .05f) /
            (minOf(foreground.luminance(), background.luminance()) + .05f)

    @Test fun customSurfaceUsesExactSelectedRgbAcrossThemesAndStyles() {
        for (dark in listOf(false, true)) for (style in AppearancePaletteStyle.entries) {
            for (fill in listOf(Color.Black, Color.White, Color.Red, Color.Blue, Color(0xFF407FCE))) {
                val appearance = AppearanceSettings(paletteStyle = style, paletteSeedColor = fill.toArgb())
                val resolved = resolveAppearanceColors(appearance, dark)
                assertEquals("background dark=$dark style=$style", fill, resolved.background)
                assertEquals("surface dark=$dark style=$style", fill, resolved.surface)
                assertTrue(contrast(resolved.onBackground, resolved.background) >= 4.5f)
                for ((background, foreground) in listOf(resolved.surface to resolved.onSurface,
                    resolved.surfaceVariant to resolved.onSurfaceSecondary,
                    resolved.surfaceContainer to resolved.onSurfaceContainer,
                    resolved.surfaceContainerHigh to resolved.onSurfaceContainerHigh,
                    resolved.surfaceContainerHighest to resolved.onSurfaceContainerHighest)) {
                    assertTrue("dark=$dark style=$style fill=$fill", contrast(foreground, background) >= 4.5f)
                }
                assertNotEquals(resolved.surface, resolved.surfaceContainerHigh)
            }
        }
    }

    @Test fun fidelityDecorationRetainsSelectedRgbAndOriginalAlpha() {
        for (fill in listOf(Color.Black, Color.White, Color.Red, Color(0xFF407FCE))) {
            val appearance = AppearanceSettings(paletteStyle = AppearancePaletteStyle.FIDELITY,
                paletteSeedColor = fill.toArgb())
            val result = appearance.styleColor(Color.White.copy(alpha = .4f))
            assertEquals(fill.toArgb(), result.copy(alpha = 1f).toArgb())
            assertEquals(.4f, result.alpha, .001f)
        }
    }

    @Test fun customSurfacePropagatesToLocalPalettesAndKeepsContentReadable() {
        for (dark in listOf(false, true)) for (fill in listOf(Color.Black, Color.White, Color.Red, Color.Blue)) {
            val appearance = AppearanceSettings(paletteSeedColor = fill.toArgb())
            val side = app.daiyu.android.ui.components.ConversationPanePreviewPalette(dark, appearance)
            val assistant = app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette(dark, appearance)
            val agent = app.daiyu.android.ui.components.subagent.ConversationAgentColors(dark, appearance)
            val usage = app.daiyu.android.ui.components.UsagePalette(dark, appearance)
            assertEquals(fill, side.surface)
            assertEquals(fill, side.fallbackSurface)
            assertEquals(fill, assistant.background)
            assertEquals(fill, usage.background)
            for (gradient in listOf(side.gradient, assistant.gradient, agent.gradient, usage.sheet)) {
                gradient.forEach { assertEquals(fill, it) }
            }
            for (text in listOf(side.text, side.muted, side.toolbar, side.accent,
                assistant.text, assistant.muted, assistant.toolbar, assistant.accent,
                agent.text, agent.muted, agent.accent, agent.taskMuted,
                usage.text, usage.muted, usage.toolbar, usage.accent)) {
                assertTrue("dark=$dark fill=$fill text=$text", contrast(text, fill) >= 4.5f)
            }
            val actions = resolveAppearanceActions(appearance, dark)
            assertTrue(contrast(actions.onSecondary, actions.secondaryContainer) >= 4.5f)
            val bubble = resolveUserBubbleColors(appearance, dark)
            assertTrue(contrast(bubble.content, bubble.container) >= 4.5f)
        }
    }

    @Test fun customActionRolesKeepSelectedAccentsAndOriginalFilledForeground() {
        for (dark in listOf(false, true)) for (accent in listOf(Color.Black, Color.White, Color.Yellow, Color(0xFF767676))) {
            val appearance = AppearanceSettings(primaryAccentColor = accent.toArgb(), secondaryAccentColor = accent.toArgb(),
                paletteSeedColor = 0xFF449977.toInt(), paletteStyle = AppearancePaletteStyle.VIBRANT)
            val actions = resolveAppearanceActions(appearance, dark)
            assertEquals(accent, actions.primary)
            assertEquals(resolveAppearanceActions(AppearanceSettings(), dark).onPrimary, actions.onPrimary)
            assertEquals(accent, actions.onSecondary)
            assertEquals(accent, actions.onPrimaryContainer)
            val theme = resolveAppearanceColors(appearance, dark)
            assertEquals(accent, theme.secondary)
            assertEquals((if (dark) DaiyuPalette.dark else DaiyuPalette.light).onSecondary, theme.onSecondary)
            assertEquals(accent, theme.onTertiaryContainer)
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
            assertEquals(Color.Red, changed.selectionContent)
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
            assertEquals(destructiveColors().container,
                app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette(dark, changed).dangerContainer)
            assertTrue(contrast(destructiveColors().content, destructiveColors().container) >= 4.5f)
            assertEquals(resolveAppearanceColors(base, dark).primaryContainer,
                resolveAppearanceColors(base.copy(secondaryAccentColor = Color.Green.toArgb()), dark).primaryContainer)
        }
    }

}
