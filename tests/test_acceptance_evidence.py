"""Isolated stdlib tests: no Android build, network, secrets, or acceptance claims."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "acceptance" / "evidence.py"
MODULE_SPEC = importlib.util.spec_from_file_location("acceptance_evidence", MODULE_PATH)
evidence = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(evidence)
TEMPLATE_SPEC = importlib.util.spec_from_file_location(
    "acceptance_template", MODULE_PATH.with_name("template.py"))
template = importlib.util.module_from_spec(TEMPLATE_SPEC)
with patch.dict(sys.modules, {"evidence": evidence}):
    TEMPLATE_SPEC.loader.exec_module(template)
SHA = "a" * 40
OTHER_SHA = "b" * 40


@unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd,
                     "safe POSIX file operations unavailable")
class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.spec = {"items": [{"id": "A001", "title": "Not copied into reports"}]}
        self.digest = hashlib.sha256(json.dumps(self.spec).encode()).hexdigest()
        self.policy = {"schema_version": 1, "spec_sha256": self.digest,
                       "requirements": {"A001": ["platform_automation"]}}
        self.ledger = {"schema_version": 1, "build_sha": SHA,
                       "spec_sha256": self.digest, "records": []}

    def artifact(self, role="test_report", name="result.json"):
        raw = b'{"tests":1,"failures":0}'  # Synthetic fixture, not project evidence.
        (self.root / name).write_bytes(raw)
        return {"path": name, "sha256": hashlib.sha256(raw).hexdigest(), "role": role}

    def run_record(self, kind="platform_automation", status="passed"):
        record = {"item_id": "A001", "build_sha": SHA, "kind": kind,
                  "status": status, "origin": "execution", "command": ["runner", "--tests"],
                  "exit_code": 0, "actual": "synthetic fixture observation",
                  "artifacts": [self.artifact()]}
        if kind == "static_review":
            record["origin"] = "human_review"
            record["artifacts"][0]["role"] = "review_report"
        if kind in ("real_device", "performance"):
            record["environment"] = {"physical_device": True,
                                     "device_model": "fixture", "os_version": "fixture"}
        if kind == "real_device":
            record["artifacts"].append(self.artifact("device_capture", "capture.json"))
        if kind == "performance":
            record["artifacts"][0]["role"] = "performance_measurement"
            record["measurement"] = {"metric": "frame_time", "unit": "ms",
                                     "threshold": "<= 16", "observed": "12"}
        return record

    def verify(self):
        return evidence.verify(self.spec, self.digest, self.ledger, self.policy,
                               SHA, self.root, expected_count=1)

    def reject(self, code):
        with self.assertRaisesRegex(evidence.InvalidEvidence, "^" + code + "$"):
            self.verify()

    def test_missing_is_not_run(self):
        report = self.verify()
        self.assertFalse(report["satisfied"])
        self.assertEqual(report["counts"]["not_run"], 1)
        self.assertEqual(report["unmet"][0]["reasons"], ["missing_record"])

    def test_valid_hashed_fixture(self):
        self.ledger["records"] = [self.run_record()]
        self.assertTrue(self.verify()["satisfied"])

    def test_all_four_categories_and_no_static_substitution(self):
        self.policy["requirements"]["A001"] = list(evidence.KINDS)
        self.ledger["records"] = [self.run_record("static_review")]
        report = self.verify()
        self.assertEqual(len(report["unmet"]), 3)
        self.ledger["records"] = [self.run_record(k) for k in evidence.KINDS]
        self.assertTrue(self.verify()["satisfied"])

    def test_blocked_and_failed_are_unsatisfied(self):
        self.ledger["records"] = [{"item_id": "A001", "build_sha": SHA,
                                   "kind": "platform_automation", "status": "blocked",
                                   "blocker": "no device"}]
        self.assertEqual(self.verify()["counts"]["blocked"], 1)
        record = self.run_record(status="failed")
        record.update(exit_code=1, failure="test assertion failed")
        self.ledger["records"] = [record]
        self.assertEqual(self.verify()["counts"]["failed"], 1)

    def test_failed_assertion_may_have_zero_process_exit(self):
        record = self.run_record(status="failed")
        record["failure"] = "criterion assertion failed despite successful runner"
        self.ledger["records"] = [record]
        self.assertFalse(self.verify()["satisfied"])

    def test_cross_item_summary_default_81(self):
        spec = [{"id": "A%03d" % i} for i in range(1, 82)]
        policy = copy.deepcopy(self.policy)
        policy["requirements"] = {item["id"]: ["real_device"] for item in spec}
        report = evidence.verify(spec, self.digest, self.ledger, policy, SHA, self.root)
        self.assertEqual(report["counts"]["not_run"], 81)
        self.assertEqual(len(report["unmet"]), 81)
        self.assertEqual(report["items"][-1]["item_index"], 81)

    def test_exact_cases_schema_and_policy_floor(self):
        self.spec = {"schema_version": 1, "cases": [{"id": "A001",
                     "method": "合成协议+Android真机"}]}
        self.policy["requirements"]["A001"] = ["platform_automation", "real_device"]
        self.assertFalse(self.verify()["satisfied"])
        self.policy["requirements"]["A001"] = ["static_review"]
        self.reject("policy_weakens_spec")
        self.spec["cases"][0]["method"] = "new unknown method"
        self.reject("unclassified_spec_method")

    def test_template_never_copies_history(self):
        spec = {"schema_version": 1, "cases": [{"id": "AC-001",
                "method": "自动化故障注入/合成数据", "status": "passed",
                "build_sha": OTHER_SHA, "actual": "old claim"}]}
        ledger = template.make_template(spec, self.digest, SHA, "ledger", 1)
        policy = template.make_template(spec, self.digest, SHA, "policy", 1)
        self.assertEqual(ledger["records"][0]["status"], "not_run")
        self.assertEqual(ledger["records"][0]["build_sha"], SHA)
        self.assertEqual(ledger["records"][0]["artifacts"], [])
        report = evidence.verify(spec, self.digest, ledger, policy, SHA, self.root, 1)
        self.assertFalse(report["satisfied"])

    def test_supplied_81_case_spec_when_available(self):
        path = MODULE_PATH.parents[2] / "docs" / "refactor-input" / "acceptance.json"
        if not path.exists():
            self.skipTest("parent-supplied spec not present; fixture schema tested separately")
        spec, digest = evidence.load_json(path)
        ids = evidence.spec_items(spec)
        self.assertEqual(ids, ["AC-%03d" % i for i in range(1, 82)])
        ledger = template.make_template(spec, digest, SHA, "ledger")
        policy = template.make_template(spec, digest, SHA, "policy")
        report = evidence.verify(spec, digest, ledger, policy, SHA, self.root)
        self.assertEqual(report["counts"]["not_run"], 81)
        self.assertEqual(report["counts"]["passed"], 0)
        self.assertGreaterEqual(len(report["unmet"]), 81)
        self.assertIn("performance", policy["requirements"]["AC-072"])
        self.assertIn("real_device", policy["requirements"]["AC-074"])
        self.assertIn("static_review", policy["requirements"]["AC-075"])

    def test_full_sha_only(self):
        for invalid in (SHA[:12], SHA.upper(), "a" * 41, " a" * 20, None):
            with self.subTest(value=invalid):
                with self.assertRaises(evidence.InvalidEvidence):
                    evidence.full_sha(invalid)
        self.assertEqual(evidence.full_sha("a" * 64), "a" * 64)

    def test_no_implicit_rebinding(self):
        record = self.run_record()
        record["build_sha"] = OTHER_SHA
        self.ledger["records"] = [record]
        self.reject("record_build_mismatch")
        self.ledger["build_sha"] = OTHER_SHA
        self.reject("ledger_build_mismatch")

    def test_digest_and_spec_binding(self):
        self.ledger["records"] = [self.run_record()]
        (self.root / "result.json").write_bytes(b"tampered")
        self.reject("artifact_digest_mismatch")
        self.ledger["spec_sha256"] = "f" * 64
        self.reject("ledger_spec_mismatch")
        self.ledger["spec_sha256"] = self.digest
        self.policy["spec_sha256"] = "f" * 64
        self.reject("policy_spec_mismatch")

    def test_nonzero_pass_and_boolean_exit_rejected(self):
        record = self.run_record()
        self.ledger["records"] = [record]
        record["exit_code"] = 3
        self.reject("pass_nonzero_exit")
        record["exit_code"] = True
        self.reject("invalid_exit_code")

    def test_dispatch_and_model_claim_rejected(self):
        for origin in ("dispatch", "model_self_report", "model_assertion"):
            record = self.run_record()
            record["origin"] = origin
            self.ledger["records"] = [record]
            self.reject("unsupported_origin")
        for role in ("dispatch", "model_assertion"):
            record = self.run_record()
            record["artifacts"][0]["role"] = role
            self.ledger["records"] = [record]
            self.reject("unsupported_artifact_role")
        for command in (["dispatch"], ["runner", "dispatch"], ["echo", "passed"]):
            record = self.run_record()
            record["command"] = command
            self.ledger["records"] = [record]
            self.reject("non_acceptance_command")

    def test_execution_metadata_is_required(self):
        mutations = [
            ("actual", None, "actual_observation_required"),
            ("command", "runner --tests", "invalid_command"),
            ("command", [], "invalid_command"),
            ("exit_code", "0", "invalid_exit_code"),
            ("artifacts", [], "artifacts_required"),
        ]
        for key, value, code in mutations:
            with self.subTest(key=key, value=value):
                record = self.run_record()
                record[key] = value
                self.ledger["records"] = [record]
                self.reject(code)
        record = self.run_record(status="failed")
        self.ledger["records"] = [record]
        self.reject("failure_required")
        self.ledger["records"] = [{"item_id": "A001", "kind": "platform_automation",
                                   "status": "blocked", "build_sha": SHA}]
        self.reject("blocker_required")

    def test_empty_artifact_and_missing_file(self):
        record = self.run_record()
        self.ledger["records"] = [record]
        (self.root / "result.json").write_bytes(b"")
        record["artifacts"][0]["sha256"] = hashlib.sha256(b"").hexdigest()
        self.reject("empty_artifact")
        (self.root / "result.json").unlink()
        with self.assertRaises(OSError):
            self.verify()

    def test_device_requires_physical_capture_and_test(self):
        record = self.run_record("real_device")
        self.ledger["records"] = [record]
        record["environment"]["physical_device"] = False
        self.reject("physical_device_required")
        record["environment"]["physical_device"] = True
        record["artifacts"].pop()
        self.reject("device_evidence_incomplete")

    def test_performance_requires_measurement(self):
        record = self.run_record("performance")
        self.ledger["records"] = [record]
        del record["measurement"]
        self.reject("measurement_required")

    def test_unrun_must_not_claim_commands(self):
        self.ledger["records"] = [{"item_id": "A001", "build_sha": SHA,
                                   "kind": "platform_automation", "status": "not_run",
                                   "command": ["runner"], "exit_code": 0}]
        self.reject("unrun_record_has_execution")

    def test_duplicates_unknown_items_and_incomplete_policy(self):
        record = self.run_record()
        self.ledger["records"] = [record, copy.deepcopy(record)]
        self.reject("duplicate_record")
        record["item_id"] = "unknown"
        self.ledger["records"] = [record]
        self.reject("unknown_item")
        self.policy["requirements"] = {}
        self.reject("policy_coverage_mismatch")

    def test_supplementary_failure_is_visible(self):
        failed = self.run_record("static_review", "failed")
        failed["failure"] = "review rejected"
        self.ledger["records"] = [self.run_record(), failed]
        self.assertEqual(self.verify()["counts"]["failed"], 1)

    def test_paths_are_relative_without_traversal(self):
        for path in ("../secret", "/secret", "C:/secret", "a\\b", "a/../b", "./b", "a//b"):
            record = self.run_record()
            record["artifacts"][0]["path"] = path
            self.ledger["records"] = [record]
            self.reject("invalid_artifact_path")

    def test_symlink_file_and_directory_rejected(self):
        target = self.root / "target"
        target.write_bytes(b"data")
        link = self.root / "link"
        link.symlink_to(target)
        with self.assertRaises(OSError):
            evidence.bounded_read(link, 100)
        directory = self.root / "directory"
        directory.mkdir()
        (directory / "file").write_bytes(b"data")
        (self.root / "dirlink").symlink_to(directory, target_is_directory=True)
        with self.assertRaises(OSError):
            evidence.bounded_read(self.root / "dirlink" / "file", 100)
        with self.assertRaises(evidence.InvalidEvidence):
            evidence.bounded_read(directory, 100)

    def test_bounded_reads_and_total_budget(self):
        path = self.root / "large"
        path.write_bytes(b"12345")
        with self.assertRaisesRegex(evidence.InvalidEvidence, "file_too_large"):
            evidence.bounded_read(path, 4)
        self.ledger["records"] = [self.run_record()]
        with patch.object(evidence, "MAX_TOTAL_BYTES", 1):
            self.reject("file_too_large")

    def test_duplicate_keys_invalid_json_and_count(self):
        path = self.root / "bad.json"
        for content in ('{"x":1,"x":2}', '{"x":NaN}', '{', '\ud800'):
            path.write_bytes(content.encode("utf-8", errors="surrogatepass"))
            with self.assertRaises(evidence.InvalidEvidence):
                evidence.load_json(path)
        with self.assertRaisesRegex(evidence.InvalidEvidence, "spec_count_mismatch"):
            evidence.spec_items(self.spec)

    def cli(self, ledger=None):
        for name, data in (("spec", self.spec), ("policy", self.policy),
                           ("ledger", self.ledger if ledger is None else ledger)):
            (self.root / (name + ".json")).write_text(json.dumps(data), encoding="utf-8")
        # Digest binds exact input bytes, not a normalized representation.
        self.digest = hashlib.sha256((self.root / "spec.json").read_bytes()).hexdigest()
        return ["--spec", str(self.root / "spec.json"), "--policy", str(self.root / "policy.json"),
                "--ledger", str(self.root / "ledger.json"), "--build-sha", SHA,
                "--evidence-root", str(self.root), "--expected-count", "1"]

    def test_cli_exit_codes_and_secret_safe_output(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = evidence.main(self.cli())
        self.assertEqual(code, 1)
        self.assertTrue(json.loads(out.getvalue())["valid"])
        self.ledger["records"] = [self.run_record()]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = evidence.main(self.cli())
        self.assertEqual(code, 0)
        marker = "PRIVATE_TOKEN_DO_NOT_PRINT"
        self.ledger["records"][0]["command"].append(marker)
        self.spec["items"][0]["title"] = marker
        # Rebind only this synthetic test fixture after changing its spec bytes.
        self.digest = hashlib.sha256(json.dumps(self.spec).encode()).hexdigest()
        self.policy["spec_sha256"] = self.ledger["spec_sha256"] = self.digest
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(evidence.main(self.cli()), 0)
        self.assertNotIn(marker, out.getvalue())
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(evidence.main(["--unknown", marker]), 2)
        self.assertNotIn(marker, out.getvalue())
        args = self.cli()
        args[1] = str(self.root / marker)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(evidence.main(args), 2)
        self.assertNotIn(marker, out.getvalue())


if __name__ == "__main__":
    unittest.main()
