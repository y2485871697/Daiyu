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
        for start, end, title, action, argument in (
            ("onDeleteTurn = delete@{ id ->", "}, onRetryStep =", "删除这条消息及后面的内容？", "onDeleteTurn", "firstSourceKey"),
            ("onRegenerate = regenerate@{ id ->", "}, onSpeak =", "重新生成将删除此回复及后面的消息", "onRegenerate", "id"),
        ):
            with self.subTest(action=action):
                caller = self.callback(start, end)
                label = '删除' if action == 'onDeleteTurn' else '确定'
                self.assertIn(f'ConfirmPrompt("{title}", "", "{label}",', caller)
                self.assertIn(f"action = {{ messageCallbacks.{action}({argument}) }}", caller)
                if action == "onDeleteTurn":
                    self.assertIn("val firstSourceKey = chatState.turns.firstOrNull { it.matchesKey(id) }?.listKey ?: id", caller)
                if action == "onDeleteTurn":
                    self.assertIn("showCloseButton = false, deletionAppearance = true", caller)
                else:
                    self.assertIn("showCloseButton = false, secondaryCancel = true", caller)
                    self.assertNotIn("deletionAppearance", caller)
                for forbidden in ("ChoicePrompt(", "listOf(", "destructive = true"):
                    self.assertNotIn(forbidden, caller)

    def test_regeneration_condition_and_immediate_branch_are_unchanged(self):
        caller = self.callback("onRegenerate = regenerate@{ id ->", "}, onSpeak =")
        for token in ("actionSnapshot?.renderedTargets?.get(id) ?: actionSnapshot?.targets?.get(id)",
                      "val index = chatState.turns.indexOfFirst { it.matchesKey(id) }", "target != null && index >= 0",
                      "chatState.turns.drop(index + 1).any { it is ChatTurnUi.User }",
                      "else messageCallbacks.onRegenerate(id)"):
            self.assertIn(token, caller)

    def test_regeneration_preparation_is_silent_without_hiding_other_status(self):
        status = self.main.split("statusText = controller.problem ?: when {", 1)[1].split("composer = ComposerUi(", 1)[0]
        self.assertNotIn("正在准备重新生成", self.main)
        self.assertNotIn("regeneration", status)
        self.assertNotIn("imageHost.sending ->", status)
        for token in ("controller.dataBusy -> controller.dataStatus", '!controller.ready -> "恢复会话中"',
                      '!selectionReady -> "加载会话中"', "messageEditBlocked ->"):
            self.assertIn(token, status)
        self.assertIn("else -> null", status)
        activity = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ChatSubmissionActivityTest.kt").read_text()
        self.assertIn("mainThread { assertNull(state().statusText) }", activity)
        self.assertIn('ui.onNodeWithText("正在准备重新生成…").assertDoesNotExist()', activity)
        self.assertIn('ui.onNodeWithTag("chat-status").assertDoesNotExist()', activity)
        for token in ("testRegenerationHidesReplyWhilePreparingAndRestoresItOnRejection",
                      "testAcceptedRegenerationReplacesPreviewOnlyAfterExactNewReplyIsPublished",
                      "assertEquals(original, fixture.database.messages(conversation.id))",
                      "assertEquals(1, fixture.opens.get())"):
            self.assertIn(token, activity)

    def test_regeneration_device_scope_runs_only_related_contracts(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("regeneration_hint_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
        recorder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(recorder)
        target = "app.daiyu.android.ChatSubmissionActivityTest"
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target))
        self.assertEqual("regeneration-preparation-targeted", scope)
        self.assertEqual(["python3", "-B", "-m", "unittest", "test_chat_submission_preview_contract",
                          "test_message_confirmation_contract", "-v"], command)
        for mode, classes in (("compile", target), ("release", target), ("daily", target),
                              ("candidate", target), ("smoke", target + "," + target),
                              ("smoke", target + ",app.daiyu.android.OtherTest")):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=classes))[1])

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
