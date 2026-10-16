# P19/P20 independent generation settings (detached component)

## Scope and source

This change adds only:

- `model/generation/GenerationSettingsStore.kt`: the production source of explicit generation configuration, detached snapshots and a narrow encrypted-credential adapter.
- `ui/screens/settings/GenerationSettingsScreen.kt`: a controlled Miuix settings component and memory-only editing drafts.
- `ui/GenerationSettingsHost.kt` and app-private `GenerationSettingsEditor.kt`: an independently mountable production editor host with per-visit ownership and background I/O. See [the exact parent API, admission contract and regression sources](generation-settings-host.md).
- Independent tests and this document.

It does **not** connect navigation, Controller, Main, or the existing `GenerationApprovalHost`. Merely installing or opening the component cannot send a request, discover models, download content, or authorize a run. There is no chat-model fallback or provider capability inference.

The existing `GenerationModelProfile`, `GenerationProtocol`, `GenerationKind`, `GenerationRunConfig` and `GenerationDownloadHosts` remain authoritative. Both image protocols and the video protocol are supported as explicit choices. Kind/protocol incompatibility is rejected rather than coerced. An endpoint must already be the complete HTTPS generation endpoint accepted by `GenerationModelProfile`; no path is appended.

## Parent-facing API

Construct `GenerationSettingsStore(context)`. Perform its synchronous I/O off the main thread:

- `loadSnapshot(): GenerationSettingsSnapshot` returns a detached, validated configuration with `accountScope`, `revision`, `profiles`, `downloads`, `maxPolls`, and complete editable `values`.
- `loadForEditing(): GenerationSettingsEditingSnapshot` preserves every non-secret original field, the stable IDs, and all still-valid keys. Missing/stale credentials are represented by an empty key **only in this editing result**, accompanied by `credentialUnavailableIds`. `incompleteSave` requires a separate explicit recovery confirmation. This result is not accepted as a runnable configuration.
- `save(values, expectedRevision, confirmIncompleteSaveRecovery = false, admitSave = { true }): GenerationSettingsSnapshot` validates, durably commits and reads back the configuration and credentials. Adopt the returned snapshot; do not treat a draft or a successful button click as persisted state. `expectedRevision` is null only before the first save. A stale editor is rejected. The optional fast, memory-only owner predicate runs once under the shared store lock before store reads/writes/fencing; false rejects with `STALE_EDIT`. Once admitted, the store completes real readback even if the editor leaves.

`GenerationSettingsException.reason` distinguishes missing configuration, corrupt configuration, unavailable credentials, an incomplete/unconfirmed save, stale editing and invalid input. Its message contains no configuration values. For **MISSING only**, the host may show a new blank `GenerationSettingsDraftUi()`. For credential loss, use `loadForEditing()` and show its explicit warning. For corruption, surface the error and fail closed; do not substitute a blank runnable model or silently repair unknown fields.

The Compose entry point is:

```kotlin
@Composable
fun GenerationSettingsScreen(
    draft: GenerationSettingsDraftUi,
    onDraftChange: (GenerationSettingsDraftUi) -> Unit,
    onSave: (GenerationSettingsDraftUi) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    credentialUnavailableIds: Set<String> = emptySet(),
    incompleteSave: Boolean = false,
    enabled: Boolean = true,
)
```

Both snapshot types expose `toGenerationSettingsDraft()`. A save callback converts with `draft.toSettingsValues()`, then calls the store with `draft.expectedRevision` and `draft.confirmIncompleteSaveRecovery`. Catch conversion/store errors and display sanitized messages. Disable interaction while saving, and reload/adopt the returned draft only on success. The component itself has no side effects or storage access.

Do not use `rememberSaveable`, saved instance state, logs, analytics or backups for these drafts/snapshots. They contain credentials in memory. `toString()` is redacted, but this is not permission to log their individual fields. Do not retain a draft beyond its editing session.

## Parent generation preparation

Use **one** loaded `GenerationSettingsSnapshot` when preparing an approval:

- Its `accountScope` supplies the account scope of the parent's durable preparation identity. It is neither display text nor a newly generated per-run value.
- Its `profiles` supply the trusted profiles; select by persisted profile ID, not display label.
- Its `maxPolls` and `downloads` supply the corresponding preparation inputs.
- Retain the exact frozen configuration for execution/recovery under the existing owner and approval contracts.

A settings save/change or selection change must invalidate any old prepared approval and advance the parent's editor revision, including edit-then-revert. Persisted settings are **not** `TaskAuthorization`, an approval token, a runner owner, or a paid-write grant. The existing explicit paid confirmation and owner binding remain mandatory.

This settings component does not own per-item prompts, references, masks or optional request parameters. It does not remove, default or translate them. Preserve the full item maps in the existing preparation flow; currently unsupported optional parameters must remain blocked by that flow rather than being silently dropped. Saved generation model identifiers may be up to the existing generation profile limit (512 characters), independent of chat-model limits.

## Persistence and failure handling

The `generation-settings-v1` manifest contains typed non-secret configuration, opaque persistent scope/profile IDs, a revision, and credential binding digests. Scope and IDs are generated once when first persisted, not derived from labels or generated at load/preparation time. Changes to labels, model, endpoint, protocol or credentials do not rotate existing IDs or scope. New profiles receive distinct IDs. Saves preserve the IDs through explicit incomplete-save recovery.

The existing `ModelSettingsStore` handles all credential encryption, key alias rotation and AndroidKeyStore access, using separate per-profile `generation-credential-…` namespaces and generation-only alias prefixes. The narrow adapter stores a fixed inert envelope because the legacy type validates chat-specific fields; that envelope is **not** a provider configuration or a network target. The actual generation fields remain in the independent manifest. No chat/summary namespace or catalog is used as a source. No new plaintext credential preferences or parallel encryption framework is introduced.

Save ordering:

1. Validate all fields, kinds/protocols, IDs, hosts and limits before any write.
2. Commit and read back a complete `saving` manifest before changing credentials.
3. Save each key via the existing encrypted store and read it back.
4. Commit/read back `ready`, decode it, and verify all credential bindings again before returning a usable snapshot.

Failures retain a namespace-wide in-process fence and best-effort restore the durable `saving` manifest; no failed save returns a usable snapshot. The editor can present that complete candidate for explicit re-save/recovery, with lost or mismatched keys clearly marked. Missing/unknown fields, malformed types, unsupported schema/protocol values, and incompatible kind/protocol pairs fail closed before credential lookup. There is no automatic corruption repair.

`SharedPreferences.commit()` plus readback is the existing durability boundary; readback is not an independent raw disk read. Total storage failure (including failure to restore a saving gate) cannot guarantee crash-surviving detection. The in-process fence remains conservative across all store wrappers. This limitation is not described as transactional storage or crash-proof recovery.

Removing a profile removes it from the active manifest. The existing credential store has no deletion API, so an unreferenced encrypted vault can remain on disk; it is not exposed as a profile or used as fallback. This component deliberately does not rewrite the credential framework to add garbage collection.

## Download policy

Download permission is a separate explicit switch and reviewed exact-host list, not implicit permission from the generation endpoint or a paid-run approval. The UI requires a fresh explicit host-review confirmation for saves with downloads enabled. Editing the list or toggle invalidates that review. Disabled downloads preserve the original entered list but expose an empty effective policy; an empty list also denies every download even if the switch is enabled.

The complete input is retained and validated: no whitespace trimming, lowercasing, blank-line filtering, duplicate removal, URL-to-host extraction, wildcard expansion or suffix matching. Enter lowercase exact domain names, one per line. The existing host contract accepts only HTTPS download URLs with the reviewed exact host and no user information, fragments or non-default ports. Parent network/DNS policy and redirect denial are still mandatory; a hostname allowlist alone is not SSRF protection or DNS pinning.

## Verification status

Added tests (not executed by this implementation agent):

- `GenerationSettingsStoreTest` (Robolectric): missing-source behavior; all protocol roundtrips; stable scope/profile IDs; stale editor rejection; exact raw values; long-lived detached snapshots; absent/swapped credentials; schema/type/enum corruption; validation before writes; strict separate host policy; credential and manifest readback failure; false/throw-after-write gates; explicit recovery; UI draft conversion and host-review requirements.
- `GenerationSettingsStoreDeviceTest` (instrumentation): actual AndroidKeyStore encrypted roundtrip and namespace/alias separation; generation model names beyond chat limits; absent ciphertext/foreign aliases; no plaintext credential in preferences.

Build, unit tests, instrumentation tests, Compose runtime rendering/click tests and device/process-death tests have **not** been run here. The supervising agent must compile and execute appropriate checks before claiming they pass. Existing production controller/approval integration remains intentionally detached.
