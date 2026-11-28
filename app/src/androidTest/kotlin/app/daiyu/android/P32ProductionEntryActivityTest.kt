package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.ui.screens.settings.SettingsCatalog
import app.daiyu.android.voice.control.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList

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
    private fun call(name: String, vararg args: Any?) = MainActivity::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
        .invoke(activity, *args)
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

    fun testOriginalSendDraftProducesSavedExactOwnerReceiptWithoutAnotherSender() {
        val conversation = fixture.database.create("default")
        val session = fixture.session("answer", held = true)
        val rawReceipts = CopyOnWriteArrayList<VoiceControlReceipt>()
        val deliveries = CopyOnWriteArrayList<VoiceControlReceipt>()
        val stoppedOwners = CopyOnWriteArrayList<VoiceAssistantOwner>()
        try {
            mainThread {
                activity.select(conversation)
                retainedControls(activity).onTextChange("one original send")
                val expected = ChatController.TextSendOwner(conversation.id, field("editorGeneration") as Long,
                    field("draftRevision") as Long)
                val permit = VoiceControlPermit("test-permit", conversation.id.value, "test-lifecycle", profile(), VoiceControlLimits(), Long.MAX_VALUE)
                val turn = VoiceControlTurn(permit, 1, "test-request")
                val type = MainActivity::class.java.declaredClasses.single { it.simpleName == "UniversalSend" }
                val constructor = type.declaredConstructors.single().apply { isAccessible = true }
                val fence = AndroidUniversalReceiptFence(turn, { deliveries.add(it) }, { stoppedOwners.add(it) })
                val callback: (VoiceControlReceipt) -> Unit = {
                    rawReceipts.add(it)
                    fence.accept(it)
                }
                val send = constructor.newInstance(turn, expected, callback)
                call("sendDraft", "one original send", send)
            }
            awaitTextSendResult(activity)
            awaitControl("durable voice business delivery") { deliveries.isNotEmpty() }
            awaitControl("one original fake model open") { fixture.opens.get() == 1 }
            ui.waitForIdle()
            val accepted = deliveries.single() as VoiceControlReceipt.Accepted
            val rows = fixture.database.messages(conversation.id).map { it.second }
            assertEquals(2, rows.size)
            assertEquals(1, rows.count { it.role == Message.USER_ROLE })
            val exact = rows.single { it.role == Message.ASSISTANT_ROLE }
            val exactOwner = VoiceAssistantOwner(conversation.id.value, exact.runId.value, exact.id.value)
            assertEquals(exactOwner, accepted.assistant)
            // Retained-state refresh and the original callback may both report durable Accepted.
            // Only the fenced business delivery is single; do not suppress either production path.
            val raw = rawReceipts.toList()
            assertTrue("original send must report a raw receipt", raw.isNotEmpty())
            assertTrue("all raw receipts must be Accepted for the same exact persisted owner",
                raw.all { it is VoiceControlReceipt.Accepted && it.assistant == exactOwner &&
                    it.requestId == accepted.requestId })
            assertTrue("duplicate Accepted must not stop the original owner", stoppedOwners.isEmpty())
            assertEquals(1, fixture.opens.get())
            assertDraft("")
        } finally {
            session.release.countDown()
        }
    }
}
