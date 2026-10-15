"""Offline routing tests; synthetic not-run plans are not application evidence."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
MODULE_DIR = ROOT / "scripts" / "acceptance"


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, MODULE_DIR / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


evidence = module("coverage_test_evidence", "evidence.py")
with patch.dict(sys.modules, {"evidence": evidence}):
    coverage = module("acceptance_coverage", "coverage.py")
    template = module("coverage_test_template", "template.py")

SHA = "a" * 40
DIGEST = "b" * 64
SECRET = "SENSITIVE_FREE_TEXT_MUST_NOT_ESCAPE"
# Independent expected assignment, including owner/feature boundary cases.
OWNER_CASES = {
    1: [17, 70, 74], 2: [8, 9, 10, 27, 28, 29, 30], 3: [14, 15, 16],
    4: [5, 6, 7], 5: [33, 34, 35, 60, 61],
    6: [12, 13, 23, 24, 25, 26, 31, 32, 62],
    7: [11, 36, 37, 77, 81], 8: [38, 39, 40, 41, 42, 43, 78, 79],
    9: [46, 47, 80], 10: [44, 45], 11: [58, 59],
    12: [53, 54, 55, 56, 57], 13: [48, 49],
    14: [63, 64, 65, 66, 67, 68, 69], 15: [4, 76],
    16: [1, 2, 3, 18, 19, 20, 21, 22], 17: [71],
    18: [50, 51, 52], 19: [75], 20: [72, 73],
}


def fixture():
    return {"schema_version": 1, "cases": [
        {"id": item_id, "feature_id": coverage.ROUTES[item_id][0],
         "method": "自动化故障注入/合成数据", "title": SECRET,
         "given": SECRET, "when": SECRET, "then": SECRET,
         "status": "passed", "build_sha": "c" * 40, "actual": SECRET,
         "command": [SECRET], "evidence_artifact": SECRET}
        for item_id in coverage.CANONICAL_IDS]}


class CoverageTests(unittest.TestCase):
    def setUp(self):
        self.spec = fixture()

    def plan(self, **kwargs):
        args = dict(spec=self.spec, digest=DIGEST, build_sha=SHA,
                    expected_spec_sha256=DIGEST)
        args.update(kwargs)
        return coverage.make_coverage(**args)

    def reject(self, code, **kwargs):
        with self.assertRaisesRegex(evidence.InvalidEvidence, "^" + code + "$"):
            self.plan(**kwargs)

    def test_exact_81_unique_all_not_run_no_claim_import(self):
        report = self.plan()
        self.assertEqual(len(report["items"]), 81)
        self.assertEqual([row["item_id"] for row in report["items"]],
                         list(coverage.CANONICAL_IDS))
        self.assertEqual(len(set(row["item_id"] for row in report["items"])), 81)
        self.assertEqual(report["counts"],
                         {"not_run": 81, "passed": 0, "failed": 0, "blocked": 0})
        self.assertFalse(report["tests_executed"])
        self.assertEqual(report["build_sha"], SHA)
        self.assertEqual(report["spec_sha256"], DIGEST)
        self.assertTrue(all(row["status"] == "not_run" for row in report["items"]))
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertNotIn("c" * 40, json.dumps(report))
        self.assertTrue(all(row["status"] == "not_run" for row in report["unmet"]))

    def test_all_owner_boundaries_independently_enumerated(self):
        report = self.plan()
        actual = {row["item_id"]: row for row in report["items"]}
        expected_ids = []
        for owner, numbers in OWNER_CASES.items():
            for number in numbers:
                item_id = "AC-%03d" % number
                expected_ids.append(item_id)
                row = actual[item_id]
                self.assertEqual(row["owner"], owner, item_id)
                self.assertEqual(row["integration_owner"], 1)
                self.assertEqual(row["acceptance_owner"], 20)
                self.assertEqual(row["production_owner"], owner if 2 <= owner <= 18 else None)
                self.assertEqual(row["owner_role"], "integration" if owner == 1 else
                                 "provenance" if owner == 19 else
                                 "acceptance" if owner == 20 else "production")
        self.assertEqual(len(expected_ids), len(set(expected_ids)))
        self.assertEqual(set(expected_ids), set(coverage.CANONICAL_IDS))
        for row in report["owners"]:
            owner = row["owner"]
            self.assertEqual(row["role"], "integration" if owner == 1 else
                             "provenance" if owner == 19 else
                             "acceptance" if owner == 20 else "production")
            self.assertEqual(row["count"], len(OWNER_CASES[row["owner"]]))
            self.assertEqual(set(row["item_ids"]),
                             {"AC-%03d" % n for n in OWNER_CASES[row["owner"]]})

    def test_mcp_owner_confirmation_does_not_grant_business_acceptance(self):
        report = self.plan()
        row = next(row for row in report["items"] if row["item_id"] == "AC-062")
        self.assertEqual(row["owner"], 6)
        self.assertEqual(row["production_owner"], 6)
        self.assertEqual(row["integration_owner"], 1)
        self.assertEqual(row["acceptance_owner"], 20)
        self.assertEqual(row["mapping_status"], "configured")
        self.assertEqual(row["status"], "not_run")
        self.assertEqual(report["counts"]["not_run"], 81)
        self.assertEqual(report["counts"]["passed"], 0)
        self.assertFalse(report["tests_executed"])
        self.assertFalse(any("owner_confirmation_required" in gap["reasons"]
                             for gap in report["unmet"]))
        gaps = [gap for gap in report["unmet"] if gap["item_id"] == "AC-062"]
        self.assertTrue(gaps)
        self.assertTrue(all(gap["reasons"] == ["missing_record"] for gap in gaps))

    def test_shared_floor_and_template_interface_no_second_classifier(self):
        methods = list(evidence.METHOD_KINDS)
        for index, case in enumerate(self.spec["cases"]):
            case["method"] = methods[index % len(methods)]
        floors = evidence.spec_floors(self.spec)
        policy = template.make_template(self.spec, DIGEST, SHA, "policy")
        plan = self.plan()
        for row in plan["items"]:
            self.assertEqual(row["required_kinds"], list(floors[row["item_id"]]))
            self.assertEqual(row["required_kinds"], policy["requirements"][row["item_id"]])
            gaps = [gap for gap in plan["unmet"] if gap["item_id"] == row["item_id"]
                    and gap["kind"] is not None]
            self.assertEqual({gap["kind"] for gap in gaps}, set(row["required_kinds"]))
            for gap in gaps:
                self.assertEqual(gap["reasons"], ["missing_record"])
                self.assertEqual(gap["required_artifact_roles"],
                                 sorted(evidence.ROLES[gap["kind"]]))

    def test_reordered_spec_keeps_canonical_routing(self):
        before = self.plan()
        self.spec["cases"].reverse()
        self.assertEqual(self.plan(), before)

    def test_full_sha_only_and_strict_expected_digest(self):
        for bad in ("HEAD", "a" * 8, "A" * 40, "g" * 40, "a" * 41, None, 1):
            with self.subTest(build_sha=bad):
                self.reject("invalid_build_sha", build_sha=bad)
        self.assertEqual(self.plan(build_sha="d" * 64)["build_sha"], "d" * 64)
        for bad in ("B" * 64, "b" * 40, "z" * 64, None):
            self.reject("invalid_expected_spec_digest", expected_spec_sha256=bad)
        self.reject("invalid_spec_digest", digest="bad")
        self.reject("spec_digest_mismatch", expected_spec_sha256="c" * 64)

    def test_invalid_shape_version_count_and_ambiguous_arrays(self):
        for spec in ([], {"items": self.spec["cases"]}, None, 123):
            self.reject("unsupported_coverage_spec", spec=spec)
        for version in (True, "1", 1.0, 2, None):
            self.spec["schema_version"] = version
            self.reject("unsupported_spec_version")
        self.spec = fixture()
        for cases in (self.spec["cases"][:-1], self.spec["cases"] + [self.spec["cases"][0]], {}):
            spec = {"schema_version": 1, "cases": cases}
            self.reject("spec_count_mismatch", spec=spec)
        self.spec["items"] = []
        self.reject("unsupported_spec_shape")

    def test_unknown_duplicate_missing_and_noncanonical_ids(self):
        for bad in ("AC-000", "AC-082", "AC-01", "ac-001", "UNKNOWN"):
            self.spec = fixture()
            self.spec["cases"][0]["id"] = bad
            self.reject("noncanonical_case_ids")
        self.spec = fixture()
        self.spec["cases"][0]["id"] = "AC-002"
        self.reject("duplicate_item_id")
        self.spec = fixture()
        del self.spec["cases"][0]["id"]
        self.reject("invalid_item_id")
        self.spec = fixture()
        self.spec["cases"][0] = None
        self.reject("invalid_spec_item")

    def test_changed_feature_or_unknown_method_fails_closed(self):
        self.spec["cases"][0]["feature_id"] = "F16"
        self.reject("case_feature_mismatch")
        self.spec = fixture()
        del self.spec["cases"][0]["feature_id"]
        self.reject("case_feature_mismatch")
        self.spec = fixture()
        self.spec["cases"][0]["method"] = SECRET
        self.reject("unclassified_spec_method")

    def test_configuration_cannot_omit_or_add_ac(self):
        routes = dict(coverage.ROUTES)
        del routes["AC-081"]
        with patch.object(coverage, "ROUTES", routes):
            self.reject("invalid_routing_configuration")
        routes = dict(coverage.ROUTES, **{"AC-082": ("F12", 7)})
        with patch.object(coverage, "ROUTES", routes):
            self.reject("invalid_routing_configuration")


@unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd,
                     "safe POSIX file operations unavailable")
class CoverageCliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "spec.json"
        self.raw = json.dumps(fixture(), ensure_ascii=False).encode("utf-8")
        self.path.write_bytes(self.raw)
        self.digest = hashlib.sha256(self.raw).hexdigest()

    def invoke(self, extra=None, path=None, digest=None, sha=SHA):
        argv = ["--spec", str(path or self.path), "--build-sha", sha,
                "--expected-spec-sha256", digest or self.digest]
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = coverage.main(argv + (extra or []))
        text = output.getvalue()
        self.assertNotIn(SECRET, text)
        return code, json.loads(text)

    def test_cli_success_is_generation_only_and_does_not_modify_input(self):
        code, report = self.invoke()
        self.assertEqual(code, 0)
        self.assertTrue(report["valid"])
        self.assertEqual(report["counts"]["not_run"], 81)
        self.assertEqual(report["counts"]["passed"], 0)
        self.assertEqual(self.path.read_bytes(), self.raw)
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_changed_bytes_even_whitespace_rejected_without_partial_report(self):
        self.path.write_bytes(self.raw + b"\n")
        code, report = self.invoke()
        self.assertEqual(code, 2)
        self.assertEqual(report, {"valid": False, "error": "spec_digest_mismatch"})

    def test_unsafe_json_and_symlinks_rejected(self):
        for raw in (b'{"cases":[],"cases":[]}', b'{"a":NaN}', b'\xff', b'[] trailing'):
            with self.subTest(raw=raw):
                self.path.write_bytes(raw)
                code, report = self.invoke(digest=hashlib.sha256(raw).hexdigest())
                self.assertEqual(code, 2)
                self.assertFalse(report["valid"])
        link = self.path.parent / "link.json"
        link.symlink_to(self.path)
        self.assertEqual(self.invoke(path=link)[0], 2)
        self.assertEqual(self.invoke(path=self.path.parent)[0], 2)
        self.path.write_bytes(b" " * (evidence.MAX_JSON_BYTES + 1))
        self.assertEqual(self.invoke()[0], 2)

    def test_cli_no_free_text_on_errors_or_acceptance_import_switches(self):
        for extra in (["--ledger", SECRET], ["--owners", SECRET],
                      ["--expected-count", "1"], ["--unknown", SECRET]):
            code, report = self.invoke(extra=extra)
            self.assertEqual(code, 2)
            self.assertEqual(report["error"], "invalid_cli_arguments")
        self.assertEqual(self.invoke(path=self.path.parent / SECRET)[0], 2)
        self.assertEqual(self.invoke(sha=SECRET)[1]["error"], "invalid_build_sha")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(coverage.main([]), 2)
        self.assertNotIn(SECRET, output.getvalue())

    def test_never_executes_commands_or_network(self):
        with contextlib.ExitStack() as stack:
            mocks = [stack.enter_context(patch(target, side_effect=AssertionError(target)))
                     for target in ("urllib.request.urlopen", "subprocess.Popen",
                                    "subprocess.run", "subprocess.call", "subprocess.check_call",
                                    "subprocess.check_output", "os.system", "os.popen",
                                    "socket.socket", "socket.create_connection")]
            code, report = self.invoke()
            self.assertEqual(code, 0)
            self.assertFalse(report["tests_executed"])
            for mock in mocks:
                mock.assert_not_called()

    def test_repository_spec_all_81_and_shared_floor(self):
        path = ROOT / "docs" / "refactor-input" / "acceptance.json"
        spec, digest = evidence.load_json(path)
        code, report = self.invoke(path=path, digest=digest)
        self.assertEqual(code, 0)
        self.assertEqual(report["counts"]["not_run"], 81)
        floors = evidence.spec_floors(spec)
        for row in report["items"]:
            self.assertEqual(row["required_kinds"], list(floors[row["item_id"]]))
        by_id = {row["item_id"]: row for row in report["items"]}
        self.assertEqual(by_id["AC-021"]["required_kinds"],
                         ["platform_automation", "real_device"])
        self.assertEqual(by_id["AC-070"]["required_kinds"],
                         ["static_review", "platform_automation", "real_device"])
        self.assertEqual(by_id["AC-072"]["required_kinds"], ["performance"])
        self.assertEqual(by_id["AC-073"]["required_kinds"], ["performance"])
        self.assertEqual(by_id["AC-075"]["required_kinds"], ["static_review"])


if __name__ == "__main__":
    unittest.main()
