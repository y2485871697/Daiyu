package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class ModelRequestDiagnosticTest {
    @Test fun runnerPersistsActionableSafeFailureInsteadOfDiscardingIt() {
        val failure = ModelRequestException(ModelFailure.REQUEST,
            ModelRequestDiagnostic(422, RequestRejection.UNSUPPORTED_PARAMETER, RequestField.TOOLS))
        var opened = 0
        var executed = 0
        val saved = mutableListOf<Message>()
        val port = ModelPort { opened++; object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { throw failure }
            override fun cancel() {}
        } }
        val initial = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
        val result = ChatRunner(port, object : ConversationStore {
            override fun save(message: Message) { saved.add(message) }
        }, BatteryTool { executed++; "{}" }, {}).run(initial, emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(failure.userMessage, result.error)
        assertTrue(result.error!!.contains("HTTP 422"))
        assertTrue(result.error!!.contains("tools"))
        assertEquals(1, opened); assertEquals(0, executed)
        assertEquals(1, saved.count { it.status == RunStatus.FAILED })
    }

    @Test fun oldFailureMessagesRemainStableAndNeverInventHttpStatus() {
        ModelFailure.entries.forEach { failure -> assertEquals(failure.userMessage, ModelRequestException(failure).userMessage) }
        assertFalse(ModelRequestException(ModelFailure.REQUEST).userMessage.contains("HTTP"))
        for ((failure, status) in listOf(ModelFailure.AUTHENTICATION to 401, ModelFailure.PERMISSION to 403, ModelFailure.RATE_LIMIT to 429)) {
            val message = ModelRequestException(failure, ModelRequestDiagnostic(status)).userMessage
            assertEquals(failure.userMessage, message)
        }
    }
}
