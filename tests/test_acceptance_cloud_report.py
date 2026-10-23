"""Synthetic offline fixtures only: these tests are not cloud/business evidence."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

BASE = Path(__file__).resolve().parents[1] / "scripts" / "acceptance"
SPEC = importlib.util.spec_from_file_location("cloud_acceptance_evidence", BASE / "evidence.py")
evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evidence)
SPEC = importlib.util.spec_from_file_location("acceptance_cloud_report", BASE / "cloud_report.py")
cloud = importlib.util.module_from_spec(SPEC)
# Avoid collision with scripts/cooperation/evidence.py loaded by other tests.
with patch.dict(sys.modules, {"evidence": evidence}), patch.object(sys, "path", [str(BASE), *sys.path]):
    SPEC.loader.exec_module(cloud)

SHA = "a" * 40


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


@unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY")
                     and os.open in os.supports_dir_fd, "safe POSIX I/O unavailable")
class CloudReportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.snapshot_path = self.root / "snapshot.json"
        self.snapshot = {"schema_version": 1, "run": {
            "id": 42, "run_attempt": 1, "head_sha": SHA,
            "repository": {"full_name": "y2485871697/Daiyu"}, "workflow_id": 383377276,
            "head_branch": "main", "status": "completed", "conclusion": "success"},
            "jobs": [], "reports": []}
        self.reports = {}
        for job_id, api in enumerate(cloud.APIS, 101):
            folder = self.root / api
            folder.mkdir()
            instrumentation = ("INSTRUMENTATION_STATUS: numtests=2\n"
                               "INSTRUMENTATION_STATUS: current=1\n"
                               "INSTRUMENTATION_STATUS_CODE: 1\n"
                               "INSTRUMENTATION_STATUS_CODE: 0\n"
                               "INSTRUMENTATION_RESULT: stream=\n"
                               "Time: 0.2\n\nOK (2 tests)\n\nINSTRUMENTATION_CODE: -1\n")
            (folder / "platform-tests.txt").write_bytes(instrumentation.encode())
            (folder / "logcat.txt").write_bytes(b"I/TestRunner: finished fixture\n")
            report = {"schema_version": 1, "run_id": 42, "run_attempt": 1, "head_sha": SHA,
                      "job_id": job_id, "api": api, "exit_code": 0, "expected_tests": 2,
                      "instrumentation": self.text_ref(api + "/platform-tests.txt"),
                      "logcat": self.text_ref(api + "/logcat.txt")}
            self.reports[api] = report
            self.snapshot["jobs"].append({
                "id": job_id, "run_id": 42, "run_attempt": 1, "head_sha": SHA, "api": api,
                "name": "启动验证 API " + api, "status": "completed", "conclusion": "success",
                "report_sha256": "0" * 64})
            self.snapshot["reports"].append({"api": api, "job_id": job_id,
                                              "path": api + "/report.json", "sha256": "0" * 64})
        self.sync_reports()

    def text_ref(self, name):
        return {"path": name, "sha256": digest((self.root / name).read_bytes())}

    def sync_reports(self):
        for api, report in self.reports.items():
            raw = json.dumps(report).encode()
            (self.root / api / "report.json").write_bytes(raw)
            for ref in self.snapshot["reports"]:
                if ref["api"] == api:
                    ref["sha256"] = digest(raw)
            for job in self.snapshot["jobs"]:
                if job["api"] == api:
                    job["report_sha256"] = digest(raw)

    def save(self):
        raw = json.dumps(self.snapshot).encode()
        self.snapshot_path.write_bytes(raw)
        return digest(raw)

    def verify(self, **kwargs):
        args = dict(snapshot_path=self.snapshot_path, snapshot_sha256=self.save(),
                    build_sha=SHA, run_id=42, attempt=1, evidence_root=self.root)
        args.update(kwargs)
        return cloud.verify(**args)

    def reject(self, code=None, **kwargs):
        with self.assertRaises(evidence.InvalidEvidence) as caught:
            self.verify(**kwargs)
        if code:
            self.assertEqual(str(caught.exception), code)

    def change_text(self, text, role="instrumentation", api="26"):
        ref = self.reports[api][role]
        (self.root / ref["path"]).write_bytes(text.encode())
        ref["sha256"] = digest(text.encode())
        self.sync_reports()

    def cli_args(self):
        return ["--snapshot", str(self.snapshot_path), "--snapshot-sha256", self.save(),
                "--build-sha", SHA, "--run-id", "42", "--attempt", "1",
                "--evidence-root", str(self.root)]

    def assert_failed_report(self, code, report):
        self.assertEqual(code, 2)
        self.assertEqual(set(report), {"valid", "error", "cloud_regression_satisfied",
                                      "business_satisfied", "trusted_collection",
                                      "acceptance_items_credited"})
        for key in ("valid", "cloud_regression_satisfied", "business_satisfied",
                    "trusted_collection"):
            self.assertIs(report[key], False)
        self.assertEqual(report["acceptance_items_credited"], 0)
        self.assertIs(type(report["acceptance_items_credited"]), int)
        self.assertNotIn(str(self.root), json.dumps(report))

    def cli(self, args=None):
        if args is None:
            args = self.cli_args()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = cloud.main(args)
        report = json.loads(output.getvalue())
        if code == 2:
            self.assert_failed_report(code, report)
        return code, report

    def file_cli(self, args=None):
        # Execute only the local Python entry point, never report-supplied commands.
        result = subprocess.run([sys.executable, "-B", str(BASE / "cloud_report.py"),
                                 *(self.cli_args() if args is None else args)],
                                cwd=self.root, capture_output=True, text=True,
                                timeout=10, check=False)
        self.assertEqual(result.stderr, "")
        report = json.loads(result.stdout)
        if result.returncode == 2:
            self.assert_failed_report(result.returncode, report)
        return result.returncode, report

    def test_full_matrix_internal_consistency_only_and_repeat(self):
        for _ in range(2):
            result = self.verify()
            self.assertTrue(result["cloud_regression_satisfied"])
            self.assertFalse(result["business_satisfied"])
            self.assertFalse(result["trusted_collection"])
            self.assertEqual(result["acceptance_items_credited"], 0)
            self.assertEqual([r["api"] for r in result["jobs"]], ["26", "37.2"])
            self.assertEqual([r["tests_passed"] for r in result["jobs"]], [2, 2])
            self.assertNotIn("path", json.dumps(result))

    def test_missing_api_is_incomplete_not_passed(self):
        self.snapshot["jobs"].pop()
        self.snapshot["reports"].pop()
        report = self.verify()
        self.assertFalse(report["cloud_regression_satisfied"])
        self.assertEqual(report["missing_apis"], ["37.2"])
        code, report = self.cli()
        self.assertEqual(code, 1)
        self.assertTrue(report["valid"])
        self.snapshot["jobs"].clear()
        self.snapshot["reports"].clear()
        self.assertFalse(self.verify()["cloud_regression_satisfied"])

    def test_dangling_missing_report_rejects(self):
        self.snapshot["reports"].pop()
        self.reject("job_report_coverage_mismatch")

    def test_expected_sha_run_attempt_and_pin_cannot_rebind(self):
        for args in ({"build_sha": "b" * 40}, {"run_id": 43}, {"attempt": 2}):
            with self.subTest(args=args):
                self.reject("execution_identity_mismatch", **args)
        self.reject("snapshot_pin_mismatch", snapshot_sha256="0" * 64)
        for sha in (SHA[:8], SHA.upper(), None, 123):
            self.reject("invalid_build_sha", build_sha=sha)
        self.assertEqual(evidence.full_sha("a" * 64), "a" * 64)

    def test_exact_repository_workflow_and_branch(self):
        run = self.snapshot["run"]
        for key, bad in (("repository", {"full_name": "other/Daiyu"}),
                         ("workflow_id", 383377277), ("head_branch", "feature")):
            original = copy.deepcopy(run[key])
            run[key] = bad
            self.reject("repository_workflow_branch_mismatch")
            run[key] = original

    def test_all_terminal_states_other_than_success_rejected(self):
        for obj in (self.snapshot["run"], *self.snapshot["jobs"]):
            for status, conclusion in (("queued", None), ("in_progress", "success"),
                                       ("completed", "failure"), ("completed", "cancelled"),
                                       ("completed", "timed_out"), ("completed", "neutral"),
                                       ("completed", "skipped"), ("completed", "unknown")):
                with self.subTest(status=status, conclusion=conclusion):
                    obj.update(status=status, conclusion=conclusion)
                    self.reject("terminal_success_required")
            obj.update(status="completed", conclusion="success")

    def test_strict_integer_fields_not_bool_string_float(self):
        targets = [(self.snapshot, "schema_version"),
                   *[(self.snapshot["run"], k) for k in ("id", "run_attempt", "workflow_id")],
                   *[(self.snapshot["jobs"][0], k) for k in ("id", "run_id", "run_attempt")],
                   (self.snapshot["reports"][0], "job_id")]
        for obj, key in targets:
            original = obj[key]
            for bad in (True, False, "1", 1.0, 0, -1, None, 2**64):
                with self.subTest(key=key, bad=bad):
                    obj[key] = bad
                    self.reject()
            obj[key] = original
        for key in ("schema_version", "run_id", "run_attempt", "job_id", "exit_code", "expected_tests"):
            original = self.reports["26"][key]
            for bad in (True, False, "0", 0.0, None):
                self.reports["26"][key] = bad
                self.sync_reports()
                self.reject()
            self.reports["26"][key] = original
        self.sync_reports()
        for key in ("run_id", "attempt"):
            self.reject(**{key: True})

    def test_cross_run_attempt_sha_job_and_api_in_reports(self):
        report = self.reports["26"]
        for key, bad in (("run_id", 43), ("run_attempt", 2), ("head_sha", "b" * 40),
                         ("job_id", 102), ("api", "37.2"), ("exit_code", 1),
                         ("expected_tests", 3)):
            original = report[key]
            report[key] = bad
            self.sync_reports()
            self.reject()
            report[key] = original
        self.sync_reports()
        for key, bad in (("run_id", 43), ("run_attempt", 2), ("head_sha", "b" * 40),
                         ("name", "启动验证 API 37.2")):
            job = self.snapshot["jobs"][0]
            original = job[key]
            job[key] = bad
            self.reject()
            job[key] = original

    def test_unknown_duplicate_or_non_string_api_and_duplicate_job(self):
        for bad in (26, 37.2, True, "27", "unknown", None):
            self.snapshot["jobs"][0]["api"] = bad
            self.reject("unsupported_api")
        self.snapshot["jobs"][0]["api"] = "26"
        self.snapshot["jobs"][1]["id"] = 101
        self.reject("duplicate_job")
        self.snapshot["jobs"][1]["id"] = 102
        self.snapshot["reports"][1] = copy.deepcopy(self.snapshot["reports"][0])
        self.reject("duplicate_api_report")

    def test_unknown_schema_keys_reject_including_derived_passed_command(self):
        for obj in (self.snapshot, self.snapshot["run"], self.snapshot["jobs"][0]):
            obj["command"] = ["secret-should-not-run"]
            self.reject()
            del obj["command"]
        self.reports["26"]["passed"] = True
        self.sync_reports()
        self.reject("invalid_api_report")

    def test_original_text_requires_unique_exact_nonzero_ok(self):
        for text in ("passed", "OK (0 tests)\n", "OK (3 tests)\n", "OK (2 tests)\nOK (2 tests)\n",
                     "prefix OK (2 tests) suffix\n", " OK (2 tests)\n", "OK (2 tests)\nOK (3 tests)\n"):
            with self.subTest(text=text):
                self.change_text(text)
                self.reject()
        self.change_text("OK (2 tests)\r\nINSTRUMENTATION_CODE: -1\r\n")
        self.assertTrue(self.verify()["cloud_regression_satisfied"])

    def test_failure_crash_cancel_timeout_and_contradictions_override_ok(self):
        signals = ("FAILURES!!!", "INSTRUMENTATION_FAILED: nope", "Process crashed.",
                   "FATAL EXCEPTION: main", "java.lang.AssertionError: nope", "shortMsg=oops",
                   "Error: runner", "timeout", "timed out", "cancelled", "skipped",
                   "INSTRUMENTATION_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2",
                   "INSTRUMENTATION_STATUS: numtests=3", "INSTRUMENTATION_STATUS: current=3",
                   "INSTRUMENTATION_RESULT: unknown=1", "INSTRUMENTATION_FUTURE: unknown")
        for signal in signals:
            with self.subTest(signal=signal):
                self.change_text("OK (2 tests)\n" + signal + "\n")
                self.reject()
        self.change_text("OK (2 tests)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: -1\n")
        self.reject("instrumentation_terminal_conflict")
        self.change_text("OK (2 tests)\nINSTRUMENTATION_CODE: -1\n")
        for text in ("FATAL EXCEPTION: main\n", "process crashed\n", "error\n"):
            self.change_text(text, role="logcat")
            self.reject("negative_report_signal")

    def test_identifier_metadata_is_not_an_exception_but_diagnostics_still_reject(self):
        suffix = "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n"
        for name in ("testPreferenceWriteFailureKeepsConfirmedChoiceAndShowsError", "testRejectsException"):
            prefix = ("INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
                      "INSTRUMENTATION_STATUS: class=app.daiyu.android.FixtureException\n"
                      f"INSTRUMENTATION_STATUS: test={name}\n")
            for status in (0, -2, -3, -4):
                with self.subTest(name=name, status=status):
                    self.change_text(prefix + f"INSTRUMENTATION_STATUS_CODE: {status}\n" + suffix)
                    if status == 0:
                        self.assertTrue(self.verify()["cloud_regression_satisfied"])
                        self.assertEqual(0, self.file_cli()[0])
                    else:
                        self.reject("instrumentation_status_conflict")
            for signal in ("java.lang.AssertionError: nope", "INSTRUMENTATION_STATUS: stream=java.lang.Exception: nope",
                           "INSTRUMENTATION_STATUS: test=fixture Error: nope", "INSTRUMENTATION_STATUS: test=foo\tException"):
                self.change_text(prefix + signal + "\n" + suffix)
                self.reject("negative_report_signal")
        self.change_text(suffix)
        self.change_text("INSTRUMENTATION_STATUS: test=testRejectsException\n", role="logcat")
        self.reject("negative_report_signal")

    def test_mandatory_final_terminal_code_through_verify_and_file_cli(self):
        cases = (
            ("OK (2 tests)\n", "instrumentation_terminal_conflict"),
            ("INSTRUMENTATION_CODE: -1\nOK (2 tests)\n", "instrumentation_not_terminal"),
            ("OK (2 tests)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_STATUS_CODE: 0\n",
             "instrumentation_not_terminal"),
            ("OK (2 tests)\nINSTRUMENTATION_CODE: -1\ntrailing collector summary\n",
             "instrumentation_not_terminal"),
        )
        for text, error in cases:
            with self.subTest(text=text):
                self.change_text(text)
                self.reject(error)
                code, report = self.file_cli()
                self.assert_failed_report(code, report)
                self.assertEqual(report["error"], error)

    def test_prefixed_protocol_markers_reject_through_verify_and_cli(self):
        # Rehash every layer: these must fail on protocol, not a stale digest.
        signals = ("INSTRUMENTATION_STATUS_CODE: -2", "INSTRUMENTATION_CODE: 0",
                   "INSTRUMENTATION_STATUS: numtests=3", "INSTRUMENTATION_STATUS: current=3",
                   "INSTRUMENTATION_FUTURE: unknown", "INSTRUMENTATION_RESULT: unknown=1",
                   # Even otherwise legal protocol cannot be normalized from a prefix.
                   "INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_CODE: -1",
                   "INSTRUMENTATION_STATUS: numtests=2", "INSTRUMENTATION_STATUS: current=1")
        for prefix in (" ", "\t", "I/TestRunner: "):
            for signal in signals:
                with self.subTest(prefix=prefix, signal=signal):
                    text = "OK (2 tests)\n" + prefix + signal + "\n"
                    with self.assertRaisesRegex(evidence.InvalidEvidence,
                                                "^unsupported_instrumentation_line$"):
                        cloud._instrumentation(text.encode(), 2)
                    self.change_text(text)
                    self.reject("unsupported_instrumentation_line")
                    for invoke in (self.cli, self.file_cli):
                        code, report = invoke()
                        self.assert_failed_report(code, report)
                        self.assertEqual(report["error"], "unsupported_instrumentation_line")

    def test_embedded_protocol_marker_cannot_hide_in_supported_payload(self):
        for outer in ("INSTRUMENTATION_STATUS: stream=", "INSTRUMENTATION_RESULT: stream=",
                      "INSTRUMENTATION_STATUS: test="):
            with self.subTest(outer=outer):
                self.change_text("OK (2 tests)\n" + outer + "INSTRUMENTATION_CODE: 0\n")
                self.reject("unsupported_instrumentation_line")
                code, report = self.file_cli()
                self.assert_failed_report(code, report)
                self.assertEqual(report["error"], "unsupported_instrumentation_line")

    def test_file_entry_exit_codes_boundaries_and_no_report_commands(self):
        code, report = self.file_cli()
        self.assertEqual(code, 0)
        self.assertTrue(report["valid"])
        self.assertTrue(report["cloud_regression_satisfied"])
        self.assertIs(report["business_satisfied"], False)
        self.assertIs(report["trusted_collection"], False)
        self.assertEqual(report["acceptance_items_credited"], 0)
        for bad_args in ([], ["--secret-token-private"]):
            code, report = self.file_cli(bad_args)
            self.assert_failed_report(code, report)
            self.assertNotIn("private", json.dumps(report))
        args = self.cli_args()
        args[3] = "0" * 64
        code, report = self.file_cli(args)
        self.assert_failed_report(code, report)
        self.assertEqual(report["error"], "snapshot_pin_mismatch")
        args[1] = str(self.root / "missing-private.json")
        code, report = self.file_cli(args)
        self.assert_failed_report(code, report)
        self.assertEqual(report["error"], "unreadable_or_invalid_input")
        self.assertNotIn("private", json.dumps(report))
        # If a command were wrongly executed it would leave a local marker.
        marker = self.root / "command-must-not-run"
        self.reports["26"]["command"] = [sys.executable, "-c",
                                          "from pathlib import Path; "
                                          f"Path({str(marker)!r}).touch()"]
        self.sync_reports()
        code, report = self.file_cli()
        self.assert_failed_report(code, report)
        self.assertEqual(report["error"], "invalid_api_report")
        self.assertFalse(marker.exists())
        del self.reports["26"]["command"]
        self.sync_reports()
        self.snapshot["jobs"].pop()
        self.snapshot["reports"].pop()
        code, report = self.file_cli()
        self.assertEqual(code, 1)
        self.assertTrue(report["valid"])
        self.assertIs(report["cloud_regression_satisfied"], False)
        self.assertIs(report["business_satisfied"], False)
        self.assertIs(report["trusted_collection"], False)
        self.assertEqual(report["acceptance_items_credited"], 0)

    def test_filtered_job_rejected_by_legacy_report_too(self):
        self.snapshot['jobs'][0]['name'] += '-filtered'
        self.reject('job_api_mismatch')

    def test_digest_chain_all_layers_and_recheck_after_mutation(self):
        self.assertTrue(self.verify()["cloud_regression_satisfied"])
        path = self.root / "26/platform-tests.txt"
        path.write_bytes(b"OK (2 tests)\nforged\n")
        self.reject("artifact_digest_mismatch")
        self.change_text("OK (2 tests)\nINSTRUMENTATION_CODE: -1\n")
        (self.root / "26/report.json").write_bytes(b"{}")
        self.reject("report_digest_mismatch")
        self.sync_reports()
        self.snapshot["reports"][0]["sha256"] = "0" * 64
        self.reject("job_report_digest_mismatch")
        for pin in ("sha256:" + "0" * 64, "G" * 64, "a" * 63, None):
            self.reject("invalid_digest", snapshot_sha256=pin)

    def test_paths_traversal_absolute_backslash_drive_and_shared_files(self):
        ref = self.reports["26"]["instrumentation"]
        original = ref["path"]
        for path in ("../secret", "/tmp/secret", "26/../secret", "26//file", "./file",
                     "C:/secret", "26\\file", "", "26/\x00file"):
            ref["path"] = path
            self.sync_reports()
            self.reject("invalid_artifact_path")
        ref["path"] = original
        self.reports["37.2"]["instrumentation"] = copy.deepcopy(ref)
        self.sync_reports()
        self.reject("reused_report_path")

    def test_symlink_files_and_parent_components_are_not_followed(self):
        for relative in ("26/platform-tests.txt", "26/report.json", "snapshot.json"):
            self.save()
            path = self.root / relative
            raw = path.read_bytes()
            target = self.root / "target"
            target.write_bytes(raw)
            path.unlink()
            path.symlink_to(target)
            try:
                with self.assertRaises((OSError, evidence.InvalidEvidence)):
                    cloud.verify(self.snapshot_path, digest(self.snapshot_path.read_bytes()),
                                 SHA, 42, 1, self.root)
            finally:
                path.unlink()
                path.write_bytes(raw)
                target.unlink()
        linked = self.root / "linked-root"
        linked.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises((OSError, evidence.InvalidEvidence)):
            self.verify(evidence_root=linked)
        (self.root / "26/platform-tests.txt").unlink()
        (self.root / "26/platform-tests.txt").mkdir()
        self.reject("not_regular_file")

    def test_bounded_json_text_invalid_encoding_and_control_bytes(self):
        self.change_text("x" * (cloud.MAX_TEXT_BYTES + 1))
        self.reject("file_too_large")
        self.change_text("OK (2 tests)\n\x1b[31m")
        self.reject("invalid_report_text")
        path = self.root / "26/platform-tests.txt"
        path.write_bytes(b"\xff")
        self.reports["26"]["instrumentation"] = self.text_ref("26/platform-tests.txt")
        self.sync_reports()
        self.reject("invalid_report_encoding")
        self.change_text("OK (2 tests)\nINSTRUMENTATION_CODE: -1\n")
        raw = b" " * (evidence.MAX_JSON_BYTES + 1)
        self.snapshot_path.write_bytes(raw)
        with self.assertRaisesRegex(evidence.InvalidEvidence, "file_too_large"):
            cloud.verify(self.snapshot_path, digest(raw), SHA, 42, 1, self.root)

    def test_duplicate_json_keys_nan_and_malformed_json(self):
        for raw, code in ((b'{"schema_version":1,"schema_version":1}', "duplicate_json_key"),
                          (b'{"x":NaN}', "invalid_json_number"),
                          (b'{', "invalid_json")):
            self.snapshot_path.write_bytes(raw)
            with self.assertRaisesRegex(evidence.InvalidEvidence, code):
                cloud.verify(self.snapshot_path, digest(raw), SHA, 42, 1, self.root)

    def test_cli_pin_mismatch_missing_file_and_symlink_fail_closed(self):
        pin = self.save()
        args = ["--snapshot", str(self.snapshot_path), "--snapshot-sha256", "0" * 64,
                "--build-sha", SHA, "--run-id", "42", "--attempt", "1",
                "--evidence-root", str(self.root)]
        code, report = self.cli(args)
        self.assertEqual(code, 2)
        self.assertEqual(report["error"], "snapshot_pin_mismatch")
        args[3] = pin
        target = self.root / "original-snapshot.json"
        self.snapshot_path.rename(target)
        self.snapshot_path.symlink_to(target)
        code, report = self.cli(args)
        self.assertEqual(code, 2)
        self.assertFalse(report["cloud_regression_satisfied"])
        self.snapshot_path.unlink()
        code, report = self.cli(args)
        self.assertEqual(code, 2)
        self.assertEqual(report["error"], "unreadable_or_invalid_input")
        self.assertNotIn(str(self.root), json.dumps(report))

    def test_cli_exit_success_invalid_input_and_no_sensitive_echo(self):
        code, report = self.cli()
        self.assertEqual(code, 0)
        self.assertTrue(report["valid"])
        self.assertFalse(report["business_satisfied"])
        for args in ([], ["--secret-token-private"], ["--run-id", "private-value"],
                     ["--snapshot", "/private/path"]):
            code, report = self.cli(args)
            self.assertEqual(code, 2)
            self.assertFalse(report["valid"])
            self.assertFalse(report["cloud_regression_satisfied"])
            self.assertNotIn("private", json.dumps(report))
        self.save()
        with patch.object(cloud, "verify", side_effect=OSError("/private/secret")):
            code, report = self.cli()
        self.assertEqual(code, 2)
        self.assertNotIn("private", json.dumps(report))
        self.assertFalse(report["business_satisfied"])


@unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY")
                     and os.open in os.supports_dir_fd, "safe POSIX I/O unavailable")
class ShardedCloudTests(unittest.TestCase):
    def setUp(self):
        self.fixture = CloudReportTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.snapshot = self.fixture.snapshot
        self.snapshot.update(schema_version=2, jobs=[], reports=[])
        self.reports = {}
        classes = {f"fixture.Class{i}Test": 1 for i in range(4)}
        groups = [dict(index=i, expected_tests=1, classes=[f"fixture.Class{i}Test"]) for i in range(4)]
        for api in cloud.APIS:
            for index in range(4):
                base = f"{api}/shard-{index}/"
                (self.root / base).mkdir()
                def put(name, value):
                    path = self.root / (base + name)
                    path.write_bytes(value if isinstance(value, bytes) else json.dumps(value).encode())
                    return self.fixture.text_ref(base + name)
                identity = dict(shard_index=index, shard_count=4)
                job_id = 101 + (0 if api == "26" else 4) + index
                report = dict(schema_version=2, run_id=42, run_attempt=1, head_sha=SHA,
                    job_id=job_id, api=api, exit_code=0, expected_tests=1, **identity,
                    instrumentation=put("platform-tests.txt", b"OK (1 test)\nINSTRUMENTATION_CODE: -1\n"),
                    logcat=put("logcat.txt", b"I/Runner: finished\n"),
                    inventory=put("inventory.json", dict(schema_version=1, **identity,
                        total_tests=4, all_classes=classes, shards=groups)))
                self.reports[(api,index)] = report
                self.snapshot["jobs"].append(dict(id=job_id, run_id=42, run_attempt=1, head_sha=SHA,
                    api=api, name=f"启动验证 API {api} 分片{index}", status="completed", conclusion="success",
                    report_sha256="0"*64, **identity))
                self.snapshot["reports"].append(dict(api=api, job_id=job_id, path=base+"report.json",
                    sha256="0"*64, **identity))
        self.sync()

    def sync(self):
        for ref in self.snapshot["reports"]:
            report = self.reports[(ref["api"], ref["shard_index"])]
            raw = json.dumps(report).encode()
            (self.root / ref["path"]).write_bytes(raw)
            ref["sha256"] = digest(raw)
            next(j for j in self.snapshot["jobs"] if j["id"] == ref["job_id"])["report_sha256"] = digest(raw)

    def verify(self):
        return self.fixture.verify()

    def test_candidate_job_cannot_satisfy_formal_cloud(self):
        self.snapshot['jobs'][0]['name'] = '定向候选验证 API 26 分片0-filtered'
        with self.assertRaisesRegex(evidence.InvalidEvidence, '^job_api_mismatch$'):
            self.verify()

    def test_filtered_job_cannot_satisfy_full_eight_shard_matrix(self):
        self.assertTrue(self.verify()['cloud_regression_satisfied'])
        self.assertEqual(len(self.snapshot['jobs']), 8)
        for job in self.snapshot['jobs']:
            original = job['name']
            try:
                with self.subTest(api=job['api'], shard=job['shard_index']):
                    job['name'] = original + '-filtered'
                    with self.assertRaisesRegex(evidence.InvalidEvidence, '^job_api_mismatch$'):
                        self.verify()
            finally:
                job['name'] = original
        self.assertTrue(self.verify()['cloud_regression_satisfied'])
        for job in self.snapshot['jobs']:
            job['name'] += '-filtered'
        code, report = self.fixture.file_cli()
        self.assertEqual(code, 2)
        self.assertEqual(report['error'], 'job_api_mismatch')
        self.assertFalse(report['cloud_regression_satisfied'])

    def test_all_eight_and_missing_one_or_six_are_incomplete(self):
        result = self.verify()
        self.assertTrue(result["cloud_regression_satisfied"])
        self.assertEqual(len(result["jobs"]), 8)
        self.assertFalse(result["trusted_collection"])
        self.snapshot["jobs"].pop()
        self.snapshot["reports"].pop()
        result = self.verify()
        self.assertFalse(result["cloud_regression_satisfied"])
        self.assertEqual(result["missing_shards"], [dict(api="37.2", shard_index=3)])
        self.snapshot["jobs"] = [j for j in self.snapshot["jobs"] if j["shard_index"] == 0]
        self.snapshot["reports"] = [r for r in self.snapshot["reports"] if r["shard_index"] == 0]
        self.assertFalse(self.verify()["cloud_regression_satisfied"])

    def test_invalid_shard_identity_or_any_failed_job_rejects(self):
        job = self.snapshot["jobs"][0]
        for bad in (True, "0", -1, 4, None):
            job["shard_index"] = bad
            with self.assertRaises(evidence.InvalidEvidence): self.verify()
        job["shard_index"] = 0
        for conclusion in ("failure", "cancelled", "skipped"):
            job["conclusion"] = conclusion
            with self.assertRaises(evidence.InvalidEvidence): self.verify()
        job["conclusion"] = "success"
        self.reports[("26",0)]["shard_index"] = 1
        self.sync()
        with self.assertRaises(evidence.InvalidEvidence): self.verify()

    def test_overlap_omission_count_or_changed_inventory_rejects(self):
        report = self.reports[("26",0)]
        path = self.root / report["inventory"]["path"]
        original = path.read_bytes()
        for change in (lambda v: v["shards"][1]["classes"].__setitem__(0,"fixture.Class0Test"),
                       lambda v: v["shards"][0]["classes"].clear(),
                       lambda v: v["shards"][0].__setitem__("expected_tests",2),
                       lambda v: v.__setitem__("total_tests",5),
                       lambda v: v["shards"].reverse()):
            value = json.loads(original)
            change(value)
            path.write_bytes(json.dumps(value).encode())
            report["inventory"] = self.fixture.text_ref(str(path.relative_to(self.root)))
            self.sync()
            with self.assertRaises(evidence.InvalidEvidence): self.verify()
        path.write_bytes(original)
        report["inventory"] = self.fixture.text_ref(str(path.relative_to(self.root)))
        self.sync()
        self.assertTrue(self.verify()["cloud_regression_satisfied"])

    def test_duplicate_keys_in_inventory_and_cross_piece_paths_reject(self):
        report = self.reports[("26",0)]
        path = self.root / report["inventory"]["path"]
        path.write_text('{"schema_version":1,"schema_version":1}')
        report["inventory"] = self.fixture.text_ref(str(path.relative_to(self.root)))
        self.sync()
        with self.assertRaises(evidence.InvalidEvidence): self.verify()


if __name__ == "__main__":
    unittest.main()
