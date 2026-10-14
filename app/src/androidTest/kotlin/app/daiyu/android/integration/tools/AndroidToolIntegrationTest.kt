package app.daiyu.android.integration.tools

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.tools.*
import org.json.JSONObject
import java.util.UUID

@Suppress("DEPRECATION")
class AndroidToolIntegrationTest : InstrumentationTestCase() {
    private val databaseName = "tool-integration-test-${UUID.randomUUID()}.db"
    private val context get() = instrumentation.targetContext
    override fun tearDown() {
        try { context.deleteDatabase(databaseName) } finally { super.tearDown() }
    }
    private fun call(id: String, name: String, arguments: String = "{}") = ToolInvocation(ToolCallId(id), name, arguments)
    private fun readDispatcher(store: ToolLedgerStore, allowed: Set<ReadOnlyTool>) = ToolDispatcher(
        AndroidToolRegistry.create(context).snapshot(), SessionCapabilities("session", AndroidToolRegistry.capabilitiesFor(allowed)), ToolLedger(store))
    private val task get() = TaskAuthorization("session", "task")

    fun testRealReadOnlyBackendAndCapabilityArgumentFailures() {
        SQLiteToolLedgerStore(context, databaseName).use { store ->
            val dispatcher = readDispatcher(store, ReadOnlyTool.entries.toSet())
            val results = dispatcher.dispatch(task, listOf(call("battery", "get_battery_status"), call("status", "device_status")))
            assertEquals(listOf(ToolCallId("battery"), ToolCallId("status")), results.map { it.callId })
            val battery = JSONObject((results[0].outcome as DispatchOutcome.Confirmed).payload)
            assertTrue(battery.getBoolean("ok")); assertTrue(battery.getInt("percent") in 0..100)
            assertTrue(battery.getLong("observed_at") > 0)
            val status = JSONObject((results[1].outcome as DispatchOutcome.Confirmed).payload)
            assertTrue(status.getBoolean("ok")); assertTrue(status.getJSONObject("android").getInt("api_level") > 0)
            assertTrue(status.has("memory") && status.has("storage") && status.has("battery"))
            val disabled = readDispatcher(store, emptySet()).dispatch(task, listOf(call("disabled", "device_status"))).single()
            assertEquals("capability_disabled", (disabled.outcome as DispatchOutcome.NotExecuted).code)
            val invalid = dispatcher.dispatch(task, listOf(call("valid-but-not-run", "get_battery_status"), call("invalid", "device_status", "{\"extra\":true}")))
            assertTrue(invalid.all { (it.outcome as DispatchOutcome.NotExecuted).code == "invalid_arguments" })
            assertFalse(store.transaction { entries -> entries.any { it.callId == "valid-but-not-run" } })
        }
    }

    fun testConfirmedReadReceiptsSurviveCloseAndReopenWithoutResampling() {
        var payload = ""
        SQLiteToolLedgerStore(context, databaseName).use { store ->
            val receipt = readDispatcher(store, setOf(ReadOnlyTool.BATTERY)).dispatch(task, listOf(call("battery", "get_battery_status"))).single().outcome as DispatchOutcome.Confirmed
            assertFalse(receipt.replayed); payload = receipt.payload
        }
        SQLiteToolLedgerStore(context, databaseName).use { store ->
            val replay = readDispatcher(store, setOf(ReadOnlyTool.BATTERY)).dispatch(task, listOf(call("battery", "get_battery_status"))).single().outcome as DispatchOutcome.Confirmed
            assertTrue(replay.replayed); assertEquals(payload, replay.payload)
        }
    }

    fun testDurableUnknownProtectsNewIdAndEquivalentBackendAfterRecovery() {
        var executions = 0
        val effect = WriteEffect("test.notes", "replace", "a", "hello")
        val schema = ToolSchema(listOf(ArgumentField("target", ArgumentType.Text())))
        fun adapter(name: String, unknown: Boolean) = object : RegisteredTool {
            override val definition = ToolDefinition(name, 1, "Test adapter", "test.write", ToolEffect.WRITE, schema)
            override fun prepare(arguments: ToolArguments): PreparedTool {
                check(arguments.text("target") == "a")
                return PreparedTool(effect) {
                    executions++
                    if (unknown) throw IllegalStateException("connection lost after send")
                    ExecutionReceipt.Confirmed("must not run")
                }
            }
        }
        val authorization = TaskAuthorization("session", "task", setOf(effect))
        fun dispatcher(store: ToolLedgerStore, tool: RegisteredTool) = ToolDispatcher(
            AndroidToolRegistry.create(context, listOf(tool)).snapshot(), SessionCapabilities("session", setOf("test.write")), ToolLedger(store))
        SQLiteToolLedgerStore(context, databaseName).use { store ->
            val result = dispatcher(store, adapter("write_gui", true)).dispatch(authorization, listOf(call("original", "write_gui", "{\"target\":\"a\"}")))
            assertTrue(result.single().outcome is DispatchOutcome.Unknown)
        }
        SQLiteToolLedgerStore(context, databaseName).use { store ->
            val result = dispatcher(store, adapter("write_http", false)).dispatch(authorization, listOf(call("new-id", "write_http", "{\"target\":\"a\"}")))
            assertTrue(result.single().outcome is DispatchOutcome.Unknown)
            assertEquals(1, store.transaction { it.size })
        }
        assertEquals(1, executions)
    }

    fun testInjectedHostHelperSharesRecoveryAndDoesNotAllowNestedExecution() {
        val helper = object : android.database.sqlite.SQLiteOpenHelper(context, databaseName, null, 1) {
            override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
                db.execSQL("CREATE TABLE host_state (value TEXT NOT NULL)")
                db.execSQL("INSERT INTO host_state VALUES ('host')")
            }
            override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("test_migration")
        }
        try {
            val ledger = SQLiteToolLedgerStore(helper)
            val dispatcher = readDispatcher(ledger, setOf(ReadOnlyTool.BATTERY))
            helper.writableDatabase.beginTransaction()
            try {
                val result = dispatcher.dispatch(task, listOf(call("nested", "get_battery_status"))).single().outcome
                assertEquals("ledger_unavailable", (result as DispatchOutcome.NotExecuted).code)
                helper.writableDatabase.setTransactionSuccessful()
            } finally { helper.writableDatabase.endTransaction() }
            val confirmed = dispatcher.dispatch(task, listOf(call("battery", "get_battery_status"))).single().outcome
            assertTrue(confirmed is DispatchOutcome.Confirmed)
            ledger.close() // Does not close the injected host helper.
            assertTrue(helper.writableDatabase.isOpen)
            SQLiteToolLedgerStore(helper).use { reopened ->
                assertEquals("battery", reopened.transaction { it.single().callId })
                helper.writableDatabase.rawQuery("SELECT value FROM host_state", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("host", it.getString(0))
                }
            }
        } finally { helper.close() }
    }

    fun testTransactionRollbackAndMultipleStoreInstancesKeepLedger() {
        SQLiteToolLedgerStore(context, databaseName).use { first ->
            SQLiteToolLedgerStore(context, databaseName).use { second ->
                first.transaction { it.add(LedgerEntry("session", "call", "task", "request", "effect", "owner")) }
                assertEquals(LedgerState.UNKNOWN, second.transaction { it.single().state })
                try {
                    second.transaction<Unit> { it.clear(); throw IllegalStateException("rollback") }
                    fail("expected rollback")
                } catch (_: IllegalStateException) {}
                assertEquals("call", first.transaction { it.single().callId })
            }
        }
        SQLiteToolLedgerStore(context, databaseName).use { reopened ->
            assertEquals(LedgerState.UNKNOWN, reopened.transaction { it.single().state })
        }
    }
}
