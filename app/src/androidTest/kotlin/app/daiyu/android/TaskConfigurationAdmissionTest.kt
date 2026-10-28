package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.model.catalog.selectionFor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class TaskConfigurationAdmissionTest : InstrumentationTestCase() {
    fun testActiveTaskRejectsModelReasoningAndCrossConversationDeletionUntilDrained() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("ready") { fixture.controller.ready }
            fixture.controller.assistantStore.save(AssistantProfile("other", "Other", ""))
            val running = fixture.database.create("default")
            val viewing = fixture.database.create("other")
            fixture.database.draft(viewing.id, "untouched")
            val editor = fixture.controller.providers.activeEditor()
            val saved = fixture.controller.providers.withEditor(editor) { it.load() }
            val ticket = checkNotNull(fixture.controller.providers.catalogSnapshot(editor).selectionFor(saved.model))
            val session = fixture.session(held = true, holdSeconds = 30, releaseOnCancel = false)
            checkNotNull(fixture.controller.send(running, "run"))
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            val preferences = fixture.application.getSharedPreferences("conversation-ui", 0).all.toMap()
            val active = checkNotNull(fixture.controller.active)
            controlMain {
                assertTrue(fixture.controller.taskConfigurationBusy())
                assertFalse(fixture.controller.setChatReasoning(viewing.id.value, ReasoningEffort.HIGH))
                assertFalse(fixture.controller.bindConversationAssistant(viewing.id, "other", "default"))
                try { fixture.controller.deleteAssistant("default"); fail("running assistant deleted") }
                catch (failure: IllegalStateException) { assertEquals("请先结束任务", failure.message) }
                try {
                    fixture.controller.selectActiveProviderCatalogModel(editor, saved, editor, saved, ticket, { false }) {
                        fail("rejected model switch must not queue work")
                    }
                    fail("running model switch accepted")
                } catch (failure: IllegalStateException) { assertEquals("请先结束任务", failure.message) }
            }
            assertEquals(saved, fixture.controller.providers.withEditor(editor) { it.load() })
            assertEquals(preferences, fixture.application.getSharedPreferences("conversation-ui", 0).all.toMap())
            assertEquals(active.id, fixture.controller.active?.id)
            assertEquals(1L, session.cancelled.count)
            assertNotNull(fixture.controller.assistantStore.read("default"))
            assertEquals("other", fixture.database.assistantId(viewing.id))
            assertEquals("untouched", fixture.database.conversations().first { it.id == viewing.id }.draft)
            controlMain { fixture.controller.stop() }
            assertTrue(fixture.controller.taskConfigurationBusy())
            session.release.countDown()
            awaitControl("fully drained") { !fixture.controller.taskConfigurationBusy() }
            controlMain {
                assertTrue(fixture.controller.setChatReasoning(viewing.id.value, ReasoningEffort.HIGH))
                fixture.controller.deleteAssistant("default")
                assertTrue(fixture.controller.dataBusy)
            }
            awaitControl("delete finished") { !fixture.controller.dataBusy }
            assertNull(fixture.controller.assistantStore.read("default"))
        } finally { fixture.close() }
    }

    fun testPreparationBeforeActiveAlsoRejectsReasoningAndDeletion() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val fixture = RunControlFixture(instrumentation.targetContext, beforeModel = {
            entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
        })
        val failure = AtomicReference<Throwable?>()
        var sender: Thread? = null
        try {
            awaitControl("ready") { fixture.controller.ready }
            fixture.controller.assistantStore.save(AssistantProfile("other", "Other", ""))
            fixture.session(held = false)
            sender = Thread {
                try { fixture.controller.send(null, "preparing") }
                catch (problem: Throwable) { failure.set(problem) }
            }.apply { start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(fixture.controller.active)
            controlMain {
                assertTrue(fixture.controller.taskConfigurationBusy())
                assertFalse(fixture.controller.setChatReasoning(null, ReasoningEffort.MAX))
                try { fixture.controller.deleteAssistant("default"); fail("preparing delete accepted") }
                catch (problem: IllegalStateException) { assertEquals("请先结束任务", problem.message) }
            }
            assertEquals(ReasoningEffort.OFF, fixture.controller.reasoningPreference.snapshot(null))
            assertNotNull(fixture.controller.assistantStore.read("default"))
        } finally {
            release.countDown(); sender?.join(15_000); fixture.close()
        }
        assertFalse(checkNotNull(sender).isAlive)
        failure.get()?.let { throw it }
    }
    fun testPersistedOtherConversationUseKeepsExactBusyNoticeAndProfile() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("ready") { fixture.controller.ready }
            fixture.controller.assistantStore.save(AssistantProfile("other", "Other", ""))
            val running = fixture.database.create("default")
            val run = app.daiyu.android.domain.RunId("persisted-run")
            val user = app.daiyu.android.domain.Message(app.daiyu.android.domain.MessageId("persisted-user"),
                running.id, run, "user", emptyList(), app.daiyu.android.domain.RunStatus.COMPLETED)
            val assistant = user.copy(id = app.daiyu.android.domain.MessageId("persisted-assistant"),
                role = "assistant", status = app.daiyu.android.domain.RunStatus.RUNNING)
            fixture.database.send(user, assistant, expectedAssistantId = "default")
            controlMain { fixture.controller.deleteAssistant("default") }
            awaitControl("delete refused") { !fixture.controller.dataBusy }
            controlMain { assertEquals("请先结束任务", fixture.controller.consumeDataNotice()) }
            assertNotNull(fixture.controller.assistantStore.read("default"))
            assertEquals("default", fixture.database.assistantId(running.id))
            assertEquals(app.daiyu.android.domain.RunStatus.RUNNING, fixture.database.messages(running.id).last().second.status)
        } finally { fixture.close() }
    }

}
