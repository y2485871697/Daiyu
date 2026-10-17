"""Synthetic, offline fixtures only; never product/cloud/business evidence."""
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
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "scripts/acceptance"
SHA = "a" * 40


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, BASE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


evidence = load("final_evidence_api", "evidence.py")
gate = load("final_source_gate_api", "source_gate.py")
with patch.dict(sys.modules, {"evidence": evidence}):
    template = load("final_template_api", "template.py")
with patch.dict(sys.modules, {"evidence": evidence, "template": template}):
    recovery = load("final_recovery_api", "recovery_suite.py")
with patch.dict(sys.modules, {"evidence": evidence}), patch.object(sys, "path", [str(BASE), *sys.path]):
    cloud = load("final_cloud_api", "cloud_report.py")
with patch.dict(sys.modules, {"evidence": evidence, "source_gate": gate,
                              "cloud_report": cloud, "recovery_suite": recovery}):
    final = load("final_delivery_api", "final_delivery.py")


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


@unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd,
                     "safe POSIX I/O unavailable")
class FinalDeliveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.repo = self.root / "repo"
        canonical = self.repo / "docs/refactor-input/acceptance.json"
        canonical.parent.mkdir(parents=True)
        canonical.write_bytes((ROOT / "docs/refactor-input/acceptance.json").read_bytes())
        self.spec, self.spec_digest = evidence.load_json(canonical)
        self.expected = {"sha": SHA, "run_id": 42, "attempt": 1}
        self.run = {"id": 42, "run_attempt": 1, "head_sha": SHA,
                    "repository": {"full_name": cloud.REPOSITORY}, "workflow_id": cloud.WORKFLOW_ID,
                    "head_branch": "main", "status": "completed", "conclusion": "success"}
        self.job = {"id": 101, "run_id": 42, "run_attempt": 1, "head_sha": SHA,
                    "name": "单测、检查与开发包", "status": "completed", "conclusion": "success",
                    "started_at": "2026-01-01T00:00:00Z", "completed_at": "2026-01-01T00:01:00Z"}
        app_name = "代鱼-0.1.0-aaaaaaaa.apk"
        record = {"source_sha": SHA, "run_id": "42", "attempt": "1", "application_id": "app.daiyu.android",
                  "certificate_sha256": final.verify_signing.EXPECTED_CERT, "signing": "daiyu-fixed",
                  "variant": "debug", "debuggable": True}
        app = self.put("delivery/" + app_name, b"synthetic application APK, not installable")
        test = self.put("delivery/tests/platform-test.apk", b"synthetic test APK, not installable")
        signature = ""
        for name in (app_name, "tests/platform-test.apk"):
            signature += ("APK: " + name + "\nNumber of signers: 1\n"
                          "Signer #1 certificate SHA-256 digest: " + final.verify_signing.EXPECTED_CERT
                          + "\nVerified using v2 scheme (APK Signature Scheme v2): true\n\n")
        self.delivery = {"run": self.put("build-run.json", self.run),
                         "jobs": self.put("build-jobs.json", {"total_count": 1, "jobs": [self.job]}),
                         "record": self.put("delivery/构建记录.json", record), "app": app, "test": test,
                         "signature": self.put("delivery/签名证据.txt", signature.encode()),
                         "sums": self.put("delivery/SHA256SUMS", (app["sha256"] + "  " + app_name + "\n"
                                     + test["sha256"] + "  tests/platform-test.apk\n").encode())}
        self.members = {"record": "构建记录.json", "app": app_name, "test": "tests/platform-test.apk",
                        "signature": "签名证据.txt", "sums": "SHA256SUMS"}
        self.meta = {"id": 501, "name": "代鱼开发包", "expired": False,
                     "created_at": "2026-01-01T00:00:30Z",
                     "workflow_run": {"id": 42, "head_sha": SHA, "head_branch": "main"}}
        self.repack()
        self.doc = {"schema_version": 1, "mode": "full_build", "build": copy.deepcopy(self.expected),
                    "check": copy.deepcopy(self.expected), "delivery": self.delivery,
                    "cloud": None, "business": None, "tool": None}

    def put(self, name, value):
        raw = value if isinstance(value, bytes) else json.dumps(value).encode()
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        return {"path": name, "sha256": sha256(raw)}

    def repack(self, override=None):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for key, member in self.members.items():
                raw = (self.root / self.delivery[key]["path"]).read_bytes()
                archive.writestr(member, (override or {}).get(member, raw))
        self.delivery["archive"] = self.put("build-artifact.zip", stream.getvalue())
        self.meta["digest"] = "sha256:" + self.delivery["archive"]["sha256"]
        self.delivery["artifact"] = self.put("build-artifact.json", self.meta)

    def save(self):
        return self.put("final-input.json", self.doc)

    def verify(self, **overrides):
        ref = self.save()
        args = dict(manifest_path=self.root / ref["path"], manifest_sha256=ref["sha256"],
                    repo_root=self.repo, evidence_root=self.root,
                    build_sha=SHA, build_run=42, build_attempt=1,
                    check_sha=SHA, check_run=42, check_attempt=1)
        args.update(overrides)
        # Source-gate behavior has its own real Git integration tests. This
        # fixture supplies no actual repository and makes no source-bound claim.
        with patch.object(gate, "verify", return_value={"source_bound": True, "clean": True,
                                                       "code": "source_bound", "head_sha": SHA}):
            return final.verify(**args)

    def reject(self, code=None, **overrides):
        with self.assertRaises(evidence.InvalidEvidence) as caught:
            self.verify(**overrides)
        if code:
            self.assertEqual(str(caught.exception), code)

    def add_cloud(self, apis=("26", "37.2")):
        snapshot = {"schema_version": 1, "run": copy.deepcopy(self.run), "jobs": [], "reports": []}
        for job_id, api in enumerate(apis, 201):
            log = self.put(api + "/platform-tests.txt", b"OK (1 test)\nINSTRUMENTATION_CODE: -1\n")
            logcat = self.put(api + "/logcat.txt", b"I/Runner: finished\n")
            report = self.put(api + "/report.json", {"schema_version": 1, "head_sha": SHA,
                "run_id": 42, "run_attempt": 1, "job_id": job_id, "api": api, "exit_code": 0,
                "expected_tests": 1, "instrumentation": log, "logcat": logcat})
            snapshot["jobs"].append({"id": job_id, "head_sha": SHA, "run_id": 42, "run_attempt": 1,
                "api": api, "name": "启动验证 API " + api, "status": "completed", "conclusion": "success",
                "report_sha256": report["sha256"]})
            snapshot["reports"].append({"api": api, "job_id": job_id, **report})
        self.doc["cloud"] = self.put("snapshot.json", snapshot)

    def add_business(self, passed=False):
        ledger = template.make_template(self.spec, self.spec_digest, SHA, "ledger")
        if passed:
            # Deliberately self-described synthetic data: even a structurally
            # complete ledger cannot grant final acceptance/authentication.
            for row in ledger["records"]:
                row.update(status="passed", command=["never-execute-this"], exit_code=0,
                           origin="human_review" if row["kind"] == "static_review" else "execution",
                           actual="synthetic fixture, not real execution")
                row["artifacts"] = [{"role": role, **self.put(row["item_id"] + "/" + row["kind"] + "/" + role,
                                                            b"synthetic self-described evidence")}
                                    for role in evidence.ROLES[row["kind"]]]
                if row["kind"] in ("real_device", "performance"):
                    row["environment"] = {"physical_device": True, "device_model": "fixture", "os_version": "fixture"}
                if row["kind"] == "performance":
                    row["measurement"] = dict(metric="fixture", unit="ms", threshold="fixture", observed="fixture")
        self.doc["business"] = {"ledger": self.put("ledger.json", ledger),
            "policy": self.put("policy.json", template.make_template(self.spec, self.spec_digest, SHA, "policy"))}

    def add_tool(self):
        self.doc["tool"] = {"receipt": self.put("tool-receipt.json", {"identity": self.expected,
            "command": ["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "exit_code": 0}),
            "log": self.put("tool.log", b"testOne (fixture.Suite.testOne) ... ok\n\nRan 1 test in 0.1s\n\nOK\n")}

    def test_missing_81_item_evidence_cannot_pass_even_with_full_cloud(self):
        self.add_cloud()
        self.add_tool()
        result = self.verify()
        self.assertFalse(result["final_acceptance_passed"])
        self.assertFalse(result["internal_consistency_satisfied"])
        self.assertEqual(result["status"], "incomplete")
        self.assertEqual(result["business"]["counts"]["not_run"], 81)
        self.assertTrue(result["cloud"]["cloud_regression_satisfied"])
        self.assertEqual(len(result["cloud_log_sha256"]), 2)

    def test_plan_or_self_report_is_not_a_ledger_and_not_run_template_is_not_pass(self):
        self.add_business()
        result = self.verify()
        self.assertEqual(result["business"]["counts"]["not_run"], 81)
        for fake in ({"status": "passed", "covered_ac_count": 81},
                     {"build_sha": SHA, "tests_executed": False, "acceptance_claimed": False},
                     {"schema_version": 1, "passed": 81, "records": []}):
            self.doc["business"]["ledger"] = self.put("ledger.json", fake)
            self.reject()

    def test_even_structurally_complete_self_described_data_never_grants_final_acceptance(self):
        self.add_cloud()
        self.add_tool()
        self.add_business(passed=True)
        result = self.verify()
        self.assertEqual(result["status"], "ready_for_independent_review")
        self.assertTrue(result["internal_consistency_satisfied"])
        self.assertEqual(result["business"]["counts"]["passed"], 81)
        self.assertFalse(result["business_satisfied"])
        self.assertFalse(result["business_evidence_authenticated"])
        self.assertFalse(result["final_acceptance_passed"])
        self.assertFalse(result["trusted_collection"])
        self.add_cloud(("26",))
        self.assertFalse(self.verify()["internal_consistency_satisfied"])

    def test_fixed_identity_pins_attempt_and_terminal_success(self):
        for field, value in (("sha", "b" * 40), ("run_id", 99), ("attempt", 2), ("attempt", True)):
            original = self.doc["check"][field]
            self.doc["check"][field] = value
            self.reject()
            self.doc["check"][field] = original
        self.reject("manifest_pin_mismatch", manifest_sha256="0" * 64)
        self.meta["created_at"] = "2026-01-01T00:01:01Z"
        self.delivery["artifact"] = self.put("build-artifact.json", self.meta)
        self.reject("artifact_attempt_mismatch")
        self.meta["created_at"] = "2026-01-01T00:00:30Z"
        self.delivery["artifact"] = self.put("build-artifact.json", self.meta)
        self.job["conclusion"] = "failure"
        self.delivery["jobs"] = self.put("build-jobs.json", {"total_count": 1, "jobs": [self.job]})
        self.reject("job_identity_mismatch")

    def test_apk_must_match_source_archive_sums_and_record(self):
        app_member = self.members["app"]
        self.repack({app_member: b"another APK"})
        self.reject("archive_member_mismatch")
        self.repack()
        self.delivery["sums"] = self.put("delivery/SHA256SUMS", b"arbitrary summary\n")
        self.repack()
        self.reject("apk_sums_mismatch")
        record = json.loads((self.root / self.delivery["record"]["path"]).read_text())
        record["source_sha"] = "b" * 40
        self.delivery["record"] = self.put("delivery/构建记录.json", record)
        self.repack()
        self.reject("delivery_record_mismatch")

    def test_old_recheck_keeps_both_identities_and_never_relabels_build(self):
        self.doc["mode"] = "old_apk_recheck"
        self.doc["check"] = {"sha": "b" * 40, "run_id": 43, "attempt": 2}
        with patch.object(final, "reuse") as checked:
            result = self.verify(check_sha="b" * 40, check_run=43, check_attempt=2)
        checked.assert_called_once()
        self.assertEqual(result["build"], self.expected)
        self.assertEqual(result["check"], self.doc["check"])
        self.assertEqual(result["apk_provenance"]["app_sha256"], self.delivery["app"]["sha256"])
        self.doc["mode"] = "full_build"
        self.reject("full_build_identity_mismatch", check_sha="b" * 40, check_run=43, check_attempt=2)

    def test_source_tool_and_cloud_failures_cannot_disappear(self):
        self.add_tool()
        receipt = json.loads((self.root / "tool-receipt.json").read_text())
        receipt["exit_code"] = 7
        self.doc["tool"]["receipt"] = self.put("tool-receipt.json", receipt)
        self.reject("invalid_tool_execution")
        self.add_tool()
        self.doc["tool"]["log"] = self.put("tool.log", b"OK\n")
        self.reject()
        self.add_cloud()
        report_ref = json.loads((self.root / "snapshot.json").read_text())["reports"][0]
        report = json.loads((self.root / report_ref["path"]).read_text())
        (self.root / report["instrumentation"]["path"]).write_bytes(b"OK (1 test)\n")
        self.reject("artifact_digest_mismatch")

    def cli_args(self):
        ref = self.save()
        return ["--manifest", str(self.root / ref["path"]), "--manifest-sha256", ref["sha256"],
                "--repo-root", str(self.repo), "--evidence-root", str(self.root),
                "--build-sha", SHA, "--build-run", "42", "--build-attempt", "1",
                "--check-sha", SHA, "--check-run", "42", "--check-attempt", "1"]

    def assert_cli(self, expected_exit):
        result = subprocess.run([sys.executable, "-B", str(BASE / "final_delivery.py"), *self.cli_args()],
                                cwd=self.root, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, expected_exit, result.stderr)
        self.assertEqual(result.stderr, "")
        self.assertEqual(len(result.stdout.splitlines()), 1)
        report = json.loads(result.stdout)
        self.assertNotIn(str(self.root), result.stdout)
        self.assertNotIn("private-path-marker", result.stdout)
        self.assertNotIn("Traceback", result.stdout)
        self.assertFalse(report["final_acceptance_passed"])
        self.assertFalse(report["trusted_collection"])
        return report

    def test_nested_actions_objects_reject_non_dicts_without_cli_traceback(self):
        for field, code in (("repository", "actions_run_mismatch"),
                            ("workflow_run", "artifact_revision_mismatch")):
            for value in (None, [], ["private-path-marker"], "private-path-marker", 7, 1.5, True):
                with self.subTest(field=field, value=value):
                    run, meta = copy.deepcopy(self.run), copy.deepcopy(self.meta)
                    target = run if field == "repository" else meta
                    target[field] = value
                    self.delivery["run"] = self.put("build-run.json", run)
                    self.delivery["artifact"] = self.put("build-artifact.json", meta)
                    self.reject(code)
                    report = self.assert_cli(2)
                    self.assertFalse(report["valid"])
                    self.assertEqual(report["error"], code)

    def test_business_failure_cli_exit2_preserves_report_even_with_missing_layers(self):
        self.add_business(passed=True)
        ledger = json.loads((self.root / "ledger.json").read_text())
        failed = ledger["records"][0]
        failed.update(status="failed", exit_code=1, failure="synthetic known failure")
        # Missing required records/tool/cloud/source binding cannot hide failure.
        ledger["records"] = [failed]
        self.doc["business"]["ledger"] = self.put("ledger.json", ledger)
        self.assertEqual(self.verify()["status"], "failed")
        report = self.assert_cli(2)
        self.assertTrue(report["valid"])
        self.assertEqual(report["status"], "failed")
        self.assertEqual(report["business"]["counts"]["failed"], 1)
        self.assertGreater(report["business"]["counts"]["not_run"], 0)
        self.assertFalse(report["internal_consistency_satisfied"])
        self.assertFalse(report["business_satisfied"])
        self.assertFalse(report["business_evidence_authenticated"])

    def test_missing_not_run_and_blocked_business_cli_remain_incomplete_exit1(self):
        self.assertEqual(self.assert_cli(1)["status"], "incomplete")
        self.add_business()
        report = self.assert_cli(1)
        self.assertEqual(report["business"]["counts"]["not_run"], 81)
        ledger = json.loads((self.root / "ledger.json").read_text())
        ledger["records"][0].update(status="blocked", blocker="synthetic missing device")
        self.doc["business"]["ledger"] = self.put("ledger.json", ledger)
        report = self.assert_cli(1)
        self.assertEqual(report["status"], "incomplete")
        self.assertEqual(report["business"]["counts"]["blocked"], 1)
        self.assertEqual(report["business"]["counts"]["failed"], 0)

    def test_real_file_cli_is_offline_fail_closed_and_redacted(self):
        for args, expected_exit in ((self.cli_args(), 1), ([], 2), (["--secret-private-value"], 2)):
            result = subprocess.run([sys.executable, "-B", str(BASE / "final_delivery.py"), *args],
                                    cwd=self.root, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, expected_exit, result.stderr)
            self.assertEqual(result.stderr, "")
            report = json.loads(result.stdout)
            self.assertFalse(report["final_acceptance_passed"])
            self.assertFalse(report["trusted_collection"])
            self.assertNotIn("secret-private-value", result.stdout)
            self.assertNotIn(str(self.root), result.stdout)
