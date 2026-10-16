package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageHistorySegment
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.videos.MessageVideoInput

/** Runs the same strict boundary on Android, not just the JVM org.json implementation. */
@Suppress("DEPRECATION")
class MessageVideoInputCodecAndroidTest : InstrumentationTestCase() {
    private val user = Message(MessageId("user"), ConversationId("会话"), RunId("run"),
        Message.USER_ROLE, emptyList(), RunStatus.COMPLETED)
    private val owner = MessageVideoOwner("会话", "user")
    private val ref = MessageVideoReference(owner, "12345678-1234-1234-1234-123456789abc",
        "video/webm", 987654, "ab".repeat(32), 3210, 640, 480)
    private fun encode() = MessageVideoInputCodec.encode(user, listOf(MessageVideoInput(owner, listOf(ref))))
    private fun reject(encoded: String, failure: MessageVideoCodecFailure? = null, message: Message = user) {
        try {
            MessageVideoInputCodec.decode(message, encoded)
            fail("Sidecar must fail closed")
        } catch (e: MessageVideoCodecException) {
            if (failure != null) assertEquals(failure, e.failure)
            assertEquals("Message video sidecar: ${e.failure.name}", e.message)
            assertNull(e.cause)
        }
    }

    fun testAndroidRoundTripPreservesAllMetadataAndHistoryBinding() {
        assertEquals(ref, MessageVideoInputCodec.decode(user, encode()).single().references.single())
        val physical = user.copy(id = MessageId("assistant"), role = Message.ASSISTANT_ROLE,
            historySegments = listOf(MessageHistorySegment(user.id, Message.USER_ROLE, emptyList()),
                MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE, emptyList())))
        val encoded = MessageVideoInputCodec.encode(physical, listOf(MessageVideoInput(owner, listOf(ref))))
        assertEquals(owner, MessageVideoInputCodec.decode(physical, encoded).single().owner)
        reject(encoded, MessageVideoCodecFailure.OWNER_MISMATCH, physical.copy(runId = RunId("other")))
        reject(encoded.replace("\"message\":\"user\"", "\"message\":\"assistant\""),
            MessageVideoCodecFailure.OWNER_MISMATCH, physical)
    }

    fun testAndroidDoesNotAcceptLenientJsonOrNumericCoercion() {
        val encoded = encode()
        listOf("987654.0", "987654.5", "9.87654e5", "\"987654\"", "9223372036854775808",
            "-9223372036854775809", "0987654", "+987654", "null", "true").forEach {
            reject(encoded.replace("\"bytes\":987654", "\"bytes\":$it"))
        }
        listOf(encoded.replaceFirst("{", "{\"version\":1,"),
            encoded.replaceFirst("{", "{\"\\u0076ersion\":1,"),
            encoded.replaceFirst("\"version\"", "'version'"),
            "/*comment*/$encoded", "$encoded trailing", encoded.dropLast(1) + ",}").forEach {
            reject(it, MessageVideoCodecFailure.INVALID_JSON)
        }
        reject(encoded.replace("\"width\":640", "\"width\":4294967936"), MessageVideoCodecFailure.INVALID_REFERENCE)
        reject(encoded.replace("\"sha256\":", "\"path\":\"/private/secret\",\"sha256\":"),
            MessageVideoCodecFailure.INVALID_FIELDS)
        reject(encoded.replace("\"message\":\"user\"", "\"message\":\"content://secret\""),
            MessageVideoCodecFailure.INVALID_IDENTITY)
        reject(encoded.replace("user", "user\\ud800"), MessageVideoCodecFailure.INVALID_JSON)
    }

    fun testBoundedUtf8AndSQLiteChunkReaderKeepSplitUnicodeIntact() {
        // Isolated reader fixture uses an already-allowlisted column. This is NOT DB sidecar wiring.
        val encoded = encode()
        val firstUnicode = encoded.indexOf('会')
        assertTrue(firstUnicode > 0)
        val split = " ".repeat(65535 - firstUnicode) + encoded
        assertEquals(0xe4, split.toByteArray(Charsets.UTF_8)[65535].toInt() and 255)
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, history_segments TEXT)")
            val values = ContentValues().apply { put("id", "row"); put("history_segments", split) }
            assertTrue(db.insertOrThrow("messages", null, values) != -1L)
            val loaded = SQLiteText.read(db, "messages", "history_segments", "id", "row")!!
            assertEquals(split, loaded)
            assertEquals(ref, MessageVideoInputCodec.decode(user, loaded).single().references.single())
        } finally { db.close() }
        val oversized = "\"" + "界".repeat(MessageVideoInputCodec.MAX_UTF8_BYTES / 3 + 1)
        assertTrue(oversized.length < MessageVideoInputCodec.MAX_UTF8_BYTES)
        reject(oversized, MessageVideoCodecFailure.TOO_LARGE)
    }
}
