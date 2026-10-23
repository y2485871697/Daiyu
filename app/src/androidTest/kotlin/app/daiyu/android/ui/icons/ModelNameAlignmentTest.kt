package app.daiyu.android.ui.icons

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPicker
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerState
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi
import app.daiyu.android.ui.screens.settings.SettingsRow
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ModelNameAlignmentTest : WiredActivityTestCase() {
    private var density = 1f

    private fun bounds(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true)
        .assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    fun testSharedCorrectionSupportsBothTextLibrariesWithoutMovingTouchRowOrIcon() {
        for (dark in listOf(false, true)) for (font in listOf(1f, 1.3f)) {
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                        density = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(density, font)) {
                            Column {
                                for (material in listOf(false, true)) {
                                    Row(Modifier.width(280.dp).heightIn(min = 48.dp).testTag("row:$material"),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        ModelBrandIcon("gpt-6.1-sol", Modifier.size(22.dp).testTag("icon:$material"),
                                            tint = MiuixTheme.colorScheme.onSurface)
                                        val modifier = Modifier.modelNameOpticalAlignment().testTag("name:$material")
                                        if (material) androidx.compose.material3.Text("gpt-6.1-sol", modifier, maxLines = 1)
                                        else top.yukonga.miuix.kmp.basic.Text("gpt-6.1-sol", modifier, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (material in listOf(false, true)) {
                val row = bounds("row:$material")
                val icon = bounds("icon:$material")
                val name = bounds("name:$material")
                assertEquals(48f, row.height / density, .5f)
                assertEquals(22f, icon.height / density, .5f)
                assertEquals(row.center.y, icon.center.y, 1f)
                assertEquals(icon.center.y - density.roundToInt(), name.center.y, 1f)
                assertTrue(name.left >= icon.right - 1f)
            }
        }
    }

    fun testActualPickerKeepsSingleAndTwoLineNameBlocksAtSameOpticalOffset() {
        for (dark in listOf(false, true)) for (font in listOf(1f, 1.3f)) {
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                        density = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(density, font)) {
                            ChatModelPicker(ChatModelPickerState("gpt-6.1-sol", listOf(
                                ModelCatalogEntryUi("gpt-6.1-sol"),
                                ModelCatalogEntryUi("gpt-6-astra", "工作模型"),
                            )), {}, {})
                        }
                    }
                }
            }
            for ((id, hasApiId) in listOf("gpt-6.1-sol" to false, "gpt-6-astra" to true)) {
                node("chat-model-list").performScrollToKey("model:current:$id")
                val row = bounds("chat-model-option:$id")
                val icon = bounds("chat-model-brand:current:$id")
                val name = bounds("chat-model-name:current:$id")
                val contentBottom = if (hasApiId) bounds("chat-model-id:current:$id").bottom else name.bottom
                assertEquals(icon.center.y - density.roundToInt(), (name.top + contentBottom) / 2f, 1f)
                assertEquals(row.center.y, icon.center.y, 1f)
                assertEquals(22f, icon.width / density, .5f)
                assertTrue(row.height / density >= 55.5f)
                if (hasApiId) assertTrue(name.bottom <= bounds("chat-model-id:current:$id").top)
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testSettingsCorrectionIsOptInAndPreservesDefaultRowsAndClickTargets() {
        for (dark in listOf(false, true)) {
            var clicks = 0
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                        density = LocalDensity.current.density
                        Column(Modifier.width(280.dp)) {
                            for (corrected in listOf(false, true)) {
                                SettingsRow("gpt-6.1-sol", tag = "settings:$corrected", onClick = { clicks++ },
                                    leading = { ModelBrandIcon("gpt-6.1-sol", Modifier.size(24.dp).testTag("settings-icon:$corrected"),
                                        tint = MiuixTheme.colorScheme.onSurface) },
                                    textModifier = if (corrected) Modifier.modelNameOpticalAlignment().testTag("settings-name:$corrected")
                                        else Modifier.testTag("settings-name:$corrected"))
                            }
                        }
                    }
                }
            }
            for (corrected in listOf(false, true)) {
                val row = bounds("settings:$corrected")
                val icon = bounds("settings-icon:$corrected")
                val name = bounds("settings-name:$corrected")
                assertEquals(64f, row.height / density, .5f)
                assertEquals(row.center.y, icon.center.y, 1f)
                assertEquals(icon.center.y - if (corrected) density.roundToInt() else 0, name.center.y, 1f)
                node("settings:$corrected").assertHasClickAction().performClick()
            }
            assertEquals(2, clicks)
        }
    }
}
