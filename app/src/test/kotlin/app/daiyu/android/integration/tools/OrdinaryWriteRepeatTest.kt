package app.daiyu.android.integration.tools

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.platform.actions.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class OrdinaryWriteRepeatTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "repeat-${UUID.randomUUID()}.db"
    private var store: SQLiteToolLedgerStore? = null
    private val scopes = mutableListOf<RunOrdinaryPlatformTools>()
    private class Backend : PlatformActionBackend {
        var calls = 0
        var volume = 0
        var uncertain = false
        override fun unavailable(kind: PlatformActionKind): String? = null
        override fun execute(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
            calls++
            volume = action.arguments.integer("index").toInt()
            return if (uncertain) ExecutionReceipt.Unknown("submission_unconfirmed")
                else ExecutionReceipt.Confirmed("read_back=$volume")
        }
    }
    private val backend = Backend()
    private fun open(): SQLiteToolLedgerStore = SQLiteToolLedgerStore(context, name).also { store = it }
    private inner class Task(id: String, ledgerStore: SQLiteToolLedgerStore = store ?: open()) {
        val message = Message(MessageId("assistant-$id"), ConversationId("conversation"), RunId(id),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val owner = runnerOwner(message)
        val auth = TaskAuthorization("session", id, emptySet())
        val scope = RunOrdinaryPlatformTools(owner, auth, backend).also { scopes += it }
        val dispatcher = ToolDispatcher(ToolRegistry().apply { scope.tools.forEach(::register) }.snapshot(),
            SessionCapabilities("session", setOf(PlatformActionKind.VOLUME.capability)), ToolLedger(ledgerStore))
        fun call(id: String) = dispatcher.dispatchOwned(auth, listOf(ToolInvocation(ToolCallId(id),
            PlatformActionKind.VOLUME.toolName, """{"stream":"music","index":3}""")), owner) { false }.single().outcome
        fun batch() = dispatcher.dispatchOwned(auth, listOf("one", "two").map { ToolInvocation(ToolCallId(it),
            PlatformActionKind.VOLUME.toolName, """{"stream":"music","index":3}""") }, owner) { false }
    }
    @After fun clean() { scopes.forEach { it.close() }; store?.close(); context.deleteDatabase(name) }

    @Test fun sameCallReplaysButNewCallAndNewTaskReapplyWithReadbackAfterReopen() {
        val first = Task("first")
        assertFalse((first.call("call") as DispatchOutcome.Confirmed).replayed)
        assertTrue((first.call("call") as DispatchOutcome.Confirmed).replayed)
        assertEquals(1, backend.calls)
        backend.volume = 8
        assertFalse((first.call("another") as DispatchOutcome.Confirmed).replayed)
        assertEquals(3, backend.volume)
        first.scope.close(); store!!.close(); store = null
        backend.volume = 9
        val next = Task("second")
        val result = next.call("new-task-call") as DispatchOutcome.Confirmed
        assertFalse(result.replayed); assertEquals("read_back=3", result.payload)
        assertEquals(3, backend.calls); assertEquals(3, backend.volume)
        assertTrue(store!!.transaction { entries -> entries.size == 3 && entries.all { it.repeatable && it.state == LedgerState.CONFIRMED } })
    }
    @Test fun newUnknownAfterConfirmedIsNotHiddenAndSurvivesDatabaseReopen() {
        val first = Task("first")
        assertTrue(first.call("confirmed") is DispatchOutcome.Confirmed)
        backend.uncertain = true
        assertEquals(DispatchOutcome.Unknown("submission_unconfirmed"), first.call("uncertain"))
        first.scope.close(); store!!.close(); store = null
        backend.uncertain = false
        val next = Task("second")
        assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), next.call("fresh"))
        assertEquals(2, backend.calls)
    }
    @Test fun duplicateEffectsWithinOneBatchStillHaveNoSideEffects() {
        val task = Task("batch")
        assertTrue(task.batch().all { it.outcome is DispatchOutcome.NotExecuted })
        assertEquals(0, backend.calls)
    }
    @Test fun changedRequestCannotReuseAnExistingCallId() {
        val task = Task("id-conflict")
        assertTrue(task.call("same") is DispatchOutcome.Confirmed)
        val result = task.dispatcher.dispatchOwned(task.auth, listOf(ToolInvocation(ToolCallId("same"),
            PlatformActionKind.VOLUME.toolName, """{"stream":"music","index":4}""")), task.owner) { false }.single().outcome
        assertEquals(DispatchOutcome.NotExecuted("call_id_conflict"), result)
        assertEquals(1, backend.calls)
    }
    @Test fun legacySchemaIsUpgradedWithoutLosingUnknownProtection() {
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("""CREATE TABLE tool_ledger(session_id TEXT NOT NULL, call_id TEXT NOT NULL, task_id TEXT NOT NULL,
                request_key TEXT NOT NULL, effect_key TEXT, owner TEXT NOT NULL, state TEXT NOT NULL,
                payload TEXT NOT NULL, is_error INTEGER NOT NULL, code TEXT NOT NULL, PRIMARY KEY(session_id,call_id))""")
            db.execSQL("INSERT INTO tool_ledger VALUES('legacy','call','task','request','effect','owner','UNKNOWN','',0,'unknown')")
            db.version = 1
        }
        val upgraded = open()
        assertEquals(LedgerState.UNKNOWN, upgraded.transaction { it.single().state })
        assertFalse(upgraded.transaction { it.single().repeatable })
        upgraded.transaction { entries -> entries.add(LedgerEntry("new", "call", "task", "request", "other", "owner", repeatable = true)) }
        upgraded.close(); store = null
        assertTrue(open().transaction { entries -> entries.single { it.sessionId == "new" }.repeatable })
    }
}
