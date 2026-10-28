"""Message deletion and regeneration use scoped, themed confirmation actions."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"


class MessageConfirmationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (APP / "MainActivity.kt").read_text()

    def callback(self, start, end):
        return self.main.split(start, 1)[1].split(end, 1)[0]

    def test_both_callers_use_only_confirm_cancel_and_local_close_opt_out(self):
        for start, end, title, action in (
            ("onDeleteTurn = { id ->", "}, onRetryStep =", "删除这条消息及后面的内容？", "onDeleteTurn"),
            ("onRegenerate = { id ->", "}, onSpeak =", "重新生成将删除此回复及后面的消息", "onRegenerate"),
        ):
            with self.subTest(action=action):
                caller = self.callback(start, end)
                self.assertIn(f'ConfirmPrompt("{title}", "", "确定",', caller)
                self.assertIn(f"action = {{ messageCallbacks.{action}(id) }}", caller)
                self.assertIn("showCloseButton = false, secondaryCancel = true", caller)
                for forbidden in ("ChoicePrompt(", "listOf(", "destructive = true", "deletionAppearance"):
                    self.assertNotIn(forbidden, caller)

    def test_regeneration_condition_and_immediate_branch_are_unchanged(self):
        caller = self.callback("onRegenerate = { id ->", "}, onSpeak =")
        for token in ("actionSnapshot?.renderedTargets?.get(id) ?: actionSnapshot?.targets?.get(id)",
                      "it.listKey == id || it.id == id", "target != null && index >= 0",
                      "chatState.turns.drop(index + 1).any { it is ChatTurnUi.User }",
                      "else messageCallbacks.onRegenerate(id)"):
            self.assertIn(token, caller)

    def test_prompt_options_are_explicit_and_keep_existing_defaults(self):
        prompt = self.main.split("private class ConfirmPrompt(", 1)[1].split("private var prompt", 1)[0]
        self.assertIn("val destructive: Boolean = false", prompt)
        self.assertIn("val showCloseButton: Boolean = true", prompt)
        self.assertIn("val secondaryCancel: Boolean = false", prompt)
        rendering = self.main.split("is ConfirmPrompt ->", 1)[1].split("private fun dismissPrompt", 1)[0]
        for token in ('preview = retainedPage == "chat"', "showCloseButton = current.showCloseButton",
                      "secondaryCancel = current.secondaryCancel", "confirmPrompt(current)",
                      "if (valid()) dismissPrompt(current)"):
            self.assertIn(token, rendering)
        confirmation = self.main.split("private fun confirmPrompt(", 1)[1].split("private fun openSettingsFromPage", 1)[0]
        self.assertIn("activityUsable() && prompt === expected", confirmation)
        self.assertIn("prompt = null; expected.action()", confirmation)

    def test_preview_uses_the_same_action_roles_as_the_theme_preview(self):
        dialog = (APP / "ui/components/DaiyuDialogs.kt").read_text().split("internal fun DaiyuConfirmDialog(", 1)[1].split("internal val RetainedChoiceSelect", 1)[0]
        self.assertIn("secondaryCancel: Boolean = false", dialog)
        self.assertIn('testTag("dialog-cancel"), secondary = secondaryCancel', dialog)
        self.assertIn("primary = !destructive, danger = destructive", dialog)
        self.assertNotIn("actionRow = true", dialog)
        button = (APP / "ui/screens/assistants/AssistantPreviewComponents.kt").read_text()
        for token in ("val secondaryContainer = actions.secondaryContainer", "val onSecondary = actions.onSecondary",
                      "val primary = actions.primary", "val onPrimary = actions.onPrimary",
                      "primary -> colors.primary", "secondary -> colors.secondaryContainer",
                      "secondary -> colors.onSecondary"):
            self.assertIn(token, button)

    def test_android_regression_exercises_actual_routes_and_theme_pixels(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/MessageConfirmationActivityTest.kt").read_text()
        for token in ("retainedControls(activity).onDeleteTurn", "retainedControls(activity).onRegenerate",
                      '"cancel", "back", "outside"', "KEYCODE_BACK", "exportBackup()",
                      'assertCountEquals(1)', 'assertCountEquals(0)', '"确定"',
                      "cancel.width, confirm.width", "captureScreenNode", "actions.secondaryContainer",
                      "actions.onSecondary", "actions.primary", "actions.onPrimary", "fixture.opens.get()",
                      "testDeleteConfirmKeepsEarlierMessagesAndCommitsOnce",
                      "testRegenerateConfirmMakesOneRequestAndTruncatesLaterMessages"):
            self.assertIn(token, test)


if __name__ == "__main__":
    unittest.main()
