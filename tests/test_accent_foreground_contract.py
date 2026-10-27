"""Custom accent roles preserve selected color; body and bubble contrast stays separate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

def source(name):
    return (SRC / name).read_text()

class AccentForegroundContractTest(unittest.TestCase):
    def test_switch_preview_and_actual_controls_keep_their_original_foreground_roles(self):
        palette = source('ui/app/DaiyuPalette.kt')
        sheet = source('ui/screens/settings/AppearanceColorSheet.kt')
        self.assertIn('val switchThumb: Color = Color.White', palette)
        self.assertIn('background(DaiyuPalette.switchThumb, CircleShape)', sheet)
        self.assertNotIn('colorContentColor', sheet)
        self.assertIn('checkedThumbColor = colors.onPrimary', source('ui/screens/settings/SettingsUiComponents.kt'))
        self.assertIn('if (checked) DaiyuPalette.switchThumb', source('ui/screens/assistants/AssistantPreviewComponents.kt'))

    def test_local_accent_typography_bypasses_only_custom_accent_contrast_conversion(self):
        for name in ['ui/components/UsagePresentation.kt', 'ui/components/ConversationPanePreview.kt',
                     'ui/components/subagent/ConversationAgentStyle.kt', 'ui/screens/assistants/AssistantPreviewComponents.kt']:
            text = source(name)
            self.assertIn('val accent = appearance.resolvedPrimaryAccent ?: if (customSurface)', text)
            self.assertNotIn('resolvedPrimaryAccent?.let { readableAccent', text)
            self.assertIn('readableAccent(theme.onSurfaceSecondary', text)
        for name in ['ui/screens/chat/conversation/ChatAppearance.kt', 'ui/screens/chat/conversation/ConversationAppearance.kt']:
            self.assertNotIn('readableAccent(appearance.resolvedPrimaryAccent', source(name))
            self.assertIn('accent = appearance.resolvedPrimaryAccent ?: base.accent', source(name))

    def test_material_and_local_filled_controls_do_not_derive_black_from_accent_brightness(self):
        theme = source('ui/app/AgentAppTheme.kt')
        self.assertEqual(2, theme.count('onTertiary = materialDefaults.onTertiary'))
        for name in ['ui/components/UsagePresentation.kt', 'ui/components/subagent/ConversationAgentStyle.kt',
                     'ui/screens/settings/LinuxSettingsTheme.kt']:
            self.assertNotIn('colorContentColor', source(name))
        self.assertIn('val onPrimary = Color.White', source('ui/screens/settings/LinuxSettingsTheme.kt'))

    def test_body_bubble_and_system_bar_contrast_helpers_remain(self):
        colors = source('ui/app/AppearanceColors.kt')
        self.assertIn('if (background.luminance() > .179f) Color.Black else Color.White', colors)
        self.assertIn('val content = colorContentColor(surface)', colors)
        self.assertIn('UserBubbleColors(fill, colorContentColor(fill))', colors)
        self.assertIn('colorContentColor(background) == Color.Black', source('MainActivity.kt'))

    def test_settings_selected_check_keeps_custom_primary_before_default_contrast_fallback(self):
        rows = source('ui/screens/settings/SettingsUiComponents.kt')
        self.assertIn('tint = app.daiyu.android.ui.app.LocalAppearanceSettings.current.resolvedPrimaryAccent', rows)
        self.assertIn('?: app.daiyu.android.ui.app.readableAccent(MiuixTheme.colorScheme.primary', rows)

if __name__ == '__main__': unittest.main()
