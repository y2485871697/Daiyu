# P36 / P32 capture-stopped implementation review

## Scope and source evidence

Task-supplied baseline: `e54c3fae` (not independently checked through Git metadata).
This change is limited to `voice/offline`, `voice/control`, and their tests.
No MainActivity, ChatController, shared checklist, release notes, production voice host,
receipt fence, audio gate, provider protocol, or AndroidDictation implementation changes.

Observed in the assigned source tree:

- `OfflineSpeechSession.run`: natural endpoint/deadline already releases the microphone
  before zero-padding / `finish()` / decoder tail, but had no event for that interval.
- `OfflineComposerHost`: `listening` previously stayed true until final/cancel/failure;
  callback ownership used target equality, not a particular attempt.
- `VoiceModeController.nextRound`: partial callbacks previously published LISTENING;
  system PREPARING and provider/offline capture-stop were not propagated.
- `AndroidDictation.onEndOfSpeech`: reports PREPARING with
  `detail = recognition_final_pending`; initial preparation has a different detail.
- `AndroidUniversalRecognition.DoubaoInput.finishCapture`: strictly releases audio,
  then closes the frame channel while the connection still waits for its final.

## Implementation

1. Add explicit `captureStopped()` to offline session and recognition event interfaces.
2. Offline natural endpoint/deadline posts this event **after successful microphone
   release and before any tail input / finish work**. A monotonic atomic flag suppresses
   a queued listening callback even while the old gate lease still exists for native
   cleanup. Cancellation and unconfirmed release do not emit a successful stop event.
3. Offline host clears only `listening` and changes the status to waiting for final;
   target, preview and final ownership remain. Per-attempt serial fencing also rejects
   old callbacks when a caller reuses the exact same target. Late listening cannot
   undo captureStopped. Partial remains preview-only.
4. UNIVERSAL maps only system `recognition_final_pending` PREPARING to captureStopped,
   forwards offline captureStopped, and emits Doubao captureStopped after strict audio
   close but before closing the frame channel. No end event is treated as a final.
5. Controller publishes WAITING_FOR_FINAL for the exact current round, with a monotonic
   round capture-stop flag. Partial changes transcript only, never capture phase.
   Old permit/round and post-final callbacks remain fenced. The existing recognition
   timer is not extended or replaced; Stop/close/drain/final-submit/receipt logic is
   unchanged. The panel has a label for the additional phase.
6. A small system-dictation factory injection permits testing the real adapter and
   AndroidDictation callback/lease path with a fake platform recognizer.

A system end-of-speech callback is software capture evidence, **not** proof that its
lease or recognizer has been destroyed: final results still own that recognizer until
existing strict final/cancel teardown. Offline/Doubao stop events follow actual input
release. None of these events establishes physical microphone behavior or audibility.

## MainActivity integration

Read-only inspection found composerListening already requires
`VoiceControlPhase.LISTENING` for UNIVERSAL and the host `listening` field for offline.
WAITING_FOR_FINAL and the updated offline state therefore project false without a
MainActivity hunk. No parent hunk is required by this patch.

## Regression test source added / updated

`OfflineSpeechContractsTest`:

- Real session + real host with a worker blocked in decoder `finish`: microphone is
  released, worker and decoder still live, target retained, listening false, no draft
  commit; the real final later commits once.
- Busy callback queue drained at tail entry, before lease retirement: old queued
  listening is suppressed and captureStopped is already available before finish.
- Same-target retry rejects old listening/stopped/partial/final/cancel/failure callbacks;
  late listening in the current stopped attempt cannot revive capture.
- Natural final queue includes captureStopped; timeout cases check stop-before-finish;
  queued release-failure test confirms no successful captureStopped is claimed.

`VoiceModeControllerTest`:

- Partial before ready preserves CONNECTING; stopped capture keeps a live input while
  waiting for final, and late partial/listening cannot restore LISTENING.
- Only the real final submits, once; stopped callbacks cannot overwrite SUBMITTING.
- Recognition deadline and user Stop retain existing close/drain behavior while waiting.
- Old stopped/listening callbacks are isolated across both new permits and new turns.

`AndroidUniversalRecognitionTest` (Robolectric SDK 28, fake recognizer only):

- Initial PREPARING is not a stop/start; ready then end-of-speech produces start/stop,
  without early destroy or final; later onResults still yields the actual final.
- Old recognizer callbacks cannot mutate a later input after lease retirement.

## Validation status and limitations

**Tests and build: NOT RUN / 未执行.** The implementation agent is restricted to
workspace file operations. No shell, device, live provider request, network session,
credentials, Git branch creation, push, or Git metadata operation was performed.

Static inspection checked all `OfflineSpeechSession.Events` and
`VoiceControlPorts.RecognitionEvents` implementations in the tree, plus phase consumers
(the exhaustive panel mapping is updated). This is not a compiler or test-pass claim.

The new tests do not open real JNI, a physical microphone, Android system recognition,
or a Doubao connection. Doubao's new stop dispatch is source-reviewed at the existing
strict close boundary; no new provider/device integration test is claimed. The parent
agent must run the focused tests/build and create/review the Git artifact from this
worktree (including this document and the new AndroidUniversalRecognitionTest file).
