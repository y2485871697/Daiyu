"""Source wiring for history AST preparation; device execution stays in the approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
BASE = APP / 'ui/screens/chat/conversation'


class MarkdownEntryContract(unittest.TestCase):
    def test_runtime_recovery_does_not_read_or_format_selected_history(self):
        source = (APP / 'ChatController.kt').read_text().split('init {executor.execute {', 1)[1].split('\n    }}', 1)[0]
        self.assertIn('runtime.recoverPending', source)
        self.assertIn('rememberQuestions(history)', source)
        self.assertIn('recoverDeletedModelBindings()', source)
        self.assertLess(source.index('runtime.recoverPending'), source.index('ready=true;notifyChanged()'))
        self.assertNotIn('database.messages(', source)
        self.assertNotIn('prepareRestoredMarkdown(', source)
        self.assertNotIn('database.save(', source)
        self.assertNotIn('model.open(', source)

    def test_initial_history_uses_the_existing_cancelable_background_selection(self):
        source = (APP / 'MainActivity.kt').read_text()
        refresh = source.split('    private fun refresh() {', 1)[1]
        initial = refresh.split('            if (!selectionReady) {', 1)[1].split('            if (modelDeletionRevision', 1)[0]
        self.assertIn('if (!voiceForeground || paneSelectionRequest != null) return@guard', initial)
        self.assertIn('if (saved == null) select(null) else {', initial)
        self.assertIn('selectPaneConversation(saved.id.value)', initial)
        self.assertIn('publishChat()', initial)
        self.assertIn('controller.ready && selectionReady && controller.problem', source)
        self.assertNotIn('database.messages(', initial)
        selection = source.split('    private fun selectPaneConversation(id: String)', 1)[1].split('    private fun retainedConversation', 1)[0]
        io = selection.split('withContext(Dispatchers.IO) {', 1)[1].split('                    if (paneSelectionRequest', 1)[0]
        self.assertIn('val snapshot = read(id)', io)
        self.assertIn('prepareRestoredMarkdown(snapshot.messages.map { it.second })', io)
        self.assertIn('context.ensureActive()', io)
        self.assertLess(selection.index('prepareRestoredMarkdown('), selection.index('select(snapshot.conversation, snapshot)'))
        self.assertIn('if (revision != currentPaneRevision()) continue', selection)
        self.assertIn('paneSelectionRequest !== request || !activityUsable()', selection)

    def test_cancelled_initial_selection_resumes_and_background_notifications_do_not_restart_it(self):
        source = (APP / 'MainActivity.kt').read_text()
        resume = source.split('    override fun onResume() {', 1)[1].split('    private fun applyPreferredRefreshRate()', 1)[0]
        self.assertIn('if (selectionReady) publishChat() else refresh()', resume)
        self.assertLess(resume.index('voiceForeground = true'), resume.index('else refresh()'))
        pause = source.split('    override fun onPause() {', 1)[1].split('    override fun onStop()', 1)[0]
        self.assertIn('cancelPaneConversationSelection()', pause)
        tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/RestoredMarkdownActivityTest.kt').read_text()
        self.assertIn('testPausedInitialReadRestartsOnResumeWithoutAnotherControllerEvent', tests)
        self.assertIn('testFreshActivityRestoresStoppedLargeScreenHistoryWithoutReplay', tests)
        self.assertIn('realPauseResume(activity, atPause = {', tests)
        self.assertIn('assertTrue(job.isCancelled)', tests)
        self.assertIn('assertEquals(1_814_960, encoded.length)', tests)
        self.assertIn('RunStatus.STOPPED', tests)
        self.assertIn('assertEquals(before, rawFields())', tests)
        self.assertIn('assertEquals(stopped.historySegments, restored.historySegments)', tests)

    def test_restoration_preserves_source_syntax_history_and_original_cache_budget(self):
        source = (BASE / 'RestoredMarkdown.kt').read_text()
        for token in ['MessageHistory.expand(messages)', 'it.role == Message.ASSISTANT_ROLE',
                      'it.status != RunStatus.RUNNING', 'filterIsInstance<MessageBlock.Text>()',
                      'summaries + texts', 'cache.preparationSources(restoredMarkdownSources(messages))',
                      'MarkdownParser.parse(source, checkActive)', 'checkActive()\n    synchronized(cache) { prepared.forEach(cache::put) }']:
            self.assertIn(token, source)
        self.assertNotIn('texts.joinToString', source)
        cache = (BASE / 'MarkdownProjectionCache.kt').read_text()
        for token in ['sources.asReversed().distinct()', 'selected.size >= maxEntries',
                      'source.length.toLong() + chars > maxSourceChars', 'return selected.asReversed()']:
            self.assertIn(token, cache)

    def test_first_composition_composes_only_exact_ready_source_and_parser(self):
        cache = (BASE / 'MarkdownProjectionCache.kt').read_text().split('fun getPrepared(', 1)[1].split('fun preparationSources', 1)[0]
        self.assertIn('sourceProjection(source, parser) ?: return null', cache)
        self.assertIn('MarkdownProjection(sources.toList(), blocks, parser, displayText)', cache)
        ui = (BASE / 'MessageMarkdown.kt').read_text().split('internal fun rememberMarkdownProjection', 1)[1].split('internal fun ProjectedMessageMarkdown', 1)[0]
        self.assertIn('markdownProjectionCache.getPrepared(sources, parser)', ui)
        self.assertNotIn('MarkdownParser.parse', ui)
        self.assertNotIn('runBlocking', ui)

    def test_bulk_and_interactive_parsing_have_separate_bounded_background_lanes(self):
        source = (BASE / 'MarkdownProjection.kt').read_text()
        self.assertIn('INTERACTIVE_MARKDOWN_CHARS = 4_096L', source)
        self.assertEqual(2, source.count('Dispatchers.Default.limitedParallelism(1)'))
        self.assertIn('sources.sumOf { it.length.toLong() } <= INTERACTIVE_MARKDOWN_CHARS', source)
        self.assertIn('interactiveMarkdownDispatcher else markdownDispatcher', source)
        self.assertIn('withContext(dispatcher)', source)
        self.assertIn('cache.get(sources, parser) ?: cache.getPrepared(sources, parser)', source)
        self.assertIn('if (predecessor == null) cached?.let { return it }', source)
        self.assertIn('currentCoroutineContext().ensureActive()\n    cache.put(projection)', source)

    def test_regressions_cover_real_recovery_first_composition_and_source_integrity(self):
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/RestoredMarkdownActivityTest.kt').read_text()
        self.assertIn('beforeController = { database, preferences ->', android)
        self.assertIn('history recovery ready before Activity mounts', android)
        self.assertIn('assertEquals(original, fixture.database.messages(conversation.id))', android)
        self.assertIn('Ready must not wait for historical formatting', android)
        self.assertIn('testSlowInitialHistoryReadDoesNotBlockMainOrRestartOnRefresh', android)
        self.assertIn('assertEquals(1, reads.get())', android)
        self.assertIn('assertEquals(0, fixture.opens.get())', android)
        self.assertIn('catch (failure: Throwable)', android)
        self.assertIn('result.close()', android)
        self.assertNotIn('prepareRestoredMarkdown(', android)
        self.assertNotIn('setContent', android)
        self.assertNotIn('cache.put', android)
        unit = (ROOT / 'app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/MarkdownProjectionTest.kt').read_text()
        self.assertIn('shortColdProjectionCompletesWhileUnrelatedBulkParseIsBlocked', unit)
        self.assertLess(unit.index('val latest = kotlinx.coroutines.withTimeout'), unit.index('finally { release.countDown(); old.cancelAndJoin() }', unit.index('val latest = kotlinx.coroutines.withTimeout')))
        mixed = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MessageMarkdownTest.kt').read_text()
        self.assertIn('testPreparedMixedHistoryHasExactProjectionInItsFirstComposition', mixed)
        self.assertIn('compositions.all { it?.sources == sources && it.parser === parser }', mixed)


if __name__ == '__main__':
    unittest.main()
