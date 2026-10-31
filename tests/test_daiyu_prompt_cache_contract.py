"""Daiyu-owned prompt-cache settings and real request wiring."""
from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[1]
M=R/'app/src/main/kotlin/app/daiyu/android'
U=M/'ui/screens/settings'
class DaiyuPromptCacheContractTest(unittest.TestCase):
 def test_selector_replaces_raw_body_in_the_anthropic_card(self):
  s=(U/'ModelSettingsScreen.kt').read_text()
  self.assertNotIn('RequestBodyFieldsEditor(',s)
  self.assertIn('item(key = "provider_cache_retention")',s)
  self.assertIn('DaiyuPromptCacheFields(draft, enabled, changeDraft)',s)
  self.assertIn('!speechOnly && draft.protocol == ProviderProtocol.MESSAGES',s)
 def test_cache_has_its_own_section_instead_of_belonging_to_headers(self):
  s=(U/'ModelSettingsScreen.kt').read_text()
  header=s.split('item(key = "provider_headers") {',1)[1].split('if (!speechOnly &&',1)[0]
  cache=s.split('item(key = "provider_cache_retention") {',1)[1].split('if (providerName != null)',1)[0]
  self.assertIn('SettingsSectionTitle("自定义请求头")',header)
  self.assertNotIn('DaiyuPromptCacheFields(',header)
  self.assertIn('SettingsSectionTitle("提示缓存")',cache)
  self.assertLess(cache.index('SettingsSectionTitle("提示缓存")'),cache.index('SettingsCard { DaiyuPromptCacheFields('))
  self.assertNotIn('ProviderHeadersEditor(',cache)
 def test_choice_row_has_three_exact_labels_and_no_description(self):
  s=(U/'DaiyuPromptCacheFields.kt').read_text()
  for text in ['缓存保留时长','不保留','5分钟','1小时','ProviderChoiceRow(']:self.assertIn(text,s)
  self.assertNotIn('summary =',s)
  self.assertIn('draft.copy(promptCacheRetention =',s)
 def test_options_have_daiyu_identity_and_legacy_default(self):
  e=(M/'model/DaiyuPromptCacheRetention.kt').read_text()
  self.assertIn('Daiyu_prompt_cache',e)
  s=(M/'model/ProviderRequestOptions.kt').read_text()
  self.assertIn('val promptCacheRetention: DaiyuPromptCacheRetention = DaiyuPromptCacheRetention.ONE_HOUR',s)
  self.assertIn('DaiyuPromptCacheRetention.STORAGE_KEY',s)
 def test_provider_both_loads_and_saves_retention(self):
  s=(U/'ProviderManagementRoute.kt').read_text()
  self.assertIn('promptCacheRetention = requestOptions.promptCacheRetention',s)
  self.assertIn('promptCacheRetention = promptCacheRetention',s)
 def test_transport_caches_only_after_the_final_body_is_ready(self):
  s=(M/'model/MessagesModel.kt').read_text()
  self.assertIn('DaiyuPromptCaching.apply(',s)
  self.assertLess(s.index('SessionGateway.applyAnthropic('),s.index('DaiyuPromptCaching.apply('))
  self.assertLess(s.index('DaiyuPromptCaching.apply('),s.index('ImageRequestPreflight.prepare('))
  for f in ['NativeCompletionModel.kt','ResponsesModel.kt']:
   self.assertNotIn('DaiyuPromptCaching.apply(', (M/'model'/f).read_text())
 def test_helper_uses_protocol_cache_fields_without_local_control_keys(self):
  s=(M/'model/DaiyuPromptCaching.kt').read_text()
  for text in ['cache_control','ephemeral','ttl','DaiyuPromptCacheRetention.NONE']:
   self.assertIn(text,s)
  self.assertIn('JSONObject(request.toString())',s)
 def test_title_preserves_the_provider_retention(self):
  s=(M/'title/TitleModelCoordinator.kt').read_text()
  self.assertIn('promptCacheRetention = provider.requestOptions.promptCacheRetention',s)
 def test_retained_model_save_preserves_retention(self):
  s=(M/'MainActivity.kt').read_text()
  self.assertIn('promptCacheRetention = promptCacheRetention',s)
if __name__=='__main__':unittest.main()
