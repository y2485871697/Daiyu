"""Source wiring contracts; Compose/pixel and Kotlin runtime checks await an approved build."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation"


class ToolCardHtmlContractTest(unittest.TestCase):
    def test_full_taxonomy_and_category_driven_body_are_wired(self):
        presentation = (CHAT / "ToolCardPresentation.kt").read_text()
        categories = re.search(r'val categories = setOf\(([^\n]+)\)', presentation).group(1)
        names = re.findall(r'"([a-z_]+)"', categories)
        self.assertEqual(21, len(names))
        body = (CHAT / "ToolBodyLayout.kt").read_text()
        for name in names:
            self.assertIn(f'"{name}"', body)
        content = (CHAT / "ToolCategoryContent.kt").read_text()
        self.assertIn("val body = toolBodyLayout(category, details)", content)
        self.assertIn("if (fields.isEmpty()) return", content)
        self.assertNotIn('Disclosure("运行环境"', content)
        self.assertIn("if (rich.bodyRestricted)", content)

    def test_ordinary_command_catalog_names_are_classified_and_have_terminal_glyphs(self):
        catalog = (ROOT / "app/src/main/kotlin/app/daiyu/android/integration/tools/RunCommandTools.kt").read_text()
        registered = set(re.findall(r'ToolDefinition\("([^"]+)"', catalog))
        self.assertEqual({"terminal_exec", "terminal_read"}, registered)
        presentation = (CHAT / "ToolCardPresentation.kt").read_text()
        terminal_group = re.search(r'group\("terminal", ([^\n]+)\)', presentation).group(1)
        glyph = (CHAT / "ToolOperationGlyph.kt").read_text()
        for name in registered:
            self.assertIn(f'"{name}" to ', terminal_group)
            line = next(line for line in glyph.splitlines() if f'"{name}"' in line)
            self.assertIn('-> "terminal"' if name == "terminal_exec" else '-> "output"', line)
        regression = (ROOT / "app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolCardPresentationTest.kt").read_text()
        self.assertIn("ordinaryCommandToolsUseTerminalPresentationWithoutChangingReceiptOrIdentity", regression)
        self.assertIn("WorkStepState.entries", regression)
        self.assertIn("WorkStepState.UNKNOWN, withoutReceipt", regression)

    def test_terminal_execution_target_is_receipt_driven_and_used_in_both_headers(self):
        target = (CHAT / "TerminalToolTarget.kt").read_text()
        self.assertIn('receipt.opt("backend")', target)
        self.assertIn('listOf("root", "host_root")', target)
        self.assertIn('readUid("guest_uid")', target)
        self.assertNotIn("arguments", target)
        self.assertNotIn("Settings", target)
        presentation = (CHAT / "ToolCardPresentation.kt").read_text()
        self.assertIn("TerminalToolTarget.fromResult(toolName, result, output)", presentation)
        self.assertIn("targets.all { it != null }", presentation)
        self.assertIn("processTitleForSteps(steps)", (CHAT / "WorkProcessCard.kt").read_text())
        self.assertIn("executionTargetLabel = display.targetLabel", (CHAT / "ChatTurnMapper.kt").read_text())
        shizuku = (ROOT / "app/src/main/kotlin/app/daiyu/android/shizuku/RunShizukuCommands.kt").read_text()
        self.assertIn('.put("backend", "shizuku").put("uid", backend.identity.uid)', shizuku)

    def test_compact_headers_adapt_locally_and_preserve_disclosure_ownership(self):
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        self.assertEqual(2, shell.count("heightIn(min = 44.dp)"))
        self.assertIn("val summaryWidth = maxWidth * .38f", shell)
        self.assertIn("maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.2f", shell)
        self.assertIn("maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.2f", shell)
        self.assertIn('if (stacked) Text(stateLabel, Modifier.fillMaxWidth(), color = tint,', shell)
        self.assertIn('Modifier.size(18.dp).testTag("work-process-icon")', shell)
        self.assertIn('Modifier.size(18.dp).testTag("tool-operation-icon:', shell)
        self.assertIn("fontSize = 13.sp, lineHeight = 18.sp", shell)
        self.assertIn("fontSize = 12.sp, lineHeight = 17.sp", shell)
        self.assertIn("rememberSaveable(ownerKey)", shell)
        self.assertEqual(2, shell.count("rememberSaveable(step.owner, step.toolCallId)"))
        self.assertIn("key(ownerKey, step.owner, step.toolCallId)", shell)
        self.assertIn('Text("工具回执未确认 · 不重放"', shell)
        self.assertNotIn("graphicsLayer", shell)
        self.assertNotIn("CompositionLocalProvider(LocalDensity", shell)
        geometry = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolCardReadabilityTest.kt").read_text()
        self.assertIn("fun testCompactHeadersKeepPhoneAndNarrowCardsWithinHeightBudget", geometry)
        self.assertIn('surface.height <= 98f * phoneDensity + 1f', geometry)
        self.assertIn('titleBounds.bottom <= stateBounds.top', geometry)

    def test_technical_header_uses_arrow_not_alternate_label(self):
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        self.assertIn('ToolDisclosureHeader("技术详情", technical,', shell)
        self.assertNotIn("收起技术详情", shell)
        self.assertIn("compact = true", shell)
        content = (CHAT / "ToolCategoryContent.kt").read_text()
        self.assertIn("DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight", content)
        self.assertIn('stateDescription = if (expanded) "已展开" else "已折叠"', content)
        rich = (CHAT / "ToolRichDetails.kt").read_text()
        self.assertIn('ToolDisclosureUi("原始回执"', rich)
        self.assertNotIn('ToolDisclosureUi("调用回执"', rich)

    def test_full_output_removes_close_cross_but_keeps_content_copy_and_shared_dismiss(self):
        dialog = (CHAT / "ToolOutputDialog.kt").read_text()
        for token in ['tool-output-close', '关闭完整输出', 'DaiyuIcons.Close', 'showCloseButton']:
            self.assertNotIn(token, dialog)
        for token in ['PreviewDialog(onDismiss,', 'Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp))',
                      'SelectionContainer(Modifier.weight(1f, fill = false))', 'detectTapGestures { }',
                      'testTag("tool-output-meta")', 'testTag("tool-output-text")', 'testTag("tool-output-copy")',
                      'clipboard.setText(AnnotatedString(document.text))', 'Text("复制全文"']:
            self.assertIn(token, dialog)

    def test_output_preview_has_no_hint_or_fade_but_keeps_shared_dialog(self):
        content = (CHAT / "ToolCategoryContent.kt").read_text()
        self.assertIn("if (tappable) Box(surface", content)
        for removed in ("点按查看完整输出", "tool-output-hint:", "hintBackground", "Brush.verticalGradient"):
            self.assertNotIn(removed, content)
        self.assertIn('.conversationClickable(onClickLabel = "查看${document.title}") { opened = true }', content)
        self.assertIn('testTag("tool-output-preview:${document.title}"), style = style, maxLines = 3', content)
        self.assertIn("if (opened) ToolOutputDialog(document", content)
        regression = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolCardReadabilityTest.kt").read_text()
        self.assertIn("fun testOutputPreviewHasNoHintAndFullOutputStillOpens", regression)
        self.assertIn('onNodeWithText("点按查看完整输出", useUnmergedTree = true).assertDoesNotExist()', regression)
        self.assertIn('onNodeWithTag("tool-output-hint:标准输出", useUnmergedTree = true).assertDoesNotExist()', regression)
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        self.assertIn("LocalToolOutputContext provides ToolOutputContext(step.name, stateLabel)", shell)
        dialog = (CHAT / "ToolOutputDialog.kt").read_text()
        for token in ("0xFFFBF9FE", "0xFF24212C", "0xCC16101F", "scrimBlurRadius = 4f",
                      "viewportHeight * .82f", "RoundedCornerShape(18.dp)", '"$it · 输出"'):
            self.assertIn(token, dialog)
        self.assertNotIn("usagePalette", dialog)
        self.assertIn("clipboard.setText(AnnotatedString(document.text))", dialog)

    def test_time_and_parameter_projection_preserve_recorded_semantics(self):
        rich = (CHAT / "ToolRichDetails.kt").read_text()
        self.assertIn("ZonedDateTime.parse(value).toOffsetDateTime()", rich)
        self.assertIn('ToolDisclosureUi("调用参数"', rich)
        self.assertIn("value is JSONArray -> sanitizedArray(value, depth + 1)", rich)
        self.assertIn("is Number, is Boolean -> value", rich)
        for filename in ("ToolCardPresentation.kt", "ToolCategoryDetails.kt"):
            self.assertIn("65_536", (CHAT / filename).read_text())


if __name__ == "__main__":
    unittest.main()
