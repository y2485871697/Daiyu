package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.EtaFormTextField
import app.daiyu.android.ui.components.subagent.ConversationAgentField
import app.daiyu.android.ui.screens.assistants.AssistantPreviewField
import app.daiyu.android.ui.screens.settings.SettingsTextField

class EmbeddedInputPlaceholderTest : WiredActivityTestCase() {
    private enum class Kind { REGULAR, COMPACT, EMBEDDED, MULTILINE, ETA, SUBAGENT, ASSISTANT }
    private var value by mutableStateOf("")
    private val label = "输入框标题"
    private fun tag(kind: Kind) = if (kind == Kind.SUBAGENT) "subagent-field:$label" else "placeholder-field"
    private fun field(kind: Kind) = ui.onNodeWithTag(tag(kind))

    private fun show(kind: Kind, dark: Boolean = false, direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f, secret: Boolean = false, enabled: Boolean = true) {
        mainThread {
            value = ""
            activity.setContent {
                val platform = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platform.density, fontScale),
                    LocalLayoutDirection provides direction) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        Column(Modifier.requiredWidth(280.dp).safeDrawingPadding()) {
                            when (kind) {
                                Kind.ETA -> EtaFormTextField(value, { value = it }, label, singleLine = true,
                                    enabled = enabled, modifier = Modifier.testTag(tag(kind)))
                                Kind.SUBAGENT -> ConversationAgentField(label, value, { value = it }, enabled = enabled)
                                Kind.ASSISTANT -> AssistantPreviewField(value, { value = it }, label, enabled = enabled,
                                    fieldModifier = Modifier.testTag(tag(kind)), minHeight = 48.dp)
                                else -> SettingsTextField(label, value, { value = it }, password = secret,
                                    compact = kind == Kind.COMPACT || kind == Kind.EMBEDDED,
                                    embedded = kind == Kind.EMBEDDED, multiline = kind == Kind.MULTILINE,
                                    enabled = enabled, tag = tag(kind))
                            }
                        }
                    }
                }
            }
        }
        awaitVisualIdle()
    }

    private fun assertPlaceholderInside(kind: Kind) {
        field(kind).assertContentDescriptionEquals(label)
        val frame = field(kind).fetchSemanticsNode().boundsInRoot
        val placeholder = ui.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("placeholder stays inside $kind", placeholder.left >= frame.left - 1f &&
            placeholder.right <= frame.right + 1f && placeholder.top >= frame.top - 1f && placeholder.bottom <= frame.bottom + 1f)
    }

    fun testSharedFieldsShowTheirTitleOnlyWhileEmptyIncludingFocusedAndWhitespaceValues() {
        for (kind in Kind.entries) {
            show(kind)
            assertPlaceholderInside(kind)
            field(kind).performClick()
            awaitVisualIdle()
            assertPlaceholderInside(kind)
            field(kind).performTextReplacement("value")
            awaitVisualIdle()
            field(kind).assertEditableText("value").assertContentDescriptionEquals(label)
            ui.onAllNodesWithText(label, useUnmergedTree = true).assertCountEquals(0)
            field(kind).performTextReplacement(" ")
            awaitVisualIdle()
            ui.onAllNodesWithText(label, useUnmergedTree = true).assertCountEquals(0)
            field(kind).performTextReplacement("")
            awaitVisualIdle()
            assertPlaceholderInside(kind)
            if (kind == Kind.MULTILINE) {
                field(kind).performTextReplacement("line one\nline two")
                field(kind).assertEditableText("line one\nline two")
                ui.onAllNodesWithText(label, useUnmergedTree = true).assertCountEquals(0)
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testDisabledEmptyPlaceholdersRemainInsideTheirFieldAtLargeFontInBothThemesAndDirections() {
        for (dark in listOf(false, true)) for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (kind in Kind.entries) {
                show(kind, dark, direction, fontScale = 1.3f, enabled = false)
                assertPlaceholderInside(kind)
                field(kind).assertIsNotEnabled().performTouchInput { click() }
                field(kind).assertIsNotFocused()
                assertEquals("", value)
                assertPlaceholderInside(kind)
            }
        }
    }

    fun testPasswordEyesStayInsideAllSettingsVariantsAndKeepSelectionOnToggle() {
        for (kind in listOf(Kind.REGULAR, Kind.COMPACT, Kind.EMBEDDED)) {
            show(kind, secret = true)
            field(kind).performTextReplacement("secret-canary")
            field(kind).performClick().performTextInputSelection(TextRange(3))
            val frame = ui.onNodeWithTag("${tag(kind)}-container").fetchSemanticsNode().boundsInRoot
            val eye = ui.onNodeWithTag("settings-secret-visibility:$label")
            val bounds = eye.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            assertTrue("eye is inside $kind", bounds.left >= frame.left && bounds.right <= frame.right + 1f &&
                bounds.top >= frame.top && bounds.bottom <= frame.bottom + 1f)
            assertEquals(frame.center.y, bounds.center.y, 1f)
            eye.performClick()
            awaitVisualIdle()
            field(kind).assertIsFocused().assertEditableText("secret-canary")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
            ui.onNodeWithTag("settings-secret-visibility:$label").performClick()
            awaitVisualIdle()
            field(kind).assertIsFocused().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
            assertEquals("secret-canary", value)
        }
    }

    fun testAssistantLabelActionSurvivesEnteredValuesAndAnEmptyLabel() {
        var title by mutableStateOf("动作字段")
        var clicks = 0
        mainThread {
            value = ""
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.requiredWidth(280.dp).safeDrawingPadding()) {
                        AssistantPreviewField(value, { value = it }, title,
                            fieldModifier = Modifier.testTag("action-field"), labelAction = {
                                TextButton({ clicks++ }, Modifier.testTag("field-action")) { Text("动作") }
                            })
                    }
                }
            }
        }
        ui.onNodeWithTag("field-action").assertIsDisplayed().performClick()
        ui.onNodeWithTag("action-field").performTextReplacement("entered")
        ui.onNodeWithText("动作字段", useUnmergedTree = true).assertDoesNotExist()
        ui.onNodeWithTag("field-action").assertIsDisplayed().performClick()
        mainThread { title = "" }
        ui.onNodeWithTag("field-action").assertIsDisplayed().performClick()
        assertEquals(3, clicks)
    }
}
