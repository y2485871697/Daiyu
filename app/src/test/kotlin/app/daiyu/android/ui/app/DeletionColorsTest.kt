package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionColorsTest {
    @Test fun deletionUsesOriginalReferenceRedWithWhiteContent() {
        val colors = destructiveColors()
        assertEquals(Color(0xFF92454F), colors.container)
        assertEquals(Color.White, colors.content)
        assertEquals(colors.container, colors.border)
        val contrast = (colors.content.luminance() + .05f) / (colors.container.luminance() + .05f)
        assertTrue("white labels stay readable: $contrast", contrast >= 4.5f)
    }

    @Test fun customAccentsAndSurfacesDoNotRecolorDeletionOrGeneralErrors() {
        for (dark in listOf(false, true)) for (color in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF00FF00.toInt())) {
            val appearance = AppearanceSettings(primaryAccentColor = color, secondaryAccentColor = color, paletteSeedColor = color)
            val deletion = AssistantPreviewPalette(dark, appearance)
            assertEquals(Color(0xFF92454F), deletion.dangerContainer)
            assertEquals(Color.White, deletion.onDangerContainer)
            val original = if (dark) DaiyuPalette.dark else DaiyuPalette.light
            val theme = resolveAppearanceColors(appearance, dark)
            assertEquals(original.error, theme.error)
            assertEquals(original.errorContainer, theme.errorContainer)
            assertEquals(original.onErrorContainer, theme.onErrorContainer)
        }
    }
}
