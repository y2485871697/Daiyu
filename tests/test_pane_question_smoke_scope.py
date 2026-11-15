"""Drawer/question handoff runs only its five requested device classes and contracts."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from test_record_tool_tests_scope import recorder

ROOT = Path(__file__).resolve().parents[1]
CLASSES = (
    "app.daiyu.android.QuestionBarrierIntegrationTest",
    "app.daiyu.android.ConversationPaneSwitchActivityTest",
    "app.daiyu.android.ui.screens.chat.conversation.MessageActionBarLayoutTest",
    "app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest",
    "app.daiyu.android.ui.screens.chat.conversation.WorkflowDisclosureDirectionTest",
)


class PaneQuestionSmokeScopeTest(unittest.TestCase):
    def test_exact_scope_is_order_independent_and_never_discovers_all_contracts(self):
        self.assertEqual(CLASSES, recorder.PANE_QUESTION_PLATFORM_CLASSES)
        for names in (CLASSES, tuple(reversed(CLASSES))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(names)))
            self.assertEqual("pane-question-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.PANE_QUESTION_MODULES, "-v"], command)
            self.assertNotIn("discover", command)
            for module in ("test_stream_question_projection_contract", "test_sidebar_selection_contract",
                           "test_message_action_snapshot_admission_contract", "test_streaming_occlusion_contract",
                           "test_workflow_disclosure_coverage_contract", "test_pane_question_smoke_scope"):
                self.assertIn(module, command)

    def test_near_matches_and_other_modes_do_not_claim_target_scope(self):
        valid = ",".join(CLASSES)
        for value in ("", ",".join(CLASSES[:-1]), valid + "," + CLASSES[0], valid + ",unknown.Test", valid + ",", " " + valid):
            self.assertNotEqual("pane-question-targeted", recorder.command_for("python3", dict(
                SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        for mode in ("compile", "candidate", "daily", "release"):
            self.assertNotEqual("pane-question-targeted", recorder.command_for("python3", dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=valid))[1])

    def selector(self, root, names, shard, api):
        source = (ROOT / "scripts/smoke_boot.sh").read_text().split("<<'PYCOUNT'\n", 1)[1].split("\nPYCOUNT", 1)[0]
        guard = "from unittest.mock import patch\nfrom pathlib import Path\n"
        guard += "with patch.object(Path, 'rglob', side_effect=AssertionError('full androidTest scan')):\n"
        guard += "    exec(" + repr(source) + ")\n"
        env = dict(os.environ, SMOKE_SCRIPT_DIR=str(ROOT / "scripts"), SMOKE_MODE="smoke",
                   SMOKE_API=api, SMOKE_SHARD_COUNT="4", SMOKE_SHARD_INDEX=str(shard),
                   SMOKE_TEST_CLASSES=",".join(names))
        return subprocess.run([sys.executable, "-B", "-c", guard], cwd=root, env=env,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def fixture(self, root):
        (root / "smoke").mkdir()
        for name in CLASSES:
            path = Path("app/src/androidTest/kotlin") / (name.replace(".", "/") + ".kt")
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text((ROOT / path).read_text())

    def test_real_discovery_has_only_five_sources_and_complete_four_shards_on_both_apis(self):
        for api in ("26", "37.2"):
            for names in (CLASSES, tuple(reversed(CLASSES))):
                union = set()
                with tempfile.TemporaryDirectory() as temp:
                    root = Path(temp)
                    self.fixture(root)
                    for shard in range(4):
                        result = self.selector(root, names, shard, api)
                        self.assertEqual(0, result.returncode, result.stderr)
                        inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                        selection = json.loads((root / "smoke/selection.json").read_text())
                        self.assertEqual("targeted", inventory["discovery_scope"])
                        self.assertEqual(set(CLASSES), set(inventory["all_classes"]))
                        selected = set(selection["selected_classes"])
                        self.assertFalse(union & selected)
                        union.update(selected)
                    self.assertEqual(set(CLASSES), union)

    def test_missing_source_is_rejected_without_unbounded_discovery(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            (root / "app/src/androidTest/kotlin" / (CLASSES[0].replace(".", "/") + ".kt")).unlink()
            self.assertNotEqual(0, self.selector(root, CLASSES, 0, "26").returncode)
