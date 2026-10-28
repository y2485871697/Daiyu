package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.OptionSelectionItem
import app.daiyu.android.ui.components.UsagePalette
import top.yukonga.miuix.kmp.theme.MiuixTheme
import app.daiyu.android.ui.screens.settings.LocalSettingsPageStyle
import app.daiyu.android.ui.screens.settings.SettingsCard
import app.daiyu.android.ui.screens.settings.SettingsScaffoldPage

class VoiceSettingsPaletteActivityTest : WiredActivityTestCase() {
    private var settingsStyle = false

    private fun mount(appearance: AppearanceSettings, reference: Boolean, title: String = "我的声音") {
        settingsStyle = false
        val visit = Any()
        mainThread { activity.setContent { key(visit) {
            AgentAppTheme(appearance, applyInterfaceScale = false) {
                Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("voice-theme-sample")) {
                    if (reference) SettingsScaffoldPage(title, onBack = {}) {
                        item { SettingsCard(Modifier.testTag("voice-theme-card")) { cardContent() } }
                    } else VoicePersonalScaffold(title, onBack = {}, enabled = true) {
                        item { VoiceSettingsCard(Modifier.testTag("voice-theme-card")) { cardContent() } }
                    }
                }
            }
        } } }
        awaitUniqueTag("voice-theme-card")
        awaitVisualIdle()
    }

    @Composable
    private fun cardContent() {
        val grouped = LocalSettingsPageStyle.current
        SideEffect { settingsStyle = grouped }
        Spacer(Modifier.height(64.dp))
    }

    private fun sample(tag: String, background: Boolean): Color {
        val pixels = node(tag).captureToImage().toPixelMap()
        return pixels[if (background) 2 else pixels.width / 2, pixels.height / 2]
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .025f)
        assertEquals(expected.green, actual.green, .025f)
        assertEquals(expected.blue, actual.blue, .025f)
    }

    fun testPersonalRoutesShareSettingsBackgroundCardsAndGeometryAcrossThemeChanges() {
        for (dark in listOf(false, true)) for (seed in listOf(null, 0xFF497B85.toInt())) {
            for (pureBlack in if (dark) listOf(false, true) else listOf(false)) {
                val appearance = AppearanceSettings(
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                    paletteSeedColor = seed, pureBlackEnabled = pureBlack,
                    primaryAccentColor = 0xFFAD4168.toInt(),
                )
                mount(appearance, reference = true)
                val expectedBackground = sample("voice-theme-sample", background = true)
                if (dark && pureBlack) assertColor(Color.Black, expectedBackground)
                val expectedCard = sample("voice-theme-card", background = false)
                val expectedBounds = node("voice-theme-card").fetchSemanticsNode().boundsInRoot
                for (title in listOf("我的声音", "声音复刻账户", "同步音色名额", "导入声音", "制作声音")) {
                    mount(appearance, reference = false, title = title)
                    assertTrue("$title enables the settings card style", settingsStyle)
                    assertColor(expectedBackground, sample("voice-theme-sample", background = true))
                    assertColor(expectedCard, sample("voice-theme-card", background = false))
                    val actual = node("voice-theme-card").fetchSemanticsNode().boundsInRoot
                    assertEquals(expectedBounds.left, actual.left, 1f)
                    assertEquals(expectedBounds.width, actual.width, 1f)
                }
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testUnconfiguredHomeSharesSettingsBackgroundAndKeepsItsActions() {
        for (dark in listOf(false, true)) {
            val appearance = AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
            )
            mount(appearance, reference = true)
            val expected = sample("voice-theme-sample", background = true)
            var configured = 0
            mainThread { activity.setContent {
                AgentAppTheme(appearance, applyInterfaceScale = false) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("voice-theme-sample")) {
                        VoicePersonalEmptyPage(isCurrent = { true }, onConfigure = { configured++ }, onBack = {})
                    }
                }
            } }
            awaitUniqueTag("voice-account-empty")
            awaitVisualIdle()
            assertColor(expected, sample("voice-theme-sample", background = true))
            node("voice-account-menu").assertIsDisplayed()
            node("voice-account-add").assertIsDisplayed().performClick()
            assertEquals(1, configured)
            node("voice-account-configure").performClick()
            assertEquals(2, configured)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testQuotaChoicesUseSettingsCardFillAndKeepSelectionAndDisabledActions() {
        for (dark in listOf(false, true)) for (seed in listOf(null, 0xFF497B85.toInt())) {
            val selected = mutableStateOf(false)
            var choices = 0
            val appearance = AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                paletteSeedColor = seed,
            )
            mainThread { activity.setContent {
                AgentAppTheme(appearance, applyInterfaceScale = false) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
                        SettingsCard(Modifier.testTag("quota-fill-reference")) { Spacer(Modifier.height(64.dp)) }
                        OptionSelectionItem("名额", selected.value, cardStyle = true,
                            modifier = Modifier.padding(horizontal = 16.dp).testTag("quota-fill-choice")) {
                            choices++; selected.value = true
                        }
                        OptionSelectionItem("不可用", selected = false, cardStyle = true, enabled = false,
                            modifier = Modifier.testTag("quota-disabled")) { choices++ }
                    }
                }
            } }
            awaitUniqueTag("quota-fill-choice")
            awaitVisualIdle()
            fun fill(tag: String): Color {
                val pixels = node(tag).captureToImage().toPixelMap()
                return pixels[pixels.width * 3 / 4, pixels.height / 2]
            }
            assertColor(fill("quota-fill-reference"), fill("quota-fill-choice"))
            node("quota-fill-choice").assertIsNotSelected().performClick().assertIsSelected()
            assertColor(UsagePalette(dark, appearance).selectionContainer, fill("quota-fill-choice"))
            assertEquals(1, choices)
            node("quota-disabled").assertIsNotEnabled().performTouchInput { click() }
            assertEquals(1, choices)
        }
        assertEquals(0, fixture.opens.get())
    }

}
