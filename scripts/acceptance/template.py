#!/usr/bin/env python3
"""Emit a new all-not-run ledger or minimum-category policy; never import results."""
import json
import re
import sys

from evidence import (InvalidEvidence, SafeParser, SCHEMA_VERSION, full_sha,
                      load_json, require, spec_floors, spec_items)


def make_template(spec, digest, build_sha, output_format, expected_count=81):
    full_sha(build_sha)
    ids = spec_items(spec, expected_count)
    # Emit only canonical spec identifiers, never titles, commands, or free text.
    require(all(re.fullmatch(r"AC-[0-9]{3}", i) for i in ids), "noncanonical_case_ids")
    floors = spec_floors(spec)
    require(set(floors) == set(ids), "case_methods_required")
    if output_format == "policy":
        return {"schema_version": SCHEMA_VERSION, "spec_sha256": digest,
                "requirements": {i: list(floors[i]) for i in ids}}
    require(output_format == "ledger", "invalid_template_format")
    return {"schema_version": SCHEMA_VERSION, "build_sha": build_sha,
            "spec_sha256": digest,
            "records": [{"item_id": i, "kind": kind, "status": "not_run",
                         "build_sha": build_sha, "command": None, "exit_code": None,
                         "artifacts": []} for i in ids for kind in floors[i]]}


def main(argv=None):
    parser = SafeParser(description=__doc__)
    parser.add_argument("--spec", required=True)
    parser.add_argument("--build-sha", required=True)
    parser.add_argument("--format", choices=("policy", "ledger"), required=True)
    parser.add_argument("--expected-count", type=int, default=81)
    try:
        args = parser.parse_args(argv)
        spec, digest = load_json(args.spec)
        output = make_template(spec, digest, args.build_sha, args.format, args.expected_count)
    except InvalidEvidence as error:
        print(json.dumps({"valid": False, "error": str(error)}, sort_keys=True))
        return 2
    except (OSError, ValueError, TypeError, RecursionError, OverflowError):
        print('{"valid": false, "error": "unreadable_or_invalid_input"}')
        return 2
    print(json.dumps(output, ensure_ascii=True, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
