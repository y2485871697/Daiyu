from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PICKER = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ComposerShortcutPicker.kt"
ACTIVITY_TEST = ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ComposerShortcutActivityTest.kt"


class SkillPickerEmptyStateContractTest(unittest.TestCase):
    def test_only_skill_zero_count_is_hidden(self):
        source = PICKER.read_text()
        status = source.split("        LazyColumn(", 1)[0]
        self.assertIn("if (conversation || filtered?.isEmpty() != true || error != null)", status)
        self.assertIn('error != null -> error; filtered == null -> "正在加载"', status)
        self.assertIn('"个会话" else "个 Skill"', status)

    def test_both_empty_kinds_fill_and_center_in_the_same_list_viewport(self):
        source = PICKER.read_text()
        empty = source.split("if (filtered?.isEmpty() == true) item {", 1)[1]
        self.assertIn("Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center)", empty)
        self.assertIn('Text(if (conversation) "暂无可引用会话" else "暂无可用 Skill"', empty)
        self.assertIn("textAlign = TextAlign.Center", empty)
        self.assertIn('testTag("reference-picker-list")', source)
        self.assertIn('clickable { onChoose(choice) }', source)

    def test_real_slash_entry_regression_checks_absence_and_both_centers(self):
        source = ACTIVITY_TEST.read_text()
        test = source.split("fun testCancelLeavesExactDraftAndSlashPickerDoesNotSendOrReadSkills()", 1)[1]
        self.assertIn('type("/")', test)
        self.assertIn('onNodeWithText("0 个 Skill").assertDoesNotExist()', test)
        self.assertIn('onNodeWithText("暂无可用 Skill").assertIsDisplayed()', test)
        self.assertIn('node("reference-picker-list").fetchSemanticsNode().boundsInRoot', test)
        self.assertIn("empty.center.x - content.center.x", test)
        self.assertIn("empty.center.y - content.center.y", test)
        self.assertIn('assertDraft("/"); assertEquals(0, fixture.opens.get())', test)
