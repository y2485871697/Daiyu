package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.components.ConversationPanePreviewPalette
import app.daiyu.android.ui.components.UsagePalette
import app.daiyu.android.ui.components.subagent.ConversationAgentColors
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import org.junit.Assert.assertEquals
import org.junit.Test

class AccentForegroundTest {
    private val accents = listOf(Color(0xFFE6C7F2), Color.White, Color.Yellow, Color.Black, Color(0xFF407FCE))

    @Test fun customFilledButtonsKeepTheirOriginalThemeForeground() {
        for (dark in listOf(false, true)) for (accent in accents) for (surface in listOf(null, 0xFF449977.toInt())) {
            val settings = AppearanceSettings(primaryAccentColor = accent.toArgb(), secondaryAccentColor = accent.toArgb(), paletteSeedColor = surface)
            val theme = resolveAppearanceColors(settings, dark)
            val original = if (dark) DaiyuPalette.dark else DaiyuPalette.light
            assertEquals(accent, theme.primary)
            assertEquals(accent, theme.secondary)
            assertEquals(original.onPrimary, theme.onPrimary)
            assertEquals(original.onPrimaryVariant, theme.onPrimaryVariant)
            assertEquals(original.onSecondary, theme.onSecondary)
            assertEquals(original.disabledOnPrimary, theme.disabledOnPrimary)
            assertEquals(original.disabledOnPrimaryButton, theme.disabledOnPrimaryButton)
            assertEquals(original.disabledOnSecondaryVariant, theme.disabledOnSecondaryVariant)
            assertEquals(resolveAppearanceActions(AppearanceSettings(), dark).onPrimary,
                resolveAppearanceActions(settings, dark).onPrimary)
            assertEquals(UsagePalette(dark).onPrimary, UsagePalette(dark, settings).onPrimary)
        }
    }

    @Test fun customAccentTypographyRetainsExactSelectedRgb() {
        for (dark in listOf(false, true)) for (primary in accents) for (surface in listOf(null, 0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
            val secondary = Color(0xFFD2E9BD)
            val settings = AppearanceSettings(primaryAccentColor = primary.toArgb(), secondaryAccentColor = secondary.toArgb(), paletteSeedColor = surface)
            val theme = resolveAppearanceColors(settings, dark)
            val actions = resolveAppearanceActions(settings, dark)
            val usage = UsagePalette(dark, settings)
            assertEquals(primary, theme.onPrimaryContainer)
            assertEquals(primary, actions.onPrimaryContainer)
            assertEquals(secondary, theme.onSecondaryVariant)
            assertEquals(secondary, theme.onSecondaryContainer)
            assertEquals(secondary, theme.onSecondaryContainerVariant)
            assertEquals(secondary, theme.onTertiaryContainer)
            assertEquals(secondary, actions.onSecondary)
            assertEquals(primary, usage.accent)
            assertEquals(primary, usage.selectionContent)
            assertEquals(secondary, usage.onSecondaryContainer)
            assertEquals(primary, ConversationPanePreviewPalette(dark, settings).accent)
            assertEquals(primary, AssistantPreviewPalette(dark, settings).accent)
            val agent = ConversationAgentColors(dark, settings)
            assertEquals(primary, agent.accent)
            assertEquals(primary, agent.strong)
            assertEquals(secondary, agent.onSoft)
        }
    }

    @Test fun switchThumbKeepsItsOriginalWhiteRole() {
        assertEquals(Color.White, DaiyuPalette.switchThumb)
    }

    @Test fun changingAccentLeavesBodyBubbleAndDangerRolesAlone() {
        for (dark in listOf(false, true)) for (surface in listOf(null, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val before = AppearanceSettings(paletteSeedColor = surface)
            val after = before.copy(primaryAccentColor = 0xFFE6C7F2.toInt(), secondaryAccentColor = 0xFFD2E9BD.toInt())
            val base = resolveAppearanceColors(before, dark)
            val changed = resolveAppearanceColors(after, dark)
            assertEquals(base.surface, changed.surface)
            assertEquals(base.onSurface, changed.onSurface)
            assertEquals(base.errorContainer, changed.errorContainer)
            assertEquals(base.onErrorContainer, changed.onErrorContainer)
            assertEquals(resolveUserBubbleColors(before, dark), resolveUserBubbleColors(after, dark))
        }
    }
}
