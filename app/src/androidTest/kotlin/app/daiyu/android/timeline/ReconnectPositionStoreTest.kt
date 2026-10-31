package app.daiyu.android.timeline

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase

@Suppress("DEPRECATION")
class ReconnectPositionStoreTest : InstrumentationTestCase() {
    fun testSameIdPauseResumeAndSuccessCannotRewriteTheFirstCoordinateOrCreationTime() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO conversations(id) VALUES('c')")
            ChatTimelineStore.install(db)
            for ((index, position) in listOf(null, ReconnectPosition(3)).withIndex()) {
                val first = ChatTimelineEntry.Reconnect("event-$index", TimelineAnchor("c", "r", "a", 0),
                    7L, "running", 10L, "network", 0, "model", position)
                ChatTimelineStore.write(db, first)
                var elapsed = first.elapsedMs
                for (status in listOf("stopped", "running", "succeeded")) {
                    elapsed += 10
                    ChatTimelineStore.write(db, first.copy(status = status, elapsedMs = elapsed,
                        created = 100L, anchor = first.anchor.copy(messageId = "other", segment = 5),
                        position = ReconnectPosition(999)))
                    val saved = ChatTimelineStore.read(db, "c").filterIsInstance<ChatTimelineEntry.Reconnect>().single { it.id == first.id }
                    assertEquals(first.anchor, saved.anchor)
                    assertEquals(first.created, saved.created)
                    assertEquals(first.position, saved.position)
                    assertEquals(status, saved.status)
                    assertEquals(elapsed, saved.elapsedMs)
                }
                ChatTimelineStore.write(db, first.copy(position = ReconnectPosition(1)))
                assertEquals("succeeded", ChatTimelineStore.read(db, "c").filterIsInstance<ChatTimelineEntry.Reconnect>().single { it.id == first.id }.status)
            }
            assertEquals(2, ChatTimelineStore.read(db, "c").size)
        }
    }
}
