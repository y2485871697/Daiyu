package app.daiyu.android.question

import android.app.AlertDialog
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import app.daiyu.android.MainActivity
import app.daiyu.android.testContext
import app.daiyu.android.domain.question.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class QuestionTest : InstrumentationTestCase() {
    private fun arguments() = JSONObject("""{"title":"关键选择","question":"选哪个？","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}],"recommended_option_id":"b"}""")
    private fun context() = testContext(instrumentation.targetContext, "question-${UUID.randomUUID()}-")
    private fun failure(expected: QuestionFailure, block: () -> Unit) {
        try { block(); fail("Expected $expected") } catch (error: QuestionException) { assertEquals(expected, error.failure) }
    }
    fun testPrivatePersistenceAnswerIdempotencyAndOwnerScope() {
        val context = context(); var registry = QuestionRegistry(context)
        val id = QuestionIdentity("conversation", "run", "owner", "call")
        try {
            assertEquals("call_id_required", registry.execute("ask_user", arguments()).getString("error"))
            registry.register(id, arguments())
            registry.submit(id, QuestionAnswer.Selected("a", "备注"))
            registry.submit(id, QuestionAnswer.Selected("a", "备注"))
            failure(QuestionFailure.DUPLICATE_ANSWER) { registry.submit(id, QuestionAnswer.Selected("b")) }
            registry.close(); registry = QuestionRegistry(context)
            val replay = registry.execute(id, "ask_user", arguments())
            assertTrue(replay.getBoolean("ok")); assertEquals("option", replay.getJSONObject("answer").getString("kind"))
            assertEquals("a", replay.getJSONObject("answer").getString("option_id"))
            registry.register(id.copy(ownerId = "new-owner"), arguments())
            assertNull(registry.records().last().answer)
            assertEquals(2, registry.records().size)
        } finally { registry.close(); context.deleteDatabase("questions.db") }
    }
    fun testInjectedStoreRecoveryAndStopAreOwnerScoped() {
        val context = context(); val store = PrivateQuestionStore(context)
        val a = QuestionIdentity("conversation", "run", "a", "call")
        val b = a.copy(ownerId = "b")
        val registryA = QuestionRegistry(store, a)
        val registryB = QuestionRegistry(store, b)
        try {
            registryA.register(a, arguments()); registryB.register(b, arguments())
            registryA.interrupt()
            assertTrue(store.get(a.storageKey)!!.interrupted)
            assertFalse(store.get(b.storageKey)!!.interrupted)
            assertNotNull(registryB.barrier.pending())
            // Replacement/recovery must not overlap a live registry for the same owner.
            registryB.close()
            val recoveredB = QuestionRegistry(store, b, recoverOwner = true)
            try {
                assertTrue(store.get(b.storageKey)!!.interrupted)
                assertNull(recoveredB.barrier.pending())
                assertNull(store.get(b.storageKey)!!.answer)
                assertEquals("expired_question", recoveredB.execute(b, "ask_user", arguments()).getString("error"))
            } finally { recoveredB.close() }
        } finally { registryA.close(); registryB.close(); store.close(); context.deleteDatabase("questions.db") }
    }
    fun testOwnerRecoveryInterruptedNotResumedOrShown() {
        val context = context(); val identity = QuestionIdentity("conversation", "run", "owner", "orphan")
        // Closing only the storage connection simulates a lost runner without normal barrier.close.
        val storage = PrivateQuestionStore(context)
        storage.create(QuestionRecord(identity.storageKey, AskUserContract.parse(arguments().toString())))
        storage.close()
        val registry = QuestionRegistry(context)
        try {
            assertTrue(registry.records().single().interrupted)
            assertTrue(registry.records().single().summary().contains("中断"))
            assertNull(registry.records().single().answer)
            var shown: QuestionRecord? = null; registry.barrier.attach { shown = it }; assertNull(shown)
            assertEquals("expired_question", registry.execute(identity, "ask_user", arguments()).getString("error"))
            failure(QuestionFailure.EXPIRED_QUESTION) { registry.submit(identity, QuestionAnswer.Selected("a")) }
        } finally { registry.close(); context.deleteDatabase("questions.db") }
    }
    fun testSQLiteUnicodeAndNulOwnerPrefixesAreExclusiveAndIsolated() {
        // NUL is exercised at the SQLite boundary, not as a new public Identity policy.
        for (nul in listOf(false, true)) {
            val context = context(); val store = PrivateQuestionStore(context)
            val a = QuestionIdentity(if (nul) "conversation" else "会话😀", if (nul) "run" else "运行🚀", if (nul) "ownerXa" else "owner😀", "call")
            val b = a.copy(ownerId = if (nul) "ownerXb" else "owner😁")
            fun key(value: String) = if (nul) value.replace('X', '\u0000') else value
            fun record(id: QuestionIdentity) = QuestionRecord(key(id.storageKey), AskUserContract.parse(arguments().toString()))
            try {
                assertTrue(store.create(record(a)))
                // Different provider call IDs must not bypass the owner's single pending question.
                assertFalse(store.create(record(a.copy(callId = "second"))))
                assertTrue(store.create(record(b)))
                store.interruptPending(key(a.ownerPrefix))
                assertTrue(store.get(key(a.storageKey))!!.interrupted)
                assertFalse(store.get(key(b.storageKey))!!.interrupted)
                assertNull(store.get(key(a.storageKey))!!.answer)
                assertTrue(store.create(record(a.copy(callId = "second"))))
                assertTrue(store.answer(key(b.storageKey), QuestionAnswer.Selected("a")))
                store.close()
                // An actual new SQLite connection must preserve the exact Unicode/NUL key and state.
                val reopened = PrivateQuestionStore(context)
                try {
                    assertTrue(reopened.get(key(a.storageKey))!!.interrupted)
                    assertEquals(QuestionAnswer.Selected("a"), reopened.get(key(b.storageKey))!!.answer)
                    assertFalse(reopened.get(key(a.copy(callId = "second").storageKey))!!.interrupted)
                    reopened.interruptPending("")
                    assertTrue(reopened.get(key(a.copy(callId = "second").storageKey))!!.interrupted)
                    assertFalse(reopened.get(key(b.storageKey))!!.interrupted)
                } finally { reopened.close() }
            } finally { store.close(); context.deleteDatabase("questions.db") }
        }
    }

    fun testSQLiteUnicodeScopedReopenRecoveryDoesNotAskAgain() {
        val context = context()
        val a = QuestionIdentity("会话😀", "运行🚀", "owner😀", "orphan")
        val answered = a.copy(callId = "answered")
        val b = a.copy(ownerId = "owner😁")
        var store = PrivateQuestionStore(context)
        var recovered: QuestionRegistry? = null
        try {
            val question = AskUserContract.parse(arguments().toString())
            assertTrue(store.create(QuestionRecord(answered.storageKey, question)))
            assertTrue(store.answer(answered.storageKey, QuestionAnswer.Selected("b", "保留")))
            assertTrue(store.create(QuestionRecord(a.storageKey, question)))
            assertTrue(store.create(QuestionRecord(b.storageKey, question)))
            // Simulate process loss: do not close a barrier (which would interrupt before reopen).
            store.close(); store = PrivateQuestionStore(context)
            val registry = QuestionRegistry(store, a, recoverOwner = true)
            recovered = registry
            assertTrue(store.get(a.storageKey)!!.interrupted)
            assertNull(store.get(a.storageKey)!!.answer)
            assertFalse(store.get(b.storageKey)!!.interrupted)
            assertNull(registry.barrier.pending())
            var shown: QuestionRecord? = null
            registry.barrier.attach { shown = it }
            assertNull(shown)
            assertEquals("expired_question", registry.execute(a, "ask_user", arguments()).getString("error"))
            failure(QuestionFailure.EXPIRED_QUESTION) { registry.submit(a, QuestionAnswer.Selected("a")) }
            val replay = registry.execute(answered, "ask_user", arguments())
            assertTrue(replay.getBoolean("ok"))
            assertEquals("b", replay.getJSONObject("answer").getString("option_id"))
            assertEquals("保留", replay.getJSONObject("answer").getString("note"))
            assertEquals(3, store.records().size)
            assertFalse(store.get(b.storageKey)!!.interrupted)
        } finally { recovered?.close(); store.close(); context.deleteDatabase("questions.db") }
    }

    fun testBatchCapabilityValidationAndCloseUnblocksWaiting() {
        val context = context(); val registry = QuestionRegistry(context)
        try {
            assertEquals("batch_conflict", registry.execute("call", "ask_user", arguments(), listOf("ask_user", "device_status")).getString("error"))
            assertTrue(registry.records().isEmpty())
            assertEquals("invalid_arguments", registry.execute("bad", "ask_user", arguments().put("allow_other", "false")).getString("error"))
            assertTrue(registry.records().isEmpty())
            val args = arguments().put("allow_other", false).put("allow_delegation", false).put("allow_note", false)
            registry.register("restricted", args)
            failure(QuestionFailure.INVALID_ANSWER) { registry.submit("restricted", QuestionAnswer.Other("x")) }
            failure(QuestionFailure.INVALID_ANSWER) { registry.submit("restricted", QuestionAnswer.Delegate()) }
            failure(QuestionFailure.INVALID_ANSWER) { registry.submit("restricted", QuestionAnswer.Selected("a", "note")) }
            val done = CountDownLatch(1); val failure = AtomicReference<Throwable>()
            val worker = Thread {
                try { registry.execute("restricted", "ask_user", args); failure.set(AssertionError("continued without an answer")) }
                catch (_: InterruptedException) {} catch (error: Throwable) { failure.set(error) }
                finally { done.countDown() }
            }
            worker.start(); assertFalse(done.await(30, TimeUnit.MILLISECONDS)); registry.interrupt()
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertNull(failure.get())
            assertTrue(registry.records().single().interrupted); assertNull(registry.records().single().answer)
        } finally { registry.close(); context.deleteDatabase("questions.db") }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun window(host: QuestionHost): AlertDialog? = QuestionHost::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(host) as? AlertDialog

    fun testNativeDialogRequiresSelectionDetachRetainsQuestionAndTranscript() {
        val context = context(); val registry = QuestionRegistry(context); val host = QuestionHost(registry)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val summaries = mutableListOf<String>()
        try {
            registry.register("ui", arguments().put("allow_other", false).put("allow_delegation", false).put("allow_note", false))
            instrumentation.runOnMainSync { host.attach(activity) { summaries.add(it.summary()) } }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialog = window(host)!!
                val all = views(dialog.window!!.decorView)
                assertEquals(2, all.filterIsInstance<RadioButton>().size)
                assertTrue(all.filterIsInstance<EditText>().isEmpty())
                assertEquals(-1, all.filterIsInstance<RadioGroup>().single().checkedRadioButtonId)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing); assertNull(registry.records().single().answer)
                host.detach()
                assertNull(window(host)); assertNotNull(registry.barrier.pending())
                host.attach(activity) { summaries.add(it.summary()) }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialog = window(host)!!
                val group = views(dialog.window!!.decorView).filterIsInstance<RadioGroup>().single()
                group.check((group.getChildAt(0) as RadioButton).id)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            assertEquals(QuestionAnswer.Selected("a"), registry.records().single().answer)
            assertTrue(summaries.any { it.contains("等待用户回答") }); assertTrue(summaries.any { it.contains("已选择：A") })
        } finally {
            instrumentation.runOnMainSync { host.detach(); activity.finish() }
            registry.close(); context.deleteDatabase("questions.db")
        }
    }

    private class FaultStore : QuestionStore {
        private val data = linkedMapOf<String, QuestionRecord>()
        var failAnswer = false
        var failRecords = false
        var recordsFailures = 0
        var failRecordsAfterAnswer = false
        var answerAttempts = 0
        override fun get(callId: String) = data[callId]
        override fun records(): List<QuestionRecord> {
            if (failRecords) { recordsFailures++; throw IllegalStateException("private-attach-read-token") }
            if (failRecordsAfterAnswer && data.values.any { it.answer != null }) throw IllegalStateException("private-read-token")
            return data.values.toList()
        }
        override fun create(record: QuestionRecord): Boolean { if (data.containsKey(record.callId)) return false; data[record.callId] = record; return true }
        override fun answer(callId: String, answer: QuestionAnswer): Boolean {
            answerAttempts++
            if (failAnswer) throw IllegalStateException("private-write-token")
            val old = data[callId] ?: return false
            if (old.answer != null || old.interrupted) return false
            data[callId] = old.copy(answer = answer); return true
        }
        override fun interruptPending() = interruptPending("")
        override fun interruptPending(ownerPrefix: String) {
            data.replaceAll { key, row -> if (key.startsWith(ownerPrefix) && row.answer == null) row.copy(interrupted = true) else row }
        }
    }

    fun testInitialAttachReadFailureIsContainedAndReattachRestoresPending() {
        val store = FaultStore(); val id = QuestionIdentity("conversation", "run", "owner", "ui")
        val registry = QuestionRegistry(store, id); val host = QuestionHost(registry)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var transcriptCalls = 0
        try {
            registry.register(id, arguments())
            store.failRecords = true
            instrumentation.runOnMainSync {
                // Must return normally despite attach's synchronous pending-state read failing.
                host.attach(activity) { transcriptCalls++ }
                assertTrue(store.recordsFailures > 0)
                assertNull(window(host))
                assertEquals(0, transcriptCalls)
            }
            instrumentation.waitForIdleSync()
            assertNull(store.get(id.storageKey)!!.answer)
            assertFalse(store.get(id.storageKey)!!.interrupted)
            instrumentation.runOnMainSync {
                store.failRecords = false
                host.detach(); host.attach(activity) { transcriptCalls++ }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialog = window(host)!!
                assertTrue(dialog.isShowing)
                assertEquals(-1, views(dialog.window!!.decorView).filterIsInstance<RadioGroup>().single().checkedRadioButtonId)
                assertTrue(transcriptCalls > 0)
                assertFalse(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("private-") })
            }
            assertNull(store.get(id.storageKey)!!.answer)
            assertEquals(0, store.answerAttempts)
        } finally { instrumentation.runOnMainSync { host.detach(); activity.finish() }; registry.close() }
    }

    fun testWriteFailureKeepsWindowSelectionAndAllowsExplicitRetry() {
        val store = FaultStore(); val id = QuestionIdentity("conversation", "run", "owner", "ui")
        val registry = QuestionRegistry(store, id); val host = QuestionHost(registry)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            registry.register(id, arguments())
            instrumentation.runOnMainSync { host.attach(activity) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialog = window(host)!!
                val all = views(dialog.window!!.decorView)
                val group = all.filterIsInstance<RadioGroup>().single()
                val chosen = (group.getChildAt(0) as RadioButton).id
                group.check(chosen)
                all.filterIsInstance<EditText>().last().setText("保留备注")
                store.failAnswer = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertSame(dialog, window(host)); assertTrue(dialog.isShowing)
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                assertNull(store.get(id.storageKey)!!.answer); assertEquals(chosen, group.checkedRadioButtonId)
                assertEquals("保留备注", all.filterIsInstance<EditText>().last().text.toString())
                val messages = all.filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(messages.contains("答案保存失败，请重试；尚未提交答案"))
                assertFalse(messages.any { it.contains("private-write-token") })
                store.failAnswer = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals(QuestionAnswer.Selected("a", "保留备注"), store.get(id.storageKey)!!.answer)
                assertEquals(2, store.answerAttempts); assertFalse(dialog.isShowing)
            }
            instrumentation.waitForIdleSync()
        } finally { instrumentation.runOnMainSync { host.detach(); activity.finish() }; registry.close() }
    }

    fun testCommittedAnswerReadOrTranscriptFailureDoesNotOfferResubmit() {
        for (failRead in listOf(false, true)) {
            val store = FaultStore(); val id = QuestionIdentity("conversation", "run", "owner", "ui")
            val registry = QuestionRegistry(store, id); val host = QuestionHost(registry)
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                registry.register(id, arguments())
                instrumentation.runOnMainSync {
                    host.attach(activity) { if (!failRead && it.answer != null) throw IllegalStateException("private-transcript-token") }
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val dialog = window(host)!!
                    val all = views(dialog.window!!.decorView)
                    val group = all.filterIsInstance<RadioGroup>().single()
                    group.check((group.getChildAt(0) as RadioButton).id)
                    store.failRecordsAfterAnswer = failRead
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertEquals(QuestionAnswer.Selected("a"), store.get(id.storageKey)!!.answer)
                    assertTrue(dialog.isShowing); assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                    assertEquals(1, store.answerAttempts)
                    val messages = all.filterIsInstance<TextView>().map { it.text.toString() }
                    assertTrue(messages.contains("答案已提交，但消息刷新失败；请稍后查看提问状态"))
                    assertFalse(messages.any { it.contains("private-") })
                }
                // Exercise the posted barrier refresh too: exceptions must not escape Android main.
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals(1, store.answerAttempts)
                    store.failRecordsAfterAnswer = false
                    host.detach(); host.attach(activity)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { assertNull(window(host)) }
                assertEquals(QuestionAnswer.Selected("a"), registry.records().single().answer)
            } finally { instrumentation.runOnMainSync { host.detach(); activity.finish() }; registry.close() }
        }
    }
}
