"""Source-only context badge contracts; Compose geometry/color tests are separate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
COMPOSER = SRC / "ui/screens/chat/conversation/ChatComposer.kt"


class ComposerContextStyleContractTest(unittest.TestCase):
    def setUp(self):
        self.text = COMPOSER.read_text()
        self.badge = self.text.split('.widthIn(max = 120.dp).heightIn(min = 22.dp)', 1)[1].split('Box(Modifier.fillMaxWidth().padding(horizontal = 13.dp)', 1)[0]

    def test_badge_wraps_its_content_inside_compact_limits(self):
        self.assertIn('.testTag("chat-context")', self.badge)
        self.assertIn('.padding(1.dp).padding(horizontal = 3.dp, vertical = 2.dp)', self.badge)
        self.assertIn('Arrangement.spacedBy(4.dp)', self.badge)
        self.assertIn('modifier = Modifier.weight(1f, fill = false)', self.badge)
        self.assertIn('modifier = Modifier.testTag("chat-context-label")', self.badge)
        self.assertNotIn('fillMaxWidth', self.badge)
        self.assertIn('maxLines = 1, overflow = TextOverflow.Ellipsis', self.badge)

    def test_local_ink_is_shared_by_icon_and_text(self):
        self.assertIn('val contextColor = if (visualEnabled) chatChromeColors().accent else colors.disabledOnSurface', self.text)
        self.assertIn('ChatContextMark(fraction, contextColor,', self.badge)
        self.assertIn('color = contextColor', self.badge)
        appearance = (SRC / 'ui/screens/chat/conversation/ChatAppearance.kt').read_text()
        self.assertIn('Color(0xFF7663BD)', appearance)
        self.assertIn('Color(0xFFB4A3F7)', appearance)
        self.assertIn('val chrome = chatChromeColors().copy(accent = conversationColors().accent)', self.text)

    def test_typography_and_icon_proportions_stay_local(self):
        self.assertIn('Modifier.size(15.dp).testTag("chat-context-icon")', self.badge)
        self.assertIn('fontSize = 10.sp, lineHeight = 14.sp', self.badge)
        self.assertEqual(2, self.badge.count('RoundedCornerShape(11.dp)'))
        self.assertIn('FontWeight.Normal', self.badge)
        self.assertIn('PlatformTextStyle(includeFontPadding = false)', self.badge)
        self.assertIn('LineHeightStyle.Alignment.Center', self.badge)
        self.assertNotIn('LocalDensity', self.text)

    def test_toolbar_graphics_are_optically_sized_inside_unchanged_touch_slots(self):
        attach = self.text.split('ChatIconButton(ChatPreviewIcons.Attach,', 1)[1].split('Box(Modifier.size(32.dp).testTag("chat-reasoning")', 1)[0]
        thought = 'Box(Modifier.size(32.dp).testTag("chat-reasoning")' + self.text.split('Box(Modifier.size(32.dp).testTag("chat-reasoning")', 1)[1].split('Box(Modifier.size(32.dp).testTag("chat-assistant")', 1)[0]
        assistant = self.text.split('Box(Modifier.size(32.dp).testTag("chat-assistant")', 1)[1].split('key(state.controlOwnerKey)', 1)[0]
        self.assertIn('Modifier.size(32.dp)', attach)
        self.assertIn('iconSize = 22.5.dp', attach)
        self.assertIn('Modifier.size(32.dp).testTag("chat-reasoning")', thought)
        self.assertIn('modifier = Modifier.size(24.dp)', thought)
        self.assertIn('actions.onOpenReasoning', thought)
        self.assertIn('AssistantAvatar(assistant, 19.dp,', assistant)
        self.assertIn('onClick = actions.onOpenAssistant', assistant)

    def test_balance_moves_left_by_layout_spacing_not_a_visual_offset(self):
        controls = self.text.split('key(state.controlOwnerKey)', 1)[1].split('SpeechMorphButton(', 1)[0]
        self.assertIn('Arrangement.spacedBy(15.dp)', controls)
        self.assertIn('.widthIn(max = 72.dp)', controls)
        self.assertIn('animateChanges = true, iconSize = 16.dp', controls)
        self.assertIn('fontSize = 14.sp, lineHeight = 28.sp', controls)
        self.assertIn('Modifier.size(32.dp).testTag(if (textAction', controls)
        self.assertNotIn('.offset(', controls)

    def test_fraction_and_icon_contracts_are_unchanged(self):
        self.assertIn('state.contextFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)', self.badge)
        self.assertIn('if (fraction == null) state.contextLabel else "${(fraction * 100).toInt()}% ${state.contextLabel}"', self.badge)
        self.assertNotIn('onMenuAction', self.badge)
        icon = (SRC / 'ui/icons/ChatPreviewIcons.kt').read_text().split('internal fun ChatContextMark(', 1)[1]
        self.assertIn('color.alpha * .24f', icon)
        self.assertIn('color.alpha * .5f', icon)
        self.assertIn('progress?.let { drawPath(it, color, style = stroke) }', icon)

    def test_context_readability_checks_real_lines_and_rejects_clipping(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatChromeActivityTest.kt').read_text()
        helper = source.split('private fun assertNoTextOverflow(', 1)[1].split('private fun assertTextOverflowRejected(', 1)[0]
        for token in ('text.didOverflowHeight', 'text.isLineEllipsized(line)',
                      'text.getLineLeft(line) < -1f', 'text.getLineRight(line) > text.size.width + 1f',
                      'text.getLineTop(line) < -1f', 'text.getLineBottom(line) > text.size.height + 1f',
                      'text.getLineStart(0)', 'visibleEnd = true', 'text.layoutInput.text.length'):
            self.assertIn(token, helper)
        self.assertNotIn('text.hasVisualOverflow', helper)
        for token in ('IntSize(text.size.width - 2, text.size.height)',
                      'IntSize(text.size.width, text.size.height - 1)', 'assertTextOverflowRejected(truncated)',
                      'catch (_: junit.framework.AssertionFailedError)', 'assertTrue("overflow oracle must reject'):
            self.assertIn(token, source)

    def test_only_actual_token_characters_gain_tracking_not_padding_or_slash_spaces(self):
        self.assertIn('letterSpacing = if (showTokens) 0.3.sp else MiuixTheme.textStyles.footnote1.letterSpacing', self.badge)
        model = (SRC / 'ui/model/CloudContextUsage.kt').read_text()
        self.assertIn('return "${compact(inputTokens)}/${compact(windowTokens?.takeIf { it > 0 })}"', model)
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatChromeActivityTest.kt').read_text()
        toggle = android.split('fun testContextPercentageAndActualToggleAnimateInsideTheSameCompactBadge()', 1)[1].split('fun testEmptySend', 1)[0]
        for token in ('20_000L', '"20K/272K"', 'assertEquals(0.3.sp, tokenLayout.layoutInput.style.letterSpacing)',
                      'assertEquals(percentLetterSpacing, layout(percent).layoutInput.style.letterSpacing)',
                      'assertNoTextOverflow(tokenLayout)'):
            self.assertIn(token, toggle)

    def test_live_usage_updates_share_the_mode_animation_and_snapshot_outgoing_text(self):
        self.assertIn('val contextText = if (showContextTokens)', self.badge)
        self.assertIn('AnimatedContent(targetState = showContextTokens to contextText,', self.badge)
        content = self.badge.split('label = "context-usage-mode")', 1)[1]
        self.assertIn('{ (showTokens, text) ->', content)
        self.assertIn('Text(text,', content)
        self.assertNotIn('state.context', content)
        for animation in ('fadeIn(tween(180))', 'fadeOut(tween(120))',
                          'slideInVertically(tween(220, easing = FastOutSlowInEasing))',
                          'slideOutVertically(tween(180))',
                          'SizeTransform { _, _ -> tween(220, easing = FastOutSlowInEasing) }'):
            self.assertIn(animation, self.badge)

    def test_unknown_usage_label_is_only_context(self):
        model = (SRC / 'ui/model/CloudContextUsage.kt').read_text()
        self.assertNotIn('未知 上下文', model)
        self.assertEqual(2, model.count('?: return "上下文"'))

    def test_context_toggles_locally_with_animated_text_and_width(self):
        self.assertIn('var showContextTokens by rememberSaveable { mutableStateOf(false) }', self.text)
        self.assertIn('onClick = { showContextTokens = !showContextTokens }', self.badge)
        self.assertIn('role = Role.Button', self.badge)
        self.assertIn('AnimatedContent(targetState = showContextTokens', self.badge)
        for animation in ('fadeIn(', 'fadeOut(', 'slideInVertically(', 'slideOutVertically(', 'SizeTransform'):
            self.assertIn(animation, self.badge)
        self.assertIn('state.contextTokensText ?: "—/—"', self.badge)
        self.assertNotIn('onOpenContext', self.badge)
        self.assertEqual(1, self.badge.count('ChatContextMark('))


if __name__ == '__main__':
    unittest.main()
