# Offline speech / sherpa-ncnn

## Origin and changes

- Restored from frozen P32 release `f001fb82eb9941e5673970c656f5c4d5370fbf7f` inputs
  supplied under `build/agent-input/offline/SOURCE.json`. Frozen inputs were not edited.
  Production adapters use `app.daiyu.android.voice.offline`, share
  `ActivityVoiceAudio.gate`, atomically publish a complete verified model directory,
  fence microphone startup/cancellation and late editor callbacks, and commit only a
  final captured-selection draft. This is an adaptation, not a new original model.
- Kotlin JNI binding: k2-fsa/sherpa-ncnn tag `v2.1.15`, upstream path
  `android/SherpaNcnn/app/src/main/java/com/k2fsa/sherpa/ncnn/SherpaNcnn.kt`.
  Apache-2.0; full text in `LICENSE`. Original JNI package/config member names remain.
  The maintainer-owned JNI adaptation adds deterministic close/null native handle checks;
  this restoration trims formatting/comments without changing the JNI ABI.
- Native runtime: official release
  https://github.com/k2-fsa/sherpa-ncnn/releases/download/v2.1.15/sherpa-ncnn-v2.1.15-android.tar.bz2
  SHA-256 `7fbdb6f8bb1f8ec092467ed61d9ec85c01f060d125ccf632ef9d7ec5816d4a65`.
  `scripts/prepare-speech-runtime.py` verifies the whole tar before publishing only
  `libncnn.so` and `libsherpa-ncnn-jni.so` for arm64-v8a, armeabi-v7a, x86_64, x86.
  No model weights enter Git/APK. CPU decoding (`useGPU=false`, one native thread).
- Parent-agent official-source audit identifies sherpa source
  `c794e1439fce79932e989220aa1c2848ecbdcdcf`, ncnn
  `c4193aadbbb56582aa87b1850dd3d98fb8fd936d` (BSD-3-Clause and bundled notices in
  `LICENSE-ncnn`), kaldi-native-fbank `v1.21.5` (Apache-2.0 in
  `LICENSE-kaldi-native-fbank`), and linked KISS FFT
  `febd4caeed32e33ad8b2e0bb5ea77542c40f18ec` (BSD-3-Clause).
  Complete fixed-source KISS FFT notices are included as `KISSFFT-COPYING` and
  `KISSFFT-LICENSE`, correcting the historical omission. All files in this directory
  are also delivered inside the APK at `assets/licenses/sherpa-ncnn/`.

## Model and current license evidence

- Official repository:
  https://huggingface.co/csukuangfj/sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13
- Frozen revision: `05945efc40afe4b572542f01104ca5c413a9f6e1`.
  Seven upstream file lengths/SHA-256 values are unchanged in `SpeechModelManifest.kt`.
- Official fixed-revision model card:
  https://huggingface.co/csukuangfj/sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/raw/05945efc40afe4b572542f01104ca5c413a9f6e1/README.md
  declares metadata `license: apache-2.0`. Parent audit recorded README SHA-256
  `d4be344e4c6481e17ea43302ba0bdb999eea93cd0dd4356be83ddcbcdf1cc3ca`
  and license-addition commit `9d685d73e5066a5410204f1947684449cc2e3270`.
  The fixed official model source, not the engine license alone, is the model-license
  evidence. The old frozen NOTICE's "not declared" statement is historical/stale and
  is deliberately not presented as the current licensing position.
- Model weights are downloaded only after explicit confirmation from immutable HTTPS
  URLs. Full hashes/lengths are checked before publication; storage is app-private
  no-backup. Download completion does not enable recording.

## Runtime behavior and limits

- Explicit independent enable and microphone tap/permission; permission grant never
  auto-starts. Recognition itself makes no network requests, stores no audio files,
  logs no transcripts, and uses sherpa-ncnn endpoint detection, not system
  SpeechRecognizer or Silero/Operit code.
- Mono 16,000-Hz signed PCM16 is converted to normalized float samples. No-text
  deadline 8 seconds, capture limit 60 seconds; endpoint trailing silence 1.2 seconds.
- Preview is separate presentation; final text replaces only the captured draft
  selection. User edits/selection changes/send, another editor/conversation, drawer,
  navigation and Activity pause/stop/destruction fence/cancel the attempt. Nothing
  auto-sends a chat message. Audio teardown failures poison the shared gate.
- Packaging/linker tests are not a claim of device transcription quality. See
  `docs/offline-speech.md` for unexecuted checks and release integration requirements.
