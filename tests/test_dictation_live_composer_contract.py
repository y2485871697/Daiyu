"""Real ASR hypotheses render inside the composer without bypassing final/owner fences."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class DictationLiveComposerContractTest(unittest.TestCase):
    def test_preview_projection_is_pure_and_keeps_the_original_final_chain(self):
        main = (SRC / 'MainActivity.kt').read_text()
        projection = main.split('private fun currentDictationProjection(', 1)[1].split('private fun applyRecognizedDraft(', 1)[0]
        for text in ('voiceEditorCurrent(target)', 'inlineMessageEditor != null', 'captured.second.preview(',
                     'original.edit(preview.text)', 'ComposerReferences(next).toSource(preview.cursor)'):
            self.assertIn(text, projection)
        for text in ('updateDraft(', '.accept(', 'publishChat(', 'database.'):
            self.assertNotIn(text, projection)
        self.assertIn('captured.accept(projection.text, recognized)', main)
        self.assertIn('captureDictationDraft(target)\n        offlineHost.start(target,', main)

    def test_all_real_partial_sources_feed_the_actual_composer(self):
        main = (SRC / 'MainActivity.kt').read_text()
        render = main.split('@Composable private fun projectedVoiceComposer()', 1)[1].split('private fun composerListening(', 1)[0]
        for text in ('remoteVoice.preview.collectAsStateWithLifecycle()',
                     'currentDictationProjection(offline, system, remotePreview)', 'text = projection?.text',
                     'selectionStart = projection?.cursor', 'dictationPreview = projection != null'):
            self.assertIn(text, render)
        remote = (SRC / 'ActivityRemoteVoiceHost.kt').read_text()
        self.assertIn('if (!result.final) mutablePreview.value = target to result.text', remote)
        self.assertGreaterEqual(remote.count('mutablePreview.value = null'), 2)

    def test_real_edits_and_stale_preview_callbacks_are_fenced(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('currentDictationProjection() != dictationProjection', main)
        self.assertIn('(dictationProjection?.text ?: editor?.text ?: draft) != expected', main)
        self.assertIn('selectionStart = dictationProjection.originalOffset(start)', main)
        self.assertIn('selectionEnd = dictationProjection.originalOffset(end)', main)
        self.assertIn('invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate()\n                    updateDraft(next)', main)
        composer = (SRC / 'ui/screens/chat/conversation/ChatComposer.kt').read_text()
        self.assertIn('if (!state.dictationPreview && references.references.isEmpty() && projected.references.isEmpty())', composer)
        models = (SRC / 'ui/screens/chat/conversation/ChatScreenModels.kt').read_text()
        self.assertIn('!dictationPreview && when (phase)', models)
        self.assertIn('if (currentDictationProjection() != null) return@send', main)
        self.assertIn('if (currentDictationProjection() != null) return@supplement', main)

    def test_normal_dictation_has_no_bottom_diagnostic_rows(self):
        main = (SRC / 'MainActivity.kt').read_text()
        entry = main.split('@Composable private fun DictationComposerEntry()', 1)[1].split('\n    }', 1)[0]
        for text in ('if (engineeringUi && pack.enabled)', 'if (engineeringUi && offline.preview.isNotEmpty())',
                     'if (engineeringUi && state.preview.isNotEmpty())', 'if (engineeringUi && state.listening)'):
            self.assertIn(text, entry)
        self.assertIn('engineeringUi, tag = "offline-speech-status"', entry)
        self.assertNotIn('basic.Text(text = offline.status', entry)
        notice = (SRC / 'ui/components/DictationStatusNotice.kt').read_text()
        self.assertIn('if (engineeringUi && state.status.isNotEmpty())', notice)
        self.assertIn('Toast.LENGTH_SHORT', notice)


    def test_device_regressions_distinguish_visible_partial_from_saved_draft(self):
        offline = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/DictationActivityTest.kt').read_text()
        for fragment in ('testLivePartialsRenderInComposerWithoutPersistingOrAppendingAndFinalCommitsOnce',
                         'testEditingPartialBackToOriginalStillRetiresRecognition',
                         'testStalePartialEditAndSendCallbacksCannotActOnTheNextHypothesis',
                         'assertEquals("before OLD after", fixture.database.draft())'):
            self.assertIn(fragment, offline)
        callback = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/MainVoiceProductionCallbackTest.kt').read_text()
        self.assertIn('testRemotePartialsRenderInComposerAndFinalPreservesReferencesWithoutNetworking', callback)
        self.assertIn('testSystemPartialStateRendersInComposerWithoutPromotingItToFinal', callback)


if __name__ == '__main__':
    unittest.main()
