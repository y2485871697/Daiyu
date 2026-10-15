"""Planner contract tests only; no Android/physical/paid acceptance execution."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
MODULE_DIR = ROOT / "scripts" / "acceptance"


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, MODULE_DIR / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


evidence = module("scenario_test_evidence", "evidence.py")
with patch.dict(sys.modules, {"evidence": evidence}):
    template = module("scenario_test_template", "template.py")
    with patch.dict(sys.modules, {"template": template}):
        scenarios = module("scenario_test_planner", "scenarios.py")
SHA = "a" * 40
DIGEST = "d" * 64
SECRET = "DO_NOT_ECHO_SYNTHETIC_SECRET"


def fixture():
    # These synthetic methods test classification, not product behavior.
    cases = [{"id": "AC-%03d" % n, "method": "自动化故障注入/合成数据"}
             for n in range(1, 82)]
    methods = {
        11: "合成协议+Android真机", 17: "Android真机端到端",
        36: "合成记录+真机只读", 41: "Android故障注入",
        42: "Android真机后台任务", 43: "Android故障注入",
        70: "静态组件检查+支持设备实机+合成迁移包",
        72: "真机性能采样；候选门槛待S1设备基准冻结",
        73: "真机资源采样；候选门槛待基准冻结",
        77: "Android权限矩阵", 81: "Android权限矩阵",
    }
    for number, method in methods.items():
        cases[number - 1]["method"] = method
    return {"schema_version": 1, "cases": cases}


class ScenarioTests(unittest.TestCase):
    def setUp(self):
        self.spec = fixture()

    def plan(self, **kwargs):
        return scenarios.make_plan(self.spec, DIGEST, SHA, DIGEST, **kwargs)

    def reject(self, code, spec=None, digest=DIGEST, sha=SHA, pin=DIGEST, cases=None):
        with self.assertRaisesRegex(evidence.InvalidEvidence, "^" + code + "$"):
            scenarios.make_plan(self.spec if spec is None else spec, digest, sha, pin, cases)

    def test_nonempty_plan_and_complete_case_bindings(self):
        plan = self.plan()
        self.assertEqual(plan["case_count"], 16)
        self.assertEqual([r["case_id"] for r in plan["cases"]], list(scenarios.CASE_IDS))
        involved = set()
        for row in plan["cases"]:
            self.assertTrue(row["ac_bindings"])
            self.assertIn(row["status"], ("not_run", "blocked"))
            self.assertEqual(row["status"] == "blocked", bool(row["blocker_codes"]))
            for binding in row["ac_bindings"]:
                involved.add(binding["item_id"])
                self.assertIn(binding["item_id"], scenarios.AC_IDS)
                self.assertTrue(binding["required_kinds"])
                self.assertEqual(binding["review_owner_number"], 20)
        self.assertEqual(involved, set(scenarios.OWNERS))
        self.assertEqual(plan["covered_ac_count"] + plan["unplanned_ac_count"], 81)
        self.assertFalse(plan["ledger_compatible"])
        self.assertFalse(plan["acceptance_claimed"])
        self.assertFalse(plan["tests_executed"])
        self.assertNotIn("records", plan)

    def test_all_required_kinds_use_existing_helper_and_only_add_floors(self):
        original = scenarios.make_template
        with patch.object(scenarios, "make_template", wraps=original) as helper:
            plan = self.plan()
        helper.assert_called_once_with(self.spec, DIGEST, SHA, "policy")
        policy = template.make_template(self.spec, DIGEST, SHA, "policy")
        for row in plan["cases"]:
            for binding in row["ac_bindings"]:
                self.assertLessEqual(set(policy["requirements"][binding["item_id"]]),
                                     set(binding["required_kinds"]))
        by_id = {r["case_id"]: r for r in plan["cases"]}
        self.assertIn("real_device", by_id["BIZ-001"]["ac_bindings"][0]["required_kinds"])
        self.assertEqual(by_id["BIZ-014"]["ac_bindings"][0]["required_kinds"], ["performance"])
        self.assertEqual(set(by_id["BIZ-015"]["ac_bindings"][0]["required_kinds"]),
                         {"static_review", "platform_automation", "real_device"})

    def test_owner_uncertainties_and_measurement_responsibility(self):
        bindings = {b["item_id"]: b for r in self.plan()["cases"] for b in r["ac_bindings"]}
        self.assertEqual(bindings["AC-062"]["owner_resolution"], "confirmed_by_01")
        self.assertEqual(bindings["AC-062"]["production_owner_numbers"], [6])
        for ac in ("AC-072", "AC-073"):
            self.assertEqual(bindings[ac]["production_owner_numbers"], [])
            self.assertEqual(bindings[ac]["measurement_owner_number"], 20)
            self.assertEqual(bindings[ac]["owner_resolution"], "measurement_only")

    def test_full_sha_and_pin_are_mandatory(self):
        for sha in ("HEAD", "a" * 7, "A" * 40, "a" * 39, None):
            self.reject("invalid_build_sha", sha=sha)
        for pin in (None, "d" * 7, "D" * 64):
            self.reject("invalid_spec_pin", pin=pin)
        self.reject("scenario_spec_pin_mismatch", pin="e" * 64)
        self.reject("invalid_spec_digest", digest="digest")
        self.assertEqual(scenarios.make_plan(self.spec, DIGEST, "b" * 64, DIGEST)
                         ["build_sha"], "b" * 64)

    def test_ac_count_duplicates_unknown_order_and_shape_rejected(self):
        spec = copy.deepcopy(self.spec)
        spec["cases"].pop()
        self.reject("spec_count_mismatch", spec=spec)
        spec = copy.deepcopy(self.spec)
        spec["cases"][0]["id"] = "AC-002"
        self.reject("duplicate_item_id", spec=spec)
        spec["cases"][0]["id"] = "AC-999"
        self.reject("scenario_ac_coverage_mismatch", spec=spec)
        spec = copy.deepcopy(self.spec)
        spec["cases"].reverse()
        self.reject("scenario_ac_coverage_mismatch", spec=spec)
        self.reject("scenario_cases_spec_required", spec={"items": self.spec["cases"]})

    def test_unknown_method_anywhere_even_outside_scenario_is_rejected(self):
        self.spec["cases"][74]["method"] = SECRET
        self.reject("unclassified_spec_method")

    def test_unknown_catalog_ac_and_owner_gaps_fail_closed(self):
        catalog = list(scenarios.CATALOG)
        catalog[0] = catalog[0]._replace(ac_ids=("AC-999",))
        with patch.object(scenarios, "CATALOG", tuple(catalog)):
            self.reject("unknown_scenario_ac")
        with patch.object(scenarios, "OWNERS", {}):
            self.reject("scenario_owner_coverage_mismatch")

    def test_case_selection_is_strict_canonical_and_not_free_text(self):
        self.assertEqual([r["case_id"] for r in self.plan(case_ids=["BIZ-014", "BIZ-001"])
                          ["cases"]], ["BIZ-001", "BIZ-014"])
        for cases in ([SECRET], ["AC-049"], [], "BIZ-001", [None]):
            self.reject("unknown_scenario_case", cases=cases)
        self.reject("duplicate_scenario_case", cases=["BIZ-001", "BIZ-001"])

    def test_no_passed_no_execution_fields_no_historical_claims(self):
        for case in self.spec["cases"]:
            case.update(status="passed", build_sha="b" * 40, actual=SECRET,
                        command=["adb", SECRET], evidence_artifact=SECRET,
                        title=SECRET, given=SECRET, then=SECRET)
        plan = self.plan()
        encoded = json.dumps(plan)
        self.assertNotIn(SECRET, encoded)
        self.assertNotIn('"passed"', encoded)
        self.assertNotIn('"failed"', encoded)
        for key in ("command", "argv", "exit_code", "artifacts", "actual", "environment"):
            self.assertNotIn('"' + key + '"', encoded)
        self.assertEqual(plan["build_sha"], SHA)
        self.assertEqual(plan["spec_sha256"], DIGEST)
        summary = scenarios.summarize(plan)
        self.assertEqual(sum(summary["counts"].values()), 16)
        self.assertNotIn("cases", summary)

    def test_planning_does_not_execute_commands_network_or_gui(self):
        # These guards test the planner's boundary only. They are NOT evidence of
        # process death, network loss, OEM grants, or absence of paid app traffic.
        with contextlib.ExitStack() as stack:
            for obj, name in ((subprocess, "run"), (subprocess, "Popen"),
                              (subprocess, "call"), (os, "system"), (os, "popen"),
                              (os, "execv"), (socket, "socket"),
                              (socket, "create_connection")):
                stack.enter_context(patch.object(obj, name,
                                                 side_effect=AssertionError("execution forbidden")))
            plan = self.plan()
            self.assertEqual(plan["case_count"], 16)


@unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd,
                     "safe POSIX file operations unavailable")
class ScenarioCliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "spec.json"
        self.spec = fixture()
        raw = json.dumps(self.spec).encode()
        self.path.write_bytes(raw)
        self.digest = hashlib.sha256(raw).hexdigest()

    def args(self):
        return ["--spec", str(self.path), "--build-sha", SHA,
                "--spec-sha256", self.digest]

    def invoke(self, args):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = scenarios.main(args)
        self.assertEqual(stderr.getvalue(), "")
        self.assertNotIn(SECRET, stdout.getvalue())
        return code, json.loads(stdout.getvalue())

    def test_default_summary_and_nonempty_plan_artifact(self):
        code, summary = self.invoke(self.args())
        self.assertEqual(code, 0)  # Planning only, NOT business passed.
        self.assertTrue(summary["valid"])
        self.assertFalse(summary["acceptance_claimed"])
        self.assertEqual(sum(summary["counts"].values()), 16)
        code, plan = self.invoke(self.args() + ["--format", "plan"])
        self.assertEqual(code, 0)
        self.assertEqual(len(plan["cases"]), 16)
        self.assertNotIn("records", plan)
        self.assertEqual(plan["spec_sha256"], self.digest)

    def test_bad_pin_cli_unknown_case_and_missing_path_are_safe(self):
        for args, error in (
            (self.args() + ["--case", SECRET], "unknown_scenario_case"),
            (self.args() + ["--unknown", SECRET], "invalid_cli_arguments"),
            (["--spec", str(self.path.parent / SECRET), "--build-sha", SHA,
              "--spec-sha256", self.digest], "unreadable_or_invalid_input"),
            (self.args()[:-1] + ["e" * 64], "scenario_spec_pin_mismatch"),
        ):
            code, output = self.invoke(args)
            self.assertEqual(code, 2)
            self.assertEqual(output, {"valid": False, "error": error})

    def test_oserror_never_echoes_filename(self):
        with patch.object(scenarios, "load_json", side_effect=OSError(SECRET)):
            code, output = self.invoke(self.args())
        self.assertEqual(code, 2)
        self.assertEqual(output["error"], "unreadable_or_invalid_input")

    def test_changed_spec_bytes_require_new_reviewed_pin(self):
        self.path.write_bytes(self.path.read_bytes() + b"\n")
        code, output = self.invoke(self.args())
        self.assertEqual(code, 2)
        self.assertEqual(output["error"], "scenario_spec_pin_mismatch")

    def test_spec_free_text_is_not_exported(self):
        for case in self.spec["cases"]:
            case.update(title=SECRET, given=SECRET, actual=SECRET, status="passed")
        raw = json.dumps(self.spec).encode()
        self.path.write_bytes(raw)
        self.digest = hashlib.sha256(raw).hexdigest()
        code, output = self.invoke(self.args() + ["--format", "plan"])
        self.assertEqual(code, 0)
        self.assertNotIn('"passed"', json.dumps(output))

    def test_supplied_spec_integrity_and_no_ledger_substitution(self):
        path = ROOT / "docs" / "refactor-input" / "acceptance.json"
        # Required integration input, not a silent skip to a reduced mock spec.
        spec, digest = evidence.load_json(path)
        plan = scenarios.make_plan(spec, digest, SHA, digest)
        self.assertEqual(evidence.spec_items(spec), list(scenarios.AC_IDS))
        self.assertEqual(plan["case_count"], 16)
        policy = template.make_template(spec, digest, SHA, "policy")
        with self.assertRaisesRegex(evidence.InvalidEvidence, "^invalid_records$"):
            evidence.verify(spec, digest, plan, policy, SHA, self.path.parent)


if __name__ == "__main__":
    unittest.main()
