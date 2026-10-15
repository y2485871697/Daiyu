"""Synthetic offline wiring tests; not project business acceptance evidence."""
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = ROOT / "scripts" / "acceptance"

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

evidence = load("recovery_evidence_api", MODULES / "evidence.py")
with patch.dict(sys.modules, {"evidence": evidence}):
    template = load("recovery_template_api", MODULES / "template.py")
with patch.dict(sys.modules, {"evidence": evidence, "template": template}):
    suite = load("recovery_suite_api", MODULES / "recovery_suite.py")

SHA = "a" * 40
RUN = 100
ATTEMPT = 2
SELECTOR = suite.P + "testControllerCallbacksKeepNewDraftsAndOtherConversation"
SYNTHETIC_ASSERTION = (b"package app.daiyu.android\nclass PlatformTest: Fixture {\n"
                       b"    fun testControllerCallbacksKeepNewDraftsAndOtherConversation() {}\n}\n")


def transcript(selector=SELECTOR, code="0"):
    cls, method = selector.split("#")
    frame = f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: numtests=1\n"
    return (frame + "INSTRUMENTATION_STATUS_CODE: 1\n" + frame +
            "INSTRUMENTATION_STATUS_CODE: " + code + "\n" +
            ("OK (1 test)\n" if code == "0" else "FAILURES!!!\n") +
            "INSTRUMENTATION_CODE: -1\n").encode()


@unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd,
                     "existing acceptance API requires safe POSIX reads")
class RecoverySuiteTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.spec, self.spec_sha = evidence.load_json(ROOT / "docs/refactor-input/acceptance.json")
        self.features, self.feature_sha = evidence.load_json(ROOT / "docs/refactor-input/features.json")
        self.run = {"id": RUN, "run_attempt": ATTEMPT, "head_sha": SHA,
                    "repository": {"full_name": suite.REPOSITORY}, "workflow_id": suite.WORKFLOW_ID,
                    "head_branch": "main", "event": "workflow_dispatch", "status": "completed", "conclusion": "success"}
        self.jobs = {"total_count": 3, "jobs": [
            self.job(1, "启动验证 API 26"), self.job(2, "启动验证 API 37.2"),
            self.job(3, "单测、检查与开发包")]}
        self.bundle = {"schema_version": 1, "source_sha": SHA, "run_id": RUN, "attempt": ATTEMPT,
                       "spec_sha256": self.spec_sha, "features_sha256": self.feature_sha,
                       "run": self.put("run.json", self.run), "jobs": self.put("jobs.json", self.jobs), "sources": []}

    def job(self, number, name):
        return {"id": number, "name": name, "run_id": RUN, "run_attempt": ATTEMPT,
                "head_sha": SHA, "status": "completed", "conclusion": "success",
                "started_at": "2026-01-01T00:00:00Z", "completed_at": "2026-01-01T00:10:00Z"}

    def put(self, name, value):
        raw = value if isinstance(value, bytes) else json.dumps(value, sort_keys=True).encode()
        (self.root / name).write_bytes(raw)
        return {"path": name, "sha256": suite.digest(raw)}

    def add_source(self, kind="instrumentation", api="26", selector=SELECTOR, result="passed", exit_code=0,
                   assertion=False):
        number = 1 if api == "26" else 2 if api == "37.2" else 3
        prefix = kind + (api or "")
        if kind == "instrumentation":
            raw = transcript(selector, "0" if result == "passed" else "-1" if result == "failed" else "-3")
            command = ["adb", "shell", "am", "instrument", "-w", "app.daiyu.android.test/android.test.InstrumentationTestRunner"]
        elif kind == "python":
            cls, method = selector.split("#")
            raw = f"{method} ({cls}.{method}) ... ok\n\nRan 1 test in 0.001s\n\nOK\n".encode()
            command = ["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"]
        else:
            raw = None
            command = ["./gradlew", "--no-daemon", ":core-domain:test", ":app:lintDebug"]
        receipt = {"source_sha": SHA, "run_id": RUN, "attempt": ATTEMPT, "job_id": number,
                   "origin": "execution", "type": kind, "api": api, "exit_code": exit_code,
                   "job_name": self.jobs["jobs"][number-1]["name"],
                   "command": command, "expected_tests": [selector]}
        if kind == "instrumentation":
            receipt.update(physical_device=False, sdk_int=26 if api == "26" else 37)
        assertion_ref = self.put(prefix+"-assertion.kt", SYNTHETIC_ASSERTION) if assertion else None
        if assertion:
            receipt["compiled_assertion_sha256"] = assertion_ref["sha256"]
        source = {"type": kind, "api": api, "job_id": number,
                  "receipt": self.put(prefix+"-receipt.json", receipt),
                  "xml": [self.put(prefix+".xml", suite.to_xml({selector: result}).encode())],
                  "raw": self.put(prefix+".txt", raw) if raw else None,
                  "assertion_source": assertion_ref}
        refs = [source["receipt"], *source["xml"]] + ([source["raw"]] if raw else []) + ([assertion_ref] if assertion else [])
        source["members"] = {ref["path"]: ref["path"] for ref in refs}
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for ref in refs:
                archive.writestr(ref["path"], (self.root / ref["path"]).read_bytes())
        source["archive"] = self.put(prefix+".zip", stream.getvalue())
        name = "代鱼启动证据-API"+api if kind == "instrumentation" else "代鱼测试报告" if kind == "domain" else "代鱼业务Python证据"
        source["artifact"] = self.put(prefix+"-artifact.json", {
            "id": number, "name": name, "expired": False, "digest": "sha256:"+source["archive"]["sha256"],
            "created_at": "2026-01-01T00:05:00Z",
            "workflow_run": {"id": RUN, "head_sha": SHA, "head_branch": "main"}})
        self.bundle["sources"].append(source)
        return source

    def collect(self):
        return suite.collect(self.spec, self.spec_sha, self.features, self.feature_sha,
                             self.bundle, suite.Files(self.root), SHA, RUN, ATTEMPT)

    def mutate_json(self, source, key, update):
        ref = source[key]
        value = json.loads((self.root / ref["path"]).read_bytes())
        update(value)
        source[key] = self.put(ref["path"], value)

    def rebind_archive(self, source):
        """Rebuild a synthetic archive after intentional fixture changes."""
        refs = [source["receipt"], *source["xml"]]
        if source["raw"] is not None:
            refs.append(source["raw"])
        if source["assertion_source"] is not None:
            refs.append(source["assertion_source"])
        source["members"] = {ref["path"]: ref["path"] for ref in refs}
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for ref in refs:
                archive.writestr(ref["path"], (self.root / ref["path"]).read_bytes())
        source["archive"] = self.put(source["archive"]["path"], stream.getvalue())
        self.mutate_json(source, "artifact", lambda a: a.update(digest="sha256:"+source["archive"]["sha256"]))

    def reject(self, code):
        with self.assertRaisesRegex(evidence.InvalidEvidence, "^"+code+"$"):
            self.collect()

    def test_mapping_cannot_be_rebound(self):
        self.spec["cases"][2]["feature_id"] = "F31"
        self.reject("mapping_cannot_be_rebound")
        with self.assertRaises(TypeError):
            suite.CASE_FEATURES["AC-003"] = "F31"
        with self.assertRaises(TypeError):
            suite.SUPPORT["AC-003"] = ()

    def test_exact_81_missing_stays_not_run(self):
        output = self.collect()
        self.assertEqual(len(output["mapping"]), 81)
        self.assertEqual(output["acceptance"]["counts"]["not_run"], 81)
        self.assertEqual(output["acceptance"]["counts"]["passed"], 0)

    def test_retained_self_developed_scenarios_are_explicit_gaps(self):
        output = self.collect()
        rows = {r["item_id"]: r for r in output["mapping"]}
        scenarios = [s for row in rows.values() for s in row["retained_scenarios"]]
        self.assertGreaterEqual(len(scenarios), 20)
        self.assertTrue(all(s["status"] == "not_run" for s in scenarios))
        self.assertFalse(output["retained_scenarios_satisfied"])
        self.assertFalse(output["business_satisfied"])
        self.assertEqual(len(rows), 81)
        for item in ("AC-001", "AC-003", "AC-004", "AC-005", "AC-006", "AC-010", "AC-028",
                     "AC-035", "AC-038", "AC-048", "AC-053", "AC-063", "AC-070"):
            self.assertTrue(rows[item]["retained_scenarios"])

    def test_success_is_not_class_presence_or_typecheck(self):
        self.add_source(assertion=True)
        self.add_source(api="37.2", assertion=True)
        output = self.collect()
        self.assertEqual(output["acceptance"]["counts"]["passed"], 0)
        self.assertEqual(output["mapping"][2]["status"], "not_run")
        self.bundle["sources"][0]["type"] = "host_typecheck"
        self.reject("unsupported_source_type")

    def test_reviewed_source_digest_is_not_trusted_compiled_provenance(self):
        self.add_source(assertion=True)
        with patch.object(suite, "DRAFT_ASSERTION_SHA256", suite.digest(SYNTHETIC_ASSERTION)):
            self.assertEqual(self.collect()["acceptance"]["counts"]["passed"], 0)
            self.add_source(api="37.2", assertion=True)
            output = self.collect()
        self.assertEqual(output["acceptance"]["counts"]["passed"], 0)
        self.assertEqual(output["acceptance"]["counts"]["not_run"], 81)
        self.assertFalse(output["business_satisfied"])
        row = output["mapping"][2]
        self.assertEqual(row["status"], "not_run")
        self.assertEqual(row["coverage"], "partial")
        self.assertEqual(row["gap"], "trusted_compiled_provenance_required")
        self.assertTrue(all(s["status"] == "passed" for s in row["support"]))

    def test_partial_selector_links_second_xml_without_granting_pass(self):
        unrelated = "Fixture#unrelated"
        expected_reports = []
        for api in suite.APIS:
            source = self.add_source(api=api, assertion=True)
            first = self.put("first-"+api+".xml", suite.to_xml({unrelated: "passed"}).encode())
            second = source["xml"][0]
            source["xml"] = [first, second]
            self.mutate_json(source, "receipt", lambda r: r.update(expected_tests=[unrelated, SELECTOR]))
            footer = b"OK (1 test)\nINSTRUMENTATION_CODE: -1\n"
            first_frames = transcript(unrelated).removesuffix(footer).replace(b"numtests=1", b"numtests=2")
            second_frames = transcript().removesuffix(footer).replace(b"numtests=1", b"numtests=2").replace(b"current=1", b"current=2")
            source["raw"] = self.put(source["raw"]["path"], first_frames+second_frames+
                                     b"OK (2 tests)\nINSTRUMENTATION_CODE: -1\n")
            self.rebind_archive(source)
            expected_reports.append(dict(second, role="test_report"))
        with patch.object(suite, "DRAFT_ASSERTION_SHA256", suite.digest(SYNTHETIC_ASSERTION)):
            output = self.collect()
        record = next(r for r in output["ledger"]["records"]
                      if r["item_id"] == "AC-003" and r["kind"] == "platform_automation")
        self.assertEqual(record["status"], "not_run")
        self.assertEqual(record["artifacts"], [])
        for ref in expected_reports:
            self.assertEqual(suite.junit((self.root / ref["path"]).read_bytes()), {SELECTOR: "passed"})
        for source, ref in zip(self.bundle["sources"], expected_reports):
            observed = suite.source_results(suite.Files(self.root), source, self.run,
                                            self.jobs["jobs"], SHA, ATTEMPT)
            self.assertEqual(observed["test_refs"][SELECTOR], {k: ref[k] for k in ("path", "sha256")})
        self.assertEqual(len(output["mapping"]), 81)
        self.assertEqual(output["acceptance"]["counts"]["passed"], 0)
        self.assertEqual(output["acceptance"]["counts"]["not_run"], 81)
        self.assertFalse(output["acceptance"]["satisfied"])
        self.assertFalse(output["business_satisfied"])

    def test_partial_domain_and_python_never_business_pass(self):
        self.add_source(kind="domain", api=None, selector=suite.D+"processDeathMarksInflightUnknownWithoutOpeningModelOrExecutingTool")
        self.add_source(kind="python", api=None, selector="test_recovery_suite.RecoverySuiteTests#test_mapping_cannot_be_rebound")
        output = self.collect()
        self.assertEqual(output["acceptance"]["counts"]["passed"], 0)
        self.assertEqual(output["mapping"][29]["support"][0]["status"], "passed")
        self.assertEqual(output["mapping"][74]["support"][0]["status"], "passed")

    def test_nonzero_and_assertion_failure_block_pass(self):
        self.add_source(exit_code=7, assertion=True)
        self.add_source(api="37.2", assertion=True)
        with patch.object(suite, "DRAFT_ASSERTION_SHA256", suite.digest(SYNTHETIC_ASSERTION)):
            output = self.collect()
            self.assertEqual(output["mapping"][2]["status"], "not_run")
            self.assertEqual(output["mapping"][2]["support"][0]["status"], "failed")
            self.assertEqual(output["acceptance"]["counts"]["passed"], 0)
        self.bundle["sources"].clear()
        self.add_source(result="failed", assertion=True)
        output = self.collect()
        self.assertEqual(output["mapping"][2]["status"], "not_run")
        self.assertEqual(output["mapping"][2]["support"][0]["status"], "failed")

    def test_boolean_exit_dispatch_and_wrong_api_rejected(self):
        source = self.add_source()
        self.mutate_json(source, "receipt", lambda r: r.update(exit_code=True))
        self.reject("invalid_execution_exit")
        self.mutate_json(source, "receipt", lambda r: r.update(exit_code=0, command=["dispatch"]))
        self.reject("wrong_instrumentation_command")
        source["api"] = "37"
        self.reject("invalid_api")

    def test_exact_run_attempt_sha_repository_workflow(self):
        for key, value in (("id", 99), ("run_attempt", 1), ("head_sha", "b"*40),
                           ("head_branch", "feature"), ("workflow_id", 1)):
            with self.subTest(key=key):
                bad = dict(self.run, **{key: value})
                self.bundle["run"] = self.put("run.json", bad)
                self.reject("actions_run_mismatch")
        self.bundle["run"] = self.put("run.json", {**self.run, "repository": {"full_name": "other/repo"}})
        self.reject("actions_run_mismatch")

    def test_job_and_receipt_revision_rebinding(self):
        source = self.add_source()
        self.mutate_json(source, "receipt", lambda r: r.update(source_sha="b"*40))
        self.reject("execution_identity_mismatch")
        self.mutate_json(source, "receipt", lambda r: r.update(source_sha=SHA, attempt=1))
        self.reject("execution_identity_mismatch")

    def test_archive_digest_membership_and_retry_age(self):
        source = self.add_source()
        self.mutate_json(source, "artifact", lambda a: a.update(digest="sha256:"+"b"*64))
        self.reject("artifact_metadata_mismatch")
        self.mutate_json(source, "artifact", lambda a: a.update(digest="sha256:"+source["archive"]["sha256"], created_at="2025-12-31T23:00:00Z"))
        self.reject("artifact_attempt_mismatch")
        self.mutate_json(source, "artifact", lambda a: a.update(created_at="2026-01-01T00:05:00Z"))
        self.mutate_json(source, "receipt", lambda r: r.update(extra="changed after download"))
        self.reject("archive_member_mismatch")

    def test_inventory_and_xml_are_not_cherry_pickable(self):
        source = self.add_source()
        self.mutate_json(source, "receipt", lambda r: r.update(expected_tests=[SELECTOR, "Fixture#omitted"]))
        self.reject("test_inventory_mismatch")
        self.mutate_json(source, "receipt", lambda r: r.update(expected_tests=[SELECTOR]))
        source["xml"] = [self.put("changed.xml", suite.to_xml({"Fixture#different": "passed"}).encode())]
        self.reject("test_inventory_mismatch")

    def test_status_transcript_not_just_ok(self):
        bad = [b"OK (1 test)\nINSTRUMENTATION_CODE: -1\n", transcript().replace(b"INSTRUMENTATION_CODE: -1", b"INSTRUMENTATION_CODE: 0"),
               transcript().replace(b"numtests=1", b"numtests=2"), transcript()+transcript(),
               transcript().replace(b"OK (1 test)", b"OK (99 tests)"), transcript().replace(b"INSTRUMENTATION_STATUS_CODE: 0", b"INSTRUMENTATION_STATUS_CODE: 1")]
        for raw in bad:
            with self.subTest(raw=raw[:30]), self.assertRaises(evidence.InvalidEvidence):
                suite.instrumentation(raw)
        self.assertEqual(suite.instrumentation(transcript()), {SELECTOR: "passed"})
        self.assertEqual(suite.junit(suite.to_xml(suite.instrumentation(transcript())).encode()), {SELECTOR: "passed"})

    def test_instrumentation_terminal_position_rejected_by_parser_and_collect(self):
        terminal = b"INSTRUMENTATION_CODE: -1\n"
        body = transcript().removesuffix(terminal)
        start_code = b"INSTRUMENTATION_STATUS_CODE: 1\n"
        finish_code = b"INSTRUMENTATION_STATUS_CODE: 0\n"
        cases = [
            ("before_any_test", terminal+body, "instrumentation_terminal_before_finish"),
            ("while_active", body.replace(start_code, start_code+terminal),
             "instrumentation_terminal_before_finish"),
            ("unfinished_frame", body.replace(finish_code, terminal+finish_code),
             "instrumentation_terminal_before_finish"),
            ("before_inventory_finished", body.replace(b"numtests=1", b"numtests=2")+terminal,
             "instrumentation_terminal_before_finish"),
            ("later_status", transcript()+b"INSTRUMENTATION_STATUS: class=Fixture\n",
             "instrumentation_after_terminal"),
            ("later_status_code", transcript()+start_code, "instrumentation_after_terminal"),
            ("later_test", transcript()+transcript("Fixture#later").removesuffix(terminal),
             "instrumentation_after_terminal"),
            ("duplicate_terminal", transcript()+terminal, "instrumentation_not_terminal"),
        ]
        source = self.add_source()
        for name, raw, code in cases:
            with self.subTest(case=name):
                with self.assertRaisesRegex(evidence.InvalidEvidence, "^"+code+"$"):
                    suite.instrumentation(raw)
                source["raw"] = self.put(source["raw"]["path"], raw)
                self.rebind_archive(source)
                self.reject(code)
        # Harmless trailing output is not a new test frame.
        self.assertEqual(suite.instrumentation(transcript()+b"collector finished\n"), {SELECTOR: "passed"})

    def test_prefixed_instrumentation_markers_rejected_by_parser_and_collect(self):
        source = self.add_source()
        for prefix in (b" ", b"\t", b"I/runner: "):
            for marker in (b"INSTRUMENTATION_STATUS_CODE: -2",
                           b"INSTRUMENTATION_CODE: 0",
                           b"INSTRUMENTATION_STATUS: class=Fixture",
                           b"INSTRUMENTATION_RESULT: stream=conflicting"):
                with self.subTest(prefix=prefix, marker=marker):
                    raw = transcript() + prefix + marker + b"\n"
                    with self.assertRaisesRegex(evidence.InvalidEvidence,
                                                "^unsupported_instrumentation_line$"):
                        suite.instrumentation(raw)
                    source["raw"] = self.put(source["raw"]["path"], raw)
                    self.rebind_archive(source)
                    self.reject("unsupported_instrumentation_line")

    def test_prefixed_terminal_before_valid_terminal_rejected(self):
        terminal = b"INSTRUMENTATION_CODE: -1\n"
        raw = transcript().removesuffix(terminal) + b" INSTRUMENTATION_CODE: 0\n" + terminal
        with self.assertRaisesRegex(evidence.InvalidEvidence, "^unsupported_instrumentation_line$"):
            suite.instrumentation(raw)

    def test_multiple_instrumentation_markers_on_one_line_rejected(self):
        raw = transcript() + b"INSTRUMENTATION_RESULT: stream=INSTRUMENTATION_CODE: 0\n"
        with self.assertRaisesRegex(evidence.InvalidEvidence, "^unsupported_instrumentation_line$"):
            suite.instrumentation(raw)

    def test_convert_rejects_prefixed_instrumentation_failure(self):
        ref = self.put("prefixed-failure.txt", transcript()+b" INSTRUMENTATION_STATUS_CODE: -2\n")
        with contextlib.redirect_stdout(io.StringIO()) as output:
            code = suite.main(["convert", "--type", "instrumentation", "--raw",
                               str(self.root / ref["path"])])
        self.assertEqual(code, 2)
        self.assertEqual(json.loads(output.getvalue())["error"], "unsupported_instrumentation_line")

    def test_junit_counter_empty_duplicate_skip_and_doctype(self):
        good = suite.to_xml({"Fixture#one": "passed"}).encode()
        for raw in (b'<testsuite tests="0" failures="0" errors="0" skipped="0"/>',
                    good.replace(b'tests="1"', b'tests="2"'),
                    good.replace(b'</testsuite>', b'<testcase classname="Fixture" name="one"/></testsuite>'),
                    b'<!DOCTYPE testsuite [<!ENTITY fake "x">]>'+good):
            with self.assertRaises(evidence.InvalidEvidence):
                suite.junit(raw)
        self.assertEqual(suite.junit(suite.to_xml({"Fixture#one": "not_run"}).encode()), {"Fixture#one": "not_run"})

    def test_approved_test_class_inventory_cannot_be_cherry_picked(self):
        source = self.add_source(assertion=True)
        ref = source["assertion_source"]
        omitted = SYNTHETIC_ASSERTION + b"    fun testAnotherRequiredAssertion() {}\n"
        source["assertion_source"] = self.put(ref["path"], omitted)
        self.mutate_json(source, "receipt", lambda r: r.update(compiled_assertion_sha256=suite.digest(omitted)))
        self.reject("assertion_inventory_incomplete")

    def test_manifest_cannot_supply_mutable_pass_mapping(self):
        self.bundle["mapping"] = {"AC-001": "passed"}
        self.reject("invalid_bundle")

    def test_path_symlink_tamper_and_cli_pin(self):
        source = self.add_source()
        source["receipt"]["path"] = "../outside"
        self.reject("invalid_artifact_path")
        source["receipt"]["path"] = "instrumentation26-receipt.json"
        path = self.root / source["receipt"]["path"]
        path.write_bytes(b"tampered")
        self.reject("file_digest_mismatch")
        path.unlink();path.symlink_to(self.root / "run.json")
        with self.assertRaises((OSError, evidence.InvalidEvidence)):
            self.collect()
        with contextlib.redirect_stdout(io.StringIO()) as output:
            code = suite.main(["mapping", "--spec-sha256", "0"*64, "--features-sha256", self.feature_sha,
                               "--spec", str(ROOT / "docs/refactor-input/acceptance.json"),
                               "--features", str(ROOT / "docs/refactor-input/features.json")])
        self.assertEqual(code, 2)
        self.assertEqual(json.loads(output.getvalue())["error"], "input_pin_mismatch")

    def test_junit_unknown_aborted_and_retry_outcomes_fail_closed(self):
        good = suite.to_xml({"Fixture#one": "passed"}).encode()
        cases = [good.replace(b" />", b"><"+tag+b" /></testcase>")
                 for tag in (b"aborted", b"retry", b"flakyFailure", b"rerunFailure", b"unknown")]
        cases += [good.replace(b'name="one"', b'name="one" '+attr)
                  for attr in (b'result="aborted"', b'outcome="unknown"', b'retry="1"', b'status="aborted"')]
        cases += [good.replace(b'tests="1"', b'tests="1" aborted="1"'),
                  good.replace(b" />", b"><system-out><retry /></system-out></testcase>")]
        source = self.add_source(selector="Fixture#one")
        for raw in cases:
            with self.subTest(raw=raw):
                self.assertNotEqual(raw, good)
                with self.assertRaises(evidence.InvalidEvidence):
                    suite.junit(raw)
                source["xml"] = [self.put("unknown-outcome.xml", raw)]
                self.rebind_archive(source)
                with self.assertRaises(evidence.InvalidEvidence):
                    self.collect()

    def test_junit_only_strict_utf8_no_dtd_or_entities(self):
        good = suite.to_xml({"Fixture#one": "passed"})
        declared = '<?xml version="1.0" encoding="UTF-8"?>'+good
        self.assertEqual(suite.junit(declared.encode()), {"Fixture#one": "passed"})
        cases = [b"\xff"+good.encode(),
                 ('<?xml version="1.0" encoding="ISO-8859-1"?>'+good).encode(),
                 ('<!DOCTYPE testsuite [<!ENTITY value "expanded">]>'+good).encode(),
                 ('<!ENTITY value "expanded">'+good).encode()]
        for encoding in ("utf-16", "utf-16-le", "utf-16-be", "utf-32", "utf-32-le", "utf-32-be"):
            cases.extend(text.encode(encoding) for text in (good, '<!DOCTYPE testsuite [<!ENTITY x "x">]>'+good))
        for raw in cases:
            with self.subTest(prefix=raw[:12]), self.assertRaises(evidence.InvalidEvidence):
                suite.junit(raw)

    def test_python_terminal_counts_failure_skip_and_order(self):
        passed = b"test_one (Fixture.test_one) ... ok\n\nRan 1 test in 0.001s\n\nOK\n"
        skipped = passed.replace(b"... ok", b"... skipped 'reason'").replace(b"\nOK\n", b"\nOK (skipped=1)\n")
        failed = passed.replace(b"... ok", b"... FAIL").replace(b"\nOK\n", b"\nFAILED (failures=1)\n")
        error = failed.replace(b"... FAIL", b"... ERROR").replace(b"failures=1", b"errors=1")
        for raw, status in ((passed, "passed"), (skipped, "not_run"), (failed, "failed"), (error, "failed")):
            self.assertEqual(suite.python_log(raw), {"Fixture#test_one": status})
        mixed = (b"test_one (Fixture.test_one) ... FAIL\ntest_two (Fixture.test_two) ... skipped 'reason'\n"
                 b"\nRan 2 tests in 0.001s\n\nFAILED (failures=1, skipped=1)\n")
        self.assertEqual(suite.python_log(mixed), {"Fixture#test_one": "failed", "Fixture#test_two": "not_run"})
        bad = [passed+b"FAILED (failures=1)\n", passed+b"OK\n", passed+b"test_two (Fixture.test_two) ... ok\n",
               passed.replace(b"\nOK\n", b"\nFAILED (failures=1)\n"),
               passed.replace(b"Ran 1 test", b"Ran 2 tests"), passed.replace(b"\nOK\n", b"\nOK (skipped=1)\n"),
               passed.replace(b"\nRan", b"\nOK\nRan"), passed.replace(b"\nOK\n", b"\n"),
               failed.replace(b"FAILED (failures=1)", b"OK"), skipped.replace(b"OK (skipped=1)", b"OK"),
               mixed.replace(b"failures=1, skipped=1", b"failures=1"),
               failed.replace(b"failures=1", b"failures=2"), error.replace(b"errors=1", b"failures=1"),
               passed.replace(b"... ok", b"... expected failure"),
               passed.replace(b"test_one (", b"test_other ("),
               passed.replace(b"\nOK", b"\nRan 1 test in 0.001s\nOK"),
               passed+b"untrusted trailing data\n",
               passed.replace(b"Ran 1 test", b"Ran 2 tests")+b"test_two (Fixture.test_two) ... ok\n",
               passed.replace(b"\nOK\n", b"\ntest_two (Fixture.test_two) ... ok\nOK\n").replace(b"Ran 1 test", b"Ran 2 tests")]
        for raw in bad:
            with self.subTest(raw=raw), self.assertRaises(evidence.InvalidEvidence):
                suite.python_log(raw)

    def test_python_invalid_summary_rejected_through_collect(self):
        source = self.add_source(kind="python", api=None, selector="Fixture#test_one")
        source["raw"] = self.put(source["raw"]["path"],
                                 b"test_one (Fixture.test_one) ... ok\nRan 1 test in 0.001s\nOK\nFAILED (failures=1)\n")
        self.rebind_archive(source)
        self.reject("python_not_terminal")

    def test_receipt_job_id_is_exact_integer_selected_job(self):
        source = self.add_source()
        for value in (None, True, False, "1", 1.0, 2, 999):
            with self.subTest(value=value):
                self.mutate_json(source, "receipt", lambda r: r.update(job_id=value))
                self.rebind_archive(source)
                self.reject("receipt_job_mismatch")
        self.mutate_json(source, "receipt", lambda r: r.pop("job_id"))
        self.rebind_archive(source)
        self.reject("receipt_job_mismatch")
        self.mutate_json(source, "receipt", lambda r: r.update(job_id=1))
        self.rebind_archive(source)
        self.assertTrue(self.collect()["valid"])

    def test_domain_argv_rejects_execution_suppression_and_extra_tasks(self):
        source = self.add_source(kind="domain", api=None, selector="Fixture#one")
        base = ["./gradlew", "--no-daemon", ":core-domain:test", ":app:lintDebug"]
        bad = [base+[flag] for flag in ("--dry-run", "-m", "--dry-run=true", ":other:test", "clean", "build", "--offline")]
        bad += [base+["-x", ":core-domain:test"], base+["--exclude-task=:core-domain:test"],
                base+["-I", "untrusted.gradle"], base+["--init-script=untrusted.gradle"],
                base+["-Dorg.gradle.init.script=untrusted.gradle"],
                ["./gradlew", "--dry-run", ":core-domain:test"],
                ["./gradlew", ":core-domain:test", ":core-domain:test"]]
        for command in bad:
            with self.subTest(command=command):
                self.mutate_json(source, "receipt", lambda r: r.update(command=command))
                self.rebind_archive(source)
                self.reject("wrong_domain_command")
        for command in (base, ["./gradlew", ":core-domain:test"], ["gradlew.bat", "--no-daemon", ":core-domain:test"]):
            self.mutate_json(source, "receipt", lambda r: r.update(command=command))
            self.rebind_archive(source)
            self.assertTrue(self.collect()["valid"])

    def test_cross_attempt_job_and_artifact_boundaries(self):
        source = self.add_source()
        bad_jobs = copy.deepcopy(self.jobs)
        bad_jobs["jobs"][0]["run_attempt"] = ATTEMPT-1
        self.bundle["jobs"] = self.put("jobs.json", bad_jobs)
        self.reject("job_identity_mismatch")
        self.bundle["jobs"] = self.put("jobs.json", self.jobs)
        for value, valid in (("2026-01-01T00:00:00Z", True), ("2026-01-01T00:10:00Z", True),
                             ("2025-12-31T23:59:59Z", False), ("2026-01-01T00:10:01Z", False)):
            self.mutate_json(source, "artifact", lambda a: a.update(created_at=value))
            if valid:
                self.assertTrue(self.collect()["valid"])
            else:
                self.reject("artifact_attempt_mismatch")

    def test_file_path_and_byte_budget_boundaries(self):
        for path in ("", "/absolute", "../escape", "a/../escape", "a//b", "./x", "a/", "C:/x", "a\\b", "x\x00y"):
            with self.subTest(path=path), self.assertRaises(evidence.InvalidEvidence):
                suite.Files(self.root).read({"path": path, "sha256": "0"*64})
        ref = self.put("bounded.txt", b"abcd")
        with patch.object(suite, "MAX_ARTIFACT_BYTES", 4), patch.object(suite, "MAX_TOTAL_BYTES", 4):
            files = suite.Files(self.root)
            self.assertEqual(files.read(ref), b"abcd")
            self.assertEqual(files.read(ref), b"abcd")  # cached refs do not spend budget twice
            other = self.put("over.txt", b"x")
            with self.assertRaisesRegex(evidence.InvalidEvidence, "^file_too_large$"):
                files.read(other)
        with patch.object(suite, "MAX_ARTIFACT_BYTES", 3):
            with self.assertRaisesRegex(evidence.InvalidEvidence, "^file_too_large$"):
                suite.Files(self.root).read(ref)
        directory = self.root / "linked-parent"
        directory.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises((OSError, evidence.InvalidEvidence)):
            suite.Files(self.root).read(dict(ref, path="linked-parent/bounded.txt"))

    def test_zip_unsafe_duplicate_symlink_and_size_boundaries(self):
        source = self.add_source()
        def archive_with(extra):
            self.rebind_archive(source)
            stream = io.BytesIO((self.root / source["archive"]["path"]).read_bytes())
            with zipfile.ZipFile(stream, "a") as archive:
                extra(archive)
            source["archive"] = self.put(source["archive"]["path"], stream.getvalue())
            self.mutate_json(source, "artifact", lambda a: a.update(digest="sha256:"+source["archive"]["sha256"]))
        for name in ("../escape", "/absolute", "a//b", "folder//", "C:/escape", "a\\b"):
            with self.subTest(name=name):
                archive_with(lambda a: a.writestr(name, b"x"))
                self.reject("invalid_artifact_path")
        info = zipfile.ZipInfo("linked")
        info.create_system = 3
        info.external_attr = 0o120777 << 16
        archive_with(lambda a: a.writestr(info, b"target"))
        self.reject("zip_symlink")
        with self.assertWarns(UserWarning):
            archive_with(lambda a: a.writestr(source["receipt"]["path"], b"duplicate"))
        self.reject("duplicate_zip_member")
        archive_with(lambda a: a.writestr("padding", b"x"*2048, compress_type=zipfile.ZIP_DEFLATED))
        files = suite.Files(self.root)
        for ref in [source["receipt"], *source["xml"], source["raw"], source["artifact"], source["archive"]]:
            files.read(ref)
        # Patch only after reading: exercise decompressed ZIP limits, not disk byte limits.
        with patch.object(suite, "MAX_ARTIFACT_BYTES", 2047):
            with self.assertRaisesRegex(evidence.InvalidEvidence, "^zip_too_large$"):
                suite.archive_members(files, source, self.run, self.jobs["jobs"][0])
        with patch.object(suite, "MAX_ARTIFACT_BYTES", 2048):
            suite.archive_members(files, source, self.run, self.jobs["jobs"][0])
        with zipfile.ZipFile(io.BytesIO(files.cache[source["archive"]["path"]])) as archive:
            total = sum(i.file_size for i in archive.infolist())
        with patch.object(suite, "MAX_TOTAL_BYTES", total-1):
            with self.assertRaisesRegex(evidence.InvalidEvidence, "^zip_too_large$"):
                suite.archive_members(files, source, self.run, self.jobs["jobs"][0])
        with patch.object(suite, "MAX_TOTAL_BYTES", total):
            suite.archive_members(files, source, self.run, self.jobs["jobs"][0])

    def test_zip_member_binding_missing_wrong_and_corrupt_archive(self):
        source = self.add_source()
        source["members"][source["receipt"]["path"]] = "missing.json"
        self.reject("unbound_archive_member")
        source["members"][source["receipt"]["path"]] = source["xml"][0]["path"]
        self.reject("archive_member_mismatch")
        self.rebind_archive(source)
        source["members"].pop(source["raw"]["path"])
        self.reject("incomplete_archive_binding")
        self.rebind_archive(source)
        source["archive"] = self.put(source["archive"]["path"], b"not a zip")
        self.mutate_json(source, "artifact", lambda a: a.update(digest="sha256:"+source["archive"]["sha256"]))
        with self.assertRaises(zipfile.BadZipFile):
            self.collect()

    def test_cli_mapping_verify_convert_and_all_pins(self):
        spec_args = ["--spec", str(ROOT / "docs/refactor-input/acceptance.json"),
                     "--features", str(ROOT / "docs/refactor-input/features.json"),
                     "--spec-sha256", self.spec_sha, "--features-sha256", self.feature_sha]
        def invoke(args):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                code = suite.main(args)
            return code, output.getvalue()
        code, raw = invoke(["mapping", *spec_args])
        self.assertEqual(code, 0)
        self.assertEqual(len(json.loads(raw)["mapping"]), 81)
        ref = self.put("bundle.json", self.bundle)
        args = ["verify", *spec_args, "--bundle", str(self.root / ref["path"]),
                "--bundle-sha256", ref["sha256"], "--evidence-root", str(self.root),
                "--build-sha", SHA, "--run-id", str(RUN), "--attempt", str(ATTEMPT)]
        code, raw = invoke(args)
        self.assertEqual(code, 1)
        self.assertFalse(json.loads(raw)["business_satisfied"])
        for flag, value, error in (("--spec-sha256", "0"*64, "input_pin_mismatch"),
                                    ("--features-sha256", "0"*64, "input_pin_mismatch"),
                                    ("--bundle-sha256", "0"*64, "input_pin_mismatch"),
                                    ("--bundle-sha256", "invalid", "invalid_pin"),
                                    ("--attempt", "1", "execution_identity_mismatch")):
            changed = list(args)
            changed[changed.index(flag)+1] = value
            code, raw = invoke(changed)
            self.assertEqual(code, 2)
            self.assertEqual(json.loads(raw)["error"], error)
        ref = self.put("convert.txt", transcript())
        code, raw = invoke(["convert", "--type", "instrumentation", "--raw", str(self.root / ref["path"])])
        self.assertEqual(code, 0)
        self.assertEqual(suite.junit(raw.encode()), {SELECTOR: "passed"})
        ref = self.put("invalid-python.txt", b"OK\n")
        code, raw = invoke(["convert", "--type", "python", "--raw", str(self.root / ref["path"])])
        self.assertEqual(code, 2)
        self.assertFalse(json.loads(raw)["valid"])
        code, raw = invoke(["verify"])
        self.assertEqual(code, 2)
        self.assertFalse(json.loads(raw)["valid"])

    def test_no_process_or_network_import(self):
        source = (MODULES / "recovery_suite.py").read_text()
        for forbidden in ("import subprocess", "import urllib", "import requests", "import socket", "os.system", "os.popen"):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
