"""Synthetic offline fixtures only: no device, network, build, or business AC claim."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / "scripts" / "acceptance" / "performance.py"
EVIDENCE_SPEC = importlib.util.spec_from_file_location("performance_evidence", MODULE.with_name("evidence.py"))
evidence = importlib.util.module_from_spec(EVIDENCE_SPEC)
EVIDENCE_SPEC.loader.exec_module(evidence)
SPEC = importlib.util.spec_from_file_location("acceptance_performance", MODULE)
performance = importlib.util.module_from_spec(SPEC)
with patch.dict(sys.modules, {"evidence": evidence}):
    SPEC.loader.exec_module(performance)

SHA = "a" * 40
REFERENCE_SHA = "b" * 64
DATASET = "c" * 64
PIN = "d" * 64


@unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd,
                     "safe POSIX file operations unavailable")
class PerformanceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.environment = {"physical_device": True, "device_type": "physical",
                            "device_model": "synthetic-model", "os_version": "synthetic-os",
                            "refresh_rate_hz": 60, "build_type": "release"}
        self.baseline = {"schema_version": 1, "baseline_version": "fixture-v1", "frozen": True,
                         "build_sha": REFERENCE_SHA, "environment": copy.deepcopy(self.environment),
                         "dataset_sha256": DATASET, "rules": [
                             {"item_id": "AC-072", "metric": "cold_start", "unit": "ms",
                              "statistic": "p95", "direction": "lte", "threshold": 19,
                              "min_samples": 20}]}
        raw = b"Synthetic capture fixture, not real collection or acceptance evidence.\n"
        (self.root / "capture.txt").write_bytes(raw)
        self.record = {"item_id": "AC-072", "metric": "cold_start", "unit": "ms",
                       "samples": list(range(1, 21)),
                       "artifact": {"path": "capture.txt", "sha256": hashlib.sha256(raw).hexdigest()}}
        self.measurement = {"schema_version": 1, "build_sha": SHA, "baseline_sha256": PIN,
                            "baseline_version": "fixture-v1", "environment": copy.deepcopy(self.environment),
                            "dataset_sha256": DATASET, "records": [self.record]}

    def verify(self):
        return performance.verify(self.measurement, SHA, self.root, self.baseline, PIN, PIN)

    def reject(self, code=None):
        with self.assertRaises(evidence.InvalidEvidence) as caught:
            self.verify()
        if code:
            self.assertEqual(str(caught.exception), code)

    def test_equality_boundary_exact_p95_and_honest_scope(self):
        result = self.verify()
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["results"][0]["observed"], 19)
        self.assertTrue(result["comparison_satisfied"])
        self.assertFalse(result["business_acceptance"])
        self.assertFalse(result["collection_authenticated"])
        self.baseline["rules"][0]["threshold"] = math.nextafter(19.0, -math.inf)
        self.assertEqual(self.verify()["status"], "failed")

    def test_nearest_rank_is_not_interpolated(self):
        for samples, expected in [([7], 7), (list(range(1, 20)), 19),
                                  (list(range(1, 21)), 19), (list(range(1, 22)), 20),
                                  (list(range(1, 101)), 95), ([3, 1, 3, 2], 3)]:
            with self.subTest(n=len(samples)):
                self.assertEqual(performance.nearest_rank_p95(samples), expected)
        with self.assertRaises(evidence.InvalidEvidence):
            performance.nearest_rank_p95([])

    def test_min_max_gte_and_negative_finite_values(self):
        rule = self.baseline["rules"][0]
        self.record["samples"] = [-3.5, -1, 0]
        rule.update(min_samples=3, statistic="min", direction="gte", threshold=-3.5)
        self.assertEqual(self.verify()["status"], "passed")
        rule["threshold"] = -3.4
        self.assertEqual(self.verify()["status"], "failed")
        rule.update(statistic="max", direction="lte", threshold=0)
        self.assertEqual(self.verify()["status"], "passed")
        self.record["samples"] = [10 ** 400]
        rule.update(min_samples=1, threshold=10 ** 400)
        self.assertEqual(self.verify()["status"], "passed")

    def test_strict_numbers_and_minimum_types(self):
        for value in (True, False, None, "19", float("nan"), float("inf"), -float("inf")):
            for field in ("sample", "threshold", "refresh"):
                with self.subTest(value=repr(value), field=field):
                    saved = copy.deepcopy((self.measurement, self.baseline))
                    if field == "sample":
                        self.record["samples"][0] = value
                    elif field == "threshold":
                        self.baseline["rules"][0]["threshold"] = value
                    else:
                        self.measurement["environment"]["refresh_rate_hz"] = value
                    self.reject()
                    self.measurement, self.baseline = saved
                    self.record = self.measurement["records"][0]
        for value in (True, 20.0, 0, -1, performance.MAX_SAMPLES + 1):
            self.baseline["rules"][0]["min_samples"] = value
            self.reject("invalid_min_samples")

    def test_insufficient_empty_and_unfrozen_are_blocked(self):
        for samples in ([], list(range(19))):
            self.record["samples"] = samples
            result = self.verify()
            self.assertEqual(result["status"], "blocked")
            self.assertEqual(result["results"][0]["reason"], "insufficient_samples")
        self.baseline["frozen"] = False
        self.assertEqual(self.verify()["results"][0]["reason"], "baseline_not_frozen")
        self.record["samples"] = [False]
        self.reject("invalid_sample")  # malformed must not be hidden behind blocked
        self.record["samples"] = []
        self.baseline["frozen"] = 0
        self.reject("invalid_freeze_state")

    def test_no_baseline_never_creates_threshold(self):
        report = performance.verify(self.measurement, SHA, self.root)
        self.assertEqual(report["status"], "blocked")
        self.assertEqual(report["reason"], "baseline_missing")
        self.assertEqual(report["results"], [])
        with self.assertRaises(evidence.InvalidEvidence):
            performance.verify(self.measurement, SHA, self.root, baseline_pin=PIN)

    def test_pin_required_external_exact_bytes(self):
        for actual, pin, code in ((PIN, None, "external_baseline_pin_required"),
                                  (PIN, "D" * 64, "external_baseline_pin_required"),
                                  ("e" * 64, PIN, "baseline_pin_mismatch")):
            with self.assertRaisesRegex(evidence.InvalidEvidence, "^" + code + "$"):
                performance.verify(self.measurement, SHA, self.root, self.baseline, actual, pin)
        self.measurement["baseline_sha256"] = "e" * 64
        self.reject("measurement_baseline_mismatch")
        self.measurement["baseline_sha256"] = PIN
        self.measurement["baseline_version"] = "another-version"
        self.reject("measurement_baseline_version_mismatch")

    def test_full_sha_and_schema_bool_rejected(self):
        for target in (self.baseline, self.measurement):
            old = target["build_sha"]
            for sha in ("abc123", "A" * 40, True, None):
                target["build_sha"] = sha
                self.reject("invalid_build_sha")
            target["build_sha"] = old
            target["schema_version"] = True
            self.reject()
            target["schema_version"] = 1
        self.measurement["build_sha"] = REFERENCE_SHA
        self.reject("measurement_build_mismatch")

    def test_environment_dataset_metric_and_unit_ownership(self):
        for field, value, code in (("device_model", "different-model", "environment_mismatch"),
                                   ("os_version", "different-os", "environment_mismatch"),
                                   ("refresh_rate_hz", 120, "environment_mismatch"),
                                   ("device_type", "emulator", "physical_device_required"),
                                   ("device_type", "host", "physical_device_required"),
                                   ("physical_device", 1, "physical_device_required"),
                                   ("build_type", "debug", "release_build_required")):
            old = self.measurement["environment"][field]
            self.measurement["environment"][field] = value
            self.reject(code)
            self.measurement["environment"][field] = old
        self.measurement["dataset_sha256"] = "e" * 64
        self.reject("dataset_mismatch")
        self.measurement["dataset_sha256"] = DATASET
        for field, value, code in (("item_id", "AC-073", "metric_coverage_mismatch"),
                                   ("metric", "other", "metric_coverage_mismatch"),
                                   ("unit", "s", "unit_mismatch"),
                                   ("item_id", "AC-071", "invalid_item_id")):
            old = self.record[field]
            self.record[field] = value
            self.reject(code)
            self.record[field] = old

    def test_duplicate_missing_extra_rules_and_unknown_aggregate(self):
        self.measurement["records"].append(copy.deepcopy(self.record))
        self.reject("duplicate_measurement")
        self.measurement["records"].pop()
        self.baseline["rules"].append(copy.deepcopy(self.baseline["rules"][0]))
        self.reject("duplicate_rule")
        self.baseline["rules"].pop()
        self.baseline["rules"][0]["statistic"] = "average"
        self.reject("invalid_statistic")
        self.baseline["rules"][0]["statistic"] = "p95"
        self.record["observed"] = 1
        self.reject("invalid_measurement_record")

    def test_multiple_items_and_failure_precedes_blocked(self):
        second = copy.deepcopy(self.baseline["rules"][0])
        second.update(item_id="AC-073", metric="memory", threshold=18)
        self.baseline["rules"].append(second)
        record = copy.deepcopy(self.record)
        record.update(item_id="AC-073", metric="memory")
        self.measurement["records"].append(record)
        self.record["samples"] = []
        report = self.verify()
        self.assertEqual(report["status"], "failed")
        self.assertEqual(report["counts"], {"passed": 0, "blocked": 1, "failed": 1})

    def test_path_traversal_absolute_windows_and_symlinks(self):
        for name in ("../capture.txt", "/capture.txt", "./capture.txt", "a//b", "a/../b",
                     "C:/capture.txt", "a\\b", "a\x00b"):
            self.record["artifact"]["path"] = name
            self.reject("invalid_artifact_path")
        (self.root / "link.txt").symlink_to(self.root / "capture.txt")
        self.record["artifact"]["path"] = "link.txt"
        with self.assertRaises((OSError, evidence.InvalidEvidence)):
            self.verify()
        (self.root / "linked-dir").symlink_to(self.root, target_is_directory=True)
        self.record["artifact"]["path"] = "linked-dir/capture.txt"
        with self.assertRaises((OSError, evidence.InvalidEvidence)):
            self.verify()
        (self.root / "directory").mkdir()
        self.record["artifact"]["path"] = "directory"
        with self.assertRaises((OSError, evidence.InvalidEvidence)):
            self.verify()

    def test_artifact_nonempty_digest_and_size(self):
        (self.root / "capture.txt").write_bytes(b"")
        self.record["artifact"]["sha256"] = hashlib.sha256(b"").hexdigest()
        self.reject("empty_artifact")
        (self.root / "capture.txt").write_bytes(b"tampered")
        self.reject("artifact_digest_mismatch")
        with patch.object(performance, "MAX_ARTIFACT_BYTES", 1):
            self.reject("file_too_large")

    def cli(self, extra=None, include_baseline=True):
        baseline_raw = json.dumps(self.baseline).encode()
        (self.root / "baseline.json").write_bytes(baseline_raw)
        pin = hashlib.sha256(baseline_raw).hexdigest()
        self.measurement["baseline_sha256"] = pin
        (self.root / "measurement.json").write_text(json.dumps(self.measurement), encoding="utf-8")
        args = ["--measurement", str(self.root / "measurement.json"), "--build-sha", SHA,
                "--evidence-root", str(self.root)]
        if include_baseline:
            args += ["--baseline", str(self.root / "baseline.json"), "--baseline-sha256", pin]
        args += extra or []
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            status = performance.main(args)
        return status, output.getvalue()

    def test_cli_exit_codes_and_safe_summary(self):
        code, output = self.cli()
        self.assertEqual(code, 0)
        report = json.loads(output)
        self.assertFalse(report["business_acceptance"])
        self.assertFalse(report["collection_authenticated"])
        for private in (str(self.root), "capture.txt", "synthetic-model", "cold_start", "fixture-v1"):
            self.assertNotIn(private, output)
        self.baseline["rules"][0]["threshold"] = 18
        code, output = self.cli()
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(output)["status"], "failed")
        code, output = self.cli(include_baseline=False)
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(output)["status"], "blocked")
        code, output = self.cli(["--secret-token=must-not-echo"])
        self.assertEqual(code, 2)
        self.assertNotIn("must-not-echo", output)
        self.measurement["environment"]["device_type"] = "host"
        code, output = self.cli()
        self.assertEqual(code, 2)
        self.assertEqual(json.loads(output)["status"], "invalid")

    def test_cli_hash_is_of_bytes_not_reserialized_json(self):
        code, _ = self.cli(["--baseline-sha256", PIN])
        self.assertEqual(code, 2)

    def test_bounded_samples_and_total_budget(self):
        with patch.object(performance, "MAX_SAMPLES", 19):
            self.reject("invalid_samples")
        with patch.object(performance, "MAX_TOTAL_SAMPLES", 19):
            self.reject("too_many_samples")
        with patch.object(performance, "MAX_TOTAL_BYTES", 1):
            self.reject("file_too_large")

    def test_cli_unreadable_and_null_baseline_do_not_echo_input(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = performance.main(["--measurement", str(self.root / "secret-file-name"),
                                     "--build-sha", SHA, "--evidence-root", str(self.root)])
        self.assertEqual(code, 2)
        self.assertNotIn("secret-file-name", output.getvalue())
        self.assertNotIn(str(self.root), output.getvalue())
        self.cli()  # Write otherwise valid measurement and baseline fixtures.
        (self.root / "baseline.json").write_text("null", encoding="utf-8")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = performance.main(["--measurement", str(self.root / "measurement.json"),
                                     "--baseline", str(self.root / "baseline.json"),
                                     "--baseline-sha256", PIN, "--build-sha", SHA,
                                     "--evidence-root", str(self.root)])
        self.assertEqual(code, 2)
        self.assertEqual(json.loads(output.getvalue())["error"], "invalid_baseline")

    def test_shared_safe_json_rejects_duplicate_keys_and_nonfinite_constants(self):
        path = self.root / "bad.json"
        for raw in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}', b'{"x":-Infinity}'):
            path.write_bytes(raw)
            with self.assertRaises(evidence.InvalidEvidence):
                evidence.load_json(path)
        path.write_bytes(b'{"x":1e999}')
        value, _ = evidence.load_json(path)
        with self.assertRaises(evidence.InvalidEvidence):
            performance.number(value["x"])


if __name__ == "__main__":
    unittest.main()
