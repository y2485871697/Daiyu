"""Guard streaming hot-path wiring; behavioral assertions live in JVM tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
CHAT = APP / "ui/screens/chat/conversation"


class StreamingPreparationContract(unittest.TestCase):
    def test_displayed_parts_depend_on_projected_text_not_pending_appends(self):
        source = (CHAT / "MarkdownTyping.kt").read_text()
        self.assertIn("val partIds = sources.map { it.id }", source)
        self.assertIn("val parts = remember(partIds, projection, compatible)", source)
        self.assertIn("remember(projection, requested.size, acceptsProjection)", source)
        self.assertNotIn("remember(projection, sources)", source)
        self.assertIn("MarkdownTypingSource(id, parsed.sources[index])", source)
        self.assertIn("target.parts.map { it.source } == sources", source)
        self.assertEqual(1, source.count("withFrameNanos { motion.advance(current, it, end) { currentAdvance() } }"))

    def test_committed_fallback_does_not_invalidate_its_own_composition(self):
        source = (CHAT / "MessageMarkdown.kt").read_text()
        helper = source.split("internal fun rememberMarkdownBodySnapshot", 1)[1].split("@Composable", 1)[0]
        self.assertIn("remember(MarkdownParserIdentity(parser)) { MarkdownBodyPublication() }", helper)
        self.assertIn("markdownBodySnapshot(text, blocks, published.body)", helper)
        self.assertIn("SideEffect { published.body = body }", helper)
        self.assertNotIn("mutableStateOf", helper)
        self.assertIn("val body = rememberMarkdownBodySnapshot(text, blocks)", source)

    def test_cache_is_activity_owned_scoped_and_passed_to_the_mapper(self):
        source = (APP / "MainActivity.kt").read_text()
        self.assertIn("private val toolPresentations =", source)
        publish = source.split("private fun publishChat(rowsChanged: Boolean", 1)[1].split("private fun requestScroll", 1)[0]
        self.assertIn("toolPresentations.scope(selected?.id?.value)", publish)
        self.assertIn("presentations = toolPresentations", publish)
        self.assertLess(publish.index("toolPresentations.scope"), publish.index("ChatTurnMapper.map("))

    def test_mapping_retains_expanded_history_and_keeps_receipts_live(self):
        source = (CHAT / "ChatTurnMapper.kt").read_text()
        self.assertLess(source.index("MessageHistory.expand(messages)"), source.index("presentations?.retain(history)"))
        self.assertIn("presentations?.present(block) ?: ToolCardPresentations.present(", source)
        self.assertIn("toolState(message, block)", source)
        self.assertIn("state = receipt ?: when (confirmedError)", source)
        self.assertIn("integrateReasoning = hasWorkflow, presentations = presentations", source)

    def test_pure_presentation_cache_has_exact_keys_and_bounded_retention(self):
        source = (CHAT / "ToolPresentationCache.kt").read_text()
        for token in ["data class Key(val name: String, val arguments: String, val result: String?)",
                      "maxEntries: Int = 64", "maxSourceChars: Int = 262_144", "0.75f, true",
                      "key.sourceChars > maxSourceChars", "entries.size > maxEntries || sourceChars > maxSourceChars",
                      "entry.key !in retained", "this.conversation == conversation", "entries.clear()"]:
            self.assertIn(token, source)
        for forbidden in ["WorkStepState", "ToolReceiptUi", "ExecutionState", "JSONObject", "mutableStateOf"]:
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
