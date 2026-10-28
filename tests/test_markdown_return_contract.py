"""Source wiring only; Kotlin cache and first-frame rendering regressions are deferred."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation"


class MarkdownReturnContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ui = (BASE / "MessageMarkdown.kt").read_text()
        cls.projection = (BASE / "MarkdownProjection.kt").read_text()
        cls.cache = (BASE / "MarkdownProjectionCache.kt").read_text()

    def test_first_composition_uses_exact_cached_ast_before_producer_runs(self):
        ui = self.ui.split("internal fun rememberMarkdownProjection(", 1)[1].split("\n@Composable", 1)[0]
        self.assertIn("markdownProjectionCache.get(sources, parser)", ui)
        self.assertIn("produceState<MarkdownProjection?>(cached, parserIdentity)", ui)
        self.assertIn("collectMarkdownProjections(snapshotFlow { requested }, parser) { value = it }", ui)
        self.assertIn("return cached ?: completed?.takeIf { it.parser === parser && isMarkdownAppend(it.sources, sources) }", ui)
        self.assertNotIn("MarkdownParser.parse", ui)
        self.assertNotIn("runBlocking", ui)
        self.assertIn("remember(sources, parserIdentity)", ui)
        self.assertIn("remember(MarkdownParserIdentity(parser))", self.ui)

    def test_cache_has_source_parser_identity_and_two_memory_limits(self):
        for token in ["maxEntries: Int = 64", "maxSourceChars: Int = 262_144", "parser === other.parser",
                      "private data class Key(val sources: List<String>, val parser: MarkdownParserIdentity)",
                      "System.identityHashCode(parser)", "@Synchronized",
                      "LinkedHashMap<Key, Entry>(16, .75f, true)", "it.length.toLong()",
                      "if (chars > maxSourceChars) return", "entries.remove(key)",
                      "entries.size > maxEntries || sourceChars > maxSourceChars", "iterator.remove()"]:
            self.assertIn(token, self.cache)
        for token in ["Context", "View", "UriHandler", "AnnotatedString", "SharedPreferences", "File("]:
            self.assertNotIn(token, self.cache)

    def test_only_successful_active_background_parse_is_cached(self):
        self.assertIn("internal class MarkdownProjection(", self.projection)
        self.assertNotIn("data class MarkdownProjection(", self.projection)
        self.assertIn("Dispatchers.Default.limitedParallelism(1)", self.projection)
        self.assertIn("cache.get(sources, parser)?.let { return it }", self.projection)
        self.assertIn("withContext(dispatcher)", self.projection)
        self.assertIn("parser(source)", self.projection)
        self.assertIn("currentCoroutineContext().ensureActive()\n    cache.put(projection)", self.projection)
        self.assertLess(self.projection.index("parser(source)"), self.projection.index("cache.put(projection)"))
        self.assertNotIn("catch (", self.projection)

    def test_uncached_streaming_geometry_and_current_readiness_are_not_replaced_by_plaintext(self):
        for token in ["markdownBodySnapshot(text, blocks, published)", "if (body == null) MarkdownBodyLoading(modifier, text.isNotBlank())",
                      "ParsedMessageMarkdown(body.text, body.blocks, modifier, visibleCharacters, generationComplete && body.text == text)",
                      "else previous?.takeIf { text.startsWith(it.text) }"]:
            self.assertIn(token, self.ui + self.projection)
        self.assertIn("messageBodyStyle()", self.ui)
        self.assertIn("remember(uriHandler, feedbackView)", self.ui)
        self.assertNotIn("rememberSaveable", self.cache)

    def test_kotlin_sources_cover_limits_identity_and_cancelled_work(self):
        test = (ROOT / "app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/MarkdownProjectionCacheTest.kt").read_text()
        for token in ["exactCompletedProjectionReturnsWithoutReparsing", "parserIdentityDoesNotBorrowAnEqualParsersAst",
                      "entryLimitEvictsLeastRecentlyUsedProjection", "sourceBudgetCountsReplacementOnceAndRejectsOversizedProjection",
                      "retainedSourceListIsASnapshot", "cancelledOrFailedParseCannotPublishIntoCache"]:
            self.assertIn(token, test)

    def test_android_sources_hold_worker_and_assert_first_return_composition(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MessageMarkdownTest.kt").read_text()
        for token in ["testRemountUsesCachedProjectionInFirstCompositionWhileParserWorkerIsBlocked",
                      "firstCompositions.all { it?.sources == listOf(source) }", "assertEquals(1, sourceParses.get())",
                      "assertEquals(1L, release.count)", "testCachedChatReturnIsFormattedAndReadReadyWithoutWaitingForAnotherParse",
                      "confirmed.any { turn.listKey in it }", "testBlockedOldParseCannotReplaceCurrentPendingOrFormattedBody",
                      "testPendingAppendCannotConfirmReadUsingPreviouslyPublishedBody", "testDifferentTurnWithAppendedTextCannotReusePublishedBody",
                      "testCachedAstUsesCurrentThemeAndFontAfterRemount", 'Column(Modifier.testTag("return-theme"))']:
            self.assertIn(token, test)


if __name__ == "__main__":
    unittest.main()
