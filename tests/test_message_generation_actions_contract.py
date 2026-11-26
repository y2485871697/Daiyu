"""Generating message actions use native disabled state and an independent stable-prefix branch."""
from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"

class MessageGenerationActionsContract(unittest.TestCase):
    def test_native_action_bar_and_long_press_menu_share_capability(self):
        bar = (SRC / "ui/screens/chat/conversation/MessageActionBar.kt").read_text()
        self.assertIn("messageActionsEnabled && actions.messageActionsEnabled", bar)
        for call in ("onEditUser", "onRegenerate", "onDeleteTurn"):
            self.assertIn("actions." + call + "(id) }, enabled = actionsEnabled", bar)
        self.assertIn("if (actionsEnabled) 1f else .38f", bar)
        self.assertIn("branchEnabled && actions.messageBranchEnabled", bar)
        menu = (SRC / "ui/screens/chat/conversation/ChatMenus.kt").read_text().split("internal fun UserTurnMenu", 1)[1].split("internal fun ChatVoiceModeMenu", 1)[0]
        self.assertIn('"复制" -> true', menu)
        self.assertIn('"分支" -> actions.messageBranchEnabled', menu)
        self.assertIn("else -> actions.messageActionsEnabled", menu)
        self.assertIn(".clickable(enabled = enabled)", menu)

    def test_host_projects_real_admission_and_blocks_old_mutation_gestures(self):
        activity = (SRC / "MainActivity.kt").read_text()
        self.assertIn("messageActionsEnabled = chatRouteSelected && !sidebarVisible && controller.messageMutationsAvailable()", activity)
        self.assertIn("messageBranchEnabled = chatRouteSelected && !sidebarVisible && controller.messageBranchAvailable()", activity)
        self.assertIn("controller.loadMessageBranchSnapshot(owner)", activity)
        for gate in ("return@regenerate", "return@delete"):
            self.assertIn("if (!controller.messageMutationsAvailable()) " + gate, activity)
        speech = activity.split("onSpeak = speak@{ id ->", 1)[1].split("onBranch = branch@", 1)[0]
        self.assertNotIn("messageMutationsAvailable()", speech)
        self.assertIn("speechHost.owner != renderedSpeechOwner", speech)
        host = (SRC / "ControllerMessageActions.kt").read_text()
        self.assertIn("action != MessageAction.BRANCH && !controller.messageMutationsAvailable()", host)

    def test_live_branch_uses_an_independent_worker_and_lifetime_pin(self):
        controller = (SRC / "ChatController.kt").read_text()
        self.assertIn("private val messageBranchExecutor=Executors.newSingleThreadExecutor()", controller)
        snapshot = controller.split("internal fun loadMessageBranchSnapshot", 1)[1].split("internal data class RegenerationPreview", 1)[0]
        self.assertIn("messageBranchExecutor.execute", snapshot)
        self.assertIn("messageActionReaders++", snapshot)
        self.assertIn("messageActionReaders--", snapshot)
        action = controller.split("internal fun messageAction(action:", 1)[1].split("internal fun skillManagement", 1)[0]
        self.assertIn("val actionExecutor = if (stableBranch) messageBranchExecutor else executor", action)
        self.assertIn("if (stableBranch) messageActionReaders++", action)
        self.assertIn("if (stableBranch) messageActionReaders--", action)
        reader = controller.split("private fun readBranchGenerationOriginal", 1)[1].split("private val branchGenerationAssets", 1)[0]
        self.assertIn("retained.openOutputs", reader)
        self.assertIn(".use { it.readBackup(reference) }", reader)
        self.assertNotIn("generationOutputReaders", reader)

    def test_branch_capability_is_prefix_scoped_and_not_mutation_authority(self):
        store = (SRC / "storage/MessageActionStore.kt").read_text()
        self.assertIn("branchPrefixSize: Int? = null", store)
        self.assertIn("all.takeWhile { it.status != RunStatus.RUNNING }", store)
        self.assertIn("ContextRevision.digest(prefix) == target.revision", store)
        self.assertIn('require(target.branchPrefixSize == null)', store)
        branch = (SRC / "context/ProductionConversationBranch.kt").read_text()
        self.assertIn("if (!stablePrefixOnly) require(original.none", branch)
        self.assertIn("checkNotNull(selectPrefix).invoke(currentHistory) == snapshot.prefix", branch)
        self.assertIn("if (!stablePrefixOnly) check(archives(db)", branch)
        self.assertIn("snapshot.history.forEachIndexed", branch)
        static = (SRC / "backup/StaticGenerationBranch.kt").read_text()
        self.assertIn("capture.selectedCalls", static)
        self.assertIn("selectedGeneration(BackupGenerationCodec.export", static)

    def test_held_model_and_real_activity_regressions_are_wired(self):
        tests = ROOT / "app/src/androidTest/kotlin/app/daiyu/android"
        controller = (tests / "ControllerMessageActionsTest.kt").read_text()
        self.assertIn("fun testStableBranchCompletesWhileTheSourceModelIsStillHeld()", controller)
        self.assertIn("assertEquals(1L, release.count)", controller)
        self.assertIn("fun testBranchOnlyCapabilityCannotBeUsedForIdleHistoryMutation()", controller)
        activity = (tests / "MessageActionsActivityTest.kt").read_text()
        self.assertIn("fun testGeneratingActivityOnlyAllowsCopyAndActualBranchWithoutTheSnapshotDialog()", activity)
        self.assertIn("clipboard.primaryClip", activity)
        self.assertIn("session.cancelled.count", activity)
        ui = (tests / "ui/screens/chat/conversation/MessageActionAvailabilityTest.kt").read_text()
        self.assertIn("assertIsNotEnabled().performTouchInput", ui)
        self.assertIn("enabled = true", ui)
        self.assertIn("UserTurnMenu", ui)

    def test_exact_five_class_smoke_scope_preserves_full_fallback(self):
        spec = importlib.util.spec_from_file_location("message_action_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
        recorder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(recorder)
        classes = recorder.MESSAGE_ACTION_PLATFORM_CLASSES
        self.assertEqual(5, len(classes))
        self.assertIn("test_chat_submission_preview_contract", recorder.MESSAGE_ACTION_MODULES)
        for chosen in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(chosen)))
            self.assertEqual("message-actions-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.MESSAGE_ACTION_MODULES, "-v"], command)
        for chosen in (classes[:-1], (*classes, classes[0]), (*classes, "app.daiyu.android.OtherTest")):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(chosen)))[1])
        for mode in ("compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=",".join(classes)))[1])
        smoke = (ROOT / "scripts/smoke_boot.sh").read_text()
        self.assertIn("message_action_sources if message_action_only", smoke)

    def test_frozen_message_action_tools_execute_their_own_modules_and_keep_dual_sha(self):
        import io
        import json
        import os
        import tempfile
        from types import SimpleNamespace
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location("message_action_frozen_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
        recorder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(recorder)
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory).resolve() / "frozen-tools"
            evidence = Path(directory) / "evidence"
            source = tools / "scripts/acceptance/record_tool_tests.py"
            env = dict(GITHUB_SHA="b" * 40, EXPECTED_SHA="a" * 40,
                       GITHUB_RUN_ID="43", GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke",
                       SMOKE_TEST_CLASSES=",".join(recorder.MESSAGE_ACTION_PLATFORM_CLASSES))
            def child(command, stdout, stderr, env=None, cwd=None):
                self.assertEqual(tools, cwd)
                self.assertEqual(str(tools / "tests"), env["PYTHONPATH"].split(os.pathsep)[0])
                stdout.write(b"fixture failed as expected\n")
                return 7
            with patch.dict(os.environ, env, clear=True), patch.object(recorder, "__file__", str(source)), \
                    patch.object(recorder, "Path", side_effect=lambda value: evidence if value == "tool-evidence" else Path(value)), \
                    patch.object(recorder.subprocess, "check_output", return_value="a" * 40 + "\n"), \
                    patch.object(recorder.subprocess, "call", side_effect=child), \
                    patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                self.assertEqual(7, recorder.record("python3"))
            receipt = json.loads((evidence / "receipt.json").read_text())
            self.assertEqual("message-actions-targeted", receipt["scope"])
            self.assertEqual("a" * 40, receipt["identity"]["sha"])
            self.assertEqual("b" * 40, receipt["validation_tools_sha"])
            self.assertEqual(7, receipt["exit_code"])

if __name__ == "__main__":
    unittest.main()
