package app.daiyu.android.storage

import android.content.ContentValues
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.model.generation.*
import app.daiyu.android.testContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class SQLiteGenerationAttemptStoreTest : InstrumentationTestCase() {
    private val helpers = mutableListOf<ConversationDatabase>()
    private val contexts = mutableListOf<android.content.Context>()
    private data class Fixture(val context: android.content.Context, val db: ConversationDatabase,
        val assistant: Message, val binding: GenerationAttemptBinding) {
        val attempt get() = GenerationAttempt(binding.identity("item1"), "a".repeat(64), GenerationProtocol.OPENAI_IMAGES_JSON)
        fun store(helper: ConversationDatabase = db) = SQLiteGenerationAttemptStore({ helper.writableDatabase }, binding)
    }

    private fun helper(context: android.content.Context) = ConversationDatabase(context).also { helpers.add(it) }
    private fun fixture(history: Boolean = false): Fixture {
        val context = testContext(instrumentation.targetContext, "generation-${UUID.randomUUID()}-").also { contexts.add(it) }
        val db = helper(context)
        SQLiteGenerationAttemptStore.installSchema(db.writableDatabase)
        val conversation = db.create(assistantId = db.selectedAssistantId())
        val user = Message(MessageId(UUID.randomUUID().toString()), conversation.id, RunId(UUID.randomUUID().toString()),
            "user", listOf(MessageBlock.Text("question", "generate")), RunStatus.COMPLETED)
        val call = MessageBlock.Tool(ToolInvocation(ToolCallId("tool-call"), "generate_media", "{}"))
        val assistantId = MessageId(UUID.randomUUID().toString())
        val assistant = user.copy(id = assistantId, role = "assistant", blocks = if (history) emptyList() else listOf(call),
            status = RunStatus.RUNNING, historySegments = if (history)
                listOf(MessageHistorySegment(assistantId, "assistant", listOf(call))) else emptyList())
        db.send(user, assistant)
        return Fixture(context, db, assistant, GenerationAttemptBinding(conversation.id.value, assistantId.value,
            user.runId.value, call.invocation.id.value, call.invocation.name))
    }

    private fun rejects(work: () -> Unit) {
        try { work() } catch (_: Exception) { return }
        fail("expected fail-closed rejection")
    }
    private fun existing(f: Fixture, attempt: GenerationAttempt = f.attempt): GenerationReservation.Existing =
        f.store().reserve(attempt) as GenerationReservation.Existing
    private fun outputs(f: Fixture): List<GenerationOutput> =
        (existing(f).submission as Submission.Completed).outputs

    override fun tearDown() {
        try {
            helpers.forEach { it.close() }
            contexts.forEach { it.deleteDatabase("conversations.db") }
        } finally { super.tearDown() }
    }

    fun testCommittedBeforeStartedAndReopenDoesNotReadmit() {
        val f = fixture(); val observer = helper(f.context)
        assertSame(GenerationReservation.Started, f.store().reserve(f.attempt))
        // Another helper sees the row before any finish, not just this connection's uncommitted write.
        assertNull((f.store(observer).reserve(f.attempt) as GenerationReservation.Existing).submission)
        f.db.close(); observer.close()
        assertNull(existing(f).submission)
        val changed = f.attempt.copy(fingerprint = "b".repeat(64))
        assertEquals(f.attempt.fingerprint, existing(f, changed).fingerprint)
        rejects { f.store().finish(changed, Submission.NotExecuted(GenerationFailure.CANCELLED)) }
        assertNull(existing(f).submission)
    }

    fun testIndependentHelpersRaceOnlyOneStarted() {
        val f = fixture(); val other = helper(f.context)
        // Open before racing so this is write serialization, not helper creation serialization.
        other.writableDatabase
        val gate = CountDownLatch(1); val done = CountDownLatch(2)
        val first = AtomicReference<GenerationReservation>(); val second = AtomicReference<GenerationReservation>()
        val failure = AtomicReference<Throwable>()
        listOf(f.store() to first, f.store(other) to second).forEach { (store, result) ->
            Thread {
                try { check(gate.await(5, TimeUnit.SECONDS)); result.set(store.reserve(f.attempt)) }
                catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
            }.start()
        }
        gate.countDown(); assertTrue(done.await(10, TimeUnit.SECONDS)); failure.get()?.let { throw it }
        assertEquals(1, listOf(first.get(), second.get()).count { it === GenerationReservation.Started })
        assertEquals(1, listOf(first.get(), second.get()).count { it is GenerationReservation.Existing })
        assertTrue(f.store().reserve(f.attempt) is GenerationReservation.Existing)
    }

    fun testIndependentFinishesSerializeWithoutLosingOutputIndices() {
        val f = fixture(); val other = helper(f.context)
        f.store().reserve(f.attempt); other.writableDatabase
        val gate = CountDownLatch(1); val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable>()
        listOf(f.store(), f.store(other)).forEachIndexed { index, store ->
            Thread {
                try {
                    check(gate.await(5, TimeUnit.SECONDS))
                    store.finish(f.attempt, Submission.Completed(listOf(
                        GenerationOutput(index, GenerationSource.Remote("https://cdn.example/output$index")))))
                } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
            }.start()
        }
        gate.countDown(); assertTrue(done.await(10, TimeUnit.SECONDS)); failure.get()?.let { throw it }
        f.db.close(); other.close()
        assertEquals(listOf(0, 1), outputs(f).map { it.index })
    }

    fun testNestedTransactionAndIgnoredInsertNeverReturnStarted() {
        val f = fixture(); val db = f.db.writableDatabase
        db.beginTransaction()
        try { rejects { f.store().reserve(f.attempt) } } finally { db.endTransaction() }
        db.execSQL("CREATE TRIGGER ignore_generation BEFORE INSERT ON generation_attempts BEGIN SELECT RAISE(IGNORE); END")
        rejects { f.store().reserve(f.attempt) }
        db.execSQL("DROP TRIGGER ignore_generation")
        assertSame(GenerationReservation.Started, f.store().reserve(f.attempt))
        db.beginTransaction()
        try { rejects { f.store().finish(f.attempt, Submission.NotExecuted(GenerationFailure.CANCELLED)) } }
        finally { db.endTransaction() }
        assertNull(existing(f).submission)
    }

    fun testCommitFailureDoesNotGrantExecutionAndWeakDurabilityFailsClosed() {
        val f = fixture(); val db = f.db.writableDatabase
        db.execSQL("CREATE TABLE generation_commit_guard(owner TEXT REFERENCES conversations(id) DEFERRABLE INITIALLY DEFERRED)")
        db.execSQL("CREATE TRIGGER fail_generation_commit AFTER INSERT ON generation_attempts BEGIN INSERT INTO generation_commit_guard VALUES('missing'); END")
        rejects { f.store().reserve(f.attempt) }
        db.execSQL("DROP TRIGGER fail_generation_commit")
        db.rawQuery("SELECT count(*) FROM generation_attempts", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        db.execSQL("PRAGMA synchronous=NORMAL")
        rejects { f.store().reserve(f.attempt) }
        db.execSQL("PRAGMA synchronous=FULL")
        assertSame(GenerationReservation.Started, f.store().reserve(f.attempt))
    }

    fun testRealOwnerRunToolAndHistoryAreRequired() {
        val f = fixture(history = true)
        val b = f.binding
        val invalid = listOf(
            GenerationAttemptBinding("foreign", b.assistantMessageId, b.runId, b.toolCallId, b.toolName),
            GenerationAttemptBinding(b.conversationId, "foreign", b.runId, b.toolCallId, b.toolName),
            GenerationAttemptBinding(b.conversationId, b.assistantMessageId, "foreign", b.toolCallId, b.toolName),
            GenerationAttemptBinding(b.conversationId, b.assistantMessageId, b.runId, "foreign", b.toolName),
            GenerationAttemptBinding(b.conversationId, b.assistantMessageId, b.runId, b.toolCallId, "foreign"))
        invalid.forEach { binding ->
            val attempt = f.attempt.copy(identity = binding.identity("item1"))
            rejects { SQLiteGenerationAttemptStore({ f.db.writableDatabase }, binding).reserve(attempt) }
        }
        assertSame(GenerationReservation.Started, f.store().reserve(f.attempt))
        // Reconstructing a binding does not generate a new operation identity.
        assertEquals(b.operationId, GenerationAttemptBinding(b.conversationId, b.assistantMessageId, b.runId,
            b.toolCallId, b.toolName).operationId)
        f.db.save(f.assistant.copy(status = RunStatus.COMPLETED))
        assertTrue(f.store().reserve(f.attempt) is GenerationReservation.Existing)
        rejects { f.store().reserve(f.attempt.copy(identity = b.identity("new-item"))) }
        f.db.delete(f.assistant.conversationId)
        rejects { f.store().reserve(f.attempt) }
        // Deletion did not erase a billing tombstone.
        f.db.readableDatabase.rawQuery("SELECT count(*) FROM generation_attempts", null).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
    }

    fun testLargeInlineAndSignedRemoteRoundTripAfterReopenAndMonotonicMerge() {
        val f = fixture(); val store = f.store()
        assertSame(GenerationReservation.Started, store.reserve(f.attempt))
        val media = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val signed = "https://cdn.example/image?signature=private-value"
        store.finish(f.attempt, Submission.Completed(listOf(
            GenerationOutput(0, GenerationSource.Inline(media, "image/png")),
            GenerationOutput(2, failure = GenerationFailure.INVALID_MEDIA))))
        store.finish(f.attempt, Submission.Completed(listOf(
            GenerationOutput(1, GenerationSource.Remote(signed)),
            GenerationOutput(2, GenerationSource.Remote("https://cdn.example/repaired")))))
        store.finish(f.attempt, Submission.Unknown(GenerationFailure.TRANSPORT))
        store.finish(f.attempt, Submission.NotExecuted(GenerationFailure.CANCELLED))
        store.finish(f.attempt, Submission.Completed(listOf(GenerationOutput(0, failure = GenerationFailure.PROTOCOL))))
        f.db.close()
        val restored = outputs(f)
        assertEquals(listOf(0, 1, 2), restored.map { it.index })
        assertTrue(media.contentEquals((restored[0].source as GenerationSource.Inline).bytes))
        assertEquals(signed, (restored[1].source as GenerationSource.Remote).url)
        assertNotNull(restored[2].source); assertNull(restored[2].failure)
        assertFalse(restored[1].source.toString().contains("private-value"))
    }

    fun testPendingHandleAndCompletedSurviveLateWritesAndInvalidFinish() {
        val f = fixture(); val attempt = f.attempt.copy(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART)
        val handle = GenerationHandle(attempt.identity, attempt.fingerprint, "task1",
            "https://api.example/v1/videos/task1", "https://api.example/v1/videos/task1/content")
        val store = f.store(); store.reserve(attempt); store.finish(attempt, Submission.Pending(handle))
        store.finish(attempt, Submission.Unknown(GenerationFailure.TRANSPORT))
        assertEquals(Submission.Pending(handle), existing(f, attempt).submission)
        rejects { store.finish(attempt, Submission.Pending(handle.copy(planFingerprint = "foreign"))) }
        val completed = Submission.Completed(listOf(GenerationOutput(0, GenerationSource.Remote(handle.contentUrl, true))))
        store.finish(attempt, completed)
        store.finish(attempt, Submission.Pending(handle))
        f.db.close()
        val output = ((existing(f, attempt).submission as Submission.Completed).outputs.single().source as GenerationSource.Remote)
        assertEquals(handle.contentUrl, output.url); assertTrue(output.authenticated)
    }

    fun testOutcomeFailureAndCorruptionKeepReservationClosed() {
        val f = fixture(); val db = f.db.writableDatabase; val store = f.store()
        store.reserve(f.attempt)
        db.execSQL("CREATE TRIGGER ignore_generation_finish BEFORE UPDATE ON generation_attempts BEGIN SELECT RAISE(IGNORE); END")
        rejects { store.finish(f.attempt, Submission.Unknown(GenerationFailure.TRANSPORT)) }
        db.execSQL("DROP TRIGGER ignore_generation_finish")
        assertNull(existing(f).submission)
        rejects { store.finish(f.attempt, Submission.Completed(listOf(GenerationOutput(32, failure = GenerationFailure.PROTOCOL)))) }
        rejects { store.finish(f.attempt, Submission.Completed(listOf(
            GenerationOutput(0, GenerationSource.Remote("https://cdn.example/" + "s".repeat(16384)))))) }
        assertNull(existing(f).submission)
        db.update("generation_attempts", ContentValues().apply { put("outcome", byteArrayOf(0)) }, null, null)
        rejects { store.reserve(f.attempt) }
        db.update("generation_attempts", ContentValues().apply { putNull("outcome") }, null, null)
        assertNull(existing(f).submission)
    }
}
