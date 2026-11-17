from __future__ import annotations

import importlib.util
import json
import re
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/terminal-retention-checks.yml"
TARGET = "app.daiyu.android.terminal.TerminalScreenTest"


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class TerminalOutputCloudContract(unittest.TestCase):
    def test_actual_api_shard_options_matrix(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        condition = "inputs.mode == 'smoke' && inputs.smoke_api == 'all' && inputs.smoke_classes == 'app.daiyu.android.terminal.TerminalScreenTest' && '"
        start = workflow.index(condition) + len(condition)
        options, _ = json.JSONDecoder().raw_decode(workflow[start:])
        self.assertEqual(options, [
            {"api": "26", "target": "google_apis", "ram": 4096, "shard": 0},
            {"api": "37.2", "target": "google_apis_ps16k", "ram": 6144, "shard": 0},
        ])
        self.assertEqual([row["shard"] for row in options], [0, 0])
        self.assertIn("inputs.smoke_classes == 'app.daiyu.android.terminal.TerminalScreenTest')) && '1' || '4'", workflow)
        smoke = (ROOT / "scripts/smoke_boot.sh").read_text()
        self.assertIn("select_terminal_screen", smoke)
        self.assertIn(TARGET, smoke)

        from test_record_tool_tests_scope import recorder
        self.assertEqual(TARGET, recorder.TERMINAL_SCREEN_PLATFORM_CLASS)
        self.assertIn("test_terminal_screen_smoke_scope", recorder.TERMINAL_SCREEN_MODULES)

    def test_selector_executes_and_rejects_wrong_mode_api_class_or_shard(self):
        mod = load_module("terminal_retention_contract", ROOT / "scripts/acceptance/terminal_retention_contract.py")
        for api in (26, 37):
            self.assertEqual(mod.select_terminal_screen("smoke", "all", TARGET, api, 0), TARGET)
        bad = (("normal", "all", TARGET, 26, 0), ("smoke", "26", TARGET, 26, 0),
               ("smoke", "all", TARGET, 25, 0), ("smoke", "all", TARGET, 26, 1),
               ("smoke", "all", TARGET, 26, 2), ("smoke", "all", "other.Test", 26, 0))
        for args in bad:
            with self.subTest(args=args), self.assertRaises(ValueError):
                mod.select_terminal_screen(*args)

    def test_junit_suite_target_and_reject_missing_or_failed_xml(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("TerminalGridBufferTest", workflow)
        self.assertIn("validate_suites(suites, expected)", workflow)
        self.assertIn("assert not packages", workflow)
        mod = load_module("terminal_retention_contract", ROOT / "scripts/acceptance/terminal_retention_contract.py")
        target = "app.daiyu.android.terminal.TerminalGridBufferTest"
        good = ET.fromstring(f'<testsuite name="{target}" tests="1" failures="0" errors="0" skipped="0"/>')
        mod.validate_suites([good], {target})
        with self.assertRaises(ValueError):
            mod.validate_suites([], {target})
        failed = ET.fromstring('<testsuite name="x" tests="1" failures="1"/>')
        with self.assertRaises(ValueError):
            mod.validate_suites([failed], {"x"})

    def test_runtime_target_checks_keep_app_and_core_filters_and_evidence_separate(self):
        workflow = WORKFLOW.read_text()
        start = workflow.index(':app:compileDebugKotlin :app:testDebugUnitTest')
        split = workflow.index(':core-domain:test', start)
        end = workflow.index(':app:compileDebugAndroidTestKotlin', split)
        app_filters = re.findall(r"--tests '([^']+)'", workflow[start:split])
        core_filters = re.findall(r"--tests '([^']+)'", workflow[split:end])
        expected_app = {'app.daiyu.android.terminal.RootScopeCommandProcessTest', 'app.daiyu.android.terminal.TerminalGridBufferTest', 'app.daiyu.android.terminal.LinuxRootfsBackupAccessTest', 'app.daiyu.android.ui.screens.chat.conversation.TerminalToolTargetTest', 'app.daiyu.android.ui.screens.terminal.TerminalTaskOutputTest', 'app.daiyu.android.integration.tools.RunCommandToolsTest', 'app.daiyu.android.terminal.LinuxRunnerGenerationPinsTest', 'app.daiyu.android.terminal.PtyRootScopeProtocolTest', 'app.daiyu.android.terminal.LinuxWorkspaceDirectoryTest', 'app.daiyu.android.terminal.LinuxEnvironmentStoreTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapperTest', 'app.daiyu.android.integration.tools.ShizukuCommandTransportTest', 'app.daiyu.android.terminal.LinuxSettingsApplicationFreezeTest', 'app.daiyu.android.integration.tools.TerminalJobSessionTest', 'app.daiyu.android.terminal.TerminalRegistryRetentionTest', 'app.daiyu.android.terminal.TerminalJobHistoryReaderTest'}
        self.assertEqual(set(app_filters), expected_app)
        self.assertEqual(len(app_filters), len(expected_app))
        self.assertEqual(core_filters, ['app.daiyu.android.domain.runtime.TerminalExecutionTargetTest'])
        self.assertIn("shutil.rmtree('core-domain/build/test-results/test', ignore_errors=True)", workflow)
        self.assertIn("validate_suites(core_suites, {'app.daiyu.android.domain.runtime.TerminalExecutionTargetTest'})", workflow)
        self.assertIn("core_classes=sorted(s.attrib['name'] for s in core_suites)", workflow)
        self.assertIn("core_tests=sum(int(s.attrib['tests']) for s in core_suites)", workflow)
        upload = workflow[workflow.index('- name: 上传检查证据'):]
        self.assertIn('core-domain/build/test-results/test/', upload)

    def test_instrumentation_parser_nonzero_failure_and_smoke_hard_gate(self):
        parser = load_module("instrumentation_result", ROOT / "scripts/acceptance/instrumentation_result.py")
        failing_input = (b"INSTRUMENTATION_STATUS: class=Target\nINSTRUMENTATION_STATUS: test=works\n"
                         b"INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: 0\n")
        with self.assertRaises(parser.ResultError):
            parser.instrumentation(failing_input, 1)
        smoke = (ROOT / "scripts/smoke_boot.sh").read_text()
        self.assertIn("set -euo pipefail", smoke)
        self.assertIn('python3 "$SCRIPT_DIR/acceptance/instrumentation_result.py"', smoke)
        self.assertIn("> smoke/instrumentation-result.json", smoke)
        self.assertLess(smoke.index("adb_stage instrumentation"), smoke.index("instrumentation_result.py"))

    def test_source_binding_and_always_evidence_upload_fields(self):
        source = (ROOT / "scripts/verify_smoke_source.py").read_text()
        self.assertIn("SOURCE_SHA", source)
        self.assertIn("source_run['head_sha'] != sha", source)
        self.assertIn("record['source_sha'] != sha", source)
        self.assertIn("SMOKE_TEST_CLASSES", source)
        verifier = load_module("verify_smoke_source", ROOT / "scripts/verify_smoke_source.py")
        with self.assertRaises(ValueError):
            verifier.verify({"SOURCE_SHA": "a" * 40, "SOURCE_RUN_ID": "12"}, {},
                           {"id": 12, "run_attempt": 1, "head_sha": "b" * 40,
                            "head_branch": "main", "workflow_id": 397560064,
                            "status": "completed", "conclusion": "success"}, [], [])
        workflow = WORKFLOW.read_text()
        lines = workflow.splitlines()
        upload = next(i for i, line in enumerate(lines) if "name: 上传检查证据" in line)
        block = "\n".join(lines[upload:upload + 14])
        self.assertIn("if: always()", block)
        for field in ("terminal-retention-checks/", "testDebugUnitTest", "host-pty"):
            self.assertIn(field, block)


if __name__ == "__main__":
    unittest.main()
