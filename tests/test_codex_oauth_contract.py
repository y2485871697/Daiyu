"""Static OAuth wiring guards; protocol JVM tests own executable token/callback coverage."""
from pathlib import Path
import unittest,xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[1];M=R/'app/src/main/kotlin/app/daiyu/android'
def read(p):return (M/p).read_text()
class OAuthContract(unittest.TestCase):
 def test_real_ui_launcher(self):
  s=read('ui/screens/settings/ProviderManagementRoute.kt');self.assertIn('onOAuth = {',s);self.assertIn('oauthLauncher.launch(',s);self.assertIn('.putExtra("attempt", oauthAttempt)',s)
 def test_one_shot_result_resumes_with_new_permit(self):
  s=read('ui/screens/settings/ProviderManagementRoute.kt');a=s.index('LaunchedEffect(resumed, permit, oauthReturned)');b=s.index('LaunchedEffect(resumed, permit, busy',a);body=s[a:b]
  self.assertLess(body.index('withContext(Dispatchers.IO)'),body.index('oauthReturned = false'));self.assertIn('sameRegistry && current(permit) &&',body);self.assertIn('oauthAttempt == null && !oauthReturned',s)
 def test_settings_vault_ref_is_not_backup_field(self):
  s=read('storage/ModelSettings.kt');self.assertIn('previousConnection.oauth != settings.oauth',s);self.assertIn('listOf("oauth-reference", "request-options"',s)
  keys=s.split('private val configurationFields = ',1)[1].split('\n',1)[0];self.assertNotIn('oauth',keys)
  self.assertIn('require(apiKey.isEmpty())',s)
 def test_snapshot_both_directions(self):
  s=read('model/ProviderSnapshot.kt');self.assertIn('oauth = oauth,',s);self.assertIn('oauth = settings.oauth,',s);self.assertIn('oauth == other.oauth',s)
 def test_every_post_uses_same_auth_and_codex_policy(self):
  s=read('model/ResponsesModel.kt');self.assertEqual(2,s.count('val authentication = authHeaders()'));self.assertIn('CodexRequestPolicy.apply(request, settings.requestOptions.speedMode)',s)
  self.assertLess(s.index('val authentication = authHeaders()',s.index('val pendingPost = post')),s.index('val conn = connect(URL(finalUrl))'))
 def test_catalog_propagates_auth_and_exact_url(self):
  s=read('model/catalog/ModelCatalogService.kt');self.assertIn('oauth = settings.oauth',s)
  s=read('model/catalog/RemoteModelFetcher.kt');self.assertIn('$baseUrl/models?client_version=',s);self.assertIn('oauthHeaders?.forEach',s);self.assertIn('connection.instanceFollowRedirects = false',s)
 def test_activity_not_exported_and_no_javascript_bridge(self):
  root=ET.parse(R/'app/src/main/AndroidManifest.xml').getroot();ns='{http://schemas.android.com/apk/res/android}'
  item=next(x for x in root.find('application').findall('activity') if x.get(ns+'name')=='.model.oauth.CodexLoginActivity');self.assertEqual('false',item.get(ns+'exported'))
  s=read('model/oauth/CodexLoginActivity.kt');self.assertNotIn('addJavascriptInterface',s);self.assertIn('MIXED_CONTENT_NEVER_ALLOW',s);self.assertIn('withContext(Dispatchers.IO + NonCancellable) { auth =',s)
 def test_no_token_or_callback_material_in_preferences(self):
  s=read('model/oauth/CodexTokenStore.kt');self.assertIn('AES/GCM/NoPadding',s);self.assertIn('cipher.updateAAD(auth.id.toByteArray())',s)
  self.assertNotIn('putString("access_token"',s);self.assertNotIn('verifier',s);self.assertNotIn('state',s)
 def test_save_pause_lifetime_and_no_stub_oauth(self):
  s=read('ui/screens/settings/ProviderManagementRoute.kt');self.assertIn('newProvider.registration.beginSave()',s);self.assertIn('newProvider.registration.finishSave()',s);self.assertIn('newProvider.registration.registered(added.slotId)',s)
  onpause=s.split('if (event == Lifecycle.Event.ON_PAUSE)',1)[1].split('else if (event == Lifecycle.Event.ON_RESUME)',1)[0];self.assertNotIn('creating?.revoke()',onpause)
 def test_summary_accepts_oauth_and_keeps_api_key_wire_cap(self):
  s=read('model/SummaryProviderFactory.kt');self.assertNotIn('require(snapshot.oauth == null)',s)
  self.assertIn('if (snapshot.oauth == null) body.put(',s)
  self.assertIn('provider.apiKey.isBlank() && provider.oauth == null',read('context/SummaryProfileStore.kt'))
  self.assertIn('outputBudget?.validateTerminal(texts)',read('context/ProviderSummaryModel.kt'))
  self.assertIn('settings.requestOptions.codex || settings.oauth != null',read('context/ManualSummaryTargets.kt'))
  self.assertIn('summarySettings(frozen.settings)',read('context/CatalogSummaryExecutionPolicy.kt'))
  self.assertIn('editing.settings.oauth != null || editing.settings.apiKey.isNotBlank()',read('MainActivity.kt'))
if __name__ == '__main__':
 unittest.main()
