"""Source guards only; JVM wire tests and Android UI tests own runtime coverage."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
UI = MAIN / 'ui/screens/settings'


class ProviderSearchSaveContractTest(unittest.TestCase):
    def test_hosted_search_reuses_protocol_and_https_validation_not_a_host_allowlist(self):
        source = (MAIN / 'storage/ModelSettings.kt').read_text()
        validation = source.split('fun validateConnection()', 1)[1].split('class CredentialUnavailableException', 1)[0]
        self.assertIn('requestEndpoint()', validation)
        self.assertIn('requestOptions.validate(protocol)', validation)
        api_key_validation = validation.split('        if (oauth != null) {', 1)[0] + validation.split('        }', 1)[1]
        self.assertNotIn('requireEndpoint', api_key_validation)
        self.assertIn('CodexOAuthProtocol.requireEndpoint(endpoint, responsesPath)', validation)
        protocol = (MAIN / 'model/ResponsesSearchProtocol.kt').read_text()
        self.assertNotIn('fun requireEndpoint', protocol)
        self.assertNotIn('api.openai.com', protocol)
        self.assertIn('protocol == ProviderProtocol.RESPONSES', (MAIN / 'model/ProviderRequestOptions.kt').read_text())

    def test_search_is_only_edited_per_model_and_projected_when_freezing(self):
        provider = (UI / 'ModelSettingsScreen.kt').read_text()
        self.assertNotIn('Provider 托管网页搜索', provider)
        self.assertNotIn('openAiWebSearchEnabled', provider)
        editor = (UI / 'CatalogModelEditorDialog.kt').read_text()
        self.assertIn('SettingsSwitchRow("原生搜索", value.nativeSearch, enabled)', editor)
        form = (MAIN / 'model/catalog/CatalogModelEdit.kt').read_text()
        self.assertIn('record.settings.requestOptions.copy(openAiWebSearchEnabled = nativeSearch)', form)
        parameters = (MAIN / 'model/catalog/CatalogModelParameters.kt').read_text()
        self.assertIn('previous?.settings?.requestOptions?.openAiWebSearchEnabled', parameters)
        runtime = (MAIN / 'storage/ModelSettings.kt').read_text().split('internal fun loadForRun(', 1)[1].split('internal fun checkCatalogConfiguration', 1)[0]
        self.assertIn('selected?.settings?.requestOptions?.openAiWebSearchEnabled', runtime)
        self.assertIn('current.requestOptions.copy(', runtime)
        chat = (MAIN / 'model/NativeCompletionRequest.kt').read_text()
        self.assertIn('!gemini && settings.requestOptions.openAiWebSearchEnabled', chat)
        self.assertIn('request.put("web_search_options", JSONObject())', chat)

    def test_validation_errors_belong_to_the_current_editor(self):
        source = (UI / 'ProviderManagementRoute.kt').read_text()
        detail = source.split('private class ProviderDetail(', 1)[1].split('private class PausedProviderDraft', 1)[0]
        new = source.split('internal class NewProviderDraft(', 1)[1].split('private class ProviderConfirm', 1)[0]
        for owner in (detail, new):
            self.assertIn('var error by mutableStateOf<String?>(null)', owner)
        self.assertIn('newProvider.error = "未保存', source)
        self.assertIn('d.error = "未保存', source)
        self.assertNotIn('notice = "未保存', source)
        self.assertIn('errorText = newProvider.error', source)
        self.assertEqual(2, source.count('errorText = d.error'))
        self.assertIn('newProvider.error = null', source)
        self.assertIn('d.error = null', source)
        self.assertNotIn('ProviderAuthenticationPage(enabled, notice', source)

    def test_creation_mutation_reports_only_to_its_live_draft(self):
        source = (UI / 'ProviderManagementRoute.kt').read_text()
        self.assertIn('reportFailure: (String) -> Unit', source)
        self.assertIn('if (creating === newProvider) newProvider.error = message', source)
        mutation = source.split('fun mutate(', 1)[1].split('DisposableEffect(controller)', 1)[0]
        self.assertEqual(2, mutation.count('reportFailure('))
        self.assertIn('if (current(p))', mutation)

    def test_only_the_configuration_error_is_full_width_and_centered(self):
        source = (UI / 'ProviderSettingsComponents.kt').read_text()
        helper = source.split('internal fun ProviderFormError(', 1)[1].split('@Composable', 1)[0]
        self.assertIn('fillMaxWidth()', helper)
        self.assertIn('textAlign = TextAlign.Center', helper)
        self.assertIn('testTag("model-save-error")', helper)
        self.assertIn('ProviderFormError(errorText)', (UI / 'ModelSettingsScreen.kt').read_text())
        catalog = (UI / 'ProviderCatalogSettingsScreen.kt').read_text()
        self.assertNotIn('errorText?.let { SettingsNote', catalog)
        shared = (UI / 'SettingsUiComponents.kt').read_text().split('internal fun SettingsNote(', 1)[1]
        self.assertNotIn('TextAlign.Center', shared.split('\n}', 1)[0])
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ResponsesSearchSettingsActivityTest.kt').read_text()
        self.assertIn('performTextReplacement(before.apiKey)\n        reveal("model-save", lazyKey = "model_save").assertIsDisplayed()\n        node("model-save-error").assertDoesNotExist()', android)
        self.assertIn('awaitModelEditor()\n        reveal("model-save", lazyKey = "model_save").assertIsDisplayed()\n        node("model-save-error").assertDoesNotExist()', android)



if __name__ == '__main__':
    unittest.main()
