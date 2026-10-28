package app.daiyu.android.ui.app

import androidx.compose.ui.graphics.Color
import app.daiyu.android.ui.components.UsagePalette
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionAccentTest {
    @Test fun defaultMatchesExistingSelectionForegroundInBothThemes() {
        for (dark in listOf(false, true)) {
            val expected = Color(if (dark) 0xFFC6B6D5 else 0xFF82718F)
            assertEquals(expected, resolveSelectionAccent(dark))
            assertEquals(expected, UsagePalette(dark).accent)
            assertEquals(expected, UsagePalette(dark).primary)
        }
    }

}
