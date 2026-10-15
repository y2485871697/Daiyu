# Anthropic Messages adapter

## Production entry and configuration

`MainActivity` exposes Responses / Messages selection in the model settings dialog. `ModelSettingsStore` persists only `protocol=messages`; a missing field retains the legacy Responses default. Unknown, empty or wrongly typed stored values cannot fall back to Responses. Editing invalid protocol data returns `UNKNOWN` and no credential, so the user can repair it explicitly.

`ChatApplication → ChatController → ModelFactory` captures the chosen protocol, endpoint, model, credential and tool switches once before the send transaction. The default factory selects `MessagesModel` for `ProviderProtocol.MESSAGES`; no runtime preference reread or automatic provider probing occurs. Changing protocol clears the UI key field and requires re-entry plus a binding confirmation. Store callers must explicitly authorize a protocol change with `protocolChangeAuthorized`; failed saves retain the existing compensation path, including protocol field presence.

- HTTPS only, without userinfo, query or fragment. A host appends `/v1/messages`; `/v1` appends `/messages`; a custom endpoint is retained with trailing slash normalized.
- `responsesPath` must be empty. Responses and Chat Completions endpoints are rejected. The pure domain `messagesEndpoint(address)` does not read settings or credentials, avoiding recursive validation.
- POST uses `x-api-key`, `anthropic-version: 2023-06-01`, JSON content type and SSE accept. Default `max_tokens` is 4096. No Bearer authentication, beta features, extended thinking or redirects are enabled.
- Non-secret backups retain the optional protocol but import it only as metadata. Explicit restore preserves omitted protocol, normalizes Responses to a missing field, disables device tools and sets the credential-rebind gate when protocol changes. Import never authorizes the saved credential for a different protocol.

The adapter originated in task-04 candidate `9f1dd0415529dd548ec0c11d5b80715cc57a9993`. Production integration and verification are recorded in `docs/重构清单.md`; the candidate's source-only report is not a platform execution result.

## History and tools

`MessagesModel` implements both the read-only tool overload and `List<ToolDefinition>`, preserving supplied JSON Schema. It is blocking, single-caller and non-resumable, with no automatic retries. A failed request cannot be reposted through the same session. Each successful tool round requires exactly one output for every pending call.

Only user and assistant roles are supported. System and unknown roles are rejected before connection; there is no trusted system-instruction channel in the current `ModelPort` contract. Incomplete assistants are excluded under the existing history policy. Visible user/assistant text becomes `text`; completed assistant tool blocks become `tool_use`, followed by user `tool_result` blocks. A reconstructed completed tool without a result is rejected.

`MessageHistory.expand()` is the single expansion boundary for authoritative history segments and supplement deduplication. Saved `ProtocolOutput.items` remain authoritative Messages role/content objects, cumulative within the model session. Matching completed tool blocks supply only missing results; existing matching results are not duplicated. Missing results are inserted beside their call, merging before existing user text when appropriate. Duplicate calls/results, orphan results, missing saved calls for authoritative tool blocks and conflicting result payloads are rejected. Encoding does not mutate stored messages or raw strings.

Responses protocol items and unsupported content types are rejected, not converted or replaced with visible text. Responses likewise rejects saved Messages role/content objects before connection. Selecting another protocol does not migrate old protocol history.

## Stream and transport guarantees

The existing `SseReader` supplies blank-line-delimited frames: comments/event/id lines are ignored, multiline data is joined, CRLF is accepted and frame size is bounded. Unterminated frames and EOF before `message_stop` cannot report success.

The stream verifies message/block ordering, indices, unique tool IDs, completion and matching delta types. Incremental text remains visible on later errors. Final text, tools and usage are confirmed only after a complete `message_stop` and supported stop reason (`end_turn`, `tool_use`, `stop_sequence`). `max_tokens`, `pause_turn`, `refusal` and other incomplete stop reasons do not confirm tools. Extended-thinking/signature, image/document and unknown content/delta/event types fail closed, except ping. This is the current adapter's supported subset, not a claim of complete provider feature parity.

Tool JSON fragments must be strings, accumulate per block and parse only as one complete object at block stop. Empty/no fragments retain the required initial input object; they do not fabricate default arguments. Missing/non-object initial input, malformed syntax and non-string fragments fail as sanitized protocol errors. Strict object parsing rejects trailing values, single quotes, comments, bare keys, invalid escapes/numbers and non-object roots before Android's permissive JSON parser runs. It preserves legal arrays, null, Unicode and whitespace. Text/argument accumulation is bounded to two million characters and 1024 blocks.

Container limits include the root; scalar values consume no additional container depth:

| Boundary | Maximum containers | Reserved outer containers |
| --- | ---: | --- |
| SSE event / complete POST | 128 | 0 |
| Saved role/content message | 126 | POST root + messages array |
| Tool input / reconstructed arguments | 123 | Above plus message/content array/tool block |
| Tool input schema | 125 | POST root/tools array/tool definition |

Materialized envelopes are checked before serialization, including retained unknown SSE fields. A maximum accepted tool input fits its saved protocol message and the next POST. The assistant envelope is checked before confirming tools/final text/usage. No fallback accepts malformed input.

Usage merges start/delta data without erasing missing fields: input includes ordinary input plus cache reads and cache creation; cached tokens track reads. Counters must be non-negative and bounded, and usage is emitted only after successful termination. This mapping does not complete the separate trusted request/attempt/usage persistence chain.

HTTP/SSE failures map to fixed shared `ModelFailure` values without service error bodies or credentials. Cancellation checks surround connection setup, headers, delivery, terminal persistence and cleanup; cancel disconnects the current connection. Caller sink exceptions keep their identity, and cleanup cannot replace the primary failure. Streams close with `use`; error streams close without reading; owned connections disconnect in `finally`. There is no silent repost after timeout or EOF. The shared failure enum still has Responses-specific wording in some UI messages.

## Verification scope

`MessagesTest` contains 18 offline synthetic HTTPS/SSE methods covering headers, endpoints, history, schema, multiple rounds, framing, strict JSON/depth, error sanitization, transport failure, cancellation, resource cleanup and segmented protocol replay. Six `MessagesHistoryContractTest` JVM methods cover expansion, missing/matching/conflicting/orphan results, insertion order and foreign protocol rejection. Factory/endpoint tests cover typed protocol freezing and early configuration rejection. Store, backup and Activity tests cover credential gates, compensation, metadata import and real selection UI.

Parent host compilation and dual-JSON synthetic fixture execution are recorded separately from Android instrumentation. API26/API37.2 execution and exact source SHA are recorded in the reconstruction ledger and bound cloud evidence. Live provider requests, complete provider feature parity and full product acceptance remain separate pending work. No APK download or installation is authorized for this batch.
