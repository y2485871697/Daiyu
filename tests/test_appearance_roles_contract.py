"""Colour responsibility wiring; visual and gesture checks remain Android tests."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
def source(path): return (SRC / path).read_text()

class AppearanceRolesContractTest(unittest.TestCase):
    def test_three_editor_groups_merge_only_their_fields_and_failures_keep_drafts(self):
        sheet = source('ui/screens/settings/AppearanceColorSheet.kt')
        for item in ('STYLE -> current.withColorStyle(source)', 'ACCENTS -> current.withAccentColors(source)',
                     'BUBBLES -> current.withUserBubbleColors(source)', 'editor.merge(draft, AppearanceSettings())',
                     'onChange(editor.merge(state, draft))', 'if (saved) onDismiss()', 'saveFailed = !saved'):
            self.assertIn(item, sheet)
        self.assertIn('SettingsRow("聊天气泡"', source('ui/screens/settings/AppearanceSettingsScreen.kt'))
        host = source('MainActivity.kt')
        self.assertIn('onColorChange = { saveAppearance(it) }', host)
        save = host.split('private fun saveAppearance(value:', 1)[1].split('internal interface VoiceHostPlayer', 1)[0]
        self.assertIn('): Boolean', save)
        self.assertLess(save.index('appearanceStore.save(value)'), save.index('saved = true'))

    def test_modes_have_durable_keys_and_default_removes_only_custom_color(self):
        model = source('data/model/AppearanceSettings.kt')
        store = source('data/model/AppearanceSettingsStore.kt')
        for mode in ('OFF("off")', 'THEME("theme")', 'CUSTOM("custom")'): self.assertIn(mode, model)
        for key in ('user_bubble_mode', 'user_bubble_color'): self.assertIn(key, store)
        self.assertIn('check(read() == normalized)', store)
        self.assertIn('fenced[preferences] ?: read()', store)
        self.assertIn('fenced.getOrPut(preferences) { read() }', store)

    def test_custom_bubbles_are_user_only_and_inline_editor_has_its_own_readable_surface(self):
        chat = source('ui/screens/chat/conversation/ChatMessageList.kt')
        self.assertIn('color = conversationColors().bubbleText', chat)
        assistant = chat.split('internal fun AssistantTurn(', 1)[1].split('internal fun UserMessageText', 1)[0]
        self.assertNotIn('.background(', assistant)
        self.assertNotIn('bubble', assistant.lower())
        resolve = source('ui/app/AppearanceColors.kt').split('internal fun resolveUserBubbleColors(', 1)[1]
        self.assertIn('UserBubbleMode.OFF -> UserBubbleColors(Color.Transparent, text)', resolve)
        self.assertIn('UserBubbleColors(fill, colorContentColor(fill))', resolve)
        inline = source('ui/screens/chat/conversation/InlineMessageEdit.kt')
        self.assertIn('background(MiuixTheme.colorScheme.surface', inline)
        self.assertIn('readableAccent(MaterialTheme.colorScheme.primary, LocalAppearanceDark.current, surface)', inline)
        self.assertIn('MaterialTheme.colorScheme.copy(primary = accent)', inline)

    def test_draft_preview_uses_production_role_resolvers_without_saving(self):
        sheet = source('ui/screens/settings/AppearanceColorSheet.kt')
        preview = sheet.split('private fun AppearanceDraftPreview(', 1)[1].split('private fun AppearanceColorPicker', 1)[0]
        for name in ('resolveAppearanceColors', 'resolveAppearanceActions', 'resolveUserBubbleColors', 'ConversationPanePreviewPalette'):
            self.assertIn(name, preview)
        self.assertNotIn('onChange(', preview)
        self.assertIn('val preview = editor.merge(state, draft)', sheet)

    def test_selected_row_tools_and_filter_have_distinct_roles(self):
        palette = source('ui/components/ConversationPanePreview.kt')
        pane = source('ui/components/ConversationSidePaneScaffold.kt')
        for role in ('selectionContainer', 'selectionBorder', 'selectionContent', 'utilityContainer'):
            self.assertIn(role, palette); self.assertIn(role, pane)
        self.assertNotIn('conversationPanePreviewPalette().bubble', pane)
        self.assertIn('val danger = Color(0xFFBC6575)', palette)
        self.assertIn('val folder = if (appearance.primaryAccentColor != null) theme.primaryContainer', palette)
        self.assertIn('val selectionBackground = colors.selectionContainer', source('ui/screens/chat/conversation/ChatMenus.kt'))
        self.assertIn('val selectedBackground = colors.selectionContainer', source('ui/screens/chat/conversation/ChatModelPicker.kt'))

    def test_sending_is_primary_and_never_reads_secondary_accent(self):
        actions = source('ui/screens/chat/conversation/ChatAppearance.kt').split('internal fun chatActionColors()', 1)[1].split('internal data class ChatChromeColors', 1)[0]
        self.assertNotIn('secondaryAccentColor', actions)
        self.assertIn('val filled = LocalAppearanceDark.current || customPrimary', actions)
        self.assertIn('appendContainer = buttons.primary', actions)

    def test_settings_subagent_confirms_use_danger_containers_without_changing_menus(self):
        settings = source('ui/SubAgentSettingsScreen.kt')
        self.assertEqual(2, settings.count('containerColor = MaterialTheme.colorScheme.errorContainer'))
        self.assertEqual(2, settings.count('contentColor = MaterialTheme.colorScheme.onErrorContainer'))
        self.assertIn('repository.removePreset(preset.id)', settings)
        self.assertIn('editor.remove(profile.id)', settings)

    def test_fixed_delete_role_is_opt_in_and_menu_rows_keep_original_style(self):
        buttons = source('ui/screens/assistants/AssistantPreviewComponents.kt')
        self.assertIn('val deletion = deletionAppearance && !actionRow', buttons)
        self.assertIn('deletion -> colors.dangerContainer', buttons)
        self.assertIn('deletion -> colors.onDangerContainer', buttons)
        dialogs = source('ui/components/MiuixDialogActions.kt')
        self.assertIn('color = MiuixTheme.colorScheme.errorContainer', dialogs)
        self.assertIn('textColor = MiuixTheme.colorScheme.onErrorContainer', dialogs)
        self.assertIn('else if (destructive)', dialogs)
        revoke = source('ui/screens/settings/WorkspaceSettingsScreen.kt')
        self.assertNotIn('deletionAppearance', revoke)
        picker = source('ui/screens/assistants/AssistantPickerDialogs.kt')
        menu = picker.split('internal fun AssistantActionsDialog',1)[1].split('internal fun AssistantDeleteDialog',1)[0]
        self.assertNotIn('deletionAppearance', menu)

    def test_subagent_delete_is_explicit_and_disabled_guards_remain(self):
        dialog = source('ui/components/SubAgentProfileConfigDialog.kt')
        for fragment in ('deletion = editing', 'primaryColor = MiuixTheme.colorScheme.errorContainer',
                         'onPrimaryColor = MiuixTheme.colorScheme.onErrorContainer',
                         'if (deletion) deletionButtonColors()', 'session.canDelete(latestEditor, latestEnabled)',
                         'session.canDelete(latestEditor, latestEnabled, ticket)'):
            self.assertIn(fragment, dialog)

    def test_direct_delete_controls_use_semantic_pair_or_foreground_only(self):
        for path in ('ui/screens/settings/ProviderDirectoryPage.kt', 'ui/screens/settings/RequestBodyFieldsEditor.kt',
                     'ui/screens/settings/GenerationSettingsScreen.kt'):
            self.assertIn('deletionButtonColors()', source(path))
        inline = source('ui/screens/settings/ProviderInlineEditors.kt')
        self.assertIn('containerColor = MiuixTheme.colorScheme.errorContainer', inline)
        self.assertIn('contentColor = MiuixTheme.colorScheme.onErrorContainer', inline)
        self.assertIn('tint = LocalContentColor.current', inline)
        self.assertIn('tint = MiuixTheme.colorScheme.error)', source('ui/screens/chat/conversation/MessageActionBar.kt'))
        for path in ('ui/screens/settings/LocalCatalogModelsScreen.kt', 'ui/screens/settings/McpSettingsScreen.kt',
                     'ui/screens/settings/PrivateWorkspacePanel.kt', 'ui/screens/chat/ManageChatsScreen.kt'):
            self.assertIn('deletionAppearance = true', source(path))

    def test_explicit_theme_mode_and_tertiary_are_bridged_without_recoloring_browser(self):
        for path in ('ui/components/ConversationPanePreview.kt', 'ui/components/UsagePresentation.kt',
                     'ui/components/subagent/ConversationAgentStyle.kt', 'ui/screens/assistants/AssistantPreviewComponents.kt',
                     'ui/screens/chat/conversation/ConversationAppearance.kt', 'ui/screens/settings/LinuxSettingsTheme.kt'):
            self.assertIn('LocalAppearanceDark.current', source(path))
        theme = source('ui/app/AgentAppTheme.kt')
        self.assertIn('LocalAppearanceDark provides isDark', theme)
        self.assertEqual(2, theme.count('onTertiaryContainer = if (tertiaryAccent != null)'))
        self.assertIn('Color(0xFF528AD2)', source('browser/ported/ui/theme/Theme.kt'))

if __name__ == '__main__': unittest.main()
