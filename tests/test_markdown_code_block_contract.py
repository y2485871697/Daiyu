"""Narrow wiring guard for Markdown code-block layout and exact clipboard copy."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation"


class MarkdownCodeBlockContract(unittest.TestCase):
    def test_code_block_has_symmetric_card_and_left_aligned_body(self):
        source = (BASE / "MessageMarkdown.kt").read_text()
        branch = source.split("is MarkdownBlock.Code ->", 1)[1].split("is MarkdownBlock.Quote ->", 1)[0]
        self.assertIn("MarkdownCodeBlock(block, visible)", branch)
        code = source.split("private fun MarkdownCodeBlock(", 1)[1].split("/** Width sampling", 1)[0]
        for token in ["Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center)",
                      "Modifier.fillMaxWidth().padding(horizontal = 4.dp)",
                      "textAlign = TextAlign.Left", 'testTag("markdown-code-block")',
                      'testTag("markdown-code-text")']:
            self.assertIn(token, code)

    def test_copy_uses_full_code_not_revealed_text_and_has_a_top_right_action(self):
        source = (BASE / "MessageMarkdown.kt").read_text()
        code = source.split("private fun MarkdownCodeBlock(", 1)[1].split("/** Width sampling", 1)[0]
        self.assertIn('testTag("markdown-code-header")', code)
        self.assertIn('testTag("markdown-code-copy")', code)
        self.assertIn('clipboard.setText(AnnotatedString(block.text))', code)
        self.assertNotIn('clipboard.setText(AnnotatedString(text))', code)
        self.assertIn("DaiyuIcons.Copy", code)
        self.assertIn('"复制代码"', code)
        self.assertIn("DisableSelection", code)
        android_test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MessageMarkdownTest.kt").read_text()
        self.assertIn("testCodeBlocksAreCenteredAndCopyTheirExactCodeFromTheTopRightAction", android_test)
        self.assertIn("assertEquals(first, manager.primaryClip!!.getItemAt(0).text.toString())", android_test)
        self.assertIn("assertEquals(second, manager.primaryClip!!.getItemAt(0).text.toString())", android_test)


if __name__ == "__main__":
    unittest.main()
