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
                object : ResumableModelSession {
                    override val canResume = true
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        nexts++
                        throw ModelRequestException(ModelFailure.NETWORK)
                    }
                    override fun resume(sink: (ModelEvent) -> Unit) {
                        resumes++
                        assertEquals(ExecutionPhase.STREAMING, checkpoints.last().phase)
                        assertTrue(checkpoints.last().requestUnconfirmed)
                        assertEquals(1, admissions)
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
}
