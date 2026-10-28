package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.components.UsagePalette
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionAccentTest {
    @Test fun defaultMatchesExistingSelectionForegroundInBothThemes() {
        for (dark in listOf(false, true)) {
            val expected = Color(if (dark) 0xFFC6B6D5 else 0xFF82718F)
            assertEquals(expected, resolveSelectionAccent(AppearanceSettings(), dark))
            assertEquals(expected, UsagePalette(dark).accent)
            assertEquals(expected, UsagePalette(dark).primary)
        }
    }

    @Test fun customAccentPreservesExactColorWithoutContrastRemapping() {
        for (dark in listOf(false, true)) for (accent in listOf(0xFFE6C7F2, 0xFFFFFFFF, 0xFF000000, 0xFF31886F, 0x8031886F)) {
            val settings = AppearanceSettings(primaryAccentColor = accent.toInt())
            assertEquals(Color(accent.toInt()), resolveSelectionAccent(settings, dark))
            assertEquals(resolveSelectionAccent(settings, dark), UsagePalette(dark, settings).accent)
        }
    }

    @Test fun secondaryAndSurfaceChoicesDoNotReplacePrimarySelectionAccent() {
        for (dark in listOf(false, true)) for (surface in listOf(null, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val settings = AppearanceSettings(paletteSeedColor = surface, secondaryAccentColor = 0xFFE3A32D.toInt())
            assertEquals(resolveSelectionAccent(AppearanceSettings(), dark), resolveSelectionAccent(settings, dark))
            val custom = settings.copy(primaryAccentColor = 0xFF3D876C.toInt())
            assertEquals(Color(0xFF3D876C), resolveSelectionAccent(custom, dark))
        }
    }
}
