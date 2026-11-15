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
    fun testNativeSearchDecoderEdgesReachTheExactCallObserverWhenMainDeliveryIsDelayed() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        val unblock = CountDownLatch(1)
        var observation: app.daiyu.android.voice.control.VoiceControlCancellation? = null
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            val session = fixture.session("answer", held = true, holdSeconds = 30)
            fixture.database.draft("search fixture")
            assertNotNull(fixture.controller.send(null, "search fixture"))
            assertTrue(session.entered.await(10, TimeUnit.SECONDS))
            val message = checkNotNull(fixture.controller.active)
            val owner = app.daiyu.android.voice.control.VoiceAssistantOwner(
                message.conversationId.value, message.runId.value, message.id.value)
            val bodies = CopyOnWriteArrayList<app.daiyu.android.voice.control.VoiceAssistantBody>()
            controlMain {
                observation = app.daiyu.android.voice.control.AndroidUniversalOwnerObserver(fixture.controller) { false }
                    .observe(owner) { bodies += it }
            }
            val blocked = CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                blocked.countDown(); check(unblock.await(10, TimeUnit.SECONDS))
            }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val wire = listOf(
                """{"type":"response.web_search_call.searching","item_id":"search-fixture"}""",
                """{"type":"response.web_search_call.completed","item_id":"search-fixture"}""",
                """{"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}""",
                "[DONE]").joinToString("\n\n", postfix = "\n\n") { "data: $it" }
            app.daiyu.android.model.NativeCompletionStream.read(app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS,
                wire.reader().buffered(), { false }, checkNotNull(session.sink.get()))
            unblock.countDown()
            awaitControl("ordered native search edges") { bodies.any { it.searching } && bodies.any { it.searchCompletion != null } }
            val started = bodies.indexOfFirst { it.searching }
            val finished = bodies.indexOfFirst { it.searchCompletion != null }
            assertTrue(started >= 0 && finished > started)
            assertFalse(bodies[finished].searching)
            assertEquals(message.conversationId.value, bodies[finished].owner.conversationId)
            assertTrue(bodies.all { it.owner == owner })
            controlMain { observation?.cancel(); observation = null }
            session.release.countDown()
            awaitControl("run complete") { fixture.controller.active == null }
        } finally {
            unblock.countDown()
            controlMain { observation?.cancel() }
            fixture.close()
        }
    }

    fun testQueuedSearchSuccessReplayIsDroppedWhenItsExactRunIsRevoked() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        val unblock = CountDownLatch(1)
        var observation: AutoCloseable? = null
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            val session = fixture.session("answer", held = true, holdSeconds = 30)
            fixture.database.draft("replay fixture")
            assertNotNull(fixture.controller.send(null, "replay fixture"))
            assertTrue(session.entered.await(10, TimeUnit.SECONDS))
            val message = checkNotNull(fixture.controller.active)
            val owner = app.daiyu.android.voice.control.VoiceAssistantOwner(
                message.conversationId.value, message.runId.value, message.id.value)
            checkNotNull(session.sink.get())(ModelEvent.NativeSearch("search", app.daiyu.android.domain.NativeSearchPhase.COMPLETED))
            assertNotNull(fixture.controller.nativeSearchState(owner).completion)
            val blocked = CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                blocked.countDown(); check(unblock.await(10, TimeUnit.SECONDS))
            }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val notices = CopyOnWriteArrayList<app.daiyu.android.voice.control.VoiceNativeSearchState>()
            observation = fixture.controller.observeNativeSearch(owner) { notices += it }
            assertTrue(fixture.controller.stop(message))
            unblock.countDown()
            val barrier = CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post { barrier.countDown() }
            assertTrue(barrier.await(10, TimeUnit.SECONDS))
            assertTrue("A revoked owner must not replay success", notices.isEmpty())
        } finally {
            unblock.countDown(); observation?.close(); fixture.close()
        }
    }

    fun testCompressionTransitionsAreCapturedInOrderBeforeTheCoalescedUiNotification() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        val unblock = CountDownLatch(1)
        var observation: AutoCloseable? = null
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            val notices = CopyOnWriteArrayList<ChatController.VoiceCompressionNotice>()
            controlMain { observation = fixture.controller.observeMainCompression { notices += it } }
            val blocked = CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                blocked.countDown(); check(unblock.await(10, TimeUnit.SECONDS))
            }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val publish = ChatController::class.java.getDeclaredMethod("postCompressionActivity",
                app.daiyu.android.timeline.CompressionActivity::class.java).apply { isAccessible = true }
            val running = app.daiyu.android.timeline.CompressionActivity("compression-fixture", "voice-owner", 2, null)
            publish.invoke(fixture.controller, running)
            publish.invoke(fixture.controller, running.copy(running = false, succeeded = true))
            unblock.countDown()
            awaitControl("both compression transitions") { notices.size == 2 }
            assertEquals(listOf(true, false), notices.map { it.running })
            assertEquals(listOf(null, "compression-fixture"), notices.map { it.completion })
            assertTrue(notices.all { it.conversationId == "voice-owner" })
            controlMain { observation?.close(); observation = null }
        } finally {
            unblock.countDown()
            controlMain { observation?.close() }
            fixture.close()
        }
    }

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
            assertEquals("run-control", frozen[1].model)
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
                    val binding = checkNotNull(fixture.controller.providers.selectedConversationModel())
                    assertTrue(fixture.database.bindModel(null, fixture.database.modelBinding(null), binding))
                    fixture.controller.reasoningPreference.set(null, effort)
                    fixture.session("answer", held = false)
                    fixture.database.draft("retain exact choice")
                    val conversation = fixture.controller.send(null, "retain exact choice")
                    assertNotNull(conversation)
                    awaitControl("choice complete") { fixture.controller.active == null }
                    assertEquals(model, frozen.single().model)
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
            val chatBinding = checkNotNull(fixture.controller.providers.selectedConversationModel())
            assertTrue(fixture.database.bindModel(null, fixture.database.modelBinding(null), chatBinding))
            fixture.controller.reasoningPreference.set(null, ReasoningEffort.HIGH)
            fixture.session("answer", held = false)
            fixture.database.draft("chat reasoning")
            assertNotNull(fixture.controller.send(null, "chat reasoning"))
            awaitControl("chat complete") { fixture.controller.active == null }
            assertEquals(app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS, frozen.single().protocol)
            assertEquals("high", JSONObject(frozen.single().requestOptions.customBody).getString("reasoning_effort"))
            assertEquals(1, fixture.opens.get())
        } finally { fixture.close() }
    }

    fun testMissingRememberedVoiceModelCanBeExplicitlyRepairedWithoutStartingARequest() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            val source = fixture.database.create()
            val original = checkNotNull(fixture.database.modelBinding(source.id))
            val old = fixture.database.create(modelSource = source.id, temporaryVoice = true)
            val missing = app.daiyu.android.storage.ConversationModelBinding("deleted-provider", "deleted-model")
            assertTrue(fixture.database.bindModel(old.id, original, missing))
            fixture.database.delete(old.id)
            val result = AtomicReference<ChatController.VoiceCallStart?>()
            val complete = CountDownLatch(1)
            controlMain { fixture.controller.createVoiceCall(source.id) { result.set(it); complete.countDown() } }
            assertTrue(complete.await(10, TimeUnit.SECONDS))
            val repair = checkNotNull(result.get())
            assertTrue(repair.needsModelSelection)
            assertEquals(missing, fixture.database.modelBinding(repair.conversation.id))
            assertEquals(original, fixture.database.modelBinding(source.id))
            assertEquals(0, fixture.opens.get())
            assertTrue(fixture.database.messages(repair.conversation.id).isEmpty())
            assertTrue(fixture.database.bindModel(repair.conversation.id, missing, original))
            assertEquals("run-control", fixture.controller.freezeConversationProvider(repair.conversation.id).settings.model)
            controlMain { fixture.controller.discardVoiceCall(repair.conversation.id) }
            awaitControl("repair owner cleaned") { fixture.database.conversations().none { it.id == repair.conversation.id } }
            val restored = fixture.database.create(modelSource = source.id, temporaryVoice = true)
            assertEquals(original, fixture.database.modelBinding(restored.id))
            fixture.database.delete(restored.id)
        } finally { fixture.close() }
    }

    fun testVoiceCallInheritsSourceReasoningIntoItsActualTextRequestAndCleansItAfterHangup() {
        val frozen = CopyOnWriteArrayList<ProviderSnapshot>()
        val prompts = CopyOnWriteArrayList<String>()
        val fixture = RunControlFixture(instrumentation.targetContext, onSession = { p, assistant, _ ->
            frozen += p; prompts += assistant.systemPrompt
        })
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            fixture.controller.reasoningPreference.set(null, ReasoningEffort.LOW)
            val source = fixture.database.create()
            fixture.controller.reasoningPreference.set(source.id.value, ReasoningEffort.HIGH)
            val binding = checkNotNull(fixture.database.modelBinding(source.id))
            fixture.controller.reasoningPreference.rememberModel(binding.providerSlotId, binding.localModelIdentity, ReasoningEffort.OFF)
            val start = AtomicReference<ChatController.VoiceCallStart?>()
            val created = CountDownLatch(1)
            controlMain { fixture.controller.createVoiceCall(source.id) { start.set(it); created.countDown() } }
            assertTrue(created.await(10, TimeUnit.SECONDS))
            val temporary = checkNotNull(start.get()).conversation
            assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(temporary.id.value))
            val voiceReasoning = fixture.controller.voiceCallReasoning(temporary.id)
            controlMain { assertTrue(fixture.controller.setVoiceCallReasoning(voiceReasoning, ReasoningEffort.HIGH)) }
            assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(source.id.value))
            assertEquals(ReasoningEffort.OFF, fixture.controller.reasoningPreference.rememberedModel(binding.providerSlotId, binding.localModelIdentity))
            assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.rememberedVoiceModel(binding.providerSlotId, binding.localModelIdentity))
            fixture.controller.reasoningPreference.set(source.id.value, ReasoningEffort.OFF)
            fixture.session("voice answer", held = false)
            val ticket = ChatController.TextSendTicket()
            val result = AtomicReference<ChatController.TextSendResult?>()
            val accepted = CountDownLatch(1)
            controlMain {
                assertTrue(fixture.controller.sendText(temporary, 0, 1, "voice request", ticket, voiceConversation = true) {
                    result.set(it); fixture.controller.acknowledgeTextSend(ticket); accepted.countDown()
                })
            }
            assertTrue(accepted.await(10, TimeUnit.SECONDS))
            assertTrue(result.get() is ChatController.TextSendResult.Accepted)
            awaitControl("voice complete") { fixture.controller.active == null }
            val provider = frozen.single()
            assertTrue(provider.requestOptions.voiceTextOnly)
            assertTrue(prompts.single().contains(app.daiyu.android.voice.VoiceCallOutput.INSTRUCTIONS))
            assertEquals("high", JSONObject(provider.requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            assertEquals(1, fixture.opens.get())
            controlMain { fixture.controller.discardVoiceCall(temporary.id) }
            val key = "reasoning:conversation:${temporary.id.value}"
            awaitControl("temporary call deleted") {
                fixture.database.temporaryVoiceConversations().isEmpty() &&
                    !fixture.application.getSharedPreferences("conversation-ui", 0).contains(key)
            }
            assertEquals(ReasoningEffort.OFF, fixture.controller.reasoningPreference.snapshot(source.id.value))
            assertEquals(ReasoningEffort.LOW, fixture.controller.reasoningPreference.snapshot(null))
        } finally { fixture.close() }
    }

    fun testVoiceCallCopiesDraftAndExplicitOffButRejectsCorruptSourceBeforeCreatingAnyCall() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            fun create(source: app.daiyu.android.domain.ConversationId?): ChatController.VoiceCallStart? {
                val value = AtomicReference<ChatController.VoiceCallStart?>()
                val done = CountDownLatch(1)
                controlMain { fixture.controller.createVoiceCall(source) { value.set(it); done.countDown() } }
                assertTrue(done.await(10, TimeUnit.SECONDS))
                return value.get()
            }
            fixture.controller.reasoningPreference.set(null, ReasoningEffort.MINIMAL)
            val fromDraft = checkNotNull(create(null)).conversation
            assertEquals(ReasoningEffort.MINIMAL, fixture.controller.reasoningPreference.snapshot(fromDraft.id.value))
            controlMain { fixture.controller.discardVoiceCall(fromDraft.id) }
            awaitControl("draft call deleted") { fixture.database.temporaryVoiceConversations().isEmpty() }
            val source = fixture.database.create()
            fixture.controller.reasoningPreference.set(source.id.value, ReasoningEffort.OFF)
            val fromOff = checkNotNull(create(source.id)).conversation
            assertEquals(ReasoningEffort.OFF, fixture.controller.reasoningPreference.snapshot(fromOff.id.value))
            controlMain { fixture.controller.discardVoiceCall(fromOff.id) }
            awaitControl("off call deleted") { fixture.database.temporaryVoiceConversations().isEmpty() }
            assertTrue(fixture.application.getSharedPreferences("conversation-ui", 0).edit()
                .putString("reasoning:conversation:${source.id.value}", "corrupt-choice").commit())
            assertNull(create(source.id))
            assertTrue(fixture.database.temporaryVoiceConversations().isEmpty())
            assertEquals(0, fixture.opens.get())
        } finally { fixture.close() }
    }

}
