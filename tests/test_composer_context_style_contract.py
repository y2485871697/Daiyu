"""Source-only context badge contracts; Compose geometry/color tests are separate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
COMPOSER = SRC / "ui/screens/chat/conversation/ChatComposer.kt"


class ComposerContextStyleContractTest(unittest.TestCase):
    def setUp(self):
        self.text = COMPOSER.read_text()
        self.badge = self.text.split('Row(Modifier.widthIn(max = 120.dp)', 1)[1].split('Box(Modifier.fillMaxWidth().padding(horizontal = 13.dp)', 1)[0]

    def test_badge_wraps_its_content_inside_compact_limits(self):
        self.assertIn('heightIn(min = 24.dp).testTag("chat-context")', self.badge)
        self.assertIn('.padding(1.dp).padding(horizontal = 4.dp, vertical = 2.dp)', self.badge)
        self.assertIn('Arrangement.spacedBy(4.dp)', self.badge)
        self.assertIn('Modifier.weight(1f, fill = false).width(IntrinsicSize.Max).testTag("chat-context-label")', self.badge)
        self.assertNotIn('fillMaxWidth', self.badge)
        self.assertIn('maxLines = 1, overflow = TextOverflow.Ellipsis', self.badge)

    def test_local_ink_is_shared_by_icon_and_text(self):
        self.assertIn('val contextColor = if (state.enabled) chatChromeColors().accent else colors.disabledOnSurface', self.text)
        self.assertIn('ChatContextMark(fraction, contextColor,', self.badge)
        self.assertIn('color = contextColor', self.badge)
        appearance = (SRC / 'ui/screens/chat/conversation/ChatAppearance.kt').read_text()
        self.assertIn('Color(0xFF7663BD)', appearance)
        self.assertIn('Color(0xFFB4A3F7)', appearance)
        self.assertIn('val chrome = chatChromeColors().copy(accent = conversationColors().accent)', self.text)

    def test_typography_and_icon_proportions_stay_local(self):
        self.assertIn('Modifier.size(18.dp).testTag("chat-context-icon")', self.badge)
        self.assertIn('fontSize = 10.sp, lineHeight = 14.sp', self.badge)
        self.assertIn('FontWeight.Normal', self.badge)
        self.assertIn('PlatformTextStyle(includeFontPadding = false)', self.badge)
        self.assertIn('LineHeightStyle.Alignment.Center', self.badge)
        self.assertNotIn('LocalDensity', self.text)

    def test_fraction_and_read_only_contracts_are_unchanged(self):
        self.assertIn('state.contextFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)', self.badge)
        self.assertIn('if (fraction == null) state.contextLabel else "${(fraction * 100).toInt()}% ${state.contextLabel}"', self.badge)
        self.assertNotIn('clickable', self.badge)
        icon = (SRC / 'ui/icons/ChatPreviewIcons.kt').read_text().split('internal fun ChatContextMark(', 1)[1]
        self.assertIn('color.alpha * .24f', icon)
        self.assertIn('color.alpha * .5f', icon)
        self.assertIn('progress?.let { drawPath(it, color, style = stroke) }', icon)


if __name__ == '__main__':
    unittest.main()
