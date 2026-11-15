"""Generation-only validation stays exact without rebuilding paid media clients."""
import importlib.util
import io
import os
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import test_speech_platform_discovery as discovery

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("generation_test_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)
CLASSES = (
    "app.daiyu.android.GenerationInputImportTest",
    "app.daiyu.android.storage.SQLiteGenerationAttemptStoreTest",
    "app.daiyu.android.storage.SQLiteGenerationOutputStoreTest",
    "app.daiyu.android.integration.tools.RunGenerationToolsDeviceTest",
    "app.daiyu.android.ControllerGenerationResourcesTest",
    "app.daiyu.android.ControllerGenerationIntegrationTest",
    "app.daiyu.android.storage.GenerationProtocolMigrationTest",
)


class GenerationPortScopeTest(unittest.TestCase):
    def fixture(self, root):
        helper = discovery.SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        for count, name in enumerate(CLASSES, 1):
            package, _, short = name.rpartition(".")
            source = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("package " + package + "\nclass " + short + " : TestCase() {\n" +
                              "".join(" fun testCase" + str(i) + "() {}\n" for i in range(count)) + "}\n")
        return helper

    def test_exact_recorder_scope_keeps_order_independence_and_all_safety_modules(self):
        self.assertEqual(CLASSES, recorder.GENERATION_PORT_PLATFORM_CLASSES)
        for names in (CLASSES, tuple(reversed(CLASSES))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(names)))
            self.assertEqual("generation-port-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.GENERATION_PORT_MODULES, "-v"], command)
        for required in ("test_generation_port_scope", "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
                         "test_frozen_smoke_source",
                         "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
                         "test_smoke_boot.ProcessTests.test_strict_shared_result_rejects_ambiguous_and_failed_protocol",
                         "test_smoke_boot.ProcessTests.test_targeted_count_mismatch_or_runner_failure_is_not_success"):
            self.assertIn(required, recorder.GENERATION_PORT_MODULES)

    def test_incomplete_expanded_duplicate_and_other_mode_filters_keep_full_fallback(self):
        valid = ",".join(CLASSES)
        for raw in ("", ",".join(CLASSES[:-1]), valid + "," + CLASSES[0],
                    valid + ",app.daiyu.android.UnknownTest", valid + ",", " " + valid, valid + " "):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=raw))[1])
        for mode in ("", "build", "compile", "candidate", "daily", "release", "checks"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=valid))[1])

    def test_receipt_binds_old_source_and_new_tool_cwd_without_hiding_child_failure(self):
        env = dict(GITHUB_SHA="b" * 40, GITHUB_RUN_ID="43", GITHUB_RUN_ATTEMPT="1",
                   EXPECTED_SHA="a" * 40, SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(CLASSES))
        raw = b"generation scope child failure\n"
        with tempfile.TemporaryDirectory() as directory:
            old = os.getcwd()
            try:
                os.chdir(directory)
                def child(command, stdout, stderr, env, cwd):
                    self.assertEqual(ROOT, cwd)
                    self.assertEqual(str(ROOT / "tests"), env["PYTHONPATH"].split(os.pathsep)[0])
                    self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.GENERATION_PORT_MODULES, "-v"], command)
                    stdout.write(raw)
                    return 7
                console = SimpleNamespace(buffer=io.BytesIO())
                with patch.dict(os.environ, env, clear=True), \
                        patch.object(recorder.subprocess, "check_output", return_value="a" * 40 + "\n"), \
                        patch.object(recorder.subprocess, "call", side_effect=child), \
                        patch.object(recorder.sys, "stdout", console):
                    self.assertEqual(7, recorder.record("python3"))
                receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                self.assertEqual(dict(sha="a" * 40, run_id=43, attempt=1), receipt["identity"])
                self.assertEqual("b" * 40, receipt["workflow_sha"])
                self.assertEqual("b" * 40, receipt["validation_tools_sha"])
                self.assertEqual("generation-port-targeted", receipt["scope"])
                self.assertEqual(7, receipt["exit_code"])
                self.assertEqual(raw, Path("tool-evidence/unittest.log").read_bytes())
            finally:
                os.chdir(old)

    def test_inventory_is_exact_disjoint_and_complete_across_four_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            selected = []
            total = 0
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(CLASSES))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual(dict(zip(CLASSES, range(1, 8))), inventory["all_classes"])
                self.assertEqual(sorted(CLASSES), selection["requested_classes"])
                selected.extend(selection["selected_classes"])
                total += selection["expected_tests"]
            self.assertEqual(sorted(CLASSES), sorted(selected))
            self.assertEqual(28, total)

    def test_missing_and_ambiguous_requested_sources_fail_closed(self):
        for missing in (True, False):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper = self.fixture(root)
                source = root / "app/src/androidTest/kotlin" / (CLASSES[0].replace(".", "/") + ".kt")
                if missing:
                    source.unlink()
                else:
                    with source.open("a") as stream:
                        stream.write("class AmbiguousTest : TestCase() {}\n")
                result = helper.select(root, classes=",".join(CLASSES))
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root / "smoke/selection.json").exists())

    def test_mixed_duplicate_or_non_smoke_filters_do_not_narrow_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            valid = ",".join(CLASSES)
            for raw in (valid + ",app.daiyu.android.UnknownTest", valid + "," + CLASSES[0], ",".join(CLASSES[:-1])):
                result = helper.select(root, classes=raw)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            for mode in ("candidate", "build"):
                result = helper.select(root, classes=valid, mode=mode)
                self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / "smoke/selection.json").exists())

    def test_default_full_inventory_still_includes_other_valid_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            (root / discovery.SOURCE.parent / "UnrelatedTest.kt").unlink()
            result = helper.select(root, classes="", mode="build", count=1)
            self.assertEqual(0, result.returncode, result.stderr)
            inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
            self.assertEqual("full", inventory["discovery_scope"])
            self.assertEqual(set(CLASSES) | {discovery.CLASS}, set(inventory["all_classes"]))

    def test_frozen_generation_script_route_keeps_source_and_workflow_identities_separate(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        exact_route = ('if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "' + ",".join(CLASSES) +
                       '"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi;')
        self.assertIn(exact_route, workflow)
        self.assertIn("${{ inputs.mode == 'smoke' && inputs.smoke_classes == 'app.daiyu.android.GenerationInputImportTest,app.daiyu.android.storage.SQLiteGenerationAttemptStoreTest,app.daiyu.android.storage.SQLiteGenerationOutputStoreTest,app.daiyu.android.integration.tools.RunGenerationToolsDeviceTest,app.daiyu.android.ControllerGenerationResourcesTest,app.daiyu.android.ControllerGenerationIntegrationTest,app.daiyu.android.storage.GenerationProtocolMigrationTest' && '-generation-port' || '' }}", workflow)
        self.assertIn('ref: ${{ inputs.mode == \'smoke\' && inputs.expected_sha || github.sha }}', workflow)
        self.assertIn('ref: ${{ github.sha }}', workflow)
        self.assertIn('python3 .smoke-validation-tools/scripts/verify_smoke_source.py', workflow)
        self.assertIn('sha256sum -c SHA256SUMS', workflow)
        self.assertIn('test -s smoke/prelaunch-ready.txt && bash "$smoke_script"', workflow)
