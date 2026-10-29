"""Wiring guards for the original live-draft/explicit-stop semantics; JVM and device tests cover behavior."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def region(text, start, end):
    assert start in text, f'missing production entry: {start}'
    tail = text.split(start, 1)[1]
    assert end in tail, f'missing production boundary: {end}'
    return tail.split(end, 1)[0]


class DictationRetainedDraftContractTest(unittest.TestCase):
    def test_every_host_wires_live_hypotheses_to_the_owned_save_path(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertEqual(3, main.count('applyPartialDraft = ::applyRecognizedPartial'))
        for file in ('voice/ComposerDictationHost.kt', 'voice/offline/OfflineComposerHost.kt', 'ActivityRemoteVoiceHost.kt'):
            source = (SRC / file).read_text()
            self.assertIn('applyPartialDraft(target,', source)
        partial = region(main, 'private fun applyRecognizedPartial(', 'private fun applyRecognizedDraft(')
        for fragment in ('voiceEditorCurrent(target)', 'captured.accept(projection.text, recognized)',
                         'applyingDictationDraft = true', 'try { updateDraft(next) } finally { applyingDictationDraft = false }',
                         'check(draft == next)', 'toSource(captured.cursor)'):
            self.assertIn(fragment, partial)
        for forbidden in ('onSend(', 'submit(', 'sendText('):
            self.assertNotIn(forbidden, partial)

    def test_only_recognition_owned_mutations_can_migrate_the_captured_revision(self):
        main = (SRC / 'MainActivity.kt').read_text()
        current = region(main, 'private fun voiceEditorCurrent(', 'private fun captureDictationDraft(')
        self.assertIn('target.conversationId == selected?.id?.value', current)
        self.assertIn('target.editorGeneration == editorGeneration', current)
        self.assertIn('dictationDraft?.first == target && dictationDraftRevision == draftRevision', current)
        revision = region(main, 'private var draftRevision =', 'private data class PendingTextSend(')
        self.assertIn('if (!applyingDictationDraft) { invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate() }', revision)
        self.assertIn('if (applyingDictationDraft) dictationDraftRevision = value', revision)
        save = region(main, 'private fun updateDraft(', 'internal fun createNewConversation(')
        self.assertIn('if (!applyingDictationDraft) { dictationHost.invalidate(); offlineHost.invalidate() }', save)
        self.assertIn('controller.database.draft(id, value)', save)
        self.assertIn('draftRevision++', save)
        retire = region(main, 'private fun invalidatePreparedSpeech()', 'private fun invalidateConfiguredVoice()')
        self.assertIn('dictationDraft = null', retire)
        self.assertIn('dictationDraftRevision = null', retire)

    def test_repeat_click_stops_before_recapturing_the_current_text(self):
        main = (SRC / 'MainActivity.kt').read_text()
        offline = region(main, 'private fun startOfflineDictation()', 'private val imageHost')
        self.assertIn('offlineHost.state.value.target', offline)
        self.assertLess(offline.index('offlineHost.state.value.target'), offline.index('captureDictationDraft(target)'))
        self.assertLess(offline.index('return'), offline.index('captureDictationDraft(target)'))
        remote = region(main, '"DOUBAO" -> {\n                            val ownedTarget', 'else -> toast("听写未配置')
        self.assertIn('if (ownedTarget != null) remoteVoice.dictate(ownedTarget)', remote)
        self.assertNotIn('captureDictationDraft(ownedTarget)', remote)
        host = (SRC / 'ActivityRemoteVoiceHost.kt').read_text()
        repeat = region(host, 'fun repeatClick(', 'fun end()')
        self.assertIn('if (phase != Phase.ENDED) cancel()', repeat)
        self.assertNotIn('finishInput()', host)

    def test_success_is_silent_but_permission_and_failure_notices_remain(self):
        for file in ('voice/ComposerDictationHost.kt', 'voice/offline/OfflineComposerHost.kt', 'ActivityRemoteVoiceHost.kt'):
            text = (SRC / file).read_text()
            self.assertNotIn('已填入草稿，请检查后手动发送', text)
            self.assertNotIn('已填入捕获的草稿，请检查后手动发送', text)
            self.assertIn('权限', text)
            self.assertIn('失败', text)
        remote = (SRC / 'ActivityRemoteVoiceHost.kt').read_text()
        status = region(remote, 'fun finalDraftStatus(', '\n    }')
        self.assertIn('commit()\n                ""', status)
        notice = (SRC / 'ui/components/DictationStatusNotice.kt').read_text()
        self.assertIn('Toast.makeText', notice)

    def test_regressions_cover_stopping_restarting_revision_and_persisted_text(self):
        unit = (ROOT / 'app/src/test/kotlin/app/daiyu/android/voice/offline/OfflineSpeechContractsTest.kt').read_text()
        for name in ('stopKeepsLiveTextAndNextAttemptAppendsAtTheCurrentCursor',
                     'cancellationFailureAndEmptyFinalKeepAlreadyRecognizedText',
                     'changedEditorAndFailedPartialSaveRetireTheAttempt'):
            self.assertIn('fun ' + name, unit)
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/DictationActivityTest.kt').read_text()
        for name in ('testStopThenRecordAgainKeepsBothRoundsAndCurrentCursor',
                     'testCancellingLivePartialKeepsRecognizedDraftAndRejectsLateFinal',
                     'testMovingCursorKeepsRecognizedTextAndRetiresTheRecorder'):
            self.assertIn('fun ' + name, device)
        callback = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/MainVoiceProductionCallbackTest.kt').read_text()
        self.assertIn('testOwnedPartialsAdvanceRevisionButManualEditsRetireEveryOldCallback', callback)


if __name__ == '__main__':
    unittest.main()
