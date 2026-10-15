#!/usr/bin/env python3
"""Generate an offline evidence-routing plan, never application acceptance results."""
from __future__ import annotations

import json
import sys

from evidence import (DIGEST, KINDS, ROLES, SCHEMA_VERSION, InvalidEvidence,
                      SafeParser, full_sha, load_json, require, spec_floors,
                      spec_items)

# Explicit per-AC routing reviewed against docs/并行分工.md and acceptance.json.
# Feature identifiers are identity guards, NOT task/owner numbers. In particular
# F20 belongs to task 12, F31 has two owners, and F01 splits between 15 and 16.
# Values: (expected feature_id, routing owner). 1 integrates, 19 reviews sources,
# 20 accepts; none is represented as a production implementation owner.
ROUTES = {
    "AC-001": ("F01", 16),
    "AC-002": ("F01", 16),
    "AC-003": ("F01", 16),
    "AC-004": ("F01", 15),
    "AC-005": ("F02", 4),
    "AC-006": ("F02", 4),
    "AC-007": ("F02", 4),
    "AC-008": ("F03", 2),
    "AC-009": ("F03", 2),
    "AC-010": ("F03", 2),
    "AC-011": ("F04", 7),
    "AC-012": ("F04", 6),
    "AC-013": ("F04", 6),
    "AC-014": ("F05", 3),
    "AC-015": ("F05", 3),
    "AC-016": ("F05", 3),
    "AC-017": ("F05", 1),
    "AC-018": ("F06", 16),
    "AC-019": ("F06", 16),
    "AC-020": ("F06", 16),
    "AC-021": ("F06", 16),
    "AC-022": ("F06", 16),
    "AC-023": ("F07", 6),
    "AC-024": ("F07", 6),
    "AC-025": ("F08", 6),
    "AC-026": ("F08", 6),
    "AC-027": ("F09", 2),
    "AC-028": ("F09", 2),
    "AC-029": ("F09", 2),
    "AC-030": ("F09", 2),
    "AC-031": ("F10", 6),
    "AC-032": ("F10", 6),
    "AC-033": ("F11", 5),
    "AC-034": ("F11", 5),
    "AC-035": ("F11", 5),
    "AC-036": ("F12", 7),
    "AC-037": ("F12", 7),
    "AC-038": ("F13", 8),
    "AC-039": ("F13", 8),
    "AC-040": ("F14", 8),
    "AC-041": ("F14", 8),
    "AC-042": ("F15", 8),
    "AC-043": ("F15", 8),
    "AC-044": ("F16", 10),
    "AC-045": ("F16", 10),
    "AC-046": ("F17", 9),
    "AC-047": ("F17", 9),
    "AC-048": ("F18", 13),
    "AC-049": ("F18", 13),
    "AC-050": ("F19", 18),
    "AC-051": ("F19", 18),
    "AC-052": ("F19", 18),
    "AC-053": ("F20", 12),
    "AC-054": ("F20", 12),
    "AC-055": ("F20", 12),
    "AC-056": ("F21", 12),
    "AC-057": ("F21", 12),
    "AC-058": ("F22", 11),
    "AC-059": ("F22", 11),
    "AC-060": ("F23", 5),
    "AC-061": ("F23", 5),
    "AC-062": ("F24", 6),  # MCP responsibility confirmed by task 1; division v1.1.
    "AC-063": ("F25", 14),
    "AC-064": ("F25", 14),
    "AC-065": ("F25", 14),
    "AC-066": ("F26", 14),
    "AC-067": ("F26", 14),
    "AC-068": ("F27", 14),
    "AC-069": ("F27", 14),
    "AC-070": ("F28", 1),
    "AC-071": ("F29", 17),
    "AC-072": ("F30", 20),
    "AC-073": ("F30", 20),
    "AC-074": ("F31", 1),
    "AC-075": ("F31", 19),
    "AC-076": ("F01", 15),
    "AC-077": ("F07", 7),
    "AC-078": ("F13", 8),
    "AC-079": ("F15", 8),
    "AC-080": ("F17", 9),
    "AC-081": ("F12", 7),
}
CANONICAL_IDS = tuple("AC-%03d" % number for number in range(1, 82))
PENDING_OWNERS = set()


def owner_role(owner):
    if owner == 1:
        return "integration"
    if owner == 19:
        return "provenance"
    if owner == 20:
        return "acceptance"
    return "production"


def make_coverage(spec, digest, build_sha, expected_spec_sha256):
    """Return a fresh all-not-run plan; digest must come from safe load_json.

    This is not a ledger importer. It deliberately ignores historical status,
    execution claims and free text in the specification. No result is evaluated.
    """
    full_sha(build_sha)
    require(isinstance(digest, str) and DIGEST.fullmatch(digest), "invalid_spec_digest")
    require(isinstance(expected_spec_sha256, str)
            and DIGEST.fullmatch(expected_spec_sha256), "invalid_expected_spec_digest")
    require(digest == expected_spec_sha256, "spec_digest_mismatch")
    require(isinstance(spec, dict) and "cases" in spec, "unsupported_coverage_spec")
    require(type(spec.get("schema_version")) is int and spec["schema_version"] == 1,
            "unsupported_spec_version")
    ids = spec_items(spec, 81)
    require(set(ids) == set(CANONICAL_IDS), "noncanonical_case_ids")
    require(set(ROUTES) == set(CANONICAL_IDS)
            and all(type(route[1]) is int and 1 <= route[1] <= 20
                    for route in ROUTES.values())
            and PENDING_OWNERS <= set(ROUTES), "invalid_routing_configuration")
    cases = {case["id"]: case for case in spec["cases"]}
    for item_id in CANONICAL_IDS:
        require(cases[item_id].get("feature_id") == ROUTES[item_id][0],
                "case_feature_mismatch")
    # Share the validator/template minimum classification; never replace it
    # with an independent keyword classifier or weaken real-device requirements.
    floors = spec_floors(spec)
    require(set(floors) == set(CANONICAL_IDS), "case_methods_required")
    rows, unmet = [], []
    owners = {owner: [] for owner in range(1, 21)}
    for item_id in CANONICAL_IDS:
        owner = ROUTES[item_id][1]
        kinds = list(floors[item_id])
        require(kinds and all(kind in KINDS for kind in kinds), "invalid_required_kinds")
        pending = item_id in PENDING_OWNERS
        owners[owner].append(item_id)
        rows.append({
            "item_id": item_id, "owner": owner, "owner_role": owner_role(owner),
            "production_owner": owner if 2 <= owner <= 18 else None,
            "integration_owner": 1, "acceptance_owner": 20,
            "mapping_status": "pending_integration_confirmation" if pending else "configured",
            "required_kinds": kinds, "status": "not_run",
        })
        for kind in kinds:
            unmet.append({"item_id": item_id, "owner": owner, "kind": kind,
                          "status": "not_run", "reasons": ["missing_record"],
                          "required_artifact_roles": sorted(ROLES[kind])})
        if pending:
            unmet.append({"item_id": item_id, "owner": owner, "kind": None,
                          "status": "not_run", "reasons": ["owner_confirmation_required"],
                          "confirmation_owner": 1})
    return {
        "schema_version": SCHEMA_VERSION, "report_type": "evidence_routing_plan",
        "build_sha": build_sha, "spec_sha256": digest,
        "tests_executed": False, "status": "not_run",
        "counts": {"not_run": 81, "passed": 0, "failed": 0, "blocked": 0},
        "owners": [{"owner": owner, "role": owner_role(owner),
                    "item_ids": owners[owner], "count": len(owners[owner])}
                   for owner in owners],
        "items": rows, "unmet": unmet,
    }


def main(argv=None):
    parser = SafeParser(description=__doc__)
    parser.add_argument("--spec", required=True)
    parser.add_argument("--build-sha", required=True)
    parser.add_argument("--expected-spec-sha256", "--spec-sha256", required=True,
                        dest="expected_spec_sha256")
    try:
        args = parser.parse_args(argv)
        spec, digest = load_json(args.spec)
        output = make_coverage(spec, digest, args.build_sha, args.expected_spec_sha256)
    except InvalidEvidence as error:
        print(json.dumps({"valid": False, "error": str(error)}, sort_keys=True))
        return 2
    except (OSError, ValueError, TypeError, RecursionError, OverflowError):
        print('{"valid": false, "error": "unreadable_or_invalid_input"}')
        return 2
    print(json.dumps({"valid": True, **output}, ensure_ascii=True, indent=2, sort_keys=True))
    # Success means generation only, NOT an acceptance pass (see evidence.py).
    return 0


if __name__ == "__main__":
    sys.exit(main())
