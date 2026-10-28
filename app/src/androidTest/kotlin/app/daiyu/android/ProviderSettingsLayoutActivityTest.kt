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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextLayoutResult
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.components.deletionButtonColors
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.ProviderActionButton
import app.daiyu.android.ui.screens.settings.ProviderSettingsTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ProviderSettingsLayoutActivityTest : WiredActivityTestCase() {
    // String semantics rebuilds at the parent width; measure in the actual label box.
    private fun measuredLabelLayout(result: TextLayoutResult): TextLayoutResult {
        val input = result.layoutInput
        return TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
            text = input.text,
            style = input.style,
            overflow = input.overflow,
            softWrap = input.softWrap,
            maxLines = input.maxLines,
            placeholders = input.placeholders,
            constraints = Constraints.fixed(result.size.width, result.size.height),
        )
    }

    fun testConnectionFieldsStayCompactAndSecretActionSharesTheFieldCenter() {
        openModels()
        val name = reveal("provider-name-container").fetchSemanticsNode().boundsInRoot
        val endpoint = reveal("settings-field:Base URL-container").fetchSemanticsNode().boundsInRoot
        val key = reveal("settings-field:API Key-container").fetchSemanticsNode().boundsInRoot
        val eye = node("settings-secret-visibility:API Key").fetchSemanticsNode().boundsInRoot
        reveal("model-connection-test").assertHeightIsAtLeast(48.dp)
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
    fun testProviderActionsHaveFullHeightAccentFillAndScalableLabels() {
        val titles = listOf("测试连接", "测试余额查询", "保存配置", "删除提供商")
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            val clicks = IntArray(titles.size)
            val enabled = mutableStateOf(true)
            var density = 1f
            var primary = Color.Unspecified
            var onPrimary = Color.Unspecified
            var deleteFill = Color.Unspecified
            var deleteText = Color.Unspecified
            mainThread { activity.setContent {
                val platform = LocalDensity.current
                density = platform.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        ProviderSettingsTheme {
                            primary = MiuixTheme.colorScheme.primary
                            onPrimary = MiuixTheme.colorScheme.onPrimary
                            deleteFill = Color(0xFF92454F)
                            deleteText = Color.White
                            key(dark, fontScale) {
                                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                                    Column(Modifier.width(250.dp).background(Color.Magenta).testTag("provider-action-host")) {
                                        titles.forEachIndexed { index, title ->
                                            val buttonModifier = Modifier.testTag("provider-action:$index")
                                            if (index == titles.lastIndex) {
                                                ProviderActionButton(title, { clicks[index]++ }, buttonModifier,
                                                    enabled = enabled.value, colors = deletionButtonColors())
                                            } else {
                                                ProviderActionButton(title, { clicks[index]++ }, buttonModifier, enabled = enabled.value)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } }
            ui.waitForIdle()
            val host = node("provider-action-host")
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            var previousBottom = hostBounds.top
            titles.forEachIndexed { index, title ->
                val button = node("provider-action:$index").assertIsEnabled()
                    .assertHeightIsAtLeast(48.dp)
                val bounds = button.fetchSemanticsNode().boundsInRoot
                val touch = button.fetchSemanticsNode().touchBoundsInRoot
                assertTrue("touch target is at least 48dp", touch.height >= kotlin.math.round(48f * density))
                assertEquals("touch and fill top", bounds.top, touch.top, 1f)
                assertEquals("touch and fill bottom", bounds.bottom, touch.bottom, 1f)
                assertTrue("touch targets do not overlap", touch.top >= previousBottom)
                previousBottom = touch.bottom
                val layouts = mutableListOf<TextLayoutResult>()
                val label = ui.onNodeWithText(title, useUnmergedTree = true)
                label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val semanticsLayout = layouts.single()
                val layout = measuredLabelLayout(semanticsLayout)
                assertEquals(semanticsLayout.size, layout.size)
                assertTrue("a one-pixel label must still report overflow", measuredLabelLayout(
                    semanticsLayout.copy(size = IntSize(1, semanticsLayout.size.height))).hasVisualOverflow)
                assertEquals(16.sp, layout.layoutInput.style.fontSize)
                assertEquals(22.sp, layout.layoutInput.style.lineHeight)
                assertEquals(if (index == titles.lastIndex) deleteText else onPrimary, layout.layoutInput.style.color)
                assertFalse(
                    "label must not clip dark=$dark fontScale=$fontScale index=$index title=$title " +
                        "density=$density layoutDensity=${layout.layoutInput.density} " +
                        "button=$bounds touch=$touch host=$hostBounds " +
                        "label=${label.fetchSemanticsNode().boundsInRoot} " +
                        "semanticsConstraints=${semanticsLayout.layoutInput.constraints} " +
                        "semanticsSize=${semanticsLayout.size} " +
                        "semanticsParagraph=${semanticsLayout.multiParagraph.width}x${semanticsLayout.multiParagraph.height} " +
                        "constraints=${layout.layoutInput.constraints} size=${layout.size} " +
                        "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                        "overflowWidth=${layout.didOverflowWidth} overflowHeight=${layout.didOverflowHeight} " +
                        "lines=${layout.lineCount} maxLines=${layout.layoutInput.maxLines}",
                    layout.hasVisualOverflow,
                )
                assertEquals(bounds.center.y, label.fetchSemanticsNode().boundsInRoot.center.y, 1f)
                val fontDiagnostic = "dark=$dark requestedFontScale=$fontScale index=$index title=$title " +
                    "platformDensity=$density layoutDensity=${layout.layoutInput.density} " +
                    "semanticsDensity=${semanticsLayout.layoutInput.density} button=$bounds touch=$touch host=$hostBounds " +
                    "heightDp=${bounds.height / density} heightDeltaPx=${bounds.height - 48f * density} " +
                    "label=${label.fetchSemanticsNode().boundsInRoot} style=${layout.layoutInput.style} " +
                    "size=${layout.size} constraints=${layout.layoutInput.constraints} " +
                    "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                    "firstBaseline=${layout.firstBaseline} lastBaseline=${layout.lastBaseline} " +
                    "lineCount=${layout.lineCount} line0=${layout.getLineTop(0)}..${layout.getLineBottom(0)} " +
                    "semanticsSize=${semanticsLayout.size} " +
                    "semanticsParagraph=${semanticsLayout.multiParagraph.width}x${semanticsLayout.multiParagraph.height}"
                android.util.Log.i("DaiyuProviderLayout", fontDiagnostic)
                if (fontScale > 1f) assertTrue("large font grows the fill: $fontDiagnostic", bounds.height > 48f * density)
                val left = (touch.left - hostBounds.left).toInt()
                val top = (touch.top - hostBounds.top).toInt()
                val bitmap = captureScreenNode(host)
                val image = bitmap.toPixelMap()
                val width = touch.width.toInt()
                val height = touch.height.toInt()
                check(left >= 0 && top >= 0 && left + width <= image.width && top + height <= image.height)
                val pixels = bitmap.toPixelMap(startX = left, startY = top, width = width, height = height)
                val expected = if (index == titles.lastIndex) deleteFill else primary
                fun sameColor(color: Color) = kotlin.math.abs(color.red - expected.red) < .02f &&
                    kotlin.math.abs(color.green - expected.green) < .02f && kotlin.math.abs(color.blue - expected.blue) < .02f
                val paintedRows = (0 until pixels.height).filter { y ->
                    (0 until pixels.width).any { x -> sameColor(pixels[x, y]) }
                }
                assertEquals("full-height fill dark=$dark font=$fontScale index=$index", bounds.height, paintedRows.size.toFloat(), 2f)
                assertEquals("continuous fill", paintedRows.last() - paintedRows.first() + 1, paintedRows.size)
                assertEquals("no transparent top inset", 0f, paintedRows.first().toFloat(), 2f)
                assertEquals("no transparent bottom inset", bounds.height, (paintedRows.last() + 1).toFloat(), 2f)
                assertTrue("top edge follows the expected theme color", sameColor(pixels[pixels.width / 2, (2f * density).toInt()]))
                assertTrue("bottom edge follows the expected theme color", sameColor(pixels[pixels.width / 2, pixels.height - 1 - (2f * density).toInt()]))
                val topPoint = Offset(touch.center.x, touch.top + 2f * density)
                val bottomPoint = Offset(touch.center.x, touch.bottom - 2f * density)
                assertTrue("top tap is inside fill", topPoint.y > bounds.top)
                assertTrue("bottom tap is inside fill", bottomPoint.y < bounds.bottom)
                button.performTouchInput { click() }
                assertEquals(1, clicks[index])
                host.performTouchInput { click(topPoint - hostBounds.topLeft) }
                assertEquals(2, clicks[index])
                host.performTouchInput { click(bottomPoint - hostBounds.topLeft) }
                assertEquals(3, clicks[index])
            }
            mainThread { enabled.value = false }
            ui.waitForIdle()
            titles.indices.forEach { index ->
                val button = node("provider-action:$index").assertIsNotEnabled()
                button.performTouchInput { click() }
                val touch = button.fetchSemanticsNode().touchBoundsInRoot
                host.performTouchInput { click(Offset(touch.center.x, touch.top + 2f * density) - hostBounds.topLeft) }
                host.performTouchInput { click(Offset(touch.center.x, touch.bottom - 2f * density) - hostBounds.topLeft) }
                assertEquals(3, clicks[index])
            }
            assertTrue("disabled edge touches must not invoke any action", clicks.all { it == 3 })
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testConfirmedFooterDeletionClearsCurrentProviderAndReturnsToDirectory() {
        openModels()
        reveal("provider-delete", lazyKey = "provider_delete").performClick()
        awaitUniqueTag("dialog-confirm")
        awaitNodeWindowFocus(node("dialog-confirm"))
        awaitVisualIdle()
        node("dialog-confirm").assertIsDisplayed().assertIsEnabled()
        node("dialog-confirm").performClick()
        awaitControl("provider deleted") { fixture.controller.providers.snapshot().providers.isEmpty() }
        awaitUniqueTag("provider-management")
        assertNull(fixture.controller.providers.snapshot().activeSlotId)
        backToChat(activity)
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
        assertModelSecret(before.apiKey)
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
