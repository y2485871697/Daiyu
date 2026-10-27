"""Exact custom surfaces share production roles; Android pixel checks run in the UI batch."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
class ExactAppearanceColorContractTest(unittest.TestCase):
    def test_custom_palettes_use_opaque_resolved_surface_and_matching_text(self):
        for path in ('ui/components/ConversationPanePreview.kt', 'ui/components/UsagePresentation.kt',
                     'ui/components/subagent/ConversationAgentStyle.kt', 'ui/screens/assistants/AssistantPreviewComponents.kt'):
            text = (SRC / path).read_text()
            self.assertIn('resolveAppearanceColors(appearance, dark)', text)
            self.assertIn('if (customSurface) listOf(theme.surface', text)
            self.assertIn('if (customSurface) theme.onSurface', text)
            self.assertIn('dark, theme.surface)', text)
    def test_system_bars_refresh_when_custom_rgb_changes(self):
        theme = (SRC / 'ui/app/AgentAppTheme.kt').read_text()
        host = (SRC / 'MainActivity.kt').read_text()
        bars = host.split('private fun updateSystemBars(', 1)[1].split('private fun chatActions()', 1)[0]
        self.assertIn('appearance.paletteSeedColor, appearance.pureBlackEnabled', theme)
        self.assertIn('resolveAppearanceColors(appearance, isDark).background', bars)
        self.assertIn('colorContentColor(background) == Color.Black', bars)
    def test_android_regression_compares_draft_and_applied_pixels(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/AppearanceColorPickerActivityTest.kt').read_text()
        self.assertIn('testExactInterfaceDraftAndAppliedSurfaceInBothThemes', test)
        self.assertIn('exact-applied-surface', test)
        self.assertIn('assertTrue(requests.isEmpty())', test)
if __name__ == '__main__': unittest.main()
