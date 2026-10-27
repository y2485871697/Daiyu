package app.daiyu.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.util.concurrent.atomic.AtomicInteger
import app.daiyu.android.model.oauth.CodexAuth
import app.daiyu.android.model.oauth.CodexLoginActivity
import app.daiyu.android.model.oauth.CodexOAuthProtocol

class ProviderOAuthCreationActivityTest : WiredActivityTestCase() {
    private fun openOAuthDraft() {
        openProviderManagement()
        reveal("provider-add", "模型提供商").performClick()
        reveal("provider-auth-oauth", "认证方式").performClick()
        awaitUniqueTag("page:新建提供商")
    }
    private fun awaitLoginButton(text: String, hits: AtomicInteger) {
        try {
        ui.waitUntil(10_000) {
            hits.get() == 1 && ui.onAllNodes(hasTestTag("provider-oauth-login") and isEnabled() and hasText(text))
                .fetchSemanticsNodes().size == 1
        }
        } catch (timeout: ComposeTimeoutException) {
            val buttons = ui.onAllNodesWithTag("provider-oauth-login").fetchSemanticsNodes()
                .joinToString(prefix = "[", postfix = "]") { button ->
                    val text = button.config.getOrNull(SemanticsProperties.Text)?.map { it.text }
                    "{text=$text, enabled=${!button.config.contains(SemanticsProperties.Disabled)}}"
                }
            throw AssertionError("OAuth result expected=$text, monitorHits=${hits.get()}, buttons=$buttons", timeout)
        }
    }
    private fun loginResult(code: Int, auth: CodexAuth? = null, hits: AtomicInteger): Instrumentation.ActivityMonitor {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != CodexLoginActivity::class.java.name) return null
                hits.incrementAndGet()
                val data = Intent().putExtra("attempt", intent.getStringExtra("attempt"))
                auth?.let { data.putExtra("oauth-reference", it.id) }
                return Instrumentation.ActivityResult(code, data)
            }
        }
        instrumentation.addMonitor(monitor)
        return monitor
    }
    fun testOAuthChoiceOpensUnsignedCreationPageBeforeAnyLoginOrRegistration() {
        val before = fixture.controller.providers.snapshot()
        openOAuthDraft()
        reveal("provider-name", "新建提供商").assertEditableText("OpenAI Codex")
        reveal("settings-field:Base URL", "新建提供商").assertEditableText(CodexOAuthProtocol.BASE_URL)
        node("settings-field:API Key").assertDoesNotExist()
        node("settings-radio:配置").assertDoesNotExist()
        reveal("provider-oauth-login", "新建提供商").assertIsDisplayed().assertIsEnabled()
        reveal("model-save", "新建提供商", "model_save").assertTextContains("创建").assertIsNotEnabled()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }
    fun testCancelledLoginReturnsToSameEditedDraftWithoutRedAuthenticationNotice() {
        val before = fixture.controller.providers.snapshot()
        openOAuthDraft()
        reveal("provider-name", "新建提供商").performTextReplacement("Edited Codex")
        val hits = AtomicInteger()
        val monitor = loginResult(Activity.RESULT_CANCELED, hits = hits)
        try {
            reveal("provider-oauth-login", "新建提供商").performClick()
            awaitUniqueTag("page:新建提供商")
            awaitLoginButton("使用 OpenAI 登录", hits)
        } finally { instrumentation.removeMonitor(monitor) }
        reveal("provider-name", "新建提供商").assertEditableText("Edited Codex")
        realPauseResume(activity)
        reveal("provider-name", "新建提供商").assertEditableText("Edited Codex")
        ui.onNodeWithText("OAuth 登录已取消或页面已变化，请重试。").assertDoesNotExist()
        reveal("model-save", "新建提供商", "model_save").assertIsNotEnabled()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitUniqueTag("page:认证方式")
        ui.onNodeWithText("OAuth 登录已取消或页面已变化，请重试。").assertDoesNotExist()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }
    fun testSuccessfulLoginKeepsEditsAndRegistersOnlyAfterCreate() {
        val before = fixture.controller.providers.snapshot()
        val auth = CodexAuth("00000000-0000-0000-0000-000000000007")
        openOAuthDraft()
        reveal("provider-name", "新建提供商").performTextReplacement("Created Codex")
        val hits = AtomicInteger()
        val monitor = loginResult(Activity.RESULT_OK, auth, hits = hits)
        try {
            reveal("provider-oauth-login", "新建提供商").performClick()
            awaitUniqueTag("page:新建提供商")
            awaitLoginButton("重新登录 OpenAI", hits)
        } finally { instrumentation.removeMonitor(monitor) }
        reveal("provider-name", "新建提供商").assertEditableText("Created Codex")
        reveal("provider-oauth-login", "新建提供商").assertTextContains("重新登录")
        assertEquals(before, fixture.controller.providers.snapshot())
        reveal("model-save", "新建提供商", "model_save").assertIsEnabled().performClick()
        awaitUniqueTag("page:Created Codex")
        val after = fixture.controller.providers.snapshot()
        val added = after.providers.single { it.name == "Created Codex" }
        assertEquals(before.providers.size + 1, after.providers.size)
        assertEquals(before.activeSlotId, after.activeSlotId)
        val saved = fixture.controller.providers.editing(fixture.controller.providers.editor(added.slotId)).settings
        assertEquals(auth, saved.oauth)
        assertEquals(CodexOAuthProtocol.BASE_URL, saved.endpoint)
        assertEquals("", saved.apiKey)
        assertEquals("", saved.model)
        assertEquals(0, fixture.opens.get())
    }
}
