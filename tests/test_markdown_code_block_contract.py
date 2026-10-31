"""Narrow source wiring guards, not a replacement for Android gesture/visual tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation"


class MarkdownCodeBlockContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.markdown = (BASE / "MessageMarkdown.kt").read_text()
        cls.code = (BASE / "MarkdownCodeBlock.kt").read_text()
        cls.reveal = (BASE / "MarkdownCodeReveal.kt").read_text()

    def test_code_block_keeps_symmetric_card_and_left_aligned_body(self):
        branch = self.markdown.split("is MarkdownBlock.Code ->", 1)[1].split("is MarkdownBlock.Quote ->", 1)[0]
        self.assertIn("MarkdownCodeBlock(block, visibility.value, generationComplete)", branch)
        for token in ["Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center)",
                      "Modifier.fillMaxWidth().padding(horizontal = 4.dp)", "RoundedCornerShape(8.dp)",
                      "textAlign = TextAlign.Left", 'testTag("markdown-code-block")', 'testTag("markdown-code-text")']:
            self.assertIn(token, self.code)

    def test_card_keeps_readable_width_and_body_only_caps_its_natural_height(self):
        card = self.code.split("Column(", 1)[1].split('testTag("markdown-code-block")', 1)[0]
        self.assertIn(".fillMaxWidth().padding(horizontal = 4.dp)", card)
        self.assertNotIn("IntrinsicSize", self.code)
        self.assertIn("import androidx.compose.foundation.layout.heightIn", self.code)
        self.assertIn("Row(Modifier.fillMaxWidth()", self.code)
        self.assertIn("Box(Modifier.fillMaxWidth().heightIn(max = maxBodyHeight)", self.code)
        self.assertNotIn("heightIn(min =", self.code)
        self.assertNotIn(".height(bodyHeight)", self.code)
        self.assertNotIn("requiredWidth", self.code)
        self.assertNotIn("unbounded = true", self.code)

    def test_body_uses_compact_vertical_padding_and_display_only_newline_projection(self):
        self.assertIn(".padding(horizontal = 12.dp, vertical = 4.dp)", self.code)
        self.assertIn('MarkdownCodeText(block, visibleCharacters, Modifier.testTag("markdown-code-text")', self.code)
        self.assertIn('BasicText(markdownCodeDisplayText(block, visible)', self.reveal)
        policy = (BASE / "MarkdownCodePolicy.kt").read_text()
        self.assertIn("visibleText.length == block.text.length", policy)
        self.assertIn('visibleText.removeSuffix("\\n")', policy)
        self.assertNotIn(".trim(", policy)
        self.assertNotIn(".trimEnd(", policy)

    def test_header_actions_have_exact_order_and_no_extra_visible_labels(self):
        for tag in ("download", "expand", "unlock", "copy"):
            self.assertIn('"markdown-code-' + tag + '"', self.code)
        actions = [self.code.index('"markdown-code-' + tag + '"') for tag in ("download", "expand", "unlock", "copy")]
        self.assertEqual(sorted(actions), actions)
        self.assertIn('testTag("markdown-code-header")', self.code)
        self.assertIn("Modifier.size(16.dp)", self.code)
        self.assertIn("conversationClickable", self.code)
        self.assertNotIn("TextButton", self.code)

    def test_blank_language_header_defaults_to_text(self):
        header = self.code.split('testTag("markdown-code-header")', 1)[1].split("CodeAction(", 1)[0]
        self.assertIn('BasicText(block.language.ifBlank { "text" }', header)

    def test_language_label_uses_readable_size_without_changing_body_or_actions(self):
        header = self.code.split('testTag("markdown-code-header")', 1)[1].split("CodeAction(", 1)[0]
        self.assertIn('TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = colors.muted)', header)
        self.assertIn('Modifier.weight(1f).padding(end = 4.dp)', header)
        self.assertIn('maxLines = 1', header)
        self.assertIn('fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Left', self.code)
        self.assertIn('modifier.size(36.dp)', self.code)
        self.assertIn('Icon(icon, description, Modifier.size(16.dp)', self.code)

    def test_copy_uses_full_code_not_revealed_text(self):
        self.assertIn('clipboard.setText(AnnotatedString(block.text))', self.code)
        self.assertNotIn('clipboard.setText(AnnotatedString(text))', self.code)
        self.assertIn("DaiyuIcons.Copy", self.code)
        self.assertIn('"复制代码"', self.code)
        self.assertIn("DisableSelection", self.code)

    def test_height_capped_viewport_only_locks_vertical_and_contains_unlocked_boundary_and_fling(self):
        for token in ["screenHeightDp.dp * .25f", ".coerceIn(128.dp, 220.dp)", ".heightIn(max = maxBodyHeight)",
                      "var unlocked by remember { mutableStateOf(false) }", "verticalScroll(vertical, enabled = verticalScrollEnabled)",
                      "horizontalScroll(horizontal)", "Modifier.nestedScroll(containedScroll)",
                      "onPostScroll", ": Offset = available", "onPostFling", ": Velocity = available"]:
            self.assertIn(token, self.code)
        self.assertIn("softWrap = false", self.reveal)
        self.assertNotIn("horizontalScroll(horizontal, enabled = unlocked)", self.code)
        self.assertIn('"锁定代码上下滑动" else "解锁代码上下滑动"', self.code)
        self.assertLess(self.code.index(".heightIn(max = maxBodyHeight)"), self.code.index(".verticalScroll(vertical"))
        self.assertNotIn("remember(block.text) { mutableStateOf(false) }\n    var unlocked", self.code)

    def test_unlock_is_only_shown_for_measured_vertical_overflow(self):
        for token in ["val overflowsVertically = vertical.viewportSize > 0 && vertical.maxValue > 0",
                      "val verticalScrollEnabled = unlocked && overflowsVertically",
                      "if (overflowsVertically) CodeAction(CodeBlockIcons.Scroll",
                      "if (verticalScrollEnabled) Modifier.nestedScroll(containedScroll)"]:
            self.assertIn(token, self.code)
        self.assertNotIn("horizontal.maxValue", self.code)
        self.assertNotIn("if (unlocked) Modifier.nestedScroll", self.code)

    def test_output_follows_measured_bottom_then_animates_once_to_top(self):
        for token in ["markdownCodeProducing(block, visibleCharacters, generationComplete)", "LaunchedEffect(producing)",
                      "snapshotFlow { vertical.maxValue }", "vertical.scrollTo(bottom)", "else if (sawOutput)",
                      "sawOutput = false", "withFrameNanos { }", "vertical.scrollTo(vertical.maxValue)", "vertical.animateScrollTo(0, tween(300))", "horizontal.animateScrollTo(0, tween(300))"]:
            self.assertIn(token, self.code)
        chat = (BASE / "ChatMessageList.kt").read_text()
        self.assertEqual(2, chat.count("generationComplete = turn.status != RunStatus.RUNNING"))
        self.assertIn("turn.status != RunStatus.RUNNING && contentReady", chat)
        self.assertIn("val currentComplete = rememberUpdatedState(generationComplete)", chat)
        self.assertIn("generationComplete = currentComplete.value, deferBlockReveal = true)", chat)
        self.assertEqual(3, chat.count("textBody = textBody"))
        self.assertIn("generationComplete && body.text == text", self.markdown)
        self.assertIn("MarkdownBlocks(block.blocks, openLink, visibility, generationComplete)", self.markdown)
        self.assertIn("MarkdownBlocks(item.blocks, openLink, visibility, generationComplete)", self.markdown)

    def test_expansion_reuses_shared_output_dialog_without_a_close_cross(self):
        for token in ["LocalToolOutputContext provides null", "ToolDisclosureUi(block.language, block.text, code = true)",
                      "onDismiss = { expanded = false }"]:
            self.assertIn(token, self.code)
        dialog = (BASE / "ToolOutputDialog.kt").read_text()
        self.assertNotIn("showCloseButton", self.code)
        self.assertNotIn("showCloseButton", dialog)
        self.assertNotIn('testTag("tool-output-close")', dialog)
        self.assertNotIn("DaiyuIcons.Close", dialog)
        self.assertIn("clipboard.setText(AnnotatedString(document.text))", dialog)

    def test_download_freezes_code_and_writes_only_utf8_to_selected_document(self):
        for token in ['ActivityResultContracts.CreateDocument("text/plain")', "pendingDownload = block.text",
                      "val content = pendingDownload", "pendingDownload = null", "uri != null && content != null",
                      'require(uri.scheme == "content")', "Dispatchers.IO", 'openOutputStream(uri, "wt")',
                      "content.toByteArray(Charsets.UTF_8)", "it.flush()", "throw cancelled"]:
            self.assertIn(token, self.code)
        self.assertNotIn("rememberSaveable", self.code)
        self.assertNotIn("Environment.getExternalStorage", self.code)


if __name__ == "__main__":
    unittest.main()
