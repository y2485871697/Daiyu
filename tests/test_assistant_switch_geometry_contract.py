"""Source-only switch geometry contracts; device pixel tests run separately."""
from pathlib import Path
from math import floor
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
VISUAL = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/AssistantEditVisualTest.kt'


class AssistantSwitchGeometryContractTest(unittest.TestCase):
    def setUp(self):
        self.source = (SRC / 'ui/screens/assistants/AssistantPreviewComponents.kt').read_text()
        self.switch = self.source.split('internal fun AssistantPreviewSwitch(', 1)[1].split('internal fun AssistantPreviewSection(', 1)[0]

    def test_thumb_uses_symmetric_insets_and_logical_alignment(self):
        self.assertIn('.padding(horizontal = 3.dp * s)', self.switch)
        self.assertIn('Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)', self.switch)
        self.assertIn('.size(16.dp * s).testTag("assistant-preview-switch-thumb")', self.switch)
        self.assertNotIn('.offset(', self.switch)
        self.assertEqual(2, self.switch.count('.size(36.dp * s, 22.dp * s)'))
        self.assertIn('DaiyuPalette.switchThumb else colors.muted', self.switch)

    def test_pixel_rounding_keeps_a_one_pixel_tolerance_across_density_and_scale(self):
        def pixels(dp, density):
            return floor(dp * density + .5)
        for density in (1., 1.5, 2.75, 3.5):
            for scale in (1., .8):
                d = density * scale
                width, height, size, inset = [pixels(dp, d) for dp in (36, 22, 16, 3)]
                for checked in (False, True):
                    for rtl in (False, True):
                        left = width - inset - size if checked != rtl else inset
                        right = width - left - size
                        self.assertEqual(inset, right if checked != rtl else left)
                        top = floor((height - size) / 2 + .5)
                        self.assertLessEqual(abs(top - inset), 1)
                        self.assertLessEqual(abs(height - top - size - inset), 1)
        self.assertEqual(6, 36 - 14 - 16)  # Historical checked end gap was twice the 3dp inset.
        self.assertEqual(17, 36 - 3 - 16)

    def test_interaction_palette_and_scope_are_unchanged(self):
        self.assertIn('.alpha(if (enabled) 1f else .35f)', self.switch)
        self.assertIn('.toggleable(checked, enabled = enabled, role = Role.Switch', self.switch)
        self.assertIn('indication = null, onValueChange = onToggle', self.switch)
        self.assertIn('Color(0xFFB3A0C7) else Color(0xFFA18BB8)', self.switch)
        editor = (SRC / 'ui/screens/assistants/AssistantEditScreen.kt').read_text()
        compression = (SRC / 'ui/screens/chat/context/CompressionConversationDialog.kt').read_text()
        self.assertEqual(2, editor.count('AssistantPreviewSwitch('))
        self.assertIn('AssistantPreviewSwitch(draft.customModel, enabled, ""', compression)
        self.assertNotIn('LocalDensity', self.switch)
        self.assertNotIn('absoluteOffset', self.switch)

    def test_android_regressions_cover_real_editor_and_raw_switch_geometry(self):
        visual = VISUAL.read_text()
        for value in ('testSkillAndMemoryThumbsStayAtTheirTrackEndsWithoutSaving',
                      'testThumbEndsFollowDensityScaleAndRtlWhileDisabledClicksStayBlocked',
                      'hasAnyAncestor(hasTestTag(tag))', 'captureScreenNode(trackNode).toPixelMap()',
                      'Triple(1.5f, .8f, LayoutDirection.Rtl)', 'Triple(3.5f, .8f, LayoutDirection.Rtl)',
                      'assertEquals(0, writes.get())', 'toggle.assertIsNotEnabled()',
                      'if (enabled) !on else on, checked.value'):
            self.assertIn(value, visual)


if __name__ == '__main__':
    unittest.main()
