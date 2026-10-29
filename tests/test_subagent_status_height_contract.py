"""Source-only contracts; Android geometry regressions run with the approved build batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


def source(path):
    return (SRC / path).read_text()


class SubAgentStatusHeightContractTest(unittest.TestCase):
    def test_status_uses_default_sheet_height_for_every_state(self):
        text = source("ui/screens/chat/conversation/SubAgentStatusDialog.kt")
        self.assertIn("ConversationAgentSheet(onDismiss, radius = 26, useDefaultHeight = true)", text)
        self.assertNotIn("appWindowSize", text)
        self.assertNotIn("heightIn(min = 180.dp)", text)
        self.assertIn("Column(Modifier.fillMaxSize()", text)
        self.assertIn('Modifier.fillMaxWidth().weight(1f).testTag("subagent-status-empty")', text)
        self.assertIn('LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("subagent-status-list"))', text)
        self.assertIn("items(rows, key = { subAgentStatusItemKey(it.owner) })", text)

    def test_assistant_and_status_share_one_safe_viewport_height_rule(self):
        sizing = source("ui/components/BottomSheetSizing.kt")
        self.assertIn("internal fun defaultBottomSheetHeight(maxHeight: Dp): Dp", sizing)
        self.assertIn("(maxHeight - 20.dp).coerceAtLeast(0.dp)", sizing)
        self.assertIn("minOf((maxHeight - 48.dp).coerceIn(300.dp, 520.dp), availableHeight)", sizing)
        assistant = source("ui/screens/assistants/AssistantPreviewComponents.kt")
        self.assertIn("val sheetHeight = defaultBottomSheetHeight(maxHeight)", assistant)
        self.assertIn(".fillMaxWidth().height(sheetHeight)", assistant)
        style = source("ui/components/subagent/ConversationAgentStyle.kt")
        self.assertIn("useDefaultHeight: Boolean = false", style)
        self.assertIn("Modifier.padding(10.dp).height(defaultBottomSheetHeight(maxHeight))", style)
        self.assertIn("else Modifier.heightIn(max = (maxHeight - 20.dp).coerceAtLeast(0.dp)).padding(10.dp)", style)
        self.assertIn(".then(heightModifier).fillMaxWidth()", style)
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = true)", style)
        workspace = source("ui/components/ConversationWorkspaceSheet.kt")
        self.assertIn("ConversationAgentSheet(onDismiss, useDefaultHeight = true,", workspace)
        for file in SRC.rglob("*.kt"):
            if file.name in {"SubAgentStatusDialog.kt", "ConversationCollaborationDialog.kt", "SearchHistoryDialog.kt", "ConversationWorkspaceSheet.kt"}:
                continue
            text = file.read_text()
            if file.relative_to(SRC).as_posix() == "ui/screens/settings/McpSettingsScreen.kt":
                before, boundary, detail = text.partition("private fun McpToolDescriptionSheet(")
                self.assertTrue(boundary, str(file))
                allowed = "ConversationAgentSheet(onDismiss, useDefaultHeight = true)"
                self.assertEqual(1, detail.count(allowed), str(file))
                text = before + boundary + detail.replace(allowed, "", 1)
            self.assertNotIn("useDefaultHeight = true", text, str(file))

    def test_height_rule_has_small_window_and_landscape_unit_cases(self):
        text = (ROOT / "app/src/test/kotlin/app/daiyu/android/BottomSheetSizingTest.kt").read_text()
        for fragment in ["0 to 0", "100 to 80", "280 to 260", "320 to 300", "400 to 352", "600 to 520",
                         "defaultBottomSheetHeight(available.dp)"]:
            self.assertIn(fragment, text)

    def test_android_geometry_compares_real_surfaces_in_all_status_states(self):
        text = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/BottomSheetEdgeActivityTest.kt").read_text()
        for fragment in ["testStatusSheetMatchesAssistantHeightForEveryState", 'rawBounds(node("assistant-sheet").fetchSemanticsNode()).height',
                         'assertFloating("conversation-agent-sheet")', "loading = state == 0", "failed = state == 2",
                         "listOf(0, 1, 2, 3, 4)", "listOf(1f, .8f)", '"Status and assistant surfaces share a default height"',
                         'expectedHeight, rawBounds(status).height, 2f)',
                         'performScrollToIndex(rows.lastIndex)', 'assertEquals(0, fixture.opens.get())',
                         'ui.onNodeWithText("正在读取任务状态…").assertIsDisplayed()',
                         'ui.onNodeWithText("任务状态读取失败；未改变任何任务").assertIsDisplayed()',
                         'ui.onNodeWithText("冻结模型 39").assertDoesNotExist()']:
            self.assertIn(fragment, text)

    def test_long_list_restores_start_and_commits_each_disclosure_before_query(self):
        text = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/SubAgentStatusDialogTest.kt").read_text()
        body = text.split('fun testLongListUsesFrozenModelAndScrollsInBothThemes()', 1)[1]
        for token in ('(0 until 40)', 'listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)',
                      'performScrollToIndex(0)\n            awaitVisualIdle()',
                      'performScrollToIndex(rows.lastIndex)',
                      'ui.onNodeWithText("已完成").assertIsDisplayed()',
                      'ui.onNodeWithTag("subagent-status").assertDoesNotExist()'):
            self.assertIn(token, body)
        for index in (0, 39):
            hidden = f'ui.onNodeWithText("冻结模型 {index}").assertDoesNotExist()'
            committed = (f'ui.onNodeWithTag("subagent-status:task-{index}").performClick()'
                         '\n            awaitVisualIdle()')
            shown = f'ui.onNodeWithText("冻结模型 {index}").scrollToVisible().assertIsDisplayed()'
            self.assertLess(body.index(hidden), body.index(committed))
            self.assertLess(body.index(committed), body.index(shown))
        self.assertNotIn('Thread.sleep', body)
        self.assertNotIn('performScrollToNode(hasText("冻结模型', body)


if __name__ == "__main__":
    unittest.main()
