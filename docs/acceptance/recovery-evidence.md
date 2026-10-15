# Recovery evidence: offline validation contract

`scripts/acceptance/recovery_suite.py` checks an already-collected local evidence
bundle. It does **not** run commands from receipts, dispatch Actions, download
artifacts, build, start a device/GUI, or perform network requests. A successful
parse is not business acceptance.

## Inputs and identity

- `mapping` requires SHA-256 pins for the acceptance specification and feature
  list. The original 81 AC IDs and their feature associations are fixed in code;
  a bundle cannot replace the mapping.
- `verify` additionally requires a bundle SHA-256 pin, a full source revision,
  run ID and exact run attempt. The bundle refers to local run/jobs JSON,
  receipts, XML, transcripts and ZIP files using relative paths and digests.
- Run identity is restricted to the configured repository/workflow and `main`.
  Jobs must match the selected revision, run, attempt and expected matrix name.
  `receipt.job_id` must be a JSON integer (not a boolean, float or string), equal
  to the selected job ID; receipt revision/run/attempt must also match.
- Artifact metadata must name the expected artifact and match the local ZIP
  digest and run revision. Since artifact metadata has no attempt field, its
  creation time must fall within the selected job's start/end interval (inclusive).
  This is a consistency check, not independent authentication of downloaded JSON.

## Supported result formats (fail closed)

### JUnit

Only strict UTF-8 XML is accepted. Non-UTF-8 declarations, NUL-bearing UTF-16/32
representations, DTDs and entity declarations are rejected before XML parsing.
The supported subset requires explicit test/failure/error/skipped counters,
unique test identities and a nonempty direct testcase inventory. Unknown test
attributes, aborted/retry/flaky outcome elements, unknown suite attributes,
conflicting outcomes and nested/unbound result elements are rejected, not
interpreted as successful tests. Standard `failure`/`error` mean failed;
`skipped` means `not_run`. Unsupported producer extensions require review of
this parser rather than silent acceptance.

### Python unittest

The converter accepts verbose `test_method (module.Class.test_method) ...`
records for `ok`, `FAIL`, `ERROR` and `skipped <reason>`. Unsupported outcomes
(including expected/unexpected-success variants and subtest extensions) fail
closed. Test names must agree, identities must be unique, and exactly one
`Ran N test(s) in <seconds>s` summary must follow all test records. Exactly one
terminal `OK` or `FAILED (...)` must follow that summary. Its failure, error and
skip counts must exactly match the observed records, including skipped tests
alongside failures. Duplicate/contradictory terminals, a new test after summary
or terminal, and nonblank trailing data are invalid. Failure diagnostics before
the summary are not counted as additional tests.

### Android instrumentation and domain tests

Instrumentation requires matched start/finish status frames, a complete
inventory, a consistent summary and a single terminal instrumentation code;
OK-only text is insufficient. Protocol markers hidden by indentation, tabs or
log prefixes, or repeated on one line, are rejected as
`unsupported_instrumentation_line`; the validator does not strip or normalize
these lines. Ordinary non-protocol trailing output remains permitted.
Python/instrumentation transcripts must exactly
agree with the XML inventory and outcomes. A nonzero command exit or unsuccessful
job prevents supporting execution from being reported as passed.

Domain receipts accept only `./gradlew`, `gradlew` or `gradlew.bat`, followed by
optional `--no-daemon`, mandatory `:core-domain:test` and optional
`:app:lintDebug`, in that order. This is an argv allowlist, not substring task
recognition: dry-run (`--dry-run`, `-m`), exclusions (`-x`, `--exclude-task`), init
scripts (`-I`, `--init-script`), arbitrary properties/options and additional tasks
are rejected. Nothing in the receipt is executed by the validator.

## Files and archives

The existing `evidence.py` safe-read API opens only regular local files without
following symlinks in any path component. Absolute paths, drive prefixes,
backslashes, empty/dot/parent components and NULs are disallowed. Reads are
bounded by per-file and aggregate byte limits; digest-verified cached paths do
not spend the aggregate budget twice. JSON also uses the existing duplicate-key
and nonfinite-number rejection and size limit.

ZIPs are inspected in memory and **never extracted**. Every referenced receipt,
report, raw transcript and optional assertion source must be byte-identical to
its bound archive member. Unsafe paths (including malformed directory paths),
duplicate members, symlinks, encrypted/unsupported compression members and
excessive individual/total decompressed sizes are rejected. Unreferenced members
are still subject to name/type/declared-size checks; their content is not
business evidence. Digests prove local byte consistency, not the provenance of
an otherwise untrusted bundle or the authenticity of its upstream producer.

## AC-003 and acceptance limits

The historical frozen Kotlin assertion digest and a receipt's
`compiled_assertion_sha256` are **self-described source identity**, not a trusted
compiled-source attestation. Placing those source bytes in a ZIP does not bind
the executed APK/test binary to those bytes. This implementation has no trusted
build/execution attestation chain, so AC-003 is `partial`, with the gap
`trusted_compiled_provenance_required`, even when both API matrices and the
historical source digest agree. It cannot earn a platform-automation pass from
these inputs. Other reviewed selectors are also supporting evidence only.

Missing acceptance evidence remains `not_run`; failed supporting execution is
visible in the support rows but does not manufacture a business verdict.
`business_satisfied` and `retained_scenarios_satisfied` remain false. The original
self-developed behavior/configuration/data scenarios stay explicit unrun gaps.
Synthetic parser/fixture tests are not project business or real-device evidence.

## Offline CLI and exit codes

Supply independently recorded pins; do not treat a digest recomputed from an
untrusted input as independent authentication. Example argument shapes:

```text
python3 -B scripts/acceptance/recovery_suite.py mapping \
  --spec <acceptance.json> --features <features.json> \
  --spec-sha256 <sha256> --features-sha256 <sha256>

python3 -B scripts/acceptance/recovery_suite.py verify \
  --spec <acceptance.json> --features <features.json> \
  --spec-sha256 <sha256> --features-sha256 <sha256> \
  --bundle <bundle.json> --bundle-sha256 <sha256> \
  --evidence-root <local-directory> --build-sha <full-revision> \
  --run-id <integer> --attempt <integer>

python3 -B scripts/acceptance/recovery_suite.py convert \
  --type python --raw <local-unittest-log>
```

`mapping`/`convert` return 0 for valid inputs. `verify` returns 1 for a valid but
business-incomplete bundle (the current expected result), 0 only if business
satisfaction were established by an implemented policy, and 2 for invalid input.
Errors use bounded diagnostic codes rather than echoing untrusted paths/content.
`convert` merely emits normalized XML; it is not an acceptance verdict.

## Regression review

`tests/test_recovery_suite.py` covers unsupported JUnit outcomes/encodings,
DTD/entity bypasses, unittest count/terminal contradictions, exact receipt job
identity, domain argv suppression attempts, CLI pins and exit codes, cross-attempt
bindings, path/symlink/byte budgets and ZIP path/duplicate/symlink/size boundaries.
The implementation agent has no Shell and has **not run these tests**; execution
and integration verification must be performed by the supervising agent. No
build, recorded command, network operation or GUI is needed for these fixtures.
