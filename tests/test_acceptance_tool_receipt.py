"""Producer tests use mocked children, never execute the unittest command."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "tool_receipt_producer", ROOT / "scripts/acceptance/record_tool_tests.py")
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)


class ToolReceiptTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "tool-evidence"
        self.env = dict(GITHUB_SHA="b" * 40, GITHUB_RUN_ID="43", GITHUB_RUN_ATTEMPT="2")

    def test_exact_command_identity_raw_bytes_and_real_exit_for_both_interpreters(self):
        raw = b"testExample (fixture.Suite.testExample) ... FAIL\r\nraw stderr\x00\n"
        for python in ("python", "python3"):
            for code in (0, 1, 7):
                with self.subTest(python=python, code=code):
                    def child(command, stdout, stderr):
                        self.assertEqual(command, [python, "-B", "-m", "unittest", "discover", "-s", "tests", "-v"])
                        self.assertEqual(stderr, subprocess.STDOUT)
                        stdout.write(raw)
                        return code
                    console = io.BytesIO()
                    with patch.dict(os.environ, self.env), patch.object(producer, "Path", return_value=self.root), \
                            patch.object(producer.subprocess, "call", side_effect=child), \
                            patch.object(producer.sys, "stdout", SimpleNamespace(buffer=console)):
                        self.assertEqual(producer.main(["--python", python]), code)
                    receipt = json.loads((self.root / "receipt.json").read_text(encoding="utf-8"))
                    self.assertEqual(receipt, dict(identity=dict(sha="b" * 40, run_id=43, attempt=2),
                        command=[python, "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], exit_code=code))
                    self.assertEqual((self.root / "unittest.log").read_bytes(), raw)
                    self.assertEqual(console.getvalue(), raw)

    def test_interruption_or_spawn_failure_cannot_leave_a_success_receipt(self):
        for error in (OSError("synthetic spawn failure"), KeyboardInterrupt()):
            with self.subTest(error=type(error).__name__):
                self.root.mkdir(exist_ok=True)
                (self.root / "receipt.json").write_text('{"exit_code":0}')
                with patch.dict(os.environ, self.env), patch.object(producer, "Path", return_value=self.root), \
                        patch.object(producer.subprocess, "call", side_effect=error):
                    with self.assertRaises(type(error)):
                        producer.record("python3")
                self.assertFalse((self.root / "receipt.json").exists())

    def test_workflow_wires_current_check_tools_and_keeps_smoke_exit_missing_on_timeout(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        checks = workflow.split("  checks:\n", 1)[1].split("  build:\n", 1)[0]
        build = workflow.split("  build:\n", 1)[1].split("  smoke:\n", 1)[0]
        smoke = workflow.split("  smoke:\n", 1)[1]
        self.assertIn("python scripts/acceptance/record_tool_tests.py --python python", checks)
        self.assertIn("python3 scripts/acceptance/record_tool_tests.py --python python3", build)
        self.assertNotIn("unittest discover -s tests -q", workflow)
        for job in (checks, build):
            self.assertIn("tool-evidence/", job)
            self.assertIn("if: always()", job)
        self.assertIn("if: inputs.mode == 'smoke'", smoke)
        self.assertIn("python3 scripts/acceptance/record_tool_tests.py --python python3", smoke)
        self.assertIn("代鱼工具证据-smoke-API${{ matrix.api }}", smoke)
        self.assertIn("path: tool-evidence/", smoke)
        self.assertIn('code=$?; printf "%s\\n" "$code" > smoke/exit-code.txt; exit "$code"', smoke)
        self.assertNotIn("echo 0", smoke)
        self.assertIn("script: bash -c 'set +e; bash scripts/smoke_boot.sh;", smoke)
        self.assertIn("name: 上传启动证据\n        if: always()", smoke)
        self.assertNotIn("final_delivery.py", workflow)
