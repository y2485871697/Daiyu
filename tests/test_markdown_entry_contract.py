"""Source wiring for history AST preparation; device execution stays in the approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
BASE = APP / 'ui/screens/chat/conversation'


class MarkdownEntryContract(unittest.TestCase):
    def test_recovery_prepares_selected_history_on_worker_before_publishing_ready(self):
        source = (APP / 'ChatController.kt').read_text().split('init {executor.execute {', 1)[1].split('internal val providers', 1)[0]
        self.assertIn('preferences.getString("selected", null)', source)
        self.assertIn('database.messages(ConversationId(selected)).map { it.second }', source)
        self.assertIn('prepareRestoredMarkdown(messages)', source)
        self.assertIn('if (synchronized(this) { closed }) throw kotlinx.coroutines.CancellationException', source)
        self.assertLess(source.index('prepareRestoredMarkdown(messages)'), source.index('ready=true;notifyChanged()'))
        self.assertNotIn('database.save(', source)
        self.assertNotIn('model.open(', source)

    def test_restoration_preserves_source_syntax_history_and_original_cache_budget(self):
        source = (BASE / 'RestoredMarkdown.kt').read_text()
        for token in ['MessageHistory.expand(messages)', 'it.role == Message.ASSISTANT_ROLE',
                      'it.status != RunStatus.RUNNING', 'filterIsInstance<MessageBlock.Text>()',
                      'texts + texts.joinToString("\\n\\n")', 'cache.preparationSources(restoredMarkdownSources(messages))',
                      'MarkdownParser.parse(source, checkActive)', 'checkActive()\n        cache.put(']:
            self.assertIn(token, source)
        cache = (BASE / 'MarkdownProjectionCache.kt').read_text()
        for token in ['sources.asReversed().distinct()', 'selected.size >= maxEntries',
                      'source.length.toLong() + chars > maxSourceChars', 'return selected.asReversed()']:
            self.assertIn(token, cache)

    def test_first_composition_composes_only_exact_ready_source_and_parser(self):
        cache = (BASE / 'MarkdownProjectionCache.kt').read_text().split('fun getPrepared(', 1)[1].split('fun preparationSources', 1)[0]
        self.assertIn('getBlocks(source, parser) ?: return null', cache)
        self.assertIn('MarkdownProjection(sources.toList(), blocks, parser)', cache)
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
        self.assertIn('cache.getPrepared(sources, parser)?.let { return it }', source)
        self.assertIn('currentCoroutineContext().ensureActive()\n    cache.put(projection)', source)

    def test_regressions_cover_real_recovery_first_composition_and_source_integrity(self):
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/RestoredMarkdownActivityTest.kt').read_text()
        self.assertIn('beforeController = { database, preferences ->', android)
        self.assertIn('history recovery ready before Activity mounts', android)
        self.assertIn('assertEquals(original, fixture.database.messages(conversation.id))', android)
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
