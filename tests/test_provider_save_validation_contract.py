"""Source-only guards for deferred provider validation; Android tests own UI behavior."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class ProviderSaveValidationContractTest(unittest.TestCase):
    def test_error_starts_empty_resets_with_draft_and_is_only_computed_on_submit(self):
        source = (SRC / 'ModelSettingsScreen.kt').read_text()
        self.assertIn('var fieldsSaveError by remember(draft) { mutableStateOf<String?>(null) }', source)
        submit = source.split('val submit: () -> Unit = {', 1)[1].split('val changeDraft:', 1)[0]
        self.assertIn('if (latestEnabled && latestDraft === draft && (!oauthOnly || draft.oauth != null))', submit)
        self.assertIn('connection.cancel(); balance.cancel()', submit)
        self.assertIn('fieldsSaveError = draft.inlineFieldsSaveError()', submit)
        self.assertIn('if (fieldsSaveError == null) onSave(draft)', submit)
        self.assertEqual(1, source.count('draft.inlineFieldsSaveError()'))

    def test_errors_are_not_rendered_in_field_or_gateway_editors(self):
        source = (SRC / 'ProviderInlineEditors.kt').read_text()
        editors = source.split('internal fun ProviderInlineFields(', 1)[1]
        self.assertNotIn('SettingsNote(', editors)
        self.assertNotIn('val notice =', editors)
        self.assertIn('headerFieldsValid = error == null', editors)
        self.assertIn('bodyFieldsValid = error == null', editors)
        self.assertIn('headerFieldsError = error', editors)
        self.assertIn('bodyFieldsError = error', editors)
        self.assertIn('TextButton("清除无效配置"', editors)

    def test_summary_retains_all_field_validation_and_uses_safe_messages(self):
        source = (SRC / 'ProviderInlineEditors.kt').read_text()
        helper = source.split('internal fun ModelSettingsDraftUi.inlineFieldsSaveError()', 1)[1].split('@Composable', 1)[0]
        for token in ('!headerFieldsValid', 'headerFieldsError', '!bodyFieldsValid', 'bodyFieldsError',
                      'ProviderHeaders.parse(customHeaders)', 'ProviderRequestOptions(customBody = customBody).validate(protocol)',
                      'sessionGateway.validate()', 'joinToString("\\n")'):
            self.assertIn(token, helper)
        for token in ('exceptionOrNull', '.message', 'toString()', 'save(', 'setClipboard', 'Log.'):
            self.assertNotIn(token, helper)

    def test_save_stays_clickable_for_validation_and_summary_is_before_button(self):
        source = (SRC / 'ModelSettingsScreen.kt').read_text()
        footer = source.split('item(key = "model_save")', 1)[1].split('if (onDelete != null)', 1)[0]
        self.assertIn('val fieldError = fieldsSaveError', footer)
        self.assertIn('if (fieldError != null) {', footer)
        self.assertIn('if (providerName == null) SettingsNote(fieldError, error = true) else ProviderFormError(fieldError)', footer)
        self.assertIn('else if (!errorText.isNullOrBlank())', footer)
        self.assertLess(footer.index('SettingsNote(fieldError'), footer.index('val saveModifier'))
        self.assertIn('this[RetainedModelSaveAction] = submit', footer)
        self.assertEqual(1, footer.count('enabled = enabled,'))
        self.assertIn('enabled = enabled && (!oauthOnly || draft.oauth != null),', footer)
        self.assertNotIn('&& draft.headerFieldsValid', footer)
        self.assertNotIn('&& draft.bodyFieldsValid', footer)

    def test_android_regressions_cover_actual_clicks_no_write_and_error_lifetime(self):
        source = (ANDROID / 'ui/screens/settings/ProviderSaveValidationTest.kt').read_text()
        for name in ('testInvalidRowsOnlyShowOneSummaryAboveSaveAfterClickAndNeverSubmit',
                     'testEditingClearsFailedAttemptAndCorrectedHeaderCanSave',
                     'testMalformedOriginalConfigurationIsHiddenUntilSaveAndClearStillWorks',
                     'testInvalidGatewayUsesTheSameSaveSummary',
                     'testDisabledAndStaleSaveCallbacksCannotCreateErrorsOrSubmit',
                     'testHostSaveFailureAppearsOnlyAfterSaveAndClearsOnEdit'):
            self.assertIn('fun ' + name + '()', source)
        for token in ('providerName = "提供商"', 'save.performClick()', '.assertDoesNotExist()',
                      'boundsInRoot.bottom <=', 'boundsInRoot.top', 'assertEquals(0, saves)',
                      'assertEquals(1, saves)', 'assertEquals(0, fixture.opens.get())',
                      'mainThread { stale() }', 'mainThread { current() }', 'assertIsNotEnabled()'):
            self.assertIn(token, source)

    def test_existing_invalid_save_helper_and_headers_keep_real_submission_checks(self):
        helper = (ANDROID / 'ComposeActivityTestCase.kt').read_text().split('protected fun attemptBlockedInlineBodySave()', 1)[1].split('protected fun SemanticsNodeInteraction.', 1)[0]
        self.assertIn('assertIsEnabled()', helper)
        self.assertIn('button.performClick()', helper)
        self.assertIn('RetainedModelSaveAction', helper)
        self.assertIn('mainThread { submit() }', helper)
        headers = (ANDROID / 'ProviderHeadersActivityTest.kt').read_text()
        self.assertIn('assertFalse(draft.headerFieldsValid)', headers)
        self.assertIn('assertNull(saved)', headers)
        self.assertIn('JSONObject(draft.customHeaders).getString("X-Project")', headers)
        self.assertEqual(2, headers.count('ui.onNodeWithText(checkNotNull(draft.headerFieldsError)).assertExists()'))


if __name__ == '__main__':
    unittest.main()
