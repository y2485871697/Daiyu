"""Keep expensive projections off the frame path without changing execution authority."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class PagePerformanceContract(unittest.TestCase):
    def test_streaming_reuses_mounted_projections_independently_of_the_small_lru(self):
        source = (MAIN / 'ui/screens/chat/conversation/ToolPresentationCache.kt').read_text()
        self.assertIn('IdentityHashMap<MessageBlock.Tool, ToolCardPresentations.Presentation?>()', source)
        self.assertLess(source.index('mounted[block]?.let { return it }'), source.index('val key = key(block)'))
        self.assertIn('mounted.keys.retainAll(sources.keys)', source)
        self.assertIn('mounted.clear()', source)
        mapper = (MAIN / 'ui/screens/chat/conversation/ChatTurnMapper.kt').read_text()
        self.assertIn('presentations?.retain(history)', mapper)
        runtime = (ROOT / 'app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolPresentationCacheTest.kt').read_text()
        self.assertIn('mountedHistoryLargerThanTheLruBudgetDoesNotReparseOnEveryStreamChunk', runtime)

    def test_sidebar_navigation_does_not_republish_chat_before_changing_pages(self):
        source = (MAIN / 'MainActivity.kt').read_text()
        for entry in ('onOpenSettings', 'onOpenModelProviders', 'onOpenUsageStats', 'onOpenSkills', 'onOpenPermissions', 'onOpenAssistants'):
            body = source.split(entry + ' = {', 1)[1].split('}', 1)[0]
            self.assertIn('dismissSidebarState()', body)
            self.assertNotIn('dismissSidebar()', body)

    def test_avatars_and_terminal_annotations_are_prepared_on_workers(self):
        avatars = (MAIN / 'ui/screens/assistants/AssistantAvatarLoader.kt').read_text()
        self.assertIn('withContext(Dispatchers.IO) { assets.avatarBitmap(fileName) }', avatars)
        for name in ('AssistantEditScreen.kt', 'AssistantPickerDialogs.kt'):
            source = (MAIN / 'ui/screens/assistants' / name).read_text()
            self.assertIn('rememberAssistantAvatar(', source)
            self.assertNotIn('assets.avatarBitmap(', source)
        terminal = (MAIN / 'ui/screens/terminal/TerminalConsole.kt').read_text()
        self.assertIn('withContext(Dispatchers.Default) { prepareTerminalStyledOutput(text, color) }', terminal)

    def test_large_settings_lists_use_row_granularity_and_one_selection_index(self):
        skills = (MAIN / 'ui/screens/settings/SkillManagementScreen.kt').read_text()
        self.assertIn('itemsIndexed(entries', skills)
        self.assertNotIn('item(key = "skills-group:', skills)
        models = (MAIN / 'ui/screens/settings/LocalCatalogModelsScreen.kt').read_text()
        self.assertIn('remember(snapshot) { snapshot?.selectionIndex().orEmpty() }', models)
        self.assertIn('val ticket = selectionIndex[model.id]', models)
        self.assertNotIn('snapshot?.selectionFor(model.id)', models)
        usage = (MAIN / 'ui/screens/stats/UsageStatsScreen.kt').read_text()
        self.assertIn('itemsIndexed(provider.models', usage)
        self.assertIn('value = withContext(Dispatchers.Default)', usage)
        self.assertIn('val projection = key(projectionKey)', usage)
        self.assertNotIn('val projection = key(usage,', usage)

    def test_blocked_assistant_management_does_not_request_a_sheet_exit(self):
        picker = (MAIN / 'ui/screens/assistants/AssistantPickerDialogs.kt').read_text()
        self.assertIn('onLongClick = { if (canInteract()) finishSelection { if (canInteract()) actionProfileId = profile.id } }', picker)

    def test_cached_model_tickets_do_not_reuse_editor_or_confirmation_authority(self):
        models = (MAIN / 'ui/screens/settings/LocalCatalogModelsScreen.kt').read_text()
        self.assertIn('editorStale = false; editing = ticket.copy()', models)
        self.assertIn('editing = null; deleting = selection.copy()', models)
        self.assertIn('if (deleting === selection && current() && matches(selection))', models)
        runtime = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/LocalCatalogModelsActivityTest.kt').read_text()
        self.assertIn('testCancelledEditorCallbacksCannotModifyOrDismissFreshEditor', runtime)
        self.assertIn('assertTrue(stale()); assertTrue(staleCancel())', runtime)
