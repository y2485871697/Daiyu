package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.model.ProviderSnapshot
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ControllerReasoningFreezeTest : InstrumentationTestCase() {
    fun testStandaloneAndConversationPreferencesReachRealPreparationOnceAndSurviveEdits() {
        val frozen = CopyOnWriteArrayList<ProviderSnapshot>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var first = true
        val fixture = RunControlFixture(instrumentation.targetContext,
            onSession = { provider, _, _ -> frozen += provider },
            beforeModel = { if (first) { first = false; entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } })
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            fixture.controller.reasoningPreference.set(null, ReasoningEffort.LOW)
            fixture.database.draft("first")
            val initialSession = fixture.session("first answer", held = true)
            val accepted = AtomicReference<Conversation?>()
            val failure = AtomicReference<Throwable?>()
            val thread = Thread { try { accepted.set(fixture.controller.send(null, "first")) } catch (t: Throwable) { failure.set(t) } }
            thread.start()
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                fixture.controller.reasoningPreference.set(null, ReasoningEffort.HIGH)
                assertEquals("low", JSONObject(frozen.single().requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            } finally { release.countDown(); thread.join(10000) }
            assertFalse(thread.isAlive); failure.get()?.let { throw it }
            val conversation = checkNotNull(accepted.get())
            assertTrue(initialSession.entered.await(5, TimeUnit.SECONDS))
            checkNotNull(initialSession.sink.get())(ModelEvent.ReasoningFinal("reported", "accepted by real runtime checkpoint"))
            assertEquals("accepted by real runtime checkpoint", fixture.database.messages(conversation.id).last().second
                .historySegments.flatMap { it.reportedReasoning }.single().text)
            assertEquals(ReasoningEffort.LOW, fixture.controller.reasoningPreference.snapshot(conversation.id.value))
            fixture.controller.reasoningPreference.set(conversation.id.value, ReasoningEffort.OFF)
            // A real settings edit and preference edit while active cannot change the captured request.
            fixture.settings.save(fixture.settings.load().copy(model = "later-model"))
            initialSession.release.countDown()
            awaitControl("first complete") { fixture.controller.active == null }
            assertEquals(1, frozen.size)
            assertEquals("run-control", frozen[0].model)
            assertEquals("low", JSONObject(frozen[0].requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            fixture.session("second answer", held = false)
            fixture.database.draft(conversation.id, "second")
            assertNotNull(fixture.controller.send(conversation, "second"))
            awaitControl("second complete") { fixture.controller.active == null }
            assertEquals(2, frozen.size)
            assertEquals("later-model", frozen[1].model)
            assertEquals("none", JSONObject(frozen[1].requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(null))
            assertEquals(ReasoningEffort.OFF, app.daiyu.android.ui.model.ConversationReasoningPreference(
                fixture.application.getSharedPreferences("conversation-ui", 0)).snapshot(conversation.id.value))
        } finally { release.countDown(); fixture.close() }
    }

    fun testConflictingCustomPreferenceRejectsBeforeFactoryAcceptanceAndKeepsDraft() {
        val frozen = CopyOnWriteArrayList<ProviderSnapshot>()
        val fixture = RunControlFixture(instrumentation.targetContext, onSession = { p, _, _ -> frozen += p })
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            fixture.settings.save(fixture.settings.load().copy(requestOptions = app.daiyu.android.model.ProviderRequestOptions("""{"reasoning":{"effort":"high"}}""")))
            fixture.controller.reasoningPreference.set(null, ReasoningEffort.MAX)
            fixture.database.draft("retain")
            assertNull(fixture.controller.send(null, "retain"))
            assertTrue(checkNotNull(fixture.controller.problem).contains("思考偏好与自定义推理参数冲突"))
            assertEquals("retain", fixture.database.draft())
            assertEquals(0, fixture.opens.get()); assertTrue(frozen.isEmpty())
            assertTrue(fixture.database.conversations().isEmpty())
        } finally { fixture.close() }
    }
    fun testFormerlyRestrictedModelAcceptsEveryChoiceWithoutLocalRejection() {
        for (model in listOf("gpt-6-astra", "gpt-6.1-sol"))
            for (effort in ReasoningEffort.textChoices(model)) {
                val frozen = CopyOnWriteArrayList<ProviderSnapshot>()
                val fixture = RunControlFixture(instrumentation.targetContext, onSession = { p, _, _ -> frozen += p })
                try {
                    awaitControl("controller ready") { fixture.controller.ready }
                    fixture.settings.save(fixture.settings.load().copy(model = model, reasoningOverride = false))
                    fixture.controller.reasoningPreference.set(null, effort)
                    fixture.session("answer", held = false)
                    fixture.database.draft("retain exact choice")
                    val conversation = fixture.controller.send(null, "retain exact choice")
                    assertNotNull(conversation)
                    awaitControl("choice complete") { fixture.controller.active == null }
                    assertEquals(if (effort == ReasoningEffort.OFF) "none" else effort.wireValue,
                        JSONObject(frozen.single().requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
                    assertEquals(effort, fixture.controller.reasoningPreference.snapshot(conversation!!.id.value))
                    assertEquals(1, fixture.opens.get())
                } finally { fixture.close() }
            }
    }

    fun testChatHighPreferenceReachesSharedProductionFreezeWithoutLocalRejection() {
        val frozen = CopyOnWriteArrayList<ProviderSnapshot>()
        val fixture = RunControlFixture(instrumentation.targetContext, onSession = { p, _, _ -> frozen += p })
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            fixture.settings.save(fixture.settings.load().copy(endpoint = "https://provider.invalid/v1",
                protocol = app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS), protocolChangeAuthorized = true)
            fixture.controller.reasoningPreference.set(null, ReasoningEffort.HIGH)
            fixture.session("answer", held = false)
            fixture.database.draft("chat reasoning")
            assertNotNull(fixture.controller.send(null, "chat reasoning"))
            awaitControl("chat complete") { fixture.controller.active == null }
            assertEquals("high", JSONObject(frozen.single().requestOptions.customBody).getString("reasoning_effort"))
            assertEquals(1, fixture.opens.get())
        } finally { fixture.close() }
    }
}
