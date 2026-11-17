package app.daiyu.android.voice.control

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

/** 检查语音所有者状态投影。 */
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

    @Test fun failedReasoningChoiceUsesOnlyTheClosedSafeDiagnosticSet() {
        RequestReasoningEffort.entries.forEach { effort ->
            val failed = message(RunStatus.FAILED).copy(error = effort.unsupportedMessage)
            val projected = checkNotNull(universalAssistantBody(owner, failed, false))
            assertEquals(effort, projected.unsupportedReasoning)
            assertEquals("Hello world", projected.text)
            assertNull(universalAssistantBody(owner, failed.copy(status = RunStatus.RUNNING), false)?.unsupportedReasoning)
            assertNull(universalAssistantBody(owner, failed.copy(error = "private URL/token"), false)?.unsupportedReasoning)
            assertNull(universalAssistantBody(owner, failed.copy(id = MessageId("other")), false))
        }
    }


    @Test fun responseFailuresKeepOnlyClosedModelDiagnostics() {
        ModelFailure.entries.forEach { failure ->
            val failed = message(RunStatus.FAILED).copy(error = failure.userMessage)
            assertEquals(failure.userMessage, universalAssistantBody(owner, failed, false)?.responseFailure)
            assertNull(universalAssistantBody(owner, failed.copy(status = RunStatus.RUNNING), false)?.responseFailure)
        }
        val detailed = ModelRequestException(ModelFailure.REQUEST, ModelRequestDiagnostic(
            httpStatus = 400, rejection = RequestRejection.UNSUPPORTED_PARAMETER, field = RequestField.TOOLS)).userMessage
        assertEquals(detailed, universalAssistantBody(owner, message(RunStatus.FAILED).copy(error = detailed), false)?.responseFailure)
        for (privateError in listOf("https://private.invalid?token=secret", ModelFailure.NETWORK.userMessage + " token=secret",
            ModelFailure.REQUEST.userMessage + "，涉及字段：private_key", ModelFailure.REQUEST.userMessage + "（HTTP 999）")) {
            assertNull(universalAssistantBody(owner, message(RunStatus.FAILED).copy(error = privateError), false)?.responseFailure)
        }
    }

    @Test fun unknownBrowserResultAndCancellationHaveDistinctSafeReasons() {
        val source = message(RunStatus.FAILED).copy(error = "private error must not escape", blocks = listOf(
            MessageBlock.Text("text", "已有字幕"), MessageBlock.Tool(ToolInvocation(ToolCallId("browser"),
                "browser_write", "private arguments"), ModelHistory.UNKNOWN_TOOL_RESULT)))
        val projected = checkNotNull(universalAssistantBody(owner, source, false))
        assertEquals("已有字幕", projected.text)
        assertEquals("浏览器执行结果未确认；本轮已停止，未自动重试", projected.responseFailure)
        assertFalse(projected.browsing)
        assertEquals("回答已中止，可以继续说",
            universalAssistantBody(owner, source.copy(status = RunStatus.STOPPED), false)?.responseFailure)
        assertNull(universalAssistantBody(owner, source.copy(id = MessageId("other")), false))
    }

    @Test fun browserAndNativeActivityMergeWithoutInventingBrowserSuccess() {
        val invocation = ToolInvocation(ToolCallId("browser"), "browser_write", "{\"action\":\"new_tab\"}")
        val pending = message().copy(blocks = listOf(MessageBlock.Tool(invocation)))
        val body = checkNotNull(universalAssistantBody(owner, pending, false))
        assertTrue(body.browsing); assertFalse(body.searching); assertNull(body.searchCompletion)
        val native = body.withNativeSearch(VoiceNativeSearchState(true))
        assertTrue(native.browsing); assertTrue(native.searching)
        val completedNative = body.withNativeSearch(VoiceNativeSearchState(false, "native-receipt"))
        assertTrue(completedNative.browsing); assertEquals("native-receipt", completedNative.searchCompletion)
        val result = checkNotNull(universalAssistantBody(owner, pending.copy(blocks = listOf(
            MessageBlock.Tool(invocation, """{"text":"confirmed","filePath":null,"browser_control":{"revision":1}}"""))), false)).withNativeSearch(VoiceNativeSearchState())
        assertFalse(result.browsing); assertEquals("browser:run:browser", result.searchCompletion)
        val failed = checkNotNull(universalAssistantBody(owner, pending.copy(status = RunStatus.FAILED), false))
            .withNativeSearch(VoiceNativeSearchState(true, "late"))
        assertFalse(failed.browsing); assertFalse(failed.searching); assertNull(failed.searchCompletion)
    }

    @Test fun browserCompletionRequiresLatestPositiveReceiptAndNoPendingTool() {
        val invocation = ToolInvocation(ToolCallId("browser"), "browser_read", "{}")
        val confirmed = """{"text":"readable","browser_control":{"revision":1}}"""
        for (result in listOf(null, "confirmed", ModelHistory.UNKNOWN_TOOL_RESULT,
            """{"ok":false,"executed":false,"error":"cancelled"}""")) {
            val source = message().copy(blocks = listOf(MessageBlock.Tool(invocation, result)))
            assertNull(universalAssistantBody(owner, source, false)?.searchCompletion)
        }
        val done = message().copy(blocks = listOf(MessageBlock.Tool(invocation, confirmed)))
        assertEquals("browser:run:browser", universalAssistantBody(owner, done, false)?.searchCompletion)
        assertEquals("browser:run:browser", universalAssistantBody(owner, done.copy(status = RunStatus.COMPLETED), false)?.searchCompletion)
        assertNull(universalAssistantBody(owner, done.copy(status = RunStatus.STOPPED), false)?.searchCompletion)
        val recovered = done.copy(blocks = listOf(MessageBlock.Tool(invocation.copy(id = ToolCallId("failed")),
            """{"ok":false,"error":"rejected"}"""), MessageBlock.Text("retry", "重新查询。")) + done.blocks)
        assertEquals("browser:run:browser", universalAssistantBody(owner, recovered, false)?.searchCompletion)
        assertNull(universalAssistantBody(owner, done.copy(blocks = listOf(
            MessageBlock.Tool(invocation.copy(id = ToolCallId("earlier-pending")))) + done.blocks), false)?.searchCompletion)
        val adjacentRecovery = recovered.copy(blocks = recovered.blocks.filterNot { it is MessageBlock.Text })
        assertEquals("browser:run:browser", universalAssistantBody(owner, adjacentRecovery, false)?.searchCompletion)
        assertNull(universalAssistantBody(owner, done.copy(blocks = done.blocks +
            MessageBlock.Tool(invocation.copy(id = ToolCallId("pending")))), false)?.searchCompletion)
    }

    @Test fun mergedReceiptsSurviveBusySnapshotsWithoutReplayingTheOtherSource() {
        val activity = VoiceCallSearchActivity()
        val idle = VoiceAssistantBody(owner, "", VoiceBodyStatus.STREAMING)
        val browser = idle.copy(searchCompletion = "browser:run:one")
        assertNull(activity.project(idle.copy(browsing = true), VoiceNativeSearchState()).searchCompletion)
        assertEquals("browser:run:one", activity.project(browser, VoiceNativeSearchState()).searchCompletion)
        assertEquals("browser:run:one", activity.project(browser, VoiceNativeSearchState(true)).searchCompletion)
        assertEquals("native:one", activity.project(browser, VoiceNativeSearchState(false, "one")).searchCompletion)
        assertEquals("native:one", activity.project(browser, VoiceNativeSearchState()).searchCompletion)
        assertEquals("native:one", activity.project(idle.copy(browsing = true), VoiceNativeSearchState()).searchCompletion)
        assertEquals("browser:run:two", activity.project(browser.copy(searchCompletion = "browser:run:two"),
            VoiceNativeSearchState()).searchCompletion)
        assertNull(activity.project(idle.copy(status = VoiceBodyStatus.CANCELLED), VoiceNativeSearchState()).searchCompletion)
    }

}
