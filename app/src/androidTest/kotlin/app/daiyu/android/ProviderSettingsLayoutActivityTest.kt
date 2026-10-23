package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import app.daiyu.android.ui.screens.settings.ProviderActionButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ProviderSettingsLayoutActivityTest : WiredActivityTestCase() {
    fun testConnectionFieldsStayCompactAndSecretActionSharesTheFieldCenter() {
        openModels()
        val name = reveal("provider-name-container").fetchSemanticsNode().boundsInRoot
        val endpoint = reveal("settings-field:Base URL-container").fetchSemanticsNode().boundsInRoot
        val key = reveal("settings-field:API Key-container").fetchSemanticsNode().boundsInRoot
        val eye = node("settings-secret-visibility:API Key").fetchSemanticsNode().boundsInRoot
        val density = activity.resources.displayMetrics.density
        assertTrue("name must not reserve multiline space", name.height / density < 80f)
        assertTrue("URL must not reserve multiline space", endpoint.height / density < 80f)
        assertTrue("secret must not add a button row", key.height / density < 90f)
        assertEquals(key.center.y, eye.center.y, 2f)
        modelField("API Key").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        assertEquals(0, fixture.opens.get())
    }

    fun testInlineNameAndConnectionFieldsHaveNoLegacyModelOrPathControls() {
        val before = fixture.settings.load()
        openModels()
        reveal("provider-name").assertEditableText("主模型服务商").performTextReplacement("本地名称草稿")
        modelField("Base URL").assertEditableText(before.endpoint)
        node("provider-protocol-select").assertDoesNotExist()
        node("responses-path-select").assertDoesNotExist()
        node("settings-field:模型名").assertDoesNotExist()
        assertEquals("主模型服务商", fixture.controller.providers.snapshot().providers.single().name)
        assertEquals(before, fixture.settings.load())
        selectProviderTab("模型")
        reveal("model-catalog-fetch").assertExists()
        reveal("local-model-add").assertExists()
        reveal("local-model-search").assertExists()
        selectProviderTab("配置")
        reveal("provider-name").assertEditableText("本地名称草稿")
        assertEquals(0, fixture.opens.get())
    }
    fun testProviderFooterKeepsSaveConfigurationAndAlignedDeleteButtons() {
        val before = fixture.controller.providers.snapshot()
        openModels()
        reveal("provider-delete", lazyKey = "provider_delete").scrollToVisible().assertIsEnabled()
        val save = node("model-save").assertIsDisplayed().assertTextContains("保存配置")
            .fetchSemanticsNode().boundsInRoot
        val delete = node("provider-delete").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = activity.resources.displayMetrics.density
        assertEquals(save.left, delete.left, 1f)
        assertEquals(save.right, delete.right, 1f)
        val saveTouch = node("model-save").fetchSemanticsNode().touchBoundsInRoot
        val deleteTouch = node("provider-delete").fetchSemanticsNode().touchBoundsInRoot
        assertTrue("save keeps a full-width touch target: $saveTouch", saveTouch.width / density > 200f && saveTouch.height / density >= 48f)
        assertTrue("delete keeps a full-width touch target: $deleteTouch", deleteTouch.width / density > 200f && deleteTouch.height / density >= 48f)
        assertTrue("delete is below save", delete.top >= save.bottom)
        assertTrue("touch targets do not overlap", deleteTouch.top >= saveTouch.bottom)
        node("provider-delete").performClick()
        awaitUniqueTag("dialog-confirm")
        awaitNodeWindowFocus(node("dialog-confirm"))
        awaitVisualIdle()
        node("dialog-confirm").assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithText("不会自动切换", substring = true).assertExists()
        node("dialog-cancel").performClick()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }
    fun testCompactProviderActionsKeepFortyDpFillAndFortyEightDpTouchTargets() {
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            val clicks = IntArray(3)
            val enabled = mutableStateOf(true)
            var density = 1f
            mainThread { activity.setContent {
                val platform = LocalDensity.current
                density = platform.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        key(dark, fontScale) {
                            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                                Column(Modifier.width(250.dp).background(Color.Magenta).testTag("compact-provider-host")) {
                                    listOf("测试余额查询", "保存配置", "删除提供商").forEachIndexed { index, title ->
                                        ProviderActionButton(title, { clicks[index]++ },
                                            Modifier.testTag("compact-provider-action:$index"), enabled = enabled.value,
                                            colors = if (index == 2) ButtonDefaults.textButtonColorsPrimary(
                                                color = MiuixTheme.colorScheme.surfaceVariant,
                                                textColor = assistantPreviewPalette().danger)
                                            else ButtonDefaults.textButtonColorsPrimary())
                                    }
                                }
                            }
                        }
                    }
                }
            } }
            ui.waitForIdle()
            val host = node("compact-provider-host")
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            repeat(3) { index ->
                val button = node("compact-provider-action:$index").assertIsEnabled()
                    .assertHeightIsEqualTo(40.dp).assertTouchHeightIsEqualTo(48.dp)
                val touch = button.fetchSemanticsNode().touchBoundsInRoot
                val left = (touch.left - hostBounds.left).toInt()
                val top = (touch.top - hostBounds.top).toInt()
                val bitmap = captureScreenNode(host)
                val image = bitmap.toPixelMap()
                val width = touch.width.toInt()
                val height = touch.height.toInt()
                check(left >= 0 && top >= 0 && left + width <= image.width && top + height <= image.height)
                val pixels = bitmap.toPixelMap(startX = left, startY = top, width = width, height = height)
                // Sample the vertical content padding, not the centered label.
                val fill = pixels[pixels.width / 2, (8f * density).toInt()]
                fun sameColor(color: Color) = kotlin.math.abs(color.red - fill.red) < .02f &&
                    kotlin.math.abs(color.green - fill.green) < .02f && kotlin.math.abs(color.blue - fill.blue) < .02f
                val paintedRows = (0 until pixels.height).filter { y ->
                    (0 until pixels.width).any { x -> sameColor(pixels[x, y]) }
                }
                assertEquals("compact fill dark=$dark font=$fontScale index=$index", 40f * density, paintedRows.size.toFloat(), 2f)
                assertEquals("continuous fill", paintedRows.last() - paintedRows.first() + 1, paintedRows.size)
                assertEquals("top touch margin", 4f * density, paintedRows.first().toFloat(), 2f)
                assertEquals("bottom fill edge", 44f * density, (paintedRows.last() + 1).toFloat(), 2f)
                val outside = pixels[pixels.width / 2, (2f * density).toInt()]
                assertFalse("edge tap must be outside the fill", sameColor(outside))
                assertEquals(1f, outside.red, .01f)
                assertEquals(0f, outside.green, .01f)
                assertEquals(1f, outside.blue, .01f)
                val fillBounds = button.fetchSemanticsNode().boundsInRoot
                val topPoint = Offset(touch.center.x, touch.top + 2f * density)
                val bottomPoint = Offset(touch.center.x, touch.bottom - 2f * density)
                assertTrue("top edge is outside fill", topPoint.y < fillBounds.top)
                assertTrue("bottom edge is outside fill", bottomPoint.y > fillBounds.bottom)
                host.performTouchInput { click(topPoint - hostBounds.topLeft) }
                assertEquals(1, clicks[index])
                host.performTouchInput { click(bottomPoint - hostBounds.topLeft) }
                assertEquals(2, clicks[index])
            }
            mainThread { enabled.value = false }
            ui.waitForIdle()
            repeat(3) { index ->
                val button = node("compact-provider-action:$index").assertIsNotEnabled()
                button.performTouchInput { click() }
                val touch = button.fetchSemanticsNode().touchBoundsInRoot
                host.performTouchInput { click(Offset(touch.center.x, touch.top + 2f * density) - hostBounds.topLeft) }
                host.performTouchInput { click(Offset(touch.center.x, touch.bottom - 2f * density) - hostBounds.topLeft) }
                assertEquals(2, clicks[index])
            }
            assertTrue("disabled edge touches must not invoke any action", clicks.all { it == 2 })
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testConfirmedFooterDeletionClearsCurrentProviderAndReturnsToChat() {
        openModels()
        reveal("provider-delete", lazyKey = "provider_delete").performClick()
        awaitUniqueTag("dialog-confirm")
        awaitNodeWindowFocus(node("dialog-confirm"))
        awaitVisualIdle()
        node("dialog-confirm").assertIsDisplayed().assertIsEnabled()
        node("dialog-confirm").performClick()
        awaitControl("provider deleted") { fixture.controller.providers.snapshot().providers.isEmpty() }
        awaitUniqueTag("chat-top-bar")
        assertNull(fixture.controller.providers.snapshot().activeSlotId)
        assertEquals(0, fixture.opens.get())
    }

    fun testNameIsSavedInsideConfigurationWithoutChangingModelOrCredentials() {
        val before = fixture.settings.load()
        openModels()
        reveal("provider-name").performTextReplacement("已改名供应商")
        saveModel()
        awaitControl("name saved") { fixture.controller.providers.snapshot().providers.single().name == "已改名供应商" }
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testEndpointMenuHasExactChoicesAndDoesNotSaveOnSelection() {
        val before = fixture.settings.load()
        openModels()
        reveal("provider-endpoint-select").performClick()
        node("settings-radio:OpenAI Responses").assertIsSelected()
        node("settings-radio:Google Gemini").assertDoesNotExist()
        node("settings-radio:Anthropic Messages").assertDoesNotExist()
        node("settings-radio:OpenAI Chat Completions").performClick()
        reveal("provider-endpoint-select").assertTextContains("Chat Completions API")
        modelField("API Key").assertEditableText("")
        node("settings-switch:重输密钥并绑定").assertDoesNotExist()
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testMenuClosesOnPauseAndDoesNotChangeSavedSettings() {
        val before = fixture.settings.load()
        openModels()
        reveal("provider-endpoint-select").performClick()
        node("provider-endpoint-select-menu").assertExists()
        realPauseResume(activity, atPause = {})
        awaitModelEditor()
        node("provider-endpoint-select-menu").assertDoesNotExist()
        assertEquals(before, fixture.settings.load())
    }
}
