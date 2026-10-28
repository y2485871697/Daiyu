package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
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

    @Test fun pureBlackChangesOnlyDarkBackgroundAndSurface() {
        for (dark in listOf(false, true)) for (black in listOf(false, true)) {
            val base = if (dark) DaiyuPalette.dark else DaiyuPalette.light
            val resolved = resolveAppearanceColors(AppearanceSettings(pureBlackEnabled = black), dark)
            assertEquals(if (dark && black) Color.Black else base.background, resolved.background)
            assertEquals(if (dark && black) Color.Black else base.surface, resolved.surface)
            assertEquals(base.primary, resolved.primary)
            assertEquals(base.secondary, resolved.secondary)
            assertEquals(base.onSurface, resolved.onSurface)
            assertEquals(base.surfaceContainer, resolved.surfaceContainer)
            assertEquals(base.outline, resolved.outline)
        }
    }
}
