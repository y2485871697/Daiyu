"""Wiring guards; actual wire/decode semantics are exercised by Kotlin JVM tests."""
from pathlib import Path
import unittest
R = Path(__file__).resolve().parents[1]
S = R / 'app/src/main/kotlin/app/daiyu/android'

class RikkaHubProtocolPortContractTest(unittest.TestCase):
    def test_protocol_selection_only_changes_mode_and_revokes_old_consent(self):
        source = (S/'ui/screens/settings/ModelSettingsScreen.kt').read_text()
        switch = source.split('internal fun ModelSettingsDraftUi.selectProtocol(', 1)[1].split('/**', 1)[0]
        self.assertIn('if (selected == protocol) this', switch)
        self.assertIn('protocol = selected', switch)
        self.assertIn('protocolChangeAuthorized = false', switch)
        for field in ('apiKey', 'customHeaders', 'responsesPath', 'headerFieldsValid', 'bodyFieldsValid', 'headerFieldRows', 'bodyFieldRows', 'anthropicVersion'):
            self.assertNotIn(field+' =', switch)

    def test_only_explicit_save_grants_protocol_change_authority(self):
        route = (S/'ui/screens/settings/ProviderManagementRoute.kt').read_text()
        self.assertIn('newProvider.draft === submitted', route)
        self.assertIn('submitted.savedProtocol == saved.protocol', route)
        self.assertIn('submitted.protocol != saved.protocol, submitted.requestOptionsReplacementAuthorized', route)
        self.assertIn('submitted.protocol != submitted.savedProtocol, enabled =', route)
        store = (S/'storage/ModelSettings.kt').read_text()
        self.assertIn('protocolChangeAuthorized: Boolean = false', store)
        self.assertIn('readProtocol() == settings.protocol || protocolChangeAuthorized', store)

    def test_protocol_and_path_are_not_account_replacement_signals(self):
        store = (S/'storage/ModelSettings.kt').read_text()
        change = store.split('val connectionChanged =', 1)[1].split('val previousOptionsValid', 1)[0]
        for field in ('endpoint', 'apiKey', 'customHeaders'):
            self.assertIn('previousConnection.'+field, change)
        self.assertNotIn('previousConnection.protocol', change)
        self.assertNotIn('previousConnection.responsesPath', change)
        self.assertIn('modelCatalog.configurationChange', store)
        self.assertIn('if (connectionChanged)', store)

    def test_inactive_path_is_retained_but_not_used_by_other_transports(self):
        self.assertNotIn('require(settings.responsesPath.isEmpty())', (S/'model/MessagesRequest.kt').read_text())
        self.assertNotIn('require(settings.responsesPath.isEmpty())', (S/'model/NativeCompletionRequest.kt').read_text())

    def test_main_and_child_still_share_frozen_request_policy(self):
        for path in ('ChatController.kt', 'delegation/SavedSubAgentRuntimeBindings.kt'):
            self.assertIn('ReasoningRequestPolicy.freeze', (S/path).read_text())
        policy = (S/'model/ReasoningRequestPolicy.kt').read_text()
        self.assertIn('RikkaHubReasoning.chat', policy)
        self.assertIn('RikkaHubReasoning.gemini', policy)
        self.assertIn('"adaptive"', policy)
        self.assertIn('"output_config"', policy)
        self.assertIn('require(!hasReasoning(body))', policy)

    def test_reported_thoughts_and_opaque_replay_are_distinct(self):
        stream = (S/'model/NativeCompletionStream.kt').read_text()
        for name in ('reasoning_content', 'reasoning_details', 'ReasoningDelta', 'ReasoningFinal', 'thoughtSignature'):
            self.assertIn(name, stream)
        self.assertNotIn('call.arguments.toString().ifEmpty', stream)
        self.assertIn('if (!done) throw ModelRequestException(ModelFailure.INCOMPLETE)', stream)

    def test_responses_confirm_tools_only_after_successful_terminal(self):
        stream = (S/'model/ResponsesStream.kt').read_text()
        section = stream.split('"response.output_item.done" ->', 1)[1].split('"response.completed" ->', 1)[0]
        self.assertNotIn('ToolReady', section)
        self.assertIn('calls.values.forEach { sink(ModelEvent.ToolReady(it)) }', stream)
        self.assertIn('if (failure is ModelDeliveryRejectedException)', stream)

    def test_server_tools_do_not_enter_the_local_tool_event_branch(self):
        stream = (S/'model/MessagesStream.kt').read_text()
        tail = stream.split('blocks.forEach { (index, block) ->', 1)[1]
        self.assertIn('"tool_use" -> deliver(ModelEvent.ToolReady', tail)
        self.assertNotIn('isServerToolUse', tail)
        self.assertIn('MessagesRequest.validateServerBlock', stream)

    def test_upstream_pin_and_migration_scope_are_documented(self):
        notice = (R/'THIRD_PARTY/rikkahub/NOTICE.md').read_text()
        self.assertIn('ad2c4430d50ba52392b2941367f98ef59614cb73', notice)
        self.assertIn('ChatCompletionsStreamDecoder.kt', notice)
        self.assertIn('Interactions', notice)
        self.assertIn('Vertex', notice)

if __name__ == '__main__':
    unittest.main()
