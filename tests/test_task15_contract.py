"""Offline specification-validator tests, NOT product/business acceptance.

Parent runs:
  python3 -m unittest discover -s tests -p 'test_task15_contract.py' -v
  python3 scripts/acceptance/verify_task15_contract.py
No product module, network, user data, shell, or execution evidence is used.
"""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts/acceptance/verify_task15_contract.py"
SPEC = importlib.util.spec_from_file_location("task15_contract", MODULE_PATH)
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)
MANIFEST = ROOT / "docs/acceptance/task15-contract-v1.json"


class Task15ContractTests(unittest.TestCase):
    def setUp(self):
        # The real manifest is mandatory: no skip/fallback to a synthetic success.
        self.document = validator.load_manifest(MANIFEST)

    def reject(self, document, code=None):
        with self.assertRaises(validator.InvalidContract) as caught:
            validator.validate_contract(document)
        if code is not None:
            self.assertEqual(str(caught.exception), code)

    def mutate_case(self, field, value):
        fixture = copy.deepcopy(self.document)
        fixture["cases"][0][field] = value
        return fixture

    def test_real_manifest_structure_only(self):
        report = validator.validate_contract(self.document)
        self.assertEqual(report, {
            "contract_valid": True, "validation_scope": "specification_structure_only",
            "status": "specification_only", "tests_executed": False,
            "case_count": 28,
            "feature_counts": {"P01": 5, "P06": 5, "P07": 5, "P27": 9, "P28": 4},
        })
        self.assertNotIn("passed", report)
        self.assertEqual({case["status"] for case in self.document["cases"]}, {"not_run"})

    def test_stable_identity_set_and_order_independence(self):
        self.assertEqual({case["id"] for case in self.document["cases"]},
                         set(validator.CASE_CONTRACTS))
        self.document["cases"].reverse()
        self.assertEqual(validator.validate_contract(self.document)["case_count"], 28)

    def test_root_requires_exact_schema(self):
        for bad in (None, [], "manifest", 1, True):
            with self.subTest(value=bad):
                self.reject(bad, "root_fields")
        for field in validator.ROOT_FIELDS:
            fixture = copy.deepcopy(self.document)
            del fixture[field]
            with self.subTest(missing=field):
                self.reject(fixture, "root_fields")
        self.document["passed"] = True
        self.reject(self.document, "root_fields")

    def test_version_rejects_bool_float_string_and_unknown(self):
        for bad in (True, 1.0, "1", 2, None):
            fixture = copy.deepcopy(self.document)
            fixture["schema_version"] = bad
            with self.subTest(value=bad):
                self.reject(fixture, "schema_version")

    def test_contract_id_and_sources_are_bound(self):
        for field, value, code in (
            ("contract_id", "task15-contract-v2", "contract_id"),
            ("sources", [], "sources"),
            ("sources", list(reversed(validator.SOURCES)), "sources"),
            ("execution_note", "Tests passed", "execution_note"),
        ):
            fixture = copy.deepcopy(self.document)
            fixture[field] = value
            with self.subTest(field=field, value=value):
                self.reject(fixture, code)

    def test_root_never_claims_execution(self):
        for status in ("passed", "failed", "not_run", "specification_only ", True, None, []):
            fixture = copy.deepcopy(self.document)
            fixture["status"] = status
            with self.subTest(status=status):
                self.reject(fixture, "specification_status")
        for bad in (True, 0, 1, "false", None, []):
            fixture = copy.deepcopy(self.document)
            fixture["tests_executed"] = bad
            with self.subTest(executed=bad):
                self.reject(fixture, "execution_forbidden")

    def test_owner_legal_and_task15_only(self):
        for owner in ("00", "21", "04", 15, True, "15 ", None):
            fixture = copy.deepcopy(self.document)
            fixture["owner"] = owner
            with self.subTest(root_owner=owner):
                self.reject(fixture, "owner")
            with self.subTest(case_owner=owner):
                self.reject(self.mutate_case("owner", owner))

    def test_case_count_and_array_type(self):
        for value in ({}, None, "cases", [], self.document["cases"][:19],
                      self.document["cases"] + self.document["cases"][:3]):
            fixture = copy.deepcopy(self.document)
            fixture["cases"] = value
            with self.subTest(value_type=type(value).__name__):
                self.reject(fixture, "case_count")

    def test_case_requires_exact_fields(self):
        for field in validator.CASE_FIELDS:
            fixture = copy.deepcopy(self.document)
            del fixture["cases"][0][field]
            with self.subTest(missing=field):
                self.reject(fixture, "case_fields")
        for field in ("passed", "exit_code", "command", "product_passed"):
            with self.subTest(extra=field):
                self.reject(self.mutate_case(field, "forged"), "case_fields")
        self.document["cases"][0] = []
        self.reject(self.document, "case_fields")

    def test_duplicate_and_unknown_ids(self):
        self.document["cases"][1] = copy.deepcopy(self.document["cases"][0])
        self.reject(self.document, "duplicate_id")
        self.reject(self.mutate_case("id", "T15-P01-99"), "unknown_id")

    def test_stable_feature_scenario_mapping_cannot_be_relabelled(self):
        self.reject(self.mutate_case("feature_id", "P99"), "case_identity")
        self.reject(self.mutate_case("feature_id", "P06"), "case_identity")
        self.reject(self.mutate_case("scenario_id", "model_search"), "case_identity")

    def test_missing_feature_rejected(self):
        self.document["cases"] = [case for case in self.document["cases"]
                                  if case["feature_id"] != "P06"]
        self.reject(self.document, "feature_coverage")

    def test_missing_scenario_rejected_even_with_all_features(self):
        self.document["cases"].pop()
        self.reject(self.document, "scenario_coverage")

    def test_text_fields_strict_types_and_nonempty_assertions(self):
        for field in ("id", "feature_id", "scenario_id", "title", "given", "when", "then",
                      "method", "basis", "owner", "status"):
            for bad in ("", " \n\t", None, True, 42, [], {}, "x" * 4097):
                with self.subTest(field=field, value_type=type(bad).__name__):
                    self.reject(self.mutate_case(field, bad), "case_text")

    def test_case_execution_status_forbidden(self):
        for status in ("passed", "failed", "blocked", "specification_only"):
            with self.subTest(status=status):
                self.reject(self.mutate_case("status", status), "case_execution_status")
        for value in (True, 0, "false", None):
            with self.subTest(executed=value):
                self.reject(self.mutate_case("tests_executed", value), "execution_forbidden")

    def test_no_fabricated_sha_actual_or_artifact(self):
        for field in ("build_sha", "actual", "evidence_artifact"):
            for value in ("a" * 40, "passed", "future/report.json", "", False, [], {}):
                with self.subTest(field=field, value_type=type(value).__name__):
                    self.reject(self.mutate_case(field, value), "execution_forbidden")

    def test_dependencies_array_required(self):
        for bad in ([], None, {}, "03", [{}] * 9):
            with self.subTest(value_type=type(bad).__name__):
                self.reject(self.mutate_case("dependencies", bad), "dependencies")

    def test_dependency_exact_fields_and_nonempty_assertion(self):
        for bad, code in (({}, "dependency_fields"),
                          ({"owner": "03", "contract": "", "status": "passed"}, "dependency_fields"),
                          ({"owner": "03", "contract": "  "}, "dependency_assertion"),
                          ({"owner": "03", "contract": []}, "dependency_assertion")):
            with self.subTest(value=bad):
                self.reject(self.mutate_case("dependencies", [bad]), code)

    def test_dependency_owner_must_be_legal_and_feature_specific(self):
        for owner in ("00", "21", "15", "04", 3, True, None, [], ""):
            with self.subTest(owner=owner):
                self.reject(self.mutate_case("dependencies", [{"owner": owner, "contract": "synthetic"}]))
        p06 = next(case for case in self.document["cases"] if case["feature_id"] == "P06")
        p06["dependencies"] = [{"owner": "03", "contract": "wrong module"}]
        self.reject(self.document, "dependency_owner")

    def test_duplicate_dependency_owner_rejected(self):
        dependency = self.document["cases"][0]["dependencies"][0]
        self.document["cases"][0]["dependencies"].append(copy.deepcopy(dependency))
        self.reject(self.document, "duplicate_dependency")

    def test_evidence_array_strict(self):
        for bad in ([], None, {}, "E-UI", [{}], [{}] * 6):
            with self.subTest(value_type=type(bad).__name__):
                self.reject(self.mutate_case("evidence", bad), "evidence")

    def test_evidence_schema_kind_assertion_and_duplicate(self):
        for bad, code in (({}, "evidence_fields"),
                          ({"kind": "E-UI", "required": "test", "actual": "passed"}, "evidence_fields"),
                          ({"kind": "dispatch", "required": "test"}, "evidence_kind"),
                          ({"kind": [], "required": "test"}, "evidence_kind"),
                          ({"kind": "E-UI", "required": " \t"}, "evidence_assertion"),
                          ({"kind": "E-UI", "required": True}, "evidence_assertion")):
            fixture = copy.deepcopy(self.document)
            fixture["cases"][0]["evidence"][0] = bad
            with self.subTest(value=bad):
                self.reject(fixture, code)
        self.document["cases"][0]["evidence"][1] = copy.deepcopy(
            self.document["cases"][0]["evidence"][0])
        self.reject(self.document, "duplicate_evidence")

    def test_ui_and_data_evidence_are_both_required(self):
        self.document["cases"][0]["evidence"][0]["kind"] = "E-MODEL"
        self.reject(self.document, "evidence_coverage")

    def load_raw(self, raw):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.json"
            path.write_bytes(raw)
            return validator.load_manifest(path)

    def test_duplicate_json_keys_rejected_at_all_levels(self):
        for raw in (b'{"cases":[],"cases":[]}',
                    b'{"cases":[{"id":"x","id":"y"}]}',
                    b'{"cases":[{"evidence":[{"kind":"x","kind":"y"}]}]}'):
            with self.subTest(raw=raw):
                with self.assertRaisesRegex(validator.InvalidContract, "^duplicate_json_key$"):
                    self.load_raw(raw)

    def test_malformed_nonfinite_and_invalid_utf8_json(self):
        for raw in (b'{', b'{} trailing', b'\xff', b'\xef\xbb\xbf{}',
                    b'{"x": NaN}', b'{"x": Infinity}', b'{"x": -Infinity}'):
            with self.subTest(raw=raw):
                with self.assertRaises(validator.InvalidContract):
                    self.load_raw(raw)

    def test_oversized_or_unreadable_input_rejected(self):
        with self.assertRaisesRegex(validator.InvalidContract, "^input_too_large$"):
            self.load_raw(b" " * (validator.MAX_BYTES + 1))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(validator.InvalidContract, "^invalid_json_or_file$"):
                validator.load_manifest(Path(directory) / "not-created.json")
            with self.assertRaises(validator.InvalidContract):
                validator.load_manifest(Path(directory))

    def test_cli_default_is_valid_specification_not_acceptance(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = validator.main([])
        self.assertEqual(code, 0)
        report = json.loads(output.getvalue())
        self.assertTrue(report["contract_valid"])
        self.assertFalse(report["tests_executed"])
        self.assertEqual(report["validation_scope"], "specification_structure_only")
        self.assertNotIn("passed", report)

    def test_cli_damaged_fixture_exit_two_no_partial_success_or_free_text(self):
        marker = "SYNTHETIC_DO_NOT_ECHO"
        self.document["cases"][0]["actual"] = marker
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "damaged.json"
            path.write_text(json.dumps(self.document), encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = validator.main([str(path)])
        self.assertEqual(code, 2)
        self.assertNotIn(marker, output.getvalue())
        self.assertEqual(json.loads(output.getvalue()), {
            "contract_valid": False, "validation_scope": "specification_structure_only",
            "tests_executed": False, "error": "execution_forbidden",
        })


if __name__ == "__main__":
    unittest.main()
