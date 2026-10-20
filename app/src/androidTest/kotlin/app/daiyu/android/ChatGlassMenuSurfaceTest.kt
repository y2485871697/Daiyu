package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatGlassMenuSurface
import kotlin.math.roundToInt

class ChatGlassMenuSurfaceTest : WiredActivityTestCase() {
    fun testInsetHighlightAndOutsideShadowKeepMenuGeometryInBothThemes() {
        for (dark in listOf(false, true)) {
            var density = 1f
            val background = if (dark) Color(0xFF282330) else Color(0xFFF8F5FC)
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        density = LocalDensity.current.density
                        Box(Modifier.fillMaxSize().background(background).testTag("menu-surface-host"),
                            contentAlignment = Alignment.Center) {
                            ChatGlassMenuSurface(null, Modifier.requiredSize(156.dp, 120.dp).testTag("menu-surface")) {}
                        }
                    }
                }
            }
            val menu = ui.onNodeWithTag("menu-surface")
            menu.assertWidthIsEqualTo(156.dp).assertHeightIsEqualTo(120.dp)
            val pixels = menu.captureToImage().toPixelMap()
            val x = pixels.width / 2
            val top = pixels[x, (1.5f * density).toInt()]
            val fill = pixels[x, (4f * density).roundToInt()]
            assertTrue("top inset highlight, dark=$dark", top.luminance() > fill.luminance())
            val side = pixels[(1.5f * density).toInt(), pixels.height / 2]
            val sideFill = pixels[(4f * density).roundToInt(), pixels.height / 2]
            if (dark) assertTrue("dark inset rim", side.luminance() > sideFill.luminance())
            else assertTrue("light inset rim", side.luminance() < sideFill.luminance())

            val host = ui.onNodeWithTag("menu-surface-host")
            val bounds = menu.fetchSemanticsNode().boundsInRoot
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            val image = host.captureToImage().toPixelMap()
            val sx = (bounds.center.x - hostBounds.left).roundToInt()
            val sy = (bounds.bottom - hostBounds.top + 3f * density).roundToInt()
            assertTrue("shadow is visible outside the menu, dark=$dark",
                image[sx, sy].luminance() < background.luminance())
        }
    }
}
