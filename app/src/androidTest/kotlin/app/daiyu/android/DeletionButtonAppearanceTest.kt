package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.MiuixDialogActions
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton

class DeletionButtonAppearanceTest : WiredActivityTestCase() {
    fun testSharedDeletionStylesStayRedWhiteAndKeepDisabledCallbacksInBothThemes() {
        for (dark in listOf(false, true)) for (pureBlack in listOf(false, true)) {
            val enabled = mutableStateOf(true)
            val clicks = IntArray(2)
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                    pureBlackEnabled = pureBlack), applyInterfaceScale = false) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().background(Color.Magenta)) {
                        MiuixDialogActions("删除", {}, { clicks[0]++ }, Modifier.width(280.dp),
                            confirmEnabled = enabled.value, destructive = true, deletionAppearance = true)
                        AssistantPreviewButton("删除", { clicks[1]++ }, Modifier.width(200.dp).testTag("assistant-delete"),
                            danger = true, deletionAppearance = true, enabled = enabled.value)
                    }
                }
            } }
            for (tag in listOf("dialog-confirm", "assistant-delete")) {
                val target = node(tag).assertIsEnabled().assertHeightIsAtLeast(44.dp)
                val pixels = captureScreenNode(target).toPixelMap()
                var red = 0
                var white = 0
                val expected = Color(0xFF92454F)
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    if (kotlin.math.abs(pixel.red - expected.red) < .025f && kotlin.math.abs(pixel.green - expected.green) < .025f &&
                        kotlin.math.abs(pixel.blue - expected.blue) < .025f) red++
                    if (pixel.red > .97f && pixel.green > .97f && pixel.blue > .97f) white++
                }
                assertTrue("original red $tag dark=$dark pureBlack=$pureBlack", red > pixels.width * pixels.height * .35f)
                assertTrue("white label $tag", white > 8)
                target.performClick()
            }
            ui.runOnIdle { assertEquals(1, clicks[0]); assertEquals(1, clicks[1]); enabled.value = false }
            for (tag in listOf("dialog-confirm", "assistant-delete")) {
                node(tag).assertIsNotEnabled().performTouchInput { click() }
            }
            ui.runOnIdle { assertEquals(1, clicks[0]); assertEquals(1, clicks[1]) }
        }
    }
}
