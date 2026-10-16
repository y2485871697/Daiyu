# P32 offline speech restoration

## Production entry and implementation

`MainActivity` initializes the pack verifier (no download), exposes **离线语音设置**
from the actual composer, and routes to independent `offline-speech`. This screen is
separate from the general voice-settings worker's files. Explicit two-step model
confirmation downloads the fixed seven-file pack; **启用离线听写** is a separate
operation. With offline enabled the existing microphone action and the explicit
**离线听写（本机麦克风）** composer button use sherpa-ncnn. The explicit offline
button exists only after independent enable; it never falls back to system/cloud.
The pre-existing general microphone's system-host behavior when offline is disabled
was preserved for the separate general voice worker and its existing tests; this is
not claimed as offline recognition. Parent must merge that general microphone policy
with the separate voice-settings worker. No offline session invokes SpeechRecognizer.

Implementation locations:

- `voice/offline/SpeechModelManifest.kt`: unchanged seven model names, lengths,
  SHA-256 values and fixed revision `05945efc40afe4b572542f01104ca5c413a9f6e1`.
- `OfflineSpeechPack`: full startup/download validation; HTTPS-only bounded
  redirects; size cap; fsynced partial files; same-filesystem staging directory;
  atomic whole-pack rename into private `noBackupFilesDir/speech/<revision>`.
  Canceled/failed downloads do not admit partial packs or enable the microphone.
- `OfflineSpeechSession` / `AndroidOfflineSpeechDevice`: dedicated native worker,
  actual JNI sherpa-ncnn, mono 16k PCM16, CPU one thread, native endpoint/decoder
  flushing, 8-second no-text and 60-second capture deadlines. No recognizer-service
  intents, audio uploads/files, credential use or transcript logging.
- `OfflineComposerHost` / `SpeechDraft`: target conversation/editor generation/draft
  revision/session identity; captured selection; preview is not a final draft;
  cancel and editor fences suppress queued late results. Only a final natural
  completion writes the current captured draft, never a send API. Edit-then-undo
  is fenced by revision, not merely text equality.
- Audio uses the existing process-wide `ActivityVoiceAudio.gate`, never a new gate.
  Preemption synchronously releases AudioRecord under its resource lock, without
  waiting for native load/main-thread callbacks. A canceled late model load cannot
  open another microphone. Decoder close stays on its worker. Unconfirmed audio
  teardown poisons the shared gate, including across Activity recreation.
- `com.k2fsa.sherpa.ncnn`: original JNI package/member ABI, with explicit close.
  `app/offline-speech-rules.pro` retains all JNI/config classes and members for R8.

## The only build/CI additions

No dependency versions, SDKs, ABI filters, PTY targets, signing flow, or new CI
workflow were added/changed. `:app:prepareOfflineSpeechRuntime` runs the pinned
preparer and exposes generated JNI libraries via AGP's generated `jniLibs` source.
The existing workflow's **build** and **checks** jobs run this preparation before
first unit/Lint execution; it creates no APK. The **smoke** job is unchanged and
only installs/tests the final artifact, never repairs/downloads native libraries.

Pinned tar SHA-256:
`7fbdb6f8bb1f8ec092467ed61d9ec85c01f060d125ccf632ef9d7ec5816d4a65`.
Only eight allowlisted regular ELF entries are copied. Optional
`DAIYU_SPEECH_RUNTIME_ARCHIVE=/path/to/sherpa-ncnn-v2.1.15-android.tar.bz2` makes
Gradle use a parent's already-downloaded archive; `--archive` still requires the
same complete tar SHA-256. Without that environment variable the preparation task
explicitly downloads the fixed official tar at build time. It never downloads the
model. This implementation task did not run the task or fetch any data.

## Provenance and licensing

Restoration input is the frozen P32 release `f001fb82eb9941e5673970c656f5c4d5370fbf7f`
with original hash manifest under `build/agent-input/offline/SOURCE.json`; those
inputs are unchanged. Engine/ncnn/fbank full license texts are retained under
`THIRD_PARTY/sherpa-ncnn`.

The fixed official HF README declares `license: apache-2.0`; its upstream model
is not this application's original work. The previous historical NOTICE claiming
no model metadata license is stale, not the current legal position. Fixed URLs,
README digest, license-addition commit and native transitive provenance are in
`THIRD_PARTY/sherpa-ncnn/NOTICE.md`. Parent independently holds official evidence,
including native tar 9,820,071 bytes/hash match and eight ELF notes: Android API21,
64-bit PT_LOAD 16 KiB, 32-bit 4 KiB. These parent-reported findings were **not
independently executed by this implementation worker**. Parent must integrate the
actual evidence and complete KISS FFT COPYING/BSD-3-Clause license texts before
release; the old three-license set alone is not claimed complete.

## Added checks — all unexecuted here

- `OfflineSpeechContractsTest` (host JUnit): model/deadline contracts, file hash and
  length checks, selected-text replacement, permission does not auto-start,
  revision/late result/duplicate isolation, normalized PCM and decoder tail,
  preemption during native load/capture, synchronous strict release and poisoned
  shared gate, cancellation after natural completion, finite deadlines, native
  load failure without fallback.
- `tests/test_offline_speech_runtime.py`: fixture archives only; pinned metadata,
  corrupt/missing/duplicate rejection before output, exact eight-entry extraction,
  no network when local archive is supplied, CI preparation ordering and smoke
  non-preparation.
- `OfflineSpeechEntryActivityTest` (device instrumentation): real composer opens the
  independent settings route; opening it/first consent tap neither downloads nor
  enables recording. No real model/permission interaction is performed.
- `OfflineSpeechNativePackagingTest` (device instrumentation): inspects **final APK**
  for all eight native entries, installed libraries and JNI/config members, and
  actually loads the native binding on the device. This test does **not** claim
  model inference/microphone capture/recognition accuracy.

No model download, download/preparation script, unit/instrumentation tests, Lint,
Gradle configuration, compile, APK build, or emulator run was performed by this
worker, per delegation. Parent final CI must verify those and resolve MainActivity
merge conflicts. Remaining device acceptance must explicitly provision the verified
model, test real Mandarin/English speech and permission revocation, audio/TTS
preemption, lifecycle/editor switch while loading/capturing, and both API26 and
API37.2/16-KiB devices. Source implementation and test seams alone are **not**
evidence of successful production transcription or a passed build.
