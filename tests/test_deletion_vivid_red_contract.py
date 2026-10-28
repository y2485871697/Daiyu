"""Original-reference delete colors must not recolor errors, menus, or neutral message tools."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
def source(path): return (SRC / path).read_text()

class DeletionOriginalRedContractTest(unittest.TestCase):
    def test_delete_role_is_original_reference_red_and_independent_of_the_theme(self):
        resolver = source('ui/app/AppearanceColors.kt').split('internal fun destructiveColors()', 1)[1]
        self.assertIn('Color(0xFF92454F)', resolver)
        self.assertIn('DestructiveColors(red, Color.White, red)', resolver)
        for forbidden in ('DaiyuPalette', 'primaryAccentColor', 'secondaryAccentColor', 'paletteSeedColor'):
            self.assertNotIn(forbidden, resolver)
        palette = source('ui/app/DaiyuPalette.kt')
        self.assertIn('errorContainer = Color(0xFFFBECEF)', palette)
        self.assertIn('errorContainer = Color(0xFF3D222B)', palette)

    def test_miuix_and_preview_buttons_share_the_dedicated_pair(self):
        actions = source('ui/components/MiuixDialogActions.kt')
        self.assertIn('color = destructiveColors().container', actions)
        self.assertIn('textColor = destructiveColors().content', actions)
        preview = source('ui/screens/assistants/AssistantPreviewComponents.kt')
        self.assertIn('private val destructive = destructiveColors()', preview)
        self.assertIn('deletion -> colors.dangerContainer', preview)
        self.assertIn('deletion -> colors.onDangerContainer', preview)
        self.assertIn('val deletion = deletionAppearance && !actionRow', preview)

    def test_all_filled_delete_entry_points_stop_borrowing_general_error_containers(self):
        for path in ('ui/SubAgentSettingsScreen.kt', 'ui/components/SubAgentProfileConfigDialog.kt',
                     'ui/screens/settings/ProviderInlineEditors.kt', 'ui/screens/settings/SkillManagementScreen.kt',
                     'ui/screens/chat/ManageChatsScreen.kt'):
            text = source(path)
            self.assertIn('destructiveColors().container', text, path)
            self.assertIn('destructiveColors().content', text, path)
            self.assertNotIn('colorScheme.errorContainer', text, path)
            self.assertNotIn('colorScheme.onErrorContainer', text, path)
        for path in ('ui/screens/settings/ModelSettingsScreen.kt', 'ui/screens/settings/CatalogModelEditorDialog.kt',
                     'ui/screens/settings/ProviderDirectoryPage.kt', 'ui/screens/settings/LocalCatalogModelsScreen.kt'):
            self.assertIn('deletionButtonColors()', source(path), path)
        self.assertIn('deletionAppearance = true', source('ui/components/FolderDeleteConfirmation.kt'))

    def test_workspace_selection_delete_uses_red_without_recoloring_errors(self):
        text = source('ui/components/ConversationWorkspaceSheet.kt')
        delete = text.split('ConversationAgentButton("删除",', 1)[1].split('onClick = { onDeleteWorkspaces(selectedWorkspaces) })', 1)[0]
        self.assertIn('primaryColor = destructiveColors().container', delete)
        self.assertIn('onPrimaryColor = destructiveColors().content', delete)
        self.assertIn('testTag("workspace-delete")', delete)
        self.assertIn('enabled = !busy && selectedWorkspaces.isNotEmpty()', delete)
        self.assertIn('MaterialTheme.colorScheme.error', text)

    def test_message_toolbar_and_nondelete_destructive_actions_remain_distinct(self):
        bar = source('ui/screens/chat/conversation/MessageActionBar.kt')
        delete = bar.split('TooltipBox(text = "删除", enabled = messageActionsEnabled)', 1)[1].split('if (timestamp.isNotBlank())', 1)[0]
        self.assertIn('tint = colors.muted', delete)
        self.assertNotIn('destructiveColors', delete)
        self.assertNotIn('deletionAppearance', source('ui/screens/settings/WorkspaceSettingsScreen.kt'))
        actions = source('ui/components/MiuixDialogActions.kt')
        self.assertIn('else if (destructive)', actions)
        self.assertIn('color = MiuixTheme.colorScheme.error', actions)

    def test_pixel_regressions_use_literal_color_oracles_and_real_selected_provider_route(self):
        android = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
        for name in ('DeletionButtonAppearanceTest.kt', 'ProviderDirectorySelectionActivityTest.kt'):
            test = (android / name).read_text()
            self.assertIn('Color(0xFF92454F)', test)
            self.assertIn('captureScreenNode', test)
            self.assertIn('abs(pixel.green - expected.green)', test)
        shared = (android / 'DeletionButtonAppearanceTest.kt').read_text()
        self.assertIn('assertIsNotEnabled()', shared)
        self.assertIn('MiuixDialogActions(', shared)
        self.assertIn('AssistantPreviewButton(', shared)
        selected = (android / 'ProviderDirectorySelectionActivityTest.kt').read_text()
        self.assertIn('assertOriginalRed("provider-delete-selected")', selected)
        self.assertIn('assertOriginalRed("dialog-confirm")', selected)
        self.assertIn('assertEquals(before, fixture.controller.providers.snapshot())', selected)

if __name__ == '__main__': unittest.main()
