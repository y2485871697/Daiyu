# Production finite personal cascade / independent playback profiles

Implementation artifact; no build, test execution, provider calls or paid traffic were performed in this implementation worktree.

## Runtime contract

`VoiceProfile` is an internal alias of the existing `voice.VoicePlaybackProfile`, not a second profile enum.

* `prepareSpeech()` / `snapshot()` / `save(value)` remain READ_ALOUD defaults for existing manual read, dictation and duplex callers.
* `prepareSpeech(VoicePlaybackProfile.CONVERSATION)` freezes only CONVERSATION; `save(value, profile)` saves only that target. `current(frozen)` checks the frozen profile. Do not unwrap a conversation freeze and call the old `current(configuration)` default.
* `configuration` remains the READ_ALOUD StateFlow for existing callers. `conversationConfiguration` and `profileConfiguration(profile)` expose independent state.
* The existing Android credential vault stores one schema-2 encrypted envelope with distinct profile entries. No missing profile reads another entry. New installations have two separately generated SYSTEM defaults. Old single-profile records are held as `legacyConfiguration`, not activated. Settings explicitly moves the old record to ONE chosen destination using `migrateLegacy(profile)`; the other remains unchanged/unconfigured. The move atomically removes the pending legacy record, avoiding duplicate credential retention and vault-size inflation.
* All profile revisions change on explicit save. No authorization is serialized. Failed/unreadable vaults fail closed; explicit save is the recovery path. Envelope total size still obeys the existing vault limit.
* MiMo import/picker/select/delete behavior remains, with picker invalidation when switching profile. Import and selection never upload. `removePersonal(id)` revokes synchronously before removing reference bytes/index; use this method rather than calling `personal.remove` directly in production UI.

### Account leaf seam (no account network implemented here)

Parent construction can be:

```kotlin
private val voiceAccounts by lazy { VoiceAccountResources.forContext(applicationContext) }
private val voiceRuntime by lazy {
    ControllerVoiceRuntime(applicationContext) { cfg ->
        voiceAccounts.voices.selected(cfg.voice, cfg.speech())
    }
}
```

The resolver is called only for DOUBAO personal IDs and MUST be a cheap state-only lookup: no disk/network/host locks and no grant re-entry. Runtime account validation/fingerprinting uses `effectiveDoubaoAuthenticationKey(config)`, which calls the existing providers top-level `headers(config, "X-Api-Key" to config.apiKey)` and extracts the key case-insensitively, matching DoubaoSpeech/account identity semantics. The override fixture uses SpeechProviderConfig directly; VoiceConfiguration was not expanded with unrelated custom-header storage/UI. `FrozenVoiceSpeech` also accepts optional `doubaoPersonal` and `doubaoResolver` for output factories. Player passes the live resolver result (including null; no stale-record fallback) as synthesize's fourth argument, or the explicitly injected frozen account record if there is no resolver.

**Mandatory parent account mutation fence:** call `voiceRuntime.invalidateSpeech()` BEFORE replacing/deleting an account/voice record or retiring its owner. This synchronously revokes and drains old playback outside the runtime storage lock. Resolver changes are additionally detected before each upload; a resolver alone is not a synchronous mutation notification. The account leaf is not imported or modified by this artifact. Account preview/UI integration is parent-owned.

## Minimal Main composer wiring (parent applies; Main not edited here)

Replace only the existing `voice-cascade-arm` TextButton with:

```kotlin
ProductionPersonalCascadeConsent(
    host = cascadeVoice,
    isCurrent = {
        currentEditor() && voiceRequestEpoch == capturedVoiceEpoch &&
            remoteVoice.operationToken == capturedRemoteToken &&
            speechHost.owner == capturedSpeechOwner && controller.active == null &&
            voiceEditorCurrent(DictationDraftTarget(capturedConversation, capturedGeneration, capturedRevision))
    },
    onFailure = { toast(it) },
)
```

Do NOT pass the old `currentVoiceControls()` unchanged: it captures the pre-preparation cascade epoch, while preparing a new proposal intentionally closes/retires the old cascade. Keep the captured editor/voice-request/remote/speech-owner checks above. The component separately fences the original callback, request generation, exact proposal identity, host epoch, and RESUMED lifecycle. Old async preparation/confirm/dismiss callbacks cannot arm/cancel a newer operation. Leaving composition or backgrounding cancels the pending proposal. Existing status, stop, reserve, accepted/rejected and update wiring stays unchanged.

Keep the original Main `reserve(pending.ticket)`, `accepted(pending.ticket, result.assistant)`, `rejected(pending.ticket)` calls. UNKNOWN goes through rejected and never retries. In `onDestroy`, call `cascadeVoice.dispose()` (in the existing guarded teardown), not only `close()`: dispose permanently unregisters the runtime invalidation listener and cancels the owned scope, while close remains the repeatable Stop API. Do not dispose on ordinary stop/pause if the same host will be reused. No new ChatController API, send automation or Manifest permission is required.

## Business path and limits

* `arm()` is still ordinary explicit manual-cascade permission, now using CONVERSATION; it rejects personal IDs for both MiMo and Doubao. System TTS and ordinary remote voices retain that path.
* `preparePersonalConsent()` only reads local references/state and creates a proposal. `armPersonal(ExplicitPersonalCascadePermission.acceptDisplayedLimits(offer))` is a distinct positive-action permission API; selecting/freezing a voice or authorizing one-request read does not call it.
* The dialog names provider/account fingerprint, destination host, selected voice/reference digest, repeated uploads and possible charges, and all four finite limits. MiMo disclosure specifies reference audio is uploaded each time; Doubao specifies account voice ID rather than claiming local audio upload.
* Limits: 8 synthesis uploads, 1600 Unicode code points including punctuation/whitespace, 120 seconds cumulative audio reservations, 300 seconds from explicit confirmation. Each request reserves a fixed 15 seconds, not text-estimated audio. The host also cancels on the confirmation window deadline, including while waiting for manual send.
* `reserve` creates the grant against the original ticket reference identity and the remaining confirmation window. `accepted` binds only that ticket's exact assistant conversation/run/message. Wrong ticket does not start output. Unknown/rejected, owner retirement, deletion, config/account mutation or lifecycle stop revokes before output drain. A completed message remains owner-bound until all queued audio finishes or that exact message is retired; later updates cannot re-enqueue done.
* Grant callbacks read only volatile/StateFlow/current cached reference metadata plus the state-only account resolver. They never acquire a host/runtime lock, read reference files, drain output, or re-enter grant. Exact local-reference bytes are separately revalidated on IO before consume, outside grant lock.
* `RemoteVoiceHostPlayer` wraps the actual HTTP transport boundary. Immediately before every execute it checks live runtime/reference state, calls `consume` with the original ticket/owner/request ID/exact text, then executes once. Failure/UNKNOWN does not refund, restore, retry or fall back. Reusing a request ID is denied before transport.
* Player enforces the reservation against **MediaPlayer's prepared audio-track duration**. Unknown/nonpositive/over-limit media is rejected before start. Accepted tracks are non-looping and also have a wall-clock cutoff. Completion, error, cutoff, stop and close release/reset the native player and delete the temporary file. Release failures are latched and reported by strict close so the existing audio gate cannot silently treat unconfirmed release as success. This is a real media bound, not a text-length estimate.
* Runtime invalidation callbacks and host close never hold a storage/control lock across cancel/drain. Existing gate/output release protocols remain authoritative.

## Tests supplied (not executed here)

* `PersonalCascadeProductionDeviceTest`: independent encrypted profiles/missing-profile denial, explicit legacy move, ordinary-personal arm denial, UNKNOWN send fencing, real player transport-wrapper consume with fake failing HTTP and replay denial, native over-duration WAV rejection/temp cleanup, live-account resolver and stale-dialog cancellation fences. No provider traffic.
* `VoiceProductionHostDeviceTest`: existing cascade fixtures explicitly configure CONVERSATION and assert owner retirement requires re-arm.
* `RemoteVoicePlaybackBudgetTest`: known/unknown/zero/over-limit media-duration boundary tests.
* `VoicePlaybackProfilesTest`: rejects unpaired UTF-16 surrogate fixtures; maximum valid NUL/supplementary/ASCII fields round-trip within the existing envelope bound. The codec version/wire format is unchanged; malformed UTF-16 is rejected before UTF-8 size measurement to prevent writeUTF expansion mismatch.

## Explicit remaining integration / verification

Parent must apply the small Main composer/runtime-account wiring above and connect account mutation fencing. This artifact does not claim a reachable production dialog until that Main call is applied. Existing single-request manual consent and system TTS callers are intentionally untouched. The separate universal-control host may use `prepareSpeech(CONVERSATION)` and its own output factory without modifying these four existing hosts. Android/Compose compilation, device playback/timer behavior and full test suite remain for the parent unified batch; test source is evidence of intended coverage, not a passing test claim.
