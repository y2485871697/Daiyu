package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import app.daiyu.android.ui.components.subagent.conversationAgentColors
import app.daiyu.android.ui.screens.settings.ProviderSettingsTabs
import app.daiyu.android.ui.screens.settings.ProviderSettingsTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ProviderSettingsTabsActivityTest : WiredActivityTestCase() {
    fun testInactiveTabsMatchUseCurrentConfigurationAndKeepSelectionAndDisabledBehavior() {
        val saved = fixture.settings.load()
        val appearances = listOf(AppearanceSettings(), AppearanceSettings(pureBlackEnabled = true))
        for (dark in listOf(false, true)) for (appearance in appearances) {
            val selected = mutableStateOf(false)
            val enabled = mutableStateOf(true)
            val selections = mutableListOf<Boolean>()
            var background = Color.Unspecified
            var primary = Color.Unspecified
            var onPrimary = Color.Unspecified
            var secondary = Color.Unspecified
            var onSecondary = Color.Unspecified
            var disabled = Color.Unspecified
            var onDisabled = Color.Unspecified
            var disabledPrimary = Color.Unspecified
            var onDisabledPrimary = Color.Unspecified
            mainThread {
                activity.setContent {
                    AgentAppTheme(appearance.copy(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                        ProviderSettingsTheme {
                            val colors = conversationAgentColors()
                            val defaultButton = ButtonDefaults.textButtonColors()
                            val primaryButton = ButtonDefaults.textButtonColorsPrimary()
                            background = MiuixTheme.colorScheme.surface
                            primary = primaryButton.color
                            onPrimary = primaryButton.textColor
                            secondary = colors.soft
                            onSecondary = colors.onSoft
                            disabled = defaultButton.disabledColor
                            onDisabled = defaultButton.disabledTextColor
                            disabledPrimary = primaryButton.disabledColor
                            onDisabledPrimary = primaryButton.disabledTextColor
                            Box(Modifier.fillMaxSize().background(background).systemBarsPadding()) {
                                Column(Modifier.fillMaxSize().testTag("provider-tabs-host")) {
                                    ProviderSettingsTabs(selected.value, enabled.value) {
                                        selections += it
                                        selected.value = it
                                    }
                                    ConversationAgentButton("使用当前配置", modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 20.dp).testTag("reference-secondary"), onClick = {})
                                }
                            }
                        }
                    }
                }
            }
            ui.waitForIdle()
            val host = node("provider-tabs-host")
            fun assertFill(tag: String, expected: Color) {
                val bounds = node(tag).fetchSemanticsNode().boundsInRoot
                val hostBounds = host.fetchSemanticsNode().boundsInRoot
                val pixels = captureScreenNode(host).toPixelMap()
                val inset = 6f * activity.resources.displayMetrics.density
                val composed = expected.compositeOver(background)
                for (x in listOf(bounds.left + inset, bounds.right - inset)) {
                    val actual = pixels[(x - hostBounds.left).toInt(), (bounds.center.y - hostBounds.top).toInt()]
                    assertTrue("fill $tag dark=$dark appearance=$appearance expected=$composed actual=$actual",
                        kotlin.math.abs(actual.red - composed.red) < .02f &&
                            kotlin.math.abs(actual.green - composed.green) < .02f &&
                            kotlin.math.abs(actual.blue - composed.blue) < .02f)
                }
            }
            fun assertText(title: String, expected: Color) {
                val layouts = mutableListOf<TextLayoutResult>()
                ui.onNodeWithText(title, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                assertEquals(expected, layouts.single().layoutInput.style.color)
            }
            for (modelTab in listOf(false, true, false)) {
                node("settings-radio:${if (modelTab) "模型" else "配置"}").performClick()
                awaitVisualIdle()
                node("settings-radio:${if (modelTab) "模型" else "配置"}").assertIsSelected()
                node("settings-radio:${if (modelTab) "配置" else "模型"}").assertIsNotSelected()
                for (title in listOf("配置", "模型")) {
                    val active = modelTab == (title == "模型")
                    node("settings-radio:$title").assertIsEnabled().assertHeightIsAtLeast(48.dp)
                        .assertWidthIsAtLeast(48.dp).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                    assertFill("settings-radio:$title", if (active) primary else secondary)
                    assertText(title, if (active) onPrimary else onSecondary)
                }
                assertFill("reference-secondary", secondary)
                assertText("使用当前配置", onSecondary)
            }
            assertEquals(listOf(false, true, false), selections)
            mainThread { enabled.value = false }
            awaitVisualIdle()
            for (title in listOf("配置", "模型")) {
                node("settings-radio:$title").assertIsNotEnabled().performTouchInput { click() }
                assertFill("settings-radio:$title", if (title == "配置") disabledPrimary else disabled)
                assertText(title, if (title == "配置") onDisabledPrimary else onDisabled)
            }
            assertEquals(listOf(false, true, false), selections)
            node("settings-radio:配置").assertIsSelected()
        }
        assertEquals(saved, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
}
