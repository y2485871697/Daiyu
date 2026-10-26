#!/usr/bin/env python3
"""Offline recovery evidence wiring. No execution, dispatch, build or download."""
from __future__ import annotations

from datetime import datetime
import hashlib
import io
import json
from pathlib import Path
import re
import sys
from types import MappingProxyType
import xml.etree.ElementTree as ET
import zipfile

from evidence import (InvalidEvidence, SafeParser, artifact_path, bounded_read,
                      DIGEST, full_sha, load_json, MAX_ARTIFACT_BYTES,
                      MAX_TOTAL_BYTES, require, spec_items, verify)
from template import make_template

REPOSITORY = "y2485871697/Daiyu"
WORKFLOW_ID = 397560064
APIS = ("26", "37.2")
# Contract identity is code, not caller-editable JSON. Order is AC-001..AC-081.
FEATURE_NUMBERS = (
    1,1,1,1,2,2,2,3,3,3,4,4,4,5,5,5,5,6,6,6,6,6,7,7,8,8,
    9,9,9,9,10,10,11,11,11,12,12,13,13,14,14,15,15,16,16,17,17,
    18,18,19,19,19,20,20,20,21,21,22,22,23,23,24,25,25,25,26,26,
    27,27,28,29,30,30,31,31,1,7,13,15,17,12,
)
CASE_FEATURES = MappingProxyType({f"AC-{i:03d}": f"F{n:02d}"
                                  for i, n in enumerate(FEATURE_NUMBERS, 1)})
P = "app.daiyu.android.PlatformTest#"
C = "app.daiyu.android.ControllerRuntimeWiringTest#"
R = "app.daiyu.android.storage.RuntimeStorageTest#"
D = "app.daiyu.android.domain.runtime.RunnerCheckpointTest#"
# Reviewed assertion-level support, never a class-presence wildcard. All are
# partial: offline source digests and collector self-reports do not establish
# trusted compiled provenance. Device/UI/performance coverage is not inferred.
SUPPORT = MappingProxyType({
    "AC-001": (("instrumentation", P+"testPersistenceRecoveryAndDraftIsolation"),),
    "AC-002": (("instrumentation", P+"testPersistenceRecoveryAndDraftIsolation"),),
    "AC-003": (("instrumentation", P+"testControllerCallbacksKeepNewDraftsAndOtherConversation"),),
    "AC-011": (("instrumentation", P+"testBatteryAllowlistAndRealBackend"),
               ("instrumentation", C+"testRealDispatcherReceivesWholeBatchCatalogueAndPersistsReadOnlyReceipts")),
    "AC-014": (("instrumentation", P+"testPaginationAndRecoveryAreStable"),),
    "AC-015": (("instrumentation", R+"testTerminalReceiptAndIndependentRecoveryAreIdempotentButSaveRemainsStrict"),),
    "AC-016": (("instrumentation", R+"testAtomicCommitRollsBackProjectionOnCheckpointInsertOrUpdateFailure"),
               ("domain", D+"recoveryRetriesMessageProjectionAfterFailedSave")),
    "AC-017": (("instrumentation", R+"testRestartRecoveryRetainsCompleteCheckpointAndNeverRequestsModelOrTool"),),
    "AC-023": (("instrumentation", C+"testDisabledConfigurationCannotAuthorizeEvenAnOtherwiseRealTool"),),
    "AC-024": (("instrumentation", P+"testEncryptedSettingsAndSseFrames"),),
    "AC-025": (("instrumentation", R+"testRevisionOwnerAndAllMessageIdentityConflictsAndExactReceiptRetry"),),
    "AC-026": (("instrumentation", C+"testStartupRecoveryUsesSharedRuntimeStoreAndNeverRequestsOrRetriesUnknownTools"),),
    "AC-030": (("domain", D+"processDeathMarksInflightUnknownWithoutOpeningModelOrExecutingTool"),
               ("instrumentation", C+"testStartupRecoveryUsesSharedRuntimeStoreAndNeverRequestsOrRetriesUnknownTools")),
    "AC-050": (("instrumentation", P+"testBackupStrictNestedSchemaProtocolAndAtomicCollisions"),),
    "AC-051": (("instrumentation", P+"testTextBackupAtomicImportAndReopen"),),
    # Validator negative tests are supporting evidence for source integrity, not
    # a business assertion of licensing or all dependency provenance.
    "AC-075": (("python", "test_recovery_suite.RecoverySuiteTests#test_mapping_cannot_be_rebound"),),
})
FULL_ASSERTIONS = frozenset()
# Historical reviewed input identity only, NOT trusted compiled provenance.
# Neither these bytes in an archive nor compiled_assertion_sha256 self-report
# binds the executed binary to source. No current source can grant full credit.
DRAFT_ASSERTION_SHA256 = "b099416bb1d584558c8b5754e687f1a999b8842aafa0010ec60dbb5ce58a2203"
# User-confirmed self-developed behavior: retain and test, not a license finding
# or proof of execution. These additive gaps do not rename the original 81 IDs.
RETAINED_SCENARIOS = MappingProxyType({
    "AC-001": ("conversation_id_isolation_and_restore",),
    "AC-004": ("conversation_search_and_sidebar", "chat_history_search_and_sidebar"),
    "AC-005": ("custom_request_body_roundtrip_and_request", "gpt_fast_mode_roundtrip_and_request",
               "provider_balance_entry_configuration_and_real_balance_not_usage"),
    "AC-006": ("assistant_configuration_snapshot_and_data", "title_model_configuration_requests_and_title_data"),
    "AC-003": ("send_button_behavior_and_draft_data",),
    "AC-010": ("stop_button_behavior_and_terminal_data",),
    "AC-014": ("conversation_id_stability_and_existing_data",),
    "AC-021": ("chat_history_search_navigation_and_sidebar",),
    "AC-022": ("vibration_entry_configuration_and_haptic_behavior",),
    "AC-027": ("error_reconnect_original_policy_and_receipts_no_automatic_replacement",),
    "AC-028": ("pause_button_behavior_and_resume_data",),
    "AC-035": ("usage_statistics_config_and_persisted_totals",),
    "AC-038": ("top_right_menu_interaction_mode_configuration",),
    "AC-040": ("auxiliary_visual_model_entry_configuration_and_observation_data",),
    "AC-042": ("virtual_screen_entry_configuration_delivery_and_release",),
    "AC-043": ("virtual_screen_failure_scene_and_retained_data",),
    "AC-074": ("application_icon_entry_resource_configuration_and_independent_identity",),
    "AC-079": ("virtual_screen_read_only_probe_does_not_claim_or_start",),
    "AC-048": ("chat_page_image_and_video_configuration_and_files", "open_file_with_daiyu_entry_and_session_scope"),
    "AC-049": ("chat_page_image_video_unknown_results_no_replay",),
    "AC-053": ("subagent_configuration_existing_tasks_and_data",),
    "AC-054": ("subagent_background_behavior_and_task_data",),
    "AC-055": ("subagent_progress_replacement_and_context",),
    "AC-063": ("tts_configuration_isolation_and_persistence",),
    "AC-064": ("tts_play_stop_resource_ownership",),
    "AC-065": ("tts_http_counts_and_failure_no_replay",),
    "AC-070": ("explicit_daiyu_file_open_system_entry",),
    "AC-078": ("top_right_menu_ask_cancel_behavior_and_data",),
})
TEST_ID = re.compile(r"[A-Za-z0-9_.$]+#[A-Za-z0-9_.$\[\] -]+\Z")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def pinned_json(path, expected):
    require(isinstance(expected, str) and DIGEST.fullmatch(expected), "invalid_pin")
    value, actual = load_json(path)
    require(actual == expected, "input_pin_mismatch")
    return value, actual


def mapping(spec, features):
    require(spec_items(spec) == list(CASE_FEATURES), "mapping_case_mismatch")
    require(isinstance(features, list) and [f.get("id") for f in features] ==
            [f"F{i:02d}" for i in range(1, 32)], "mapping_feature_mismatch")
    rows = []
    for case in spec["cases"]:
        item = case["id"]
        require(case.get("feature_id") == CASE_FEATURES[item], "mapping_cannot_be_rebound")
        rows.append({"item_id": item, "feature_id": CASE_FEATURES[item],
                     "coverage": "full_platform_assertion" if item in FULL_ASSERTIONS
                     else "partial" if item in SUPPORT else "missing_business_assertion",
                     "selectors": [{"source_type": k, "test_id": t}
                                   for k, t in SUPPORT.get(item, ())],
                     "retained_scenarios": [{"scenario_id": scenario, "status": "not_run",
                         "gap": "original_behavior_configuration_and_data_assertions_required"}
                         for scenario in RETAINED_SCENARIOS.get(item, ())]})
    return rows


def junit(raw):
    # Decode before inspecting markup: byte scans miss UTF-16/32 declarations.
    # Parse Unicode only, never let ElementTree auto-detect another encoding.
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeError:
        raise InvalidEvidence("invalid_xml_encoding") from None
    require("\x00" not in text, "invalid_xml_encoding")
    declaration = re.match(r'^\ufeff?<\?xml\s+[^?]*\?>', text)
    if declaration:
        encoding = re.search(r'''\bencoding\s*=\s*(["'])(.*?)\1''', declaration[0], re.I)
        require(encoding is None or encoding[2].lower() == "utf-8", "invalid_xml_encoding")
    require("<!DOCTYPE" not in text.upper() and "<!ENTITY" not in text.upper(), "unsafe_xml")
    try:
        root = ET.fromstring(text)
    except (ET.ParseError, ValueError):
        raise InvalidEvidence("invalid_xml") from None
    require(root.tag in ("testsuite", "testsuites"), "invalid_junit_root")
    suites = [root] if root.tag == "testsuite" else list(root)
    require(suites and all(s.tag == "testsuite" for s in suites), "invalid_junit_suites")
    results = {}
    for suite in [root, *suites]:
        require(set(suite.attrib) <= {"name", "tests", "failures", "errors", "skipped", "time",
                                      "timestamp", "hostname", "package", "id"}, "unsupported_suite_attribute")
    for suite in suites:
        tests = suite.findall("testcase")
        require(tests and len(tests) <= 10000, "empty_or_oversized_suite")
        counts = {"failures": 0, "errors": 0, "skipped": 0}
        for test in tests:
            key = test.get("classname", "") + "#" + test.get("name", "")
            require(TEST_ID.fullmatch(key) and key not in results, "duplicate_or_invalid_test")
            require(test.get("status") in (None, "run") and test.get("disabled") is None,
                    "unsupported_test_status")
            # Whitelist outcome metadata and direct children. Unknown vendor
            # aborted/retry/flaky markers must never become an implicit pass.
            require(set(test.attrib) <= {"classname", "name", "status", "time", "file", "line", "assertions"},
                    "unsupported_test_attribute")
            require(all(child.tag in ("failure", "error", "skipped", "system-out", "system-err", "properties")
                        for child in test), "unsupported_test_outcome")
            for child in test:
                if child.tag in ("system-out", "system-err"):
                    require(not len(child), "unsupported_test_outcome")
                elif child.tag == "properties":
                    require(all(p.tag == "property" and not len(p) for p in child), "unsupported_test_outcome")
            tags = {"failure": "failures", "error": "errors", "skipped": "skipped"}
            outcomes = [tags[child.tag] for child in test if child.tag in tags]
            require(len(outcomes) <= 1, "conflicting_test_outcomes")
            for tag in outcomes:
                counts[tag] += 1
            results[key] = ("failed" if any(t in outcomes for t in ("failures", "errors"))
                            else "not_run" if "skipped" in outcomes else "passed")
        for key, expected in {"tests": len(tests), **counts}.items():
            require(suite.get(key) == str(expected), "junit_counter_mismatch")
        require(len(suite.findall(".//testcase")) == len(tests), "nested_junit_tests")
        require(all(child.tag in ("testcase", "properties", "system-out", "system-err") for child in suite),
                "unexpected_suite_child")
        for child in suite:
            if child.tag in ("system-out", "system-err"):
                require(not len(child), "unexpected_suite_child")
            elif child.tag == "properties":
                require(all(p.tag == "property" and not len(p) for p in child), "unexpected_suite_child")
        for tag, counter in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
            require(len(suite.findall(".//"+tag)) == counts[counter], "unbound_junit_outcome")
    if root.tag == "testsuites":
        for key in ("tests", "failures", "errors", "skipped"):
            if root.get(key) is not None:
                require(root.get(key) == str(sum(int(s.get(key)) for s in suites)), "junit_counter_mismatch")
    return results


def instrumentation(raw):
    """Parse Android status frames, not OK-only text or source method counts."""
    text = raw.decode("utf-8")
    require(not any(x in text for x in ("INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED", "Process crashed")),
            "instrumentation_aborted")
    frame, active, results = {}, None, {}
    total = None
    terminal = False
    for line in text.splitlines():
        require("INSTRUMENTATION_" not in line
                or (line.startswith("INSTRUMENTATION_") and line.count("INSTRUMENTATION_") == 1),
                "unsupported_instrumentation_line")
        if line.startswith(("INSTRUMENTATION_STATUS:", "INSTRUMENTATION_STATUS_CODE:")):
            require(not terminal, "instrumentation_after_terminal")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if sep and key in ("class", "test", "current", "numtests"):
                require(key not in frame, "duplicate_status_field")
                frame[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.split(": ", 1)[1]
            require(set(frame) == {"class", "test", "current", "numtests"}, "incomplete_status_frame")
            key = frame["class"] + "#" + frame["test"]
            require(TEST_ID.fullmatch(key), "invalid_instrumentation_test")
            require(frame["numtests"].isdigit() and 0 < int(frame["numtests"]) <= 10000,
                    "invalid_instrumentation_count")
            total = int(frame["numtests"]) if total is None else total
            require(int(frame["numtests"]) == total and frame["current"] == str(len(results)+1),
                    "instrumentation_count_mismatch")
            if code == "1":
                require(active is None and key not in results, "duplicate_instrumentation_start")
                active = key
            else:
                require(active == key and code in ("0", "-1", "-2", "-3", "-4"),
                        "unmatched_instrumentation_finish")
                results[key] = "passed" if code == "0" else "not_run" if code in ("-3", "-4") else "failed"
                active = None
            frame = {}
        elif line.startswith("INSTRUMENTATION_CODE:"):
            require(not terminal and re.fullmatch(r"INSTRUMENTATION_CODE: -1\s*", line),
                    "instrumentation_not_terminal")
            require(active is None and not frame and total and len(results) == total,
                    "instrumentation_terminal_before_finish")
            terminal = True
    require(active is None and not frame and total and len(results) == total,
            "truncated_instrumentation")
    require(terminal, "instrumentation_not_terminal")
    if all(v == "passed" for v in results.values()):
        require(re.findall(r"OK \((\d+) tests?\)", text) == [str(total)] and "FAILURES!!!" not in text,
                "instrumentation_summary_mismatch")
    return results


def python_log(raw):
    text = raw.decode("utf-8")
    results, counts = {}, {"failures": 0, "errors": 0, "skipped": 0}
    ran = terminal = None
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = re.fullmatch(r"(test\S+) \(([A-Za-z0-9_.$]+)\.([A-Za-z0-9_]+)\) \.\.\. (ok|FAIL|ERROR|skipped .+)", line)
        if match:
            require(ran is None and terminal is None, "python_test_after_summary")
            require(match[1] == match[3], "invalid_python_test")
            key = match[2] + "#" + match[3]
            require(key not in results, "duplicate_python_test")
            outcome = match[4]
            results[key] = "passed" if outcome == "ok" else "not_run" if outcome.startswith("skipped ") else "failed"
            if outcome != "ok":
                counts["skipped" if outcome.startswith("skipped ") else "failures" if outcome == "FAIL" else "errors"] += 1
        elif re.match(r"^test\S+ .*\.\.\. ", line):
            raise InvalidEvidence("unsupported_python_outcome")
        elif line.startswith("Ran "):
            match = re.fullmatch(r"Ran (\d+) tests? in [0-9]+(?:\.[0-9]+)?s", line)
            require(ran is None and terminal is None and match and results
                    and match[1] == str(len(results)), "incomplete_python_log")
            ran = index
        elif line.startswith(("OK", "FAILED")):
            require(ran is not None and terminal is None, "python_not_terminal")
            summary = ", ".join(f"{key}={value}" for key, value in counts.items() if value)
            expected = ("FAILED ("+summary+")" if counts["failures"] or counts["errors"]
                        else "OK" + (" ("+summary+")" if summary else ""))
            require(line == expected, "python_summary_mismatch")
            terminal = index
    require(results and ran is not None, "incomplete_python_log")
    require(terminal is not None, "python_not_terminal")
    require(not any(line.strip() for line in lines[terminal+1:]), "python_after_terminal")
    return results


def to_xml(results):
    suite = ET.Element("testsuite", name="offline-transcript", tests=str(len(results)),
                       failures=str(sum(v == "failed" for v in results.values())), errors="0",
                       skipped=str(sum(v == "not_run" for v in results.values())))
    for key, status in sorted(results.items()):
        cls, name = key.split("#", 1)
        test = ET.SubElement(suite, "testcase", classname=cls, name=name)
        if status != "passed":
            ET.SubElement(test, "failure" if status == "failed" else "skipped")
    return ET.tostring(suite, encoding="unicode")


class Files:
    def __init__(self, root):
        self.root, self.total = Path(root), 0
        self.cache = {}

    def read(self, ref):
        require(isinstance(ref, dict) and set(ref) == {"path", "sha256"}, "invalid_file_ref")
        require(isinstance(ref["sha256"], str) and DIGEST.fullmatch(ref["sha256"]), "invalid_file_digest")
        key = ref["path"]
        path = artifact_path(self.root, key)
        if key not in self.cache:
            raw = bounded_read(path, min(MAX_ARTIFACT_BYTES, MAX_TOTAL_BYTES-self.total))
            require(raw, "empty_file")
            self.total += len(raw)
            self.cache[key] = raw
        raw = self.cache[key]
        require(digest(raw) == ref["sha256"], "file_digest_mismatch")
        return raw

    def json(self, ref):
        # Use the existing duplicate-key/NaN-safe JSON API, also its size bound.
        self.read(ref)
        value, sha = load_json(artifact_path(self.root, ref["path"]))
        require(sha == ref["sha256"], "file_digest_mismatch")
        return value


def exact_identity(obj, sha, run_id, attempt):
    require(obj.get("source_sha") == sha and type(obj.get("run_id")) is int
            and obj["run_id"] == run_id and type(obj.get("attempt")) is int
            and obj["attempt"] == attempt, "execution_identity_mismatch")


def timestamp(value):
    require(isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value),
            "invalid_actions_timestamp")
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        raise InvalidEvidence("invalid_actions_timestamp") from None


def archive_members(files, source, run, job):
    meta = files.json(source["artifact"])
    raw = files.read(source["archive"])
    name = ("代鱼启动证据-API"+source["api"] if source["type"] == "instrumentation"
            else "代鱼测试报告" if source["type"] == "domain" else "代鱼业务Python证据")
    require(type(meta.get("id")) is int and meta["id"] > 0 and meta.get("name") == name
            and meta.get("expired") is False and meta.get("digest") == "sha256:"+digest(raw),
            "artifact_metadata_mismatch")
    workflow = meta.get("workflow_run", {})
    require(workflow.get("id") == run["id"] and workflow.get("head_sha") == run["head_sha"]
            and workflow.get("head_branch") == "main", "artifact_revision_mismatch")
    # Artifact API has no attempt field: bind creation to the exact job interval.
    require(timestamp(job["started_at"]) <= timestamp(meta.get("created_at")) <= timestamp(job["completed_at"]),
            "artifact_attempt_mismatch")
    members = source["members"]
    require(isinstance(members, dict) and members, "archive_members_required")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        infos = archive.infolist()
        require(len(infos) <= 10000 and len({i.filename for i in infos}) == len(infos), "duplicate_zip_member")
        total = 0
        for info in infos:
            # Never extract; reject unsafe names and excessive decompressed data.
            artifact_path(files.root, info.filename[:-1] if info.is_dir() else info.filename)
            require(not info.flag_bits & 1 and info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                    "unsupported_zip_member")
            require((info.external_attr >> 16) & 0o170000 != 0o120000, "zip_symlink")
            total += info.file_size
            require(info.file_size <= MAX_ARTIFACT_BYTES and total <= MAX_TOTAL_BYTES, "zip_too_large")
        for path, member in members.items():
            require(path in files.cache and isinstance(member, str) and member in archive.namelist(),
                    "unbound_archive_member")
            require(archive.read(member) == files.cache[path], "archive_member_mismatch")


def source_results(files, source, run, jobs, sha, attempt):
    require(isinstance(source, dict) and set(source) ==
            {"type", "api", "job_id", "artifact", "archive", "receipt", "xml", "raw", "members", "assertion_source"},
            "invalid_source_shape")
    kind, api = source["type"], source["api"]
    require(kind in ("instrumentation", "domain", "python"), "unsupported_source_type")
    require(api in APIS if kind == "instrumentation" else api is None, "invalid_api")
    matching = [j for j in jobs if j.get("id") == source["job_id"]]
    require(len(matching) == 1 and type(source["job_id"]) is int, "job_not_unique")
    job = matching[0]
    name = "启动验证 API "+api if kind == "instrumentation" else "单测、检查与开发包"
    require(type(job.get("id")) is int and job.get("name") == name
            and type(job.get("run_id")) is int and job.get("run_id") == run["id"]
            and type(job.get("run_attempt")) is int and job.get("run_attempt") == attempt and job.get("head_sha") == sha
            and job.get("status") == "completed" and job.get("conclusion") in ("success", "failure")
            and isinstance(job.get("started_at"), str) and isinstance(job.get("completed_at"), str),
            "job_identity_mismatch")
    receipt = files.json(source["receipt"])
    exact_identity(receipt, sha, run["id"], attempt)
    require(type(receipt.get("job_id")) is int and receipt["job_id"] == job["id"], "receipt_job_mismatch")
    require(receipt.get("job_name") == name and receipt.get("api") == api
            and receipt.get("origin") == "execution" and receipt.get("type") == kind,
            "receipt_source_mismatch")
    code = receipt.get("exit_code")
    require(type(code) is int and -255 <= code <= 255, "invalid_execution_exit")
    command = receipt.get("command")
    require(isinstance(command, list) and command and all(isinstance(a, str) for a in command), "invalid_execution_command")
    if kind == "instrumentation":
        require(command == ["adb", "shell", "am", "instrument", "-w",
                            "app.daiyu.android.test/androidx.test.runner.AndroidJUnitRunner"], "wrong_instrumentation_command")
        require(receipt.get("physical_device") is False and receipt.get("sdk_int") == (26 if api == "26" else 37),
                "emulator_api_mismatch")
    elif kind == "domain":
        # Exact argv grammar: no dry-run, exclusions, init scripts, arbitrary
        # Gradle properties or extra tasks that can suppress/replace execution.
        require(command[0] in ("./gradlew", "gradlew", "gradlew.bat")
                and command[1:] in (["--no-daemon", ":core-domain:test", ":app:lintDebug"],
                                    ["--no-daemon", ":core-domain:test"],
                                    [":core-domain:test", ":app:lintDebug"],
                                    [":core-domain:test"]), "wrong_domain_command")
    else:
        require(command in (["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"],
                            ["python", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"]), "wrong_python_command")
    refs = source["xml"]
    require(isinstance(refs, list) and 0 < len(refs) <= 512, "xml_required")
    results, test_refs = {}, {}
    required_paths = {source["receipt"]["path"]}
    for ref in refs:
        found = junit(files.read(ref))
        require(not set(found) & set(results), "duplicate_report_test")
        results.update(found)
        # Bind each selector to its actual report, not the first XML in a bundle.
        test_refs.update({selector: ref for selector in found})
        required_paths.add(ref["path"])
    expected = receipt.get("expected_tests")
    require(isinstance(expected, list) and expected and all(isinstance(t, str) for t in expected)
            and len(set(expected)) == len(expected) and set(expected) == set(results), "test_inventory_mismatch")
    if kind in ("instrumentation", "python"):
        raw = files.read(source["raw"])
        required_paths.add(source["raw"]["path"])
        observed = instrumentation(raw) if kind == "instrumentation" else python_log(raw)
        require(observed == results, "transcript_xml_mismatch")
    else:
        require(source["raw"] is None, "unexpected_domain_raw")
    assertion_digest = None
    if source["assertion_source"] is not None:
        require(kind == "instrumentation", "unexpected_assertion_source")
        assertion_raw = files.read(source["assertion_source"])
        assertion_digest = digest(assertion_raw)
        # Legacy field name: compare local self-reported bytes only. This is
        # not a trusted claim that any executed binary compiled this source.
        require(receipt.get("compiled_assertion_sha256") == assertion_digest, "compiled_assertion_mismatch")
        # An approved whole test class cannot be reduced to our single selector
        # in the receipt inventory. This only enumerates required execution; it
        # never grants credit for the existence of a source method.
        assertion_text = assertion_raw.decode("utf-8")
        require(re.search(r"^package app\.daiyu\.android\s*$", assertion_text, re.M)
                and re.search(r"\bclass PlatformTest\s*:", assertion_text), "wrong_assertion_class")
        methods = re.findall(r"^\s*fun (test\w+)\(\)", assertion_text, re.M)
        require(methods and len(methods) == len(set(methods))
                and {P+method for method in methods} <= set(expected), "assertion_inventory_incomplete")
        required_paths.add(source["assertion_source"]["path"])
    require(set(source["members"]) == required_paths, "incomplete_archive_binding")
    archive_members(files, source, run, job)
    terminal_failure = code != 0 or job["conclusion"] != "success" or "failed" in results.values()
    return {"type": kind, "api": api, "tests": results, "failed": terminal_failure,
            "receipt": receipt, "refs": refs, "test_refs": test_refs,
            "job_id": job["id"], "assertion_digest": assertion_digest}


def collect(spec, spec_digest, features, feature_digest, bundle, files, sha, run_id, attempt):
    rows = mapping(spec, features)
    full_sha(sha)
    require(type(run_id) is int and run_id > 0 and type(attempt) is int and attempt > 0, "invalid_run_selector")
    require(isinstance(bundle, dict) and set(bundle) ==
            {"schema_version", "source_sha", "run_id", "attempt", "spec_sha256", "features_sha256", "run", "jobs", "sources"}
            and bundle["schema_version"] == 1, "invalid_bundle")
    exact_identity(bundle, sha, run_id, attempt)
    require(bundle["spec_sha256"] == spec_digest and bundle["features_sha256"] == feature_digest, "bundle_contract_mismatch")
    run = files.json(bundle["run"])
    require(type(run.get("id")) is int and run.get("id") == run_id
            and type(run.get("run_attempt")) is int and run.get("run_attempt") == attempt and run.get("head_sha") == sha
            and run.get("repository", {}).get("full_name") == REPOSITORY and run.get("workflow_id") == WORKFLOW_ID
            and run.get("head_branch") == "main" and run.get("event") == "workflow_dispatch"
            and run.get("status") == "completed" and run.get("conclusion") in ("success", "failure"), "actions_run_mismatch")
    jobs = files.json(bundle["jobs"])
    require(isinstance(jobs, dict) and isinstance(jobs.get("jobs"), list)
            and jobs.get("total_count") == len(jobs["jobs"]), "jobs_pagination_incomplete")
    sources = bundle["sources"]
    require(isinstance(sources, list) and len(sources) <= 4, "invalid_sources")
    observed, seen = [], set()
    for source in sources:
        item = source_results(files, source, run, jobs["jobs"], sha, attempt)
        key = (item["type"], item["api"])
        require(key not in seen, "duplicate_source")
        seen.add(key); observed.append(item)
    ledger = make_template(spec, spec_digest, sha, "ledger")
    policy = make_template(spec, spec_digest, sha, "policy")
    records = {(r["item_id"], r["kind"]): r for r in ledger["records"]}
    for row in rows:
        item = row["item_id"]
        support = []
        for kind, selector in SUPPORT.get(item, ()):
            for api in APIS if kind == "instrumentation" else (None,):
                found = next((s for s in observed if s["type"] == kind and s["api"] == api), None)
                status = "not_run" if found is None else found["tests"].get(selector, "not_run")
                if found and found["failed"]:
                    status = "failed"
                elif item in FULL_ASSERTIONS and found and found["assertion_digest"] != DRAFT_ASSERTION_SHA256:
                    status = "not_run"
                support.append({"source_type": kind, "api": api, "test_id": selector, "status": status})
        row["support"] = support
        row["gap"] = ("trusted_compiled_provenance_required" if item == "AC-003" else
                      "missing_business_assertion" if not support else "partial_assertions_only"
                      if item not in FULL_ASSERTIONS else "exact_api_execution_required")
        if item in FULL_ASSERTIONS:
            record = records[(item, "platform_automation")]
            if run["conclusion"] == "success" and support and all(s["status"] == "passed" for s in support):
                used = [s for s in observed if s["type"] == "instrumentation"]
                record.update(status="passed", origin="execution", exit_code=0,
                              command=used[0]["receipt"]["command"], actual="Both API matrices executed the reviewed draft callback and failed validation assertions.",
                              artifacts=[dict(s["test_refs"][selector], role="test_report")
                                         for kind, selector in SUPPORT[item]
                                         for s in used if s["type"] == kind])
                row["gap"] = None
            elif any(s["status"] == "failed" for s in support):
                record.update(status="blocked", blocker="Known execution failure; no acceptance pass may be inferred.")
        row["status"] = records.get((item, "platform_automation"), {"status": "not_run"})["status"]
    report = verify(spec, spec_digest, ledger, policy, sha, files.root)
    return {"valid": True, "schema_version": 1, "source_sha": sha, "run_id": run_id, "attempt": attempt,
            "features_sha256": feature_digest, "mapping": rows, "ledger": ledger, "policy": policy,
            "acceptance": report, "retained_scenarios_satisfied": False,
            "business_satisfied": False, "execution_sources": [{"type": s["type"], "api": s["api"],
                 "job_id": s["job_id"], "tests": len(s["tests"]), "failed": s["failed"]} for s in observed]}


def main(argv=None):
    parser = SafeParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    convert = sub.add_parser("convert")
    convert.add_argument("--type", choices=("instrumentation", "python"), required=True)
    convert.add_argument("--raw", required=True)
    for mode in ("mapping", "verify"):
        p = sub.add_parser(mode)
        p.add_argument("--spec", default="docs/refactor-input/acceptance.json")
        p.add_argument("--features", default="docs/refactor-input/features.json")
        p.add_argument("--spec-sha256", required=True)
        p.add_argument("--features-sha256", required=True)
        if mode == "verify":
            p.add_argument("--bundle", required=True)
            p.add_argument("--bundle-sha256", required=True)
            p.add_argument("--evidence-root", required=True)
            p.add_argument("--build-sha", required=True)
            p.add_argument("--run-id", type=int, required=True)
            p.add_argument("--attempt", type=int, required=True)
    try:
        args = parser.parse_args(argv)
        if args.mode == "convert":
            raw = bounded_read(Path(args.raw), MAX_ARTIFACT_BYTES)
            print(to_xml(instrumentation(raw) if args.type == "instrumentation" else python_log(raw)))
            return 0
        spec, spec_digest = pinned_json(args.spec, args.spec_sha256)
        features, feature_digest = pinned_json(args.features, args.features_sha256)
        if args.mode == "mapping":
            output = {"schema_version": 1, "mapping": mapping(spec, features),
                      "spec_sha256": spec_digest, "features_sha256": feature_digest}
            code = 0
        else:
            bundle, bundle_digest = pinned_json(args.bundle, args.bundle_sha256)
            output = collect(spec, spec_digest, features, feature_digest, bundle,
                             Files(args.evidence_root), args.build_sha, args.run_id, args.attempt)
            output["bundle_sha256"] = bundle_digest
            code = 0 if output["business_satisfied"] else 1
        print(json.dumps(output, ensure_ascii=True, sort_keys=True))
        return code
    except InvalidEvidence as error:
        print(json.dumps({"valid": False, "error": str(error)}, sort_keys=True))
    except (OSError, ValueError, TypeError, KeyError, AttributeError, RecursionError, OverflowError, zipfile.BadZipFile):
        print('{"valid": false, "error": "unreadable_or_invalid_input"}')
    return 2


if __name__ == "__main__":
    sys.exit(main())
