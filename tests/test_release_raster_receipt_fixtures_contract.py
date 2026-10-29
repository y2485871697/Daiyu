"""Keep API-neutral raster oracles and honest guest/host receipt fixtures."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
class ReleaseRasterReceiptFixturesContractTest(unittest.TestCase):
    def test_floating_capture_includes_body_and_overlay(self):
        text = (ANDROID / 'FloatingPageActionsActivityTest.kt').read_text()
        for item in ('testTag("floating-page")', 'node("floating-page").captureToImage()',
                     'channelDifference(pixel, reference) < .03f', 'channelDifference(buttonPixel, reference) > .1f',
                     'assertEquals(previous + 1, backgroundClicks)', 'assertEquals(0.dp, clearance)',
                     'listOf(.9f, 1.5f)', 'listOf(false, true)'):
            self.assertIn(item, text)
        self.assertNotIn('captureScreenNode(node("floating-body"))', text)
    def test_dialog_capture_retains_pre28_support_and_color_roles(self):
        text = (ANDROID / 'WorkspaceNameDialogsActivityTest.kt').read_text()
        self.assertIn('captureScreenNode(sheet)', text)
        self.assertIn('background.compositeOver(underlay)', text)
        self.assertNotIn('captureToImage()', text)
        for item in ('colors.secondaryContainer, colors.onSecondary', 'colors.primary, colors.onPrimary',
                     'expected.red, actual.red, .02f', 'pureBlackEnabled = !appearance.value.pureBlackEnabled'):
            self.assertIn(item, text)
    def test_selected_rendered_colors_and_disabled_actions_stay_strict(self):
        text = (ANDROID / 'ui/screens/settings/SelectionAccentAppearanceTest.kt').read_text()
        for item in ('val image = target.captureToImage()', 'assertTrue(diagnostic, matches > 0)',
                     'asAndroidBitmap().compress', 'closest=$closest difference=$difference',
                     'abs(actual.blue - expected.blue)) < .01f', 'node(tag).assertIsNotEnabled()',
                     'assertEquals(1, radioCalls)', '"unselected remains neutral: $tag"'):
            self.assertIn(item, text)
    def test_bullet_expected_size_uses_header_em_and_raster_stays_strict(self):
        text = (ANDROID / 'ui/screens/chat/conversation/ToolCardReadabilityTest.kt').read_text()
        self.assertIn('measured.layoutInput.style.fontSize.toPx()', text)
        self.assertIn('ceil(headerFontPx * .5f)', text)
        self.assertNotIn('{ 6.5.sp.toPx() }', text)
        for item in ('val image = captureScreenNode(', '[circleIndex]', 'fillMaxSize().statusBarsPadding()', 'contrast > .2f', '"Bullet must have actual painted pixels"',
                     '"Visible bullet must be round"', '"Painted ink must be vertically centered"',
                     'assertCenteredUniformDots(titleNode, "$case step"), 1f)'):
            self.assertIn(item, text)
    def test_crops_use_compose_screen_origin_and_line_oracles_remain_strict(self):
        helper = (ANDROID / 'ComposeActivityTestCase.kt').read_text().split('protected fun captureScreenNode', 1)[1].split('protected fun node(', 1)[0]
        self.assertIn('node.positionOnScreen - node.positionInRoot', helper)
        self.assertIn('node.boundsInRoot.translate(rootToScreen)', helper)
        self.assertNotIn('view.getLocationOnScreen(location)', helper)
        table = (ANDROID / 'ui/screens/chat/conversation/MarkdownTableStyleTest.kt').read_text()
        for item in ('reveal first grid boundary', 'grid sample must be wholly inside viewport',
                     'vertical physical width", stroke, vertical.size', 'horizontal physical width", stroke, horizontal.size'):
            self.assertIn(item, table)
        timeline = (ANDROID / 'ui/screens/chat/conversation/ChatTimelineDividerTest.kt').read_text()
        self.assertIn('screenshot background mismatch', timeline)
        self.assertIn('distance(pixels[x, above], background) <= .02f', timeline)
        self.assertIn('coerceAtLeast(1), stripe.size)', timeline)

    def test_proot_receipt_has_consistent_host_flags_in_both_paths(self):
        text = (ANDROID / 'ui/screens/chat/conversation/ToolOperationCardTest.kt').read_text()
        self.assertIn('it.put("root", uid == 0 && !label.contains("PRoot"))', text)
        self.assertIn('.put("host_root", uid == 0 && !label.contains("PRoot"))', text)
        self.assertIn('for (mixed in listOf(false, true)) for ((name, target)', text)
        self.assertIn('assertEquals(0, executions)', text)
        unit = (ROOT / 'app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/TerminalToolTargetTest.kt').read_text()
        self.assertIn('prootReceiptFlagsMatchTheHostRatherThanGuestUid', unit)
        self.assertIn('assertNull(conflicting.hostIdentity)', unit)
if __name__ == '__main__':
    unittest.main()
