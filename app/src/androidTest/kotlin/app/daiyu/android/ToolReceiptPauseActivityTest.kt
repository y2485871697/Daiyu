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
                ToolRecord(ToolInvocation(ToolCallId("receipt-$index"), "screen", "{}"), disposition, if (disposition == ToolDisposition.UNKNOWN) null else "receipt-$index")
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
        assertEquals(states == expected, key.canDisplayFor(currentKey()))
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
                ToolActivityUi.NOT_EXECUTED, ToolActivityUi.INTERRUPTED) else List(states.size) { ToolActivityUi.UNKNOWN },
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
            key = checkNotNull(loadedKey())
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
            key = checkNotNull(loadedKey())
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
            key = checkNotNull(loadedKey())
            snapshot = checkNotNull(field("toolReceiptSnapshot").get(activity))
            assertEquals(ExecutionPhase.PAUSED, key.execution?.phase)
            assertTrue(fixture.controller.resume(owner))
        }
        assertTrue(second.entered.await(5, TimeUnit.SECONDS))
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

    fun testPendingReadNeverBorrowsEvidenceFromAnotherOwnerOrSourceRevision() {
        mainThread { activity.select(fixture.database.conversations().single()) }
        awaitReceipts()
        mainThread {
            val key = checkNotNull(loadedKey())
            val snapshot = checkNotNull(field("toolReceiptSnapshot").get(activity))
            for (stale in listOf(key.copy(conversation = ConversationId("other")),
                key.copy(data = key.data - 1), key.copy(generation = key.generation - 1),
                key.copy(sources = emptyList()))) {
                pendingFrame(stale, snapshot, List(expected.size) { WorkStepState.UNKNOWN })
            }
        }
        awaitReceipts()
    }
}
