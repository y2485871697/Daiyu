package app.daiyu.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import app.daiyu.android.backup.MessageVideoBackupAssets
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.media.video.*
import app.daiyu.android.model.videos.MessageVideoInput
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.storage.*
import app.daiyu.android.context.ContextService
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.MessageImageOwner as MediaOwner
import app.daiyu.android.media.MessageImageReference as MediaReference
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real controller admission, worker, SQLite and runner; only the model transport is synthetic.
 * These are test SOURCES, not an assertion that instrumentation has been executed. */
@Suppress("DEPRECATION")
class ControllerMessageActionsTest : InstrumentationTestCase() {
    private class App(base: Context) : Application() {
        private val root = File(base.cacheDir, "message-actions-${UUID.randomUUID()}").apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getFilesDir() = root
        override fun getApplicationContext(): Context = this
    }
    private data class Fixture(val app: App, val db: ConversationDatabase, val controller: ChatController,
        val opens: AtomicInteger, val alias: String)
    private val fixtures = mutableListOf<Fixture>()
    private fun main(work: () -> Unit) {
        val error = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { work() } catch (t: Throwable) { error.set(t) } }
        error.get()?.let { throw it }
    }
    private fun await(done: CountDownLatch) = assertTrue("message action timed out", done.await(15, TimeUnit.SECONDS))
    private fun fresh(configured: Boolean = true,
        block: ((List<Message>, (ModelEvent) -> Unit) -> Unit)? = null): Fixture {
        val id = UUID.randomUUID().toString()
        val app = App(testContext(instrumentation.targetContext, "message-actions-$id-"))
        val db = ConversationDatabase(app)
        val alias = "app.daiyu.android.message-actions.$id"
        val settings = ModelSettingsStore(app, alias)
        if (configured) settings.save(ModelSettings("https://example.invalid", "message-action-fixture", "synthetic-key", false))
        val opens = AtomicInteger()
        val controller = ChatController(app, db, settings, app.getSharedPreferences("conversation-ui", 0), { _ ->
            CatalogFixturePort { history ->
                opens.incrementAndGet()
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (block == null) sink(ModelEvent.TextFinal("answer", "new answer")) else block(history, sink)
                    }
                    override fun cancel() {}
                }
            }
        })
        val fixture = Fixture(app, db, controller, opens, alias).also { fixtures.add(it) }
        val ready = CountDownLatch(1)
        val listener: () -> Unit = { if (controller.ready) ready.countDown() }
        main { controller.observe(listener); listener() }; await(ready)
        main { controller.unobserve(listener) }; assertNull(controller.problem)
        return fixture
    }
    private fun seed(f: Fixture, conversation: Conversation = f.db.create("default"), n: Int = 1,
        tool: Boolean = false): Conversation {
        repeat(n) { index ->
            val suffix = UUID.randomUUID().toString()
            val run = RunId("run-$suffix")
            val user = Message(MessageId("user-$suffix"), conversation.id, run, Message.USER_ROLE,
                listOf(MessageBlock.Text("body", "original user $index")), RunStatus.COMPLETED)
            val blocks = if (tool) listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("call-$suffix"), "external_write", "{}"), "UNKNOWN"))
                else listOf(MessageBlock.Text("answer", "old answer $index"))
            val assistant = user.copy(id = MessageId("assistant-$suffix"), role = Message.ASSISTANT_ROLE,
                blocks = blocks, status = RunStatus.RUNNING)
            f.db.send(user, assistant)
            f.db.save(assistant.copy(status = RunStatus.COMPLETED))
        }
        return conversation
    }
    private fun snapshot(f: Fixture, owner: ConversationId): MessageActionSnapshot {
        val done = CountDownLatch(1); val value = AtomicReference<MessageActionSnapshot?>()
        main { assertTrue(f.controller.loadMessageActionSnapshot(owner) { value.set(it); done.countDown() }) }
        await(done); return checkNotNull(value.get())
    }
    private fun action(f: Fixture, action: MessageAction, target: MessageActionTarget): MessageActionResult {
        val done = CountDownLatch(1); val value = AtomicReference<MessageActionResult>()
        main { assertTrue(f.controller.messageAction(action, target) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper()); value.set(it); done.countDown()
        }) }
        await(done); return value.get()
    }
    private fun history(f: Fixture, id: ConversationId) = ContextService.read(f.db.readableDatabase, id)

    fun testEditReturnsOriginalTextWithoutSavingOrSending() {
        val f = fresh(); val owner = seed(f)
        f.db.draft(owner.id, "unsent draft")
        val before = history(f, owner.id)
        val target = snapshot(f, owner.id).targets.values.single { it.role == Message.USER_ROLE }
        assertEquals(MessageActionResult.EditDraft("original user 0"), action(f, MessageAction.EDIT_USER, target))
        assertEquals(before, history(f, owner.id)); assertEquals(0, f.opens.get())
        assertEquals("unsent draft", f.db.conversations().single().draft)
    }

    fun testStaleRevisionAndForgedOwnerFailClosed() {
        val f = fresh(); val owner = seed(f)
        val target = snapshot(f, owner.id).targets.values.first()
        seed(f, owner)
        val before = history(f, owner.id)
        assertTrue(action(f, MessageAction.DELETE_TURN, target) is MessageActionResult.Rejected)
        val current = snapshot(f, owner.id).targets.values.first()
        assertTrue(action(f, MessageAction.BRANCH, current.copy(ownerId = MessageId("foreign"))) is MessageActionResult.Rejected)
        assertTrue(action(f, MessageAction.EDIT_USER, current.copy(assistantId = "foreign")) is MessageActionResult.Rejected)
        assertEquals(before, history(f, owner.id)); assertEquals(0, f.opens.get())
    }

    fun testSnapshotReaderDoesNotBlockEditorAndCloseWaitsForQueuedRead() {
        val f = fresh(); val owner = seed(f)
        val executor = ChatController::class.java.getDeclaredField("executor").apply { isAccessible = true }
            .get(f.controller) as java.util.concurrent.ExecutorService
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val callbacks = AtomicInteger()
        executor.execute { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        await(entered)
        val sql = f.db.writableDatabase
        try {
            main {
                assertTrue(f.controller.loadMessageActionSnapshot(owner.id) { callbacks.incrementAndGet() })
                assertFalse("automatic read must not occupy the editor write gate", f.controller.dataBusy)
                f.controller.close()
            }
            assertTrue("queued reader retains its original database", sql.isOpen)
        } finally { release.countDown() }
        val closed = ChatController::class.java.getDeclaredField("closeDone").apply { isAccessible = true }
            .get(f.controller) as CountDownLatch
        await(closed)
        assertFalse(sql.isOpen); assertEquals(0, callbacks.get()); assertEquals(0, f.opens.get())
    }

    fun testProductionBranchPreservesNullBindingAndRejectsTamperedNullReceipt() {
        val f = fresh(); val owner = seed(f)
        val sql = f.db.writableDatabase
        sql.execSQL("UPDATE conversations SET assistant_id=NULL WHERE id=?", arrayOf(owner.id.value))
        val before = history(f, owner.id)
        val target = snapshot(f, owner.id).targets.getValue(before.first().id.value)
        val copy = (action(f, MessageAction.BRANCH, target) as MessageActionResult.Branch).conversation
        assertNull(f.db.assistantId(copy.id)); assertEquals(before, history(f, owner.id))
        val count = f.db.conversations().size
        sql.execSQL("CREATE TRIGGER corrupt_branch_binding AFTER INSERT ON conversations " +
            "WHEN NEW.id <> '${owner.id.value}' BEGIN UPDATE conversations SET assistant_id='tampered' WHERE id=NEW.id; END")
        try {
            assertTrue(action(f, MessageAction.BRANCH, target) is MessageActionResult.Rejected)
            assertEquals(count, f.db.conversations().size); assertNull(f.db.assistantId(owner.id))
            assertEquals(before, history(f, owner.id)); assertEquals(0, f.opens.get())
        } finally { sql.execSQL("DROP TRIGGER corrupt_branch_binding") }
    }

    fun testBranchCopiesWholeRunAndDeleteDoesNotChangeCopy() {
        val f = fresh(); val owner = seed(f, n = 2, tool = true)
        val before = history(f, owner.id)
        val target = snapshot(f, owner.id).targets.getValue(before.first().id.value)
        val copy = (action(f, MessageAction.BRANCH, target) as MessageActionResult.Branch).conversation
        val copied = history(f, copy.id)
        assertEquals(2, copied.size); assertTrue(copied.all { it.conversationId == copy.id })
        assertTrue(copied.none { a -> before.any { a.id == it.id || a.runId == it.runId } })
        assertEquals(before, history(f, owner.id))
        assertEquals(MessageActionResult.Deleted, action(f, MessageAction.DELETE_TURN, target))
        assertEquals(before.drop(2), history(f, owner.id)); assertEquals(copied, history(f, copy.id))
        val db = f.db.readableDatabase
        before.take(2).forEach { row ->
            for (table in listOf("message_video_inputs", "message_video_witnesses")) {
                assertFalse(db.rawQuery("SELECT 1 FROM $table WHERE message_id=?", arrayOf(row.id.value)).use { it.moveToFirst() })
            }
        }
        assertEquals(0, f.opens.get())
    }

    fun testRetryUnknownToolAndRegenerateToolHistoryRejectWithoutTransport() {
        val f = fresh(); val owner = seed(f, tool = true)
        val snapshot = snapshot(f, owner.id)
        val before = history(f, owner.id)
        val retry = action(f, MessageAction.RETRY_STEP, snapshot.steps.values.single()) as MessageActionResult.Rejected
        assertTrue(retry.reason.contains("UNKNOWN"))
        val answer = snapshot.targets.values.single { it.role == Message.ASSISTANT_ROLE }
        assertTrue(action(f, MessageAction.REGENERATE, answer) is MessageActionResult.Rejected)
        assertEquals(before, history(f, owner.id)); assertEquals(1, f.db.conversations().size); assertEquals(0, f.opens.get())
    }

    fun testExplicitRegenerationMakesOneNewRequestOnNewSafeBranch() {
        val seen = AtomicReference<List<Message>>()
        val f = fresh { history, sink -> seen.set(history); sink(ModelEvent.TextFinal("new", "regenerated")) }
        val owner = seed(f, n = 2)
        val before = history(f, owner.id)
        val answer = snapshot(f, owner.id).targets.getValue(before.last().id.value)
        val result = action(f, MessageAction.REGENERATE, answer) as MessageActionResult.Regenerated
        assertEquals(RegenerationSendOutcome.ACCEPTED, result.outcome); assertTrue(result.conversation.id != owner.id)
        val completed = CountDownLatch(1)
        val listener: () -> Unit = { if (f.controller.active == null) completed.countDown() }
        main { f.controller.observe(listener); listener() }; await(completed)
        main { f.controller.unobserve(listener) }
        assertEquals(1, f.opens.get()); assertEquals(before, history(f, owner.id))
        val request = checkNotNull(seen.get())
        assertEquals(3, request.size)
        assertFalse(request.any { it.blocks.filterIsInstance<MessageBlock.Text>().any { b -> b.text == "old answer 1" } })
        assertEquals("original user 1", request.last().blocks.filterIsInstance<MessageBlock.Text>().single().text)
    }

    fun testRegenerationPreparationFailureIsNotSentAndBranchDraftRemainsReachable() {
        val f = fresh(configured = false); val owner = seed(f)
        val before = history(f, owner.id)
        val answer = snapshot(f, owner.id).targets.getValue(before.last().id.value)
        val result = action(f, MessageAction.REGENERATE, answer) as MessageActionResult.Regenerated
        assertEquals(RegenerationSendOutcome.NOT_SENT, result.outcome)
        assertTrue(result.conversation.id != owner.id)
        assertEquals("original user 0", f.db.conversations().single { it.id == result.conversation.id }.draft)
        assertTrue(history(f, result.conversation.id).isEmpty())
        assertEquals(before, history(f, owner.id)); assertEquals(0, f.opens.get())
    }

    fun testRegenerationUnknownSendRetainsExactBranchAndNeverAutomaticallyResends() {
        val f = fresh(); val owner = seed(f)
        val before = history(f, owner.id)
        val answer = snapshot(f, owner.id).targets.getValue(before.last().id.value)
        // The safe fork is empty. Failure occurs only after THIS preparation entered database.send.
        f.db.writableDatabase.execSQL("CREATE TRIGGER ignore_regeneration_send BEFORE INSERT ON messages " +
            "BEGIN SELECT RAISE(IGNORE); END")
        val result = action(f, MessageAction.REGENERATE, answer) as MessageActionResult.Regenerated
        assertEquals(RegenerationSendOutcome.UNKNOWN, result.outcome)
        assertTrue(result.conversation.id != owner.id)
        assertEquals(2, f.db.conversations().size)
        assertEquals("original user 0", f.db.conversations().single { it.id == result.conversation.id }.draft)
        assertTrue(history(f, result.conversation.id).isEmpty())
        f.db.writableDatabase.execSQL("DROP TRIGGER ignore_regeneration_send")
        // Refreshing the UNKNOWN branch is a read, never a replacement preparation or send.
        snapshot(f, result.conversation.id)
        assertEquals(2, f.db.conversations().size); assertEquals(0, f.opens.get())
        assertEquals(before, history(f, owner.id)); assertTrue(history(f, result.conversation.id).isEmpty())
    }

    fun testOldRenderedCallbacksCannotMutateOrOverwriteReplacementEditor() {
        val f = fresh(); val owner = seed(f)
        val snapshot = snapshot(f, owner.id)
        val host = ControllerMessageActionHost(f.controller)
        val results = AtomicInteger(); var ownerCurrent = true
        lateinit var old: ControllerMessageActionHost.Callbacks
        main {
            old = host.bind(snapshot, { ownerCurrent }) { results.incrementAndGet() }
            host.bind(snapshot, { true }) { results.incrementAndGet() }
            old.onDeleteTurn(snapshot.targets.keys.first()) // superseded callback has no backend admission
        }
        assertEquals(2, history(f, owner.id).size)
        val done = CountDownLatch(1)
        val observer: () -> Unit = { if (!f.controller.dataBusy) done.countDown() }
        main {
            val callbacks = host.bind(snapshot, { ownerCurrent }) { results.incrementAndGet() }
            callbacks.onEditUser(snapshot.targets.values.single { it.role == Message.USER_ROLE }.messageId.value)
            ownerCurrent = false; host.invalidate() // revoke before the posted worker completion
            f.controller.observe(observer)
        }
        await(done); main { f.controller.unobserve(observer) }
        assertEquals(0, results.get()); assertEquals(0, f.opens.get())
    }

    fun testBranchUnknownSurvivesInvalidatedHostAndReplacementWithoutRetryOrCleanup() {
        val f = fresh(configured = false); val seeded = staticFixture(f)
        val before = history(f, seeded.conversation.id)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val assets = app.daiyu.android.media.StaticGenerationAssets(File(f.app.filesDir, "static-generation-assets"),
            app.daiyu.android.media.AndroidImageInspector(), AndroidVideoInspector(),
            app.daiyu.android.media.ImageAttachmentLimits(maxImageBytes = app.daiyu.android.model.generation.GenerationRequestPlan.MAX_IMAGE_BYTES),
            VideoAttachmentLimits(maxVideoBytes = app.daiyu.android.model.generation.GenerationRequestPlan.MAX_VIDEO_BYTES.toLong()),
            afterAtomicMove = {
                entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
                throw java.io.IOException("deterministic controller publication UNKNOWN")
            })
        // Replace only the existing lazy asset dependency before messageActionStore initialization.
        // The real controller admission/catch/handler and real branch/rename still execute.
        ChatController::class.java.getDeclaredField("staticGenerationAssets\$delegate").apply {
            isAccessible = true
        }.set(f.controller, lazyOf(assets))
        val snapshot = snapshot(f, seeded.conversation.id)
        val host = ControllerMessageActionHost(f.controller); val oldResults = AtomicInteger()
        val newResults = AtomicInteger(); val done = CountDownLatch(1)
        val observer: () -> Unit = {
            if (!f.controller.dataBusy && f.controller.pendingBranchOutcome() != null) done.countDown()
        }
        main {
            f.controller.observe(observer)
            host.bind(snapshot, { true }) { oldResults.incrementAndGet() }.onBranch(snapshot.targets.keys.first())
        }
        try {
            await(entered)
            main {
                host.invalidate()
                ControllerMessageActionHost(f.controller).bind(snapshot, { true }) { newResults.incrementAndGet() }
            }
        } finally { release.countDown() }
        await(done); main { f.controller.unobserve(observer) }
        val receipt = checkNotNull(f.controller.pendingBranchOutcome())
        val locator = checkNotNull(receipt.locator)
        assertEquals(0, oldResults.get()); assertEquals(0, newResults.get())
        assertTrue(locator.conversationId != seeded.conversation.id)
        assertEquals(1, locator.staticIdentities.size)
        assertFalse(f.db.conversations().any { it.id == locator.conversationId })
        val slot = locator.staticIdentities.single().removePrefix("genstatic-v1-")
        val original = File(f.app.filesDir, "static-generation-assets/generation-static-v1/$slot/original")
        val lease = File(f.app.filesDir, "static-generation-assets/generation-static-v1/$slot.lease")
        assertTrue(original.isFile); assertTrue(lease.isFile)
        val inspected = CountDownLatch(1)
        main { assertTrue(f.controller.inspectBranch(locator) { assertFalse(it); inspected.countDown() }) }
        await(inspected)
        assertSame(receipt, f.controller.pendingBranchOutcome()) // inspection never acknowledges UNKNOWN
        main { f.controller.dismissBranchOutcome(receipt.copy()) }
        assertSame(receipt, f.controller.pendingBranchOutcome()) // stale/value-equal prompt cannot dismiss
        val second = action(f, MessageAction.BRANCH, snapshot.targets.values.first()) as MessageActionResult.Unknown
        assertSame(receipt, f.controller.pendingBranchOutcome()) // a second result never overwrites the first
        main { f.controller.dismissBranchOutcome(receipt) }
        assertSame(second, f.controller.pendingBranchOutcome())
        main { f.controller.dismissBranchOutcome(receipt) }
        assertSame(second, f.controller.pendingBranchOutcome())
        main { f.controller.dismissBranchOutcome(second) }
        assertNull(f.controller.pendingBranchOutcome())
        assertTrue(original.isFile); assertTrue(lease.isFile) // dismissal is not GC
        assertEquals(before, history(f, seeded.conversation.id)); assertEquals(1, f.db.conversations().size)
        assertTrue(seeded.bytes.contentEquals(staticBackup(f, seeded).staticGenerationOriginal(seeded.output)))
        assertEquals(0, f.opens.get()); assertEquals(0, scalarCount(f, "generation_attempts"))
        assertEquals(0, scalarCount(f, "generation_outputs"))
    }

    fun testSameOwnerBusyRerenderDoesNotLoseAdmittedReceipt() {
        val f = fresh(); val owner = seed(f)
        val snapshot = snapshot(f, owner.id)
        val host = ControllerMessageActionHost(f.controller)
        val done = CountDownLatch(1); val results = AtomicInteger()
        main {
            val callbacks = host.bind(snapshot, { true }) { results.incrementAndGet(); done.countDown() }
            callbacks.onEditUser(snapshot.targets.values.single { it.role == Message.USER_ROLE }.messageId.value)
            host.bind(snapshot, { true }) { fail("new callbacks must not steal an admitted result") }
        }
        await(done); assertEquals(1, results.get()); assertEquals(0, f.opens.get())
    }

    fun testDeleteRollsBackWhenReceiptTriggerIgnoresOneMessage() {
        val f = fresh(); val owner = seed(f)
        val before = history(f, owner.id)
        val target = snapshot(f, owner.id).targets.values.first()
        f.db.writableDatabase.execSQL("CREATE TRIGGER ignore_message_delete BEFORE DELETE ON messages BEGIN SELECT RAISE(IGNORE); END")
        assertTrue(action(f, MessageAction.DELETE_TURN, target) is MessageActionResult.Unknown)
        assertEquals(before, history(f, owner.id)); assertEquals(0, f.opens.get())
    }

    fun testDeleteRollsBackWholeRunWhenArchiveInvalidationIsIgnored() {
        val f = fresh(); val owner = seed(f, n = 2)
        val before = history(f, owner.id)
        val target = snapshot(f, owner.id).targets.getValue(before.first().id.value)
        val archive = UUID.randomUUID().toString()
        val hash = app.daiyu.android.context.PrivateBlobs(f.app).put(app.daiyu.android.context.ArchiveCodec.encode(before))
        val db = f.db.writableDatabase
        db.insertOrThrow("context_archives", null, android.content.ContentValues().apply {
            put("id", archive); put("conversation_id", owner.id.value); put("prefix_count", before.size)
            put("revision", app.daiyu.android.domain.context.ContextRevision.digest(before))
            put("summary", "real archived history"); put("blob_hash", hash); put("created", 1L); put("active", 1)
        })
        db.execSQL("CREATE TRIGGER ignore_archive_deactivation BEFORE UPDATE OF active ON context_archives " +
            "WHEN OLD.active=1 AND NEW.active=0 BEGIN SELECT RAISE(IGNORE); END")
        assertTrue(action(f, MessageAction.DELETE_TURN, target) is MessageActionResult.Unknown)
        assertEquals(before, history(f, owner.id))
        app.daiyu.android.context.DatabaseTransactions { db }.run { sql ->
            before.forEach { SQLiteMessageVideoStore.readVerified(sql, it) }
        }
        assertEquals(1, db.rawQuery("SELECT active FROM context_archives WHERE id=?", arrayOf(archive)).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        })
        // Removing the fault permits one explicit retry; no partial deletion escaped the rollback.
        db.execSQL("DROP TRIGGER ignore_archive_deactivation")
        assertEquals(MessageActionResult.Deleted, action(f, MessageAction.DELETE_TURN, target))
        assertEquals(before.drop(2), history(f, owner.id))
        assertEquals(0, db.rawQuery("SELECT active FROM context_archives WHERE id=?", arrayOf(archive)).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        })
        assertEquals(0, f.opens.get())
    }

    fun testMissingVideoWitnessIsNotRepairedByDelete() {
        val f = fresh(); val owner = seed(f)
        val before = history(f, owner.id)
        val target = snapshot(f, owner.id).targets.values.first()
        f.db.writableDatabase.delete("message_video_witnesses", "message_id=?", arrayOf(before.first().id.value))
        assertTrue(action(f, MessageAction.DELETE_TURN, target) is MessageActionResult.Unknown)
        assertTrue(action(f, MessageAction.BRANCH, target) is MessageActionResult.Unknown)
        assertEquals(1, f.db.conversations().size)
        assertEquals(before, history(f, owner.id))
        assertFalse(f.db.readableDatabase.rawQuery("SELECT 1 FROM message_video_witnesses WHERE message_id=?",
            arrayOf(before.first().id.value)).use { it.moveToFirst() })
        assertEquals(0, f.opens.get())
    }

    fun testActiveRunRejectsHistoryMutationAdmission() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val f = fresh { _, sink -> entered.countDown(); check(release.await(15, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("answer", "done")) }
        val owner = seed(f)
        val target = snapshot(f, owner.id).targets.values.first()
        f.db.draft(owner.id, "explicit new request")
        val receipt = CountDownLatch(1)
        main { assertTrue(f.controller.sendText(owner, 1, 1, "explicit new request") { receipt.countDown() }) }
        await(receipt); await(entered)
        try { main { assertFalse(f.controller.messageAction(MessageAction.DELETE_TURN, target) { fail("must not admit") }) } }
        finally { release.countDown() }
    }

    fun testImageBranchCopiesOriginalBytesToIndependentOwner() {
        val f = fresh(); val owner = f.db.create("default")
        val assets = MessageImageAssetStore(File(f.app.filesDir, "isolated-test-image-actions"))
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val userId = MessageId(UUID.randomUUID().toString()); val run = RunId(UUID.randomUUID().toString())
        val media = MediaReference(MediaOwner(owner.id.value, userId.value), UUID.randomUUID().toString(), "image/png", bytes.size, digest, 2, 2)
        assets.restore(media.owner, listOf(media), listOf(bytes))
        val reference = MessageImageReference(MessageImageOwner(owner.id.value, userId.value), media.id, media.mime, media.bytes, media.sha256, 2, 2)
        val user = Message(userId, owner.id, run, Message.USER_ROLE, listOf(MessageBlock.Image(reference)), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId(UUID.randomUUID().toString()), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("answer", "image answer")), status = RunStatus.RUNNING)
        f.db.send(user, assistant); f.db.save(assistant.copy(status = RunStatus.COMPLETED))
        val service = MessageActionStore(f.db, ContextService(f.app, f.db), assets)
        val target = service.snapshot(owner.id).targets.getValue(userId.value)
        val copy = service.branch(target)
        val copiedRef = (history(f, copy.id).first().blocks.single() as MessageBlock.Image).reference
        assertTrue(copiedRef.owner != reference.owner)
        service.deleteTurn(target)
        assets.discardUnreferenced(media.owner, listOf(media)) // confirmed DB absence; must not remove copied original
        val copiedMedia = MediaReference(MediaOwner(copiedRef.owner.conversationId, copiedRef.owner.messageId), copiedRef.id,
            copiedRef.mime, copiedRef.bytes, copiedRef.sha256, copiedRef.width, copiedRef.height)
        assertTrue(bytes.contentEquals(assets.resolver.read(copiedMedia.owner, copiedMedia)))
        assertEquals(0, f.opens.get())
    }

    private fun verifiedVideos(f: Fixture, row: Message) = DatabaseTransactions { f.db.readableDatabase }.run {
        SQLiteMessageVideoStore.readVerified(it, row)
    }

    fun testRealVideoBranchRestoresIndependentOriginalAndWitnessWithoutSending() {
        val f = fresh(); val owner = f.db.create("default")
        val original = File(f.app.filesDir, "branch-source.mp4")
        encodeSmallMp4(original)
        val bytes = original.readBytes()
        val assets = VideoAssetStore(File(f.app.filesDir, "branch-videos"))
        val draftOwner = VideoAttachmentOwner(owner.id.value, UUID.randomUUID().toString())
        val attachment = assets.import(draftOwner, bytes.inputStream())
        val user = Message(MessageId(UUID.randomUUID().toString()), owner.id, RunId(UUID.randomUUID().toString()),
            Message.USER_ROLE, listOf(MessageBlock.Text("text", "original video")), RunStatus.COMPLETED)
        val reference = assets.prepare(MessageVideoOwner(owner.id.value, user.id.value), attachment)
        val answer = user.copy(id = MessageId(UUID.randomUUID().toString()), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("answer", "original answer")), status = RunStatus.RUNNING)
        f.db.send(user, answer, videoInputs = listOf(MessageVideoInput(reference.owner, listOf(reference))))
        f.db.save(answer.copy(status = RunStatus.COMPLETED))
        val before = history(f, owner.id)
        val service = MessageActionStore(f.db, ContextService(f.app, f.db),
            MessageImageAssetStore(File(f.app.filesDir, "branch-images")), MessageVideoBackupAssets(assets))
        val target = service.snapshot(owner.id).targets.getValue(user.id.value)
        // No backup-style replace is allowed: an ignored real sidecar insert must roll back
        // conversation/messages/witnesses together, while leaving source originals readable.
        f.db.writableDatabase.execSQL("CREATE TRIGGER ignore_branch_video BEFORE INSERT ON message_video_inputs " +
            "BEGIN SELECT RAISE(IGNORE); END")
        assertTrue(runCatching { service.branch(target) }.isFailure)
        assertEquals(1, f.db.conversations().size); assertEquals(before, history(f, owner.id))
        assertTrue(bytes.contentEquals(assets.openOriginal(reference.owner, reference).use { it.readBytes() }))
        f.db.writableDatabase.execSQL("DROP TRIGGER ignore_branch_video")
        val copy = service.branch(target)
        val copied = history(f, copy.id)
        val copiedReference = verifiedVideos(f, copied.first()).single().references.single()
        assertEquals(reference.copy(owner = copiedReference.owner), copiedReference)
        assertEquals(MessageVideoOwner(copy.id.value, copied.first().id.value), copiedReference.owner)
        assertTrue(copied.first().runId != user.runId)
        assertTrue(verifiedVideos(f, copied.last()).isEmpty())
        assertEquals(before, history(f, owner.id))
        service.deleteTurn(target)
        assertTrue(assets.discardUnreferenced(reference.owner, reference))
        assertTrue(bytes.contentEquals(assets.openOriginal(copiedReference.owner, copiedReference).use { it.readBytes() }))
        assertEquals(copied, history(f, copy.id)); verifiedVideos(f, copied.first())
        assertEquals(0, f.opens.get())
    }

    fun testVideoBranchWithMissingPrivateOriginalRollsBackInsteadOfDroppingAttachment() {
        val f = fresh(); val owner = f.db.create("default")
        val user = Message(MessageId(UUID.randomUUID().toString()), owner.id, RunId(UUID.randomUUID().toString()),
            Message.USER_ROLE, listOf(MessageBlock.Text("text", "missing private original")), RunStatus.COMPLETED)
        val videoOwner = MessageVideoOwner(owner.id.value, user.id.value)
        val reference = MessageVideoReference(videoOwner, UUID.randomUUID().toString(), "video/mp4", 3L,
            "ab".repeat(32), 1000, 32, 24)
        val answer = user.copy(id = MessageId(UUID.randomUUID().toString()), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("answer", "old answer")), status = RunStatus.RUNNING)
        f.db.send(user, answer, videoInputs = listOf(MessageVideoInput(videoOwner, listOf(reference))))
        f.db.save(answer.copy(status = RunStatus.COMPLETED))
        val before = history(f, owner.id)
        val service = MessageActionStore(f.db, ContextService(f.app, f.db),
            MessageImageAssetStore(File(f.app.filesDir, "missing-images")),
            MessageVideoBackupAssets(VideoAssetStore(File(f.app.filesDir, "missing-videos"))))
        val target = service.snapshot(owner.id).targets.getValue(user.id.value)
        assertTrue(runCatching { service.branch(target) }.isFailure)
        assertEquals(1, f.db.conversations().size); assertEquals(before, history(f, owner.id))
        assertEquals(reference, verifiedVideos(f, user).single().references.single())
        assertEquals(0, f.opens.get())
    }

    /** Fake inspector only for complex historical shapes; actual private files/read/restore/DB.
     * The separate real-MP4 test above covers AndroidVideoInspector, not this synthetic fixture. */
    private fun assertSegmentVideoBranch(standalone: Boolean) {
        val f = fresh(); val owner = f.db.create("default")
        val assets = VideoAssetStore(File(f.app.filesDir, "segment-videos"), VideoAttachmentLimits(),
            VideoInspector { _, _, _ -> VideoMetadata("video/mp4", 1000, 32, 24) })
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val user = Message(MessageId(UUID.randomUUID().toString()), owner.id, RunId(UUID.randomUUID().toString()),
            Message.USER_ROLE, listOf(MessageBlock.Text("text", "initial")), RunStatus.COMPLETED)
        val supplement = user.copy(id = MessageId(UUID.randomUUID().toString()),
            blocks = listOf(MessageBlock.Text("text", "supplement video")))
        val answerId = MessageId(UUID.randomUUID().toString())
        val answer = user.copy(id = answerId, role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("answer", "projection")), historySegments = listOf(
                MessageHistorySegment(answerId, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("first", "before"))),
                MessageHistorySegment(supplement.id, Message.USER_ROLE, supplement.blocks),
                MessageHistorySegment(answerId, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("last", "after")))))
        val sourceOwner = MessageVideoOwner(owner.id.value, supplement.id.value)
        val sourceRef = MessageVideoReference(sourceOwner, UUID.randomUUID().toString(), "video/mp4", bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, 1000, 32, 24)
        assets.restore(sourceOwner, listOf(sourceRef), listOf(bytes))
        val rows = listOf(user, answer) + if (standalone) listOf(supplement) else emptyList()
        MessageHistory.expand(rows)
        DatabaseTransactions { f.db.writableDatabase }.run { db ->
            rows.forEach { row ->
                db.insertOrThrow("messages", null, android.content.ContentValues().apply {
                    put("id", row.id.value); put("conversation_id", owner.id.value); put("run_id", row.runId.value)
                    put("role", row.role); put("blocks", ArchiveCodec.blocks(row.blocks).toString())
                    put("status", row.status.name); put("protocol", org.json.JSONArray(row.protocolItems).toString())
                    put("history_segments", MessageHistoryCodec.encode(row.historySegments).toString())
                })
                val inputs = if (row.id == (if (standalone) supplement.id else answer.id))
                    listOf(MessageVideoInput(sourceOwner, listOf(sourceRef))) else emptyList()
                SQLiteMessageVideoStore.insert(db, row, inputs)
            }
        }
        val service = MessageActionStore(f.db, ContextService(f.app, f.db),
            MessageImageAssetStore(File(f.app.filesDir, "segment-images")), MessageVideoBackupAssets(assets))
        val target = service.snapshot(owner.id).targets.getValue(supplement.id.value)
        assertEquals(answer.id, target.ownerId) // displayed supplement resolves to authoritative owner
        val copy = service.branch(target)
        val copied = history(f, copy.id)
        val copiedAnswer = copied.single { it.role == Message.ASSISTANT_ROLE }
        val copiedSegment = copiedAnswer.historySegments.single { it.role == Message.USER_ROLE }
        val physical = if (standalone) copied.single { it.id == copiedSegment.messageId } else copiedAnswer
        val input = verifiedVideos(f, physical).single()
        assertEquals(MessageVideoOwner(copy.id.value, copiedSegment.messageId.value), input.owner)
        assertEquals(sourceRef.copy(owner = input.owner), input.references.single())
        assertTrue(copiedSegment.messageId != supplement.id); assertTrue(physical.runId != user.runId)
        assertEquals(rows, history(f, owner.id))
        service.deleteTurn(target)
        assertTrue(assets.discardUnreferenced(sourceOwner, sourceRef))
        assertTrue(bytes.contentEquals(assets.openOriginal(input.owner, input.references.single()).use { it.readBytes() }))
        assertEquals(0, f.opens.get())
    }

    fun testSegmentOnlyVideoOwnerAndRunAreRemapped() = assertSegmentVideoBranch(false)
    fun testStandaloneSupplementVideoOwnerAndRunAreRemapped() = assertSegmentVideoBranch(true)

    /** Same on-device AVC fixture shape as VideoChatHostAndroidTest; no network or paid provider. */
    private fun encodeSmallMp4(file: File) {
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false; var muxerStarted = false
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 64, 64).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000); setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
            val frame = ByteArray(64 * 64 * 3 / 2) { if (it < 64 * 64) 96.toByte() else 128.toByte() }
            val info = MediaCodec.BufferInfo()
            var inputCount = 0; var outputEnded = false; var track = -1
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!outputEnded && System.nanoTime() < deadline) {
                if (inputCount <= 3) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (inputCount == 3) codec.queueInputBuffer(index, 0, 0, 300_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            codec.getInputBuffer(index)!!.apply { clear(); put(frame) }
                            codec.queueInputBuffer(index, 0, frame.size, inputCount * 100_000L, 0)
                        }
                        inputCount++
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted); track = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(muxerStarted)
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            check(outputEnded && muxerStarted) { "AVC test fixture encoder did not complete" }
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
            try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
        }
        check(file.length() > 0)
    }

    private data class StaticFixture(val conversation: Conversation, val output: StaticGenerationOutput,
        val assets: app.daiyu.android.media.StaticGenerationAssets, val bytes: ByteArray)

    /** Offline imported-style evidence only. No completed tool is given a manufactured attempt. */
    private fun staticFixture(f: Fixture): StaticFixture {
        val credentials = mutableMapOf<String, String>()
        val metadata = f.app.getSharedPreferences(app.daiyu.android.model.generation.GenerationSettingsStore.METADATA_NAMESPACE, 0)
        val scope = app.daiyu.android.model.generation.GenerationSettingsStore(metadata,
            { credentials.getValue(it) }, { id, value -> credentials[id] = value }).save(
            app.daiyu.android.model.generation.GenerationSettingsValues(listOf(
                app.daiyu.android.model.generation.GenerationProfileSettings(label = "offline fixture",
                    kind = app.daiyu.android.model.generation.GenerationKind.IMAGE,
                    protocol = app.daiyu.android.model.generation.GenerationProtocol.OPENAI_IMAGES_JSON,
                    endpoint = "https://example.invalid/images/generations", model = "offline", apiKey = "synthetic-in-memory")),
                0, false, emptySet()), null).accountScope
        val conversation = f.db.create("default"); val run = RunId(UUID.randomUUID().toString())
        val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("static-call-${UUID.randomUUID()}"), "generate_images", "{}"), "historical only")
        val assistant = Message(MessageId(UUID.randomUUID().toString()), conversation.id, run, Message.ASSISTANT_ROLE,
            listOf(tool), RunStatus.RUNNING)
        f.db.send(Message(MessageId(UUID.randomUUID().toString()), conversation.id, run, Message.USER_ROLE,
            listOf(MessageBlock.Text("body", "offline static history")), RunStatus.COMPLETED), assistant)
        f.db.save(assistant.copy(status = RunStatus.COMPLETED))
        val bytes = app.daiyu.android.validDeviceTestPng()
        val hash = app.daiyu.android.context.PrivateBlobs.digest(bytes)
        val origin = GenerationAttemptBinding("deleted-origin", "deleted-owner", "origin-run", "origin-call", "generate_images")
        val source = StaticGenerationProvenance("offline-origin", origin.operationId, "offline-item", 0,
            origin.conversationId, origin.assistantMessageId, origin.runId, origin.toolCallId, origin.toolName,
            "a".repeat(64), app.daiyu.android.model.generation.GenerationProtocol.OPENAI_IMAGES_JSON,
            "genasset-v1-${"b".repeat(64)}-$hash", app.daiyu.android.model.generation.GenerationKind.IMAGE,
            "image/png", bytes.size.toLong(), hash, 1, 1, 0)
        val output = StaticGenerationOutput(source, StaticGenerationDestination(scope, conversation.id.value,
            assistant.id.value, run.value, tool.invocation.id.value, tool.invocation.name))
        val assets = app.daiyu.android.media.StaticGenerationAssets(File(f.app.filesDir, "static-generation-assets"))
        val lease = assets.prepare(output, app.daiyu.android.media.StaticGenerationOriginalReader {
            check(!f.db.writableDatabase.inTransaction()); bytes.inputStream()
        })
        assets.publish(lease)
        DatabaseTransactions { f.db.writableDatabase }.run { db ->
            app.daiyu.android.backup.BackupHistoryRows.install(db)
            app.daiyu.android.backup.StaticGenerationCodec.saveInTransaction(db, output,
                app.daiyu.android.backup.staticMessageRows(history(f, conversation.id)))
        }
        assets.settle(lease, app.daiyu.android.media.StaticGenerationSettlement.COMMITTED)
        assertEquals(0, scalarCount(f, "generation_attempts")); assertEquals(0, scalarCount(f, "generation_outputs"))
        return StaticFixture(conversation, output, assets, bytes)
    }
    private fun scalarCount(f: Fixture, table: String) = f.db.readableDatabase.rawQuery("SELECT count(*) FROM $table", null)
        .use { check(it.moveToFirst()); it.getInt(0) }
    private fun staticBackup(f: Fixture, seeded: StaticFixture) = app.daiyu.android.backup.NeutralBackupService(f.app, f.db).withStaticAssets(seeded.assets)

    fun testProductionControllerStaticBranchAgainAndSourceDeletionAndV5DuplicateReverification() {
        val f = fresh(configured = false); val seeded = staticFixture(f); val service = staticBackup(f, seeded)
        val target = snapshot(f, seeded.conversation.id).targets.values.first()
        val first = (action(f, MessageAction.BRANCH, target) as MessageActionResult.Branch).conversation
        val second = (action(f, MessageAction.BRANCH, target) as MessageActionResult.Branch).conversation
        assertEquals(MessageActionResult.Deleted, action(f, MessageAction.DELETE_TURN, target))
        val sourceDirectory = File(File(f.app.filesDir, "static-generation-assets/generation-static-v1"), seeded.output.identity.removePrefix("genstatic-v1-"))
        assertTrue(sourceDirectory.deleteRecursively()) // test-owned source fixture, never production GC
        val nested = (action(f, MessageAction.BRANCH, snapshot(f, first.id).targets.values.first()) as MessageActionResult.Branch).conversation
        for (conversation in listOf(first, second, nested)) {
            val output = service.staticGenerationOutputs(conversation.id).single()
            assertEquals(seeded.output.source, output.source)
            assertTrue(seeded.bytes.contentEquals(service.staticGenerationOriginal(output)))
            val done = CountDownLatch(1); val display = AtomicReference<List<ChatController.StaticGenerationAssetDisplay>?>()
            main { assertTrue(f.controller.loadStaticGenerationOutputs(conversation.id) { display.set(it); done.countDown() }) }
            await(done); assertNotNull(display.get()!!.single().thumbnail)
        }
        assertEquals(0, scalarCount(f, "generation_attempts")); assertEquals(0, scalarCount(f, "generation_outputs")); assertEquals(0, f.opens.get())
        val text = service.exportPackage(); val envelope = org.json.JSONObject(text)
        assertEquals(7, envelope.getInt("version")); assertEquals(3, service.preflight(text).staticGenerationOutputs)
        val to = fresh(configured = false)
        val importedAssets = app.daiyu.android.media.StaticGenerationAssets(File(to.app.filesDir, "static-generation-assets"))
        val importer = app.daiyu.android.backup.NeutralBackupService(to.app, to.db).withStaticAssets(importedAssets)
        assertFalse(importer.importPackage(text).alreadyImported); assertTrue(importer.importPackage(text).alreadyImported)
        assertEquals(0, scalarCount(to, "generation_attempts")); assertEquals(0, scalarCount(to, "generation_outputs"))
        val imported = importer.staticGenerationOutputs().first()
        val original = File(File(to.app.filesDir, "static-generation-assets/generation-static-v1"), imported.identity.removePrefix("genstatic-v1-") + "/original")
        assertTrue(original.delete())
        assertTrue(runCatching { importer.importPackage(text) }.isFailure)
        assertEquals(1, scalarCount(to, "neutral_imports")); assertEquals(0, to.opens.get())
    }

    fun testProductionExplicitStaticSaveChecksVisitBeforeAndAfterProviderOpenWithoutRetry() {
        val f = fresh(configured = false); val seeded = staticFixture(f)
        val destination = File(f.app.filesDir, "explicit-static-save.png")
        val visits = java.util.concurrent.atomic.AtomicBoolean(false)
        val opens = AtomicInteger(); var revokeOnOpen = false
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: android.net.Uri) = "image/png"
            override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
                opens.incrementAndGet(); if (revokeOnOpen) visits.set(false)
                return android.os.ParcelFileDescriptor.open(destination, android.os.ParcelFileDescriptor.parseMode(mode))
            }
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): android.database.Cursor? = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = error("unused")
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = error("unused")
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = error("unused")
        }
        provider.attachInfo(f.app, android.content.pm.ProviderInfo().apply { authority = "p37-static-test"; exported = false })
        val resolver = android.test.mock.MockContentResolver(f.app).apply { addProvider("p37-static-test", provider) }
        fun save() {
            val done = CountDownLatch(1)
            val observer: () -> Unit = { if (!f.controller.dataBusy && f.controller.dataNotice != null) done.countDown() }
            main {
                f.controller.consumeDataNotice(); f.controller.observe(observer)
                f.controller.saveStaticGenerationOriginal(seeded.output, android.net.Uri.parse("content://p37-static-test/original"), visits, resolver)
            }
            try { await(done) } finally { main { f.controller.unobserve(observer) } }
        }
        save(); assertEquals(0, opens.get()); assertFalse(destination.exists())
        visits.set(true); revokeOnOpen = true
        save(); assertEquals(1, opens.get()); assertEquals(0L, destination.length())
        visits.set(true); revokeOnOpen = false
        save(); assertEquals(2, opens.get()); assertTrue(seeded.bytes.contentEquals(destination.readBytes()))
        assertEquals(0, f.opens.get()); assertEquals(0, scalarCount(f, "generation_attempts")); assertEquals(0, scalarCount(f, "generation_outputs"))
    }

    fun testNoStaticCapabilityBranchAndCompleteExportFailClosed() {
        val f = fresh(configured = false); val seeded = staticFixture(f)
        val plain = MessageActionStore(f.db, ContextService(f.app, f.db), MessageImageAssetStore(File(f.app.filesDir, "plain-static-images")))
        val target = plain.snapshot(seeded.conversation.id).targets.values.first()
        assertTrue(runCatching { plain.branch(target) }.isFailure)
        assertTrue(runCatching { ContextService(f.app, f.db).branch(seeded.conversation.id, history(f, seeded.conversation.id).last().id) }.isFailure)
        assertTrue(runCatching { app.daiyu.android.backup.NeutralBackupService(f.app, f.db).exportPackage() }.isFailure)
        assertEquals(1, f.db.conversations().size); assertEquals(0, f.opens.get())
    }

    fun testProductionLastStaticWriteSourceTamperRollsBackEverything() {
        val f = fresh(configured = false); val seeded = staticFixture(f)
        val before = history(f, seeded.conversation.id)
        f.db.writableDatabase.execSQL("CREATE TRIGGER p37_late_source AFTER INSERT ON neutral_backup_history " +
            "WHEN NEW.kind='generation_static_output' AND NEW.identity<>'${seeded.output.identity}' BEGIN " +
            "UPDATE neutral_backup_history SET payload=replace(payload,'image/png','image/jpeg') WHERE identity='${seeded.output.identity}'; END")
        val result = action(f, MessageAction.BRANCH, snapshot(f, seeded.conversation.id).targets.values.first())
        assertTrue(result is MessageActionResult.Rejected)
        assertEquals(before, history(f, seeded.conversation.id)); assertEquals(1, f.db.conversations().size)
        assertEquals(seeded.output, staticBackup(f, seeded).staticGenerationOutputs().single())
        assertEquals(0, scalarCount(f, "generation_attempts")); assertEquals(0, scalarCount(f, "generation_outputs")); assertEquals(0, f.opens.get())
    }

    fun testProductionStaticBranchReadsAccountOnlyOutsideSqlAndRechecksBeforeT2() {
        val f = fresh(configured = false); val seeded = staticFixture(f); val service = staticBackup(f, seeded)
        val calls = AtomicInteger()
        val integration = app.daiyu.android.backup.StaticGenerationBranch(seeded.assets,
            account = {
                check(!f.db.writableDatabase.inTransaction())
                calls.incrementAndGet()
                seeded.output.destination.accountScope
            }, liveOriginal = { error("not live") }, historicalOriginal = { error("not backup history") },
            lookup = { identity -> check(!f.db.writableDatabase.inTransaction()); service.findStaticGeneration(identity) })
        val store = MessageActionStore(f.db, ContextService(f.app, f.db), MessageImageAssetStore(File(f.app.filesDir, "account-static-images")),
            staticGeneration = integration)
        val copied = store.branch(store.snapshot(seeded.conversation.id).targets.values.first())
        assertTrue(seeded.conversation.id != copied.id)
        assertEquals(2, calls.get())
        assertEquals(2, f.db.conversations().size)
        assertEquals(0, f.opens.get())
    }

    fun testProductionStaticBranchRejectsChangedAccountBeforeT2WithoutAnotherRead() {
        val f = fresh(configured = false); val seeded = staticFixture(f); val service = staticBackup(f, seeded)
        val calls = AtomicInteger()
        val before = history(f, seeded.conversation.id)
        val integration = app.daiyu.android.backup.StaticGenerationBranch(seeded.assets,
            account = {
                check(!f.db.writableDatabase.inTransaction())
                if (calls.incrementAndGet() == 1) seeded.output.destination.accountScope else "changed-account"
            }, liveOriginal = { error("not live") }, historicalOriginal = { error("not backup history") },
            lookup = { identity -> service.findStaticGeneration(identity) })
        val store = MessageActionStore(f.db, ContextService(f.app, f.db), MessageImageAssetStore(File(f.app.filesDir, "changed-account-images")),
            staticGeneration = integration)
        try { store.branch(store.snapshot(seeded.conversation.id).targets.values.first()); fail("changed account") }
        catch (_: app.daiyu.android.context.BranchRolledBack) { }
        assertEquals(2, calls.get()); assertEquals(1, f.db.conversations().size)
        assertEquals(before, history(f, seeded.conversation.id))
        seeded.assets.openVerified(seeded.output, service::findStaticGeneration).use { assertTrue(it.read() >= 0) }
        assertEquals(0, f.opens.get())
    }

    fun testProductionFullStaticSourceSetCasRejectsInsertionBetweenT1AndT2WithoutRetry() {
        val f = fresh(configured = false); val seeded = staticFixture(f); val service = staticBackup(f, seeded)
        val changed = java.util.concurrent.atomic.AtomicBoolean(false)
        val reads = AtomicInteger()
        val integration = app.daiyu.android.backup.StaticGenerationBranch(seeded.assets,
            account = { check(!f.db.writableDatabase.inTransaction()); seeded.output.destination.accountScope }, liveOriginal = { error("not live") },
            historicalOriginal = { error("not backup history") }, lookup = { identity ->
                check(!f.db.writableDatabase.inTransaction()); reads.incrementAndGet()
                val selected = service.findStaticGeneration(identity)
                if (changed.compareAndSet(false, true)) DatabaseTransactions { f.db.writableDatabase }.run { db ->
                    app.daiyu.android.backup.StaticGenerationCodec.saveInTransaction(db,
                        seeded.output.copy(source = seeded.output.source.copy(outputIndex = 1)),
                        app.daiyu.android.backup.staticMessageRows(history(f, seeded.conversation.id)))
                }
                selected
            })
        val store = MessageActionStore(f.db, ContextService(f.app, f.db), MessageImageAssetStore(File(f.app.filesDir, "cas-images")),
            staticGeneration = integration)
        val failure = runCatching { store.branch(store.snapshot(seeded.conversation.id).targets.values.first()) }.exceptionOrNull()
        assertTrue(failure is app.daiyu.android.context.BranchRolledBack)
        assertEquals(1, reads.get()); assertEquals(1, f.db.conversations().size)
        assertEquals(2, service.staticGenerationOutputs(seeded.conversation.id).size)
        assertEquals(0, scalarCount(f, "generation_attempts")); assertEquals(0, scalarCount(f, "generation_outputs")); assertEquals(0, f.opens.get())
    }

    fun testProductionPublicationUnknownCarriesPreallocatedLocatorAndRetainsFiles() {
        val f = fresh(configured = false); val seeded = staticFixture(f); val service = staticBackup(f, seeded)
        val assets = app.daiyu.android.media.StaticGenerationAssets(File(f.app.filesDir, "unknown-static"),
            app.daiyu.android.media.AndroidImageInspector(), AndroidVideoInspector(),
            app.daiyu.android.media.ImageAttachmentLimits(maxImageBytes = app.daiyu.android.model.generation.GenerationRequestPlan.MAX_IMAGE_BYTES),
            VideoAttachmentLimits(maxVideoBytes = app.daiyu.android.model.generation.GenerationRequestPlan.MAX_VIDEO_BYTES.toLong()),
            afterAtomicMove = { throw java.io.IOException("offline publication fault") })
        val integration = app.daiyu.android.backup.StaticGenerationBranch(assets,
            account = { check(!f.db.writableDatabase.inTransaction()); seeded.output.destination.accountScope }, liveOriginal = { error("not live") },
            historicalOriginal = { error("not backup history") }, lookup = { identity ->
                check(!f.db.writableDatabase.inTransaction()); service.findStaticGeneration(identity)
            })
        // Read the static SOURCE from its real store, while faulting only the destination store.
        // The destination's source lookup alone cannot open another store's bytes, so copy the
        // source fixture into this test-private store without adding any new SQL history.
        run {
            val normal = app.daiyu.android.media.StaticGenerationAssets(File(f.app.filesDir, "unknown-static"))
            val lease = normal.prepare(seeded.output, app.daiyu.android.media.StaticGenerationOriginalReader { seeded.bytes.inputStream() })
            normal.publish(lease); normal.settle(lease, app.daiyu.android.media.StaticGenerationSettlement.COMMITTED)
        }
        val store = MessageActionStore(f.db, ContextService(f.app, f.db), MessageImageAssetStore(File(f.app.filesDir, "unknown-images")),
            staticGeneration = integration)
        val failure = runCatching { store.branch(store.snapshot(seeded.conversation.id).targets.values.first()) }.exceptionOrNull()
        assertTrue(failure is app.daiyu.android.context.BranchOutcomeUnknown)
        val locator = (failure as app.daiyu.android.context.BranchOutcomeUnknown).locator
        assertTrue(locator.conversationId != seeded.conversation.id); assertEquals(1, locator.staticIdentities.size)
        assertFalse(f.db.conversations().any { it.id == locator.conversationId })
        val slot = locator.staticIdentities.single().removePrefix("genstatic-v1-")
        assertTrue(File(f.app.filesDir, "unknown-static/generation-static-v1/$slot/original").isFile)
        assertTrue(File(f.app.filesDir, "unknown-static/generation-static-v1/$slot.lease").isFile)
        assertEquals(0, scalarCount(f, "generation_attempts")); assertEquals(0, scalarCount(f, "generation_outputs")); assertEquals(0, f.opens.get())
    }

    override fun tearDown() {
        try { fixtures.forEach { f ->
            f.controller.stop(); f.controller.close(); f.db.close()
            f.app.deleteDatabase("conversations.db"); f.app.filesDir.deleteRecursively()
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it.startsWith("${f.alias}.") }.forEach(keys::deleteEntry)
        } } finally { super.tearDown() }
    }
}
