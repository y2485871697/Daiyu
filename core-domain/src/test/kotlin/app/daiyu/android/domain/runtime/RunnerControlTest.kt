package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RunnerControlTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private fun user(id: String) = initial().copy(id = MessageId(id), role = "user", blocks = listOf(MessageBlock.Text(id, id)), status = RunStatus.COMPLETED)
    private val call = ToolInvocation(ToolCallId("call"), "read", "{}")
    private fun session(events: (List<ToolOutput>, (ModelEvent) -> Unit) -> Unit) = object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = events(outputs, sink)
        override fun cancel() {}
    }
    private fun store(writes: MutableList<Message> = mutableListOf()) = object : ConversationStore {
        override fun save(message: Message) { writes.add(message) }
    }

    private class PauseStore(private val failBeforeCommit: Boolean = false,
        private val loseReceipt: Boolean = false) : AtomicRuntimeStore {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = java.util.concurrent.CopyOnWriteArrayList<RunCheckpoint>()
        @Volatile var latest: RunCheckpoint? = null
        var recoveries = 0
        override fun commitCheckpoint(checkpoint: RunCheckpoint) {
            attempts.add(checkpoint)
            if (checkpoint.phase == ExecutionPhase.PAUSED) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                if (failBeforeCommit) error("write failed")
                latest = checkpoint
                if (loseReceipt) error("receipt lost")
            } else latest = checkpoint
        }
        override fun readCommittedCheckpoint(checkpoint: RunCheckpoint) = latest
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint {
            recoveries++
            error("live owner must not recover")
        }
    }

    @Test fun pausedCommitPrecedesPublicationAndObserverExceptionCannotFailRun() {
        for (throwObserver in listOf(false, true)) {
            val runtime = PauseStore()
            val published = java.util.concurrent.CopyOnWriteArrayList<Message>()
            val pausedPublished = CountDownLatch(1)
            val opens = AtomicInteger()
            lateinit var runner: ChatRunner
            runner = ChatRunner(ModelPort { opens.incrementAndGet(); session { _, _ -> } },
                object : ConversationStore { override fun save(message: Message) = error("atomic writer only") },
                BatteryTool { error("unused") }, RunnerOptions(runtimeStore = runtime)) { message ->
                published.add(message)
                if (message.status == RunStatus.RUNNING) {
                    assertEquals(ExecutionPhase.PAUSED, runtime.latest!!.phase)
                    assertEquals(message, runtime.latest!!.message)
                    assertEquals(ExecutionPhase.PAUSED, runner.state().phase)
                    assertTrue(runner.state().pauseRequested)
                    pausedPublished.countDown()
                    if (throwObserver) error("observer unavailable")
                }
            }
            assertTrue(runner.pause())
            val task = FutureTask { runner.run(initial(), emptyList()) }
            val worker = Thread(task).apply { isDaemon = true; start() }
            try {
                assertTrue(runtime.entered.await(5, TimeUnit.SECONDS))
                assertEquals(ExecutionPhase.PAUSED, runner.state().phase)
                assertNull(runtime.latest)
                assertTrue(published.isEmpty())
                assertEquals(0, opens.get())
                runtime.release.countDown()
                assertTrue(pausedPublished.await(5, TimeUnit.SECONDS))
                assertFalse(task.isDone)
                assertTrue(runner.resume())
                val result = task.get(5, TimeUnit.SECONDS)
                assertEquals(RunStatus.COMPLETED, result.status)
                assertEquals(initial().id, result.id)
                assertEquals(initial().runId, result.runId)
                assertEquals(1, opens.get())
                assertEquals(listOf(result), published.filter { it.status != RunStatus.RUNNING })
                assertEquals(result, runtime.latest!!.message)
                assertEquals(0, runtime.recoveries)
            } finally {
                runtime.release.countDown(); runner.stop(); worker.join(5_000)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun failedOrUnconfirmedPausedCommitNeverPublishesSuccessfulPause() {
        for (lostReceipt in listOf(false, true)) {
            val runtime = PauseStore(failBeforeCommit = !lostReceipt, loseReceipt = lostReceipt)
            val published = java.util.concurrent.CopyOnWriteArrayList<Message>()
            val opens = AtomicInteger()
            val runner = ChatRunner(ModelPort { opens.incrementAndGet(); error("must not open") },
                object : ConversationStore { override fun save(message: Message) = error("atomic writer only") },
                BatteryTool { error("unused") }, RunnerOptions(runtimeStore = runtime), published::add)
            assertTrue(runner.pause())
            val task = FutureTask { runner.run(initial(), emptyList()) }
            val worker = Thread(task).apply { isDaemon = true; start() }
            try {
                assertTrue(runtime.entered.await(5, TimeUnit.SECONDS))
                assertTrue(published.isEmpty())
                runtime.release.countDown()
                val result = task.get(5, TimeUnit.SECONDS)
                assertEquals(RunStatus.FAILED, result.status)
                assertTrue(result.error!!.startsWith("保存失败"))
                assertTrue(published.none { it.status == RunStatus.RUNNING })
                assertEquals(0, opens.get())
                assertEquals(1, runtime.attempts.size)
                assertEquals(0, runtime.recoveries)
                assertEquals(if (lostReceipt) ExecutionPhase.PAUSED else null, runtime.latest?.phase)
            } finally {
                runtime.release.countDown(); runner.stop(); worker.join(5_000)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun sameItemAcrossToolKeepsEventOrderAndFinalCorrection() {
        var rounds = 0
        val runner = ChatRunner(ModelPort { session { _, sink ->
            if (rounds++ == 0) {
                sink(ModelEvent.TextDelta("same", "before")); sink(ModelEvent.ToolReady(call))
                sink(ModelEvent.TextDelta("same", "draft")); sink(ModelEvent.TextFinal("same", "after"))
                sink(ModelEvent.TextFinal("same", "after"))
            }
        } }, store(), BatteryTool { "result" }, {})
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf("before", "after"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
        assertTrue(result.blocks[1] is MessageBlock.Tool)
    }

    @Test fun publishFailureCannotDowngradeCommittedTerminal() {
        val writes = mutableListOf<Message>()
        val runner = ChatRunner(ModelPort { session { _, sink -> sink(ModelEvent.TextDelta("t", "accepted")) } },
            store(writes), BatteryTool { error("unused") }, { throw IllegalStateException("UI unavailable") })
        assertEquals(RunStatus.COMPLETED, runner.run(initial(), emptyList()).status)
        assertEquals(listOf(RunStatus.COMPLETED), writes.filter { it.status != RunStatus.RUNNING }.map { it.status })
    }

    @Test fun pauseQueuesUntilExplicitResumeAndIsolatesOldDeltaAndUsage() {
        val paused = CountDownLatch(1)
        var opens = 0
        var oldSink: ((ModelEvent) -> Unit)? = null
        val usage = mutableListOf<ModelEvent.Usage>()
        lateinit var runner: ChatRunner
        val port = ModelPort { rawHistory ->
            val history = MessageHistory.expand(rawHistory)
            opens++
            if (opens == 1) session { _, sink ->
                oldSink = sink
                sink(ModelEvent.TextDelta("same", "accepted"))
                assertTrue(runner.pause()); paused.countDown()
                sink(ModelEvent.TextDelta("same", "late"))
                sink(ModelEvent.Usage(99, 99))
            } else session { _, sink ->
                assertEquals(listOf("one", "two"), history.filter { it.role == "user" }.map { it.id.value })
                oldSink!!(ModelEvent.TextDelta("same", "stale")); oldSink!!(ModelEvent.Usage(88, 88))
                sink(ModelEvent.TextFinal("same", "new")); sink(ModelEvent.Usage(1, 2))
            }
        }
        runner = ChatRunner(port, store(), BatteryTool { error("unused") }, RunnerOptions(onUsage = usage::add)) {}
        val task = FutureTask { runner.run(initial(), emptyList()) }
        val thread = Thread(task).apply { isDaemon = true; start() }
        try {
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            assertTrue(runner.supplement(user("one"))); assertTrue(runner.supplement(user("two")))
            assertFalse(runner.supplement(user("one")))
            assertEquals(1, opens); assertFalse(task.isDone)
            assertTrue(runner.resume())
            val result = task.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(listOf("accepted", "new"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
            assertEquals(listOf(ModelEvent.Usage(1, 2)), usage)
            assertEquals(initial().runId, result.runId)
        } finally { runner.stop(); thread.join(5000); assertFalse(thread.isAlive) }
    }

    @Test fun supplementDuringBatchDoesNotReopenUntilEntireBatchReturns() {
        var opens = 0; var batches = 0
        lateinit var runner: ChatRunner
        val second = call.copy(id = ToolCallId("second"))
        val batch = BatchToolExecutor { _, calls ->
            batches++; assertEquals(listOf(call, second), calls)
            assertTrue(runner.supplement(user("extra")))
            assertEquals(1, opens)
            calls.map { ToolOutput(it.id, "done") }
        }
        runner = ChatRunner(ModelPort { rawHistory ->
            val history = MessageHistory.expand(rawHistory)
            opens++
            if (opens == 1) session { _, sink -> sink(ModelEvent.ToolReady(call)); sink(ModelEvent.ToolReady(second)) }
            else session { _, sink ->
                assertEquals("extra", history.last().id.value)
                assertEquals(2, history.filter { it.role == "assistant" }.flatMap { it.blocks }.filterIsInstance<MessageBlock.Tool>().count { it.result == "done" })
                sink(ModelEvent.TextDelta("t", "answer"))
            }
        }, store(), BatteryTool { error("batch must be used") }, RunnerOptions(batchExecutor = batch)) {}
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(1, batches); assertEquals(2, opens)
    }

    @Test fun mixedAskUserBatchRejectedBeforeDispatcher() {
        var batches = 0
        val runner = ChatRunner(ModelPort { session { _, sink ->
            sink(ModelEvent.ToolReady(call)); sink(ModelEvent.ToolReady(call.copy(id = ToolCallId("ask"), name = "ask_user")))
        } }, store(), BatteryTool { error("unused") }, RunnerOptions(batchExecutor = BatchToolExecutor { _, _ -> batches++; emptyList() })) {}
        assertEquals(RunStatus.FAILED, runner.run(initial(), emptyList()).status); assertEquals(0, batches)
    }

    @Test fun reconnectResumesTransportWithoutReplayingTextOrToolOutputs() {
        var nexts = 0; var resumes = 0; var executions = 0; var opens = 0
        val model = ModelPort {
            opens++
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (nexts++ == 0) sink(ModelEvent.ToolReady(call))
                    else {
                        assertEquals("result", outputs.single().output)
                        sink(ModelEvent.TextDelta("t", "prefix"))
                        throw ModelRequestException(ModelFailure.NETWORK)
                    }
                }
                override fun resume(sink: (ModelEvent) -> Unit) { resumes++; sink(ModelEvent.TextDelta("t", "suffix")) }
                override fun cancel() {}
            }
        }
        val runner = ChatRunner(model, store(), BatteryTool { executions++; "result" }, RunnerOptions(reconnect = ReconnectPolicy(1))) {}
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals("prefixsuffix", result.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertEquals(1, executions); assertEquals(1, opens); assertEquals(2, nexts); assertEquals(1, resumes)
    }

    @Test fun ordinarySessionNeverRepostsEvenWhenRetryEnabled() {
        var opens = 0; var nexts = 0
        val runner = ChatRunner(ModelPort { opens++; session { _, _ -> nexts++; throw ModelRequestException(ModelFailure.NETWORK) } },
            store(), BatteryTool { error("unused") }, RunnerOptions(reconnect = ReconnectPolicy(3))) {}
        assertEquals(RunStatus.FAILED, runner.run(initial(), emptyList()).status)
        assertEquals(1, opens); assertEquals(1, nexts)
    }

    @Test fun unknownClaimBlocksNewCallWithoutToolExecution() {
        var executed = 0
        val journal = object : ToolExecutionJournal {
            override val durable = true
            override fun claim(message: Message, invocation: ToolInvocation) = ToolClaim.Unknown
            override fun complete(message: Message, invocation: ToolInvocation, result: String) { error("unused") }
        }
        val runner = ChatRunner(ModelPort { session { _, sink -> sink(ModelEvent.ToolReady(call.copy(id = ToolCallId("new-id")))) } },
            store(), BatteryTool { executed++; "bad" }, RunnerOptions(toolJournal = journal)) {}
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.FAILED, result.status); assertTrue(result.error!!.contains("只读")); assertEquals(0, executed)
    }

    @Test fun pauseDuringBlockedOpenRejectsOldNextAndWaitsForExplicitResume() {
        assertControlDuringBlockedOpen(pausing = true)
    }
    @Test fun supplementDuringBlockedOpenRejectsOldNextAndReopensWithNewHistory() {
        assertControlDuringBlockedOpen(pausing = false)
    }
    @Test fun pauseDuringFailedOpenReturnsToPauseBoundaryWithoutFailingRun() {
        assertControlDuringBlockedOpen(pausing = true, failOpen = true)
    }
    @Test fun supplementDuringFailedOpenRetriesOnlyWithSupplementHistory() {
        assertControlDuringBlockedOpen(pausing = false, failOpen = true)
    }
    private fun assertControlDuringBlockedOpen(pausing: Boolean, failOpen: Boolean = false) {
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val pausedBoundary = CountDownLatch(1)
        val oldCancelled = CountDownLatch(1)
        val opens = AtomicInteger()
        val oldNexts = AtomicInteger()
        val writes = mutableListOf<Message>()
        val runtime = object : RuntimeStore {
            override fun save(checkpoint: RunCheckpoint) {
                if (checkpoint.phase == ExecutionPhase.PAUSED) pausedBoundary.countDown()
            }
        }
        val stale = object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                oldNexts.incrementAndGet()
                error("obsolete open must never reach next")
            }
            override fun cancel() { oldCancelled.countDown() }
        }
        val runner = ChatRunner(ModelPort { rawHistory ->
            val history = MessageHistory.expand(rawHistory)
            when (opens.incrementAndGet()) {
                1 -> {
                    openEntered.countDown()
                    check(releaseOpen.await(10, TimeUnit.SECONDS))
                    if (failOpen) throw ModelRequestException(ModelFailure.NETWORK)
                    stale
                }
                2 -> {
                    assertEquals(if (pausing) emptyList<String>() else listOf("extra"),
                        history.filter { it.role == "user" }.map { it.id.value })
                    assertTrue(history.all { it.conversationId == initial().conversationId })
                    session { outputs, sink ->
                        assertTrue(outputs.isEmpty())
                        sink(ModelEvent.TextFinal("fresh", "new answer"))
                    }
                }
                else -> error("unexpected extra open")
            }
        }, store(writes), BatteryTool { error("unused") }, RunnerOptions(runtimeStore = runtime), {})
        val run = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(run, "runner-open-control-owner").apply { isDaemon = true }
        val control = FutureTask {
            if (pausing) runner.pause() else runner.supplement(user("extra"))
        }
        val controller = Thread(control, "runner-open-control").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue(openEntered.await(5, TimeUnit.SECONDS))
            controller.start()
            assertTrue(control.get(2, TimeUnit.SECONDS))
            assertEquals(1L, releaseOpen.count)
            assertEquals(0, oldNexts.get())
            if (!pausing) assertFalse(runner.supplement(user("extra")))
            releaseOpen.countDown()
            if (pausing) {
                assertTrue(pausedBoundary.await(5, TimeUnit.SECONDS))
                assertFalse(run.isDone)
                assertEquals(1, opens.get())
                assertEquals(0, oldNexts.get())
                assertTrue(runner.resume())
                assertFalse(runner.resume())
            }
            val result = run.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED, result.status)
            assertNull(result.error)
            assertEquals(initial().runId, result.runId)
            assertEquals(listOf("new answer"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
            assertEquals(0, oldNexts.get())
            assertEquals(2, opens.get())
            if (!failOpen) assertTrue(oldCancelled.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
        } finally {
            releaseOpen.countDown()
            runner.stop()
            owner.join(5000)
            if (controller.state != Thread.State.NEW) controller.join(5000)
            assertFalse(owner.isAlive)
            assertFalse(controller.isAlive)
        }
    }

    @Test fun stopReturnsWhileModelOpenIsBlockedAndLateSessionIsCancelled() {
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val terminal = mutableListOf<Message>()
        val session = object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() { cancelled.countDown() }
        }
        val runner = ChatRunner(ModelPort {
            openEntered.countDown()
            check(releaseOpen.await(5, TimeUnit.SECONDS))
            session
        }, store(terminal), BatteryTool { error("tool must not run") }, {})
        val run = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(run, "runner-open-owner").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue(openEntered.await(5, TimeUnit.SECONDS))
            val stop = FutureTask { runner.stop() }
            val stopper = Thread(stop, "runner-open-stop").apply { isDaemon = true }
            stopper.start()
            try { stop.get(2, TimeUnit.SECONDS) } finally {
                releaseOpen.countDown()
                stopper.join(5000)
                assertFalse(stopper.isAlive)
            }
            assertEquals(RunStatus.STOPPED, run.get(5, TimeUnit.SECONDS).status)
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(RunStatus.STOPPED), terminal.filter { it.status != RunStatus.RUNNING }.map { it.status })
        } finally {
            releaseOpen.countDown()
            owner.join(5000)
            assertFalse(owner.isAlive)
        }
    }

    @Test fun stopReturnsWhileSessionCancelIsBlocked() {
        val nextEntered = CountDownLatch(1); val releaseNext = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1); val releaseCancel = CountDownLatch(1)
        val cancelExited = CountDownLatch(1)
        val writes = mutableListOf<Message>(); val published = mutableListOf<Message>()
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.TextDelta("t", "accepted")); nextEntered.countDown()
                check(releaseNext.await(10, TimeUnit.SECONDS))
            }
            override fun cancel() {
                cancelEntered.countDown()
                try { releaseCancel.await() } finally { cancelExited.countDown() }
            }
        } }, store(writes), BatteryTool { error("unused") }, published::add)
        val run = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(run, "runner-cancel-owner").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue(nextEntered.await(5, TimeUnit.SECONDS))
            val stop = FutureTask { runner.stop() }
            val stopper = Thread(stop, "runner-cancel-stop").apply { isDaemon = true }
            stopper.start(); stop.get(2, TimeUnit.SECONDS); stopper.join(5000)
            assertFalse(stopper.isAlive)
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS))
            releaseNext.countDown()
            val result = run.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.STOPPED, result.status)
            assertEquals(listOf(MessageBlock.Text("t", "accepted")), result.blocks)
            assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
            assertEquals(listOf(result), published.filter { it.status != RunStatus.RUNNING })
            assertEquals(1L, releaseCancel.count); assertEquals(1L, cancelExited.count)
        } finally {
            releaseNext.countDown(); releaseCancel.countDown(); runner.stop(); owner.join(5000)
            assertFalse(owner.isAlive)
            if (cancelEntered.count == 0L) assertTrue(cancelExited.await(5, TimeUnit.SECONDS))
        }
    }


    @Test fun slowConversationWriteDoesNotSerializeStop() {
        val saveEntered = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val writes = mutableListOf<Message>()
        val runner = ChatRunner(ModelPort { session { _, sink -> sink(ModelEvent.TextDelta("t", "accepted")) } },
            object : ConversationStore {
                override fun save(message: Message) {
                    if (message.status == RunStatus.RUNNING && saveEntered.count == 1L) {
                        saveEntered.countDown()
                        check(releaseSave.await(5, TimeUnit.SECONDS))
                    }
                    writes.add(message)
                }
            }, BatteryTool { error("unused") }, {})
        val run = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(run, "runner-store-owner").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue(saveEntered.await(5, TimeUnit.SECONDS))
            val stop = FutureTask { runner.stop() }
            val stopper = Thread(stop, "runner-store-stop").apply { isDaemon = true }
            stopper.start()
            try { stop.get(2, TimeUnit.SECONDS) } finally {
                releaseSave.countDown()
                stopper.join(5000)
                assertFalse(stopper.isAlive)
            }
            assertEquals(RunStatus.STOPPED, run.get(5, TimeUnit.SECONDS).status)
            assertEquals(1, writes.count { it.status != RunStatus.RUNNING })
        } finally {
            releaseSave.countDown()
            owner.join(5000)
            assertFalse(owner.isAlive)
        }
    }

    @Test fun slowRuntimeWriteDoesNotSerializeStop() {
        assertControlReturnsDuringRuntimeWrite { it.stop() }
    }

    @Test fun slowRuntimeWriteDoesNotSerializePause() {
        assertControlReturnsDuringRuntimeWrite { assertTrue(it.pause()) }
    }

    @Test fun slowRuntimeWriteDoesNotSerializeResume() {
        assertControlReturnsDuringRuntimeWrite { runner ->
            assertTrue(runner.pause())
            assertTrue(runner.resume())
            assertFalse(runner.resume())
        }
    }

    @Test fun slowRuntimeWriteDoesNotSerializeSupplement() {
        assertControlReturnsDuringRuntimeWrite { runner ->
            assertTrue(runner.supplement(user("during-runtime-write")))
            assertFalse(runner.supplement(user("during-runtime-write")))
        }
    }

    private fun assertControlReturnsDuringRuntimeWrite(control: (ChatRunner) -> Unit) {
        val runtimeEntered = CountDownLatch(1)
        val releaseRuntime = CountDownLatch(1)
        val runtime = object : RuntimeStore {
            var first = true
            override fun save(checkpoint: RunCheckpoint) {
                if (first) {
                    first = false
                    runtimeEntered.countDown()
                    check(releaseRuntime.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val nextRelease = CountDownLatch(1)
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                check(nextRelease.await(5, TimeUnit.SECONDS))
            }
            override fun cancel() { nextRelease.countDown() }
        } }, store(), BatteryTool { error("unused") },
            RunnerOptions(runtimeStore = runtime), {})
        val run = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(run, "runner-runtime-owner").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue(runtimeEntered.await(5, TimeUnit.SECONDS))
            val controlTask = FutureTask { control(runner); true }
            val controller = Thread(controlTask, "runner-runtime-control").apply { isDaemon = true }
            controller.start()
            try {
                assertTrue(controlTask.get(2, TimeUnit.SECONDS))
            } finally {
                controller.join(5000)
                assertFalse(controller.isAlive)
            }
            releaseRuntime.countDown()
            val stop = FutureTask { runner.stop() }
            val stopper = Thread(stop, "runner-runtime-stop").apply { isDaemon = true }
            stopper.start()
            try { stop.get(2, TimeUnit.SECONDS) } finally {
                stopper.join(5000)
                assertFalse(stopper.isAlive)
            }
            assertEquals(RunStatus.STOPPED, run.get(5, TimeUnit.SECONDS).status)
        } finally {
            releaseRuntime.countDown()
            owner.join(5000)
            assertFalse(owner.isAlive)
        }
    }

    @Test fun stopAndCompletionHaveOneExternallyVisibleTerminal() {
        val completedSaveEntered = CountDownLatch(1)
        val releaseCompletedSave = CountDownLatch(1)
        val writes = mutableListOf<Message>()
        val published = mutableListOf<Message>()
        val runner = ChatRunner(ModelPort { session { _, sink -> sink(ModelEvent.TextDelta("t", "answer")) } },
            object : ConversationStore {
                override fun save(message: Message) {
                    if (message.status == RunStatus.COMPLETED) {
                        completedSaveEntered.countDown()
                        check(releaseCompletedSave.await(5, TimeUnit.SECONDS))
                    }
                    writes.add(message)
                }
            }, BatteryTool { error("unused") }, published::add)
        val run = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(run, "runner-terminal-owner").apply { isDaemon = true }
        owner.start()
        val stop = FutureTask { runner.stop() }
        val stopper = Thread(stop, "runner-terminal-stop").apply { isDaemon = true }
        try {
            assertTrue(completedSaveEntered.await(5, TimeUnit.SECONDS))
            stopper.start()
            releaseCompletedSave.countDown()
            val result = run.get(5, TimeUnit.SECONDS)
            stop.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(listOf(RunStatus.COMPLETED), writes.filter { it.status != RunStatus.RUNNING }.map { it.status })
            assertEquals(listOf(RunStatus.COMPLETED), published.filter { it.status != RunStatus.RUNNING }.map { it.status })
        } finally {
            releaseCompletedSave.countDown()
            owner.join(5000)
            if (stopper.state != Thread.State.NEW) stopper.join(5000)
            assertFalse(owner.isAlive)
            assertFalse(stopper.isAlive)
        }
    }

    @Test fun cancelledGenerationCannotPublishTextUsageOrExecuteTool() {
        val paused = CountDownLatch(1)
        val usage = mutableListOf<ModelEvent.Usage>()
        var executions = 0
        var opens = 0
        var staleSink: ((ModelEvent) -> Unit)? = null
        lateinit var runner: ChatRunner
        val staleCall = call.copy(id = ToolCallId("late"))
        val port = ModelPort {
            opens++
            if (opens == 1) session { _, sink ->
                staleSink = sink
                sink(ModelEvent.TextDelta("t", "kept"))
                check(runner.pause())
                paused.countDown()
                staleSink!!(ModelEvent.TextDelta("t", "late"))
                staleSink!!(ModelEvent.Usage(77, 88))
                staleSink!!(ModelEvent.ToolReady(staleCall))
            } else session { _, sink -> sink(ModelEvent.TextFinal("t", "fresh")) }
        }
        runner = ChatRunner(port, store(), BatteryTool { executions++; "must-not-run" },
            RunnerOptions(onUsage = usage::add), {})
        val run = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(run, "runner-generation-owner").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            assertTrue(runner.resume())
            val result = run.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(listOf("kept", "fresh"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
            assertTrue(usage.isEmpty())
            assertEquals(0, executions)
            assertFalse(result.blocks.any { it is MessageBlock.Tool })
        } finally {
            runner.stop()
            owner.join(5000)
            assertFalse(owner.isAlive)
        }
    }

    @Test fun stoppingReconnectDoesNotResumeOrOpenNextRequest() {
        val reconnecting = CountDownLatch(1)
        val releaseReconnectCheckpoint = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        var opens = 0
        var nexts = 0
        var resumes = 0
        val model = ModelPort {
            opens++
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) {
                    resumes++
                    sink(ModelEvent.TextDelta("t", "must-not-arrive"))
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
        val runtime = object : RuntimeStore {
            override fun save(checkpoint: RunCheckpoint) {
                if (checkpoint.phase == ExecutionPhase.RECONNECTING) {
                    reconnecting.countDown()
                    assertTrue(releaseReconnectCheckpoint.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val runner = ChatRunner(model, store(), BatteryTool { error("unused") },
            RunnerOptions(reconnect = ReconnectPolicy(1), runtimeStore = runtime), {})
        val run = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(run, "runner-reconnect-owner").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue(reconnecting.await(5, TimeUnit.SECONDS))
            runner.stop()
            releaseReconnectCheckpoint.countDown()
            assertEquals(RunStatus.STOPPED, run.get(5, TimeUnit.SECONDS).status)
            assertEquals(1, opens)
            assertEquals(1, nexts)
            assertEquals(0, resumes)
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        } finally {
            releaseReconnectCheckpoint.countDown()
            runner.stop()
            owner.join(5000)
            assertFalse(owner.isAlive)
        }
    }

    private fun lateEvents(): List<ModelEvent> = listOf(
        ModelEvent.TextDelta("same", "must-not-append"),
        ModelEvent.TextFinal("same", "must-not-replace"),
        ModelEvent.ReasoningDelta("late-reasoning", "OK.\n".repeat(2000)),
        ModelEvent.ReasoningFinal("late-reasoning", "OK.\n".repeat(2000)),
        ModelEvent.ProtocolOutput(listOf("must-not-persist")),
        ModelEvent.ToolReady(call.copy(id = ToolCallId("late-tool"))),
        ModelEvent.Usage(99, 99)
    )

    @Test fun streamingSupplementFencesAllOldEventsAndReopensWithSupplementHistory() {
        val streaming = CountDownLatch(1); val releaseOldNext = CountDownLatch(1)
        val opens = AtomicInteger(); val executions = AtomicInteger()
        val writes = mutableListOf<Message>(); val published = mutableListOf<Message>()
        val usage = mutableListOf<ModelEvent.Usage>()
        lateinit var oldSink: (ModelEvent) -> Unit
        lateinit var freshSink: (ModelEvent) -> Unit
        val runner = ChatRunner(ModelPort { rawHistory ->
            val history = MessageHistory.expand(rawHistory)
            when (opens.incrementAndGet()) {
                1 -> session { outputs, sink ->
                    assertTrue(outputs.isEmpty()); oldSink = sink
                    sink(ModelEvent.TextDelta("same", "accepted"))
                    sink(ModelEvent.ProtocolOutput(listOf("accepted-protocol")))
                    streaming.countDown(); check(releaseOldNext.await(10, TimeUnit.SECONDS))
                    lateEvents().forEach { sink(it) }
                }
                2 -> {
                    assertEquals(listOf("before", "extra"), history.filter { it.role == "user" }.map { it.id.value })
                    assertEquals(listOf("user", "assistant", "user"), history.map { it.role })
                    val accepted = history.single { it.role == "assistant" }
                    assertEquals(initial().id, accepted.id)
                    assertEquals(RunStatus.COMPLETED, accepted.status)
                    assertEquals(listOf(MessageBlock.Text("same", "accepted")), accepted.blocks)
                    assertEquals(listOf("accepted-protocol"),accepted.protocolItems); assertEquals(user("extra"), history.last())
                    session { outputs, sink ->
                        freshSink = sink; assertTrue(outputs.isEmpty())
                        lateEvents().forEach { oldSink(it) }
                        sink(ModelEvent.TextFinal("same", "fresh"))
                        sink(ModelEvent.ProtocolOutput(listOf("fresh-protocol")))
                        sink(ModelEvent.Usage(1, 2))
                    }
                }
                else -> error("unexpected extra open")
            }
        }, store(writes), BatteryTool { executions.incrementAndGet(); "bad" },
            RunnerOptions(onUsage = usage::add), published::add)
        val run = FutureTask { runner.run(initial(), listOf(user("before"))) }
        val owner = Thread(run, "runner-streaming-supplement-owner").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue(streaming.await(5, TimeUnit.SECONDS))
            assertTrue(runner.supplement(user("extra"))); assertFalse(runner.supplement(user("extra")))
            releaseOldNext.countDown()
            val result = run.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(listOf("accepted", "fresh"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
            assertFalse(result.blocks.any { it is MessageBlock.Tool })
            assertEquals(listOf("fresh-protocol"), result.protocolItems)
            assertEquals(listOf(ModelEvent.Usage(1, 2)), usage)
            assertEquals(0, executions.get()); assertEquals(2, opens.get())
            assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
            assertEquals(listOf(result), published.filter { it.status != RunStatus.RUNNING })
            val savedBefore = writes.toList(); val publishedBefore = published.toList()
            lateEvents().forEach { oldSink(it); freshSink(it) }
            assertEquals(savedBefore, writes); assertEquals(publishedBefore, published)
            assertEquals(listOf(ModelEvent.Usage(1, 2)), usage); assertEquals(0, executions.get())
        } finally {
            releaseOldNext.countDown(); runner.stop(); owner.join(5000); assertFalse(owner.isAlive)
        }
    }

    @Test fun resumableSessionWithCanResumeFalseNeverRetriesOrReopens() {
        assertFailedReconnect(canResume = false, expectedResumes = 0)
    }
    @Test fun failingResumeStopsAtRetryBudgetWithoutRepostingNext() {
        assertFailedReconnect(canResume = true, expectedResumes = 2)
    }
    private fun assertFailedReconnect(canResume: Boolean, expectedResumes: Int) {
        var opens = 0; var nexts = 0; var resumes = 0; var executions = 0
        val writes = mutableListOf<Message>(); val usage = mutableListOf<ModelEvent.Usage>()
        lateinit var retainedSink: (ModelEvent) -> Unit
        val runner = ChatRunner(ModelPort {
            opens++
            object : ResumableModelSession {
                override val canResume: Boolean = canResume
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++; assertTrue(outputs.isEmpty()); retainedSink = sink
                    sink(ModelEvent.TextDelta("same", "prefix"))
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) {
                    resumes++; sink(ModelEvent.TextDelta("same", "-retry-$resumes"))
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun cancel() {}
            }
        }, store(writes), BatteryTool { executions++; "bad" },
            RunnerOptions(reconnect = ReconnectPolicy(maxAttempts = 2), onUsage = usage::add), {})
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(ModelFailure.NETWORK.userMessage, result.error)
        assertEquals("prefix" + (1..expectedResumes).joinToString("") { "-retry-$it" },
            result.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertEquals(1, opens); assertEquals(1, nexts); assertEquals(expectedResumes, resumes)
        assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
        val savedBefore = writes.toList(); lateEvents().forEach { retainedSink(it) }
        assertEquals(savedBefore, writes); assertTrue(usage.isEmpty()); assertEquals(0, executions)
    }

    @Test fun stoppingLongReconnectDelayUnparksOwnerWithoutResume() {
        val reconnecting = CountDownLatch(1)
        val writes = mutableListOf<Message>()
        var opens = 0; var nexts = 0; var resumes = 0
        val runner = ChatRunner(ModelPort {
            opens++
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++; sink(ModelEvent.TextDelta("t", "accepted"))
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) { resumes++ }
                override fun cancel() {}
            }
        }, store(writes), BatteryTool { error("unused") }, RunnerOptions(
            reconnect = ReconnectPolicy(maxAttempts = 1, delayMillis = 60_000),
            runtimeStore = object : RuntimeStore {
                override fun save(checkpoint: RunCheckpoint) {
                    if (checkpoint.phase == ExecutionPhase.RECONNECTING) reconnecting.countDown()
                }
            }), {})
        val run = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(run, "runner-reconnect-delay-owner").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue(reconnecting.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (owner.state != Thread.State.TIMED_WAITING && owner.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, owner.state)
            assertEquals(ExecutionPhase.RECONNECTING, runner.state().phase)
            runner.stop()
            val result = run.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.STOPPED, result.status)
            assertEquals(listOf(MessageBlock.Text("t", "accepted")), result.blocks)
            assertEquals(1, opens); assertEquals(1, nexts); assertEquals(0, resumes)
            assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
        } finally { runner.stop(); owner.join(5000); assertFalse(owner.isAlive) }
    }


    @Test fun pauseBeforeToolOutputAdmissionKeepsSessionAndOutputsUntilResume() {
        val paused = CountDownLatch(1); var requested = false
        var opens = 0; var rounds = 0; var executed = 0
        val writes = mutableListOf<Message>()
        lateinit var runner: ChatRunner
        val runtime = object : RuntimeStore {
            override fun save(checkpoint: RunCheckpoint) {
                if (checkpoint.phase == ExecutionPhase.BOUNDARY && checkpoint.outputs.isNotEmpty() && !requested) {
                    requested = true
                    assertTrue(runner.pause())
                }
                if (checkpoint.phase == ExecutionPhase.PAUSED) paused.countDown()
            }
        }
        runner = ChatRunner(ModelPort {
            opens++
            session { outputs, sink ->
                if (rounds++ == 0) sink(ModelEvent.ToolReady(call))
                else {
                    assertEquals(listOf(ToolOutput(call.id, "result")), outputs)
                    sink(ModelEvent.TextFinal("answer", "done"))
                }
            }
        }, store(writes), BatteryTool { executed++; "result" }, RunnerOptions(runtimeStore = runtime), {})
        val run = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(run, "runner-output-admission-owner").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            assertFalse(run.isDone); assertTrue(runner.resume())
            val result = run.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(1, opens); assertEquals(2, rounds); assertEquals(1, executed)
            assertEquals("result", result.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
            assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
        } finally { runner.stop(); owner.join(5000); assertFalse(owner.isAlive) }
    }

    @Test fun ownerBindingWaitsForDurableBoundaryAndFailureNeverOpensModel() {
        for (failCommit in listOf(false, true)) {
            var bindings = 0; var opens = 0
            val checkpoints = mutableListOf<RunCheckpoint>()
            val runtime = object : AtomicRuntimeStore {
                override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                    if (failCommit) error("disk")
                    checkpoints.add(checkpoint)
                }
                override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("no recovery")
            }
            val executor = object : ReceiptBatchToolExecutor {
                override fun bindOwner(owner: RunExecutionOwner) {
                    bindings++
                    assertTrue(checkpoints.isNotEmpty())
                    assertEquals(checkpoints.last().owner, owner.ownerId)
                    assertTrue(owner.owns(initial()))
                    error("binding rejected")
                }
                override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
            }
            val runner = ChatRunner(ModelPort { opens++; error("must not open") }, store(),
                BatteryTool { error("unused") }, RunnerOptions(runtimeStore = runtime, receiptBatchExecutor = executor)) {}
            assertEquals(RunStatus.FAILED, runner.run(initial(), emptyList()).status)
            assertEquals(if (failCommit) 0 else 1, bindings)
            assertEquals(0, opens)
        }
    }

    @Test fun stoppedBeforeRunNeverBindsOwnerOrOpensModel() {
        var bindings = 0; var opens = 0
        val executor = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { bindings++ }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        val runner = ChatRunner(ModelPort { opens++; error("must not open") }, store(),
            BatteryTool { error("unused") }, RunnerOptions(receiptBatchExecutor = executor)) {}
        runner.stop()
        assertEquals(RunStatus.STOPPED, runner.run(initial(), emptyList()).status)
        assertEquals(0, bindings); assertEquals(0, opens)
    }

    @Test fun stopDuringOwnerBindingDoesNotWaitOrAllowLateModelOpen() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var opens = 0
        val executor = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        val runner = ChatRunner(ModelPort { opens++; error("must not open") }, store(),
            BatteryTool { error("unused") }, RunnerOptions(receiptBatchExecutor = executor)) {}
        val task = FutureTask { runner.run(initial(), emptyList()) }
        val worker = Thread(task).apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            runner.stop()
            assertFalse(task.isDone)
            release.countDown()
            assertEquals(RunStatus.STOPPED, task.get(5, TimeUnit.SECONDS).status)
            assertEquals(0, opens)
        } finally { release.countDown(); runner.stop(); worker.join(5000); assertFalse(worker.isAlive) }
    }

    @Test fun pauseReopenRetainsOneOwnerAndIndependentRunsCannotShareIt() {
        val paused = CountDownLatch(1)
        val owners = mutableListOf<RunExecutionOwner>()
        var opens = 0
        lateinit var runner: ChatRunner
        val executor = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { owners.add(owner) }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        val model = ModelPort {
            opens++
            if (opens == 1) session { _, _ -> assertTrue(runner.pause()); paused.countDown() }
            else session { _, sink -> sink(ModelEvent.TextFinal("answer", "resumed")) }
        }
        runner = ChatRunner(model, store(), BatteryTool { error("unused") }, RunnerOptions(receiptBatchExecutor = executor)) {}
        val task = FutureTask { runner.run(initial(), emptyList()) }
        val thread = Thread(task).apply { isDaemon = true; start() }
        try {
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            assertTrue(runner.resume())
            assertEquals(RunStatus.COMPLETED, task.get(5, TimeUnit.SECONDS).status)
            assertEquals(2, opens); assertEquals(1, owners.size)
        } finally { runner.stop(); thread.join(5000); assertFalse(thread.isAlive) }
        val other = ChatRunner(ModelPort { session { _, _ -> } }, store(), BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = executor)) {}
        assertEquals(RunStatus.COMPLETED, other.run(initial(), emptyList()).status)
        assertEquals(2, owners.size)
        assertNotEquals(owners[0].ownerId, owners[1].ownerId)
        assertTrue(owners.all { it.owns(initial()) })
    }

}
