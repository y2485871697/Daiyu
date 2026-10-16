# P32 production cloud / manual-cascade host wiring

Implementation source handoff; **not a build/test execution report**. No network/provider/duplex leaf,
Gradle, manifest, native package or offline model files were modified by this implementation.
The network worker supplies `VoiceNetworkAdapters.http()/asr()/duplex()` (fresh instances).

## Actual reachable routes

* Settings page now has `open-voice-settings` (“语音设置 / 实时通话”), leading to
  `retainedPage == "voice-settings"` and `VoiceSettingsPanel`. It is a production route, not a helper-only API.
* Existing answer “朗读” calls `requestConfiguredSpeech`: SYSTEM retains Android TTS; other explicitly
  configured engines use `SpeechSynthesisClient` through the provided real HTTP adapter and play actual
  returned audio with MediaPlayer. An unavailable/missing cloud configuration never selects SYSTEM.
* Existing composer microphone calls the selected SYSTEM dictation host or `remoteVoice.dictate`.
  DOUBAO uses real ASR binary protocol + 16 kHz PCM capture. Second click ends capture and sends the
  final marker. Final frame only becomes an editable draft. Partial hypotheses are display-only.
  User must still click the existing Send action; no speech callback creates a chat request.
* Settings has `voice-duplex-start` (“明确开始豆包双工通话”), with an explicit disclosure of live mic
  external transmission and possible billing, and `voice-stop`. It uses `DoubaoDuplexSession` with
  `AndroidDuplexAudio` and the provided duplex transport. UNKNOWN is displayed as terminal and never
  automatically reconnects. This call does not pretend to be an ordinary chat cascade.
* Composer has `voice-cascade-arm`: explicit one-next-answer **manual-confirmation cascade**. User
  clicks microphone, reviews/edits the final draft and manually clicks existing Send. Arming may also
  be used with a typed draft. It does not start microphone or paid work by itself. SYSTEM or the exact
  selected cloud TTS configuration is frozen at arming; cloud text may generate multiple charged
  sentence requests, disclosed in mode status.
* Cascade reserves the actual `TextSendTicket`; only a matching `TextSendResult.Accepted` can bind.
  Receipt now carries the precise assistant Message captured at `sendPrepared`'s existing committed
  owner installation. It never guesses the current/latest history message. Rejected/UNKNOWN/stale
  receipts cannot start speech and do not retry. Image sends explicitly disarm this text-only mode.
* `publishChat` feeds exact current/admitted assistant `MessageBlock.Text` snapshots and RunStatus into
  `ActivityCascadeVoiceHost` → existing `VoiceChain` / `ProductionSpeechOutput`. Matching message,
  conversation and run IDs are mandatory. Tool blocks, protocol items, history payloads and reasoning
  events are not speech input. Non-append intermediate revisions stop speech honestly; final snapshot
  correction is checked by VoiceChain. STOPPED/FAILED/INTERRUPTED stops, COMPLETED flushes remaining
  complete text. No `observeReasoning` hook and no separate LLM request were added.

## Independent configuration and sensitive-reference consent

`ControllerVoiceRuntime` owns namespace `voice-credentials-v1` / AndroidKeyStore alias prefix
`voice-api-key`, using existing `ModelSettingsStore` AES-GCM storage with an inert credential envelope.
The complete configuration JSON (keys AND endpoint/model/provider/voice/reference ID) is inside the
existing encrypted credential field. Only inert envelope metadata is plaintext; no raw voice keys are
put into new plaintext preferences. Chat credentials/settings are never consulted. Failed decryption,
malformed configuration and unconfirmed save fail closed; explicit Save is recovery. Config revisions
are random and immutable; save publishes ready only after vault readback.

TTS has explicit engine, HTTPS base/endpoint interpreted by that engine's retained protocol, exact
model, provider/account label, key and voice fields. ASR and duplex have separate independent keys.
Their retained protocols have fixed endpoint/model; UI displays those exact constants rather than
pretending that arbitrary models/endpoints are supported. ASR resource is selected by exact value.
MiMo clone uses exact fixed `mimo-v2.5-tts-voiceclone` and rejects a mismatching configured model.

MiMo personal voices can be imported (local MP3/WAV), selected and deleted in the settings route.
Import/select never sends a reference or grants permanent authorization. Local references bind to a
length-delimited hash of provider label + endpoint + key; changing account/key/endpoint fails closed.
Before each manual personal-voice read, an actual dialog identifies destination provider/endpoint and
selected recording, explains that recording plus answer text will be sent, and asks confirmation for
**this request only**. Configuration and reference bytes are frozen; epoch/owner/current-text checks
reject stale confirms and late load completions. Request-time reference checks bind the same selected
ID/account. A deleted recording cannot be silently replaced by a default speaker.

## Ownership, permissions and exit

All production paths use the existing unique `ActivityVoiceAudio.gate`. Manual playback remains under
existing VoiceHost; remote player never acquires another gate. Dictation/cascade/duplex each lease that
same gate. Previous resources must strictly release before a new owner starts. Remote queued HTTP
results and MediaPlayer callbacks are fenced before cancellation/release. ASR capture uses the existing
strict-drain Android PCM adapter; it does not enqueue playback audio. Duplex leaf retains its own
strict audio/network close. Release failures propagate into the existing poisoned gate.

The existing Activity RECORD_AUDIO launcher is reused. Missing permission opens permission UI only;
a permission result never resurrects the prior attempt, starts microphone, reconnects, or initiates
paid work. User clicks again. Activity pause/stop/destroy, page/sidebar/conversation changes stop and
drain active calls/cascade; editor generation/revision changes invalidate dictation/reference callbacks.
Explicit stop is reachable in both composer and voice settings. No automatic microphone on render,
load/resume or mode arming. Speech-start states are software evidence, not proof of audibility or offline
operation; remote cascade reports MediaPlayer.start-returned evidence, not TTS onStart evidence.

## Integration anchors for parent's Main/Controller merge

`ChatController.kt`:
1. `voiceRuntime` lazy property next to `contextService`.
2. `TextSendResult.Accepted.assistant` (default null preserves other constructor callers).
3. `Preparation.admittedVoiceOwner`, normal `sendText` Accepted construction.
4. `sendPrepared`: capture assistant at existing `problem=null;active=assistant` installation.

`MainActivity.kt`:
1. Existing `audioGate` / `speechHost` block: prepared player factory, runtime, consent, remote/cascade hosts.
2. `onCreate`: load independent voice configuration (no audio/network activity).
3. Existing onVoice/onSpeak actions: configured dictation / prepared playback. Existing image branch
   disarms text cascade, normal send remains original sendDraft route.
4. Settings switch: reachable `voice-settings` panel plus small settings-page entry button.
5. `DictationComposerEntry`: selected dictation disclosure, cascade arm/status/stop, cloud status/stop.
6. Page/sidebar setters and lifecycle false-foreground branches: stop cascade/remote; editor revision /
   generation setters invalidate stale dictation and reference confirms. Explicit conversation select
   closes cascade before `speechHost.enter`.
7. `sendDraft`: reserve ticket; `completeTextSend`: accept/reject exact receipt after existing checks;
   `publishChat`: project only matching assistant body snapshots. No second chat send path.

New dedicated files: `ControllerVoiceRuntime`, `RemoteVoiceHostPlayer`, `ActivityRemoteVoiceHost`,
`ActivityCascadeVoiceHost`, `VoiceSettingsPanel` and `VoiceProductionHostDeviceTest`.

## Test sources / remaining limits (do not report these as tested)

Added `VoiceProductionHostDeviceTest` cases for real Android vault separation/encrypted roundtrip,
long exact model preservation, stale revision/account failure, missing ciphertext fail-closed, and
injected-output cascade receipt/body/run/late-callback/UNKNOWN-send fencing. These source cases make
no microphone/network/billable calls. **No tests or build have been run in this worktree.** Parent must
compile and run unified batches after merging the separately delivered real network and offline files.
Real-device mic permission denial/grant/reclick, background drain, audio-route switches, provider
credentials, reference upload consent cancellation, actual synthesis playback and server UNKNOWN
behavior remain device/integration acceptance, not verified here.

Intentional/precise limits:
* This is manual-confirmation cascade, not hands-free auto-send, wake word, continuous mic loop or
  auto-rearm after a response. Those behaviors were not added or claimed.
* Personal-reference voices are explicitly refused in cascade, since one selection cannot authorize
  an unknown number of reference uploads. Manual personal read with one-request consent works.
* Personal management here is MiMo local-reference import/select/delete. Paid server enrollment,
  training, Doubao personal-voice account/status catalog and provider voice discovery UI are not added;
  provider compatibility failures are surfaced, never defaulted. Normal provider voice/model IDs are
  manually exact fields, not a guessed catalog.
* Offline restoration, separate offline settings route, JNI and model provenance belong to the other
  worker; this host change makes no claim about their availability/license/build outcome.
