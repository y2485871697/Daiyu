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

    def test_terminal_execution_target_is_receipt_driven_and_kept_in_step_headers(self):
        target = (CHAT / "TerminalToolTarget.kt").read_text()
        self.assertIn('receipt.opt("backend")', target)
        self.assertIn('listOf("root", "host_root")', target)
        self.assertIn('readUid("guest_uid")', target)
        self.assertNotIn("arguments", target)
        self.assertNotIn("Settings", target)
        presentation = (CHAT / "ToolCardPresentation.kt").read_text()
        self.assertIn("TerminalToolTarget.fromResult(toolName, result, output)", presentation)
        self.assertNotIn("processTitleForSteps", (CHAT / "WorkProcessCard.kt").read_text())
        self.assertIn("executionTargetLabel = display.targetLabel", (CHAT / "ChatTurnMapper.kt").read_text())
        shizuku = (ROOT / "app/src/main/kotlin/app/daiyu/android/shizuku/RunShizukuCommands.kt").read_text()
        self.assertIn('.put("backend", "shizuku").put("uid", backend.identity.uid)', shizuku)

    def test_terminal_body_omits_permission_footer_but_keeps_receipt_identity(self):
        body = (CHAT / "ToolBodyLayout.kt").read_text()
        terminal = body.split('if (category in setOf("terminal", "background_job")) {', 1)[1].split('    val inputTitles', 1)[0]
        self.assertIn("return ToolBodyLayout(emptyList(), emptyList(),", terminal)
        self.assertNotIn('Android 宿主权限', terminal)
        details = (CHAT / "ToolCategoryDetails.kt").read_text()
        self.assertIn('target.hostIdentity?.let { fields += ToolFieldUi("Android 宿主权限", it) }', details)
        content = (CHAT / "ToolCategoryContent.kt").read_text()
        self.assertIn("if (fields.isEmpty()) return", content)

    def test_all_cards_omit_auxiliary_footer_but_keep_real_status_and_body(self):
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        self.assertNotIn('Text("工具回执未确认 · 不重放"', shell)
        for token in ('Text(stateLabel', 'ToolActivityUi.STOPPING -> "所属运行停止中"',
                      'ToolActivityUi.STOPPED -> "所属运行已停止"',
                      'ToolActivityUi.INTERRUPTED -> "所属运行已中断"',
                      'ToolCategoryContent(step.category, it)', 'toolContent(step)',
                      'ToolDetailSection(section,'):
            self.assertIn(token, shell)
        content = (CHAT / "ToolCategoryContent.kt").read_text()
        for text in (shell, content):
            self.assertNotIn('Text(rich.source', text)
            self.assertNotIn('Text(details.notice', text)
        for token in ('if (rich.bodyRestricted)', 'body.documents.forEach', 'body.prose?.let'):
            self.assertIn(token, content)
        regression = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolOperationCardTest.kt").read_text()
        self.assertIn('fun testAllCategoriesHideAuxiliaryFooterInBothProductionPaths', regression)
        self.assertIn('ToolCardPresentations.categories + "future_category"', regression)
        self.assertIn('ui.onNodeWithText("工具回执未确认 · 不重放", useUnmergedTree = true).assertDoesNotExist()', regression)

    def test_terminal_step_dots_remain_emphasized_under_unified_workflow_header(self):
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        self.assertNotIn('val terminalHeader', shell)
        self.assertIn('Text(title, Modifier.fillMaxWidth(), color = colors.text,', shell)
        self.assertIn('if (stacked) Text(progressSummary, Modifier.fillMaxWidth(), color = colors.muted,', shell)
        self.assertIn('Text(toolHeaderText(step.name, step.category in setOf("terminal", "background_job")), color = colors.text,', shell)
        self.assertIn('val progressSummary = work.progressSummary', shell)
        self.assertEqual(2, shell.count('Text(progressSummary'))

    def test_environment_dots_share_centered_circle_content_and_preserve_source_text(self):
        helper = (CHAT / "ToolHeaderText.kt").read_text()
        self.assertIn("character == '·' || character == '•'", helper)
        self.assertIn("appendInlineContent(ToolHeaderSeparatorId, character.toString())", helper)
        self.assertIn("Placeholder(.5.em, .5.em, PlaceholderVerticalAlign.Center)", helper)
        self.assertIn("InlineTextContent(ToolHeaderSeparatorPlaceholder)", helper)
        self.assertIn("drawCircle(color, radius = size.minDimension * .3f, center = center)", helper)
        self.assertNotIn("SpanStyle", helper)
        self.assertNotIn("BaselineShift", helper)
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        self.assertEqual(1, shell.count("inlineContent = toolHeaderInlineContent(colors.text)"))
        regression = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolCardReadabilityTest.kt").read_text()
        self.assertIn("private fun assertCenteredUniformDots", regression)
        self.assertIn("val expectedInkDiameter = minOf(rect.width, rect.height) * .6f", regression)
        self.assertIn("return paintedDiameters.first()", regression)
        self.assertIn("measured.placeholderRects.forEachIndexed", regression)
        self.assertIn("measured.getLineTop(line) + measured.getLineBottom(line)", regression)
        self.assertIn('assertCenteredUniformDots(titleNode, "$case step")', regression)
        self.assertIn('assertTrue(heading.placeholderRects.isEmpty())', regression)

    def test_compact_headers_adapt_locally_and_preserve_disclosure_ownership(self):
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        self.assertEqual(2, shell.count("heightIn(min = 44.dp)"))
        self.assertIn("val summaryWidth = maxWidth * .56f", shell)
        self.assertIn("maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.2f", shell)
        self.assertIn("maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.2f", shell)
        self.assertIn('if (stacked) Text(stateLabel, Modifier.fillMaxWidth(), color = tint,', shell)
        self.assertIn('Modifier.size(18.dp).testTag("work-process-icon")', shell)
        self.assertIn('Modifier.size(18.dp).testTag("tool-operation-icon:', shell)
        self.assertIn("fontSize = 13.sp, lineHeight = 18.sp", shell)
        self.assertIn("fontSize = 12.sp, lineHeight = 17.sp", shell)
        self.assertIn("rememberSaveable(ownerKey, finishing, stopped)", shell)
        self.assertIn("rememberSaveable(step.owner, step.toolCallId) { mutableStateOf(false) }", shell)
        self.assertIn("key(ownerKey, step.owner, step.toolCallId)", shell)
        self.assertNotIn("graphicsLayer", shell)
        self.assertNotIn("CompositionLocalProvider(LocalDensity", shell)
        geometry = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolCardReadabilityTest.kt").read_text()
        self.assertIn("fun testCompactHeadersKeepPhoneAndNarrowCardsWithinHeightBudget", geometry)
        self.assertIn('surface.height <= 98f * phoneDensity + 1f', geometry)
        self.assertIn('titleBounds.bottom <= stateBounds.top', geometry)

    def test_cards_have_no_technical_drawer_and_terminal_labels_are_category_scoped(self):
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        for removed in ('ToolDisclosureHeader("技术详情"', 'tool-step-technical:', 'var technical', '.rich?.technical'):
            self.assertNotIn(removed, shell)
        self.assertIn('AnimatedVisibility(expanded)', shell)
        self.assertIn('expandedOverride = !expanded', shell)
        body = (CHAT / "ToolBodyLayout.kt").read_text()
        self.assertIn('if (category in setOf("terminal", "background_job"))', body)
        self.assertIn('"执行命令"', body)
        self.assertIn('ToolDisclosureUi("终端输出"', body)
        content = (CHAT / "ToolCategoryContent.kt").read_text()
        self.assertIn('val log = document.title in setOf("终端输出",', content)
        self.assertIn('Icon(DaiyuIcons.ChevronRight, null', content)
        self.assertIn('settingsDisclosureRotation(expanded)', content)
        rich = (CHAT / "ToolRichDetails.kt").read_text()
        self.assertNotIn('详情见技术详情', rich)
        self.assertIn('if (toolName == "shizuku_execute") document(args, "script", "命令", true)', rich)

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


    def test_outcome_count_headers_wrap_and_have_real_layout_regression(self):
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        self.assertIn('val stacked = work.failedStepCount > 0 || work.unconfirmedStepCount > 0 ||', shell)
        readability = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolCardReadabilityTest.kt').read_text()
        self.assertIn('fun testFailedAndUnconfirmedHeaderCountsNeverEllipsize', readability)
        self.assertIn('measured.hasVisualOverflow', readability)
        self.assertIn('measured.isLineEllipsized(line)', readability)
        regression = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ReasoningWorkflowTest.kt').read_text()
        self.assertIn('fun testMixedResultCountsUpdateWithoutReopeningOrDuplicatingRows', regression)
        self.assertIn('已完成3步 · 1步失败 · 1步待确认', regression)

if __name__ == "__main__":
    unittest.main()
