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

    @Test fun defaultThemesKeepDeletionAndGeneralErrorRolesSeparate() {
        for (dark in listOf(false, true)) {
            val appearance = AppearanceSettings()
            val deletion = AssistantPreviewPalette(dark)
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
