"""Provider form visibility must follow real request/default-policy wiring."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/app/daiyu/android"
UI = MAIN / "ui/screens/settings"
class ProviderCapabilityVisibilityContractTest(unittest.TestCase):
    def test_oauth_hint_is_removed_but_button_and_signed_in_status_remain(self):
        s=(UI/"ModelSettingsScreen.kt").read_text()
        self.assertNotIn("以 OpenAI 账号登录，授权后可创建提供商。", s)
        self.assertIn('if (draft.oauth != null) SettingsNote("已登录 OpenAI Codex")', s)
        self.assertIn('testTag("provider-oauth-login")', s)
    def test_gateway_editor_is_absent_from_all_settings_pages(self):
        s=(UI/"ModelSettingsScreen.kt").read_text()
        self.assertNotIn('item(key = "provider_gateway")',s)
        self.assertNotIn('SettingsSectionTitle("会话 ID")',s)
        self.assertNotIn('ProviderGatewayEditor(',s)
    def test_headers_are_hidden_for_speech_and_body_only_for_anthropic(self):
        s=(UI/"ModelSettingsScreen.kt").read_text()
        self.assertIn('if (!speechOnly) item(key = "provider_headers")',s)
        self.assertIn('if (!speechOnly && draft.protocol == ProviderProtocol.MESSAGES) item(key = "provider_cache_retention")',s)
        self.assertIn('draft.providerRequestDraft(speechOnly).inlineFieldsSaveError()',s)
    def test_only_new_oauth_page_loses_balance(self):
        s=(UI/"ModelSettingsScreen.kt").read_text()
        self.assertIn('if (!speechOnly && onOAuthLogin == null) item(key = "provider_balance")',s)
    def test_policy_is_used_for_new_defaults_and_effective_settings(self):
        s=(UI/"ProviderManagementRoute.kt").read_text()
        self.assertIn('sessionGateway = newProviderSessionGateway(protocol, speechOnly)',s)
        self.assertIn('val effective = providerRequestDraft()',s)
        self.assertIn('customBody = effective.customBody',s)
        self.assertIn('ProviderHeaders.parse(effective.customHeaders)',s)
        self.assertNotIn('require(submitted.headerFieldsValid && submitted.bodyFieldsValid)',s)
    def test_retained_single_model_editor_uses_the_same_hidden_field_policy(self):
        s=(MAIN/"MainActivity.kt").read_text()
        self.assertIn('customBody = providerRequestDraft().customBody',s)
    def test_user_policy_does_not_disable_app_owned_reasoning_title_or_summary_body(self):
        s=(MAIN/"model/ProviderRequestOptions.kt").read_text()
        self.assertIn('val merged = if (customBody.isEmpty()) base else RequestBodyMerge.merge(base, parseBody())',s)
    def test_runtime_receives_gateway_enablement_for_body_and_all_headers(self):
        gateway=(MAIN/"model/sessiongateway/SessionGateway.kt").read_text()
        self.assertGreaterEqual(gateway.count('!rule.enabled'),3)
        for file in ['NativeCompletionModel.kt','ResponsesModel.kt','MessagesModel.kt']:
            s=(MAIN/'model'/file).read_text()
            line=next(line for line in s.splitlines() if 'SessionGateway.headers(' in line)
            self.assertIn('settings.requestOptions.sessionGateway',line)
        s=(MAIN/'model/MessagesModel.kt').read_text()
        line=next(line for line in s.splitlines() if 'SessionGateway.applyAnthropic(' in line)
        self.assertIn('settings.requestOptions.sessionGateway',line)
if __name__=='__main__':unittest.main()
