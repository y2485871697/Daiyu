package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.question.QuestionAnswer
import app.daiyu.android.domain.question.QuestionIdentity
import app.daiyu.android.domain.question.QuestionOption
import app.daiyu.android.domain.question.QuestionRecord
import app.daiyu.android.domain.question.UserQuestion
import app.daiyu.android.question.QuestionCardView.Reply
import app.daiyu.android.ui.app.AgentAppTheme
import kotlin.math.abs

class QuestionCardReadabilityTest : WiredActivityTestCase() {
    private val owner = QuestionIdentity("readability", "run", "owner", "call")
    private val prompt = "请选择下一步操作，选项说明和自定义回答都应该清楚显示。"
    private val label = "保持现状，不要再调整"
    private val description = "保留当前设置，也不把临时信息写进长期存储。"
    private val custom = "这是我的自定义回答\n第二行继续说明"
    private val note = "请保留我填写的补充说明"
    private var submissions = 0
    private var submitted: QuestionAnswer? = null

    private fun show(dark: Boolean, fontScale: Float, answered: Boolean = false) {
        val question = UserQuestion("下一步操作", prompt, listOf(
            QuestionOption("keep", label, description), QuestionOption("change", "调整配置")),
            recommendedOptionId = "keep")
        val record = QuestionRecord(owner.storageKey, question,
            answer = if (answered) QuestionAnswer.Selected("keep", note) else null)
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    key(dark, fontScale, answered) {
                        AgentAppTheme(AppearanceSettings(
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = false) {
                            Column(Modifier.width(300.dp).verticalScroll(rememberScrollState()).testTag("question-scroll")) {
                                QuestionCard(owner, record, !answered, submit = { identity, answer, done ->
                                    assertEquals(owner, identity)
                                    submissions++
                                    submitted = answer
                                    done(Reply.SAVED)
                                })
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun layout(text: String, size: Int): TextLayoutResult {
        node("question-scroll").scrollToDescendant(hasText(text))
        val target = ui.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single().also {
            assertEquals(size.sp, it.layoutInput.style.fontSize)
            assertFalse("Text must wrap without clipping: $text", it.hasVisualOverflow)
        }
    }

    fun testQuestionOptionsAndSavedAnswerRemainReadableInBothThemesAndLargeFont() {
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            show(dark, fontScale)
            assertEquals(fontScale, layout(prompt, 16).layoutInput.density.fontScale, .001f)
            layout(label, 15)
            layout(description, 14)
            layout("推荐", 11)
            node("question-option:0").assertIsNotSelected()
            node("question-option:1").assertIsNotSelected()
            show(dark, fontScale, answered = true)
            node("question-toggle").performClick()
            layout("已选择：$label", 15)
            layout(description, 14)
            layout(note, 15)
        }
        assertEquals(0, submissions)
        assertEquals(0, fixture.opens.get())
    }

    fun testCustomAndNoteFieldsAlignAndKeepMultilineDraftThroughFocusChanges() {
        show(dark = false, fontScale = 1.3f)
        node("question-scroll").scrollToDescendant(hasTestTag("question-option:2"))
        node("question-option:2").performClick()
        node("question-scroll").scrollToDescendant(hasTestTag("question-other"))
        val other = node("question-other").assertIsDisplayed()
        val otherBounds = other.fetchSemanticsNode().boundsInRoot
        val placeholder = ui.onNodeWithText("填写你的选择", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(otherBounds.center.y - placeholder.center.y) <= 2f)
        other.performTextReplacement(custom)
        layout(custom, 15)
        node("question-scroll").scrollToDescendant(hasTestTag("question-note"))
        val notes = node("question-note").assertIsDisplayed()
        val noteBounds = notes.fetchSemanticsNode().boundsInRoot
        assertTrue(abs(otherBounds.left - noteBounds.left) <= 1f)
        assertTrue(abs(otherBounds.width - noteBounds.width) <= 1f)
        notes.performTextReplacement(note)
        layout(note, 15)
        node("question-scroll").scrollToDescendant(hasTestTag("question-other"))
        node("question-other").assertTextContains(custom)
        node("question-scroll").scrollToDescendant(hasTestTag("question-submit"))
        node("question-submit").assertIsEnabled().performClick()
        assertEquals(1, submissions)
        assertEquals(QuestionAnswer.Other(custom, note), submitted)
        assertEquals(0, fixture.opens.get())
    }
}
