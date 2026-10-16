package app.daiyu.android

import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.video.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.catalog.OpenRouterVideoEvidence
import app.daiyu.android.model.videos.*
import app.daiyu.android.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real SQLite, parent session/main dispatcher and receiving journal. No paid network or decoder.
 * Evidence is constructed by reflection ONLY in this test: this is deliberately not a production
 * proof, catalogue fetch or assertion of remote support. Production has no evidence injection API.
 */
@Suppress("DEPRECATION")
class ControllerVideoSessionTest : InstrumentationTestCase() {
    private lateinit var db: ConversationDatabase
    private lateinit var root: File
    private val settings = ModelSettings(OpenRouterVideoCapability.ENDPOINT, "fixture/model", "synthetic-key", false)
    private val provider by lazy { ProviderSnapshot.capture(settings) }
    private val epoch = AtomicLong(7)
    private lateinit var conversation: ConversationId
    private val sessions = mutableListOf<ControllerVideoSession>()
    private class Ui : ControllerVideoUi {
        var editor = ControllerVideoEditor(null, 3, 4, "  unchanged original  ", true)
        val decisions = mutableListOf<(Boolean) -> Unit>()
        val prompts = mutableListOf<VideoApprovalGate.Prompt>()
        override fun editor() = editor
        override fun showRun(prompt: VideoApprovalGate.Prompt, text: String, decision: (Boolean) -> Unit) {
            check(text == editor.text); prompts += prompt; decisions += decision
        }
        override fun showPost(prompt: ControllerVideoPostPrompt, decision: (Boolean) -> Unit) = error("no model open in this test")
    }
    private lateinit var ui: Ui
    private fun <T> main(work: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(work) }
        return checkNotNull(result).getOrThrow()
    }
    override fun setUp() {
        super.setUp()
        val token = UUID.randomUUID().toString()
        val base = testContext(instrumentation.targetContext, "video-session-$token-")
        root = File(base.cacheDir, "video-session-$token").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        db = ConversationDatabase(context)
        conversation = db.create(db.selectedAssistantId()).id
        ui = Ui().also { it.editor = it.editor.copy(conversation = conversation.value) }
    }
    override fun tearDown() {
        try { main { sessions.forEach { it.revoke() } }; db.close(); root.deleteRecursively() }
        finally { super.tearDown() }
    }
    private fun seed(count: Int) {
        repeat(count) { n ->
            val run = RunId("seed-$n")
            val user = Message(MessageId("user-$n"), conversation, run, Message.USER_ROLE,
                listOf(MessageBlock.Text("text", "history-$n")), RunStatus.COMPLETED)
            val assistant = user.copy(id = MessageId("assistant-$n"), role = Message.ASSISTANT_ROLE)
            val owner = MessageVideoOwner(conversation.value, user.id.value)
            val refs = if (n == 0) listOf(MessageVideoInput(owner, listOf(MessageVideoReference(owner,
                "original", "video/mp4", 3, "a".repeat(64), 1000, 32, 24)))) else emptyList()
            db.send(user, assistant, videoInputs = refs)
        }
    }
    private fun proof(revision: Long): OpenRouterVideoEvidence {
        val constructor = OpenRouterVideoEvidence::class.java.declaredConstructors.single { it.parameterCount == 7 }
        constructor.isAccessible = true
        return constructor.newInstance(provider, revision, settings.model,
            "https://openrouter.ai/api/v1/models", settings.requestEndpoint(), setOf("text", "video"),
            { epoch.get() == revision }) as OpenRouterVideoEvidence
    }
    private fun newSession(): ControllerVideoSession {
        val snapshot = ControllerVideoHistory(db).capture(conversation)
        val run = RunId(UUID.randomUUID().toString())
        val assistant = Message(MessageId("active-${run.value}"), conversation, run, Message.ASSISTANT_ROLE,
            emptyList(), RunStatus.RUNNING)
        val request = FrozenVideoApprovalRequest.forText(conversation.value, run.value, "parent-preparation",
            ui.editor.revision, ui.editor.text, provider, epoch.get(), MessageHistory.expand(snapshot.rows), snapshot.inputs)
        return main {
            ControllerVideoSession(Handler(Looper.getMainLooper()), db, provider, epoch.get(), sessions.size.toLong(), epoch::get,
                { settings }, ui, ui.editor, snapshot, assistant, request, { true }, { null }, {}).also { sessions += it }
        }
    }
    private class Wait(val done: CountDownLatch, val result: AtomicReference<Any?>)
    private fun authorize(session: ControllerVideoSession): Wait {
        val result = AtomicReference<Any?>()
        val done = CountDownLatch(1)
        val evidence = proof(epoch.get())
        Thread {
            try { result.set(session.authorize(evidence)) } catch (failure: Throwable) { result.set(failure) }
            finally { done.countDown() }
        }.start()
        return Wait(done, result)
    }
    private fun awaitPrompt(count: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (main { ui.decisions.size } < count && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(count, main { ui.decisions.size })
    }
    fun testFullHistoryNotTheLatestSqlitePageIsBoundAtRunConfirmation() {
        seed(104) // 208 physical rows, beyond the ordinary UI's 200-row page.
        val session = newSession()
        assertEquals(208, session.snapshot.rows.size)
        assertEquals("user-0", session.snapshot.inputs.single().owner.messageId)
        val wait = authorize(session)
        awaitPrompt(1)
        assertEquals(1, main { ui.prompts.single().videoCount })
        // Mutate the earliest row (which a latest-page-only parent would not see).
        val first = session.snapshot.rows.first()
        db.save(first.copy(blocks = listOf(MessageBlock.Text("text", "changed earliest input"))))
        main { ui.decisions.single()(true) }
        assertTrue(wait.done.await(5, TimeUnit.SECONDS))
        assertTrue(wait.result.get() is Throwable)
        assertTrue(session.isRevoked())
    }
    fun testConfigurationEpochAndStaleCallbackCannotReviveOrCancelReplacement() {
        seed(1)
        val old = newSession()
        val oldWait = authorize(old)
        awaitPrompt(1)
        val stale = main { ui.decisions.single() }
        main { old.revoke(); epoch.incrementAndGet() }
        assertTrue(oldWait.done.await(5, TimeUnit.SECONDS))
        val replacement = newSession()
        val replacementWait = authorize(replacement)
        awaitPrompt(2)
        main { stale(true) }
        assertFalse(replacement.isRevoked())
        assertEquals(1L, replacementWait.done.count)
        main { ui.decisions.last()(true) }
        assertTrue(replacementWait.done.await(5, TimeUnit.SECONDS))
        assertTrue(replacementWait.result.get() is OpenRouterVideoCapability)
        assertFalse(replacement.isRevoked())
        main { replacement.revoke() }
        assertTrue(replacement.isRevoked())
    }

    fun testAcceptedOriginalIdentityRecoversOnlyReceiptAndCannotConsumeNewEditor() {
        val original = "  exact untrimmed text  "
        db.draft(conversation, original)
        val assets = VideoAssetStore(File(root, "assets"), VideoAttachmentLimits(), VideoInspector { _, _, _ ->
            VideoMetadata("video/mp4", 1000, 32, 24)
        })
        val draftOwner = VideoAttachmentOwner(conversation.value, "draft")
        val video = assets.import(draftOwner, byteArrayOf(1, 2, 3).inputStream())
        val request = VideoDraftRequest(conversation.value, draftOwner, 9, original, listOf(video))
        val preferences = instrumentation.targetContext.getSharedPreferences("video-receipt-${UUID.randomUUID()}", 0)
        try {
            val journal = ControllerVideoJournal(preferences)
            val attempt = ControllerVideoAttempt(request, journal)
            assertNull(attempt.reserve(db))
            val capability = OpenRouterVideoCapability.grant(settings.model, settings.requestEndpoint(), true, true)
            val prepared = attempt.prepare(assets, provider, capability) { false }
            val images = ControllerMessageImages(db, MessageImageAssetStore(File(root, "images")))
            val messages = ControllerMessageVideos(db, assets, images)
            val snapshot = messages.history.capture(conversation)
            val plan = messages.preflight(snapshot, provider, capability, prepared) { false }
            val receipt = attempt.commit(db, snapshot, prepared, plan, db.assistantId(conversation), original, { false })
            assertEquals(VideoDraftOutcome.ACCEPTED, receipt.outcome)
            val before = ControllerVideoHistory(db).capture(conversation)
            // Equal fields are a duplicate identity, not permission to allocate IDs or resend.
            val replay = ControllerVideoAttempt(request, journal)
            val recovered = checkNotNull(replay.reserve(db))
            assertEquals(VideoDraftOutcome.ACCEPTED, recovered.outcome)
            assertEquals(attempt.runId, replay.runId)
            assertTrue(before.same(ControllerVideoHistory(db).capture(conversation)))
            val editor = ControllerVideoEditor(conversation.value, 12, 17, original, true)
            assertTrue(videoEditorReceiptMatches(editor, editor, request, recovered))
            assertFalse(videoEditorReceiptMatches(editor, editor.copy(revision = 18), request, recovered))
            assertFalse(videoEditorReceiptMatches(editor, editor.copy(generation = 13), request, recovered))
            assertFalse(videoEditorReceiptMatches(editor, editor.copy(text = "new draft"), request, recovered))
            assertFalse(videoEditorReceiptMatches(editor, editor.copy(visible = false), request, recovered))
        } finally { preferences.edit().clear().commit() }
    }
}
