"""Keep streaming question projection and default-avatar decoding off the UI thread."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
class StreamQuestionProjectionContract(unittest.TestCase):
    def test_controller_projection_never_reads_durable_payload(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ChatController.kt').read_text()
        projection = text.split('@Synchronized internal fun questionProjection(', 1)[1].split('private val toolLedger', 1)[0]
        self.assertNotIn('SQLiteRuntimeStore', projection)
        self.assertNotIn('database.', projection)
        self.assertIn('questionProjection(message.conversationId, message.runId, message.id, message.role, call, evidence)', projection)
        self.assertIn('evidence?.matches(conversation, run, message, role, call)', projection)
        self.assertIn('recoveryOwner == null', projection)
        self.assertIn('current.offer?.pending?.callId==call.id', projection)
    def test_worker_snapshot_shares_one_checkpoint_decode_for_receipts_and_questions(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/RetainedToolOutcomes.kt').read_text()
        self.assertEqual(1, text.count('SQLiteRuntimeStore.readQuestionCheckpoint('))
        self.assertIn('QuestionCheckpointEvidence.from(snapshot, recoveryOwner)', text)
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        self.assertIn('controller.questionWaiting(message)', host)
        self.assertIn('toolReceiptSnapshot?.questionEvidence(conversation.value, run.value, message.value)', host)
        self.assertIn('question.readOnly()', host)
    def test_display_and_authority_share_exact_question_identity_without_write_capability(self):
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        collect = host.split('if (receiptsReady) questionCards = buildMap {', 1)[1].split('else questionCards =', 1)[0]
        self.assertIn('val display = displayWindow', collect)
        self.assertIn('else display.rows.forEach', collect)
        self.assertIn('collect(row.conversationId, row.runId, segment.messageId, segment.role, segment.blocks)', collect)
        self.assertIn('controller.questionProjection(conversation, run, message, role, block.invocation, evidence)', collect)
        self.assertIn('put(owner to block.invocation, question.confirmed(questionCards[owner to block.invocation]))', collect)
        self.assertNotIn('database.', collect)
        self.assertEqual(2, host.count('questionCards[owner to block.invocation]?.identity?.storageKey'))
        dedicated = host.split('private fun DedicatedQuestion(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('val dedicatedKey = step.dedicatedKey ?: return false', dedicated)
        self.assertIn('value.identity.storageKey == dedicatedKey', dedicated)
        self.assertIn('active && !sidebarVisible && displayWindow == null && question.canAnswer', dedicated)

    def test_recovered_question_is_validated_before_explicit_terminal_workflow_disclosure(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/QuestionBarrierIntegrationTest.kt').read_text()
        recovery = text.split('private fun recovery(answer:QuestionAnswer?', 1)[1]
        self.assertLess(recovery.index('assertNotNull(projection)'), recovery.index('launch(f,'))
        disclosure = text.split('private fun openRecoveredWorkflow(', 1)[1].split('private fun recovery(', 1)[0]
        self.assertIn('when (header.fetchSemanticsNode().config[SemanticsProperties.StateDescription])', disclosure)
        self.assertIn('if (coveredByDrawer)', disclosure)
        self.assertIn('header.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }', disclosure)
        self.assertIn('else header.performClick()', disclosure)
        self.assertIn('openRecoveredWorkflow(coveredByDrawer=true)', disclosure)
        self.assertIn('"已展开" -> Unit', disclosure)
        self.assertIn('else -> fail("unexpected workflow disclosure state")', disclosure)
        expanded = 'header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))'
        self.assertLess(disclosure.index(expanded), disclosure.index('waitForCard(readOnly=true, savedAnswer=savedAnswer)'))
        self.assertIn('assertEquals(0,f.probe.opens.get());assertEquals(0,f.probe.nexts.get())', recovery)
        self.assertIn('openRecoveredWorkflow(savedAnswer=answer)', recovery)
        self.assertIn('recreate();openRecoveredWorkflow(savedAnswer=answer)', recovery)
    def test_recovered_saved_answer_keeps_complete_disabled_form(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/QuestionBarrierIntegrationTest.kt').read_text()
        helper = text.split('private fun waitForCard(', 1)[1].split('private fun submitAction', 1)[0]
        self.assertIn('savedAnswer:QuestionAnswer?=null', helper)
        self.assertIn('if (savedAnswer == null)', helper)
        for token in ('node("question-option:0").assertDoesNotExist()',
                      'node("question-option:1").assertDoesNotExist()',
                      'node("question-submit").assertDoesNotExist()',
                      'node("question-option:$index").assertIsNotEnabled()',
                      'if (id == selected) option.assertIsSelected() else option.assertIsNotSelected()',
                      'hasSetTextAction() and hasAnyAncestor(hasTestTag("question-card"))'):
            self.assertIn(token, helper)
        recovery = text.split('private fun recovery(answer:QuestionAnswer?', 1)[1]
        self.assertNotIn('已选择：', recovery)
        self.assertIn('node("question-option:0").assertTextContains("First route")', recovery)
        self.assertIn('node("question-option:1").assertTextContains("Second route")', recovery)
        handoff = text.split('private fun assertRecoveredQuestionSurvivesDrawerHandoff(', 1)[1].split('private fun recovery(', 1)[0]
        for token in ('repeat(2)', 'swipeLeft()', 'repeat(90)', 'ui.mainClock.advanceTimeByFrame()',
                      'value.positionInRoot.y, value.boundsInRoot.top',
                      'value.size.height.toFloat(), value.boundsInRoot.height',
                      'title.positionInRoot.y, title.boundsInRoot.top',
                      'title.size.height.toFloat(), title.boundsInRoot.height',
                      'assertThreeActions()', 'assertEquals(0,f.probe.opens.get());assertEquals(0,f.probe.nexts.get())'):
            self.assertIn(token, handoff)
    def test_long_output_paths_have_distinct_saved_disclosure_owners(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolOperationCardTest.kt').read_text()
        fixture = text.split('fun testLongOutputDisclosureAndHostSlotNeverReplayTools()', 1)[1].split('fun testRestrictedReceipts', 1)[0]
        self.assertIn('source(mixed, "long-output-$mixed")', fixture)
        self.assertIn('assertCountEquals(1)', fixture)
        self.assertIn('assertEquals(0, executions)', fixture)
    def test_default_bitmap_is_shared_sampled_and_decoded_only_on_io(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/components/AssistantAvatar.kt').read_text()
        composition, worker = text.split('internal object DefaultAssistantAvatar', 1)
        self.assertNotIn('decodeResource', composition)
        self.assertNotIn('Canvas(', text)
        self.assertIn('withContext(Dispatchers.IO)', worker)
        self.assertIn('mutex.withLock', worker)
        self.assertIn('prepared.value?.let { return@withLock it }', worker)
        self.assertIn('inSampleSize', worker)
if __name__ == '__main__': unittest.main()
