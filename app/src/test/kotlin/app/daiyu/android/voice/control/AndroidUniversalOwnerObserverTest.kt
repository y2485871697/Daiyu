package app.daiyu.android.voice.control

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Pure projection tests. Subscription/DB timing still requires parent integration/device tests. */
class AndroidUniversalOwnerObserverTest {
    private val owner = VoiceAssistantOwner("chat", "run", "message")
    private fun message(status: RunStatus = RunStatus.RUNNING) = Message(
        MessageId(owner.messageId), ConversationId(owner.conversationId), RunId(owner.runId),
        Message.ASSISTANT_ROLE,
        listOf(MessageBlock.Text("body-a", "Hello "),
            MessageBlock.Tool(ToolInvocation(ToolCallId("tool"), "tool_name", "secret tool args"), "tool result"),
            MessageBlock.Text("body-b", "world")), status,
        protocolItems = listOf("reasoning protocol must never be spoken"),
        historySegments = listOf(MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE,
            listOf(MessageBlock.Text("supplement-body", "user supplement must not be spoken")))))

    @Test fun onlyExactAssistantBodyTextIsProjected() {
        assertEquals(VoiceAssistantBody(owner, "Hello world", VoiceBodyStatus.STREAMING),
            universalAssistantBody(owner, message(), false))
    }
    @Test fun eachIdentityFieldAndRoleMustMatch() {
        val source = message()
        listOf(source.copy(id = MessageId("other")), source.copy(runId = RunId("other")),
            source.copy(conversationId = ConversationId("other")), source.copy(role = Message.USER_ROLE))
            .forEach { assertNull(universalAssistantBody(owner, it, false)) }
    }
    @Test fun completedRunDoesNotMeanQuestionWasAnswered() {
        assertEquals(VoiceBodyStatus.QUESTION_WAITING,
            universalAssistantBody(owner, message(RunStatus.COMPLETED), true)?.status)
        assertEquals(VoiceBodyStatus.STREAMING,
            universalAssistantBody(owner, message(RunStatus.RUNNING), false)?.status)
    }
    @Test fun everyTerminalStatusIsExplicit() {
        val statuses = mapOf(RunStatus.COMPLETED to VoiceBodyStatus.COMPLETE,
            RunStatus.FAILED to VoiceBodyStatus.FAILED, RunStatus.STOPPED to VoiceBodyStatus.CANCELLED,
            RunStatus.INTERRUPTED to VoiceBodyStatus.CANCELLED)
        statuses.forEach { (run, expected) -> assertEquals(expected, universalAssistantBody(owner, message(run), false)?.status) }
    }
}
