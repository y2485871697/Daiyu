package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import app.daiyu.android.ui.components.UsagePalette
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import org.junit.Assert.assertEquals
import org.junit.Test

class AccentForegroundTest {
    @Test fun defaultButtonRolesKeepOriginalForegroundInBothThemes() {
        for (dark in listOf(false, true)) {
            val actions = resolveAppearanceActions(dark)
            assertEquals(if (dark) Color(0xFF261F30) else Color.White, actions.onPrimary)
            assertEquals(resolveSelectionAccent(dark), actions.onSecondary)
            assertEquals(actions.primary, AssistantPreviewPalette(dark).primary)
            assertEquals(actions.onPrimary, AssistantPreviewPalette(dark).onPrimary)
            assertEquals(Color(if (dark) 0xff211735 else 0xffffffff), UsagePalette(dark).onPrimary)
        }
    }
    @Test fun switchThumbRemainsWhite() { assertEquals(Color.White, DaiyuPalette.switchThumb) }
}
