package app.daiyu.android

import androidx.compose.ui.test.*

class ProviderAuthenticationChoiceActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private fun fillNew(name: String) {
        reveal("provider-name", "添加提供商").performTextReplacement(name)
        reveal("settings-field:Base URL", "添加提供商").performTextReplacement("https://fixture.invalid/v1")
        reveal("settings-field:API Key", "添加提供商").performTextReplacement("synthetic-new-key")
    }
    fun testAddOpensInlineConfigurationAndOnlySaveRegisters() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "模型提供商").performClick()
        node("page:认证方式").assertExists()
        node("provider-auth-oauth").assertHasNoClickAction()
        reveal("provider-auth-api-key", "认证方式").performClick()
        awaitUniqueTag("page:添加提供商")
        node("prompt-input").assertDoesNotExist()
        fillNew("API Key fixture")
        assertEquals(before, fixture.controller.providers.snapshot())
        reveal("model-save", "添加提供商").performClick()
        awaitUniqueTag("page:API Key fixture")
        val after = fixture.controller.providers.snapshot()
        val added = after.providers.single { it.name == "API Key fixture" }
        assertEquals(before.providers.size + 1, after.providers.size)
        assertFalse(added.enabled)
        assertEquals(before.activeSlotId, after.activeSlotId)
        val editor = fixture.controller.providers.editor(added.slotId)
        assertEquals("", fixture.controller.providers.editing(editor).settings.model)
        assertTrue(fixture.controller.providers.catalogSnapshot(editor).models.isEmpty())
        assertEquals(0, fixture.opens.get())
    }
    fun testSavedProviderWithoutModelsCanBeDeletedFromItsConfigurationFooter() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "模型提供商").performClick()
        reveal("provider-auth-api-key", "认证方式").performClick()
        awaitUniqueTag("page:添加提供商")
        node("provider-delete").assertDoesNotExist()
        fillNew("empty-catalog fixture")
        reveal("model-save", "添加提供商").performClick()
        awaitUniqueTag("page:empty-catalog fixture")
        reveal("model-save", "empty-catalog fixture", "model_save").assertTextContains("保存配置")
        reveal("provider-delete", "empty-catalog fixture", "provider_delete").performClick()
        node("dialog-confirm").performClick()
        awaitControl("new provider deleted") {
            fixture.controller.providers.snapshot().providers == before.providers
        }
        awaitUniqueTag("page:模型提供商")
        assertEquals(before.activeSlotId, fixture.controller.providers.snapshot().activeSlotId)
        assertEquals(0, fixture.opens.get())
    }

    fun testPausedAddDoesNotRegisterOrRestoreCredentials() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "模型提供商").performClick()
        reveal("provider-auth-api-key", "认证方式").performClick()
        awaitUniqueTag("page:添加提供商")
        fillNew("must not register")
        realPauseResume(activity, atPause = {})
        node("page:添加提供商").assertDoesNotExist()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }
    fun testAnthropicAddUsesInlineNameAndKeepsProviderDisabled() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add-anthropic", "模型提供商").performClick()
        awaitUniqueTag("page:添加提供商")
        node("prompt-input").assertDoesNotExist()
        fillNew("Anthropic fixture")
        node("provider-endpoint-select").assertDoesNotExist()
        node("settings-radio:Anthropic Messages").assertDoesNotExist()
        reveal("model-save", "添加提供商").performClick()
        awaitUniqueTag("page:Anthropic fixture")
        val after = fixture.controller.providers.snapshot()
        val added = after.providers.single { it.name == "Anthropic fixture" }
        assertFalse(added.enabled)
        assertEquals(app.daiyu.android.model.ProviderProtocol.MESSAGES,
            fixture.controller.providers.editing(fixture.controller.providers.editor(added.slotId)).settings.protocol)
        reveal("settings-field:Anthropic 版本", "Anthropic fixture").assertExists()
        node("provider-endpoint-select").assertDoesNotExist()
        assertEquals(before.activeSlotId, after.activeSlotId)
        assertEquals(0, fixture.opens.get())
    }
    fun testDirectoryHasAddRowsAndCardsInsteadOfExpandedManagementForms() {
        openProviderManagement()
        reveal("provider-add", "模型提供商").assertExists()
        reveal("provider-add-anthropic", "模型提供商").assertExists()
        reveal("provider-add-speech", "模型提供商").assertHasNoClickAction()
        reveal("provider-open-primary", "模型提供商").assertExists()
        node("provider-enabled-primary").assertDoesNotExist()
        node("provider-refresh").assertDoesNotExist()
        reveal("provider-search", "模型提供商").performTextReplacement("not-a-provider")
        node("provider-open-primary").assertDoesNotExist()
        node("provider-search").performTextReplacement("")
        reveal("provider-open-primary", "模型提供商").performTouchInput { longClick() }
        ui.onNodeWithText("配置与模型").assertExists()
        ui.onNodeWithText("重命名").assertDoesNotExist()
        ui.onNodeWithText("删除提供商").assertExists()
        assertEquals(0, fixture.opens.get())
    }
}
