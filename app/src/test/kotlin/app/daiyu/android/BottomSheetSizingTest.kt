package app.daiyu.android

import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.defaultBottomSheetHeight
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomSheetSizingTest {
    @Test fun defaultHeightFollowsAssistantAndClampsToTheSafeViewport() {
        for ((available, expected) in listOf(
            0 to 0, 10 to 0, 20 to 0, 100 to 80, 280 to 260, 320 to 300,
            340 to 300, 348 to 300, 400 to 352, 568 to 520, 600 to 520, 1000 to 520,
        )) {
            assertEquals("safe viewport: $available dp", expected.dp, defaultBottomSheetHeight(available.dp))
        }
    }
}
