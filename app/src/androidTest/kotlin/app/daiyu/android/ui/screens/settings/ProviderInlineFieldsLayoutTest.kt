package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import java.io.File

class ProviderInlineFieldsLayoutTest : WiredActivityTestCase() {
    private var draft by mutableStateOf(ModelSettingsDraftUi())
    private var enabled by mutableStateOf(true)
    private fun show(headers: Boolean, dark: Boolean = false, fontScale: Float = 1f) {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                        Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState())) {
                            SettingsCard { ProviderInlineFields(draft, enabled, headers) { draft = it } }
                        }
                    }
                }
            }
        }
        awaitVisualIdle()
    }
    private fun control(tag: String) = ui.onNodeWithTag(tag)
    private fun capture(name: String) {
        val directory = File(checkNotNull(activity.getExternalFilesDir(null)), "settings-ui")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { output ->
            check(ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }
    private fun open(headers: Boolean) {
        control(if (headers) "model-custom-headers" else "model-body-fields").performClick()
        awaitVisualIdle()
    }
    fun testHeaderFieldsShareDeleteColumnAndEyeStaysInsideValueAt320Dp() {
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            mainThread { draft = ModelSettingsDraftUi(customHeaders = """{"X-Project":"header-layout-canary"}""") }
            show(headers = true, dark = dark, fontScale = fontScale)
            open(headers = true)
            val name = control("model-header-name:0-container").fetchSemanticsNode().boundsInRoot
            val value = control("model-header-value:0-container").fetchSemanticsNode().boundsInRoot
            val eye = control("settings-secret-visibility:值").fetchSemanticsNode().boundsInRoot
            val delete = control("model-header-delete:0").fetchSemanticsNode().boundsInRoot
            val add = control("model-header-add").fetchSemanticsNode().boundsInRoot
            val summary = control("model-custom-headers").fetchSemanticsNode().boundsInRoot
            val density = activity.resources.displayMetrics.density
            assertEquals(name.left, value.left, 1f); assertEquals(name.right, value.right, 1f)
            assertTrue("name remains single line", name.height / density < 65f)
            assertTrue("value remains single line", value.height / density < 65f)
            assertEquals(value.center.y, eye.center.y, 1f)
            assertTrue("eye is inside the value field", eye.left >= value.left && eye.right <= value.right + 1f)
            assertTrue("delete shares the right column", delete.left >= value.right - 1f)
            assertEquals((name.top + value.bottom) / 2, delete.center.y, 1f)
            assertTrue("delete keeps its touch target", delete.width / density >= 47f)
            assertEquals(summary.left, add.left, 1f); assertEquals(summary.right, add.right, 1f)
            assertTrue("add is a row, not a chip", add.height / density >= 48f && add.height / density < 70f)
            control("model-header-value:0").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            capture("provider-header-${if (dark) "dark" else "light"}-$fontScale")
            control("model-custom-headers").performClick()
        }
        assertEquals(0, fixture.opens.get())
    }
    fun testHeaderVisibilityReturnsToHiddenAfterPauseAndDeleteKeepsTheFooter() {
        mainThread { draft = ModelSettingsDraftUi(customHeaders = """{"X-Project":"header-layout-canary"}""") }
        show(headers = true); open(headers = true)
        control("settings-secret-visibility:值").performClick()
        awaitVisualIdle()
        control("model-header-value:0").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
            .assertEditableText("header-layout-canary")
        control("settings-secret-visibility:值").assertContentDescriptionEquals("隐藏值").performClick()
        awaitVisualIdle()
        control("model-header-value:0").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assertEditableText("\u2022".repeat("header-layout-canary".length))
        control("settings-secret-visibility:值").assertContentDescriptionEquals("显示值").performClick()
        awaitVisualIdle()
        control("model-header-value:0").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
            .assertEditableText("header-layout-canary")
        realPauseResume(activity, atPause = {})
        awaitVisualIdle()
        control("model-header-name:0").assertDoesNotExist()
        open(headers = true)
        control("model-header-value:0").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assertEditableText("\u2022".repeat("header-layout-canary".length))
        control("model-header-delete:0").performClick()
        awaitVisualIdle()
        control("model-header-name:0").assertDoesNotExist()
        control("model-header-add").assertIsDisplayed().performClick()
        awaitVisualIdle()
        control("model-header-name:0").assertIsDisplayed()
        assertFalse(draft.headerFieldsValid)
        assertEquals(0, fixture.opens.get())
    }
    fun testVisibilityToggleKeepsFocusedSelectionAndRenderedMask() {
        val value = "header-layout-canary"
        mainThread { draft = ModelSettingsDraftUi(customHeaders = """{"X-Project":"$value"}""") }
        show(headers = true); open(headers = true)
        val beforeToggle = draft
        control("model-header-value:0").performClick().performTextInputSelection(TextRange(7))
        for (visible in listOf(true, false, true)) {
            control("settings-secret-visibility:值").performClick()
            awaitVisualIdle()
            control("model-header-value:0").assertIsFocused()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(7)))
                .assertEditableText(if (visible) value else "\u2022".repeat(value.length))
                .assert(if (visible) SemanticsMatcher.keyNotDefined(SemanticsProperties.Password)
                    else SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            assertSame(beforeToggle, draft)
        }
        control("model-header-value:0").performTextInput("!")
        awaitVisualIdle()
        val edited = value.take(7) + "!" + value.drop(7)
        control("model-header-value:0").assertEditableText(edited)
        assertEquals(edited, draft.headerFieldRows!!.single().value)
        assertEquals(0, fixture.opens.get())
    }
    fun testBodyUsesTheSameFooterAndDeleteColumnWithoutLosingMultilineJson() {
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            mainThread { draft = ModelSettingsDraftUi(customBody = """{"temperature":0.5}""") }
            show(headers = false, dark = dark, fontScale = fontScale); open(headers = false)
            val name = control("model-body-field-name:0-container").fetchSemanticsNode().boundsInRoot
            fun valueHeight() = control("model-body-field-value:0-container").getUnclippedBoundsInRoot().height.value
            val value = control("model-body-field-value:0-container").fetchSemanticsNode().boundsInRoot
            val delete = control("model-body-field-delete:0").fetchSemanticsNode().boundsInRoot
            val density = activity.resources.displayMetrics.density
            val tolerance = 1f / density
            val nameHeight = control("model-body-field-name:0-container").getUnclippedBoundsInRoot().height.value
            val shortHeight = valueHeight()
            val deleteTouch = control("model-body-field-delete:0").fetchSemanticsNode().touchBoundsInRoot
            assertEquals(name.left, value.left, 1f); assertEquals(name.right, value.right, 1f)
            assertEquals("short JSON starts at one line", nameHeight, shortHeight, tolerance)
            assertTrue("delete stays beside fields", delete.left >= value.right - 1f)
            assertEquals((name.top + value.bottom) / 2, delete.center.y, 1f)
            assertTrue("delete retains a 48dp touch target", deleteTouch.width / density >= 47f && deleteTouch.height / density >= 47f)
            val editor = control("model-body-field-value:0")
            editor.performTextReplacement("")
            awaitVisualIdle()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.onNodeWithText("值（JSON）", useUnmergedTree = true).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { getLayout -> assertTrue(getLayout(layouts)) }
            val placeholder = layouts.single()
            assertEquals(1, placeholder.lineCount)
            assertEquals("empty JSON only reserves its one-line placeholder",
                maxOf(nameHeight, placeholder.size.height / density + 24f), valueHeight(), tolerance)
            editor.assertEditableText("")
            assertFalse(draft.bodyFieldsValid)
            editor.performTextReplacement("{\n  \"nested\": true\n}")
            awaitVisualIdle()
            assertTrue("JSON grows for multiple lines", valueHeight() > shortHeight * 1.5f)
            assertTrue(draft.bodyFieldsValid)
            val wrapped = "{\"text\":\"${"x".repeat(180)}\"}"
            editor.performTextReplacement(wrapped)
            awaitVisualIdle()
            assertTrue("long single-line JSON wraps naturally", valueHeight() > shortHeight * 1.5f)
            editor.assertEditableText(wrapped)
            fun jsonLines(count: Int) = (0 until count).joinToString(",\n", "{\n", "\n}") { "  \"field$it\": $it" }
            editor.performTextReplacement(jsonLines(12))
            awaitVisualIdle()
            val cappedHeight = valueHeight()
            assertTrue("long JSON grows before the eight-line cap", cappedHeight > shortHeight * 2)
            val longer = jsonLines(24)
            editor.performTextReplacement(longer)
            awaitVisualIdle()
            assertEquals("long JSON keeps a bounded editor", cappedHeight, valueHeight(), tolerance)
            editor.assertEditableText(longer)
            assertTrue(draft.bodyFieldsValid)
            editor.performTextReplacement("0.5")
            awaitVisualIdle()
            assertEquals("JSON shrinks again with short content", nameHeight, valueHeight(), tolerance)
            editor.performTextReplacement("invalid-json")
            assertFalse(draft.bodyFieldsValid)
            editor.assertEditableText("invalid-json")
            control("model-body-field-add").performScrollTo().assertIsDisplayed()
            capture("provider-body-invalid-${if (dark) "dark" else "light"}-$fontScale")
            control("model-body-field-delete:0").performScrollTo().performTouchInput { click() }
            control("model-body-field-add").performScrollTo().assertIsDisplayed()
            assertTrue(draft.bodyFieldsValid)
            control("model-body-field-name:0").assertDoesNotExist()
            control("model-body-fields").performScrollTo().performClick()
        }
        assertEquals(0, fixture.opens.get())
    }
    fun testDeletingARevealedHeaderDoesNotRevealTheNextRow() {
        mainThread { draft = ModelSettingsDraftUi(customHeaders = """{"X-First":"first-value","X-Second":"second-value"}""") }
        show(headers = true); open(headers = true)
        ui.onAllNodesWithTag("settings-secret-visibility:值")[0].performClick()
        awaitVisualIdle()
        control("model-header-value:0").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        control("model-header-delete:0").performClick()
        awaitVisualIdle()
        control("model-header-value:0").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        control("model-header-value:0").assertEditableText("\u2022".repeat("second-value".length))
        assertEquals("X-Second", draft.headerFieldRows!!.single().name)
        assertEquals("second-value", draft.headerFieldRows!!.single().value)
        assertEquals(0, fixture.opens.get())
    }
    fun testEmptyHeaderAndBodyUseFullWidthAddRowsAndRespectDisabledState() {
        for (headers in listOf(true, false)) {
            mainThread { draft = ModelSettingsDraftUi(); enabled = true }
            show(headers); open(headers)
            val prefix = if (headers) "model-header" else "model-body-field"
            val add = control("$prefix-add")
            add.assertIsDisplayed()
            capture("provider-${if (headers) "header" else "body"}-empty")
            mainThread { enabled = false }
            add.assertIsNotEnabled()
            mainThread { enabled = true }
            add.performTouchInput { click(androidx.compose.ui.geometry.Offset(width.toFloat() - 12f, center.y)) }
            control("$prefix-name:0").assertIsDisplayed()
            control("$prefix-delete:0").performScrollTo().performClick()
            control("$prefix-add").assertIsDisplayed()
            control(if (headers) "model-custom-headers" else "model-body-fields").performClick()
        }
        assertEquals(0, fixture.opens.get())
    }
}
