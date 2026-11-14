package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.*
import java.util.concurrent.atomic.AtomicInteger

class RenamePresentationActivityTest : WiredActivityTestCase() {
    private val saves = AtomicInteger()
    private val dismissals = AtomicInteger()
    private var savedName: String? = null

    private fun mount(kind: Int, dark: Boolean = false) {
        val visit = Any()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) { key(visit) {
                var open by remember { mutableStateOf(true) }
                var name by remember { mutableStateOf("原名称") }
                val save: (String) -> Unit = { savedName = it; saves.incrementAndGet(); open = false }
                val dismiss: () -> Unit = { dismissals.incrementAndGet(); open = false }
                if (open) when (kind) {
                    0 -> WorkspaceNameDialog("重命名", "原名称", "文件夹名称", "保存", save, dismiss)
                    1 -> DaiyuTextInputDialog("重命名", "原名称", "名称", "保存", save, dismiss,
                        preview = true, nameStyle = true)
                    else -> SubAgentPresetNameDialog("重命名", name, { name = it }, name.trim().isNotBlank(),
                        dismiss, { save(name) }, fieldHint = if (kind == 2) "组名称" else "名称", renameStyle = true)
                }
            } }
        } }
        awaitUniqueTag("prompt-input")
        awaitNodeWindowFocus(node("prompt-input"))
        awaitDialogEnter(node("prompt-input"))
        ui.waitForIdle()
    }

    fun testAllRenameSurfacesShareFieldButtonsAndNoHeaderClose() {
        for (dark in listOf(false, true)) {
            var expectedWidth = 0f
            var expectedHeight = 0f
            for (kind in 0..3) {
                mount(kind, dark)
                node("chat-selection-sheet").assertExists()
                ui.onNodeWithText("重命名").assertIsDisplayed()
                ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
                ui.onNodeWithText("名称").assertDoesNotExist()
                ui.onNodeWithText("组名称").assertDoesNotExist()
                node("prompt-input").assertTextContains("原名称")
                val field = node("prompt-input").fetchSemanticsNode().boundsInRoot
                if (kind == 0) { expectedWidth = field.width; expectedHeight = field.height }
                else { assertEquals(expectedWidth, field.width, 1f); assertEquals(expectedHeight, field.height, 1f) }
                val cancel = node("dialog-cancel").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
                val confirm = node("dialog-confirm").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
                assertEquals(cancel.width, confirm.width, 1f)
                assertEquals(cancel.height, confirm.height, 1f)
                assertEquals(cancel.top, confirm.top, 1f)
                assertTrue(cancel.right < confirm.left)
            }
        }
    }

    fun testControlledNamesRetainLimitsAndOnlyConfirmSaves() {
        for (kind in 0..3) {
            mount(kind)
            val beforeSave = saves.get()
            val beforeDismiss = dismissals.get()
            node("prompt-input").performTextReplacement("取消草稿")
            node("dialog-cancel").performClick()
            ui.waitForIdle()
            assertEquals(beforeSave, saves.get())
            assertEquals(beforeDismiss + 1, dismissals.get())
            mount(kind)
            if (kind >= 2) {
                node("prompt-input").performTextReplacement(" ")
                node("dialog-confirm").assertIsNotEnabled()
                node("prompt-input").performTextReplacement("字".repeat(90))
                node("prompt-input").assertTextEquals("字".repeat(80))
            }
            node("prompt-input").performTextReplacement("新名称")
            node("dialog-confirm").assertIsEnabled().performClick()
            ui.waitForIdle()
            assertEquals(beforeSave + 1, saves.get())
            assertEquals("新名称", savedName)
        }
    }

    fun testModelAndProviderStackWithoutCompetingWithSpeedAtNarrowWidths() {
        for (width in listOf(260, 300)) for (fontScale in listOf(1f, 1.3f)) {
            val visit = Any()
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { key(visit) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        Box(Modifier.width(width.dp)) {
                            SubAgentSettingRow("模型", "gpt-6.1-sol-very-long-model-name", Icons.Rounded.ViewInAr,
                                "测试模型", badge = "主模型服务商名称很长也不会挤压模型", onClick = {}, stackedValue = true,
                                trailing = { Text("标准", Modifier.testTag("settings-speed-fixture")) })
                        }
                    }
                } }
            } }
            awaitUniqueTag("subagent-setting-model-name")
            val model = node("subagent-setting-model-name").fetchSemanticsNode().boundsInRoot
            val provider = node("subagent-setting-model-provider").fetchSemanticsNode().boundsInRoot
            val speed = node("settings-speed-fixture").fetchSemanticsNode().boundsInRoot
            assertEquals(model.left, provider.left, 1f)
            assertEquals(model.width, provider.width, 1f)
            assertTrue(model.bottom <= provider.top)
            assertTrue(provider.bottom <= speed.top)
            assertTrue("Model text must retain useful horizontal space", model.width > 80f * activity.resources.displayMetrics.density)
            assertTrue("Single-line model text must not become a vertical column", model.width > model.height * 2f)
        }
    }
}
