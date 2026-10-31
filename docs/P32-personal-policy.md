# P32 personal policy / independent playback profiles

## Scope and evidence

This leaf adds only:

- `voice/VoicePlaybackProfiles.kt`;
- `voice/personal/PersonalVoiceCascadeGrant.kt`;
- their two dedicated JVM test sources and this document.

No manifest, Gradle, Main/Controller, runtime, host, player, settings, provider protocol, MiMo index,
shared audio gate or other existing file is changed. **No compilation or tests were executed.**
The parent's final unified batch must validate compilation and behavior. This is API/source delivery,
not a claim that personal cascade is already reachable: the existing host's `mimo-local-` refusal
remains in place until the parent supplies all the integration requirements below.

Inspected local evidence:

- `build/agent-input/fork/app/src/main/kotlin/[historical-package-omitted]/agent/voice/tts/SpeechPlaybackSettings.kt`,
  lines 5–31: shared resources, distinct selection keys and distinct history files for READ_ALOUD and
  CONVERSATION; `mode == "cloud"`, provider/model preserved, voice trimmed at snapshot.
- `ControllerVoiceRuntime.kt`: immutable configuration revision, `referenceOwner()` hashing provider +
  endpoint + key; `authorizeReference()` is explicitly one-request consent, not cascade permission.
- `ActivityCascadeVoiceHost.kt`: ticket reference identity; admitted assistant message/run/conversation
  binding; current code refuses personal-reference cascade.
- `voice/personal/MimoPersonalVoices.kt`: provider-owned references, local IDs not used as wire voice IDs.
- `voice/providers/DoubaoSpeech.kt`: account/status/accepted/model checks for account personal voices.
  The new policy does not replace or relax these provider checks.

Only these local sources were consulted. No web browsing, build, device, microphone, upload or paid
request was performed. The old Prefs constant **values** are not present in the frozen input: the new
profile keys are documented logical `Prefs.Keys` identifiers, not asserted old on-disk literal keys.

## Profiles: pure data, not global preferences

`VoicePlaybackProfile.READ_ALOUD` owns logical `AGENT_TTS_*` keys and
`read_aloud_voice_history`. `CONVERSATION` owns logical `AGENT_VOICE_CONVERSATION_*` keys and
`voice_conversation_voice_history`. Selection is isolated; a shared provider/model/voice catalog does
not imply shared selection or history. The parent must map logical keys to its own durable storage,
keep each history file/model history separate, and migrate old single-profile data only explicitly.
There is no implicit conversation-to-read-aloud copy or fallback in this API.

Actual API:

```kotlin
val profiles = VoicePlaybackProfiles(
    readAloud = VoicePlaybackProfile.READ_ALOUD.snapshot(readString),
    conversation = VoicePlaybackProfile.CONVERSATION.snapshot(readString),
)
val read = profiles.freeze(VoicePlaybackProfile.READ_ALOUD, configurationRevision)
val talk = profiles.freeze(VoicePlaybackProfile.CONVERSATION, configurationRevision)
val changed = profiles.withSettings(VoicePlaybackProfile.CONVERSATION, newConversationSettings)
val encoded = talk.encode()
val restored = FrozenVoicePlaybackProfile.decode(encoded, VoicePlaybackProfile.CONVERSATION)
```

`VoicePlaybackSettings(cloud, providerId, modelId, voiceId)` matches the frozen source's selection
shape. `FrozenVoicePlaybackProfile(profile, configurationRevision, settings)` is an immutable
versioned selection/revision envelope, with strict expected-profile, field-size, canonical encoding,
version and trailing-data checks. It is pure JVM data and contains **no reference bytes, credential,
account access, provider object or consent**. The codec is not encryption/authentication. Use the
existing vault for any sensitive persistence; parent validates revision and resolves credentials.
Decoding cannot restore any cascade permission. Do not replace both runtime profiles with one
mutable global `VoiceConfiguration` selection merely because their resource catalogs are shared.

## Finite personal cascade capability

All consent dimensions are explicit:

```kotlin
PersonalVoiceCascadeBinding(
    accountFingerprint,       // lowercase SHA-256 of the actual endpoint/account, never raw key
    configurationRevision,   // any relevant config save gets a new revision
    voiceId,                 // exact selected local-reference OR account-personal voice
    referenceDigest,         // SHA-256 of exact immutable reference / canonical account-voice record
)
PersonalVoiceCascadeOwner(conversationId, runId, messageId)
PersonalVoiceCascadeLimits(
    maxSentences,             // synthesis segment/upload attempt count, not linguistic parsing
    maxCharacters,            // total Unicode code points, including whitespace/punctuation
    maxAudioDurationMs,      // sum of pre-upload duration reservations
    validForMs,              // separate monotonic time window beginning at explicit authorization
)
```

All limits are positive and finite; there is no omitted-limit/unlimited mode. The API does not choose
consumer defaults. The actual UI must disclose destination/account, selected recording/voice, that
reference audio plus answer text may be repeatedly uploaded, possible per-sentence billing, and all
four limits. The user's explicit **finite cascade** confirmation is different from a manual-read
one-request confirmation. Import, selection, profile load, read consent and dialog restoration may
not authorize this grant. Do not derive a cascade grant from `authorizeReference()`'s result.

`PersonalVoiceCascadeGrant.digest(string)` hashes the exact UTF-8 input. For a local MiMo voice,
hash the exact frozen data URI that synthesis will use (including MIME/base64 prefix), not a display
name, local path, ID or length. For an account voice, hash a canonical account/voice/status/model/
acceptance-record revision and retain the ordinary provider validation; this policy does not enroll
or train voices and does not create an account voice catalog.

### Minimal parent runtime / host injection

The following is integration-shaped code using the delivered APIs; names `liveBinding`,
`currentAdmittedOwner`, `admission`, `revokeAndStop`, and `playbackCap` are parent-supplied facts/hooks,
not additional delivered methods.

```kotlin
// Only after a separate explicit finite-cascade dialog; never in prepareSpeech/profile decode.
// Resolve/freeze the reference once locally on IO, without uploading it. Freeze the CONVERSATION
// profile and its runtime configuration, and keep the exact URI private to this operation.
val binding = PersonalVoiceCascadeBinding(
    configuration.referenceOwner(), configuration.revision, configuration.voice,
    PersonalVoiceCascadeGrant.digest(frozenReference),
)

// At reserve(originalTicket): same object later received by accepted(). Do not generate a new ticket.
val grant = PersonalVoiceCascadeGrant.authorize(
    binding = binding,
    limits = explicitlyConfirmedLimits,
    sendTicket = originalTicket,
    currentBinding = { liveBinding() }, // live runtime/account + reference availability/digest
    ownerIsCurrent = { owner -> admission() && currentAdmittedOwner() == owner },
)

// At accepted(originalTicket, exact admitted assistant), not latest history or guessed run owner:
val answerOwner = PersonalVoiceCascadeOwner(
    assistant.conversationId.value, assistant.runId.value, assistant.id.value,
)
check(grant.bindAnswer(originalTicket, answerOwner))

// In the output path immediately before EVERY actual sentence HTTP upload, not just at begin():
val use = grant.consume(
    originalSendTicket = originalTicket,
    owner = answerOwner,
    requestId = actualRequestId,
    text = exactSynthesisText,
    reservedAudioDurationMs = playbackCap,
) ?: run { revokeAndStop(); return }
// Dispatch exactly one request with exactSynthesisText and the same immutable frozen reference.
// Enforce use.reservedAudioDurationMs as the actual per-request playback cap. No automatic retry.

// At stop / owner retirement / rejected or unknown send / account change / config save:
grant.revoke() // fence first, then cancel HTTP and drain the existing output/audio lease
```

Runtime supplies the immutable selection and exact frozen reference; host owns the original ticket,
exact assistant identity, grant and cancellation lifecycle. The parent chooses CONVERSATION for
cascade/dictation and READ_ALOUD for manual answer reading. For an account voice, adapt construction
of binding to the **account actually sent**, including any header override, rather than blindly using
the MiMo-specific `referenceOwner()` example. Never fabricate an empty/constant reference digest.

`liveBinding()` must be recomputed/invalidated from live runtime state, not return the frozen binding
unconditionally. It returns null on unreadable config, deleted/unavailable reference, account-status
failure or retired operation. An immutable reference snapshot may retain its own digest, but its
availability/account/config checks must still be live. If the reference is re-resolved, recompute the
digest of the exact new material and reject changes; never substitute it silently. Callbacks execute
under the grant lock, so use cheap parent-owned state and do not call back into the grant or perform
blocking media/network work there.

`bindAnswer` succeeds once and requires original ticket **reference identity**. `consume` checks
message + run + conversation, live binding, lifecycle admission, monotonic expiry, request-ID replay
and all remaining budgets. Wrong ticket/owner, changed binding, expired/backwards clock, callback
exception, malformed request or budget overflow revokes the rest permanently; returning to the old
account/config/owner cannot revive it. A failed attempt has no refund API. `PersonalVoiceCascadeUse`
is redacted audit metadata (ordinal, text digest, reservation, remaining budgets), not a reusable
upload capability. The parent must never cache/replay it to issue additional uploads.

### Duration and asynchronous boundaries (must not be omitted)

Audio duration is not knowable from arbitrary text before synthesis. A text-length estimate is **not**
a guaranteed audio-duration upper bound. This policy reserves the caller's explicit per-request
maximum and totals reservations; the parent must enforce that maximum against actual decoded audio/
playback, rejecting overlong output or reliably cutting off/draining at that cap. `validForMs` is an
additional grant lifetime, not evidence of audio duration. If the output adapter cannot enforce the
cap, personal cascade must remain disabled, rather than treating an estimate as a hard guarantee.

`isActive()` checks live state and unspent budget; it is useful before new work, but is not a replacement
for per-upload `consume`. The final allowed request may legitimately leave zero remaining budget.
Grant methods cannot cancel an already in-flight provider request or undo a previously uploaded
reference. Parent still must fence queued work by operation epoch/current owner immediately before
network dispatch and playback, and cancel/drain existing work on stop/config/account change/expiry.
Do not allow a callback to restart old work after `revoke()`. Keep the existing single audio owner gate;
do not introduce a second gate, fallback synthesizer, automatic retry or automatic re-arm.

## Test source coverage / unexecuted acceptance

`VoicePlaybackProfilesTest`: disjoint logical keys/history, source snapshot trimming semantics,
immutable independent updates, both-profile codec roundtrip, cross-profile rejection, invalid version,
trailing/truncated/oversized input and redacted diagnostics.

`PersonalVoiceCascadeGrantTest`: original ticket identity (including equal-looking objects), one-time
bind, exact message/run/conversation ownership, live account/revision/voice/reference invalidation,
old owner/stop/error fail-closed, each budget and Unicode counting, pre-consent timing/expiry/clock
rollback, replay/no-refund, invalid input, arithmetic overflow safety, concurrent overspend and redaction.

**Tests were added but not executed; no compile-success claim.** Parent must run its unified JVM/device
batches, then verify UI consent cancellation/stale callback behavior, real host per-upload consumption,
actual duration enforcement, stop/config/account cancellation, actual provider checks and playback.
The new leaf alone does not prove those production integrations or actual audibility.
