package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.timeline.ChatTimelineEntry
import app.daiyu.android.timeline.ChatTimelineStore
import app.daiyu.android.timeline.TimelineAnchor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

/** Real SQLite probes: malformed message JSON makes any unnecessary history decode fail. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ContextTimelineColdStartTest {
    private lateinit var database: ConversationDatabase
    private lateinit var service: ContextService
    private lateinit var blobRoot: File

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        database = ConversationDatabase(context)
        blobRoot = File(context.cacheDir, "timeline-cold-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val blobContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = blobRoot
        }
        service = ContextService(database, PrivateBlobs(blobContext))
    }

    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        RuntimeEnvironment.getApplication().deleteDatabase("conversations.db")
        if (::blobRoot.isInitialized) blobRoot.deleteRecursively()
    }

    private fun seed(): ConversationId {
        val owner = database.create("default").id
        repeat(4) { index ->
            val user = Message(MessageId("user-$index-${owner.value}"), owner, RunId("run-$index-${owner.value}"),
                Message.USER_ROLE, listOf(MessageBlock.Text("question", "question $index ".repeat(200))), RunStatus.COMPLETED)
            val reply = user.copy(id = MessageId("reply-$index-${owner.value}"), role = Message.ASSISTANT_ROLE,
                status = RunStatus.RUNNING)
            database.send(user, reply)
            database.save(reply.copy(status = RunStatus.COMPLETED))
        }
        return owner
    }

    private fun event(owner: ConversationId): ChatTimelineEntry.Reconnect {
        val messages = database.messages(owner).map { it.second }
        return ChatTimelineEntry.Reconnect("reconnect-${owner.value}", TimelineAnchor.at(messages, 1),
            10L, "stopped", 100L, "NETWORK", 0, "model")
    }

    private fun poisonMessages(owner: ConversationId) {
        database.writableDatabase.execSQL("UPDATE messages SET blocks='not-json' WHERE conversation_id=?", arrayOf(owner.value))
    }

    private fun assertHistoryDecodeRequired(owner: ConversationId) {
        poisonMessages(owner)
        try {
            service.timeline(owner)
            fail("Timeline evidence must still validate the authoritative message anchors")
        } catch (_: org.json.JSONException) {
            // Only the deliberate JSON decode failure is accepted, not an arbitrary SQL error.
        }
    }

    @Test fun noEvidenceReturnsEmptyWithoutDecodingAnyMessages() {
        val owner = seed()
        poisonMessages(owner)
        assertTrue(service.timeline(owner).isEmpty())
        assertTrue(service.timeline(ConversationId("missing-conversation")).isEmpty())
    }

    @Test fun foreignSavedAndLegacyEvidenceDoesNotTriggerHistoryDecode() {
        val owner = seed()
        val foreign = seed()
        val archive = checkNotNull(service.compress(foreign, CompressionPolicy(keepTurns = 1),
            ContextSummarizer { "foreign summary" }))
        ChatTimelineStore.write(database.writableDatabase, event(foreign))
        // A foreign legacy blob must not be opened, even when its durable entry is absent.
        database.writableDatabase.delete("chat_timeline_events", "id=?", arrayOf(archive))
        database.writableDatabase.execSQL("UPDATE context_archives SET blob_hash=? WHERE id=?", arrayOf("0".repeat(64), archive))
        database.writableDatabase.execSQL("UPDATE chat_timeline_events SET payload='not-json' WHERE conversation_id=?", arrayOf(foreign.value))
        poisonMessages(owner)
        assertTrue(service.timeline(owner).isEmpty())
    }

    @Test fun savedEvidenceKeepsAnchorFilteringAndHistoryValidation() {
        val owner = seed()
        val saved = event(owner)
        ChatTimelineStore.write(database.writableDatabase, saved)
        ChatTimelineStore.write(database.writableDatabase, saved.copy(id = "orphan-event",
            anchor = saved.anchor.copy(messageId = "missing-message")))
        assertEquals(listOf(saved), service.timeline(owner))
        assertHistoryDecodeRequired(owner)
    }

    @Test fun legacyArchiveKeepsSummaryAnchorAndHistoryValidation() {
        val owner = seed()
        val archive = checkNotNull(service.compress(owner, CompressionPolicy(keepTurns = 1),
            ContextSummarizer { "retained legacy summary" }))
        val saved = service.timeline(owner).single() as ChatTimelineEntry.Compression
        database.writableDatabase.delete("chat_timeline_events", "id=?", arrayOf(archive))
        val legacy = service.timeline(owner).single() as ChatTimelineEntry.Compression
        assertEquals(saved.id, legacy.id)
        assertTrue(legacy.anchor.listKey in TimelineAnchor.keys(database.messages(owner).map { it.second }))
        assertTrue(legacy.messages > 0)
        assertEquals("retained legacy summary", legacy.summary)
        assertNull(legacy.model)
        assertHistoryDecodeRequired(owner)
    }
}
