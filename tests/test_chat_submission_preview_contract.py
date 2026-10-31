from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = APP / 'ui/screens/chat/conversation'


class ChatSubmissionPreviewContractTest(unittest.TestCase):
    def test_preview_is_screen_projection_not_authoritative_rows(self):
        screen = (CHAT / 'ChatScreen.kt').read_text()
        self.assertIn('submissionTurns(state.turns, state.submissionPreview)', screen)
        self.assertIn('ChatMessageList(displayedTurns, actions', screen)
        preview = (CHAT / 'ChatSubmissionPreview.kt').read_text()
        self.assertIn('ChatTurnUi.User(preview.key, text, pending = true)', preview)
        self.assertIn('turns.take(assistantIndex) + pending + turns.drop(assistantIndex)', preview)
        self.assertIn('turns.take(index)', preview)
        for forbidden in ('database', 'Message(', 'RunStatus.COMPLETED', 'executor', 'controller'):
            self.assertNotIn(forbidden, preview)

    def test_preview_is_retired_by_exact_receipt_not_text_equality(self):
        preview = (CHAT / 'ChatSubmissionPreview.kt').read_text()
        self.assertIn('val settledId = preview.settledUserId ?: preview.settledAssistantId', preview)
        self.assertIn('turns.any { it.id == settledId }', preview)
        self.assertNotIn('it.text ==', preview)
        main = (APP / 'MainActivity.kt').read_text()
        self.assertIn('settledAssistantId = assistant.id.value', main)
        self.assertIn('pending.editorOwner', main)
        self.assertIn('preview.first == selected?.id', main)

    def test_pending_user_is_not_an_action_target(self):
        source = (CHAT / 'ChatMessageList.kt').read_text().split('internal fun UserTurn(', 1)[1].split('internal fun AssistantTurn(', 1)[0]
        self.assertIn('if (!turn.pending) showMenu = true', source)
        self.assertIn('if (!turn.pending && actions.editingMessageKey != turn.listKey) MessageActionBar(', source)
        self.assertIn('Modifier.testTag("chat-send-preview")', source)

    def test_regeneration_preview_is_admission_scoped_and_rolls_back(self):
        controller = (APP / 'ChatController.kt').read_text()
        action = controller.split('internal fun messageAction(', 1)[1].split('internal fun skillManagement()', 1)[0]
        self.assertLess(action.index('if (!idleAvailable()'), action.index('regenerationPreview = RegenerationPreview(target)'))
        self.assertLess(action.index('regenerationPreview = RegenerationPreview(target)'), action.index('executor.execute'))
        self.assertIn('acceptedAssistantId = token?.admittedVoiceOwner?.id?.value', action)
        self.assertIn('regenerationPreview?.target === target', action)
        self.assertIn('RegenerationSendOutcome.ACCEPTED && acceptedAssistantId != null', action)
        self.assertIn('regenerationPreview?.copy(assistantId = acceptedAssistantId) else null', action)

    def test_real_activity_tests_hold_before_commit_and_keep_zero_open_oracle(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatSubmissionActivityTest.kt').read_text()
        for oracle in ('preparing.await(5, TimeUnit.SECONDS)', 'assertEquals(original, fixture.database.messages(conversation.id))',
                       'assertEquals(0, fixture.opens.get())', 'assertNull(fixture.controller.active)',
                       'testRegenerationHidesReplyWhilePreparingAndRestoresItOnRejection',
                       'testAcceptedRegenerationReplacesPreviewOnlyAfterExactNewReplyIsPublished'):
            self.assertIn(oracle, source)


if __name__ == "__main__":
    unittest.main()
