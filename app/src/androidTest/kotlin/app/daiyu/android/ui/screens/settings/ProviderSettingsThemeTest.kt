package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.daiyuInputContainerColor
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ProviderSettingsThemeTest : WiredActivityTestCase() {
    fun testProviderControlsUseTheSessionCollaborationAccentInBothThemes() {
        for (dark in listOf(false, true)) {
            var checked = false
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                        val parentColors = MiuixTheme.colorScheme
                        ProviderSettingsTheme {
                            val palette = AssistantPreviewPalette(dark)
                            val miuix = MiuixTheme.colorScheme
                            val material = MaterialTheme.colorScheme
                            val inputContainer = daiyuInputContainerColor()
                            SideEffect {
                                assertEquals(palette.primary, miuix.primary)
                                assertEquals(palette.onPrimary, miuix.onPrimary)
                                assertEquals(palette.soft, miuix.primaryContainer)
                                assertEquals(palette.accent, miuix.onPrimaryContainer)
                                assertEquals(palette.primary, material.primary)
                                assertEquals(palette.onPrimary, material.onPrimary)
                                assertEquals(palette.soft, material.primaryContainer)
                                assertEquals(palette.accent, material.onPrimaryContainer)
                                assertEquals(miuix.surfaceContainerHigh, inputContainer)
                                assertEquals(if (dark) Color(0xFF242130) else Color(0xFFF4F0FB), inputContainer)
                                assertEquals(parentColors.surface, miuix.surface)
                                assertEquals(parentColors.surfaceVariant, miuix.surfaceVariant)
                                assertTrue("input fill stays distinct from the original surface", if (dark)
                                    inputContainer.luminance() > miuix.surfaceVariant.luminance()
                                    else inputContainer.luminance() < miuix.surfaceVariant.luminance())
                                checked = true
                            }
                        }
                    }
                }
            }
            ui.waitForIdle()
            mainThread { assertTrue("theme values were composed", checked) }
        }
        assertEquals(0, fixture.opens.get())
    }
}
