package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test

class ChatRunnerResumeCheckpointTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    private inner class Fixture(val mode: String) {
        val checkpoints = mutableListOf<RunCheckpoint>()
        var reconnecting = false
        var admissions = 0
        var opens = 0
        var nexts = 0
        var resumes = 0
        val runner: ChatRunner
        val runtime = object : RuntimeStore {
            override fun save(checkpoint: RunCheckpoint) {
                if (checkpoint.phase == ExecutionPhase.RECONNECTING) reconnecting = true
                if (reconnecting && checkpoint.phase == ExecutionPhase.STREAMING) {
                    reconnecting = false
                    admissions++
                    check(checkpoint.requestUnconfirmed)
                    if (mode == "failure") throw IllegalStateException("resume checkpoint unavailable")
                    if (mode == "stop") runner.stop()
                }
                checkpoints.add(checkpoint)
            }
        }
        init {
            val port = ModelPort {
                opens++
                assertEquals(RequestOperation.OPEN, checkpoints.last().request!!.operation)
                assertTrue(checkpoints.last().requestUnconfirmed)
                assertEquals(checkpoints.last().owner, checkpoints.last().request!!.owner)
                object : ResumableModelSession {
                    override val canResume = true
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        nexts++
                        assertEquals(RequestOperation.NEXT, checkpoints.last().request!!.operation)
                        assertEquals(checkpoints.last().owner, checkpoints.last().request!!.owner)
                        throw ModelRequestException(ModelFailure.NETWORK)
                    }
                    override fun resume(sink: (ModelEvent) -> Unit) {
                        resumes++
                        assertEquals(ExecutionPhase.STREAMING, checkpoints.last().phase)
                        assertTrue(checkpoints.last().requestUnconfirmed)
                        assertEquals(1, admissions)
                        val resume = checkpoints.last().request!!
                        val next = checkpoints.last { it.request?.operation == RequestOperation.NEXT }.request!!
                        assertEquals(RequestOperation.RESUME, resume.operation)
                        assertEquals(next.requestId, resume.requestId)
                        assertNotEquals(next.attemptId, resume.attemptId)
                        assertEquals(next.owner, resume.owner)
                    }
                    override fun cancel() {}
                }
            }
            runner = ChatRunner(port, object : ConversationStore {
                override fun save(message: Message) {}
            }, BatteryTool { error("unexpected tool") }, RunnerOptions(
                reconnect = ReconnectPolicy(1), runtimeStore = runtime)) {}
        }
        fun run() = runner.run(initial(), emptyList())
        fun assertNoRepost() { assertEquals(1, opens); assertEquals(1, nexts) }
    }

    @Test fun streamingCheckpointCommitsBeforeActualResume() {
        val f = Fixture("success")
        assertEquals(RunStatus.COMPLETED, f.run().status)
        f.assertNoRepost(); assertEquals(1, f.resumes); assertEquals(1, f.admissions)
        assertEquals(1, f.checkpoints.map { it.owner }.distinct().size)
        assertEquals((1L..f.checkpoints.size.toLong()).toList(), f.checkpoints.map { it.revision })
        assertFalse(f.checkpoints.last().requestUnconfirmed)
    }

    @Test fun failedStreamingCheckpointPreventsResumeAndRepost() {
        val f = Fixture("failure")
        assertEquals(RunStatus.FAILED, f.run().status)
        f.assertNoRepost(); assertEquals(0, f.resumes); assertEquals(1, f.admissions)
        assertTrue(f.checkpoints.last().requestUnconfirmed)
    }

    @Test fun stopDuringStreamingCheckpointPreventsResumeWithoutWaiting() {
        val f = Fixture("stop")
        assertEquals(RunStatus.STOPPED, f.run().status)
        f.assertNoRepost(); assertEquals(0, f.resumes); assertEquals(1, f.admissions)
        assertTrue(f.checkpoints.last().requestUnconfirmed)
    }
    @Test fun failedOpenOrNextCheckpointPreventsThatTransportCall() {
        for (operation in listOf(RequestOperation.OPEN, RequestOperation.NEXT)) admission(operation, false)
    }
    @Test fun stopDuringOpenOrNextCheckpointPreventsThatTransportCall() {
        for (operation in listOf(RequestOperation.OPEN, RequestOperation.NEXT)) admission(operation, true)
    }
    private fun admission(operation: RequestOperation, stop: Boolean) {
        lateinit var runner: ChatRunner
        var opens = 0; var nexts = 0
        val runtime = object : RuntimeStore {
            override fun save(checkpoint: RunCheckpoint) {
                if (checkpoint.phase != ExecutionPhase.TERMINAL && checkpoint.request?.operation == operation) {
                    assertTrue(checkpoint.requestUnconfirmed)
                    assertEquals(checkpoint.owner, checkpoint.request.owner)
                    if (stop) runner.stop() else error("admission persistence unavailable")
                }
            }
        }
        val port = ModelPort {
            opens++
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { nexts++ }
                override fun cancel() {}
            }
        }
        runner = ChatRunner(port, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { error("unexpected tool") }, RunnerOptions(runtimeStore = runtime)) {}
        assertEquals(if (stop) RunStatus.STOPPED else RunStatus.FAILED, runner.run(initial(), emptyList()).status)
        assertEquals(if (operation == RequestOperation.OPEN) 0 else 1, opens)
        assertEquals(0, nexts)
    }

}
