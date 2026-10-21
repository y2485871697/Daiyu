package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ProviderSettingsThemeTest : WiredActivityTestCase() {
    fun testProviderControlsUseTheSessionCollaborationAccentInBothThemes() {
        for (dark in listOf(false, true)) {
            var checked = false
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                        ProviderSettingsTheme {
                            val palette = AssistantPreviewPalette(dark)
                            val miuix = MiuixTheme.colorScheme
                            val material = MaterialTheme.colorScheme
                            SideEffect {
                                assertEquals(palette.primary, miuix.primary)
                                assertEquals(palette.onPrimary, miuix.onPrimary)
                                assertEquals(palette.soft, miuix.primaryContainer)
                                assertEquals(palette.accent, miuix.onPrimaryContainer)
                                assertEquals(palette.primary, material.primary)
                                assertEquals(palette.onPrimary, material.onPrimary)
                                assertEquals(palette.soft, material.primaryContainer)
                                assertEquals(palette.accent, material.onPrimaryContainer)
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
