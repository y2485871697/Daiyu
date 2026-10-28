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
    private fun prepareChoices(f: RunControlFixture): Pair<app.daiyu.android.domain.Conversation, app.daiyu.android.domain.Conversation> {
        f.controller.assistantStore.save(AssistantProfile("other", "Other", ""))
        val catalog = f.settings.modelCatalogSnapshot()
        check(f.settings.modelCatalog.compareAndAppend(catalog.providerIdentity, catalog.revision,
            listOf(app.daiyu.android.model.catalog.CatalogModelParameters.newModel(app.daiyu.android.model.catalog.RemoteModel("other-model")))) { false }
            == app.daiyu.android.model.catalog.CatalogCommitResult.COMMITTED)
        return f.database.create("default") to f.database.create("default")
    }
    private fun switchModel(f: RunControlFixture, owner: app.daiyu.android.domain.ConversationId, model: String) {
        val (editor, editing) = f.controller.conversationModelEditing(owner)
        val global = f.controller.providers.withEditor(editor) { it.load() }
        val ticket = checkNotNull(f.controller.providers.catalogSnapshot(editor).selectionFor(model))
        val done = CountDownLatch(1); val saved = AtomicReference<Boolean>()
        controlMain {
            f.controller.selectActiveProviderCatalogModel(editor, editing.settings, editor, global, ticket,
                cancelled = { false }, completed = { saved.set(it); done.countDown() }, conversation = owner)
        }
        assertTrue("configuration must finish while A is still held", done.await(5, TimeUnit.SECONDS))
        assertEquals(true, saved.get())
        assertEquals(app.daiyu.android.storage.ConversationModelBinding(editor.slotId, ticket.record.settings.localIdentity), f.database.modelBinding(owner))
        assertEquals(model, f.controller.conversationModelEditing(owner).second.settings.model)
    }
    private fun assertOwnerProtection(f: RunControlFixture, running: app.daiyu.android.domain.Conversation,
        viewing: app.daiyu.android.domain.Conversation) {
        controlMain {
            assertTrue(f.controller.taskConfigurationBusy(running.id))
            assertFalse(f.controller.taskConfigurationBusy(viewing.id))
            assertFalse(f.controller.bindConversationAssistant(running.id, "default", "other"))
            assertFalse(f.controller.setChatReasoning(running.id.value, ReasoningEffort.MAX))
            assertTrue(f.controller.setChatReasoning(viewing.id.value, ReasoningEffort.HIGH))
            val (editor, editing) = f.controller.conversationModelEditing(running.id)
            val global = f.controller.providers.withEditor(editor) { it.load() }
            val ticket = checkNotNull(f.controller.providers.catalogSnapshot(editor).selectionFor("other-model"))
            try {
                f.controller.selectActiveProviderCatalogModel(editor, editing.settings, editor, global, ticket,
                    cancelled = { false }, completed = { fail("busy owner queued") }, conversation = running.id)
                fail("busy owner accepted")
            } catch (failure: IllegalStateException) { assertEquals("请先结束任务", failure.message) }
            try { f.controller.deleteAssistant("default"); fail("shared assistant deleted") }
            catch (failure: IllegalStateException) { assertEquals("请先结束任务", failure.message) }
        }
    }
    fun testActiveAndDrainingAAllowBModelAssistantAndReasoningWithoutChangingA() {
        val frozen = java.util.concurrent.CopyOnWriteArrayList<app.daiyu.android.model.ProviderSnapshot>()
        val fixture = RunControlFixture(instrumentation.targetContext, onSession = { provider, _, _ -> frozen += provider })
        try {
            awaitControl("ready") { fixture.controller.ready }
            val (running, viewing) = prepareChoices(fixture)
            controlMain { assertTrue(fixture.controller.setChatReasoning(running.id.value, ReasoningEffort.LOW)) }
            fixture.database.draft(viewing.id, "untouched")
            val originalModel = fixture.database.modelBinding(running.id)
            val registry = fixture.controller.providers.snapshot()
            val globalSettings = fixture.settings.load()
            val session = fixture.session(held = true, holdSeconds = 30, releaseOnCancel = false)
            checkNotNull(fixture.controller.send(running, "run"))
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            val active = checkNotNull(fixture.controller.active)
            assertOwnerProtection(fixture, running, viewing)
            controlMain { assertTrue(fixture.controller.bindConversationAssistant(viewing.id, "default", "other")) }
            switchModel(fixture, viewing.id, "other-model")
            controlMain { assertTrue(fixture.controller.setChatReasoning(viewing.id.value, ReasoningEffort.HIGH)) }
            assertFalse(fixture.controller.dataBusy)
            assertEquals(active.id, fixture.controller.active?.id)
            assertEquals(1L, session.cancelled.count)
            assertEquals(originalModel, fixture.database.modelBinding(running.id))
            assertEquals("default", fixture.database.assistantId(running.id))
            assertEquals("other", fixture.database.assistantId(viewing.id))
            assertEquals(registry, fixture.controller.providers.snapshot())
            assertEquals(globalSettings, fixture.settings.load())
            assertEquals("default", fixture.controller.assistantStore.selectedId.value)
            assertEquals("untouched", fixture.database.conversations().first { it.id == viewing.id }.draft)
            assertEquals(ReasoningEffort.LOW, fixture.controller.reasoningPreference.snapshot(running.id.value))
            assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(viewing.id.value))
            assertEquals(1, frozen.size)
            assertEquals("low", org.json.JSONObject(frozen.single().requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            controlMain { fixture.controller.stop() }
            assertTrue(session.cancelled.await(5, TimeUnit.SECONDS))
            assertOwnerProtection(fixture, running, viewing)
            controlMain { assertTrue(fixture.controller.bindConversationAssistant(viewing.id, "other", "default")) }
            switchModel(fixture, viewing.id, globalSettings.model)
            controlMain { assertTrue(fixture.controller.setChatReasoning(viewing.id.value, ReasoningEffort.MAX)) }
            assertEquals(ReasoningEffort.MAX, fixture.controller.reasoningPreference.snapshot(viewing.id.value))
            assertEquals("default", fixture.database.assistantId(viewing.id))
            assertEquals(originalModel, fixture.database.modelBinding(running.id))
            assertEquals(registry, fixture.controller.providers.snapshot())
            assertEquals(globalSettings, fixture.settings.load())
            assertEquals("default", fixture.controller.assistantStore.selectedId.value)
            assertEquals(1, frozen.size)
            assertEquals("low", org.json.JSONObject(frozen.single().requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            assertEquals(1L, session.release.count)
            session.release.countDown()
            awaitControl("fully drained") { !fixture.controller.taskConfigurationBusy() }
            controlMain {
                assertTrue(fixture.controller.setChatReasoning(running.id.value, ReasoningEffort.HIGH))
                assertTrue(fixture.controller.bindConversationAssistant(running.id, "default", "other"))
            }
        } finally { fixture.close() }
    }
    fun testPreparingAAllowsBAndPreservesTheOriginalFrozenOwner() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val frozen = java.util.concurrent.CopyOnWriteArrayList<app.daiyu.android.model.ProviderSnapshot>()
        val fixture = RunControlFixture(instrumentation.targetContext, onSession = { provider, _, _ -> frozen += provider }, beforeModel = {
            entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
        })
        val failure = AtomicReference<Throwable?>(); var sender: Thread? = null
        val accepted = AtomicReference<app.daiyu.android.domain.Conversation?>()
        try {
            awaitControl("ready") { fixture.controller.ready }
            val (running, viewing) = prepareChoices(fixture)
            controlMain { assertTrue(fixture.controller.setChatReasoning(running.id.value, ReasoningEffort.LOW)) }
            val originalModel = fixture.database.modelBinding(running.id)
            val registry = fixture.controller.providers.snapshot()
            val globalSettings = fixture.settings.load()
            fixture.session(held = false)
            sender = Thread { try { accepted.set(fixture.controller.send(running, "preparing")) }
                catch (problem: Throwable) { failure.set(problem) } }.apply { start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertNull(fixture.controller.active)
            assertOwnerProtection(fixture, running, viewing)
            controlMain { assertTrue(fixture.controller.bindConversationAssistant(viewing.id, "default", "other")) }
            switchModel(fixture, viewing.id, "other-model")
            controlMain { assertTrue(fixture.controller.setChatReasoning(viewing.id.value, ReasoningEffort.HIGH)) }
            assertFalse(fixture.controller.dataBusy)
            assertEquals(1L, release.count)
            assertEquals("default", fixture.database.assistantId(running.id))
            assertEquals(ReasoningEffort.LOW, fixture.controller.reasoningPreference.snapshot(running.id.value))
            assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(viewing.id.value))
            assertEquals("other", fixture.database.assistantId(viewing.id))
            assertEquals(originalModel, fixture.database.modelBinding(running.id))
            assertEquals(registry, fixture.controller.providers.snapshot())
            assertEquals(globalSettings, fixture.settings.load())
            assertEquals("default", fixture.controller.assistantStore.selectedId.value)
            assertEquals(1, frozen.size)
            assertEquals("low", org.json.JSONObject(frozen.single().requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            release.countDown(); checkNotNull(sender).join(15_000)
            assertFalse(checkNotNull(sender).isAlive); failure.get()?.let { throw it }
            assertEquals(running.id, accepted.get()?.id)
        } finally { release.countDown(); sender?.join(15_000); fixture.close() }
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
