package app.daiyu.android

import android.content.SharedPreferences
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.video.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.model.videos.*
import app.daiyu.android.storage.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Real SQLite/sidecar and private files; synthetic inspector/transport only. No service/network.
 * These tests do NOT claim that synthetic bytes are a decodable Android video.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ControllerVideoSendingTest {
    private lateinit var root: File
    private lateinit var db: ConversationDatabase
    private lateinit var assets: VideoAssetStore
    private lateinit var preferences: SharedPreferences
    private lateinit var journal: ControllerVideoJournal
    private lateinit var adapter: ControllerMessageVideos
    private lateinit var imageAssets: MessageImageAssetStore
    private var inspect: () -> Unit = {}
    private val settings = ModelSettings(OpenRouterVideoCapability.ENDPOINT, "fixture/exact-model", "fixture-secret", false)
    private val provider get() = ProviderSnapshot.capture(settings)
    private val permission get() = OpenRouterVideoCapability.grant(settings.model, settings.requestEndpoint(), true, true)

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        root = Files.createTempDirectory("controller-video").toFile()
        db = ConversationDatabase(context)
        // Test harness schema until the parent folds this into its single versioned migration.
        ControllerVideoManifestWitness.installSchema(db.writableDatabase)
        preferences = context.getSharedPreferences("controller-video-${UUID.randomUUID()}", 0)
        journal = ControllerVideoJournal(preferences)
        assets = VideoAssetStore(File(root, "videos"), VideoAttachmentLimits(), VideoInspector { _, _, _ ->
            inspect(); VideoMetadata("video/mp4", 1000, 32, 24)
        })
        imageAssets = MessageImageAssetStore(File(root, "images"))
        adapter = ControllerMessageVideos(db, assets, ControllerMessageImages(db, imageAssets))
    }
    @After fun tearDown() { db.close(); RuntimeEnvironment.getApplication().deleteDatabase("conversations.db"); root.deleteRecursively() }

    private fun request(conversation: Conversation? = null, text: String = "original body", revision: Long = 9): VideoDraftRequest {
        val owner = VideoAttachmentOwner(conversation?.id?.value ?: UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val one = assets.import(owner, byteArrayOf(1, 2, 3).inputStream())
        val two = assets.import(owner, byteArrayOf(4, 5, 6).inputStream())
        return VideoDraftRequest(conversation?.id?.value, owner, revision, text, listOf(one, two))
    }
    private data class Ready(val attempt: ControllerVideoAttempt, val snapshot: ControllerVideoHistorySnapshot,
        val prepared: PreparedControllerVideoSend, val plan: ControllerVideoModelPlan)
    private fun ready(request: VideoDraftRequest, createdAt: Long? = null): Ready {
        val attempt = ControllerVideoAttempt(request, journal)
        assertNull(attempt.reserve(db))
        val snapshot = adapter.history.capture(attempt.conversationId)
        val prepared = attempt.prepare(assets, provider, permission, createdAt) { false }
        val plan = adapter.preflight(snapshot, provider, permission, prepared) { false }
        return Ready(attempt, snapshot, prepared, plan)
    }
    private fun commit(value: Ready, draft: String = value.attempt.request.text,
        before: (android.database.sqlite.SQLiteDatabase) -> Unit = {}): VideoDraftReceipt =
        value.attempt.commit(db, value.snapshot, value.prepared, value.plan, "default", draft, { false }, before)
    private fun rejected(block: () -> Unit) { assertNotNull(runCatching(block).exceptionOrNull()) }
    private fun phase(): String = JSONObject(preferences.all.values.single() as String).getString("phase")
    private fun sendText(user: Message, assistant: Message) {
        // Explicitly emulate the mandatory parent DB hook, atomically with both message rows.
        DatabaseTransactions { db.writableDatabase }.run { sql ->
            db.send(user, assistant)
            ControllerVideoManifestWitness.insert(sql, user, emptyList())
            ControllerVideoManifestWitness.insert(sql, assistant, emptyList())
        }
    }

    @Test fun frozenMessageTimeSurvivesFullEqualityCommitAndReceiptRecoveryIncludingLegacyNullAndEpoch() {
        for (time in listOf<Long?>(null, 0L, 123456789L)) {
            val request = request(); db.draft(request.text)
            val ready = ready(request, time)
            assertEquals(time, ready.prepared.user.createdAt)
            assertEquals(time, ready.prepared.assistant.createdAt)
            assertEquals(ready.prepared.user, ready.plan.history.last())
            assertEquals(VideoDraftOutcome.ACCEPTED, commit(ready).outcome)
            assertEquals(listOf(ready.prepared.user, ready.prepared.assistant),
                db.messages(ready.attempt.conversationId).map { it.second })
            db.save(ready.prepared.assistant.copy(status = RunStatus.COMPLETED))
            // A receipt has no time field: original DB rows, not a newly captured clock, win.
            assertFalse(checkNotNull(journal.read(request)).has("created_at"))
            assertEquals(VideoDraftOutcome.ACCEPTED, journal.recover(request, db).outcome)
            val replay = ControllerVideoAttempt(request, journal)
            assertEquals(VideoDraftOutcome.ACCEPTED, replay.reserve(db)?.outcome)
            rejected { replay.prepare(assets, provider, permission, 999999999L) { false } }
            assertEquals(listOf(time, time), db.messages(ready.attempt.conversationId).map { it.second.createdAt })
        }
    }

    @Test fun changingEitherPreparedMessageTimeFailsFullEqualityBeforeDatabaseEntry() {
        for (changeUser in listOf(false, true)) {
            val request = request(); db.draft(request.text)
            val ready = ready(request, 500L)
            val changed = PreparedControllerVideoSend(
                if (changeUser) ready.prepared.user.copy(createdAt = 501L) else ready.prepared.user,
                if (changeUser) ready.prepared.assistant else ready.prepared.assistant.copy(createdAt = 501L),
                ready.prepared.inputs)
            assertEquals(VideoDraftOutcome.REJECTED, commit(ready.copy(prepared = changed)).outcome)
            assertTrue(db.messages(ready.attempt.conversationId).isEmpty())
            assertEquals(request.text, db.draft())
        }
    }

    @Test fun alreadyFrozenVideoCandidateCannotBePreparedWithADifferentTime() {
        val request = request()
        val ready = ready(request, 0L)
        rejected { ready.attempt.prepare(assets, provider, permission, 1L) { false } }
        assertEquals(0L, ready.prepared.user.createdAt)
        assertEquals(0L, ready.prepared.assistant.createdAt)
    }

    @Test fun durableNullSlotIdentityPrecedesAssetPreparationAndDbEntry() {
        val request = request()
        db.draft(request.text)
        val attempt = ControllerVideoAttempt(request, journal)
        assertNull(attempt.reserve(db))
        val encoded = JSONObject(preferences.all.values.single() as String)
        assertTrue(encoded.has("slot") && encoded.isNull("slot"))
        assertEquals(request.owner.conversationId, encoded.getString("conversation"))
        assertEquals(request.owner.draftId, encoded.getString("draft"))
        assertEquals(request.revision, encoded.getLong("revision"))
        assertEquals(attempt.userId.value, encoded.getString("user"))
        assertEquals(attempt.assistantId.value, encoded.getString("assistant"))
        assertEquals(attempt.runId.value, encoded.getString("run"))
        assertEquals(listOf(request.videos[0].id, request.videos[1].id), (0..1).map {
            encoded.getJSONArray("references").getJSONObject(it).getString("id")
        })
        inspect = { assertEquals("PREPARING", phase()); assertTrue(db.messages(attempt.conversationId).isEmpty()) }
        val prepared = attempt.prepare(assets, provider, permission) { false }
        inspect = {}
        val snapshot = adapter.history.capture(attempt.conversationId)
        val plan = adapter.preflight(snapshot, provider, permission, prepared) { false }
        var gate = false
        val receipt = attempt.commit(db, snapshot, prepared, plan, "default", request.text, { false }) {
            gate = true; assertEquals("PREPARING", phase())
        }
        assertTrue(gate)
        assertEquals(VideoDraftOutcome.ACCEPTED, receipt.outcome)
        assertTrue(videoReceiptMatches(request, receipt))
        assertEquals("ACCEPTED", phase())
        assertEquals(request.videos.toSet(), assets.list(request.owner).toSet()) // helper never deletes draft originals
        assertEquals(VideoDraftOutcome.ACCEPTED, journal.recover(request, db).outcome)
        val replay = ControllerVideoAttempt(request, ControllerVideoJournal(preferences))
        assertEquals(VideoDraftOutcome.ACCEPTED, replay.reserve(db)?.outcome)
        rejected { replay.prepare(assets, provider, permission) { false } }
        assertEquals(2, db.messages(attempt.conversationId).size)
    }

    @Test fun failedIdentityPersistenceCannotPrepareAndMissingRecoveryIsUnknown() {
        val request = request()
        val failing = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val delegate = preferences.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun commit() = false
                }
            }
        }
        val attempt = ControllerVideoAttempt(request, ControllerVideoJournal(failing))
        assertEquals(VideoDraftOutcome.UNKNOWN, attempt.reserve(db)?.outcome)
        rejected { attempt.prepare(assets, provider, permission) { false } }
        assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(request, db).outcome)
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun unknownBeforeDbAndAfterRollbackNeverAutomaticallyResends() {
        val request = request(); db.draft(request.text)
        val ready = ready(request)
        // A new instance after a PREPARING crash must not reuse the identity for another send.
        val restored = ControllerVideoAttempt(request, ControllerVideoJournal(preferences))
        assertEquals(VideoDraftOutcome.UNKNOWN, restored.reserve(db)?.outcome)
        rejected { restored.prepare(assets, provider, permission) { false } }
        db.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_video_send BEFORE INSERT ON messages WHEN NEW.role='assistant' BEGIN SELECT RAISE(ABORT,'fixture'); END")
        assertEquals(VideoDraftOutcome.UNKNOWN, commit(ready).outcome)
        assertEquals("DB_ENTERED", phase())
        assertTrue(db.messages(ready.attempt.conversationId).isEmpty())
        assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(request, db).outcome)
        db.writableDatabase.execSQL("DROP TRIGGER fail_video_send")
        assertEquals(VideoDraftOutcome.UNKNOWN, ControllerVideoAttempt(request, journal).reserve(db)?.outcome)
        assertTrue(db.messages(ready.attempt.conversationId).isEmpty())
    }

    @Test fun textAndReferenceOrderArePartOfIdentityAndUnresolvedOwnerFencesOtherRevision() {
        val request = request(); assertNull(ControllerVideoAttempt(request, journal).reserve(db))
        for (changed in listOf(
            VideoDraftRequest(request.conversation, request.owner, request.revision, "changed", request.videos),
            VideoDraftRequest(request.conversation, request.owner, request.revision, request.text, request.videos.reversed()),
            VideoDraftRequest(request.conversation, request.owner, request.revision + 1, request.text, request.videos))) {
            assertEquals(VideoDraftOutcome.UNKNOWN, ControllerVideoAttempt(changed, journal).reserve(db)?.outcome)
        }
        assertEquals(1, preferences.all.size)
    }

    @Test fun finalCasIncludesPreviouslyEmptySidecarAndPhysicalTextAndDraft() {
        val conversation = db.create("default")
        val seed = Message(MessageId("seed"), conversation.id, RunId("seed-run"), "user",
            listOf(MessageBlock.Text("text", "seed body")), RunStatus.COMPLETED)
        sendText(seed, seed.copy(id = MessageId("seed-assistant"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING))
        val request = request(conversation); db.draft(conversation.id, request.text)
        val ready = ready(request)
        val owner = MessageVideoOwner(conversation.id.value, seed.id.value)
        val reference = assets.prepare(owner, request.videos.first())
        // Corrupt metadata directly: a legitimate insert may not overwrite a zero-video witness.
        db.writableDatabase.execSQL("INSERT INTO message_video_inputs(message_id,payload) VALUES(?,?)",
            arrayOf(seed.id.value, MessageVideoInputCodec.encode(seed, listOf(MessageVideoInput(owner, listOf(reference))))))
        assertEquals(VideoDraftOutcome.REJECTED, commit(ready).outcome)
        assertEquals("REJECTED", phase())
        // The deliberately corrupted seed must not pass a checked runtime history read.
        db.readableDatabase.rawQuery("SELECT count(*) FROM messages WHERE conversation_id=?",
            arrayOf(conversation.id.value)).use { c ->
            assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0))
        }
        assertEquals(request.text, db.conversations().single().draft)
    }

    @Test fun changedBodyOrDraftIsRejectedBeforeEnteringDatabaseSend() {
        val conversation = db.create("default")
        val request = request(conversation); db.draft(conversation.id, request.text)
        val ready = ready(request)
        db.draft(conversation.id, "new editor text")
        assertEquals(VideoDraftOutcome.REJECTED, commit(ready).outcome)
        assertEquals("REJECTED", phase())
        assertTrue(db.messages(conversation.id).isEmpty())
    }

    private data class RestartFixture(val hostPreferences: String, val user: MessageId,
        val assistant: MessageId, val conversation: ConversationId)

    /** Nothing returned retains a VideoDraftRequest, attempt, plan or prepared user/body. */
    private fun acceptedForRestart(existing: Boolean = false): RestartFixture {
        val conversation = if (existing) db.create("default") else null
        val request = request(conversation)
        if (conversation == null) db.draft(request.text) else db.draft(conversation.id, request.text)
        val hostName = "host-restart-${UUID.randomUUID()}"
        val hostStore = VideoHostDraftStore(RuntimeEnvironment.getApplication().getSharedPreferences(hostName, 0))
        assertTrue(hostStore.write(listOf(VideoHostDraftStore.Draft(request.conversation, request.owner,
            request.revision, request.videos, true))))
        val ready = ready(request)
        assertEquals(VideoDraftOutcome.ACCEPTED, commit(ready).outcome)
        assertEquals("", if (conversation == null) db.draft() else db.conversations().single { it.id == conversation.id }.draft)
        assertFalse(preferences.getString(journal.key(request), null)!!.contains(request.text))
        // Make the advisory tombstone stale, as when the process dies immediately after DB commit.
        val key = journal.key(request)
        val stored = JSONObject(preferences.getString(key, null)!!).put("phase", "DB_ENTERED")
        assertTrue(preferences.edit().putString(key, stored.toString()).commit())
        request.videos.forEach { assets.remove(request.owner, it) }
        return RestartFixture(hostName, ready.attempt.userId, ready.attempt.assistantId, ready.attempt.conversationId)
    }

    private fun restoreHost(fixture: RestartFixture): VideoHostDraftStore.Draft {
        db.close()
        db = ConversationDatabase(RuntimeEnvironment.getApplication())
        journal = ControllerVideoJournal(preferences)
        adapter = ControllerMessageVideos(db, assets, ControllerMessageImages(db, imageAssets))
        return VideoHostDraftStore(RuntimeEnvironment.getApplication()
            .getSharedPreferences(fixture.hostPreferences, 0)).read().single()
    }

    @Test fun restartWithoutAnyRequestRecoversBothSlotsFromDbBodyNotClearedOrEditedComposer() {
        for (existing in listOf(false, true)) {
            val fixture = acceptedForRestart(existing)
            val draft = restoreHost(fixture)
            if (existing) db.draft(fixture.conversation, "later edited conversation composer")
            else db.draft("later edited new conversation composer")
            val before = preferences.all.toMap()
            val count = db.messages(fixture.conversation).size
            repeat(2) {
                val receipt = ControllerVideoJournal(preferences).recover(draft, db)
                assertEquals(VideoDraftOutcome.ACCEPTED, receipt.outcome)
                assertEquals(draft.owner, receipt.owner)
                assertEquals(draft.revision, receipt.revision)
                assertEquals(draft.videos, receipt.videos)
                assertEquals(fixture.conversation.value, receipt.acceptedConversation)
            }
            assertEquals(before, preferences.all) // No new IDs, phase write, reservation or resend.
            assertEquals(count, db.messages(fixture.conversation).size)
        }
    }

    @Test fun restartRejectsNoEvidenceAsUnknownAndChecksOwnerRevisionOrderAndDbOriginal() {
        val fixture = acceptedForRestart()
        val draft = restoreHost(fixture)
        val wrongDrafts = listOf(
            draft.copy(revision = draft.revision + 1),
            draft.copy(videos = draft.videos.reversed()),
            draft.copy(owner = draft.owner.copy(draftId = "different-draft")),
            draft.copy(fenced = false))
        wrongDrafts.forEach { assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(it, db).outcome) }
        db.writableDatabase.execSQL("UPDATE messages SET blocks=replace(blocks,'original body','tampered body') WHERE id=?",
            arrayOf(fixture.user.value))
        assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(draft, db).outcome)
        db.writableDatabase.execSQL("UPDATE messages SET blocks=replace(blocks,'tampered body','original body') WHERE id=?",
            arrayOf(fixture.user.value))
        db.writableDatabase.execSQL("UPDATE messages SET run_id='different-run' WHERE id=?", arrayOf(fixture.assistant.value))
        assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(draft, db).outcome)
        assertTrue(preferences.edit().clear().commit())
        assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(draft, db).outcome)
    }

    @Test fun sidecarLostBeforeFirstNewRunCaptureAndMissingWitnessCannotBecomeTextOnly() {
        val fixture = acceptedForRestart()
        val draft = restoreHost(fixture)
        db.writableDatabase.delete("message_video_inputs", "message_id=?", arrayOf(fixture.user.value))
        // No baseline exists in this adapter/process; persistent witness is the only expectation.
        rejected { adapter.history.capture(fixture.conversation) }
        assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(draft, db).outcome)
        db.writableDatabase.delete(ControllerVideoManifestWitness.TABLE, "message_id=?", arrayOf(fixture.user.value))
        rejected { adapter.history.capture(fixture.conversation) }
    }

    @Test fun corruptOrMissingJournalNeverInfersAcceptanceFromFilesOrDatabase() {
        val fixture = acceptedForRestart()
        val draft = restoreHost(fixture)
        val key = preferences.all.keys.single()
        assertTrue(preferences.edit().putString(key, "{broken").commit())
        assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(draft, db).outcome)
        assertTrue(preferences.edit().clear().commit())
        assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(draft, db).outcome)
        assertEquals(2, db.messages(fixture.conversation).size)
    }

    @Test fun knownVideoWithSidecarButWithoutWitnessFailsClosedOnCaptureAndRecovery() {
        val fixture = acceptedForRestart()
        val draft = restoreHost(fixture)
        db.writableDatabase.delete(ControllerVideoManifestWitness.TABLE, "message_id=?", arrayOf(fixture.user.value))
        rejected { adapter.history.capture(fixture.conversation) }
        assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(draft, db).outcome)
    }

    @Test fun witnessedPureTextStillPreflightsWithoutVideoPermission() {
        val conversation = db.create("default")
        val user = Message(MessageId("text-user"), conversation.id, RunId("text-run"), Message.USER_ROLE,
            listOf(MessageBlock.Text("user", "ordinary text")), RunStatus.COMPLETED)
        sendText(user, user.copy(id = MessageId("text-assistant"), role = Message.ASSISTANT_ROLE,
            blocks = emptyList(), status = RunStatus.RUNNING))
        val plan = adapter.preflight(adapter.history.capture(conversation.id), provider, null) { false }
        assertNull(plan.videos)
    }

    @Test fun acceptedRecoveryRequiresAuthoritativeSidecarNotJournalOrOriginalExistence() {
        val request = request(); db.draft(request.text)
        val ready = ready(request)
        assertEquals(VideoDraftOutcome.ACCEPTED, commit(ready).outcome)
        db.writableDatabase.delete("message_video_inputs", "message_id=?", arrayOf(ready.attempt.userId.value))
        assertEquals("ACCEPTED", phase())
        assertEquals(VideoDraftOutcome.UNKNOWN, journal.recover(request, db).outcome)
        assertEquals(VideoDraftOutcome.UNKNOWN, ControllerVideoAttempt(request, journal).reserve(db)?.outcome)
    }

    @Test fun segmentedUserOwnershipIsUniqueAndNeverDeduplicated() {
        val c = ConversationId("c"); val run = RunId("r")
        val user = Message(MessageId("u"), c, run, "user", listOf(MessageBlock.Text("t", "body")), RunStatus.COMPLETED)
        val owner = user.copy(id = MessageId("a"), role = "assistant", blocks = emptyList(), historySegments = listOf(
            MessageHistorySegment(user.id, "user", user.blocks), MessageHistorySegment(MessageId("a"), "assistant", emptyList())))
        val videoOwner = MessageVideoOwner("c", "u")
        val input = MessageVideoInput(videoOwner, listOf(MessageVideoReference(videoOwner, UUID.randomUUID().toString(),
            "video/mp4", 3, "a".repeat(64), 1000, 32, 24)))
        val valid = ControllerVideoHistorySnapshot(c, true, listOf(user, owner), listOf(emptyList(), listOf(input)))
        assertEquals(listOf(videoOwner), valid.inputs.map { it.owner })
        rejected { ControllerVideoHistorySnapshot(c, true, listOf(user, owner), listOf(listOf(input), listOf(input))) }
        rejected { ControllerVideoHistorySnapshot(c, true, listOf(user, user), listOf(listOf(input), emptyList())) }
        rejected { validateVideoHistory(listOf(owner.copy(historySegments = emptyList())), listOf(input)) }
    }

    @Test fun canonicalSidecarIgnoresJsonWhitespaceButPhysicalChangesFailCas() {
        val request = request(); db.draft(request.text)
        val ready = ready(request); assertEquals(VideoDraftOutcome.ACCEPTED, commit(ready).outcome)
        val snapshot = adapter.history.capture(ready.attempt.conversationId)
        val sql = db.writableDatabase
        sql.execSQL("UPDATE message_video_inputs SET payload='  ' || payload || '  ' WHERE message_id=?",
            arrayOf(ready.attempt.userId.value))
        DatabaseTransactions { sql }.run { adapter.history.assertCurrent(it, snapshot) }
        db.save(ready.prepared.assistant.copy(status = RunStatus.COMPLETED))
        rejected { DatabaseTransactions { sql }.run { adapter.history.assertCurrent(it, snapshot) } }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun historicalImagesAndVideosShareOneResponsesPayloadWithoutLosingInstructions() {
        val conversation = db.create("default")
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR4nGP4z8DwH4QZYAwAR8oH+WdZbrcAAAAASUVORK5CYII=")
        val drafts = app.daiyu.android.media.ImageAttachmentStore(File(root, "image-drafts"), app.daiyu.android.media.AndroidImageInspector())
        val owner = app.daiyu.android.media.ImageAttachmentOwner(conversation.id.value, "image-draft")
        val image = drafts.import(owner, png.inputStream())
        val ref = imageAssets.prepare(app.daiyu.android.media.MessageImageOwner(conversation.id.value, "image-user"), owner,
            drafts.freeze(owner, listOf(image), true)).single()
        val user = Message(MessageId("image-user"), conversation.id, RunId("image-run"), "user", listOf(
            MessageBlock.Image(MessageImageReference(MessageImageOwner(ref.owner.conversationId, ref.owner.messageId),
                ref.id, ref.mime, ref.bytes, ref.sha256, ref.width, ref.height))), RunStatus.COMPLETED)
        val reply = user.copy(id = MessageId("image-reply"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
        sendText(user, reply); db.save(reply.copy(status = RunStatus.COMPLETED))
        val request = request(conversation); db.draft(conversation.id, request.text)
        val attempt = ControllerVideoAttempt(request, journal); assertNull(attempt.reserve(db))
        val supported = ProviderSnapshot.capture(settings, settings.protocol, true)
        val cap = permission
        val prepared = attempt.prepare(assets, supported, cap) { false }
        val snapshot = adapter.history.capture(conversation.id)
        val plan = adapter.preflight(snapshot, supported, cap, prepared) { false }
        assertTrue(plan.images.hasImages)
        assertEquals(VideoDraftOutcome.ACCEPTED,
            attempt.commit(db, snapshot, prepared, plan, "default", request.text, { false }).outcome)
        val connection = Connection()
        val ui = PostUi(plan.history, plan.inputs)
        val port = adapter.wrap(supported, cap, conversation.id, prepared.assistant.id, plan,
            "image + video instructions", { false }, ui.approval) { _, instructions, grant, approval ->
            ResponsesModel(settings, { connection }, true, instructions, grant, approval)
        }
        ui.send(port.open(plan.history))
        val body = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals("image + video instructions", body.getString("instructions"))
        assertEquals(listOf("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)),
            inputMediaUrls(body, "input_image", "image_url"))
        assertEquals(listOf("data:video/mp4;base64,AQID", "data:video/mp4;base64,BAUG"),
            inputMediaUrls(body, "input_video", "video_url"))
        assertTrue(connection.sent.size() > 0)
    }

    private fun inputMediaUrls(body: JSONObject, type: String, field: String): List<String> {
        val input = body.getJSONArray("input")
        return (0 until input.length()).flatMap { index ->
            val row = input.getJSONObject(index)
            if (row.optString("role") != "user") return@flatMap emptyList<String>()
            val content = row.optJSONArray("content") ?: return@flatMap emptyList<String>()
            (0 until content.length()).map { content.getJSONObject(it) }
                .filter { it.getString("type") == type }.map { it.getString(field) }
        }
    }

    /** Synthetic UI driver for these existing payload tests, not a production auto-approval. */
    private inner class PostUi(private val rows: List<Message>, private val inputs: List<MessageVideoInput>) {
        private val main = Thread.currentThread()
        private val queue = LinkedBlockingQueue<() -> Unit>()
        private val identity = Any()
        private var decision: ((Boolean) -> Unit)? = null
        private var cancelled = false
        private fun authority() = ControllerVideoPostAuthority(identity, 1, 1, settings, rows, inputs)
        val approval = ControllerVideoPostApproval(object : ControllerVideoPostApprovalHost {
            override fun postToMain(action: () -> Unit) { queue.put(action) }
            override fun snapshotOnMain(): ControllerVideoPostAuthority? {
                assertSame(main, Thread.currentThread())
                return if (cancelled) null else authority()
            }
            override fun showOnMain(prompt: ControllerVideoPostPrompt, decision: (Boolean) -> Unit) {
                assertSame(main, Thread.currentThread())
                assertEquals(inputs.sumOf { it.references.size }, prompt.references.size)
                this@PostUi.decision = decision
            }
            override fun cancelRun() { cancelled = true }
        }, authority())

        fun send(session: ModelSession) {
            decision = null
            val failure = AtomicReference<Throwable?>()
            val worker = Thread {
                try { session.next(emptyList()) {} } catch (caught: Throwable) { failure.set(caught) }
            }.apply { start() }
            try {
                checkNotNull(queue.poll(3, TimeUnit.SECONDS))()
                checkNotNull(decision)(true) // Explicit test click for THIS displayed POST.
                worker.join(3000)
                assertFalse(worker.isAlive)
                failure.get()?.let { throw AssertionError("video POST failed", it) }
            } finally {
                session.cancel(); worker.interrupt(); worker.join(3000)
            }
        }
    }

    private class Connection : HttpsURLConnection(URL(OpenRouterVideoCapability.ENDPOINT)) {
        val sent = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n".byteInputStream()
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
    }

    @Test fun allModelOpenOverloadsSendExactVideosAndInstructionsIncludingTextContinuation() {
        val request = request(); db.draft(request.text)
        val ready = ready(request); assertEquals(VideoDraftOutcome.ACCEPTED, commit(ready).outcome)
        // Drafts are gone; the ordinary text continuation must still read the accepted originals.
        request.videos.forEach { assets.remove(request.owner, it) }
        db.save(ready.prepared.assistant.copy(status = RunStatus.COMPLETED))
        val follow = ready.prepared.user.copy(id = MessageId("follow"), runId = RunId("follow-run"),
            blocks = listOf(MessageBlock.Text("follow", "plain text continuation")))
        val active = follow.copy(id = MessageId("follow-assistant"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
        val baseline = adapter.preflightText(adapter.history.capture(follow.conversationId), provider, permission, follow) { false }
        sendText(follow, active)
        val rows = adapter.history.capture(follow.conversationId).rows.filterNot { it.id == active.id }
        val cap = permission
        var runtimeCancelled = false
        val connections = mutableListOf<Connection>()
        val ui = PostUi(rows, baseline.inputs)
        val port = adapter.wrap(provider, cap, follow.conversationId, active.id, baseline,
            "preserved assistant instructions", { runtimeCancelled }, ui.approval) { _, instructions, grant, approval ->
            assertSame(cap, grant)
            val connection = Connection().also { connections.add(it) }
            ResponsesModel(settings, { connection }, null, instructions, grant, approval)
        }
        val definitions = listOf(ToolDefinition("fixture_tool", "fixture", "{\"type\":\"object\",\"properties\":{}}"))
        val imagePlan = ControllerMessageImages(db, MessageImageAssetStore(File(root, "other-images"))).freeze(rows, provider)
        // A later actual freeze retires earlier plan identities, so open/approve each session
        // in order instead of pre-opening four stale sessions and then trying to send them.
        val opens = listOf<() -> ModelSession>({ port.open(rows) }, { port.open(rows, setOf(ReadOnlyTool.BATTERY)) },
            { port.open(rows, definitions) }, { port.open(rows, definitions, imagePlan) })
        opens.forEach { open ->
            val session = open()
            assertEquals(0, connections.last().sent.size()) // open is not a POST
            ui.send(session)
        }
        assertEquals(4, connections.size)
        connections.forEach { connection ->
            val body = JSONObject(connection.sent.toString("UTF-8"))
            assertEquals("preserved assistant instructions", body.getString("instructions"))
            val wire = body.toString()
            assertEquals(listOf("data:video/mp4;base64,AQID", "data:video/mp4;base64,BAUG"),
                inputMediaUrls(body, "input_video", "video_url"))
            assertTrue(wire.contains("plain text continuation"))
            assertFalse(wire.contains("fixture-secret"))
        }
        assertEquals("get_battery_status", JSONObject(connections[1].sent.toString("UTF-8")).getJSONArray("tools").getJSONObject(0).getString("name"))
        rejected { port.open(listOf(follow)) } // dropping old physical/video history is forbidden
        runtimeCancelled = true
        rejected { port.open(rows) }
        assertEquals(4, connections.size)
        val noGrant = adapter.wrap(provider, null, follow.conversationId, active.id, baseline, null, { false }) { _, _, _, _ -> error("no transport") }
        rejected { noGrant.open(rows) }
        runtimeCancelled = false
        db.writableDatabase.delete("message_video_inputs", "message_id=?", arrayOf(ready.attempt.userId.value))
        rejected { port.open(rows) } // missing metadata cannot become a text-only request mid-run
        assertEquals(4, connections.size)
    }
}
