package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.ui.screens.settings.SettingsCatalog
import app.daiyu.android.voice.control.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Main/Controller/SQLite entrances. Never grants RECORD_AUDIO, starts capture or calls a provider. */
class P32ProductionEntryActivityTest : WiredActivityTestCase() {
    override fun setUp() {
        super.setUp()
        val runtime = fixture.controller.voiceRuntime
        ui.waitForIdle()
        try {
            awaitControl("independent voice profiles reached a terminal load state") {
                runtime.loadState in setOf(ControllerVoiceRuntime.LoadState.LOADED, ControllerVoiceRuntime.LoadState.FAILED,
                    ControllerVoiceRuntime.LoadState.CANCELLED)
            }
        } catch (failure: Throwable) {
            throw AssertionError("voice load state=${runtime.loadState}, failureType=${runtime.loadFailureType}", failure)
        }
        assertEquals("voice load failureType=${runtime.loadFailureType}", ControllerVoiceRuntime.LoadState.LOADED, runtime.loadState)
        assertNotNull(runtime.configuration.value)
        assertNotNull(runtime.conversationConfiguration.value)
    }
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun profile() = fixture.controller.voiceRuntime.snapshot(VoiceProfile.CONVERSATION).let {
        VoiceControlProfile("${it.revision}:configured", 0, "conversation")
    }

    fun testVoiceModeSelectionThenExplicitEntryStopsAtOverlayPermission() {
        runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(dictation = "DOUBAO", asrKey = "fixture-asr"), VoiceProfile.CONVERSATION)
        }
        val conversation = fixture.database.create("default")
        fixture.database.draft(conversation.id, "keep this draft")
        mainThread { activity.select(conversation) }
        assertDraft("keep this draft")
        val before = fixture.database.exportBackup()
        withoutCallOverlayPermission { requests ->
            pickVoiceEntryMode(ChatMenuAction.VOICE_CHAT)
            assertTrue("Choosing the mode must not request overlay permission", requests.isEmpty())
            ui.onNodeWithContentDescription("语音输入").assertIsEnabled().performTouchInput { click() }
            assertEquals("Only the explicit short tap requests overlay permission", 1, requests.size)
            assertEquals("package:${instrumentation.targetContext.packageName}", requests.single().dataString)
            assertNull(field("voiceCallStarting"))
            assertNull(field("voiceCallConversation"))
            assertNull(field("voiceCallPermissionContinuation"))
            assertNull(field("universalPermissionPermit"))
            assertNull(fixture.controller.textSendState())
            assertDraft("keep this draft")
            assertEquals(before, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
        }
    }

    fun testSettingsAccountManagementIsReachableAndEntryDoesNotSynthesize() {
        runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(engine = "DOUBAO", provider = "fixture",
                endpoint = "https://example.invalid", key = "synthetic-only", model = "seed-tts-2.0", voice = "ordinary"))
        }
        openSettings()
        node("open-voice-settings").assertDoesNotExist()
        val title = SettingsCatalog.entry("voice").title

        reveal("settings-row:$title", "设置").performClick()
        node("voice-profile-switch").assertDoesNotExist()
        node("voice-profile-migrate").assertDoesNotExist()
        node("voice-duplex-start").assertDoesNotExist()
        node("voice-stop").assertDoesNotExist()
        node("voice-section-select").performClick()
        ui.onNodeWithText("我的声音").scrollToVisible().performClick()
        node("page:语音设置").scrollToDescendant(hasTestTag("voice-account-open"))
        node("voice-account-open").performClick()
        node("voice-account").assertExists()
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.textSendState())
    }

    fun testOriginalVoiceTurnProducesSavedExactOwnerReceiptWithoutAnotherSender() {
        val anchor = fixture.database.create("default")
        val draft = "leave ordinary editor unchanged"
        val text = "one original send"
        fixture.database.draft(anchor.id, draft)
        mainThread { activity.select(anchor) }
        assertDraft(draft)
        val created = CountDownLatch(1)
        var started: ChatController.VoiceCallStart? = null
        mainThread { fixture.controller.createVoiceCall(anchor.id) { started = it; created.countDown() } }
        assertTrue("Voice call owner was created", created.await(10, TimeUnit.SECONDS))
        val call = checkNotNull(started)
        assertFalse("The fixture call inherits an available model", call.needsModelSelection)
        val target = call.conversation
        assertTrue(app.daiyu.android.storage.ConversationDatabase.isTemporaryVoice(target.id.value))
        assertTrue("Voice owner differs from the ordinary editor", target.id != anchor.id)
        val session = fixture.session("answer", held = true, holdSeconds = 30)
        val rawReceipts = CopyOnWriteArrayList<VoiceControlReceipt>()
        val deliveries = CopyOnWriteArrayList<VoiceControlReceipt>()
        val stoppedOwners = CopyOnWriteArrayList<VoiceAssistantOwner>()
        try {
            val permit = VoiceControlPermit("test-permit", target.id.value, "test-lifecycle", profile(), VoiceControlLimits(), Long.MAX_VALUE)
            val turn = VoiceControlTurn(permit, 1, "test-request")
            val fence = AndroidUniversalReceiptFence(turn, { deliveries.add(it) }, { stoppedOwners.add(it) })
            val callback: (VoiceControlReceipt) -> Unit = { receipt ->
                rawReceipts.add(receipt)
                fence.accept(receipt)
            }
            // Exercise the production sender after admission, without opening the microphone or service.
            mainThread { invokeHost(activity, "submitAdmittedVoiceTurn", target, turn, text, callback) }
            awaitControl("durable voice business delivery") { deliveries.isNotEmpty() }
            val received = deliveries.single()
            assertTrue("Expected Accepted, received $received (model opens: ${fixture.opens.get()})",
                received is VoiceControlReceipt.Accepted)
            val accepted = received as VoiceControlReceipt.Accepted
            assertEquals(turn.requestId, accepted.requestId)
            assertTrue("The original fake model entered", session.entered.await(10, TimeUnit.SECONDS))
            awaitTextSendResult(activity)
            val rows = fixture.database.messages(target.id).map { it.second }
            assertEquals(2, rows.size)
            val user = rows.single { it.role == Message.USER_ROLE }
            val exact = rows.single { it.role == Message.ASSISTANT_ROLE }
            assertEquals(target.id, user.conversationId)
            assertEquals(target.id, exact.conversationId)
            assertEquals(exact.runId, user.runId)
            assertEquals(text, user.blocks.filterIsInstance<MessageBlock.Text>().single().text)
            val exactOwner = VoiceAssistantOwner(target.id.value, exact.runId.value, exact.id.value)
            assertEquals(exactOwner, accepted.assistant)
            // A state refresh and the original callback may both report the same Accepted.
            val raw = rawReceipts.toList()
            assertTrue("Original turn reported a raw receipt", raw.isNotEmpty())
            assertTrue("All raw receipts agree with the exact persisted owner",
                raw.all { it is VoiceControlReceipt.Accepted && it.assistant == exactOwner && it.requestId == turn.requestId })
            assertEquals("One fenced business delivery", 1, deliveries.size)
            assertTrue("Duplicate Accepted must not stop the original owner", stoppedOwners.isEmpty())
            assertEquals("Exactly one original model open", 1, fixture.opens.get())
            assertDraft(draft)
            assertEquals(draft, fixture.database.conversations().single { it.id == anchor.id }.draft)
            assertTrue(fixture.database.messages(anchor.id).isEmpty())
        } finally {
            session.release.countDown()
        }
    }
}
