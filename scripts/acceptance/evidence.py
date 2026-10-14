#!/usr/bin/env python3
"""Offline, fail-closed acceptance evidence validator; never executes commands."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys

SCHEMA_VERSION = 1
KINDS = ("static_review", "platform_automation", "real_device", "performance")
STATUSES = ("not_run", "passed", "failed", "blocked")
MAX_JSON_BYTES = 2 * 1024 * 1024
MAX_ARTIFACT_BYTES = 32 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024
MAX_RECORDS = 2048
MAX_ARTIFACTS = 16
FULL_SHA = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")
ITEM_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}\Z")
ROLES = {
    "static_review": {"review_report"},
    "platform_automation": {"test_report"},
    "real_device": {"test_report", "device_capture"},
    "performance": {"performance_measurement"},
}


class InvalidEvidence(ValueError):
    """Only constant diagnostic codes may cross the reporting boundary."""


def require(condition, code):
    if not condition:
        raise InvalidEvidence(code)


def full_sha(value):
    require(isinstance(value, str) and FULL_SHA.fullmatch(value), "invalid_build_sha")
    return value


def _open_regular(path: Path):
    """Open every component without following links, including parent links.

    POSIX dir_fd + O_NOFOLLOW closes the check/open race. Platforms lacking
    these primitives fail closed rather than silently weaken containment.
    """
    require(hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY")
            and os.open in os.supports_dir_fd, "safe_io_unavailable")
    absolute = Path(os.path.abspath(path))
    parts = absolute.parts
    fd = os.open(parts[0], os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for component in parts[1:-1]:
            next_fd = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                              dir_fd=fd)
            os.close(fd)
            fd = next_fd
        result = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                         dir_fd=fd)
    finally:
        os.close(fd)
    try:
        require(stat.S_ISREG(os.fstat(result).st_mode), "not_regular_file")
    except BaseException:
        os.close(result)
        raise
    return result


def bounded_read(path: Path, limit: int):
    fd = _open_regular(path)
    with os.fdopen(fd, "rb") as stream:
        require(os.fstat(stream.fileno()).st_size <= limit, "file_too_large")
        data = stream.read(limit + 1)
        require(len(data) <= limit, "file_too_large")
        return data


def _unique_object(pairs):
    obj = {}
    for key, value in pairs:
        require(key not in obj, "duplicate_json_key")
        obj[key] = value
    return obj


def load_json(path):
    raw = bounded_read(Path(path), MAX_JSON_BYTES)
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(
                               InvalidEvidence("invalid_json_number")))
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        raise InvalidEvidence("invalid_json") from None
    return value, hashlib.sha256(raw).hexdigest()


def spec_items(spec, expected_count=81):
    """Accept the exact cases schema, or a standalone item array."""
    if isinstance(spec, dict):
        keys = [key for key in ("cases", "items", "criteria", "acceptance") if key in spec]
        require(len(keys) == 1, "unsupported_spec_shape")
        items = spec[keys[0]]
    else:
        items = spec
    require(isinstance(items, list) and len(items) == expected_count, "spec_count_mismatch")
    require(0 < expected_count <= MAX_RECORDS, "invalid_expected_count")
    ids = []
    for item in items:
        require(isinstance(item, dict), "invalid_spec_item")
        item_id = item.get("id")
        require(isinstance(item_id, str) and ITEM_ID.fullmatch(item_id), "invalid_item_id")
        require(item_id not in ids, "duplicate_item_id")
        ids.append(item_id)
    return ids


# Minimum categories taken from docs/refactor-input/acceptance.json methods.
# Unknown/changed methods fail closed; a policy may add but never remove a floor.
METHOD_KINDS = {
    "自动化故障注入/合成数据": ("platform_automation",),
    "UI自动化+存储核对": ("platform_automation",),
    "合成协议+Android真机": ("platform_automation", "real_device"),
    "Android真机端到端": ("real_device",),
    "真机UI+录像/帧记录": ("real_device",),
    "真机UI+帧记录": ("real_device",),
    "真机IME矩阵": ("real_device",),
    "真机UI自动化": ("platform_automation", "real_device"),
    "UI自动化+视觉核对": ("static_review", "platform_automation"),
    "合成记录+真机只读": ("platform_automation", "real_device"),
    "Android真机，非生产测试参数": ("real_device",),
    "Android故障注入": ("platform_automation",),
    "Android真机后台任务": ("real_device",),
    "静态组件检查+支持设备实机+合成迁移包":
        ("static_review", "platform_automation", "real_device"),
    "真机性能采样；候选门槛待S1设备基准冻结": ("performance",),
    "真机资源采样；候选门槛待基准冻结": ("performance",),
    "构建静态检查+Android并存/隔离实机": ("static_review", "real_device"),
    "源码/依赖/构建产物审查": ("static_review",),
    "Android权限矩阵": ("platform_automation", "real_device"),
}


def spec_floors(spec):
    if not isinstance(spec, dict) or "cases" not in spec:
        return {}
    require(spec.get("schema_version") == 1, "unsupported_spec_version")
    floors = {}
    for case in spec["cases"]:
        method = case.get("method")
        require(isinstance(method, str) and method in METHOD_KINDS, "unclassified_spec_method")
        floors[case["id"]] = METHOD_KINDS[method]
    return floors


def requirements(policy, ids, spec_digest):
    require(isinstance(policy, dict) and policy.get("schema_version") == SCHEMA_VERSION,
            "invalid_policy")
    require(policy.get("spec_sha256") == spec_digest, "policy_spec_mismatch")
    mapping = policy.get("requirements")
    require(isinstance(mapping, dict) and set(mapping) == set(ids), "policy_coverage_mismatch")
    for kinds in mapping.values():
        require(isinstance(kinds, list) and kinds and len(kinds) <= len(KINDS),
                "invalid_required_kinds")
        require(all(isinstance(k, str) and k in KINDS for k in kinds)
                and len(kinds) == len(set(kinds)), "invalid_required_kinds")
    return mapping


def artifact_path(root, value):
    require(isinstance(value, str) and 0 < len(value) <= 512
            and "\\" not in value and "\x00" not in value, "invalid_artifact_path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and all(p not in ("", ".", "..") for p in value.split("/"))
            and not re.match(r"^[A-Za-z]:", value), "invalid_artifact_path")
    return Path(root).joinpath(*path.parts)


def _text(value, limit=256):
    return isinstance(value, str) and 0 < len(value) <= limit and not any(
        ord(c) < 32 or ord(c) == 127 for c in value)


def _check_run(record, kind):
    require(_text(record.get("actual"), 4096), "actual_observation_required")
    command = record.get("command")
    require(isinstance(command, list) and 0 < len(command) <= 128
            and all(_text(arg, 2048) for arg in command), "invalid_command")
    require(type(record.get("exit_code")) is int and -255 <= record["exit_code"] <= 255,
            "invalid_exit_code")
    require(record.get("origin") == ("human_review" if kind == "static_review" else "execution"),
            "unsupported_origin")
    # A dispatch acknowledgement is not execution, regardless of claimed status.
    executable = command[0].replace("\\", "/").rsplit("/", 1)[-1].lower()
    require(executable not in {"dispatch", "echo", "printf", "true"}
            and not any(arg.lower() in {"dispatch", "model_self_report", "model_assertion"}
                        for arg in command), "non_acceptance_command")
    if kind in ("real_device", "performance"):
        environment = record.get("environment")
        require(isinstance(environment, dict) and environment.get("physical_device") is True
                and _text(environment.get("device_model"))
                and _text(environment.get("os_version")), "physical_device_required")
    if kind == "performance":
        measurement = record.get("measurement")
        require(isinstance(measurement, dict)
                and _text(measurement.get("metric")) and _text(measurement.get("unit"))
                and _text(measurement.get("threshold"))
                and _text(measurement.get("observed")), "measurement_required")


def verify(spec, spec_digest, ledger, policy, build_sha, evidence_root, expected_count=81):
    """Return a whitelist-only report; untrusted titles/paths/commands never appear."""
    full_sha(build_sha)
    require(isinstance(spec_digest, str) and DIGEST.fullmatch(spec_digest), "invalid_spec_digest")
    ids = spec_items(spec, expected_count)
    needed = requirements(policy, ids, spec_digest)
    for item_id, floor in spec_floors(spec).items():
        require(set(floor) <= set(needed[item_id]), "policy_weakens_spec")
    require(isinstance(ledger, dict) and ledger.get("schema_version") == SCHEMA_VERSION,
            "invalid_ledger")
    require(ledger.get("spec_sha256") == spec_digest, "ledger_spec_mismatch")
    require(ledger.get("build_sha") == build_sha, "ledger_build_mismatch")
    records = ledger.get("records")
    require(isinstance(records, list) and len(records) <= MAX_RECORDS, "invalid_records")
    by_item = {item_id: {} for item_id in ids}
    total_bytes = 0
    for record in records:
        require(isinstance(record, dict), "invalid_record")
        item_id, kind = record.get("item_id"), record.get("kind")
        require(isinstance(item_id, str) and item_id in by_item, "unknown_item")
        require(isinstance(kind, str) and kind in KINDS, "invalid_kind")
        require(kind not in by_item[item_id], "duplicate_record")
        status = record.get("status")
        require(isinstance(status, str) and status in STATUSES, "invalid_status")
        full_sha(record.get("build_sha"))
        # Never reinterpret an old result as a new-build result, even when not_run.
        require(record["build_sha"] == build_sha, "record_build_mismatch")
        reasons = []
        if status in ("not_run", "blocked"):
            require(record.get("command") is None and record.get("exit_code") is None
                    and record.get("artifacts", []) == [], "unrun_record_has_execution")
            if status == "blocked":
                require(_text(record.get("blocker"), 2048), "blocker_required")
        else:
            _check_run(record, kind)
            require(status != "passed" or record["exit_code"] == 0, "pass_nonzero_exit")
            require(status != "failed" or _text(record.get("failure"), 2048), "failure_required")
            artifacts = record.get("artifacts")
            require(isinstance(artifacts, list) and 0 < len(artifacts) <= MAX_ARTIFACTS,
                    "artifacts_required")
            roles, paths = set(), set()
            for artifact in artifacts:
                require(isinstance(artifact, dict), "invalid_artifact")
                role = artifact.get("role")
                require(isinstance(role, str) and role in ROLES[kind], "unsupported_artifact_role")
                digest = artifact.get("sha256")
                require(isinstance(digest, str) and DIGEST.fullmatch(digest), "invalid_artifact_digest")
                path = artifact_path(evidence_root, artifact.get("path"))
                require(str(path) not in paths, "duplicate_artifact")
                paths.add(str(path))
                raw = bounded_read(path, min(MAX_ARTIFACT_BYTES, MAX_TOTAL_BYTES - total_bytes))
                total_bytes += len(raw)
                require(hashlib.sha256(raw).hexdigest() == digest, "artifact_digest_mismatch")
                require(len(raw) > 0, "empty_artifact")
                roles.add(role)
            if kind == "real_device":
                require(roles == ROLES[kind], "device_evidence_incomplete")
        if status != "passed":
            reasons.append(status)
        by_item[item_id][kind] = (status, reasons)

    rows = []
    counts = {status: 0 for status in STATUSES}
    unmet = []
    for index, item_id in enumerate(ids, 1):
        entries = by_item[item_id]
        states = [entries.get(k, ("not_run", ["missing_record"]))[0] for k in needed[item_id]]
        # Failures in supplementary categories must not disappear from the summary.
        all_states = states + [v[0] for k, v in entries.items() if k not in needed[item_id]]
        status = ("failed" if "failed" in all_states else "blocked" if "blocked" in all_states
                  else "not_run" if "not_run" in states else "passed")
        counts[status] += 1
        kind_rows = []
        for kind in KINDS:
            if kind not in needed[item_id] and kind not in entries:
                continue
            state, reasons = entries.get(kind, ("not_run", ["missing_record"]))
            kind_rows.append({"kind": kind, "required": kind in needed[item_id], "status": state})
            if state != "passed" and (kind in needed[item_id] or state in ("failed", "blocked")):
                unmet.append({"item_index": index, "kind": kind, "reasons": reasons})
        rows.append({"item_index": index, "status": status, "kinds": kind_rows})
    return {"schema_version": SCHEMA_VERSION, "build_sha": build_sha,
            "spec_sha256": spec_digest, "satisfied": counts["passed"] == len(ids),
            "counts": counts, "items": rows, "unmet": unmet}


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default errors may echo a secret-bearing argument or path.
        raise InvalidEvidence("invalid_cli_arguments")


def main(argv=None):
    parser = SafeParser(description=__doc__)
    parser.add_argument("--spec", required=True, help="Acceptance JSON input (default count: 81)")
    parser.add_argument("--ledger", required=True, help="Evidence ledger JSON")
    parser.add_argument("--policy", required=True, help="Reviewed per-item evidence requirements JSON")
    parser.add_argument("--build-sha", required=True, help="Exact full 40/64-character lowercase SHA")
    parser.add_argument("--evidence-root", required=True, help="Root containing relative evidence paths")
    parser.add_argument("--expected-count", type=int, default=81, help="Explicit count for other specs")
    try:
        args = parser.parse_args(argv)
        spec, digest = load_json(args.spec)
        ledger, _ = load_json(args.ledger)
        policy, _ = load_json(args.policy)
        report = verify(spec, digest, ledger, policy, args.build_sha,
                        args.evidence_root, args.expected_count)
    except InvalidEvidence as error:
        print(json.dumps({"valid": False, "error": str(error)}, sort_keys=True))
        return 2
    except (OSError, ValueError, TypeError, RecursionError, OverflowError):
        # Never echo file names, contents, environment, traceback, or exceptions.
        print('{"valid": false, "error": "unreadable_or_invalid_input"}')
        return 2
    print(json.dumps({"valid": True, **report}, ensure_ascii=True, sort_keys=True))
    return 0 if report["satisfied"] else 1


if __name__ == "__main__":
    sys.exit(main())
