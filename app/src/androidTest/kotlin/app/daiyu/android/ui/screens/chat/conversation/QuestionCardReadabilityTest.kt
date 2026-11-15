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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
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

    private fun show(dark: Boolean, fontScale: Float, answer: QuestionAnswer? = null, reply: Reply = Reply.SAVED, twoLineLabel: Boolean = false) {
        val question = UserQuestion("下一步操作", prompt, listOf(
            QuestionOption("keep", label, description), QuestionOption("change", if (twoLineLabel) "调整配置\n保留已有数据" else "调整配置")),
            recommendedOptionId = "keep")
        val record = QuestionRecord(owner.storageKey, question, answer = answer)
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    key(dark, fontScale, answer, reply, twoLineLabel) {
                        AgentAppTheme(AppearanceSettings(
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = false) {
                            Column(Modifier.width(300.dp).verticalScroll(rememberScrollState()).testTag("question-scroll")) {
                                QuestionCard(owner, record, answer == null, submit = { identity, value, done ->
                                    assertEquals(owner, identity)
                                    submissions++
                                    submitted = value
                                    done(reply)
                                })
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        node("question-scroll").scrollToDescendant(hasTestTag(tag))
        return node(tag).assertIsDisplayed()
    }

    private fun layout(text: String, size: Int): TextLayoutResult {
        node("question-scroll").scrollToDescendant(hasText(text))
        val target = ui.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val raw = layouts.single()
        assertEquals(text, raw.layoutInput.text.text)
        return measuredTextLayout(raw).also {
            assertEquals(raw.size, it.size)
            assertEquals(size.sp, it.layoutInput.style.fontSize)
            assertFalse("Text must wrap without clipping: $text constraints=${raw.layoutInput.constraints} raw=${raw.size} " +
                "paragraph=${raw.multiParagraph.width}x${raw.multiParagraph.height} " +
                "measured=${it.size} paragraph=${it.multiParagraph.width}x${it.multiParagraph.height} " +
                "width=${it.didOverflowWidth} height=${it.didOverflowHeight}", it.hasVisualOverflow)
            assertEquals(0, it.getLineStart(0))
            assertEquals(text.length, it.getLineEnd(it.lineCount - 1, visibleEnd = true))
        }
    }

    private fun measuredTextLayout(result: TextLayoutResult, size: IntSize = result.size): TextLayoutResult {
        val input = result.layoutInput
        return TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
            text = input.text, style = input.style, overflow = input.overflow, softWrap = input.softWrap,
            maxLines = input.maxLines, placeholders = input.placeholders,
            constraints = Constraints.fixed(size.width, size.height))
    }

    fun testMeasuredTextStillRejectsWidthAndHeightClipping() {
        show(dark = false, fontScale = 1.3f)
        val result = layout(prompt, 16)
        assertTrue("One-pixel width must overflow",
            measuredTextLayout(result, IntSize(1, result.size.height)).hasVisualOverflow)
        assertTrue("One-pixel height must overflow",
            measuredTextLayout(result, IntSize(result.size.width, 1)).hasVisualOverflow)
    }

    private fun assertArchived(selected: Int) {
        node("question-submit").assertDoesNotExist()
        for (index in 0..3) {
            node("question-option:$index").assertIsNotEnabled()
            if (index == selected) node("question-option:$index").assertIsSelected()
            else node("question-option:$index").assertIsNotSelected()
        }
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    private fun assertCentered(index: Int) {
        val tag = "question-option:$index"
        val row = scrollTo(tag).fetchSemanticsNode().boundsInRoot
        fun bounds(child: String) = ui.onNode(
            hasTestTag(child) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val indicator = bounds("question-option-indicator")
        val text = bounds("question-option-text")
        assertTrue("Indicator centered in $tag", abs(row.center.y - indicator.center.y) <= 1f)
        assertTrue("Text centered in $tag", abs(row.center.y - text.center.y) <= 1f)
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
            show(dark, fontScale, answer = QuestionAnswer.Selected("keep", note))
            layout(label, 15)
            layout(description, 14)
            layout(note, 15)
            assertArchived(0)
            scrollTo("question-option:1").performTouchInput { click() }
            node("question-option:0").assertIsSelected()
            scrollTo("question-note").performTouchInput { click() }
            ui.onAllNodes(isFocused()).assertCountEquals(0)
            layout(note, 15)
        }
        assertEquals(0, submissions)
        assertEquals(0, fixture.opens.get())
    }

    fun testOtherHidesNoteRetainsDraftAndArchivesOnlyVisibleAnswer() {
        show(dark = false, fontScale = 1.3f)
        scrollTo("question-option:0").performClick()
        val notes = scrollTo("question-note")
        val noteBounds = notes.fetchSemanticsNode().boundsInRoot
        notes.performTextReplacement(note)
        scrollTo("question-option:2").performClick()
        node("question-note").assertDoesNotExist()
        val other = scrollTo("question-other")
        val otherBounds = other.fetchSemanticsNode().boundsInRoot
        val placeholder = ui.onNodeWithText("填写你的选择", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(otherBounds.center.y - placeholder.center.y) <= 2f)
        assertTrue(abs(otherBounds.left - noteBounds.left) <= 1f)
        assertTrue(abs(otherBounds.width - noteBounds.width) <= 1f)
        other.performTextReplacement(custom)
        layout(custom, 15)
        scrollTo("question-option:0").performClick()
        scrollTo("question-note").assertTextContains(note)
        scrollTo("question-option:2").performClick()
        node("question-note").assertDoesNotExist()
        scrollTo("question-other").assertTextContains(custom)
        scrollTo("question-submit").assertIsEnabled().performClick()
        ui.waitForIdle()
        assertEquals(1, submissions)
        assertEquals(QuestionAnswer.Other(custom), submitted)
        assertArchived(2)
        layout(custom, 15)
        scrollTo("question-other").performTouchInput { click() }
        ui.onAllNodes(isFocused()).assertCountEquals(0)
        show(dark = true, fontScale = 1.3f, answer = submitted)
        assertArchived(2)
        node("question-note").assertDoesNotExist()
        layout(custom, 15)
        assertEquals(1, submissions)
        assertEquals(0, fixture.opens.get())
    }

    fun testSelectedAnswerKeepsNoteAfterLiveSubmissionAndRemount() {
        show(dark = false, fontScale = 1f)
        scrollTo("question-option:1").performClick()
        scrollTo("question-note").performTextReplacement(note)
        scrollTo("question-submit").performClick()
        ui.waitForIdle()
        assertEquals(QuestionAnswer.Selected("change", note), submitted)
        assertArchived(1)
        layout(note, 15)
        show(dark = true, fontScale = 1.3f, answer = submitted)
        assertArchived(1)
        layout(note, 15)
        assertEquals(1, submissions)
    }

    fun testDelegateAndLongArchivedInputsRemainFullyReadable() {
        val longText = (1..10).joinToString("\n") { "第${it}行：保留完整输入内容" }
        for (answer in listOf(QuestionAnswer.Delegate(longText), QuestionAnswer.Other(longText))) {
            show(dark = false, fontScale = 1.3f, answer = answer)
            assertArchived(if (answer is QuestionAnswer.Other) 2 else 3)
            assertTrue(layout(longText, 15).lineCount >= 10)
            if (answer is QuestionAnswer.Other) node("question-note").assertDoesNotExist()
            else node("question-note").assertExists()
        }
        assertEquals(0, submissions)
    }

    fun testOptionsCenterIndicatorAndTextInsideBackgroundForWrappedAndSingleLabels() {
        for (fontScale in listOf(1f, 1.3f)) {
            show(dark = false, fontScale = fontScale, twoLineLabel = true)
            assertEquals(1, layout("其他…", 15).lineCount)
            assertEquals(2, layout("调整配置\n保留已有数据", 15).lineCount)
            for (index in 0..3) assertCentered(index)
            scrollTo("question-option:1").performClick()
            assertCentered(1)
            scrollTo("question-option:2").performClick()
            assertCentered(2)
            show(dark = true, fontScale = fontScale, answer = QuestionAnswer.Selected("change", note), twoLineLabel = true)
            assertEquals(2, layout("调整配置\n保留已有数据", 15).lineCount)
            for (index in 0..3) assertCentered(index)
        }
    }

    fun testSavedRefreshFailureStillArchivesWithoutEnablingResubmission() {
        show(dark = false, fontScale = 1f, reply = Reply.SAVED_REFRESH_FAILED)
        scrollTo("question-option:0").performClick()
        scrollTo("question-note").performTextReplacement(note)
        scrollTo("question-submit").performClick()
        ui.waitForIdle()
        assertArchived(0)
        layout(note, 15)
        node("question-scroll").scrollToDescendant(hasText("答案已保存，消息刷新失败；请稍后查看状态"))
        ui.onNodeWithText("答案已保存，消息刷新失败；请稍后查看状态").assertIsDisplayed()
        assertEquals(1, submissions)
    }
}
