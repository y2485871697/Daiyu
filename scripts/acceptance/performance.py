#!/usr/bin/env python3
"""Offline comparison of declared physical-device samples to an externally pinned baseline.

This does not collect samples, authenticate collection, or grant business acceptance.
"""
from __future__ import annotations

import hashlib
import json
import math
import sys

from evidence import (DIGEST, ITEM_ID, MAX_ARTIFACT_BYTES, MAX_RECORDS, MAX_TOTAL_BYTES,
                      InvalidEvidence, SafeParser, artifact_path, bounded_read,
                      full_sha, load_json, require)

SCHEMA_VERSION = 1
ITEMS = ("AC-072", "AC-073")
MAX_SAMPLES = 100000
MAX_TOTAL_SAMPLES = 200000
STATISTICS = ("p95", "min", "max")


def shape(value, keys, code):
    require(isinstance(value, dict) and set(value) == set(keys.split()), code)


def text(value):
    return (isinstance(value, str) and 0 < len(value) <= 256
            and not any(ord(c) < 32 or ord(c) == 127 for c in value))


def digest(value, code):
    require(isinstance(value, str) and DIGEST.fullmatch(value), code)
    return value


def number(value, code="invalid_number"):
    # bool is an int subclass. Arbitrarily large JSON integers remain finite;
    # avoid converting them to float (which can overflow or lose precision).
    require(type(value) is int or (type(value) is float and math.isfinite(value)), code)
    return value


def environment(value):
    shape(value, "physical_device device_type device_model os_version refresh_rate_hz build_type",
          "invalid_environment")
    require(value["physical_device"] is True and value["device_type"] == "physical",
            "physical_device_required")
    require(text(value["device_model"]) and text(value["os_version"]), "invalid_environment")
    require(value["build_type"] == "release", "release_build_required")
    require(number(value["refresh_rate_hz"], "invalid_refresh_rate") > 0,
            "invalid_refresh_rate")
    return value


def identity(record):
    require(isinstance(record, dict), "invalid_record")
    require(isinstance(record.get("item_id"), str) and record["item_id"] in ITEMS,
            "invalid_item_id")
    require(isinstance(record.get("metric"), str) and ITEM_ID.fullmatch(record["metric"]),
            "invalid_metric")
    require(text(record.get("unit")), "invalid_unit")
    return record["item_id"], record["metric"]


def nearest_rank_p95(samples):
    """Exact nearest rank: sorted_samples[ceil(95*n/100)-1], with no interpolation."""
    require(isinstance(samples, list) and 0 < len(samples) <= MAX_SAMPLES, "invalid_samples")
    for value in samples:
        number(value, "invalid_sample")
    return sorted(samples)[(95 * len(samples) + 99) // 100 - 1]


def _measurement(measurement, build_sha, evidence_root):
    shape(measurement, "schema_version build_sha baseline_sha256 baseline_version environment "
          "dataset_sha256 records", "invalid_measurement")
    require(type(measurement["schema_version"]) is int
            and measurement["schema_version"] == SCHEMA_VERSION, "invalid_measurement_version")
    full_sha(measurement["build_sha"])
    require(measurement["build_sha"] == build_sha, "measurement_build_mismatch")
    environment(measurement["environment"])
    digest(measurement["dataset_sha256"], "invalid_dataset_digest")
    if measurement["baseline_sha256"] is not None:
        digest(measurement["baseline_sha256"], "invalid_measurement_baseline_digest")
    require(measurement["baseline_version"] is None or text(measurement["baseline_version"]),
            "invalid_measurement_baseline_version")
    records = measurement["records"]
    require(isinstance(records, list) and 0 < len(records) <= MAX_RECORDS, "invalid_records")
    indexed, total_bytes, total_samples = {}, 0, 0
    for record in records:
        shape(record, "item_id metric unit samples artifact", "invalid_measurement_record")
        key = identity(record)
        require(key not in indexed, "duplicate_measurement")
        samples = record["samples"]
        require(isinstance(samples, list) and len(samples) <= MAX_SAMPLES, "invalid_samples")
        total_samples += len(samples)
        require(total_samples <= MAX_TOTAL_SAMPLES, "too_many_samples")
        for value in samples:
            number(value, "invalid_sample")
        artifact = record["artifact"]
        shape(artifact, "path sha256", "invalid_artifact")
        digest(artifact["sha256"], "invalid_artifact_digest")
        path = artifact_path(evidence_root, artifact["path"])
        raw = bounded_read(path, min(MAX_ARTIFACT_BYTES, MAX_TOTAL_BYTES - total_bytes))
        total_bytes += len(raw)
        require(len(raw) > 0, "empty_artifact")
        require(hashlib.sha256(raw).hexdigest() == artifact["sha256"],
                "artifact_digest_mismatch")
        indexed[key] = record
    return indexed


def _baseline(baseline):
    shape(baseline, "schema_version baseline_version frozen build_sha environment dataset_sha256 rules",
          "invalid_baseline")
    require(type(baseline["schema_version"]) is int
            and baseline["schema_version"] == SCHEMA_VERSION, "invalid_baseline_version")
    require(text(baseline["baseline_version"]), "invalid_baseline_version")
    require(type(baseline["frozen"]) is bool, "invalid_freeze_state")
    full_sha(baseline["build_sha"])
    environment(baseline["environment"])
    digest(baseline["dataset_sha256"], "invalid_dataset_digest")
    rules = baseline["rules"]
    require(isinstance(rules, list) and 0 < len(rules) <= MAX_RECORDS, "invalid_rules")
    indexed = {}
    for rule in rules:
        shape(rule, "item_id metric unit statistic direction threshold min_samples", "invalid_rule")
        key = identity(rule)
        require(key not in indexed, "duplicate_rule")
        require(isinstance(rule["statistic"], str) and rule["statistic"] in STATISTICS,
                "invalid_statistic")
        require(isinstance(rule["direction"], str) and rule["direction"] in ("lte", "gte"),
                "invalid_direction")
        number(rule["threshold"], "invalid_threshold")
        require(type(rule["min_samples"]) is int and 0 < rule["min_samples"] <= MAX_SAMPLES,
                "invalid_min_samples")
        indexed[key] = rule
    return indexed


def _report(status, build_sha, results, reason=None):
    report = {"schema_version": SCHEMA_VERSION, "valid": True, "status": status,
              "build_sha": build_sha, "comparison_satisfied": status == "passed",
              "collection_authenticated": False, "business_acceptance": False,
              "counts": {state: sum(row["status"] == state for row in results)
                         for state in ("passed", "failed", "blocked")}, "results": results}
    if reason:
        report["reason"] = reason
    return report


def verify(measurement, build_sha, evidence_root, baseline=None, baseline_digest=None,
           baseline_pin=None):
    """Validate all supplied data before reporting blocked/failed/passed.

    baseline_digest is the SHA-256 of the actual bytes returned by load_json;
    baseline_pin must come independently from the reviewer, not either input JSON.
    Invalid inputs raise InvalidEvidence; the CLI converts these to exit 2.
    """
    full_sha(build_sha)
    measured = _measurement(measurement, build_sha, evidence_root)
    if baseline is None:
        require(baseline_pin is None and baseline_digest is None, "pin_without_baseline")
        return _report("blocked", build_sha, [], "baseline_missing")
    digest(baseline_pin, "external_baseline_pin_required")
    digest(baseline_digest, "invalid_baseline_digest")
    require(baseline_digest == baseline_pin, "baseline_pin_mismatch")
    rules = _baseline(baseline)
    require(measurement["baseline_sha256"] == baseline_pin, "measurement_baseline_mismatch")
    require(measurement["baseline_version"] == baseline["baseline_version"],
            "measurement_baseline_version_mismatch")
    require(measurement["environment"] == baseline["environment"], "environment_mismatch")
    require(measurement["dataset_sha256"] == baseline["dataset_sha256"], "dataset_mismatch")
    require(set(measured) == set(rules), "metric_coverage_mismatch")
    # Validate every unit before allowing any blocked result to mask bad data.
    for key, rule in rules.items():
        require(measured[key]["unit"] == rule["unit"], "unit_mismatch")
    results = []
    for index, (key, rule) in enumerate(rules.items(), 1):
        samples = measured[key]["samples"]
        row = {"rule_index": index, "item_id": key[0], "sample_count": len(samples),
               "required_samples": rule["min_samples"]}
        if not baseline["frozen"]:
            row.update(status="blocked", reason="baseline_not_frozen")
        elif len(samples) < rule["min_samples"]:
            row.update(status="blocked", reason="insufficient_samples")
        else:
            observed = (nearest_rank_p95(samples) if rule["statistic"] == "p95"
                        else min(samples) if rule["statistic"] == "min" else max(samples))
            passed = (observed <= rule["threshold"] if rule["direction"] == "lte"
                      else observed >= rule["threshold"])
            row.update(status="passed" if passed else "failed", observed=observed,
                       reason="within_threshold" if passed else "threshold_exceeded")
        results.append(row)
    status = ("failed" if any(row["status"] == "failed" for row in results)
              else "blocked" if any(row["status"] == "blocked" for row in results) else "passed")
    return _report(status, build_sha, results)


def main(argv=None):
    parser = SafeParser(description=__doc__)
    parser.add_argument("--measurement", required=True, help="Raw-sample measurement JSON")
    parser.add_argument("--build-sha", required=True, help="Expected full candidate build SHA")
    parser.add_argument("--evidence-root", required=True, help="Root for relative capture artifacts")
    parser.add_argument("--baseline", help="Externally reviewed baseline JSON; absent means blocked")
    parser.add_argument("--baseline-sha256", help="Independently supplied baseline byte SHA-256 pin")
    try:
        args = parser.parse_args(argv)
        measurement, _ = load_json(args.measurement)
        baseline, actual_digest = load_json(args.baseline) if args.baseline is not None else (None, None)
        # An explicit JSON null is malformed, not an absent --baseline.
        require(args.baseline is None or baseline is not None, "invalid_baseline")
        report = verify(measurement, args.build_sha, args.evidence_root, baseline,
                        actual_digest, args.baseline_sha256)
    except InvalidEvidence as error:
        print(json.dumps({"valid": False, "status": "invalid", "error": str(error),
                          "collection_authenticated": False, "business_acceptance": False},
                         sort_keys=True))
        return 2
    except (OSError, ValueError, TypeError, RecursionError, OverflowError):
        print('{"valid": false, "status": "invalid", "error": "unreadable_or_invalid_input", '
              '"collection_authenticated": false, "business_acceptance": false}')
        return 2
    print(json.dumps(report, ensure_ascii=True, sort_keys=True))
    return 0 if report["comparison_satisfied"] else 1


if __name__ == "__main__":
    sys.exit(main())
