#!/usr/bin/env python3
"""Validate task 15's offline specification, never execute product acceptance.

CLI exit 0 means schema/coverage validity ONLY; exit 2 means invalid input.
No commands, artifact paths or dependencies declared in the JSON are executed
or opened. The only input read is the explicitly selected manifest.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import sys

DEFAULT_MANIFEST = (Path(__file__).resolve().parents[2]
                    / "docs/acceptance/task15-contract-v1.json")
MAX_BYTES = 1024 * 1024
CONTRACT_ID = "task15-contract-v1"
EXECUTION_NOTE = ("Specification structure only; validator/unit-test success is not "
                  "product acceptance. No product tests have been executed.")
SOURCES = ["docs/并行分工.md", "docs/重构清单.md",
           "docs/refactor-input/acceptance.json"]
# Append within a feature, never renumber/reuse an existing v1 ID.
SCENARIOS = {
    "P01": ("assistant_crud_bindings", "conversation_owner_ab",
            "missing_owner_no_fallback", "deleted_owner_late_result",
            "frozen_persona_memory_skills"),
    "P07": ("folder_move_group", "pinned_order", "run_completion_identity",
            "draft_isolation", "drawer_menu_navigation"),
    "P28": ("title_preview_not_body", "folder_scope", "shorten_query_restore",
            "clear_query_restore"),
    "P27": ("current_body_scope", "all_records_body_scope",
            "tool_arguments_results", "pagination_no_gaps_duplicates",
            "cancel_reject_old_results", "snapshot_invalidation",
            "locate_unloaded_assistant", "locate_unloaded_tool",
            "deleted_hit_no_fallback"),
    "P06": ("model_search", "select_all_invert_counts", "zero_selection_disabled",
            "add_selected_preserve_local", "account_switch_reject_old_results"),
}
CASE_CONTRACTS = {
    f"T15-{feature}-{index:02d}": (feature, scenario)
    for feature, scenarios in SCENARIOS.items()
    for index, scenario in enumerate(scenarios, 1)
}
LEGAL_OWNERS = frozenset(f"{n:02d}" for n in range(1, 21))
DEPENDENCY_OWNERS = {
    "P01": {"01", "02", "03", "05", "11", "16"},
    "P07": {"01", "02", "03", "16"},
    "P28": {"03", "16"},
    "P27": {"02", "03", "16"},
    "P06": {"04"},
}
EVIDENCE_KINDS = {"E-UI", "E-DATA", "E-MODEL", "E-RUNTIME", "E-TOOLS"}
ROOT_FIELDS = {"schema_version", "contract_id", "owner", "status", "tests_executed",
               "execution_note", "sources", "cases"}
CASE_FIELDS = {"id", "feature_id", "scenario_id", "title", "given", "when", "then",
               "method", "basis", "owner", "dependencies", "evidence", "status",
               "tests_executed", "build_sha", "actual", "evidence_artifact"}


class InvalidContract(ValueError):
    """Contains only a fixed diagnostic code, never untrusted input text."""


def require(condition, code):
    if not condition:
        raise InvalidContract(code)


def exact_fields(value, fields, code):
    require(type(value) is dict and set(value) == fields, code)


def text(value, code):
    require(type(value) is str and bool(value.strip()) and len(value) <= 4096, code)


def array(value, minimum, maximum, code):
    require(type(value) is list and minimum <= len(value) <= maximum, code)


def validate_contract(document):
    """Return a structural summary, not an execution/acceptance result."""
    exact_fields(document, ROOT_FIELDS, "root_fields")
    require(type(document["schema_version"]) is int
            and document["schema_version"] == 1, "schema_version")
    require(type(document["contract_id"]) is str
            and document["contract_id"] == CONTRACT_ID, "contract_id")
    require(type(document["owner"]) is str and document["owner"] == "15", "owner")
    require(document["status"] == "specification_only", "specification_status")
    require(document["tests_executed"] is False, "execution_forbidden")
    require(document["execution_note"] == EXECUTION_NOTE, "execution_note")
    require(document["sources"] == SOURCES, "sources")
    cases = document["cases"]
    array(cases, 20, 30, "case_count")
    seen = set()
    feature_counts = Counter()
    for case in cases:
        exact_fields(case, CASE_FIELDS, "case_fields")
        for field in ("id", "feature_id", "scenario_id", "title", "given", "when",
                      "then", "method", "basis", "owner", "status"):
            text(case[field], "case_text")
        case_id = case["id"]
        require(case_id not in seen, "duplicate_id")
        require(case_id in CASE_CONTRACTS, "unknown_id")
        seen.add(case_id)
        feature = case["feature_id"]
        require((feature, case["scenario_id"]) == CASE_CONTRACTS[case_id],
                "case_identity")
        feature_counts[feature] += 1
        require(case["owner"] in LEGAL_OWNERS and case["owner"] == "15", "owner")
        require(case["status"] == "not_run", "case_execution_status")
        require(case["tests_executed"] is False, "execution_forbidden")
        require(all(case[field] is None for field in
                    ("build_sha", "actual", "evidence_artifact")), "execution_forbidden")
        dependencies = case["dependencies"]
        array(dependencies, 1, 8, "dependencies")
        owners = set()
        for dependency in dependencies:
            exact_fields(dependency, {"owner", "contract"}, "dependency_fields")
            text(dependency["owner"], "dependency_owner")
            owner = dependency["owner"]
            require(owner in LEGAL_OWNERS and owner in DEPENDENCY_OWNERS[feature],
                    "dependency_owner")
            require(owner not in owners, "duplicate_dependency")
            owners.add(owner)
            text(dependency["contract"], "dependency_assertion")
        evidence = case["evidence"]
        array(evidence, 2, len(EVIDENCE_KINDS), "evidence")
        kinds = set()
        for requirement in evidence:
            exact_fields(requirement, {"kind", "required"}, "evidence_fields")
            text(requirement["kind"], "evidence_kind")
            kind = requirement["kind"]
            require(kind in EVIDENCE_KINDS, "evidence_kind")
            require(kind not in kinds, "duplicate_evidence")
            kinds.add(kind)
            text(requirement["required"], "evidence_assertion")
        require({"E-UI", "E-DATA"} <= kinds, "evidence_coverage")
    require(set(feature_counts) == set(SCENARIOS), "feature_coverage")
    require(seen == set(CASE_CONTRACTS), "scenario_coverage")
    return {"contract_valid": True, "validation_scope": "specification_structure_only",
            "status": "specification_only", "tests_executed": False,
            "case_count": len(cases), "feature_counts": dict(sorted(feature_counts.items()))}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate_json_key")
        result[key] = value
    return result


def reject_constant(_value):
    raise InvalidContract("nonfinite_json")


def load_manifest(path):
    try:
        with Path(path).open("rb") as stream:
            raw = stream.read(MAX_BYTES + 1)
        require(len(raw) <= MAX_BYTES, "input_too_large")
        return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                          parse_constant=reject_constant)
    except InvalidContract:
        raise
    except (OSError, UnicodeError, ValueError, RecursionError):
        raise InvalidContract("invalid_json_or_file") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", nargs="?", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args(argv)
    try:
        report = validate_contract(load_manifest(args.manifest))
    except InvalidContract as error:
        report = {"contract_valid": False, "validation_scope": "specification_structure_only",
                  "tests_executed": False, "error": str(error)}
        print(json.dumps(report, ensure_ascii=True, sort_keys=True))
        return 2
    print(json.dumps(report, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
