package app.daiyu.android

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatComposerSurface
import app.daiyu.android.ui.screens.chat.conversation.ChatConversationLayers
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

/** Pixel evidence: translucent tint alone leaves these stripes sharply visible. */
class ChatGlassBackdropActivityTest : WiredActivityTestCase() {
    fun testComposerBlursTheMessagesBehindItInBothThemes() {
        if (Build.VERSION.SDK_INT < 33 || !isRuntimeShaderSupported()) return
        for (theme in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            mainThread { activity.setContent { Fixture(theme) } }
            ui.waitForIdle()
            val image = ui.onNodeWithTag("chat-composer-surface").captureToImage().toPixelMap()
            val y = image.height / 2
            val values = (image.width / 4 until image.width * 3 / 4).map { x ->
                val pixel = image[x, y]
                (pixel.red + pixel.green + pixel.blue) / 3f
            }
            assertTrue("Glass must sample actual background, theme=$theme", values.isNotEmpty())
            val spread = values.max() - values.min()
            assertTrue("Sharp stripes remain behind composer ($theme, spread=$spread)", spread < .30f)
            assertTrue("Glass must not be an opaque cover ($theme)", values.average() in .15.. .90)
        }
    }

    fun testComposerTintStaysVisibleWithoutBackdropInBothThemes() {
        for (dark in listOf(false, true)) {
            val background = if (dark) Color.Black else Color.White
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                    Box(Modifier.fillMaxSize().background(background).testTag("composer-tint-host")) {
                        ChatComposerSurface { Box(Modifier.fillMaxWidth().height(96.dp)) }
                    }
                }
            } }
            awaitVisualIdle()
            val host = node("composer-tint-host")
            val bounds = host.fetchSemanticsNode().boundsInRoot
            val surface = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
            val pixels = host.captureToImage().toPixelMap()
            val actual = pixels[(surface.center.x - bounds.left).toInt(), (surface.center.y - bounds.top).toInt()]
            val expected = (if (dark) Color(0xFF1F1C29).copy(alpha = .36f)
                else Color(0xFFF1EFF5).copy(alpha = .32f)).compositeOver(background)
            assertEquals("composer red dark=$dark", expected.red, actual.red, .015f)
            assertEquals("composer green dark=$dark", expected.green, actual.green, .015f)
            assertEquals("composer blue dark=$dark", expected.blue, actual.blue, .015f)
        }
    }

    @Composable
    private fun Fixture(theme: AppearanceThemeMode) {
        AgentAppTheme(AppearanceSettings(themeMode = theme), false) {
            ChatConversationLayers(messages = {
                Canvas(Modifier.fillMaxSize()) {
                    val stripe = 4.dp.toPx()
                    var x = 0f
                    var index = 0
                    while (x < size.width) {
                        drawRect(if (index++ % 2 == 0) Color.Black else Color.White,
                            topLeft = Offset(x, 0f), size = Size(stripe, size.height))
                        x += stripe
                    }
                }
            }, composer = { backdrop ->
                ChatComposerSurface(backdrop) { Box(Modifier.fillMaxWidth().height(96.dp)) }
            })
        }
    }
}
