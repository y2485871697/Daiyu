"""Source contracts for independent appearance editors; device checks await the UI batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(path):
    return (SRC / path).read_text()


class AppearanceColorContractTest(unittest.TestCase):
    def test_color_groups_copy_only_their_owned_fields(self):
        model = source('data/model/AppearanceSettings.kt')
        style = model.split('fun withColorStyle(', 1)[1].split('fun withAccentColors(', 1)[0]
        accents = model.split('fun withAccentColors(', 1)[1].split('companion object', 1)[0]
        for field in ('paletteStyle', 'paletteSeedColor'):
            self.assertIn(f'{field} = source.{field}', style)
            self.assertNotIn(field, accents)
        for field in ('accentColor', 'primaryAccentColor', 'secondaryAccentColor'):
            self.assertIn(f'{field} = source.{field}', accents)
            self.assertNotIn(field, style)
        for field in ('themeMode', 'pureBlackEnabled', 'interfaceScale', 'iconCatColor'):
            self.assertNotIn(field, style + accents)

    def test_optional_colors_have_separate_persistent_keys_and_remove_on_default(self):
        store = source('data/model/AppearanceSettingsStore.kt')
        for field, key in (('paletteSeedColor', 'palette_seed'), ('primaryAccentColor', 'primary_accent'),
                           ('secondaryAccentColor', 'secondary_accent')):
            self.assertIn(f'{field} = preferences.optionalColor("{key}")', store)
            self.assertIn(f'.putOptionalColor("{key}", normalized.{field})', store)
        self.assertIn('if (contains(key)) getInt(key, 0) else null', store)
        self.assertIn('if (color == null) remove(key) else putInt(key, color)', store)

    def test_defaults_edit_draft_and_only_confirm_saves_the_owned_group(self):
        sheet = source('ui/screens/settings/AppearanceColorSheet.kt')
        reset = sheet.split('AssistantPreviewButton("默认"', 1)[1].split('AssistantPreviewButton("确定"', 1)[0]
        self.assertIn('editor.merge(draft, AppearanceSettings())', reset)
        self.assertIn('STYLE -> current.withColorStyle(source)', sheet)
        self.assertNotIn('onChange(', reset)
        self.assertEqual(1, sheet.count('onChange(editor.merge(state, draft))'))
        self.assertIn('ACCENTS -> current.withAccentColors(source)', sheet)
        self.assertIn('BUBBLES -> current.withUserBubbleColors(source)', sheet)
        self.assertIn('onDismiss = onDismiss, showCloseButton = false', sheet)

    def test_default_color_previews_do_not_read_applied_overrides(self):
        sheet = source('ui/screens/settings/AppearanceColorSheet.kt')
        self.assertIn('val defaults = AssistantPreviewPalette(buttons.dark)', sheet)
        self.assertIn('draft.resolvedPrimaryAccent ?: defaults.primary', sheet)
        self.assertIn('draft.secondaryAccentColor?.let(::Color) ?: defaults.secondary', sheet)
        self.assertNotIn('draft.resolvedPrimaryAccent ?: buttons.primary', sheet)

    def test_both_editors_share_assistant_sheet_and_separate_palettes(self):
        sheet = source('ui/screens/settings/AppearanceColorSheet.kt')
        self.assertIn('AssistantPreviewDialog(show = true', sheet)
        for label in ('主强调色', '次强调色', '界面色彩', '用户气泡'):
            self.assertIn(f'AppearanceColorPicker("{label}"', sheet)
        self.assertIn('.verticalScroll(rememberScrollState())', sheet)
        self.assertIn('footer = {', sheet)
        for action in ('default', 'confirm'):
            self.assertIn(f'testTag("appearance-color-{action}")', sheet)

    def test_all_nine_styles_have_selected_entries_and_different_resolver_inputs(self):
        sheet = source('ui/screens/settings/AppearanceColorSheet.kt')
        resolver = source('ui/app/AppearanceColors.kt')
        self.assertIn('AppearancePaletteStyle.entries.chunked(3)', sheet)
        self.assertIn('val selected = draft.paletteStyle == style', sheet)
        for style in ('TONAL_SPOT', 'NEUTRAL', 'VIBRANT', 'EXPRESSIVE', 'RAINBOW',
                      'FRUIT_SALAD', 'MONOCHROME', 'FIDELITY', 'CONTENT'):
            self.assertIn(f'AppearancePaletteStyle.{style} ->', sheet)
            self.assertIn(f'AppearancePaletteStyle.{style} ->', resolver)

    def test_palette_pointer_drag_preserves_hsv_without_rekeying_on_color(self):
        sheet = source('ui/screens/settings/AppearanceColorSheet.kt')
        self.assertIn('selection.selectSaturationValue(saturation, value)', sheet)
        self.assertIn('selection.selectHue(it)', sheet)
        self.assertIn('val changeColor by rememberUpdatedState(onChange)', sheet)
        self.assertEqual(2, sheet.count('.pointerInput(label)'))
        self.assertNotIn('.pointerInput(color)', sheet)
        for label in ('增加饱和度', '减少饱和度', '增加明度', '减少明度'):
            self.assertIn(f'CustomAccessibilityAction("{label}")', sheet)
        self.assertIn('Box(Modifier.weight(1f).height(44.dp)', sheet)

    def test_seed_is_optional_and_tints_white_surfaces_without_touching_accents(self):
        resolver = source('ui/app/AppearanceColors.kt')
        style = resolver.split('internal fun AppearanceSettings.styleColor(', 1)[1].split('internal fun resolveAppearanceColors(', 1)[0]
        self.assertIn('if (paletteSeedColor == null) return original', style)
        self.assertIn('max(base[1], .035f)', style)
        self.assertNotIn('primaryAccentColor', style)
        self.assertNotIn('secondaryAccentColor', style)
        self.assertIn('original.alpha', style)

    def test_theme_keeps_default_primary_container_foreground_and_black_mode(self):
        resolver = source('ui/app/AppearanceColors.kt')
        self.assertIn('onPrimaryContainer = readableAccent(primary, dark, container)', resolver)
        self.assertIn('if (primary != null)', resolver)
        self.assertEqual(2, resolver.count('if (dark && appearance.pureBlackEnabled) Color.Black'))
        theme = source('ui/app/AgentAppTheme.kt')
        self.assertIn('remember(appearance, isDark) { resolveAppearanceColors(appearance, isDark) }', theme)
        self.assertIn('LocalAppearanceSettings provides appearance', theme)
        self.assertEqual(2, theme.count('primary = miuixColors.primary'))

    def test_custom_text_accents_use_readable_color_without_changing_button_fill(self):
        for path in ('ui/components/ConversationPanePreview.kt', 'ui/components/subagent/ConversationAgentStyle.kt',
                     'ui/screens/chat/conversation/ChatAppearance.kt',
                     'ui/screens/chat/conversation/ConversationAppearance.kt'):
            self.assertIn('appearance.resolvedPrimaryAccent?.let { readableAccent(it, dark) }', source(path), path)
        self.assertIn('val primary = appearance.resolvedPrimaryAccent?.copy(alpha = 1f)', source('ui/app/AppearanceColors.kt'))
        self.assertIn('color = colors.onPrimaryContainer, modifier = Modifier.testTag("appearance-scale-value")',
                      source('ui/screens/settings/AppearanceSettingsScreen.kt'))

    def test_local_surfaces_follow_the_shared_style(self):
        for path in ('ui/components/UsagePresentation.kt', 'ui/components/ConversationPanePreview.kt',
                     'ui/components/subagent/ConversationAgentStyle.kt',
                     'ui/screens/chat/conversation/ConversationAppearance.kt',
                     'ui/screens/chat/conversation/ChatComposerSurface.kt',
                     'ui/screens/assistants/AssistantPreviewComponents.kt'):
            text = source(path)
            self.assertIn('LocalAppearanceSettings.current', text, path)
            self.assertIn('styleColor', text, path)
        self.assertIn('.map(appearance::styleColor)', source('ui/components/UsagePresentation.kt'))

    def test_slider_and_switch_share_accent_but_keep_default_reference(self):
        palette = source('ui/app/DaiyuPalette.kt')
        self.assertIn('LocalAppearanceSettings.current.resolvedPrimaryAccent ?: Color(0xFF82728F)', palette)
        text = source('ui/screens/settings/AppearanceSettingsScreen.kt')
        self.assertIn('thumbColor = DaiyuPalette.switchTrack', text)
        self.assertIn('activeTrackColor = DaiyuPalette.switchTrack', text)
        pet = source('ui/screens/settings/PetSettingsScreen.kt')
        self.assertIn('checkedTrackColor = DaiyuPalette.switchTrack', pet)
        self.assertIn('checkedBorderColor = DaiyuPalette.switchTrack', pet)
        self.assertNotIn('Slider(', pet)

    def test_duplicate_scale_editor_and_icon_decoration_are_removed(self):
        screen = source('ui/screens/settings/AppearanceSettingsScreen.kt')
        for obsolete in ('scaleEditor', 'scaleInput', 'SettingsRow("界面缩放"', 'DaiyuIcons.AppIcon'):
            self.assertNotIn(obsolete, screen)
        icon = screen.split('item(key = "appearance_icon")', 1)[1].split('item(key = "appearance_scale")', 1)[0]
        self.assertNotIn('leading =', icon)
        self.assertNotIn('summary =', icon)
        self.assertIn('iconContent(iconExpanded)', icon)
        self.assertIn('testTag("appearance-scale")', screen)

    def test_assistant_create_and_done_use_independent_button_roles(self):
        picker = source('ui/screens/assistants/AssistantPickerDialogs.kt')
        self.assertIn('testTag("assistant-new"), secondary = true', picker)
        self.assertIn('testTag("assistant-done"), primary = true', picker)
        buttons = source('ui/screens/assistants/AssistantPreviewComponents.kt')
        self.assertIn('secondary -> colors.secondaryContainer', buttons)
        self.assertIn('secondary -> colors.onSecondary', buttons)
        self.assertIn('colors.appearance.resolvedPrimaryAccent ?: if (colors.dark)', buttons)

    def test_android_sources_cover_save_reload_reset_and_modal_cancel(self):
        tests = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
        persistence = (tests / 'AppearanceSettingsPersistenceTest.kt').read_text()
        for name in ('testColorDefaultsResetOnlyTheirOwnPersistentKeys', 'testAllColorStylesRoundTripWithoutChangingAccents'):
            self.assertIn(name, persistence)
        chrome = (tests / 'SettingsChromeActivityTest.kt').read_text()
        self.assertIn('assertAppearanceColorSheetAndDismiss(title)', chrome)
        self.assertIn('testColorSheetsKeepDefaultsAndDraftsIndependentUntilConfirm', chrome)
        self.assertIn('Espresso.pressBack()', chrome)


if __name__ == '__main__':
    unittest.main()
