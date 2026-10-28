"""Source wiring only; rendering/gesture assertions live in Android regressions."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation"

class MarkdownStreamOptimizationContract(unittest.TestCase):
    def test_only_text_leaves_subscribe_to_animation_frames(self):
        typing = (BASE / "MarkdownTyping.kt").read_text()
        chat = (BASE / "ChatMessageList.kt").read_text()
        ui = (BASE / "MessageMarkdown.kt").read_text()
        self.assertIn("val frame = produceState(", typing)
        self.assertNotIn("val frame by produceState(", typing)
        self.assertIn("private val completion = derivedStateOf", typing)
        self.assertIn("remember(target, frame, exact)", typing)
        self.assertNotIn("reveal.limit(", chat)
        self.assertIn("reveal.limit(partId)", ui)
        self.assertIn("val revealSettled = reveal.settled", chat)

    def test_complete_layout_is_cached_but_semantics_never_return_hidden_text(self):
        code = (BASE / "MarkdownCodeReveal.kt").read_text()
        for token in ["rememberTextMeasurer(cacheSize = 1)", "remember(block.text) { AnnotatedString(block.text) }",
                      "measurer.measure(complete, style, softWrap = false)", "remember(layout.multiParagraph)",
                      "clipPath(reveal.path) { drawText(layout) }", "text = semanticText",
                      "measurer.measure(semanticText, style, softWrap = false, skipCache = true)",
                      "it.code < 128", "markdownCodeDisplayText(block, visible)",
                      "constraints.constrainHeight(reveal.height)", "constraints.constrainWidth(reveal.width)"]:
            self.assertIn(token, code)
        self.assertNotIn("BasicText(complete", code)
        self.assertNotIn("SelectionContainer", code)
        # The prefix layout is computed only when the semantics action is invoked.
        action = code.split("getTextLayoutResult { results ->", 1)[1].split("}.layout", 1)[0]
        self.assertIn("skipCache = true", action)
        self.assertIn("layout.getPathForRange(end, visibleEnd)", code)

    def test_block_reuse_runs_after_whole_parse_and_stays_worker_local(self):
        projection = (BASE / "MarkdownProjection.kt").read_text()
        reuse = (BASE / "MarkdownBlockReuse.kt").read_text()
        self.assertIn("var published: MarkdownProjection? = null", projection)
        self.assertIn("projectMarkdown(snapshot, parser, cache, published)", projection)
        self.assertIn("it.parser === parser && isMarkdownAppend(it.sources, sources)", projection)
        self.assertIn("blocks[source] = reuseMarkdownBlocks(old, parsed)", projection)
        self.assertLess(projection.index("MarkdownParser.parse(source)"), projection.index("reuseMarkdownBlocks(old, parsed)"))
        self.assertIn("old?.takeIf { it == block } ?: block", reuse)
        self.assertNotIn("LinkedHashMap", reuse)
        self.assertNotIn("@Stable", reuse)
        self.assertNotIn("split(", reuse)

if __name__ == "__main__": unittest.main()
