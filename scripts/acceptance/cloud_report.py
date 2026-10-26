#!/usr/bin/env python3
"""Validate a pinned local cloud-regression snapshot; no network or execution.

This proves internal consistency of supplied bytes, NOT trusted collection or
business acceptance. The normalized snapshot format is documented separately.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys

from evidence import (DIGEST, InvalidEvidence, SafeParser, artifact_path,
                      bounded_read, full_sha, load_json, require)
from instrumentation_result import ResultError, instrumentation, report_text

REPOSITORY = "y2485871697/Daiyu"
WORKFLOW_ID = 397560064
BRANCH = "main"
APIS = ("26", "37.2")
MAX_TEXT_BYTES = 4 * 1024 * 1024
# Eight report JSONs and inventories (2 MiB each), plus sixteen texts (4 MiB each).
# Snapshot JSON itself is bounded by evidence.load_json (2 MiB).
MAX_TOTAL_BYTES = 96 * 1024 * 1024


def _object(value, keys, code):
    require(isinstance(value, dict) and set(value) == set(keys), code)


def _positive_int(value, code):
    require(type(value) is int and 0 < value <= 2**63 - 1, code)
    return value


def _digest(value):
    require(isinstance(value, str) and DIGEST.fullmatch(value), "invalid_digest")
    return value


def _version(value):
    require(type(value) is int and value in (1, 2), "unsupported_schema_version")


def _terminal(value):
    require(value.get("status") == "completed" and value.get("conclusion") == "success",
            "terminal_success_required")


def _identity(value, sha, run_id, attempt):
    _positive_int(value.get("run_id"), "invalid_run_id")
    _positive_int(value.get("run_attempt"), "invalid_attempt")
    full_sha(value.get("head_sha"))
    require(value["run_id"] == run_id and value["run_attempt"] == attempt
            and value["head_sha"] == sha, "execution_identity_mismatch")


def _ref(value):
    _object(value, ("path", "sha256"), "invalid_file_reference")
    _digest(value["sha256"])


def _text(raw):
    try:
        return report_text(raw)
    except ResultError as error:
        raise InvalidEvidence(str(error)) from None


def _instrumentation(raw, expected_tests):
    """Shared with live smoke: one whole-line OK and mandatory final -1."""
    try:
        return instrumentation(raw, expected_tests)
    except ResultError as error:
        raise InvalidEvidence(str(error)) from None


def verify(snapshot_path, snapshot_sha256, build_sha, run_id, attempt, evidence_root):
    """Reopen and rehash all files on every invocation, including the snapshot.

    All references are root-relative, bounded regular files opened without
    following any symlink component, using the existing evidence safety API.
    """
    full_sha(build_sha)
    _positive_int(run_id, "invalid_run_id")
    _positive_int(attempt, "invalid_attempt")
    _digest(snapshot_sha256)
    snapshot, actual_digest = load_json(snapshot_path)
    require(actual_digest == snapshot_sha256, "snapshot_pin_mismatch")
    _object(snapshot, ("schema_version", "run", "jobs", "reports"), "invalid_snapshot")
    _version(snapshot["schema_version"])
    sharded = snapshot["schema_version"] == 2
    slots = [(api, index) for api in APIS for index in range(4)] if sharded else list(APIS)
    shard_keys = ("shard_index", "shard_count") if sharded else ()
    def slot(value):
        if not sharded:
            return value["api"]
        require(type(value.get("shard_index")) is int and value["shard_index"] in range(4)
                and type(value.get("shard_count")) is int and value["shard_count"] == 4,
                "invalid_shard")
        return value["api"], value["shard_index"]
    run = snapshot["run"]
    _object(run, ("id", "run_attempt", "head_sha", "repository", "workflow_id",
                  "head_branch", "status", "conclusion"), "invalid_run")
    _positive_int(run["id"], "invalid_run_id")
    _positive_int(run["run_attempt"], "invalid_attempt")
    _positive_int(run["workflow_id"], "invalid_workflow_id")
    full_sha(run["head_sha"])
    _object(run["repository"], ("full_name",), "invalid_repository")
    require(run["repository"]["full_name"] == REPOSITORY
            and run["workflow_id"] == WORKFLOW_ID and run["head_branch"] == BRANCH,
            "repository_workflow_branch_mismatch")
    require(run["id"] == run_id and run["run_attempt"] == attempt
            and run["head_sha"] == build_sha, "execution_identity_mismatch")
    _terminal(run)

    jobs, reports = snapshot["jobs"], snapshot["reports"]
    require(isinstance(jobs, list) and len(jobs) <= len(slots), "invalid_jobs")
    require(isinstance(reports, list) and len(reports) <= len(slots), "invalid_reports")
    by_api, job_ids = {}, set()
    for job in jobs:
        _object(job, ("id", "run_id", "run_attempt", "head_sha", "api", "name",
                      "status", "conclusion", "report_sha256", *shard_keys), "invalid_job")
        _positive_int(job["id"], "invalid_job_id")
        _identity(job, build_sha, run_id, attempt)
        api = job["api"]
        require(type(api) is str and api in APIS, "unsupported_api")
        key = slot(job)
        require(key not in by_api and job["id"] not in job_ids, "duplicate_job")
        suffix = f" 分片{job['shard_index']}" if sharded else ""
        require(job["name"] == "启动验证 API " + api + suffix, "job_api_mismatch")
        _terminal(job)
        _digest(job["report_sha256"])
        by_api[key] = job
        job_ids.add(job["id"])

    seen_apis, used_paths, rows = set(), set(), []
    total_bytes = 0
    inventories = {}
    coverage = {api: set() for api in APIS}

    def path_for(value):
        path = artifact_path(evidence_root, value)
        require(value not in used_paths, "reused_report_path")
        used_paths.add(value)
        return path

    def read_text_file(ref):
        nonlocal total_bytes
        _ref(ref)
        raw = bounded_read(path_for(ref["path"]), MAX_TEXT_BYTES)
        total_bytes += len(raw)
        require(total_bytes <= MAX_TOTAL_BYTES, "total_evidence_too_large")
        require(hashlib.sha256(raw).hexdigest() == ref["sha256"], "artifact_digest_mismatch")
        return raw

    for ref in reports:
        _object(ref, ("api", "job_id", "path", "sha256", *shard_keys), "invalid_report_reference")
        api = ref["api"]
        require(type(api) is str and api in APIS, "unsupported_api")
        _positive_int(ref["job_id"], "invalid_job_id")
        _digest(ref["sha256"])
        key = slot(ref)
        require(key in by_api and ref["job_id"] == by_api[key]["id"], "report_job_mismatch")
        require(key not in seen_apis, "duplicate_api_report")
        require(ref["sha256"] == by_api[key]["report_sha256"], "job_report_digest_mismatch")
        report, report_digest = load_json(path_for(ref["path"]))
        # load_json is capped at 2 MiB; account its maximum conservatively.
        total_bytes += 2 * 1024 * 1024
        require(total_bytes <= MAX_TOTAL_BYTES, "total_evidence_too_large")
        require(report_digest == ref["sha256"], "report_digest_mismatch")
        _object(report, ("schema_version", "run_id", "run_attempt", "head_sha", "job_id",
                         "api", "exit_code", "expected_tests", "instrumentation", "logcat",
                         *shard_keys, *(("inventory",) if sharded else ())),
                "invalid_api_report")
        require(report["schema_version"] == snapshot["schema_version"]
                and type(report["schema_version"]) is int, "unsupported_schema_version")
        require(slot(report) == key, "report_shard_mismatch")
        _identity(report, build_sha, run_id, attempt)
        _positive_int(report["job_id"], "invalid_job_id")
        require(report["job_id"] == ref["job_id"] and type(report["api"]) is str
                and report["api"] == api, "report_job_api_mismatch")
        require(type(report["exit_code"]) is int and report["exit_code"] == 0,
                "report_zero_exit_required")
        tests = _positive_int(report["expected_tests"], "invalid_expected_tests")
        require(tests <= 999999999, "invalid_expected_tests")
        passed = _instrumentation(read_text_file(report["instrumentation"]), tests)
        _text(read_text_file(report["logcat"]))
        if sharded:
            _ref(report["inventory"])
            inventory, inventory_digest = load_json(path_for(report["inventory"]["path"]))
            total_bytes += 2 * 1024 * 1024
            require(total_bytes <= MAX_TOTAL_BYTES, "total_evidence_too_large")
            require(inventory_digest == report["inventory"]["sha256"], "artifact_digest_mismatch")
            require(isinstance(inventory, dict) and set(inventory) == {
                "schema_version", "shard_index", "shard_count", "total_tests", "all_classes", "shards"}
                and type(inventory["schema_version"]) is int and inventory["schema_version"] == 1,
                "invalid_inventory")
            require(slot(dict(api=api, **{k: inventory[k] for k in shard_keys})) == key,
                    "inventory_shard_mismatch")
            classes, groups = inventory["all_classes"], inventory["shards"]
            require(isinstance(classes, dict) and classes and all(
                isinstance(name, str) and name and type(count) is int and count > 0
                for name, count in classes.items()), "invalid_inventory_classes")
            require(type(inventory["total_tests"]) is int and inventory["total_tests"] == sum(classes.values())
                    and isinstance(groups, list) and len(groups) == 4, "invalid_inventory")
            union = set()
            for index, group in enumerate(groups):
                require(isinstance(group, dict) and set(group) == {"index", "expected_tests", "classes"}
                        and type(group["index"]) is int and group["index"] == index,
                        "invalid_inventory_group")
                names = group["classes"]
                require(isinstance(names, list) and names and all(isinstance(n, str) and n in classes for n in names)
                        and len(set(names)) == len(names) and not union.intersection(names), "inventory_overlap")
                require(type(group["expected_tests"]) is int
                        and group["expected_tests"] == sum(classes[n] for n in names), "inventory_count_mismatch")
                union.update(names)
            require(union == set(classes), "inventory_coverage_mismatch")
            require(groups[key[1]]["expected_tests"] == tests, "inventory_count_mismatch")
            canonical = dict(all_classes=classes, shards=groups, total_tests=inventory["total_tests"])
            require(not inventories or canonical == next(iter(inventories.values())), "inventory_changed")
            inventories[key] = canonical
            coverage[api].update(groups[key[1]]["classes"])
        seen_apis.add(key)
        rows.append({"api": api, "job_id": ref["job_id"], "status": "passed",
                     "tests_passed": passed, **({"shard_index": key[1], "shard_count": 4} if sharded else {})})

    # A dangling job/report pair is invalid, whereas an absent matrix member is
    # valid but incomplete. Neither can produce a cloud-regression pass.
    require(seen_apis == set(by_api), "job_report_coverage_mismatch")
    missing = [api for api in APIS if any(key not in seen_apis for key in slots
               if (key[0] if sharded else key) == api)]
    if sharded:
        for api in APIS:
            if api not in missing:
                require(coverage[api] == set(next(iter(inventories.values()))["all_classes"]),
                        "inventory_coverage_mismatch")
    return {"schema_version": 1, "scope": "cloud_regression_internal_consistency",
            "build_sha": build_sha, "run_id": run_id, "attempt": attempt,
            "snapshot_sha256": actual_digest, "trusted_collection": False,
            "cloud_regression_satisfied": not missing, "business_satisfied": False,
            "acceptance_items_credited": 0, "missing_apis": missing,
            **({"missing_shards": [{"api": api, "shard_index": index}
                for api, index in slots if (api, index) not in seen_apis]} if sharded else {}),
            "jobs": sorted(rows, key=lambda row: (APIS.index(row["api"]), row.get("shard_index", 0)))}


def main(argv=None):
    parser = SafeParser(description=__doc__)
    parser.add_argument("--snapshot", required=True)
    parser.add_argument("--snapshot-sha256", required=True, help="Externally supplied SHA-256 pin")
    parser.add_argument("--build-sha", required=True, help="Expected full lowercase commit SHA")
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--attempt", required=True, type=int)
    parser.add_argument("--evidence-root", required=True)
    try:
        args = parser.parse_args(argv)
        report = verify(args.snapshot, args.snapshot_sha256, args.build_sha,
                        args.run_id, args.attempt, Path(args.evidence_root))
    except InvalidEvidence as error:
        print(json.dumps({"valid": False, "error": str(error),
                          "cloud_regression_satisfied": False, "business_satisfied": False,
                          "trusted_collection": False, "acceptance_items_credited": 0},
                         sort_keys=True))
        return 2
    except (OSError, ValueError, TypeError, RecursionError, OverflowError):
        print(json.dumps({"valid": False, "error": "unreadable_or_invalid_input",
                          "cloud_regression_satisfied": False, "business_satisfied": False,
                          "trusted_collection": False, "acceptance_items_credited": 0},
                         sort_keys=True))
        return 2
    print(json.dumps({"valid": True, **report}, ensure_ascii=True, sort_keys=True))
    return 0 if report["cloud_regression_satisfied"] else 1


if __name__ == "__main__":
    sys.exit(main())
