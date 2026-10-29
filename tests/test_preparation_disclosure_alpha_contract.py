from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class PreparationDisclosureAlphaContract(unittest.TestCase):
    def test_deleted_assistant_snapshot_uses_existing_preparation_cleanup(self):
        source = (MAIN / 'ChatController.kt').read_text(encoding='utf-8')
        ordinary = source.index('val ordinaryRun = platformAction == null')
        prepared = source.index('val prepared = try {', ordinary)
        snapshot = source.index('val ordinaryAssistant = if (ordinaryRun)', ordinary)
        self.assertGreater(snapshot, prepared)
        self.assertIn('checkNotNull(assistantStore.read(assistantContext.assistantId))', source[snapshot:snapshot + 220])
        fixture = (TEST / 'ControllerAssistantBindingTest.kt').read_text(encoding='utf-8')
        regression = fixture.split('fun testDeletedDuringPreparationRollsBackWithoutOpeningAndRetainsComposer()', 1)[1].split('\n    fun ', 1)[0]
        for required in ['assertNull(f.controller.send(null, "keep"))', 'assertNotNull(f.controller.problem)',
                         'assertEquals("keep", f.database.draft())', 'assertTrue(f.database.conversations().isEmpty())',
                         'f.controller.assistantStore.select("other")', 'assertEquals("other", accepted.assistantId)',
                         'assertEquals(2, factories.get())', 'assertEquals(0, opened.get())']:
            self.assertIn(required, regression)

    def test_parent_disclosure_is_not_part_of_reasoning_saveable_identity(self):
        source = (MAIN / 'ui/screens/chat/conversation/WorkProcessCard.kt').read_text(encoding='utf-8')
        self.assertIn('key(ownerKey, "reasoning", reported.itemId) {', source)
        self.assertNotIn('key(ownerKey, "reasoning", reported.itemId, expanded)', source)
        self.assertLess(source.index('val reasoningState = rememberSaveableStateHolder()'), source.index('AnimatedVisibility(expanded)'))
        self.assertIn('reasoningState.SaveableStateProvider(reported.itemId)', source)
        fixture = (TEST / 'ui/screens/chat/conversation/ReasoningWorkflowTest.kt').read_text(encoding='utf-8')
        self.assertIn('assertDisclosure(first, expanded = false)', fixture)
        self.assertIn('assertDisclosure(second, expanded = true)', fixture)
        self.assertIn('ui.onNodeWithText("second summary").assertExists().assertIsDisplayed()', fixture)

    def test_translucent_dialog_ink_is_compared_after_same_frame_compositing(self):
        fixture = (TEST / 'WorkspaceNameDialogsActivityTest.kt').read_text(encoding='utf-8')
        sampler = fixture.split('private fun assertButtonRoles(', 1)[1].split('private data class ButtonRole', 1)[0]
        self.assertEqual(1, sampler.count('captureScreenNode('))
        self.assertIn('captureScreenNode(sheet)', sampler)
        self.assertIn('lerp(image[leftX, y], image[rightX, y]', sampler)
        self.assertIn('background.compositeOver(underlay)', sampler)
        self.assertIn('layouts.single().layoutInput.style.color', sampler)
        self.assertIn('background(MiuixTheme.colorScheme.background)', fixture)
        for channel in ['red', 'green', 'blue']:
            self.assertIn(f'expected.{channel}, actual.{channel}, .02f', fixture)


    def test_platform_approval_regression_observes_raw_run_authorization(self):
        fixture = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ControllerPlatformActionSendTest.kt").read_text()
        for token in ('field(catalog, "boundAuthorization")', 'field(catalog, "platformAction")',
                      'assertNull(run.platformAction)', 'run.authorization.allowedWrites.isEmpty()',
                      'assertFalse(preview.action.effect in run.authorization.allowedWrites)',
                      'testOrdinaryRunAfterConsumedApprovalHasFreshAuthorizationAndNoExactScope',
                      'assertNotSame(original.authorization, ordinary.authorization)',
                      'lastUserText == exactApprovalText.get()'):
            self.assertIn(token, fixture)

if __name__ == '__main__':
    unittest.main()
