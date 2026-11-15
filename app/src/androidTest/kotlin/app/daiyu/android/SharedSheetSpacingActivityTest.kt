package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.domain.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.DaiyuConfirmDialog
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

    private fun assertConversationDeleteAppearance() {
        ui.onNodeWithText("删除会话").assertIsDisplayed()
        ui.onNodeWithText("确认删除此会话及其消息？").assertDoesNotExist()
        ui.onNodeWithText("取消").assertIsDisplayed()
        ui.onNodeWithText("删除").assertIsDisplayed()
        ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
        val cancel = bounds("dialog-cancel")
        val confirm = bounds("dialog-confirm")
        val sheet = bounds("chat-selection-sheet")
        val title = ui.onNodeWithText("删除会话").fetchSemanticsNode().boundsInRoot
        assertEquals(cancel.width, confirm.width, 1f)
        assertEquals(cancel.top, confirm.top, 1f)
        assertEquals(sheet.center.x, title.center.x, 1f)
        val density = node("chat-selection-sheet").fetchSemanticsNode().layoutInfo.density.density
        assertEquals("no description or empty-body gap", 12f * density, cancel.top - title.bottom, 2f)
        val pixels = captureScreenNode(node("dialog-confirm")).toPixelMap()
        val expected = Color(0xFF92454F)
        var red = 0
        var white = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (kotlin.math.abs(pixel.red - expected.red) < .025f &&
                kotlin.math.abs(pixel.green - expected.green) < .025f &&
                kotlin.math.abs(pixel.blue - expected.blue) < .025f) red++
            if (x in pixels.width / 4 until pixels.width * 3 / 4 &&
                y in pixels.height / 4 until pixels.height * 3 / 4 &&
                pixel.red > .97f && pixel.green > .97f && pixel.blue > .97f) white++
        }
        assertTrue("workspace deletion red fill", red > pixels.width * pixels.height * .35f)
        assertTrue("workspace deletion white label", white > 8)
    }

    fun testConversationDeleteMatchesWorkspaceWithoutDescription() {
        val before = fixture.database.exportBackup()
        val dismissals = AtomicInteger()
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            val open = mutableStateOf(true)
            mount(dark, fontScale) {
                if (open.value) DaiyuConfirmDialog("删除会话", "", "删除", true,
                    onConfirm = { fail("appearance must not confirm") },
                    onDismiss = { dismissals.incrementAndGet(); open.value = false },
                    preview = true, deletionAppearance = true, showCloseButton = false)
            }
            assertConversationDeleteAppearance()
            assertEquals(8f * menuDensity, bounds("dialog-confirm").left - bounds("dialog-cancel").right, 2f)
            assertGap(bounds("chat-selection-sheet").bottom - bounds("dialog-confirm").bottom, 17f)
            node("dialog-cancel").performClick()
            ui.waitForIdle()
            node("chat-selection-sheet").assertDoesNotExist()
        }
        assertEquals(4, dismissals.get())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testRealSidebarDeleteOnlyConfirmsTargetAndDismissalKeepsHistory() {
        val target = fixture.database.create("default")
        val sibling = fixture.database.create("default")
        for (conversation in listOf(target, sibling)) {
            val key = conversation.id.value
            val user = Message(MessageId("$key-user"), conversation.id, RunId("$key-run"), Message.USER_ROLE,
                listOf(MessageBlock.Text("q", "keep history for $key")), RunStatus.COMPLETED)
            val answer = user.copy(id = MessageId("$key-answer"), role = Message.ASSISTANT_ROLE, status = RunStatus.RUNNING)
            fixture.database.send(user, answer)
            fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
        }
        val originalIds = fixture.database.conversations().map { it.id }.toSet()
        val siblingHistory = fixture.database.messages(sibling.id)
        val before = fixture.database.exportBackup()
        dismissIme()
        mainThread { invokeHost(activity, "openSidebar") }
        for (action in listOf("cancel", "back", "outside", "confirm")) {
            mainThread { invokeHost(activity, "confirmDelete", target.id.value) }
            awaitUniqueTag("chat-selection-sheet")
            awaitNodeWindowFocus(node("chat-selection-sheet"))
            ui.waitForIdle()
            assertConversationDeleteAppearance()
            when (action) {
                "cancel" -> node("dialog-cancel").performClick()
                "back" -> instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                "outside" -> node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
                "confirm" -> node("dialog-confirm").performClick()
            }
            ui.waitForIdle()
            node("chat-selection-sheet").assertDoesNotExist()
            if (action != "confirm") assertEquals(before, fixture.database.exportBackup())
        }
        awaitControl("only the confirmed conversation is removed") {
            fixture.database.conversations().none { it.id == target.id }
        }
        assertEquals(originalIds - target.id, fixture.database.conversations().map { it.id }.toSet())
        assertEquals(siblingHistory, fixture.database.messages(sibling.id))
        assertEquals(0, fixture.opens.get())
    }

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
                assertTrue("empty body should leave only title and surface padding", sheet.bottom - title.bottom <= 29f * menuDensity + 1f)
            } else {
                val choice = bounds("short-choice")
                node("short-choice").assertHeightIsAtLeast(48.dp)
                assertGap(choice.top - title.bottom, 12f)
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
            assertGap(choice.top - subtitle.bottom, 12f)
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
            assertGap(section.top - title.bottom, 12f)
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
                assertGap(bounds("assistant-workspace-body").top - title.bottom, 12f)
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
            assertEquals((if (compact) 10f else 12f) * menuDensity, choice.top - title.bottom, 2f)
            assertEquals((if (compact) 10f else 15f) * menuDensity, sheet.bottom - choice.bottom, 2f)
        }
        assertEquals(0, fixture.opens.get())
    }
}
