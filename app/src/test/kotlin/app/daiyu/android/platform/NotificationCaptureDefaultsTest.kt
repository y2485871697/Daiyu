package app.daiyu.android.platform

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic posted events only: no system listener, notification provider or real user content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, manifest = Config.NONE)
class NotificationCaptureDefaultsTest {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var store: NotificationHistoryStore
    private lateinit var host: CrossAppNotifications
    private var granted = false
    private var now = 100_000L
    private val source = object : CrossAppNotifications.NotificationSnapshotSource {
        override fun active(): List<JSONObject> = error("history must not backfill active notifications")
    }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        database = ConversationDatabase(context)
        store = NotificationHistoryStore(database)
        granted = false
        now = 100_000L
        host = CrossAppNotifications({ granted }, store, { now })
    }
    @After fun cleanup() { database.close(); context.deleteDatabase("conversations.db") }
    private fun posted() = host.posted(source) { JSONObject().put("package", "synthetic.fixture").put("text", "synthetic event") }
    private fun rows() = store.rows(now, 0, now)

    @Test fun defaultIsArmedButCaptureRequiresBothRealGrantAndConnectionAndStartsAtConnection() {
        assertTrue(store.enabled())
        assertEquals(0L, store.since())
        assertFalse(host.recording())
        host.connected(source) // A connected callback without system authorization grants nothing.
        posted()
        assertTrue(rows().isEmpty())
        assertEquals(0L, store.since())
        granted = true
        assertFalse(host.recording())
        posted() // No matching live source after revocation.
        assertTrue(rows().isEmpty())
        host.connected(source)
        assertTrue(host.recording())
        assertEquals(now, store.since())
        posted()
        assertEquals(1, rows().size)
    }

    @Test fun ordinaryReconnectKeepsHistoryAndOriginalEpochInsteadOfReenablingAndErasing() {
        granted = true
        host.connected(source)
        val start = store.since()
        posted()
        host.disconnected(source)
        now += 1_000
        assertFalse(host.recording())
        posted()
        assertEquals(1, rows().size)
        host.connected(source)
        assertEquals(start, store.since())
        assertEquals(1, rows().size)
        posted()
        assertEquals(2, rows().size)
    }

    @Test fun revokeErasesAndRegrantStartsNewEpochWithoutOverwritingExplicitOff() {
        granted = true
        host.connected(source)
        posted()
        granted = false
        host.maintain()
        assertTrue(rows().isEmpty())
        assertTrue(store.enabled()) // OS loss is not a saved opt-out.
        assertEquals(0L, store.since())
        granted = true
        now += 10_000
        host.connected(source)
        assertEquals(now, store.since())
        host.record(false)
        host.disconnected(source)
        now += 1_000
        host.connected(source)
        assertFalse(host.recording())
        assertEquals(0L, store.since())
        posted()
        assertTrue(rows().isEmpty())
        assertFalse(NotificationHistoryStore(database).enabled())
    }

    @Test fun defaultsRepairMissingSingletonAndMigrateLegacyOffOnce() {
        database.writableDatabase.delete("notification_capture", null, null)
        assertTrue(NotificationHistoryStore(database).enabled())
        store.setEnabled(false, now)
        NotificationHistoryStore.installSchema(database.writableDatabase)
        assertFalse(NotificationHistoryStore(database).enabled())
        SQLiteDatabase.create(null).use { old ->
            old.execSQL("CREATE TABLE notification_capture(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL DEFAULT 0,since_ms INTEGER NOT NULL DEFAULT 0)")
            old.execSQL("INSERT INTO notification_capture VALUES(1,0,0)")
            NotificationHistoryStore.installSchema(old)
            old.rawQuery("SELECT enabled,since_ms FROM notification_capture WHERE id=1", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(0L, it.getLong(1))
            }
        }
    }

    @Test fun currentSchemaReopenMigratesLegacyOffOnceAndPreservesLaterExplicitOff() {
        database.writableDatabase.execSQL("DELETE FROM notification_capture_policy")
        database.writableDatabase.execSQL("UPDATE notification_capture SET enabled=0,since_ms=123 WHERE id=1")
        database.close()
        database = ConversationDatabase(context)
        store = NotificationHistoryStore(database)
        assertTrue(store.enabled())
        assertEquals(0L, store.since())
        assertTrue(store.rows(now, 0, now).isEmpty())
        store.setEnabled(false, now)
        database.close()
        database = ConversationDatabase(context)
        store = NotificationHistoryStore(database)
        assertFalse(store.enabled())
        granted = true
        host = CrossAppNotifications({ granted }, store, { now })
        host.connected(source)
        posted()
        assertFalse(host.recording())
        assertTrue(rows().isEmpty())
    }

    @Test fun migrationRetainsExistingEnabledEpochAndCapturedRecords() {
        granted = true
        host.connected(source)
        posted()
        val originalEpoch = store.since()
        database.writableDatabase.execSQL("DELETE FROM notification_capture_policy")
        val migrated = NotificationHistoryStore(database)
        assertTrue(migrated.enabled())
        assertEquals(originalEpoch, migrated.since())
        assertEquals(1, migrated.rows(now, 0, now).size)
    }

    @Test fun bothDestinationsAndSystemGrantRemainRequiredAtAdmission() {
        val tool = PlatformReadTool.NOTIFICATION_HISTORY
        val both = DeviceResultPolicy(setOf(tool.toolName), setOf(tool.toolName))
        assertEquals("notification_access_required", host.admission(tool, both))
        granted = true
        assertEquals("notification_listener_not_connected", host.admission(tool, both))
        host.connected(source)
        assertEquals("private_result_consent_required", host.admission(tool, DeviceResultPolicy(setOf(tool.toolName))))
        assertNull(host.admission(tool, both))
        host.record(false)
        assertEquals("notification_history_not_recording", host.admission(tool, both))
    }
}
