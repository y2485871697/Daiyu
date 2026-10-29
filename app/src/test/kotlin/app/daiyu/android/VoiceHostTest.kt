package app.daiyu.android

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi
import app.daiyu.android.voice.SpeechError
import app.daiyu.android.voice.SpeechPhase
import app.daiyu.android.voice.SpeechPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Host-policy tests: no AndroidSpeechPlayer construction, service discovery, audio or network. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceHostTest {
    private class FakePlayer : VoiceHostPlayer {
        val mutableState = MutableStateFlow(SpeechPlaybackState())
        override val state: StateFlow<SpeechPlaybackState> = mutableState
        val plays = mutableListOf<Triple<String, String, String>>()
        val stops = mutableListOf<String>()
        val binds = mutableListOf<String>()
        var closeCount = 0
        var owner = ""
        var failure: SpeechError? = null
        override fun bindOwner(owner: String) {
            this.owner = owner
            binds += owner
            mutableState.value = SpeechPlaybackState(owner = owner)
        }
        override fun play(owner: String, requestId: String, text: String): Boolean {
            check(owner == this.owner)
            plays += Triple(owner, requestId, text)
            mutableState.value = SpeechPlaybackState(owner, requestId,
                if (failure == null) SpeechPhase.INITIALIZING else SpeechPhase.UNAVAILABLE, failure)
            return true
        }
        override fun stop(owner: String): Boolean {
            check(owner == this.owner)
            stops += owner
            mutableState.value = mutableState.value.copy(phase = SpeechPhase.STOPPED, error = null)
            return true
        }
        override fun close() { closeCount++ }
        override fun closeStrict() { stops += owner; close() }
        fun emit(phase: SpeechPhase, owner: String = this.owner, requestId: String? = state.value.requestId) {
            mutableState.value = SpeechPlaybackState(owner, requestId, phase)
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val player = FakePlayer()
        var creations = 0
        val host = VoiceHost(scope) { creations++; player }
        fun enter(id: String = "A") {
            host.enter(id)
            host.setForeground(true)
        }
    }

    @Test fun renderNavigationAndResumeNeverCreateOrAutoplay() = runTest {
        val f = Fixture(backgroundScope)
        assertEquals(0, f.creations)
        f.enter()
        f.host.syncConversation("A")
        f.host.enter("B")
        f.host.setVisible(false)
        f.host.setVisible(true)
        f.host.setForeground(false)
        f.host.setForeground(true)
        assertEquals(0, f.creations)
        assertTrue(f.player.plays.isEmpty())
        f.host.close()
        assertEquals(0, f.player.closeCount)
    }

    @Test fun onlyExplicitCurrentForegroundClickCreatesAndPlays() = runTest {
        val f = Fixture(backgroundScope)
        f.host.enter("A")
        assertFalse(f.host.speak(f.host.owner, "m", "answer"))
        f.host.setForeground(true)
        assertTrue(f.host.speak(f.host.owner, "m", "answer"))
        assertEquals(1, f.creations)
        val engineRequest = f.player.plays.single()
        assertNotEquals(f.host.owner, engineRequest.first)
        assertNotEquals("m", engineRequest.second)
        assertEquals("answer", engineRequest.third)
        assertEquals(SpeechPhase.INITIALIZING, f.host.state.value.phase)
        assertTrue(voiceHostIsActive(f.host.state.value))
        f.player.emit(SpeechPhase.SPEAKING)
        runCurrent()
        assertEquals(SpeechPhase.SPEAKING, f.host.state.value.phase)
        assertTrue(f.host.stop(f.host.owner, "m"))
        assertEquals(SpeechPhase.STOPPED, f.host.state.value.phase)
        assertFalse(voiceHostIsActive(f.host.state.value))
        f.host.close()
    }

    @Test fun reentryFencesOldPlayStopAndLateStatesEvenForSameConversation() = runTest {
        val f = Fixture(backgroundScope)
        f.enter()
        val oldOwner = f.host.owner
        f.host.speak(oldOwner, "same-message", "A answer")
        f.host.enter("B")
        f.host.enter("A")
        val newOwner = f.host.owner
        assertNotEquals(oldOwner, newOwner)
        assertFalse(f.host.speak(oldOwner, "same-message", "old answer"))
        assertFalse(f.host.stop(oldOwner))
        f.host.speak(newOwner, "same-message", "new answer")
        f.player.emit(SpeechPhase.COMPLETED, oldOwner, "same-message")
        runCurrent()
        assertEquals(newOwner, f.host.state.value.owner)
        assertEquals(SpeechPhase.INITIALIZING, f.host.state.value.phase)
        assertEquals(listOf("A answer", "new answer"), f.player.plays.map { it.third })
        f.host.close()
    }

    @Test fun backgroundAndHiddenChatStopAndNeverResumePendingSpeech() = runTest {
        val f = Fixture(backgroundScope)
        f.enter()
        val beforePause = f.host.owner
        f.host.speak(beforePause, "m", "answer")
        f.host.setForeground(false)
        assertEquals(1, f.player.stops.size)
        assertFalse(f.host.speak(beforePause, "m", "background"))
        f.host.setForeground(true)
        assertNotEquals(beforePause, f.host.owner)
        assertFalse(f.host.speak(beforePause, "m", "stale click"))
        assertEquals(1, f.player.plays.size)
        assertEquals(SpeechPhase.IDLE, f.host.state.value.phase)
        val beforeHide = f.host.owner
        f.host.speak(beforeHide, "m", "second click")
        f.host.setVisible(false)
        assertEquals(2, f.player.stops.size)
        assertFalse(f.host.speak(beforeHide, "m", "hidden"))
        f.host.setVisible(true)
        assertNotEquals(beforeHide, f.host.owner)
        assertEquals(2, f.player.plays.size)
        f.host.close()
    }

    @Test fun stopButtonForReplacedMessageCannotStopCurrentMessage() = runTest {
        val f = Fixture(backgroundScope)
        f.enter()
        f.host.speak(f.host.owner, "first", "one")
        f.host.speak(f.host.owner, "second", "two")
        assertFalse(f.host.stop(f.host.owner, "first"))
        assertEquals(1, f.player.stops.size) // Replacement already strictly disposed the first player.
        f.player.emit(SpeechPhase.COMPLETED, requestId = "first")
        runCurrent()
        assertEquals("second", f.host.state.value.requestId)
        assertEquals(SpeechPhase.INITIALIZING, f.host.state.value.phase)
        f.host.close()
    }

    @Test fun errorsStayVisibleAndOnlyNextClickRetries() = runTest {
        val f = Fixture(backgroundScope)
        f.enter()
        f.player.failure = SpeechError.OFFLINE_VOICE_UNAVAILABLE
        assertTrue(f.host.speak(f.host.owner, "m", "answer"))
        assertEquals(SpeechPhase.UNAVAILABLE, f.host.state.value.phase)
        assertEquals(R.string.voice_host_offline_voice_unavailable, voiceHostStatusResource(f.host.state.value))
        assertFalse(voiceHostIsActive(f.host.state.value))
        f.host.syncConversation("A")
        assertEquals(1, f.player.plays.size)
        f.player.failure = null
        f.host.speak(f.host.owner, "m", "answer")
        assertEquals(2, f.player.plays.size)
        assertEquals(2, f.creations) // A terminal request cannot retain a ready Android engine.
        f.host.close()
    }

    @Test fun blankAnswerDoesNotInitializeOrFallBackToTools() = runTest {
        val f = Fixture(backgroundScope)
        f.enter()
        assertFalse(f.host.speak(f.host.owner, "tool-only", "  "))
        assertEquals(0, f.creations)
        assertEquals(SpeechError.EMPTY_TEXT, f.host.state.value.error)
        f.host.speak(f.host.owner, "m", "answer")
        assertFalse(f.host.speak(f.host.owner, "m", ""))
        runCurrent()
        assertEquals(SpeechError.EMPTY_TEXT, f.host.state.value.error)
        assertEquals(1, f.player.plays.size)
        assertEquals(1, f.player.stops.size)
        f.host.close()
    }

    @Test fun closeIsTerminalAndDisposesExactlyOnce() = runTest {
        val f = Fixture(backgroundScope)
        f.enter()
        val owner = f.host.owner
        f.host.speak(owner, "m", "answer")
        f.host.close()
        f.host.close()
        f.player.emit(SpeechPhase.SPEAKING)
        runCurrent()
        f.host.enter("B")
        f.host.setForeground(true)
        assertFalse(f.host.speak(owner, "m", "after close"))
        assertFalse(f.host.stop(owner))
        assertEquals(1, f.player.closeCount)
        assertEquals(SpeechPhase.CLOSED, f.host.state.value.phase)
    }

    @Test fun textUsesOnlyCurrentAssistantFinalAnswerNotToolParametersResultsOrUser() {
        val tool = MessageBlock.Tool(
            ToolInvocation(ToolCallId("tool"), "search", "SECRET TOOL PARAMETERS"), "SECRET TOOL RESULT")
        val assistant = Message(MessageId("assistant"), ConversationId("A"), RunId("run"),
            Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("work", "work narration"), tool,
                MessageBlock.Text("answer", "visible answer")), RunStatus.COMPLETED)
        val turns = ChatTurnMapper.map(listOf(assistant)) + ChatTurnUi.User("user", "user text")
        assertEquals("visible answer", voiceHostText(turns, "assistant"))
        assertNull(voiceHostText(turns, "user"))
        assertNull(voiceHostText(turns, "missing"))
        val updated = turns.map { if (it is ChatTurnUi.Assistant) it.copy(finalText = "latest answer") else it }
        assertEquals("latest answer", voiceHostText(updated, "assistant"))
        val grouped = updated.map { if (it is ChatTurnUi.Assistant) it.copy(
            sourceListKeys = listOf(it.listKey, "exact-tail"), sourceSegmentIndices = listOf(0, 1)) else it }
        assertEquals("latest answer", voiceHostText(grouped, "exact-tail"))
        assertNull(voiceHostText(grouped, "unrelated-tail"))
        val onlyTool = ChatTurnMapper.map(listOf(assistant.copy(blocks = listOf(tool))))
        assertEquals("", voiceHostText(onlyTool, "assistant"))
    }

    @Test fun allErrorEnumsHaveSanitizedUiAndActivePhasesHaveStop() {
        val errors = SpeechError.entries.map { error ->
            voiceHostStatusResource(SpeechPlaybackState(phase = SpeechPhase.ERROR, error = error))
        }
        assertTrue(errors.all { it != null })
        assertEquals(SpeechError.entries.size, errors.distinct().size)
        SpeechPhase.entries.forEach { phase ->
            assertEquals(phase in setOf(SpeechPhase.INITIALIZING, SpeechPhase.QUEUED, SpeechPhase.SPEAKING),
                voiceHostIsActive(SpeechPlaybackState(phase = phase)))
        }
    }
}
