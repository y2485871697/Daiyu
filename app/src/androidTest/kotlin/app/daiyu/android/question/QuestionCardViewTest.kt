package app.daiyu.android.question

import android.test.InstrumentationTestCase
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import app.daiyu.android.MainActivity
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import app.daiyu.android.domain.question.*

class QuestionCardViewTest : InstrumentationTestCase() {
    private val owner = QuestionIdentity("conversation", "run", "owner", "call")
    private fun record() = QuestionRecord(owner.storageKey, UserQuestion("Title", "Choose",
        listOf(QuestionOption("one", "First"), QuestionOption("two", "Second")), recommendedOptionId = "one"))
    private fun onMain(work: () -> Unit) {
        var problem: Throwable? = null
        instrumentation.runOnMainSync { try { work() } catch (failure: Throwable) { problem = failure } }
        problem?.let { throw it }
    }
    fun testRecommendationNeverSelectsOrSubmits() = onMain {
        val card = QuestionCardView(instrumentation.targetContext)
        var submitted = false
        card.bind(owner, record(), true) { _, _, _ -> submitted = true }
        val group = (0 until card.childCount).map(card::getChildAt).filterIsInstance<RadioGroup>().single()
        assertEquals(-1, group.checkedRadioButtonId)
        (0 until card.childCount).map(card::getChildAt).filterIsInstance<Button>().single().performClick()
        assertFalse(submitted)
    }
    fun testSelectedOptionUsesStableIdAndExactOwner() = onMain {
        val card = QuestionCardView(instrumentation.targetContext)
        var selected: QuestionAnswer? = null
        var submissions = 0
        card.bind(owner, record(), true) { identity, answer, _ -> assertEquals(owner, identity); submissions++; selected = answer }
        val group = (0 until card.childCount).map(card::getChildAt).filterIsInstance<RadioGroup>().single()
        group.check(group.getChildAt(1).id)
        val send = (0 until card.childCount).map(card::getChildAt).filterIsInstance<Button>().single()
        send.performClick(); send.performClick()
        assertEquals(QuestionAnswer.Selected("two"), selected)
        assertEquals(1, submissions)
        assertFalse(send.isEnabled)
    }
    fun testPendingRefreshPreservesSelectionAndNote() = onMain {
        val card = QuestionCardView(instrumentation.targetContext)
        val submit: (QuestionIdentity, QuestionAnswer, (QuestionCardView.Reply) -> Unit) -> Unit = { _, _, _ -> }
        card.bind(owner, record(), true, submit)
        val group = (0 until card.childCount).map(card::getChildAt).filterIsInstance<RadioGroup>().single()
        group.check(group.getChildAt(0).id)
        val note = (0 until card.childCount).map(card::getChildAt).filterIsInstance<EditText>().last()
        note.setText("keep this")
        card.bind(owner, record(), true, submit)
        assertSame(group, (0 until card.childCount).map(card::getChildAt).filterIsInstance<RadioGroup>().single())
        assertEquals("keep this", note.text.toString())
    }
    fun testDifferentOwnerCannotBindStoredRecord() = onMain {
        val card = QuestionCardView(instrumentation.targetContext)
        try { card.bind(owner.copy(ownerId = "other"), record(), true) { _, _, _ -> }; fail("wrong owner accepted") }
        catch (_: IllegalArgumentException) { }
    }
    fun testAnsweredAndInterruptedRecordsAreReadOnly() = onMain {
        val card = QuestionCardView(instrumentation.targetContext)
        val submit: (QuestionIdentity, QuestionAnswer, (QuestionCardView.Reply) -> Unit) -> Unit = { _, _, _ -> fail("submitted") }
        for (value in listOf(record().copy(answer = QuestionAnswer.Selected("one")), record().copy(interrupted = true))) {
            card.bind(owner, value, true, submit)
            assertTrue((0 until card.childCount).map(card::getChildAt).none { it is Button })
            assertTrue((0 until card.childCount).map(card::getChildAt).filterIsInstance<TextView>().last().text.isNotEmpty())
        }
    }
    private fun selectedCard(reply: QuestionCardView.Reply): Pair<QuestionCardView, () -> Unit> {
        lateinit var card: QuestionCardView
        lateinit var complete: () -> Unit
        onMain {
            card = QuestionCardView(instrumentation.targetContext)
            card.bind(owner, record(), true) { _, _, done -> complete = { done(reply) } }
            val group = (0 until card.childCount).map(card::getChildAt).filterIsInstance<RadioGroup>().single()
            group.check(group.getChildAt(0).id)
            (0 until card.childCount).map(card::getChildAt).filterIsInstance<Button>().single().performClick()
        }
        return card to { onMain { complete() }; instrumentation.waitForIdleSync() }
    }
    fun testSavedReplyCannotBeReopenedByLatePendingRefresh() {
        val (card, complete) = selectedCard(QuestionCardView.Reply.SAVED)
        complete()
        onMain {
            card.bind(owner, record(), true) { _, _, _ -> fail("late pending resubmitted") }
            assertTrue((0 until card.childCount).map(card::getChildAt).none { it is Button })
        }
    }
    fun testUnknownReplyStaysDisabledOnSamePendingRefresh() {
        val (card, complete) = selectedCard(QuestionCardView.Reply.UNCONFIRMED)
        complete()
        onMain {
            card.bind(owner, record(), true) { _, _, _ -> fail("unknown resubmitted") }
            assertFalse((0 until card.childCount).map(card::getChildAt).filterIsInstance<Button>().single().isEnabled)
        }
    }
    fun testLateReplyCannotOverwriteNewOwner() {
        val (card, complete) = selectedCard(QuestionCardView.Reply.SAVED)
        val other = owner.copy(callId = "other")
        onMain { card.bind(other, record().copy(callId = other.storageKey), true) { _, _, _ -> } }
        complete()
        onMain { assertEquals(1, (0 until card.childCount).map(card::getChildAt).filterIsInstance<Button>().size) }
    }
    fun testSavedRefreshFailureDisablesFurtherSubmission() {
        val (card, complete) = selectedCard(QuestionCardView.Reply.SAVED_REFRESH_FAILED)
        complete()
        onMain {
            assertTrue((0 until card.childCount).map(card::getChildAt).none { it is Button })
            assertTrue((0 until card.childCount).map(card::getChildAt).filterIsInstance<TextView>().any { it.text.contains("答案已保存") })
        }
    }

    fun testIdleCardCanSubmitAfterRealDetachReattachAndSameRecordBind() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var calls = 0
        try {
            onMain {
                val host = FrameLayout(activity)
                activity.findViewById<ViewGroup>(android.R.id.content).addView(host)
                val card = QuestionCardView(activity)
                val submit: (QuestionIdentity, QuestionAnswer, (QuestionCardView.Reply) -> Unit) -> Unit = { _, _, _ -> calls++ }
                host.addView(card)
                card.bind(owner, record(), true, submit)
                host.removeView(card)
                host.addView(card)
                card.bind(owner, record(), true, submit)
                val group = (0 until card.childCount).map(card::getChildAt).filterIsInstance<RadioGroup>().single()
                group.check(group.getChildAt(0).id)
                val send = (0 until card.childCount).map(card::getChildAt).filterIsInstance<Button>().single()
                assertTrue(send.isEnabled)
                send.performClick(); send.performClick()
                assertEquals(1, calls)
            }
        } finally { onMain { activity.finish() } }
    }
    fun testDetachedInFlightCardIgnoresOldCompletionAfterReattach() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var card: QuestionCardView
        lateinit var done: (QuestionCardView.Reply) -> Unit
        try {
            onMain {
                val host = FrameLayout(activity)
                activity.findViewById<ViewGroup>(android.R.id.content).addView(host)
                card = QuestionCardView(activity)
                host.addView(card)
                val submit: (QuestionIdentity, QuestionAnswer, (QuestionCardView.Reply) -> Unit) -> Unit = { _, _, complete -> done = complete }
                card.bind(owner, record(), true, submit)
                val group = (0 until card.childCount).map(card::getChildAt).filterIsInstance<RadioGroup>().single()
                group.check(group.getChildAt(0).id)
                (0 until card.childCount).map(card::getChildAt).filterIsInstance<Button>().single().performClick()
                host.removeView(card); host.addView(card)
                card.bind(owner, record(), true, submit)
                done(QuestionCardView.Reply.SAVED)
            }
            instrumentation.waitForIdleSync()
            onMain {
                val send = (0 until card.childCount).map(card::getChildAt).filterIsInstance<Button>().single()
                assertFalse(send.isEnabled)
            }
        } finally { onMain { activity.finish() } }
    }

}
