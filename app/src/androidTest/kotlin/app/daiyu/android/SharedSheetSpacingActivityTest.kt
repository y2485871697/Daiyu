package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.daiyu.android.ui.components.LocalBottomSheetContent
import app.daiyu.android.ui.components.OptionSelectionItem
import app.daiyu.android.ui.components.UsageAction
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.screens.assistants.AssistantPreviewDialog
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import app.daiyu.android.ui.screens.settings.SettingsSectionTitle
import app.daiyu.android.ui.screens.settings.LocalSettingsPageStyle
import java.util.concurrent.atomic.AtomicInteger

class SharedSheetSpacingActivityTest : WiredActivityTestCase() {
    private var menuDensity = 1f

    private fun mount(dark: Boolean, fontScale: Float, surface: String = "chat-selection-sheet", content: @Composable () -> Unit) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) {
                val density = LocalDensity.current
                menuDensity = density.density
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
            }
        } }
        awaitUniqueTag(surface)
        awaitNodeWindowFocus(node(surface))
        ui.waitForIdle()
    }

    private fun bounds(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot

    private fun assertGap(gap: Float, maxDp: Float) {
        assertTrue("adjacent content must not overlap: $gap", gap >= -1f)
        assertTrue("content gap exceeds ${maxDp}dp: $gap", gap <= maxDp * menuDensity + 1f)
    }

    fun testEmptyAndSingleOptionSheetsWrapWithoutOptIn() {
        val before = fixture.database.exportBackup()
        val selections = AtomicInteger()
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) for (count in 0..1) {
            mount(dark, fontScale) {
                ChatSelectionSheet("短选单", {}, showCloseButton = false) {
                    repeat(count) {
                        OptionSelectionItem("唯一选项", false, Modifier.testTag("short-choice")) { selections.incrementAndGet() }
                    }
                }
            }
            val sheet = bounds("chat-selection-sheet")
            val title = ui.onNodeWithText("短选单").fetchSemanticsNode().boundsInRoot
            assertTrue("empty/one-row surfaces must not inherit a 180dp minimum", sheet.height < 180f * menuDensity)
            assertEquals("handle gap belongs to the shared shell", 22f * menuDensity, title.top - sheet.top, 2f)
            if (count == 0) {
                assertTrue("empty body should leave only title and surface padding", sheet.bottom - title.bottom <= 23f * menuDensity + 1f)
            } else {
                val choice = bounds("short-choice")
                node("short-choice").assertHeightIsAtLeast(48.dp)
                assertGap(choice.top - title.bottom, 8f)
                assertGap(sheet.bottom - choice.bottom, 17f)
            }
            ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
        }
        assertEquals(0, selections.get())
        assertEquals(0, fixture.opens.get())
        assertEquals(before, fixture.database.exportBackup())
    }

    fun testSubtitleAndFooterSpacingPreserveCallbacks() {
        val selections = AtomicInteger()
        val confirmations = AtomicInteger()
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            val open = mutableStateOf(true)
            mount(dark, fontScale) {
                if (open.value) ChatSelectionSheet("有副标题", {}, subtitle = "副标题", showCloseButton = false,
                    footer = {
                        UsageAction("确定", { confirmations.incrementAndGet(); open.value = false },
                            Modifier.fillMaxWidth().testTag("sheet-confirm"))
                    }) {
                    OptionSelectionItem("选项", false, Modifier.testTag("subtitle-choice")) { selections.incrementAndGet() }
                }
            }
            val subtitle = ui.onNodeWithText("副标题").fetchSemanticsNode().boundsInRoot
            val choice = bounds("subtitle-choice")
            val footer = bounds("sheet-confirm")
            node("subtitle-choice").assertIsDisplayed()
            node("sheet-confirm").assertIsDisplayed()
            assertGap(choice.top - subtitle.bottom, 8f)
            assertEquals("footer must immediately follow its short body", 0f, footer.top - choice.bottom, 1f)
            assertGap(bounds("chat-selection-sheet").bottom - footer.bottom, 17f)
            node("sheet-confirm").performTouchInput { click() }
            ui.waitForIdle()
            node("chat-selection-sheet").assertDoesNotExist()
        }
        assertEquals(4, confirmations.get())
        assertEquals(0, selections.get())
        assertEquals(0, fixture.opens.get())
    }

    fun testExplicitHeightAndCloseTouchTargetStayUnchanged() {
        val dismissals = AtomicInteger()
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            val open = mutableStateOf(true)
            mount(dark, fontScale) {
                if (open.value) ChatSelectionSheet("固定工作区", { dismissals.incrementAndGet(); open.value = false }, height = 300.dp) {
                    OptionSelectionItem("工作区内容", false, Modifier.testTag("fixed-body")) {}
                }
            }
            node("chat-selection-sheet").assertHeightIsEqualTo(300.dp)
            node("fixed-body").assertHeightIsAtLeast(48.dp)
            ui.onNodeWithContentDescription("关闭").assertHeightIsEqualTo(40.dp).assertWidthIsEqualTo(40.dp)
                .performTouchInput { click() }
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodesWithTag("chat-selection-sheet").fetchSemanticsNodes().isEmpty()
            }
        }
        assertEquals(4, dismissals.get())
        assertEquals(0, fixture.opens.get())
    }

    fun testSettingsSectionSpacingStaysScopedToSheet() {
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) for (grouped in listOf(false, true)) {
            val open = mutableStateOf(true)
            mount(dark, fontScale) {
                CompositionLocalProvider(LocalSettingsPageStyle provides grouped) {
                    Column {
                        Box(Modifier.testTag("page-section")) { SettingsSectionTitle("页面分组") }
                        if (open.value) ChatSelectionSheet("分组选择", { open.value = false }, showCloseButton = false) {
                            Box(Modifier.testTag("sheet-section")) { SettingsSectionTitle("弹层分组") }
                            OptionSelectionItem("分组选项", false, Modifier.testTag("group-choice")) {}
                        }
                    }
                }
            }
            val pageHeight = bounds("page-section").height
            val section = bounds("sheet-section")
            val title = ui.onNodeWithText("分组选择").fetchSemanticsNode().boundsInRoot
            assertEquals("page section padding must stay unchanged", (if (grouped) 19f else 24f) * menuDensity,
                pageHeight - section.height, 2f)
            assertGap(section.top - title.bottom, 8f)
            node("preview-dialog-viewport").performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, 2f)) }
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodesWithTag("chat-selection-sheet").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(pageHeight, bounds("page-section").height, 1f)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testAssistantTitleAndOptionalFooterReturnSpaceToFixedWorkspace() {
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            var fixedHeight = 0f
            for (withFooter in listOf(false, true)) {
                val footer: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? =
                    if (withFooter) ({ UsageAction("完成", {}, Modifier.fillMaxWidth().testTag("optional-footer-action")) }) else null
                mount(dark, fontScale, surface = "assistant-sheet") {
                    AssistantPreviewDialog(true, "助手工作区", {}, showCloseButton = false, footer = footer) {
                        Box(Modifier.fillMaxWidth().weight(1f).testTag("assistant-workspace-body"))
                    }
                }
                val sheet = bounds("assistant-sheet")
                if (withFooter) {
                    assertEquals(fixedHeight, sheet.height, 1f)
                    node("assistant-sheet-footer").assertIsDisplayed()
                    node("optional-footer-action").assertIsDisplayed()
                } else {
                    fixedHeight = sheet.height
                    node("assistant-sheet-footer").assertDoesNotExist()
                    assertTrue("absent footer must leave only the original surface padding",
                        sheet.bottom - bounds("assistant-workspace-body").bottom <= 18f * menuDensity + 1f)
                }
                val title = ui.onNodeWithText("助手工作区").fetchSemanticsNode().boundsInRoot
                assertGap(bounds("assistant-workspace-body").top - title.bottom, 8f)
                ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testCenteredDialogResetsInheritedSheetSectionScope() {
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) for (grouped in listOf(false, true)) {
            mount(dark, fontScale, surface = "usage-glass-dialog") {
                CompositionLocalProvider(LocalSettingsPageStyle provides grouped) {
                    Box(Modifier.testTag("page-section-reference")) { SettingsSectionTitle("页面参考") }
                    CompositionLocalProvider(LocalBottomSheetContent provides true) {
                        UsageGlassDialog("居中弹层", {}, compact = true) {
                            Box(Modifier.testTag("centered-section")) { SettingsSectionTitle("居中分组") }
                        }
                    }
                }
            }
            assertEquals("centered dialog must override inherited sheet scope", bounds("page-section-reference").height,
                bounds("centered-section").height, 1f)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testUsageDefaultHeightMatchesAssistantWorkspaceButCompactIgnoresIt() {
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            mount(dark, fontScale, surface = "assistant-sheet") {
                AssistantPreviewDialog(true, "高度参考", {}, showCloseButton = false) {}
            }
            val workspaceHeight = bounds("assistant-sheet").height
            for (compact in listOf(false, true)) {
                mount(dark, fontScale, surface = "usage-glass-dialog") {
                    UsageGlassDialog("默认高度", {}, compact = compact, useDefaultHeight = true) {
                        OptionSelectionItem("统计项", false) {}
                    }
                }
                val sheet = bounds("usage-glass-dialog")
                if (compact) {
                    assertTrue("centered mode must still wrap", sheet.height < workspaceHeight)
                } else {
                    assertEquals(workspaceHeight, sheet.height, 1f)
                }
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testUsageBottomAndCenteredModesKeepSeparateSpacing() {
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) for (compact in listOf(false, true)) {
            mount(dark, fontScale, surface = "usage-glass-dialog") {
                UsageGlassDialog("用量", {}, compact = compact) {
                    OptionSelectionItem("统计项", false, Modifier.testTag("usage-choice")) {}
                }
            }
            val title = ui.onNodeWithText("用量").fetchSemanticsNode().boundsInRoot
            val choice = bounds("usage-choice")
            val sheet = bounds("usage-glass-dialog")
            assertEquals((if (compact) 8f else 25f) * menuDensity, title.top - sheet.top, 2f)
            assertEquals((if (compact) 10f else 6f) * menuDensity, choice.top - title.bottom, 2f)
            assertEquals((if (compact) 10f else 15f) * menuDensity, sheet.bottom - choice.bottom, 2f)
        }
        assertEquals(0, fixture.opens.get())
    }
}
