package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
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
    private var pendingReply: ((Reply) -> Unit)? = null
    private val answerPermission = androidx.compose.runtime.mutableStateOf(true)

    private fun show(dark: Boolean, fontScale: Float, answer: QuestionAnswer? = null, reply: Reply = Reply.SAVED, twoLineLabel: Boolean = false, deferReply: Boolean = false) {
        val question = UserQuestion("下一步操作", prompt, listOf(
            QuestionOption("keep", label, description), QuestionOption("change", if (twoLineLabel) "调整配置\n保留已有数据" else "调整配置")),
            recommendedOptionId = "keep")
        val record = QuestionRecord(owner.storageKey, question, answer = answer)
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    key(dark, fontScale, answer, reply, twoLineLabel, deferReply) {
                        AgentAppTheme(AppearanceSettings(
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = false) {
                            Column(Modifier.width(300.dp).verticalScroll(rememberScrollState()).testTag("question-scroll")) {
                                QuestionCard(owner, record, answer == null && answerPermission.value, submit = { identity, value, done ->
                                    assertEquals(owner, identity)
                                    submissions++
                                    submitted = value
                                    if (deferReply) pendingReply = done else done(reply)
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

    private fun assertExpanded() {
        node("question-toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
    }

    private fun assertCollapsed() {
        node("question-toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
        node("question-option:0").assertDoesNotExist()
        node("question-submit").assertDoesNotExist()
    }

    private fun openArchived() {
        assertCollapsed()
        scrollTo("question-toggle").performClick()
        ui.waitForIdle()
        assertExpanded()
    }

    private fun selectOption(index: Int) {
        val before = submissions
        scrollTo("question-option:$index").performClick()
        ui.waitForIdle()
        assertExpanded()
        node("question-submit").assertExists()
        assertEquals(before, submissions)
        scrollTo("question-option:$index").assertIsSelected()
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
        openArchived()
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
            assertArchived(0)
            layout(label, 15)
            layout(description, 14)
            layout(note, 15)
            scrollTo("question-option:1").performTouchInput { click() }
            node("question-option:0").assertIsSelected()
            node("question-toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
            scrollTo("question-note").performTouchInput { click() }
            ui.onAllNodes(isFocused()).assertCountEquals(0)
            layout(note, 15)
        }
        assertEquals(0, submissions)
        assertEquals(0, fixture.opens.get())
    }

    fun testOtherHidesNoteRetainsDraftAndArchivesOnlyVisibleAnswer() {
        show(dark = false, fontScale = 1.3f)
        selectOption(0)
        val notes = scrollTo("question-note")
        val noteBounds = notes.fetchSemanticsNode().boundsInRoot
        notes.performTextReplacement(note)
        selectOption(2)
        node("question-note").assertDoesNotExist()
        val other = scrollTo("question-other")
        val otherBounds = other.fetchSemanticsNode().boundsInRoot
        val placeholder = ui.onNodeWithText("填写你的选择", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(otherBounds.center.y - placeholder.center.y) <= 2f)
        assertTrue(abs(otherBounds.left - noteBounds.left) <= 1f)
        assertTrue(abs(otherBounds.width - noteBounds.width) <= 1f)
        other.performTextReplacement(custom)
        layout(custom, 15)
        selectOption(0)
        scrollTo("question-note").assertTextContains(note)
        selectOption(2)
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

    fun testEveryPendingChoiceStaysExpandedWithoutSubmittingAndKeepsDraft() {
        show(dark = false, fontScale = 1f)
        scrollTo("question-note").performTextReplacement(note)
        for (index in 0..3) {
            selectOption(index)
            if (index == 2) {
                scrollTo("question-other").performTextReplacement(custom)
                node("question-note").assertDoesNotExist()
            } else scrollTo("question-note").assertTextContains(note)
        }
        selectOption(2)
        scrollTo("question-other").assertTextContains(custom)
        scrollTo("question-submit").assertIsEnabled()
        assertEquals(0, submissions)
        assertEquals(0, fixture.opens.get())
    }

    fun testSelectedAnswerKeepsNoteAfterLiveSubmissionAndRemount() {
        show(dark = false, fontScale = 1f)
        selectOption(1)
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

    fun testBlankArchivedInputsAreHiddenWhilePendingInputsRemainAvailable() {
        show(dark = false, fontScale = 1f)
        scrollTo("question-note").assertExists()
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        selectOption(2)
        scrollTo("question-other").assertExists()
        node("question-note").assertDoesNotExist()
        for (blank in listOf<String?>(null, "", " \n\t")) {
            for (answer in listOf(QuestionAnswer.Selected("keep", blank), QuestionAnswer.Delegate(blank))) {
                show(dark = true, fontScale = 1.3f, answer = answer)
                assertArchived(if (answer is QuestionAnswer.Selected) 0 else 3)
                node("question-note").assertDoesNotExist()
                node("question-other").assertDoesNotExist()
                ui.onNodeWithText("补充说明（可选）").assertDoesNotExist()
            }
            show(dark = true, fontScale = 1.3f, answer = QuestionAnswer.Other(blank.orEmpty()))
            assertArchived(2)
            node("question-other").assertDoesNotExist()
            node("question-note").assertDoesNotExist()
            ui.onNodeWithText("填写你的选择").assertDoesNotExist()
        }
        assertEquals(0, submissions)
        assertEquals(0, fixture.opens.get())
    }

    fun testBlankNoteDisappearsAfterLiveSubmissionAndStaysHiddenOnRemount() {
        for (index in listOf(0, 3)) for (blank in listOf("", " \n\t")) {
            show(dark = false, fontScale = 1f)
            selectOption(index)
            scrollTo("question-note").performTextReplacement(blank)
            scrollTo("question-submit").performClick()
            ui.waitForIdle()
            val expectedNote = blank.takeIf { it.isNotEmpty() }
            val expected = if (index == 0) QuestionAnswer.Selected("keep", expectedNote)
                else QuestionAnswer.Delegate(expectedNote)
            assertEquals(expected, submitted)
            assertArchived(index)
            node("question-note").assertDoesNotExist()
            show(dark = true, fontScale = 1.3f, answer = submitted)
            assertArchived(index)
            node("question-note").assertDoesNotExist()
        }
        assertEquals(4, submissions)
        assertEquals(0, fixture.opens.get())
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
            selectOption(1)
            assertCentered(1)
            selectOption(2)
            assertCentered(2)
            show(dark = true, fontScale = fontScale, answer = QuestionAnswer.Selected("change", note), twoLineLabel = true)
            assertArchived(1)
            assertEquals(2, layout("调整配置\n保留已有数据", 15).lineCount)
            for (index in 0..3) assertCentered(index)
        }
    }

    fun testConfirmedSubmissionStaysAnsweredAndKeepsDisclosureAcrossReadOnlyRefresh() {
        val question = UserQuestion("下一步操作", prompt, listOf(
            QuestionOption("keep", label, description), QuestionOption("change", "调整配置")),
            allowOther = false, allowDelegation = false, allowNote = false)
        val pending = QuestionRecord(owner.storageKey, question)
        val store = object : app.daiyu.android.domain.question.QuestionStore {
            private var row = pending
            @Synchronized override fun get(callId: String) = row.takeIf { it.callId == callId }
            @Synchronized override fun records() = listOf(row)
            @Synchronized override fun create(record: QuestionRecord) = false
            @Synchronized override fun answer(callId: String, answer: QuestionAnswer): Boolean {
                if (row.callId != callId || row.answer != null || row.interrupted) return false
                row = row.copy(answer = answer)
                return true
            }
            @Synchronized override fun interruptPending() { if (row.answer == null) row = row.copy(interrupted = true) }
            override fun interruptPending(ownerPrefix: String) = interruptPending()
        }
        val registry = app.daiyu.android.question.QuestionRegistry(store, owner)
        val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
        val binding = app.daiyu.android.question.QuestionCardBinding(registry, owner, writer) { true }
        var projection by androidx.compose.runtime.mutableStateOf(
            app.daiyu.android.ChatController.QuestionProjection(owner, pending, binding, true))
        try {
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        Column(Modifier.width(300.dp).verticalScroll(rememberScrollState()).testTag("question-scroll")) {
                            val current = projection
                            QuestionCard(current.identity, current.record, current.canAnswer, current.binding)
                        }
                    }
                }
            }
            ui.waitForIdle()
            scrollTo("question-option:0").performClick()
            scrollTo("question-submit").performClick()
            ui.waitUntil(timeoutMillis = 8_000) { binding.savedAnswer(owner) == QuestionAnswer.Selected("keep") }
            ui.waitForIdle()
            assertCollapsed()
            node("question-toggle").assertTextContains("已回答 · 下一步操作")
            mainThread {
                assertNull(projection.record.answer)
                val directHandoff = app.daiyu.android.ChatController.QuestionProjection(owner, pending, null, false)
                    .confirmed(projection)
                assertEquals(QuestionAnswer.Selected("keep"), directHandoff.record.answer)
                val other = owner.copy(callId = "other")
                val unrelated = app.daiyu.android.ChatController.QuestionProjection(
                    other, pending.copy(callId = other.storageKey), null, false)
                assertNull(unrelated.confirmed(projection).record.answer)
                val changed = app.daiyu.android.ChatController.QuestionProjection(owner,
                    pending.copy(question = question.copy(title = "新问题")), null, false)
                assertNull(changed.confirmed(projection).record.answer)
                projection = projection.readOnly()
                assertNull(projection.binding)
                assertFalse(projection.canAnswer)
                assertEquals(QuestionAnswer.Selected("keep"), projection.record.answer)
            }
            ui.waitForIdle()
            assertCollapsed()
            node("question-toggle").assertTextContains("已回答 · 下一步操作")
            mainThread {
                val previous = projection
                projection = app.daiyu.android.ChatController.QuestionProjection(owner, pending, null, false).confirmed(previous)
                assertEquals(previous.record.answer, projection.record.answer)
                assertNull(projection.copy(identity = owner.copy(callId = "other"),
                    record = pending.copy(callId = owner.copy(callId = "other").storageKey)).confirmed(previous).record.answer)
                assertNull(projection.copy(record = pending.copy(question = question.copy(title = "新问题"))).confirmed(previous).record.answer)
            }
            ui.waitForIdle()
            assertCollapsed()
            scrollTo("question-toggle").performClick()
            ui.waitForIdle()
            assertExpanded()
            node("question-toggle").assertTextContains("已回答 · 下一步操作")
            node("question-option:0").assertIsSelected().assertIsNotEnabled()
            node("question-submit").assertDoesNotExist()
            ui.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            mainThread { projection = projection.copy(record = checkNotNull(store.get(owner.storageKey))) }
            ui.mainClock.autoAdvance = false
            try {
                repeat(24) {
                    ui.mainClock.advanceTimeByFrame()
                    ui.waitForIdle()
                    assertExpanded()
                    node("question-toggle").assertTextContains("已回答 · 下一步操作")
                    ui.onNodeWithText("当前不可作答 · 下一步操作").assertDoesNotExist()
                }
            } finally { ui.mainClock.autoAdvance = true }
            assertEquals(QuestionAnswer.Selected("keep"), store.get(owner.storageKey)?.answer)
            assertEquals(0, fixture.opens.get())
        } finally {
            writer.shutdown()
            assertTrue(writer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            registry.close()
        }
    }

    fun testSubmissionWaitsForConfirmedSaveBeforeCollapsingAndCanBeReopened() {
        show(dark = false, fontScale = 1f, deferReply = true)
        selectOption(0)
        scrollTo("question-note").performTextReplacement(note)
        scrollTo("question-submit").performClick()
        ui.waitForIdle()
        assertExpanded()
        node("question-submit").assertIsNotEnabled()
        node("question-option:0").assertIsSelected().assertIsNotEnabled()
        assertEquals(1, submissions)
        mainThread { checkNotNull(pendingReply).invoke(Reply.SAVED) }
        ui.waitForIdle()
        assertArchived(0)
        mainThread { answerPermission.value = false }
        ui.waitForIdle()
        assertExpanded()
        mainThread { answerPermission.value = true }
        ui.waitForIdle()
        assertExpanded()
        layout(note, 15)
        scrollTo("question-option:1").performTouchInput { click() }
        assertExpanded()
        node("question-option:0").assertIsSelected()
        scrollTo("question-toggle").performClick()
        ui.waitForIdle()
        assertCollapsed()
        openArchived()
        assertEquals(1, submissions)
    }

    fun testRejectedAndUnconfirmedSubmissionsStayExpanded() {
        for (reply in listOf(Reply.REJECTED, Reply.UNCONFIRMED)) {
            show(dark = false, fontScale = 1f, reply = reply)
            selectOption(1)
            scrollTo("question-note").performTextReplacement(note)
            scrollTo("question-submit").performClick()
            ui.waitForIdle()
            assertExpanded()
            node("question-option:1").assertIsSelected()
            scrollTo("question-note").assertTextContains(note)
            node("question-submit").assertExists()
            if (reply == Reply.REJECTED) node("question-submit").assertIsEnabled()
            else node("question-submit").assertIsNotEnabled()
        }
        assertEquals(2, submissions)
    }

    fun testSavedRefreshFailureStillArchivesWithoutEnablingResubmission() {
        show(dark = false, fontScale = 1f, reply = Reply.SAVED_REFRESH_FAILED)
        selectOption(0)
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
