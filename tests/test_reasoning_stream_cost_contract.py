"""Guard the production cost boundaries; not device frame measurements."""
from pathlib import Path
import unittest

SRC = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android'


class ReasoningStreamCostContract(unittest.TestCase):
    def test_completed_snapshot_not_streaming_text_drives_off_thread_receipt_read(self):
        main = (SRC / 'MainActivity.kt').read_text()
        source = (SRC / 'ui/screens/chat/conversation/CompletionRead.kt').read_text()
        owner = main.split('@Composable private fun completionReadAction()', 1)[1].split('private fun completionPageReadable()', 1)[0]
        self.assertIn('message.status == RunStatus.COMPLETED', owner)
        self.assertIn('message.id.value to message.runId.value', owner)
        self.assertIn('CompletionReadOwner(id, generation, revision, completed)', owner)
        self.assertIn('rememberCompletionReceipt(owner)', owner)
        self.assertIn('chatActions(completionReadAction())', main)
        self.assertIn('onVisibleCompleted = onVisibleCompleted', main)
        self.assertIn('remember(owner) { mutableStateOf<Receipt?>(null) }', source)
        self.assertIn('LaunchedEffect(owner)', source)
        self.assertIn('withContext(Dispatchers.IO)', source)
        self.assertIn('catch (cancelled: CancellationException) { throw cancelled }', source)
        self.assertIn('(it.messageId to it.runId) in owner.completed', source)
        self.assertIn('if (owner == null) return@LaunchedEffect', source)
        self.assertNotIn('contentRevision', source)

    def test_ack_keeps_the_exact_receipt_and_two_measured_frames(self):
        main = (SRC / 'MainActivity.kt').read_text()
        read = main.split('@Composable private fun completionReadAction()', 1)[1].split('private fun completionPageReadable()', 1)[0]
        self.assertIn('it.ownerMessageId == expected.messageId && it.status == RunStatus.COMPLETED', read)
        self.assertIn('finalKey in visibleKeys', read)
        self.assertIn('acknowledgeCompletion(receipt, generation, revision)) receiptState.value = null', read)
        ack = main.split('private fun acknowledgeCompletion(', 1)[1].split('@Composable private fun DictationComposerEntry', 1)[0]
        for gate in ('completionPageReadable()', 'editorGeneration != generation', 'controller.dataRevision != revision', 'completionShown(receipt)'):
            self.assertIn(gate, ack)
        marker = (SRC / 'storage/ConversationCompletionMarkers.kt').read_text()
        self.assertIn('conversation_id=? AND message_id=? AND run_id=? AND token=?', marker)
        observer = (SRC / 'ui/screens/chat/conversation/ChatMessageList.kt').read_text().split('// Read confirmation observes', 1)[1].split('Box(modifier.fillMaxSize())', 1)[0]
        self.assertEqual(2, observer.count('withFrameNanos { }'))
        self.assertIn('completedVisibleTurns(visibleKeys, currentAssistants, readyTurns, revealedTurns)', observer)
        self.assertNotIn('currentTurns.filterIsInstance', observer)
        self.assertIn('val current = currentAssistants', observer)
        self.assertIn('revealedTurns[it.listKey] == it', observer)

    def test_timer_inputs_exclude_text_without_changing_domain_elapsed_arithmetic(self):
        source = (SRC / 'ui/screens/chat/conversation/ReportedReasoningCard.kt').read_text()
        self.assertIn('ReasoningElapsedTime(ownerKey, rememberReasoningTiming(reported)', source)
        self.assertIn('ReportedReasoning(it.itemId, "", it.elapsedMillis, it.activeSinceNanos)', source)
        self.assertIn('return remember(timing) { timing }', source)
        self.assertIn('checkNotNull(item.elapsedAt(now))', source)
        self.assertIn('delay(100L)', source)
        self.assertIn('rememberReasoningElapsed(reported, live, nanoTime)', source)

    def test_reveal_traversal_stays_at_active_leaf_and_preserves_node_budgets(self):
        source = (SRC / 'ui/screens/chat/conversation/MessageMarkdown.kt').read_text()
        host = source.split('private fun MarkdownBlocks(', 1)[1].split('private fun MarkdownBlockBody(', 1)[0]
        self.assertIn('for ((index, block) in blocks.withIndex())', host)
        self.assertNotIn('visibility.value', host)
        self.assertIn('rememberMarkdownVisibility(visibility, offset, lengths[index])', host)
        self.assertIn('for ((index, item) in block.items.withIndex())', source)
        self.assertIn('derivedStateOf { visibility.value > 0 }', source)
        self.assertIn('if (!shown.value) return', source)
        self.assertIn('rememberMarkdownVisibility(visibility, offset, lengths[index])', source)
        self.assertIn('markdownBlocksLength(it.blocks).coerceAtLeast(1)', source)
        helper = (SRC / 'ui/screens/chat/conversation/MarkdownVisibility.kt').read_text()
        self.assertIn('(parent.value - start).coerceIn(0, length)', helper)
        self.assertNotIn('return@forEachIndexed', source)

    def test_context_strings_are_remembered_by_numbers_and_locale(self):
        helper = (SRC / 'ui/screens/chat/conversation/ChatContextUsage.kt').read_text()
        self.assertIn('remember(usage.inputTokens, usage.windowTokens, locale)', helper)
        self.assertIn('usage.label(locale) to usage.tokensLabel(locale)', helper)
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('rememberChatContextUsageLabels(contextUsage, usageLocale)', main)
        self.assertIn('contextUsageText = contextUsageLabels.first', main)
        self.assertIn('contextTokensText = contextUsageLabels.second', main)


if __name__ == '__main__':
    unittest.main()
