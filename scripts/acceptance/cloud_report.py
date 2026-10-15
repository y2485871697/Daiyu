#!/usr/bin/env python3
"""Validate a pinned local cloud-regression snapshot; no network or execution.

This proves internal consistency of supplied bytes, NOT trusted collection or
business acceptance. The normalized snapshot format is documented separately.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import sys

from evidence import (DIGEST, InvalidEvidence, SafeParser, artifact_path,
                      bounded_read, full_sha, load_json, require)

REPOSITORY = "y2485871697/Daiyu"
WORKFLOW_ID = 383377276
BRANCH = "main"
APIS = ("26", "37.2")
MAX_TEXT_BYTES = 4 * 1024 * 1024
# At most two report JSONs (2 MiB each) and four texts (4 MiB each).
# Snapshot JSON itself is bounded by evidence.load_json (2 MiB).
MAX_TOTAL_BYTES = 20 * 1024 * 1024
NEGATIVE = re.compile(
    r"\b(?:fail(?:ure|ures|ed)?|crash(?:ed|ing)?|fatal|exception|errors?|"
    r"abort(?:ed)?|timed[ -]?out|timeout|cancel(?:led|ed)?|skipped|ignored)\b"
    r"|\b[A-Za-z_$][\w$]*(?:Error|Exception)\b"
    r"|INSTRUMENTATION_(?:FAILED|ABORTED)|shortMsg\s*=", re.IGNORECASE)
OK = re.compile(r"OK \(([1-9][0-9]{0,8}) tests?\)\Z")


def _object(value, keys, code):
    require(isinstance(value, dict) and set(value) == set(keys), code)


def _positive_int(value, code):
    require(type(value) is int and 0 < value <= 2**63 - 1, code)
    return value


def _digest(value):
    require(isinstance(value, str) and DIGEST.fullmatch(value), "invalid_digest")
    return value


def _version(value):
    require(type(value) is int and value == 1, "unsupported_schema_version")


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
        text = raw.decode("utf-8").replace("\r\n", "\n")
    except UnicodeError:
        raise InvalidEvidence("invalid_report_encoding") from None
    require(text.strip() and all(c in "\n\t" or (ord(c) >= 32 and ord(c) != 127)
                                 for c in text), "invalid_report_text")
    require(not NEGATIVE.search(text), "negative_report_signal")
    return text


def _instrumentation(raw, expected_tests):
    """Inspect original am instrument -w text, not a derived passed flag.

    Negative tokens are deliberately conservative (even 'errors: 0' is not
    accepted). Optional Android protocol codes/counts must not contradict OK.
    No rewriting, stripping ANSI sequences, or merging repeated runs is done.
    """
    text = _text(raw)
    successes = []
    terminal_codes = []
    for line in text.split("\n"):
        # Protocol markers anywhere in a line must be parsed, never hidden by
        # indentation or a log prefix. Reject rather than normalize such input.
        require("INSTRUMENTATION_" not in line
                or (line.startswith("INSTRUMENTATION_") and line.count("INSTRUMENTATION_") == 1),
                "unsupported_instrumentation_line")
        match = OK.fullmatch(line)
        if match:
            successes.append(int(match[1]))
        elif re.search(r"\bOK\b", line):
            raise InvalidEvidence("ambiguous_instrumentation_ok")
        if line.startswith("INSTRUMENTATION_CODE:"):
            require(line == "INSTRUMENTATION_CODE: -1", "instrumentation_terminal_conflict")
            terminal_codes.append(line)
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            require(line in ("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 1"),
                    "instrumentation_status_conflict")
        elif line.startswith("INSTRUMENTATION_STATUS: numtests="):
            require(line == f"INSTRUMENTATION_STATUS: numtests={expected_tests}",
                    "instrumentation_count_conflict")
        elif line.startswith("INSTRUMENTATION_STATUS: current="):
            count = line.partition("=")[2]
            require(re.fullmatch(r"[1-9][0-9]{0,8}", count)
                    and int(count) <= expected_tests, "instrumentation_count_conflict")
        elif line.startswith("INSTRUMENTATION_RESULT:"):
            require(line.startswith("INSTRUMENTATION_RESULT: stream="),
                    "unsupported_instrumentation_result")
        elif line.startswith("INSTRUMENTATION_"):
            require(line.startswith(tuple("INSTRUMENTATION_STATUS: " + key + "="
                                          for key in ("id", "class", "test", "stream"))),
                    "unsupported_instrumentation_line")
    require(len(successes) == 1 and successes[0] == expected_tests,
            "instrumentation_unique_ok_required")
    require(len(terminal_codes) <= 1, "instrumentation_terminal_conflict")
    return successes[0]


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
    require(isinstance(jobs, list) and len(jobs) <= len(APIS), "invalid_jobs")
    require(isinstance(reports, list) and len(reports) <= len(APIS), "invalid_reports")
    by_api, job_ids = {}, set()
    for job in jobs:
        _object(job, ("id", "run_id", "run_attempt", "head_sha", "api", "name",
                      "status", "conclusion", "report_sha256"), "invalid_job")
        _positive_int(job["id"], "invalid_job_id")
        _identity(job, build_sha, run_id, attempt)
        api = job["api"]
        require(type(api) is str and api in APIS, "unsupported_api")
        require(api not in by_api and job["id"] not in job_ids, "duplicate_job")
        require(job["name"] == "启动验证 API " + api, "job_api_mismatch")
        _terminal(job)
        _digest(job["report_sha256"])
        by_api[api] = job
        job_ids.add(job["id"])

    seen_apis, used_paths, rows = set(), set(), []
    total_bytes = 0

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
        _object(ref, ("api", "job_id", "path", "sha256"), "invalid_report_reference")
        api = ref["api"]
        require(type(api) is str and api in APIS, "unsupported_api")
        _positive_int(ref["job_id"], "invalid_job_id")
        _digest(ref["sha256"])
        require(api in by_api and ref["job_id"] == by_api[api]["id"], "report_job_mismatch")
        require(api not in seen_apis, "duplicate_api_report")
        require(ref["sha256"] == by_api[api]["report_sha256"], "job_report_digest_mismatch")
        report, report_digest = load_json(path_for(ref["path"]))
        # load_json is capped at 2 MiB; account its maximum conservatively.
        total_bytes += 2 * 1024 * 1024
        require(total_bytes <= MAX_TOTAL_BYTES, "total_evidence_too_large")
        require(report_digest == ref["sha256"], "report_digest_mismatch")
        _object(report, ("schema_version", "run_id", "run_attempt", "head_sha", "job_id",
                         "api", "exit_code", "expected_tests", "instrumentation", "logcat"),
                "invalid_api_report")
        _version(report["schema_version"])
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
        seen_apis.add(api)
        rows.append({"api": api, "job_id": ref["job_id"], "status": "passed",
                     "tests_passed": passed})

    # A dangling job/report pair is invalid, whereas an absent matrix member is
    # valid but incomplete. Neither can produce a cloud-regression pass.
    require(seen_apis == set(by_api), "job_report_coverage_mismatch")
    missing = [api for api in APIS if api not in seen_apis]
    return {"schema_version": 1, "scope": "cloud_regression_internal_consistency",
            "build_sha": build_sha, "run_id": run_id, "attempt": attempt,
            "snapshot_sha256": actual_digest, "trusted_collection": False,
            "cloud_regression_satisfied": not missing, "business_satisfied": False,
            "acceptance_items_credited": 0, "missing_apis": missing,
            "jobs": sorted(rows, key=lambda row: APIS.index(row["api"]))}


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
