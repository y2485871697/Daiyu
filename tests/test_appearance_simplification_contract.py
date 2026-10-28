"""Retired appearance preferences never re-enter runtime; retained settings keep ownership."""
from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
def source(path): return (SRC / path).read_text()
RETIRED_KEYS = ('monet', 'palette', 'accent', 'palette_seed', 'primary_accent', 'secondary_accent',
                'user_bubble_mode', 'user_bubble_color', 'morph_loading', 'morph_before_response_only')

class AppearanceSimplificationContractTest(unittest.TestCase):
    def test_removed_options_and_children_have_no_screen_or_dialog_entry(self):
        screen = source('ui/screens/settings/AppearanceSettingsScreen.kt')
        for name in ('色彩风格', '强调色', '聊天气泡', '圆形加载指示器', '仅消息生成前',
                     'onColorChange', 'loadingExpanded', 'colorEditor'):
            self.assertNotIn(name, screen)
        for name in ('AppearanceColorSheet.kt', 'AppearanceColorSelection.kt'):
            self.assertFalse((SRC / 'ui/screens/settings' / name).exists())

    def test_theme_black_icon_scale_effect_and_navigation_options_remain(self):
        screen = source('ui/screens/settings/AppearanceSettingsScreen.kt')
        for name in ('AppearanceThemeMode.entries', 'SettingsSwitchRow("纯黑背景"', 'iconContent(iconExpanded)',
                     'appearance-scale', '消息时间戳', '模糊效果', '顶栏模糊样式', '横移返回手势', '预测性返回手势'):
            self.assertIn(name, screen)

    def test_no_retired_fields_enums_or_transform_backend_in_production(self):
        retired = ('monetEnabled', 'paletteStyle', 'accentColor', 'paletteSeedColor', 'primaryAccentColor',
                   'secondaryAccentColor', 'userBubbleMode', 'userBubbleColor', 'morphLoadingIndicator',
                   'morphLoadingBeforeResponseOnly', 'hasAssistantOutput', 'assistantOutputExists',
                   'AppearancePaletteStyle', 'AppearanceAccentColor', 'UserBubbleMode', 'resolvedPrimaryAccent',
                   'styleColor', 'withColorStyle', 'withAccentColors', 'withUserBubbleColors', 'onColorChange')
        for path in SRC.rglob('*.kt'):
            text = path.read_text()
            for name in retired:
                self.assertNotRegex(text, r'\b' + name + r'\b', f'{path}: {name}')

    def test_retired_preferences_are_only_removed_in_the_existing_save_transaction(self):
        store = source('data/model/AppearanceSettingsStore.kt')
        for key in RETIRED_KEYS:
            self.assertEqual(1, store.count(f'.remove("{key}")'))
            self.assertNotRegex(store, r'get\w+\("' + key + r'"')
            self.assertNotRegex(store, r'put\w+\("' + key + r'"')
        self.assertNotIn('.clear()', store)
        read = store.split('private fun read()', 1)[1].split('/** Caller', 1)[0]
        self.assertNotIn('.edit()', read)
        for fragment in ('check(preferences.edit()', '.commit())', 'check(read() == normalized)',
                         'fenced[preferences] ?: read()', 'fenced.getOrPut(preferences) { read() }'):
            self.assertIn(fragment, store)

    def test_retained_preferences_are_read_and_written(self):
        store = source('data/model/AppearanceSettingsStore.kt')
        for key in ('theme', 'scale', 'timestamps', 'pure_black', 'blur', 'blur_style',
                    'swipe_dismiss', 'predictive_back', 'icon_light', 'icon_dark', 'icon_cat'):
            self.assertRegex(store, r'get\w+\("' + key + r'"')
            self.assertRegex(store, r'put\w+\("' + key + r'"')

    def test_pure_black_is_still_only_a_dark_background_and_surface_override(self):
        colors = source('ui/app/AppearanceColors.kt')
        self.assertIn('val base = if (dark) DaiyuPalette.dark else DaiyuPalette.light', colors)
        self.assertEqual(2, colors.count('dark && appearance.pureBlackEnabled'))
        self.assertIn('background = if (dark && appearance.pureBlackEnabled) Color.Black else base.background', colors)
        self.assertIn('surface = if (dark && appearance.pureBlackEnabled) Color.Black else base.surface', colors)

    def test_loading_retains_running_and_actual_runtime_gate(self):
        loading = source('ui/screens/chat/conversation/ComposerLoadingProjection.kt')
        self.assertIn('if (phase != ComposerPhase.RUNNING) return false', loading)
        self.assertIn('ExecutionPhase.RECONNECTING, ExecutionPhase.TOOL_EXECUTING -> true', loading)
        self.assertIn('ExecutionPhase.PAUSED, ExecutionPhase.WAITING_USER, ExecutionPhase.TERMINAL, null -> false', loading)
        host = source('MainActivity.kt')
        self.assertIn('executionPhase = state?.phase', host)
        composer = source('ui/screens/chat/conversation/ChatComposer.kt')
        self.assertIn('generating = state.showGenerationLoading()', composer)
        speech = source('ui/screens/chat/conversation/SpeechMorphButton.kt')
        self.assertIn('animate = generating || listening || showPressFeedback', speech)

    def test_default_message_bubble_and_contrast_helpers_are_preserved(self):
        conversation = source('ui/screens/chat/conversation/ConversationAppearance.kt')
        for token in ('Color(0xFF26233D)', 'Color(0xFFF0ECFC)', 'val bubbleText: Color = text'):
            self.assertIn(token, conversation)
        self.assertIn('color = conversationColors().bubbleText', source('ui/screens/chat/conversation/ChatMessageList.kt'))
        colors = source('ui/app/AppearanceColors.kt')
        self.assertIn('internal fun colorContentColor(', colors)
        self.assertIn('internal fun readableAccent(', colors)
        self.assertIn('Color(0xFF92454F)', colors)

if __name__ == '__main__': unittest.main()
