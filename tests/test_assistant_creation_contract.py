"""Source wiring guards; Android touch regressions require the approved build batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class AssistantCreationContractTest(unittest.TestCase):
    def test_create_event_is_required_and_forwarded_at_both_boundaries(self):
        picker = (SOURCE / 'ui/screens/assistants/AssistantPickerDialogs.kt').read_text()
        self.assertEqual(2, picker.count('    onCreate: () -> Unit,'))
        self.assertIn('AssistantPreviewButton(stringResource(R.string.assistant_new), onCreate,', picker)
        self.assertIn('onCreate = { if (canMutate()) onCreate() }', picker)
        self.assertNotIn('Creation remains a visual placeholder', picker)
        self.assertNotIn('AssistantPreviewButton(stringResource(R.string.assistant_new), {},', picker)

    def test_host_creates_only_after_admission_and_reuses_original_editor_route(self):
        main = (SOURCE / 'MainActivity.kt').read_text()
        host = main.split('assistantSelectionOwner?.let { owner -> key(owner) {', 1)[1].split('usageVisit?.let', 1)[0]
        self.assertIn('onCreate = { guard {', host)
        create = host.split('onCreate = { guard {', 1)[1].split('onDismiss =', 1)[0]
        self.assertIn('if (assistantOwnerMatches(owner) && retainedPage != "assistant-edit")', create)
        self.assertIn('editAssistant(assistantStore.create().id)', create)
        self.assertEqual(1, create.count('assistantStore.create()'))
        for forbidden in ('onDismiss()', 'dismissAssistantFlow()', 'assistantStore.select(', 'bindConversationAssistant('):
            self.assertNotIn(forbidden, create)
        edit = host.split('val editAssistant = { id: String ->', 1)[1].split('AssistantPickerDialog(', 1)[0]
        for token in ('assistantOwnerMatches(owner)', 'retainedPage != "assistant-edit"',
                      'assistantEditReturnPage = retainedPage', 'assistantEditReturnToSidebar = false',
                      'assistantEditingId = id; retainedPage = "assistant-edit"'):
            self.assertIn(token, edit)
        self.assertIn('onEdit = editAssistant', host)

    def test_real_touch_regressions_cover_create_save_cancel_and_stale_admission(self):
        tests = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
        navigation = (tests / 'AssistantNavigationActivityTest.kt').read_text()
        for name in ('testNewAssistantOpensEditorAndSavesWithoutChangingBinding',
                     'testNewAssistantCancelAndOutsideKeepCreatedDefaultsWithoutSavingDraft'):
            self.assertIn('fun ' + name, navigation)
        self.assertEqual(2, navigation.count('        selectBoundConversation()'))
        self.assertIn('val conversation = fixture.database.create(bound.id)', navigation)
        self.assertIn('mainThread { activity.select(conversation) }', navigation)
        self.assertNotIn('dismissOutside(fromSettings); openPicker(fromSettings)', navigation)
        for token in ('newButton.performClick()', 'mainThread { staleCreate() }',
                      'awaitUniqueTag("assistant-editor")', 'SqliteAssistantStore(fixture.database)',
                      'assertEquals(created.copy(name = name), restored.read(created.id))',
                      'listOf("cancel", "header", "system", "outside")'):
            self.assertIn(token, navigation)
        admission = (tests / 'AssistantPickerAdmissionTest.kt').read_text()
        self.assertIn('fun testStaleNewAssistantDoesNotDispatchCreate()', admission)
        self.assertIn('fun testNewAssistantDispatchesCreateWithoutSelectingOrTouchingAssets()', admission)
        self.assertNotIn('testNewAssistantPlaceholderDoesNotMutateProfilesOrSelection', admission)


if __name__ == '__main__':
    unittest.main()
