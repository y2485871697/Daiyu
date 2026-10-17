#!/usr/bin/env python3
"""Read-only final evidence entry: source/tool, cloud, and 81-item business.

Never builds, installs, dispatches, downloads, runs tests or grants trusted
business acceptance. Exit 0 means ready for independent evidence review only.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile
import zlib

# Existing scripts remain the authoritative signing/reuse policy implementations.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_signing
import verify_license_bundle
import verify_smoke_source
import cloud_report
import evidence
import source_gate
from recovery_suite import Files, python_log, timestamp
from evidence import (DIGEST, InvalidEvidence, SafeParser, artifact_path,
                      full_sha, load_json, require)

APK_LIMIT = 1024 * 1024 * 1024


def shape(value, keys, code):
    require(isinstance(value, dict) and set(value) == set(keys), code)


def identity(value, sha, run, attempt):
    shape(value, ("sha", "run_id", "attempt"), "invalid_identity")
    full_sha(value["sha"])
    require(type(value["run_id"]) is int and value["run_id"] > 0
            and type(value["attempt"]) is int and value["attempt"] > 0,
            "invalid_run_selector")
    require(value == {"sha": sha, "run_id": run, "attempt": attempt}, "identity_mismatch")


def stream_digest(stream, limit):
    digest, size = hashlib.sha256(), 0
    while True:
        chunk = stream.read(min(1024 * 1024, limit - size + 1))
        if not chunk:
            break
        size += len(chunk)
        require(size <= limit, "file_too_large")
        digest.update(chunk)
    require(size > 0, "empty_file")
    return digest.hexdigest()


def pinned_large(root, ref):
    shape(ref, ("path", "sha256"), "invalid_file_ref")
    require(isinstance(ref["sha256"], str) and DIGEST.fullmatch(ref["sha256"]), "invalid_digest")
    path = artifact_path(root, ref["path"])
    with os.fdopen(evidence._open_regular(path), "rb") as stream:
        digest = stream_digest(stream, APK_LIMIT)
    require(digest == ref["sha256"], "file_digest_mismatch")
    return path


def run_and_job(files, run_ref, jobs_ref, expected, name):
    run, jobs = files.json(run_ref), files.json(jobs_ref)
    require(isinstance(run, dict), "actions_run_mismatch")
    repository = run.get("repository")
    require(isinstance(repository, dict), "actions_run_mismatch")
    require(type(run.get("id")) is int
            and run["id"] == expected["run_id"] and type(run.get("run_attempt")) is int
            and run["run_attempt"] == expected["attempt"] and run.get("head_sha") == expected["sha"]
            and repository.get("full_name") == cloud_report.REPOSITORY
            and run.get("workflow_id") == cloud_report.WORKFLOW_ID and run.get("head_branch") == "main"
            and run.get("status") == "completed" and run.get("conclusion") == "success",
            "actions_run_mismatch")
    require(isinstance(jobs, dict) and isinstance(jobs.get("jobs"), list)
            and type(jobs.get("total_count")) is int and jobs["total_count"] == len(jobs["jobs"]),
            "jobs_pagination_incomplete")
    found = [j for j in jobs["jobs"] if isinstance(j, dict) and j.get("name") == name]
    require(len(found) == 1, "job_not_unique")
    job = found[0]
    require(type(job.get("id")) is int and job["id"] > 0
            and type(job.get("run_id")) is int and job["run_id"] == expected["run_id"]
            and type(job.get("run_attempt")) is int and job["run_attempt"] == expected["attempt"]
            and job.get("head_sha") == expected["sha"] and job.get("status") == "completed"
            and job.get("conclusion") == "success", "job_identity_mismatch")
    return run, jobs["jobs"], job


def delivery(files, document, expected, repo_root):
    shape(document, ("run", "jobs", "artifact", "archive", "record", "sums", "signature", "app", "test"),
          "invalid_delivery")
    run, jobs, job = run_and_job(files, document["run"], document["jobs"], expected, "单测、检查与开发包")
    meta = files.json(document["artifact"])
    archive_path = pinned_large(files.root, document["archive"])
    require(isinstance(meta, dict) and type(meta.get("id")) is int and meta["id"] > 0
            and meta.get("name") == "代鱼开发包" and meta.get("expired") is False
            and meta.get("digest") == "sha256:" + document["archive"]["sha256"], "artifact_metadata_mismatch")
    workflow = meta.get("workflow_run")
    require(isinstance(workflow, dict), "artifact_revision_mismatch")
    require(workflow.get("id") == expected["run_id"] and workflow.get("head_sha") == expected["sha"]
            and workflow.get("head_branch") == "main", "artifact_revision_mismatch")
    require(timestamp(job.get("started_at")) <= timestamp(meta.get("created_at"))
            <= timestamp(job.get("completed_at")), "artifact_attempt_mismatch")
    record = files.json(document["record"])
    require(isinstance(record, dict) and record.get("source_sha") == expected["sha"]
            and str(record.get("run_id")) == str(expected["run_id"])
            and str(record.get("attempt")) == str(expected["attempt"])
            and record.get("application_id") == "app.daiyu.android"
            and record.get("certificate_sha256") == verify_signing.EXPECTED_CERT
            and record.get("signing") == "daiyu-fixed" and record.get("variant") == "debug"
            and record.get("debuggable") is True, "delivery_record_mismatch")
    app_name = "代鱼-0.1.0-" + expected["sha"][:8] + ".apk"
    members = {"构建记录.json": document["record"], "SHA256SUMS": document["sums"],
               "签名证据.txt": document["signature"], app_name: document["app"],
               "tests/platform-test.apk": document["test"]}
    for name, ref in members.items():
        if name.endswith(".apk"):
            pinned_large(files.root, ref)
        else:
            files.read(ref)
    # No extraction, shell or APK execution. Reuse evidence's regular-file open
    # and path containment rules; stream large APK/ZIP contents with hard caps.
    with os.fdopen(evidence._open_regular(archive_path), "rb") as stream, zipfile.ZipFile(stream) as archive:
        infos = archive.infolist()
        require(len(infos) <= 10000 and len({i.filename for i in infos}) == len(infos), "duplicate_zip_member")
        total = 0
        for info in infos:
            artifact_path(files.root, info.filename[:-1] if info.is_dir() else info.filename)
            require(not info.flag_bits & 1 and info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    and (info.external_attr >> 16) & 0o170000 != 0o120000, "unsupported_zip_member")
            total += info.file_size
            require(info.file_size <= APK_LIMIT and total <= 2 * APK_LIMIT, "zip_too_large")
        for member, ref in members.items():
            require(member in archive.namelist(), "archive_member_missing")
            with archive.open(member) as content:
                require(stream_digest(content, APK_LIMIT) == ref["sha256"], "archive_member_mismatch")
    sums = files.read(document["sums"]).decode("utf-8")
    require(set(sums.splitlines()) == {
        document["app"]["sha256"] + "  " + app_name,
        document["test"]["sha256"] + "  tests/platform-test.apk"}
        and len(sums.splitlines()) == 2, "apk_sums_mismatch")
    signature = files.read(document["signature"]).decode("utf-8")
    sections = signature.split("APK: ")
    require(not sections[0] and len(sections) == 3, "signature_evidence_mismatch")
    for section, name in zip(sections[1:], (app_name, "tests/platform-test.apk")):
        header, separator, output = section.partition("\n")
        require(header == name and separator, "signature_evidence_mismatch")
        try:
            verify_signing.verify_certificate(output)
        except ValueError:
            raise InvalidEvidence("signature_evidence_mismatch") from None
    # Bind the license gate to the main APK, not the test APK or a fixture ZIP.
    # Recheck the existing digest on this same safely opened stream before ZIP
    # inspection; a path substitution between the earlier checks cannot pass.
    app_path = artifact_path(files.root, document["app"]["path"])
    with os.fdopen(evidence._open_regular(app_path), "rb") as stream:
        require(stream_digest(stream, APK_LIMIT) == document["app"]["sha256"], "file_digest_mismatch")
        stream.seek(0)
        try:
            records = verify_license_bundle.verify_tree(Path(repo_root))
            verify_license_bundle.verify_apk(Path(repo_root), stream, records)
        except (OSError, ValueError, KeyError, TypeError, RuntimeError, zipfile.BadZipFile, NotImplementedError):
            raise InvalidEvidence("apk_license_bundle_mismatch") from None
    return record, run, jobs, {"artifact_id": meta["id"], "artifact_sha256": document["archive"]["sha256"],
                              "metadata_sha256": document["artifact"]["sha256"],
                              "app_sha256": document["app"]["sha256"], "test_sha256": document["test"]["sha256"]}


def reuse(repo_root, build, check, record, run, jobs):
    # Recompute the ancestor/diff, never trust a manifest's changed-files claim.
    env = source_gate.git_environment()
    prefix = list(source_gate.GIT_PREFIX)
    subprocess.run(prefix + ["merge-base", "--is-ancestor", build["sha"], check["sha"]],
                   cwd=repo_root, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, check=True, timeout=5)
    result = subprocess.run(prefix + ["diff", "--no-renames", "--name-only", "-z", build["sha"], check["sha"]],
                            cwd=repo_root, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, check=True, timeout=5)
    require(len(result.stdout) <= 8 * 1024 * 1024, "diff_too_large")
    changed = result.stdout.decode("utf-8").split("\0")
    require(changed[-1] == "", "invalid_diff")
    try:
        verify_smoke_source.verify({"SOURCE_SHA": build["sha"], "SOURCE_RUN_ID": str(build["run_id"])},
                                   record, run, jobs, changed[:-1])
    except ValueError:
        raise InvalidEvidence("old_apk_reuse_forbidden") from None


def verify(manifest_path, manifest_sha256, repo_root, evidence_root,
           build_sha, build_run, build_attempt, check_sha, check_run, check_attempt):
    full_sha(build_sha)
    full_sha(check_sha)
    require(isinstance(manifest_sha256, str) and DIGEST.fullmatch(manifest_sha256), "invalid_pin")
    doc, digest = load_json(manifest_path)
    require(digest == manifest_sha256, "manifest_pin_mismatch")
    shape(doc, ("schema_version", "mode", "build", "check", "delivery", "cloud", "business", "tool"),
          "invalid_manifest")
    require(type(doc["schema_version"]) is int and doc["schema_version"] == 1, "invalid_schema")
    identity(doc["build"], build_sha, build_run, build_attempt)
    identity(doc["check"], check_sha, check_run, check_attempt)
    require(doc["mode"] in ("full_build", "old_apk_recheck"), "invalid_mode")
    if doc["mode"] == "full_build":
        require(doc["build"] == doc["check"], "full_build_identity_mismatch")
    else:
        require(doc["build"] != doc["check"], "recheck_identity_required")
    files = Files(evidence_root)
    source = source_gate.verify(repo_root, check_sha)
    record, run, jobs, apk = delivery(files, doc["delivery"], doc["build"], repo_root)
    reuse_ok = doc["mode"] == "full_build"
    if not reuse_ok and source["source_bound"]:
        reuse(repo_root, doc["build"], doc["check"], record, run, jobs)
        reuse_ok = True
    cloud = None
    log_pins = []
    if doc["cloud"] is not None:
        snapshot = files.json(doc["cloud"])
        cloud = cloud_report.verify(artifact_path(evidence_root, doc["cloud"]["path"]),
                                    doc["cloud"]["sha256"], check_sha, check_run, check_attempt, evidence_root)
        for ref in snapshot["reports"]:
            report = files.json({"path": ref["path"], "sha256": ref["sha256"]})
            log_pins.append({"api": ref["api"], "instrumentation_sha256": report["instrumentation"]["sha256"],
                             "logcat_sha256": report["logcat"]["sha256"]})
    # The single canonical 81-case specification is always used. No count or
    # alternate specification CLI escape hatch, no plan/coverage conversion.
    spec, spec_digest = load_json(Path(repo_root) / "docs/refactor-input/acceptance.json")
    evidence.spec_items(spec, 81)
    business = None
    if doc["business"] is not None:
        shape(doc["business"], ("ledger", "policy"), "invalid_business")
        ledger = files.json(doc["business"]["ledger"])
        policy = files.json(doc["business"]["policy"])
        business = evidence.verify(spec, spec_digest, ledger, policy, build_sha, evidence_root, 81)
    tool = None
    if doc["tool"] is not None:
        shape(doc["tool"], ("receipt", "log"), "invalid_tool")
        receipt = files.json(doc["tool"]["receipt"])
        shape(receipt, ("identity", "command", "exit_code"), "invalid_tool_receipt")
        identity(receipt["identity"], check_sha, check_run, check_attempt)
        require(receipt["command"] in (["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"],
                                       ["python", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"])
                and type(receipt["exit_code"]) is int and receipt["exit_code"] == 0, "invalid_tool_execution")
        results = python_log(files.read(doc["tool"]["log"]))
        tool = {"tests": len(results), "satisfied": all(state == "passed" for state in results.values()),
                "log_sha256": doc["tool"]["log"]["sha256"]}
    ready = bool(source["source_bound"] and reuse_ok and tool and tool["satisfied"]
                 and cloud and cloud["cloud_regression_satisfied"] and business and business["satisfied"])
    # A validated failure is not missing evidence, even if other layers are
    # absent. Keep the full ledger report for independent failure review.
    failed = bool(business and business["counts"]["failed"])
    return {"schema_version": 1, "scope": "final_delivery_internal_consistency",
            "status": "failed" if failed else "ready_for_independent_review" if ready else "incomplete",
            "internal_consistency_satisfied": ready, "final_acceptance_passed": False,
            "business_satisfied": False, "business_evidence_authenticated": False,
            "trusted_collection": False, "manifest_sha256": digest, "mode": doc["mode"],
            "build": doc["build"], "check": doc["check"], "apk_provenance": apk,
            "source_tool": {"source": source, "old_apk_reuse_validated": reuse_ok, "tool": tool},
            "cloud": cloud, "cloud_log_sha256": log_pins,
            "business": business or {"satisfied": False, "counts": {"not_run": 81, "passed": 0, "failed": 0, "blocked": 0}},
            "spec_sha256": spec_digest, "required_business_items": 81}


def main(argv=None):
    parser = SafeParser(description=__doc__, allow_abbrev=False)
    for name in ("manifest", "manifest-sha256", "repo-root", "evidence-root", "build-sha", "check-sha"):
        parser.add_argument("--" + name, required=True)
    for name in ("build-run", "build-attempt", "check-run", "check-attempt"):
        parser.add_argument("--" + name, required=True, type=int)
    try:
        args = parser.parse_args(argv)
        report = verify(args.manifest, args.manifest_sha256, args.repo_root, args.evidence_root,
                        args.build_sha, args.build_run, args.build_attempt,
                        args.check_sha, args.check_run, args.check_attempt)
    except InvalidEvidence as error:
        print(json.dumps({"valid": False, "error": str(error), "final_acceptance_passed": False,
                          "business_satisfied": False, "business_evidence_authenticated": False,
                          "internal_consistency_satisfied": False, "trusted_collection": False}, sort_keys=True))
        return 2
    except (OSError, ValueError, TypeError, KeyError, RecursionError, OverflowError, subprocess.SubprocessError,
            zipfile.BadZipFile, NotImplementedError, zlib.error):
        print('{"valid": false, "error": "unreadable_or_invalid_input", "final_acceptance_passed": false, '
              '"business_satisfied": false, "business_evidence_authenticated": false, '
              '"internal_consistency_satisfied": false, "trusted_collection": false}')
        return 2
    print(json.dumps({"valid": True, **report}, ensure_ascii=True, sort_keys=True))
    return 2 if report["status"] == "failed" else 0 if report["internal_consistency_satisfied"] else 1


if __name__ == "__main__":
    sys.exit(main())
