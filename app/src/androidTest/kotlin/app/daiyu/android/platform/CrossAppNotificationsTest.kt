package app.daiyu.android.platform

import android.test.InstrumentationTestCase
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.testContext
import org.json.JSONObject
import java.util.UUID

/** Synthetic snapshots only: no real listener grant, system notification or personal content. */
@Suppress("DEPRECATION")
class CrossAppNotificationsTest : InstrumentationTestCase() {
    private lateinit var database: ConversationDatabase
    private lateinit var store: NotificationHistoryStore
    private lateinit var host: CrossAppNotifications
    private var granted = false
    private var now = 2_000_000_000L
    private var reads = 0
    private var rows = emptyList<JSONObject>()
    private var duringRead: (() -> Unit)? = null
    private val current = PlatformReadTool.CROSS_APP_NOTIFICATIONS
    private val history = PlatformReadTool.NOTIFICATION_HISTORY
    private val source = object : CrossAppNotifications.NotificationSnapshotSource {
        override fun active(): List<JSONObject> { reads++; duringRead?.invoke(); return rows }
    }
    private val policy get() = DeviceResultPolicy(setOf(current.toolName, history.toolName), setOf(current.toolName, history.toolName))
    override fun setUp() {
        super.setUp()
        database = ConversationDatabase(testContext(instrumentation.targetContext, "notifications-${UUID.randomUUID()}-"))
        store = NotificationHistoryStore(database)
        host = CrossAppNotifications({ granted }, store, { now })
    }
    override fun tearDown() { database.close(); super.tearDown() }
    private fun row(pkg: String = "fixture.app", text: String = "synthetic") = JSONObject().put("package", pkg).put("title", "fixture").put("text", text)
    private fun read(tool: PlatformReadTool = current, args: String = "{}", p: DeviceResultPolicy = policy) = host.read(tool, tool.schema.parse(args), p) { false }
    private fun error(result: JSONObject, expected: String) { assertFalse(result.getBoolean("ok")); assertEquals(expected, result.getString("error")); assertFalse(result.has("data")) }
    private fun connect() { granted = true; host.connected(source) }

    fun testNoSystemGrantAndNotConnectedAreNotEmptySuccess() {
        error(read(), "notification_access_required"); assertEquals(0, reads)
        try { host.record(true); fail("cannot enable recording without actual access and connection") } catch (_: IllegalStateException) { }
        assertFalse(store.enabled())
        granted = true
        error(read(), "notification_listener_not_connected"); assertEquals(0, reads)
        connect()
        assertEquals(0, read().getJSONObject("data").getJSONArray("items").length())
        assertEquals(1, reads)
    }
    fun testBothDestinationConsentsRequiredBeforeAnySnapshotRead() {
        connect(); rows = listOf(row())
        error(read(p = DeviceResultPolicy()), "private_result_consent_required")
        error(read(p = DeviceResultPolicy(setOf(current.toolName))), "private_result_consent_required")
        error(read(p = DeviceResultPolicy(emptySet(), setOf(current.toolName))), "private_result_consent_required")
        assertEquals(0, reads)
        assertEquals(1, read().getJSONObject("data").getJSONArray("items").length())
    }
    fun testRevocationDuringSnapshotDiscardsContentAndFencesCachedAdmission() {
        connect(); rows = listOf(row(text = "must-not-deliver"))
        host.record(true); host.posted(source) { row() }
        duringRead = { granted = false }
        error(read(), "notification_access_required")
        assertEquals("notification_access_required", host.admission(current, policy))
        assertFalse(store.enabled()); assertTrue(store.rows(now, 0, now).isEmpty())
        granted = true
        error(read(), "notification_listener_not_connected")
    }
    fun testDisconnectDuringSnapshotRejectsAndDoesNotReuseOldSource() {
        connect(); duringRead = { host.disconnected(source) }
        error(read(), "notification_listener_changed")
        error(read(), "notification_listener_not_connected")
    }
    fun testHistoryRequiresSeparateOptInDoesNotBackfillAndOffClears() {
        connect(); rows = listOf(row())
        var constructed = 0
        host.posted(source) { constructed++; row() }
        assertEquals(0, constructed)
        error(read(history), "notification_history_not_recording")
        host.record(true)
        val empty = read(history).getJSONObject("data")
        assertEquals(0, empty.getJSONArray("items").length()); assertFalse(empty.getBoolean("empty_means_no_notifications"))
        assertEquals(now, empty.getLong("recording_since_ms")); assertEquals(0, reads)
        now++
        host.posted(source) { row() }
        assertEquals(1, read(history).getJSONObject("data").getJSONArray("items").length())
        host.record(false)
        assertEquals("notification_history_not_recording", host.admission(history, policy))
        error(read(history), "notification_history_not_recording")
        assertTrue(store.rows(now, 0, now).isEmpty())
        host.posted(source) { fail("off must not extract fields"); row() }
    }
    fun testExactPackageTextTimeAndLimitFiltering() {
        connect(); rows = listOf(row("fixture.one", "needle"), row("fixture.two", "needle"), row("fixture.one", "other"))
        val filtered = read(args = """{"package":"fixture.one","query":"needle","limit":1}""").getJSONObject("data")
        assertEquals(1, filtered.getJSONArray("items").length())
        assertEquals("fixture.one", filtered.getJSONArray("items").getJSONObject(0).getString("package"))
        host.record(true); host.posted(source) { row("fixture.old") }; now += 10
        host.posted(source) { row("fixture.new") }
        assertEquals(1, read(history, """{"start_ms":$now,"end_ms":$now}""").getJSONObject("data").getJSONArray("items").length())
        error(read(history, """{"start_ms":0,"end_ms":$now}"""), "invalid_time_window")
    }
    fun testVersion16And17UpgradeInstallsDisabledCaptureWithoutRewritingConversations() {
        for (version in listOf(16, 17)) {
            val context = testContext(instrumentation.targetContext, "notifications-upgrade-$version-${UUID.randomUUID()}-")
            val old = ConversationDatabase(context)
            val schemas = listOf("runtime_preparations", "context_usage")
            val preservedSchemas = old.writableDatabase.let { db -> schemas.associateWith { name ->
                db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use {
                    check(it.moveToFirst()); it.getString(0)
                }
            } }
            old.writableDatabase.apply {
                execSQL("INSERT INTO conversations(id,title,draft,updated) VALUES('fixture','synthetic','draft',123)")
                execSQL("DROP TABLE notification_records"); execSQL("DROP TABLE notification_capture")
                this.version = version
            }
            old.close()
            val upgraded = ConversationDatabase(context)
            try {
                assertEquals(22, upgraded.readableDatabase.version)
                assertFalse(NotificationHistoryStore(upgraded).enabled())
                upgraded.readableDatabase.rawQuery("SELECT title,draft,updated FROM conversations WHERE id='fixture'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("synthetic", it.getString(0)); assertEquals("draft", it.getString(1)); assertEquals(123L, it.getLong(2))
                }
                for ((name, schema) in preservedSchemas) {
                    upgraded.readableDatabase.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use {
                        assertTrue(it.moveToFirst()); assertEquals(schema, it.getString(0))
                    }
                }
                assertTrue(NotificationHistoryStore(upgraded).rows(now, 0, now).isEmpty())
            } finally { upgraded.close() }
        }
    }
    fun testSevenDayCountAndByteBudgetsAndReopenPersistOnlyOptInEpoch() {
        connect(); host.record(true)
        repeat(600) { host.posted(source) { row(text = "x".repeat(3000)) } }
        val count = database.readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(bytes),0) FROM notification_records", null).use {
            it.moveToFirst(); assertTrue(it.getLong(1) <= NotificationHistoryStore.MAX_BYTES); it.getInt(0)
        }
        assertTrue(count in 1..NotificationHistoryStore.MAX_ROWS)
        host.record(false); host.record(true)
        repeat(600) { host.posted(source) { row() } }
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM notification_records", null).use {
            it.moveToFirst(); assertEquals(NotificationHistoryStore.MAX_ROWS, it.getInt(0))
        }
        host.posted(source) { row(text = "x".repeat(NotificationHistoryStore.MAX_ROW_BYTES + 1)) }
        assertEquals(NotificationHistoryStore.MAX_ROWS, store.rows(now, 0, now).size)
        val recovered = CrossAppNotifications({ granted }, NotificationHistoryStore(database), { now })
        recovered.connected(source)
        assertTrue(recovered.recording())
        now += NotificationHistoryStore.RETENTION_MS + 1
        recovered.maintain()
        assertTrue(store.rows(now, 0, now).isEmpty())
        recovered.record(false)
        assertFalse(store.enabled())
    }
}
