"""Source guards; JVM draft ownership and Android fake-result tests own behavior."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/app/daiyu/android"
UI = MAIN / "ui/screens/settings"

class OAuthCreationPageContractTest(unittest.TestCase):
    def test_oauth_choice_creates_a_draft_and_does_not_launch_login(self):
        route = (UI / "ProviderManagementRoute.kt").read_text()
        choice = route.split("}, onOAuth = {", 1)[1].split("})", 1)[0]
        self.assertIn("NewProviderDraft(ProviderProtocol.RESPONSES, oauthOnly = true)", choice)
        self.assertNotIn("oauthLauncher.launch", choice)
        self.assertNotIn("OAuth 登录已取消或页面已变化", route)
        self.assertIn("ProviderAuthenticationPage(enabled && oauthAttempt == null, null", route)
    def test_inline_login_and_create_require_real_auth_without_a_placeholder(self):
        screen = (UI / "ModelSettingsScreen.kt").read_text()
        for fragment in ('testTag("provider-oauth-login")', '"使用 OpenAI 登录"',
                         'text = if (onOAuthLogin != null) "创建" else "保存配置"',
                         'enabled && (!oauthOnly || draft.oauth != null)',
                         'else if (!oauthOnly) SettingsTextField("API Key"'):
            self.assertIn(fragment, screen)
        route = (UI / "ProviderManagementRoute.kt").read_text()
        self.assertIn('responsesPath = "/responses")', route)
        self.assertIn("require(!newProvider.oauthOnly || submitted.oauth != null)", route)
        self.assertIn('if (!newProvider.oauthOnly) item("provider-tabs")', route)
    def test_callback_attaches_to_same_live_draft_and_transfers_current_credential(self):
        route = (UI / "ProviderManagementRoute.kt").read_text()
        for fragment in ("result.resultCode == android.app.Activity.RESULT_OK", "creating === target", "oauthAttempt != attempt || oauthDraft !== target", "target.attachOAuth(auth)",
                         "ProviderRegistrationOwnership { draft.oauth?.let(discardOAuth) }", "previous != auth",
                         "newProvider.registration.registered(added.slotId)"):
            self.assertIn(fragment, route)
        callback = route.split("LaunchedEffect(resumed, permit, oauthReturned)", 1)[1].split("LaunchedEffect(resumed, permit, busy", 1)[0]
        self.assertNotIn("creating = NewProviderDraft", callback)
    def test_browser_matches_legacy_session_ua_callbacks_and_history_without_weakening_pkce(self):
        activity = (MAIN / "model/oauth/CodexLoginActivity.kt").read_text()
        for fragment in ("setAcceptCookie(true)", "setAcceptThirdPartyCookies(browser, true)", "databaseEnabled = true",
                         "CodexOAuthProtocol.browserUserAgent", "override fun onPageStarted", "override fun onReceivedError",
                         "browser.canGoBack()", "browser.goBack()", "MIXED_CONTENT_NEVER_ALLOW", "CodexOAuthProtocol.code(url, state)"):
            self.assertIn(fragment, activity)
        self.assertNotIn("MIXED_CONTENT_ALWAYS_ALLOW", activity)
        self.assertNotIn("addJavascriptInterface", activity)
    def test_failure_results_are_terminal_and_server_responses_never_become_intent_or_log_text(self):
        activity = (MAIN / "model/oauth/CodexLoginActivity.kt").read_text()
        self.assertIn("catch (failure: CodexOAuthFailure) { fail(failure); return }", activity)
        self.assertIn('putExtra("oauth-failure", failure.reason.name)', activity)
        failure = activity.split("fun fail(failure: CodexOAuthFailure)", 1)[1].split("fun callback", 1)[0]
        self.assertIn("consumed = true; server?.close(); browser.stopLoading()", failure)
        self.assertIn("setResult(Activity.RESULT_CANCELED, data)", failure)
        self.assertIn("finish()", failure)
        self.assertNotIn("error_description", failure)
        self.assertNotIn("failure.toString()", failure)
        runtime = (MAIN / "model/oauth/CodexOAuthRuntime.kt").read_text()
        self.assertEqual(2, runtime.count("CodexOAuthHttp.post("))
        http = (MAIN / "model/oauth/CodexOAuthHttp.kt").read_text()
        self.assertIn("catch (failure: CodexOAuthFailure) { throw failure }", http)
        self.assertIn("readBounded(input, 8192, truncate = true)", http)
        self.assertIn("conn.instanceFollowRedirects = false", http)
    def test_android_result_fixtures_do_not_open_real_login_or_hide_server_errors(self):
        source = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ProviderOAuthCreationActivityTest.kt").read_text()
        self.assertIn("Instrumentation.ActivityMonitor()", source)
        self.assertIn("testSuccessfulLoginKeepsEditsAndRegistersOnlyAfterCreate", source)
        self.assertIn("testCancelledLoginReturnsToSameEditedDraftWithoutRedAuthenticationNotice", source)
        self.assertIn("assertIsNotEnabled()", source)

if __name__ == "__main__": unittest.main()
