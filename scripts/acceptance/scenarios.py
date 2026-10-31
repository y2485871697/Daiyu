#!/usr/bin/env python3
"""Offline business-scenario planner. No execution and no acceptance ledger."""
from __future__ import annotations

import json
import sys
from typing import NamedTuple

from evidence import (DIGEST, KINDS, SCHEMA_VERSION, InvalidEvidence, SafeParser,
                      full_sha, load_json, require, spec_items)
from template import make_template

PLAN_TYPE = "business_scenario_plan"
CASE_IDS = tuple("BIZ-%03d" % n for n in range(1, 17))
AC_IDS = tuple("AC-%03d" % n for n in range(1, 82))


class Scenario(NamedTuple):
    case_id: str
    ac_ids: tuple
    extra_kinds: tuple
    blockers: tuple


# These are reviewed contract identifiers, not input titles or runnable commands.
# Procedure, limits and evidence obligations live in business-scenarios.md.
CATALOG = (
    Scenario("BIZ-001", ("AC-002",), ("real_device",),
             ("physical_device_not_arranged",)),
    Scenario("BIZ-002", ("AC-014", "AC-015", "AC-017", "AC-030"),
             ("real_device",), ("physical_device_not_arranged",)),
    Scenario("BIZ-003", ("AC-050", "AC-051"), ("real_device",),
             ("physical_device_not_arranged",)),
    Scenario("BIZ-004", ("AC-009", "AC-025", "AC-026", "AC-027"),
             ("real_device",), ("physical_network_test_not_arranged",)),
    Scenario("BIZ-005", ("AC-003", "AC-009", "AC-010"), (), ()),
    Scenario("BIZ-006", ("AC-010", "AC-031", "AC-032"), (), ()),
    Scenario("BIZ-007", ("AC-015", "AC-023", "AC-025", "AC-026", "AC-062"), (), ()),
    Scenario("BIZ-008", ("AC-050", "AC-051"), ("real_device",),
             ("oem_cross_app_grant_not_arranged",)),
    Scenario("BIZ-009", ("AC-011", "AC-036", "AC-077"), ("real_device",),
             ("physical_permission_matrix_not_arranged",)),
    Scenario("BIZ-010", ("AC-081",), ("real_device",),
             ("physical_permission_matrix_not_arranged",)),
    Scenario("BIZ-011", ("AC-040", "AC-041", "AC-042", "AC-043", "AC-079"),
             ("real_device",), ("physical_display_test_not_arranged",)),
    Scenario("BIZ-012", ("AC-038", "AC-039", "AC-078"), ("real_device",),
             ("physical_display_test_not_arranged",)),
    Scenario("BIZ-013", ("AC-026", "AC-048", "AC-049"), (),
             ("paid_request_execution_forbidden", "existing_receipt_review_required")),
    Scenario("BIZ-014", ("AC-072", "AC-073"), (),
             ("device_baseline_not_frozen", "physical_device_not_arranged")),
    Scenario("BIZ-015", ("AC-070",), (),
             ("supported_device_entry_review_not_arranged",)),
    Scenario("BIZ-016", ("AC-063", "AC-064", "AC-065", "AC-068"), (), ()),
)

# Production owners; performance entries identify measurement responsibility only.
OWNERS = {
    "AC-002": (16, 3), "AC-003": (16, 2), "AC-009": (2, 4),
    "AC-010": (2,), "AC-011": (7, 6), "AC-014": (3, 16),
    "AC-015": (3, 2, 6), "AC-017": (2, 3, 6), "AC-023": (6,),
    "AC-025": (6, 2), "AC-026": (6, 2), "AC-027": (2, 4),
    "AC-030": (2, 3), "AC-031": (6, 16), "AC-032": (6, 2),
    "AC-036": (7, 5), "AC-038": (8,), "AC-039": (8,),
    "AC-040": (8, 6), "AC-041": (8,), "AC-042": (8,),
    "AC-043": (8,), "AC-048": (13,), "AC-049": (13, 6),
    "AC-050": (18, 3), "AC-051": (18, 3), "AC-062": (6,),
    "AC-063": (14,), "AC-064": (14,), "AC-065": (14,),
    "AC-068": (14,), "AC-070": (1, 7, 18), "AC-072": (),
    "AC-073": (), "AC-077": (7, 6), "AC-078": (8,),
    "AC-079": (8,), "AC-081": (7,),
}
BLOCKERS = frozenset({
    "physical_device_not_arranged", "physical_network_test_not_arranged",
    "oem_cross_app_grant_not_arranged", "physical_permission_matrix_not_arranged",
    "physical_display_test_not_arranged", "paid_request_execution_forbidden",
    "existing_receipt_review_required", "device_baseline_not_frozen",
    "supported_device_entry_review_not_arranged",
})


def _validate_catalog(ids, requirements):
    require(tuple(s.case_id for s in CATALOG) == CASE_IDS, "invalid_scenario_catalog")
    involved = set()
    for scenario in CATALOG:
        require(bool(scenario.ac_ids) and len(set(scenario.ac_ids)) == len(scenario.ac_ids),
                "invalid_scenario_ac_binding")
        require(all(ac in ids for ac in scenario.ac_ids), "unknown_scenario_ac")
        require(all(k in KINDS for k in scenario.extra_kinds)
                and len(set(scenario.extra_kinds)) == len(scenario.extra_kinds),
                "invalid_scenario_kinds")
        require(all(b in BLOCKERS for b in scenario.blockers)
                and len(set(scenario.blockers)) == len(scenario.blockers),
                "invalid_scenario_blockers")
        involved.update(scenario.ac_ids)
    require(set(OWNERS) == involved, "scenario_owner_coverage_mismatch")
    for ac in involved:
        owners = OWNERS[ac]
        require(isinstance(owners, tuple) and len(set(owners)) == len(owners)
                and all(type(n) is int and 1 <= n <= 19 for n in owners),
                "invalid_scenario_owner")
        require(bool(owners) or ac in ("AC-072", "AC-073"), "missing_scenario_owner")
        require(ac in requirements and bool(requirements[ac]), "missing_scenario_requirements")


def make_plan(spec, spec_digest, build_sha, spec_pin, case_ids=None):
    """Use load_json's digest and an independently reviewed byte-level pin.

    A SHA labels a future target only. This function cannot prove HEAD, a clean
    tree, artifact provenance, baseline availability, or physical execution.
    """
    full_sha(build_sha)
    require(isinstance(spec_pin, str) and DIGEST.fullmatch(spec_pin), "invalid_spec_pin")
    require(isinstance(spec_digest, str) and DIGEST.fullmatch(spec_digest),
            "invalid_spec_digest")
    require(spec_digest == spec_pin, "scenario_spec_pin_mismatch")
    require(isinstance(spec, dict) and "cases" in spec, "scenario_cases_spec_required")
    ids = spec_items(spec)
    require(tuple(ids) == AC_IDS, "scenario_ac_coverage_mismatch")
    # Reuse the existing method classification and policy floor for ALL 81 ACs.
    # Never infer a weaker kind from a scenario label or test function name.
    policy = make_template(spec, spec_digest, build_sha, "policy")
    requirements = policy["requirements"]
    _validate_catalog(ids, requirements)
    if case_ids is None:
        selected = CASE_IDS
    else:
        require(isinstance(case_ids, (list, tuple)) and bool(case_ids)
                and all(isinstance(c, str) and c in CASE_IDS for c in case_ids),
                "unknown_scenario_case")
        require(len(set(case_ids)) == len(case_ids), "duplicate_scenario_case")
        selected = tuple(c for c in CASE_IDS if c in case_ids)
    rows = []
    for scenario in CATALOG:
        if scenario.case_id not in selected:
            continue
        bindings = []
        for ac in scenario.ac_ids:
            kinds = set(requirements[ac]) | set(scenario.extra_kinds)
            bindings.append({
                "item_id": ac,
                "required_kinds": [k for k in KINDS if k in kinds],
                "production_owner_numbers": list(OWNERS[ac]),
                "review_owner_number": 20,
                "measurement_owner_number": 20 if ac in ("AC-072", "AC-073") else None,
                "owner_resolution": ("confirmed_by_01" if ac == "AC-062" else
                                     "measurement_only" if ac in ("AC-072", "AC-073") else
                                     "division_derived"),
            })
        rows.append({"case_id": scenario.case_id,
                     "status": "blocked" if scenario.blockers else "not_run",
                     "blocker_codes": list(scenario.blockers), "ac_bindings": bindings})
    involved = {b["item_id"] for row in rows for b in row["ac_bindings"]}
    return {"schema_version": SCHEMA_VERSION, "document_type": PLAN_TYPE,
            "build_sha": build_sha, "spec_sha256": spec_digest,
            "tests_executed": False, "acceptance_claimed": False,
            "ledger_compatible": False,
            "spec_item_count": len(ids), "covered_ac_count": len(involved),
            "unplanned_ac_count": len(ids) - len(involved),
            "case_count": len(rows), "cases": rows}


def summarize(plan):
    """Only generated whitelist codes, SHA/digest and numeric counts cross stdout."""
    return {key: plan[key] for key in (
        "schema_version", "document_type", "build_sha", "spec_sha256",
        "tests_executed", "acceptance_claimed", "ledger_compatible", "spec_item_count",
        "covered_ac_count", "unplanned_ac_count", "case_count")} | {
            "counts": {status: sum(row["status"] == status for row in plan["cases"])
                       for status in ("not_run", "blocked")}}


def main(argv=None):
    parser = SafeParser(description=__doc__)
    parser.add_argument("--spec", required=True)
    parser.add_argument("--spec-sha256", required=True)
    parser.add_argument("--build-sha", required=True)
    parser.add_argument("--case", action="append", dest="case_ids")
    parser.add_argument("--format", choices=("plan", "summary"), default="summary")
    try:
        args = parser.parse_args(argv)
        spec, digest = load_json(args.spec)
        plan = make_plan(spec, digest, args.build_sha, args.spec_sha256, args.case_ids)
        output = plan if args.format == "plan" else summarize(plan)
    except InvalidEvidence as error:
        print(json.dumps({"valid": False, "error": str(error)}, sort_keys=True))
        return 2
    except (OSError, ValueError, TypeError, RecursionError, OverflowError):
        # Do not echo an OSError path, exception, argument, or untrusted spec text.
        print('{"valid": false, "error": "unreadable_or_invalid_input"}')
        return 2
    print(json.dumps({"valid": True, **output}, ensure_ascii=True, sort_keys=True))
    # 0 means planning succeeded, never that a business AC passed.
    return 0


if __name__ == "__main__":
    sys.exit(main())
