"""Custom accent roles preserve selected color; surface-independent text and custom bubble contrast stay separate."""
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

    def test_local_typography_never_changes_with_surface_brightness(self):
        for name in ['ui/components/UsagePresentation.kt', 'ui/components/ConversationPanePreview.kt',
                     'ui/components/subagent/ConversationAgentStyle.kt', 'ui/screens/assistants/AssistantPreviewComponents.kt']:
            text = source(name)
            self.assertIn('val accent = resolveSelectionAccent(appearance, dark)' if name == 'ui/components/UsagePresentation.kt' else 'val accent = appearance.resolvedPrimaryAccent ?: ', text)
            self.assertNotIn('resolvedPrimaryAccent?.let { readableAccent', text)
            self.assertNotIn('readableAccent(theme.onSurfaceSecondary', text)
            self.assertNotIn('if (customSurface) theme.onSurface', text)
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

    def test_layer_custom_bubble_and_system_bar_contrast_helpers_remain(self):
        colors = source('ui/app/AppearanceColors.kt')
        self.assertIn('if (background.luminance() > .179f) Color.Black else Color.White', colors)
        self.assertIn('val layerTarget = colorContentColor(surface)', colors)
        self.assertNotIn('onSurface = colorContentColor', colors)
        self.assertIn('UserBubbleColors(fill, colorContentColor(fill))', colors)
        self.assertIn('colorContentColor(background) == Color.Black', source('MainActivity.kt'))

    def test_settings_selected_check_uses_shared_exact_primary_selection_role(self):
        rows = source('ui/screens/settings/SettingsUiComponents.kt')
        self.assertIn('tint = DaiyuPalette.selectionAccent', rows)
        self.assertIn('resolveSelectionAccent(LocalAppearanceSettings.current, LocalAppearanceDark.current)', source('ui/app/DaiyuPalette.kt'))
        self.assertNotIn('readableAccent(MiuixTheme.colorScheme.primary', rows)

if __name__ == '__main__': unittest.main()
