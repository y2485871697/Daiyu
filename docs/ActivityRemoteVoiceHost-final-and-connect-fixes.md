# ActivityRemoteVoiceHost: final commit and connecting-click fixes

Scope: only `ActivityRemoteVoiceHost.kt` and its dedicated host-policy test source.
No transport, audio owner gate, controller, settings panel or server behavior is added.

## Final transcript

The host still takes `(DictationDraftTarget, String) -> Unit`. A final transcript first
completes the audio attempt, then runs the current-editor check and the draft callback
inside a local exception boundary. Only normal callback return reports
`听写已填入草稿，请检查后手动发送`. A rejected commit reports
`听写未填入草稿，原草稿已保留；请手动重试`; it does not retry, clear the draft,
or attempt a compensating write. Blank final text also leaves the draft alone.

The integrating callback must throw when it cannot confirm the update. In particular,
Main's planned post-update `check(draft == text)` is necessary because the unchanged
Unit contract cannot distinguish a silently rejected update from a successful one.
The host itself cannot roll back an arbitrary callback that mutates the editor and
then throws; the intended rejection case is an update blocked before mutation.

## Repeated click

A local input phase distinguishes CONNECTING, CAPTURING, FINISHING and ENDED.
A repeat click while CONNECTING invalidates/cancels the attempt and explicitly says
that connecting was cancelled and the draft was unchanged. It never calls
`finishInput()` on that attempt. The existing `current()` fence, which includes the
ENDED flag and attempt identity, still precedes `audio.open()` for a late connection.
Only CAPTURING may transition to FINISHING and drain/close capture input. Further
clicks while waiting for the final result are no-ops. Ownership continues to use the
existing `ActivityVoiceAudio.gate` exclusively.

## Deterministic tests and limits

`app/src/test/kotlin/app/daiyu/android/ActivityRemoteVoiceHostTest.kt` tests the exact
local helpers used by the host: rejected/confirmed Unit commit, stale editor, blank
final, cancel-during-connect with a late-open fence, one-time capture finish and
invalid finish phases. These are policy tests, not full coroutine/transport or
physical-audio evidence. They do not instantiate Android audio or contact a service.

This implementation agent did not run a shell, build or tests. The main integration
agent must compile/run the dedicated tests and verify its Main-side admission and
post-update checks separately.
