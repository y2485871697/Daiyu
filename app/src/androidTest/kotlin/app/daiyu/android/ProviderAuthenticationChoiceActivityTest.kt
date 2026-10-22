package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.ProviderDirectoryPage

class ProviderAuthenticationChoiceActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    fun testAddProviderRowsShareIconSlotsAndTextStartsAcrossThemesAndFontScales() {
        val saved = fixture.controller.providers.snapshot()
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            mainThread { activity.setContent {
                val platform = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platform.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        key(dark, fontScale) {
                            ProviderDirectoryPage(null, emptyMap(), "", {}, true, null, {}, {}, {}, {}, {})
                        }
                    }
                }
            } }
            ui.waitForIdle()
            val tags = listOf("provider-add", "provider-add-anthropic", "provider-add-speech")
            val titles = listOf("新增 OpenAI-compatible", "新增 Anthropic", "新增豆包语音")
            val summaries = listOf("支持 ChatGPT、DeepSeek、Kimi、GLM、Qwen 等", "支持 Anthropic Claude 官方或兼容 API",
                "仅用于朗读，填写语音控制台 API Key，不是方舟聊天 Key。")
            var iconLeft: Float? = null
            var iconWidth: Float? = null
            var iconHeight: Float? = null
            var textLeft: Float? = null
            tags.forEachIndexed { index, tag ->
                reveal(tag, "模型提供商").scrollToVisible()
                val icon = ui.onNodeWithTag("$tag-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val title = ui.onNodeWithText(titles[index], useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val summary = ui.onNodeWithText(summaries[index], useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                if (index == 0) {
                    iconLeft = icon.left; iconWidth = icon.width; iconHeight = icon.height; textLeft = title.left
                }
                assertEquals("icon slot dark=$dark font=$fontScale", checkNotNull(iconLeft), icon.left, 1f)
                assertEquals("icon width dark=$dark font=$fontScale", checkNotNull(iconWidth), icon.width, 1f)
                assertEquals("icon height dark=$dark font=$fontScale", checkNotNull(iconHeight), icon.height, 1f)
                assertEquals("title start dark=$dark font=$fontScale", checkNotNull(textLeft), title.left, 1f)
                assertEquals(title.left, summary.left, 1f)
                assertEquals(icon.width, icon.height, 1f)
                val density = activity.resources.displayMetrics.density
                assertEquals("24dp icon width", 24f * density, icon.width, 1f)
                assertEquals("24dp icon height", 24f * density, icon.height, 1f)
                val row = node(tag).fetchSemanticsNode().boundsInRoot
                assertEquals(row.center.y, icon.center.y, 1f)
            }
            node("provider-add").assertHasClickAction()
            node("provider-add-anthropic").assertHasClickAction()
            node("provider-add-speech").assertHasNoClickAction()
        }
        assertEquals(saved, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }

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
        awaitUniqueTag("dialog-confirm")
        awaitNodeWindowFocus(node("dialog-confirm"))
        awaitVisualIdle()
        node("dialog-confirm").assertIsDisplayed().assertIsEnabled()
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
