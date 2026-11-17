package app.daiyu.android

import androidx.compose.runtime.State
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.storage.*
import app.daiyu.android.ui.RetainedToolOutcomes
import app.daiyu.android.ui.screens.chat.conversation.*
import kotlinx.coroutines.Job
import java.util.concurrent.TimeUnit

/** Exercise the real host's pending-read frame, not only the final receipt mapper. */
@Suppress("DEPRECATION")
class ToolReceiptPauseActivityTest : WiredActivityTestCase() {
    private lateinit var historical: Message
    private val expected = listOf(WorkStepState.OK, WorkStepState.FAILED,
        WorkStepState.NOT_EXECUTED, WorkStepState.UNKNOWN)

    internal override fun createFixture() = RunControlFixture(instrumentation.targetContext,
        beforeController = { database, _ ->
            val conversation = database.create("default")
            val user = Message(MessageId("receipt-user"), conversation.id, RunId("receipt-run"),
                Message.USER_ROLE, listOf(MessageBlock.Text("user", "read")), RunStatus.COMPLETED)
            val tools = listOf(ToolDisposition.SUCCEEDED, ToolDisposition.FAILED,
                ToolDisposition.NOT_EXECUTED, ToolDisposition.UNKNOWN).mapIndexed { index, disposition ->
                ToolRecord(ToolInvocation(ToolCallId("receipt-$index"), if (index == 0) "search_apps" else "screen", "{}"), disposition, if (disposition == ToolDisposition.UNKNOWN) null else "receipt-$index")
            }
            val initial = user.copy(id = MessageId("receipt-assistant"), role = Message.ASSISTANT_ROLE,
                status = RunStatus.RUNNING, blocks = tools.map { MessageBlock.Tool(it.invocation, it.result) })
            historical = initial.copy(status = RunStatus.INTERRUPTED)
            SQLiteRuntimeStore.acquire(database).use { runtime ->
                runtime.recoverPending()
                database.send(user, initial)
                runtime.commitCheckpoint(RunCheckpoint(historical, listOf(user), ExecutionPhase.TERMINAL,
                    tools, owner = "receipt-owner", revision = 1))
            }
        })

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun call(name: String): Any? = MainActivity::class.java.declaredMethods.single {
        it.name == name && it.parameterCount == 0
    }.apply { isAccessible = true }.invoke(activity)
    private fun currentKey() = call("currentToolReceiptKey") as RetainedToolOutcomes.ReadKey
    private fun loadedKey() = field("toolReceiptKey").get(activity) as? RetainedToolOutcomes.ReadKey
    private fun steps(): List<WorkStepUi> {
        val state = (field("chatState\$delegate").get(activity) as State<*>).value as ChatScreenState
        return state.turns.filterIsInstance<ChatTurnUi.Assistant>().flatMap { turn ->
            turn.workProcess?.segments.orEmpty().flatMap { it.steps }
        }.filter { it.owner?.messageId == historical.id.value }
    }
    private fun awaitReceipts() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        var ready = false
        var diagnostic = ""
        while (!ready && System.nanoTime() < deadline) {
            mainThread {
                val current = currentKey()
                val loaded = loadedKey()
                val projected = steps()
                ready = loaded?.canDisplayFor(current) == true && projected.map { it.state } == expected
                diagnostic = "current=$current loaded=$loaded states=${projected.map { it.state }} " +
                    "activities=${projected.map { it.activity }} active=${fixture.controller.active?.id}"
            }
            if (!ready) Thread.sleep(10)
        }
        assertTrue("exact durable receipt projection: $diagnostic", ready)
    }

    /** Model an IO job that has not returned, then inspect the synchronous published frame. */
    private fun pendingFrame(key: RetainedToolOutcomes.ReadKey, snapshot: Any, states: List<WorkStepState>) {
        val pending = Job()
        assertFalse(key == currentKey())
        try {
            field("toolReceiptKey").set(activity, key)
            field("toolReceiptSnapshot").set(activity, snapshot)
            field("toolReceiptRead").get(activity)?.let { (it as Job).cancel() }
            field("toolReceiptRead").set(activity, pending)
            field("toolReceiptRequested").set(activity, currentKey())
            field("toolReceiptRequest").set(activity, Any())
            repeat(3) {
                call("publishChat")
                assertEquals(states, steps().map { it.state })
            }
            assertEquals(if (states == expected) listOf(ToolActivityUi.SUCCESS, ToolActivityUi.FAILED,
                ToolActivityUi.NOT_EXECUTED, if (key.canDisplayFor(currentKey())) ToolActivityUi.INTERRUPTED else ToolActivityUi.UNKNOWN)
                else List(4) { ToolActivityUi.UNKNOWN },
                steps().map { it.activity })
            assertEquals(currentKey(), field("toolReceiptRequested").get(activity))
            assertSame(pending, field("toolReceiptRead").get(activity))
            assertTrue("fresh read remains pending", pending.isActive)
        } finally {
            pending.cancel()
            field("toolReceiptRead").set(activity, null)
            field("toolReceiptRequested").set(activity, null)
            field("toolReceiptRequest").set(activity, null)
            call("publishChat")
        }
    }

    fun testPaneReentryPreparesReceiptsBeforeDisplayAndAuthoritativeHistory() {
        val conversation = fixture.database.conversations().single()
        mainThread { activity.select(conversation) }
        awaitReceipts()
        mainThread { activity.select(null) }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val receiptEntered = java.util.concurrent.CountDownLatch(1)
        val receiptRelease = java.util.concurrent.CountDownLatch(1)
        val receiptReader = activity.toolReceiptReader
        val readerField = field("loadedWindowReader")
        @Suppress("UNCHECKED_CAST")
        val reader = readerField.get(activity) as (ConversationId, Long, Int) -> List<Pair<Long, Message>>
        val displayField = field("displayWindow\$delegate")
        fun display() = (displayField.get(activity) as State<*>).value
        mainThread {
            activity.toolReceiptReader = { rows, window ->
                receiptEntered.countDown()
                check(receiptRelease.await(60, TimeUnit.SECONDS))
                receiptReader(rows, window)
            }
            val blocked: (ConversationId, Long, Int) -> List<Pair<Long, Message>> = { id, before, limit ->
                entered.countDown(); check(release.await(60, TimeUnit.SECONDS)); reader(id, before, limit)
            }
            readerField.set(activity, blocked)
            MainActivity::class.java.declaredMethods.single { it.name == "selectPaneConversation" }
                .apply { isAccessible = true }.invoke(activity, conversation.id.value)
        }
        try {
            assertTrue("receipt read entered", receiptEntered.await(8, TimeUnit.SECONDS))
            mainThread {
                val state = (field("chatState\$delegate").get(activity) as State<*>).value as ChatScreenState
                assertNull("previous page remains selected while receipt IO is blocked", state.conversationKey)
                assertEquals("authority cannot overtake prepared receipt baseline", 1L, entered.count)
            }
            receiptRelease.countDown()
            assertTrue("authority read entered", entered.await(8, TimeUnit.SECONDS))
            awaitReceipts()
            mainThread {
                assertEquals("authority is still blocked", 1L, release.count)
                assertNotNull(display())
                assertTrue((field("shown").get(activity) as List<*>).isEmpty())
                assertTrue(checkNotNull(loadedKey()).canDisplayFor(currentKey()))
                assertEquals("display consumes completed evidence without waiting for history", expected, steps().map { it.state })
            }
            release.countDown()
            awaitControl("authority takes over without status regression") { display() == null }
            awaitReceipts()
            mainThread { assertEquals(expected, steps().map { it.state }) }
            assertEquals(0, fixture.opens.get())
        } finally {
            receiptRelease.countDown(); release.countDown()
            mainThread { activity.toolReceiptReader = receiptReader; readerField.set(activity, reader) }
        }
    }

    fun testReceiptReadFailureKeepsUnprovenStatusesPendingAndCanRetry() {
        val conversation = fixture.database.conversations().single()
        val receiptReader = activity.toolReceiptReader
        val entered = java.util.concurrent.CountDownLatch(1)
        mainThread {
            activity.select(null)
            activity.toolReceiptReader = { _, _ ->
                entered.countDown()
                throw java.io.IOException("receipt read unavailable")
            }
            activity.select(conversation)
        }
        try {
            assertTrue("failed worker entered", entered.await(8, TimeUnit.SECONDS))
            awaitControl("failed reader leaves no accepted evidence or pending job") {
                field("toolReceiptRead").get(activity) == null && loadedKey()?.canDisplayFor(currentKey()) != true
            }
            mainThread {
                assertEquals(List(4) { WorkStepState.UNKNOWN }, steps().map { it.state })
                assertEquals(List(4) { ToolActivityUi.UNKNOWN }, steps().map { it.activity })
                activity.toolReceiptReader = receiptReader
                call("publishChat")
            }
            awaitReceipts()
            assertEquals(0, fixture.opens.get())
        } finally { mainThread { activity.toolReceiptReader = receiptReader } }
    }

    fun testFirstReadSurvivesLivePhaseChangesAndPublishesWithoutAnotherModelEvent() {
        mainThread { activity.select(fixture.database.conversations().single()) }
        awaitReceipts()
        mainThread {
            val current = currentKey()
            val snapshot = checkNotNull(field("toolReceiptSnapshot").get(activity)) as RetainedToolOutcomes.Snapshot
            val scheduled = current.copy(execution = ExecutionState(ExecutionPhase.STREAMING,
                activeToolCalls = setOf(ToolCallId("receipt-0"))))
            val pending = Job()
            val request = Any()
            field("toolReceiptRead").get(activity)?.let { (it as Job).cancel() }
            field("toolReceiptKey").set(activity, null)
            field("toolReceiptSnapshot").set(activity, null)
            field("toolReceiptRead").set(activity, pending)
            field("toolReceiptRequested").set(activity, scheduled)
            field("toolReceiptRequest").set(activity, request)
            try {
                repeat(3) {
                    call("publishChat")
                    assertSame("live phase must not cancel the exact-source read", pending,
                        field("toolReceiptRead").get(activity))
                    assertTrue(pending.isActive)
                    assertEquals(List(4) { ToolActivityUi.UNKNOWN }, steps().map { it.activity })
                }
                MainActivity::class.java.declaredMethods.single { it.name == "completeToolReceipts" }
                    .apply { isAccessible = true }.invoke(activity, request, scheduled, snapshot)
                assertEquals("completed evidence is published in this callback", expected, steps().map { it.state })
                assertEquals(ToolActivityUi.SUCCESS, steps().first().activity)
                assertNull("same durable source is reprojected without another read", field("toolReceiptRequested").get(activity))
                assertNull(field("toolReceiptRead").get(activity))
            } finally { pending.cancel() }
        }
        awaitReceipts()
        assertEquals("receipt refresh never opens a model session", 0, fixture.opens.get())
    }

    fun testLateReceiptCompletionCannotReplaceAnotherSourceOrRequest() {
        mainThread { activity.select(fixture.database.conversations().single()) }
        awaitReceipts()
        mainThread {
            val current = currentKey()
            val snapshot = checkNotNull(field("toolReceiptSnapshot").get(activity)) as RetainedToolOutcomes.Snapshot
            val complete = MainActivity::class.java.declaredMethods.single { it.name == "completeToolReceipts" }
                .apply { isAccessible = true }
            val pending = Job()
            val request = Any()
            field("toolReceiptRead").get(activity)?.let { (it as Job).cancel() }
            field("toolReceiptKey").set(activity, null)
            field("toolReceiptSnapshot").set(activity, null)
            field("toolReceiptRead").set(activity, pending)
            field("toolReceiptRequest").set(activity, request)
            try {
                for (stale in listOf(current.copy(conversation = ConversationId("other")),
                    current.copy(data = current.data + 1), current.copy(generation = current.generation + 1),
                    current.copy(sources = emptyList()))) {
                    field("toolReceiptRequested").set(activity, stale)
                    complete.invoke(activity, request, stale, snapshot)
                    assertNull(loadedKey())
                    assertSame(pending, field("toolReceiptRead").get(activity))
                }
                field("toolReceiptRequested").set(activity, current)
                complete.invoke(activity, Any(), current, snapshot)
                assertNull(loadedKey())
                assertSame(request, field("toolReceiptRequest").get(activity))
            } finally {
                pending.cancel()
                field("toolReceiptRead").set(activity, null)
                field("toolReceiptRequested").set(activity, null)
                field("toolReceiptRequest").set(activity, null)
                call("publishChat")
            }
        }
        awaitReceipts()
    }

    fun testPauseRequestPausedAndResumeKeepSettledReceiptsWhileReadIsPending() {
        val conversation = fixture.database.conversations().single()
        val binding = checkNotNull(fixture.database.modelBinding(null))
        assertTrue(fixture.database.bindModel(conversation.id, fixture.database.modelBinding(conversation.id), binding))
        mainThread { activity.select(conversation) }
        awaitReceipts()
        val first = fixture.session("cancelled", holdSeconds = 120, releaseOnCancel = false)
        val second = fixture.session("resumed", holdSeconds = 120)
        setDraft("continue reading")
        ui.waitUntil(timeoutMillis = 8_000) {
            ui.onAllNodes(hasTestTag("chat-send") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        node("chat-send").assertIsDisplayed().assertIsEnabled().performClick()
        awaitTextSendResult(activity)
        assertTrue("initial model session entered: ${fixture.controller.problem}", first.entered.await(30, TimeUnit.SECONDS))
        assertNull(fixture.controller.problem)
        assertDraft("")
        val owner = checkNotNull(fixture.controller.active)
        assertEquals(1, fixture.opens.get())
        awaitReceipts()
        lateinit var key: RetainedToolOutcomes.ReadKey
        lateinit var snapshot: Any
        mainThread {
            key = currentKey()
            assertTrue(checkNotNull(loadedKey()).canDisplayFor(key))
            snapshot = checkNotNull(field("toolReceiptSnapshot").get(activity))
            assertTrue(fixture.controller.pause(owner))
            assertTrue(checkNotNull(fixture.controller.runState(owner)).pauseRequested)
            assertFalse(key == currentKey())
            assertTrue(key.canDisplayFor(currentKey()))
            pendingFrame(key, snapshot, expected)
        }
        awaitReceipts()
        mainThread {
            assertTrue(checkNotNull(fixture.controller.runState(owner)).pauseRequested)
            key = currentKey()
            assertTrue(key.execution?.pauseRequested == true)
            assertTrue(checkNotNull(loadedKey()).canDisplayFor(key))
            snapshot = checkNotNull(field("toolReceiptSnapshot").get(activity))
        }
        first.release.countDown()
        awaitControl("durable pause boundary") { fixture.controller.runState(owner)?.phase == ExecutionPhase.PAUSED }
        mainThread {
            assertFalse(key == currentKey())
            assertTrue(key.canDisplayFor(currentKey()))
            pendingFrame(key, snapshot, expected)
        }
        awaitReceipts()
        mainThread {
            key = currentKey()
            assertEquals(ExecutionPhase.PAUSED, key.execution?.phase)
            assertTrue(checkNotNull(loadedKey()).canDisplayFor(key))
            snapshot = checkNotNull(field("toolReceiptSnapshot").get(activity))
            assertEquals(ExecutionPhase.PAUSED, fixture.controller.runState(owner)?.phase)
            assertTrue(key.canDisplayFor(currentKey()))
            assertTrue(fixture.controller.resume(owner))
        }
        assertTrue(second.entered.await(30, TimeUnit.SECONDS))
        mainThread {
            assertFalse(key == currentKey())
            assertTrue(key.canDisplayFor(currentKey()))
            pendingFrame(key, snapshot, expected)
        }
        awaitReceipts()
        assertEquals(2, fixture.opens.get())
        val persisted = checkNotNull(SQLiteRuntimeStore.readCheckpointSnapshot(fixture.database.readableDatabase, historical.id.value))
        assertEquals(listOf(ToolDisposition.SUCCEEDED, ToolDisposition.FAILED,
            ToolDisposition.NOT_EXECUTED, ToolDisposition.UNKNOWN), persisted.tools.map { it.disposition })
    }

    fun testNewerDataRevisionKeepsOnlyConfirmedReceiptsWhileTheRealHostReadIsPending() {
        mainThread { activity.select(fixture.database.conversations().single()) }
        awaitReceipts()
        mainThread {
            val current = currentKey()
            val snapshot = checkNotNull(field("toolReceiptSnapshot").get(activity))
            val loaded = current.copy(data = current.data - 1)
            assertFalse("new evidence is still required", loaded.canDisplayFor(current))
            pendingFrame(loaded, snapshot, expected)
            assertEquals("display retention never opens a model session", 0, fixture.opens.get())
        }
        awaitReceipts()
    }

    fun testPendingReadNeverBorrowsEvidenceFromAnotherOwnerOrSourceRevision() {
        mainThread { activity.select(fixture.database.conversations().single()) }
        awaitReceipts()
        mainThread {
            val key = checkNotNull(loadedKey())
            val snapshot = checkNotNull(field("toolReceiptSnapshot").get(activity))
            for (stale in listOf(key.copy(conversation = ConversationId("other")),
                key.copy(data = key.data + 1), key.copy(sources = emptyList()))) {
                pendingFrame(stale, snapshot, List(4) { WorkStepState.UNKNOWN })
            }
            pendingFrame(key.copy(generation = key.generation - 1), snapshot, expected)
        }
        awaitReceipts()
    }
    fun testReturnedToolsStayUnconfirmedThenShowTheirExactFinalStatus() {
        mainThread { activity.select(fixture.database.conversations().single()) }
        awaitReceipts()
        val pending = Job()
        try {
            mainThread {
                field("toolReceiptRead").get(activity)?.let { (it as Job).cancel() }
                field("toolReceiptKey").set(activity, null)
                field("toolReceiptSnapshot").set(activity, null)
                field("toolReceiptRead").set(activity, pending)
                field("toolReceiptRequested").set(activity, currentKey())
                field("toolReceiptRequest").set(activity, Any())
                repeat(3) {
                    call("publishChat")
                    val search = steps().first()
                    assertEquals("search_apps", search.toolName)
                    assertEquals(WorkStepState.UNKNOWN, search.state)
                    assertEquals(ToolActivityUi.UNKNOWN, search.activity)
                    assertEquals(ToolActivityUi.UNKNOWN, steps().last().activity)
                }
                assertSame(pending, field("toolReceiptRead").get(activity))
                assertTrue(pending.isActive)
            }
            val header = ui.onNodeWithTag("work-process-header")
            if (header.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "已折叠")
                header.performClick()
            ui.onNodeWithTag("tool-step-header:receipt-0").assert(SemanticsMatcher("tool stays unconfirmed until its exact final receipt") {
                it.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].startsWith("结果未确认 ·")
            })
        } finally {
            mainThread {
                pending.cancel()
                field("toolReceiptRead").set(activity, null)
                field("toolReceiptRequested").set(activity, null)
                field("toolReceiptRequest").set(activity, null)
                call("publishChat")
            }
        }
        awaitReceipts()
        mainThread {
            assertEquals(ToolActivityUi.SUCCESS, steps().first().activity)
            assertEquals(WorkStepState.OK, steps().first().state)
            assertEquals(0, fixture.opens.get())
        }
        for ((index, label) in listOf("成功", "失败", "未执行").withIndex()) {
            ui.onNodeWithTag("tool-step-header:receipt-$index").assert(SemanticsMatcher("exact final status is visible") {
                it.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].startsWith("$label ·")
            })
        }
        ui.onAllNodesWithText("读取结果").assertCountEquals(0)
    }

    fun testNewToolResultStaysUnconfirmedWithoutResettingFinalRows() {
        mainThread { activity.select(fixture.database.conversations().single()) }
        awaitReceipts()
        val pending = Job()
        mainThread {
            val key = checkNotNull(loadedKey())
            val snapshot = checkNotNull(field("toolReceiptSnapshot").get(activity))
            @Suppress("UNCHECKED_CAST")
            val previous = field("shown").get(activity) as List<Pair<Long, Message>>
            val next = previous.map { (row, message) -> row to if (message.id != historical.id) message else
                message.copy(blocks = message.blocks.map { (it as MessageBlock.Tool).copy() } +
                    MessageBlock.Tool(ToolInvocation(ToolCallId("new-reading"), "search_apps", "{}"), "returned")) }
            try {
                field("shown").set(activity, next)
                field("toolReceiptKey").set(activity, key)
                field("toolReceiptSnapshot").set(activity, snapshot)
                field("toolReceiptRead").get(activity)?.let { (it as Job).cancel() }
                field("toolReceiptRead").set(activity, pending)
                field("toolReceiptRequested").set(activity, currentKey())
                field("toolReceiptRequest").set(activity, Any())
                repeat(3) {
                    call("publishChat")
                    assertEquals(expected + WorkStepState.UNKNOWN, steps().map { it.state })
                    assertEquals(listOf(ToolActivityUi.SUCCESS, ToolActivityUi.FAILED, ToolActivityUi.NOT_EXECUTED,
                        ToolActivityUi.UNKNOWN, ToolActivityUi.UNKNOWN), steps().map { it.activity })
                }
                assertSame(pending, field("toolReceiptRead").get(activity))
                assertTrue(pending.isActive)
                assertEquals(0, fixture.opens.get())
            } finally {
                pending.cancel()
                field("shown").set(activity, previous)
                field("toolReceiptRead").set(activity, null)
                field("toolReceiptRequested").set(activity, null)
                field("toolReceiptRequest").set(activity, null)
                call("publishChat")
            }
        }
        awaitReceipts()
    }

    fun testReturnedResultsWithoutLiveOwnerStayUnknownAcrossBothHostProjectionPathsUntilTheirExactReadCompletes() {
        mainThread { activity.select(fixture.database.conversations().single()) }
        awaitReceipts()
        for (useDisplay in listOf(false, true)) for (status in listOf(RunStatus.RUNNING, RunStatus.COMPLETED)) {
            val pending = Job()
            mainThread {
                @Suppress("UNCHECKED_CAST")
                val previous = field("shown").get(activity) as List<Pair<Long, Message>>
                @Suppress("UNCHECKED_CAST")
                val displayState = field("displayWindow\$delegate").get(activity) as androidx.compose.runtime.MutableState<MessageDisplayWindow?>
                val previousDisplay = displayState.value
                try {
                    val normal = previous.map { (row, message) ->
                        row to if (message.id == historical.id) message.copy(status = status) else message }
                    field("shown").set(activity, normal)
                    displayState.value = if (useDisplay) MessageDisplayWindow(normal.map { (row, message) ->
                        row to MessageDisplayRow(message.id, message.conversationId, message.runId, message.role,
                            message.blocks, message.status, message.error, message.createdAt, emptyList()) }) else null
                    field("toolReceiptRead").get(activity)?.let { (it as Job).cancel() }
                    field("toolReceiptKey").set(activity, null)
                    field("toolReceiptSnapshot").set(activity, null)
                    field("toolReceiptReadFailed").set(activity, false)
                    field("toolReceiptRead").set(activity, pending)
                    field("toolReceiptRequested").set(activity, currentKey())
                    field("toolReceiptRequest").set(activity, Any())
                    repeat(3) {
                        call("publishChat")
                        assertEquals(List(3) { WorkStepState.UNKNOWN }, steps().take(3).map { it.state })
                        assertEquals(List(3) { ToolActivityUi.UNKNOWN }, steps().take(3).map { it.activity })
                        assertTrue(pending.isActive)
                    }
                    assertEquals(if (status == RunStatus.RUNNING) WorkStepState.PENDING else WorkStepState.UNKNOWN,
                        steps().last().state)
                    val snapshot = RetainedToolOutcomes.Snapshot.read(fixture.database, normal.map { it.second }, displayState.value)
                    val request = checkNotNull(field("toolReceiptRequest").get(activity))
                    val complete = MainActivity::class.java.declaredMethods.single { it.name == "completeToolReceipts" }
                        .apply { isAccessible = true }
                    complete.invoke(activity, request, currentKey(), snapshot)
                    assertEquals(expected, steps().map { it.state })
                    assertEquals(listOf(ToolActivityUi.SUCCESS, ToolActivityUi.FAILED, ToolActivityUi.NOT_EXECUTED,
                        ToolActivityUi.UNKNOWN), steps().map { it.activity })
                    field("toolReceiptKey").set(activity, null)
                    field("toolReceiptSnapshot").set(activity, null)
                    field("toolReceiptRead").set(activity, pending)
                    field("toolReceiptRequested").set(activity, currentKey())
                    field("toolReceiptRequest").set(activity, Any())
                    field("toolReceiptReadFailed").set(activity, true)
                    call("publishChat")
                    assertEquals(List(3) { WorkStepState.UNKNOWN }, steps().take(3).map { it.state })
                    assertEquals(List(3) { ToolActivityUi.UNKNOWN }, steps().take(3).map { it.activity })
                    assertEquals(0, fixture.opens.get())
                } finally {
                    pending.cancel()
                    field("shown").set(activity, previous)
                    displayState.value = previousDisplay
                    field("toolReceiptReadFailed").set(activity, false)
                    field("toolReceiptRead").set(activity, null)
                    field("toolReceiptRequested").set(activity, null)
                    field("toolReceiptRequest").set(activity, null)
                    call("publishChat")
                }
            }
            awaitReceipts()
        }
    }

}
