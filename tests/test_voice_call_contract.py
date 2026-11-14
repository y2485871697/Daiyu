"""Targeted wiring contracts; call state behavior is covered by JVM tests."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
class VoiceCallContractTest(unittest.TestCase):
    def test_voice_uses_temporary_owner_without_editor_mutation(self):
        main = (SRC / "MainActivity.kt").read_text()
        send = main.split("submit = { turn, text, result ->", 1)[1].split("observe =", 1)[0]
        self.assertIn("voiceCallConversation", send)
        self.assertIn("voiceConversation = true", send)
        self.assertNotIn("updateDraft(", send)
        self.assertIn('仅对话前可更改', main)
        self.assertIn('voiceCallModelName = ticket.record.id', main)
        self.assertIn('voiceCallPresentation.begin(voiceCallAnchor', main)
    def test_temporary_storage_is_excluded_from_normal_history_and_backups(self):
        db = (SRC / 'storage/ConversationDatabase.kt').read_text()
        self.assertIn("id NOT GLOB 'voice-call-*'", db)
        self.assertIn("conversation_id NOT GLOB 'voice-call-*'", db)
        controller = (SRC / 'ChatController.kt').read_text()
        self.assertGreaterEqual(controller.count('VoiceCallTempFiles.recover(application.cacheDir)'), 2)
        self.assertIn('database.delete(owner)', controller)
        self.assertIn('blobs.forConversation', (SRC / 'context/ContextService.kt').read_text())
    def test_background_call_keeps_service_but_task_removal_hangs_up(self):
        main = (SRC / 'MainActivity.kt').read_text()
        for method, end in [('onPause()', 'override fun onStop'), ('onStop()', 'override fun onDestroy')]:
            body = main.split('override fun ' + method, 1)[1].split(end, 1)[0]
            self.assertIn('pauseNonCallVoice()', body)
            self.assertNotIn('invalidateConfiguredVoice()', body)
        service = (SRC / 'voice/control/VoiceCallService.kt').read_text()
        self.assertIn('onTaskRemoved(rootIntent: Intent?) { end()', service)
        self.assertIn('START_NOT_STICKY', service)
    def test_model_output_is_text_only_and_non_search_device_tools_are_not_exposed(self):
        controller = (SRC / 'ChatController.kt').read_text()
        self.assertIn('requestOptions.copy(voiceTextOnly = true)', controller)
        self.assertIn('allowedToolNames = if (voiceConversation) emptySet() else null', controller)
        self.assertIn('followChatModelWindow = voiceConversation', controller)
        self.assertIn('!voiceConversation && conversationChildrenEnabled', controller)
    def test_compaction_success_only_after_durable_application(self):
        runtime = (SRC / 'context/ProductionRunContext.kt').read_text()
        candidate = runtime.split('override fun compactRequests', 1)[1]
        self.assertNotIn('succeeded = true', candidate)
        self.assertIn('override fun compactionApplied()', runtime)
        runner = (ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain/ChatRunner.kt').read_text()
        self.assertIn('applied = true\n                runtime?.compactionApplied()', runner)
    def test_caption_and_completion_state_live_above_presentation_branch(self):
        overlay = (SRC / 'ui/screens/chat/conversation/DoubaoCallOverlay.kt').read_text()
        session = overlay.split('private fun DoubaoCallSession(', 1)[1].split('private fun callClock', 1)[0]
        self.assertIn('val captionScroll = rememberScrollState()', session)
        self.assertIn('val compressionState = rememberVoiceCallCompressionState', session)
        self.assertIn('captionScroll = captionScroll', session)
if __name__ == '__main__':
    unittest.main()
