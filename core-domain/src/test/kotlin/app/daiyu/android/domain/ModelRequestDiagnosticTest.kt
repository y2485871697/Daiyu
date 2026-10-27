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
    @Test fun unsupportedReasoningUsesTheExactClosedChineseLevelWithoutStatusSuffix() {
        val labels = listOf("关闭", "最小", "低", "中", "高", "超高", "极高")
        for ((effort, label) in RequestReasoningEffort.entries.zip(labels)) {
            for (http in listOf(null, 400, 422)) {
                val detail = ModelRequestDiagnostic(http, RequestRejection.UNSUPPORTED_VALUE,
                    RequestField.REASONING, effort)
                val failure = ModelRequestException(if (http == null) ModelFailure.CONFIGURATION else ModelFailure.REQUEST, detail)
                assertEquals("模型不支持${label}思考", failure.userMessage)
                assertEquals(http, failure.diagnostic!!.httpStatus)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelRequestDiagnostic(400, RequestRejection.UNSUPPORTED_PARAMETER, RequestField.REASONING, RequestReasoningEffort.HIGH)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelRequestDiagnostic(400, RequestRejection.UNSUPPORTED_VALUE, RequestField.TEMPERATURE, RequestReasoningEffort.HIGH)
        }
    }

    @Test fun runnerKeepsTheExactReasoningFailureInsteadOfGenericRequestError() {
        val rows = mutableListOf<Message>()
        var requests = 0
        val detail = ModelRequestDiagnostic(400, RequestRejection.UNSUPPORTED_VALUE,
            RequestField.REASONING, RequestReasoningEffort.XHIGH)
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                requests++; throw ModelRequestException(ModelFailure.REQUEST, detail)
            }
            override fun cancel() {}
        } }
        val initial = Message(MessageId("exact"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
        val result = ChatRunner(model, object : ConversationStore {
            override fun save(message: Message) { rows += message }
        }, BatteryTool { fail("no tool"); "" }, {}).run(initial, emptyList())
        assertEquals("模型不支持超高思考", result.error)
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(1, requests)
    }

}
