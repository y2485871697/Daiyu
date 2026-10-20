package app.daiyu.android

import androidx.compose.ui.test.*

class ProviderAuthenticationChoiceActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    fun testAddOpensAuthenticationThenExplicitApiKeyRegistration() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "模型提供商").performClick()
        node("page:认证方式").assertExists()
        node("provider-auth-api-key").assertHasClickAction()
        node("provider-auth-oauth").assertHasNoClickAction()
        node("prompt-input").assertDoesNotExist()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
        reveal("provider-auth-api-key", "认证方式").performClick()
        awaitUniqueTag("prompt-input")
        node("prompt-input").performTextReplacement("API Key fixture")
        ui.onNodeWithText("保存").performClick()
        awaitUniqueTag("page:API Key fixture")
        val after = fixture.controller.providers.snapshot()
        assertEquals(before.providers.size + 1, after.providers.size)
        assertEquals(before.activeSlotId, after.activeSlotId)
        assertEquals(0, fixture.opens.get())
    }

    fun testPausedAddCannotRegisterOrRestoreTheNamePrompt() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "模型提供商").performClick()
        reveal("provider-auth-api-key", "认证方式").performClick()
        awaitUniqueTag("prompt-input")
        node("prompt-input").performTextReplacement("must not register")
        realPauseResume(activity, atPause = { })
        node("prompt-input").assertDoesNotExist()
        node("page:认证方式").assertDoesNotExist()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }

    fun testAnthropicAddKeepsProviderDisabledAndDoesNotOpenOAuth() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add-anthropic", "模型提供商").performClick()
        node("page:认证方式").assertDoesNotExist()
        awaitUniqueTag("prompt-input")
        node("prompt-input").performTextReplacement("Anthropic fixture")
        ui.onNodeWithText("保存").performClick()
        awaitUniqueTag("page:Anthropic fixture")
        reveal("provider-protocol-select", "Anthropic fixture").assertTextContains("Anthropic Messages")
        reveal("settings-field:API Key", "Anthropic fixture").assertEditableText("")
        val after = fixture.controller.providers.snapshot()
        assertFalse(after.providers.single { it.name == "Anthropic fixture" }.enabled)
        assertEquals(before.activeSlotId, after.activeSlotId)
        assertEquals(0, fixture.opens.get())
    }

    fun testDirectoryHasAddRowsAndCardsInsteadOfExpandedManagementForms() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "模型提供商").assertTextContains("新增 OpenAI-compatible")
        reveal("provider-add-anthropic", "模型提供商").assertExists()
        reveal("provider-add-speech", "模型提供商").assertHasNoClickAction()
        reveal("provider-open-primary", "模型提供商").assertTextContains("主模型服务商")
        ui.onNodeWithText("配置与模型").assertDoesNotExist()
        ui.onNodeWithText("刷新").assertDoesNotExist()
        node("settings-switch:启用").assertDoesNotExist()
        reveal("provider-search", "模型提供商").performTextReplacement("no matching provider")
        node("provider-open-primary").assertDoesNotExist()
        node("provider-search").performTextReplacement("")
        reveal("provider-open-primary", "模型提供商").performTouchInput { longClick() }
        ui.onNodeWithText("配置与模型").assertExists()
        ui.onNodeWithText("重命名").assertExists()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }
}
