package app.daiyu.android

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.test.AndroidTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.integration.tools.RunGenerationTools
import app.daiyu.android.media.GenerationAssetException
import app.daiyu.android.media.GenerationAssetFailure
import app.daiyu.android.media.GenerationAssetFence
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.*
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Offline real SQLite + static PNG only. Does not open the production DB or contact a service. */
@Suppress("DEPRECATION")
class ControllerGenerationResourcesTest : AndroidTestCase() {
    private lateinit var db: SQLiteDatabase
    private lateinit var root: File
    private lateinit var resources: ControllerGenerationResources
    private lateinit var owner: GenerationAssetOwner
    private lateinit var history: String
    private lateinit var blocks: String
    private var account: String? = "account"
    private val png get() = app.daiyu.android.validDeviceTestPng()
    private val attempt get() = GenerationAttempt(owner.binding.identity("item"), "a".repeat(64),
        GenerationProtocol.OPENAI_IMAGES_JSON)

    override fun setUp() {
        super.setUp()
        account = "account"
        root = File(context.cacheDir, "generation-reader-${UUID.randomUUID()}").apply { mkdirs() }
        db = SQLiteDatabase.openOrCreateDatabase(File(root, "reader.db"), null)
        db.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("delete", cursor.getString(0))
        }
        db.execSQL("PRAGMA synchronous=FULL")
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL," +
            "role TEXT NOT NULL,status TEXT NOT NULL,blocks TEXT NOT NULL,history_segments TEXT NOT NULL)")
        SQLiteGenerationAttemptStore.installSchema(db)
        SQLiteGenerationOutputStore.installSchema(db)
        owner = GenerationAssetOwner("account", GenerationAttemptBinding("conversation", "assistant", "run",
            "original-call", RunGenerationTools.IMAGE_TOOL))
        val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("original-call"), owner.binding.toolName, "{}"))
        history = MessageHistoryCodec.encode(listOf(MessageHistorySegment(MessageId("assistant"),
            "assistant", listOf(tool)))).toString()
        blocks = JSONArray(history).getJSONObject(0).getJSONArray("blocks").toString()
        db.execSQL("INSERT INTO conversations VALUES ('conversation')")
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", "assistant"); put("conversation_id", "conversation"); put("run_id", "run")
            put("role", "assistant"); put("status", "RUNNING"); put("blocks", blocks)
            put("history_segments", history)
        })
        resources = ControllerGenerationResources("account", { db }, { account }, root)
    }

    override fun tearDown() {
        try { db.close(); root.deleteRecursively() } finally { super.tearDown() }
    }

    private fun change(column: String, value: String) {
        db.update("messages", ContentValues().apply { put(column, value) }, "id=?", arrayOf("assistant"))
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("expected rejection") }
        catch (e: IllegalStateException) {
            assertFalse(e.message.orEmpty().contains(root.absolutePath))
            assertNull(e.cause)
        }
    }

    fun testPersistentOwnerRequiresAccountAssistantRunAndOriginalUniqueTool() {
        assertTrue(resources.validateOwner(owner))
        account = "other"; assertFalse(resources.validateOwner(owner)); account = "account"
        assertFalse(resources.validateOwner(GenerationAssetOwner("other", owner.binding)))
        for ((column, good) in listOf("role" to "assistant", "run_id" to "run", "conversation_id" to "conversation")) {
            change(column, "foreign"); assertFalse(resources.validateOwner(owner)); change(column, good)
        }
        change("history_segments", history.replace("original-call", "foreign-call"))
        assertFalse(resources.validateOwner(owner)) // valid but stale legacy projection cannot authorize
        change("history_segments", history.replace(owner.binding.toolName, RunGenerationTools.VIDEO_TOOL))
        assertFalse(resources.validateOwner(owner))
        change("history_segments", "[" + history.removePrefix("[").removeSuffix("]") + "," +
            history.removePrefix("[").removeSuffix("]") + "]")
        assertFalse(resources.validateOwner(owner))
        change("history_segments", history); change("blocks", "[]")
        assertTrue(resources.validateOwner(owner)) // history is authoritative
        change("history_segments", "[]")
        assertFalse(resources.validateOwner(owner))
        change("blocks", blocks)
        assertTrue(resources.validateOwner(owner))
        db.beginTransaction()
        try { assertFalse(resources.validateOwner(owner)) } finally { db.endTransaction() }
    }

    private fun cancelled(action: () -> Unit) {
        try { action(); fail("expected cancellation") }
        catch (failure: GenerationAssetException) { assertEquals(GenerationAssetFailure.CANCELLED, failure.failure) }
    }

    fun testStableOwnerGatesAndPermanentScopeSealsDoNotDependOnReaderIdentity() {
        val first = resources.createFence(owner) { true }
        val recreatedOwner = GenerationAssetOwner("account", GenerationAttemptBinding("conversation", "assistant",
            "run", "different-call", RunGenerationTools.VIDEO_TOOL))
        assertSame(first.publicationGate, resources.createFence(recreatedOwner) { true }.publicationGate)
        val otherRun = GenerationAssetOwner("account", GenerationAttemptBinding("conversation", "assistant",
            "other-run", "call", RunGenerationTools.IMAGE_TOOL))
        val otherConversation = GenerationAssetOwner("account", GenerationAttemptBinding("other-conversation", "assistant",
            "run", "call", RunGenerationTools.IMAGE_TOOL))
        assertNotSame(first.publicationGate, resources.createFence(otherRun) { true }.publicationGate)
        val runRevocation = resources.sealRunPublicationAdmission("conversation", "run")
        runRevocation.revokeOnWorker()
        cancelled { first.admit { fail("revoked owner") } }
        cancelled { resources.createFence(recreatedOwner) { true }.admit { fail("fresh fence reopened owner") } }
        assertTrue(resources.createFence(otherRun) { true }.admit { true })
        resources.sealConversationPublicationAdmission("conversation").revokeOnWorker()
        cancelled { resources.createFence(otherRun) { true }.admit { fail("revoked conversation") } }
        assertTrue(resources.createFence(otherConversation) { true }.admit { true })
        resources.sealAccountPublicationAdmission().revokeOnWorker()
        cancelled { resources.createFence(otherConversation) { true }.admit { fail("revoked account") } }
        try {
            resources.assets(GenerationAssetFence(owner) { true })
            fail("independent gate accepted")
        } catch (_: IllegalArgumentException) { }
    }

    fun testReaderCloseDoesNotCancelWriterAndWriterCloseDoesNotCancelAcceptedHistory() {
        assertEquals(GenerationReservation.Started, SQLiteGenerationAttemptStore({ db }, owner.binding).reserve(attempt))
        val writerFence = resources.createFence(owner)
        val assets = resources.assets(writerFence)
        val info = assets.inspect(GenerationKind.IMAGE, png, "image/png")
        val first = assets.commit(attempt.identity, 0, GenerationKind.IMAGE, png, info)
        val store = SQLiteGenerationOutputStore({ db }, owner, assets)
        store.register(attempt, first)
        val closedReader = resources.openOutputs(owner)
        closedReader.close()
        val second = assets.commit(attempt.identity, 1, GenerationKind.IMAGE, png, info)
        store.register(attempt, second)
        rejected { closedReader.metadata(attempt, 0) }
        writerFence.revokeAdmission()
        writerFence.revoke()
        val historyReader = resources.openOutputs(owner)
        try {
            assertNotNull(historyReader.metadata(attempt, 0))
            assertNotNull(historyReader.metadata(attempt, 1))
            assertTrue(png.contentEquals(historyReader.openOriginal(attempt, 1)!!.use { it.readBytes() }))
            resources.sealRunPublicationAdmission("conversation", "run").revokeOnWorker()
            rejected { historyReader.metadata(attempt, 0) }
            cancelled { resources.createFence(owner).admit { fail("reopened revoked history") } }
        } finally { historyReader.close() }
    }

    fun testMetadataAndOriginalComeFromRealRegisteredOutputNotToolPayloadOrPath() {
        assertEquals(GenerationReservation.Started, SQLiteGenerationAttemptStore({ db }, owner.binding).reserve(attempt))
        val assets = resources.assets(resources.createFence(owner))
        val info = assets.inspect(GenerationKind.IMAGE, png, "image/png")
        val receipt = assets.commit(attempt.identity, 0, GenerationKind.IMAGE, png, info)
        SQLiteGenerationOutputStore({ db }, owner, assets).register(attempt, receipt)
        val reader = resources.openOutputs(owner)
        try {
            val metadata = checkNotNull(reader.metadata(attempt, 0))
            assertEquals(GenerationKind.IMAGE, metadata.kind)
            assertEquals("image/png", metadata.mimeType)
            assertEquals(png.size.toLong(), metadata.bytes)
            assertFalse(metadata.toString().contains(root.absolutePath))
            assertFalse(metadata.toString().contains(receipt.assetId))
            assertTrue(png.contentEquals(reader.openOriginal(attempt, 0)!!.use { it.readBytes() }))
            assertNull(reader.metadata(attempt, 1))
            rejected { reader.metadata(attempt.copy(fingerprint = "b".repeat(64)), 0) }
            db.execSQL("UPDATE generation_outputs SET account_scope='other'")
            rejected { reader.openOriginal(attempt, 0) }
            db.execSQL("UPDATE generation_outputs SET account_scope='account'")
            account = "other"; rejected { reader.metadata(attempt, 0) }; account = "account"
            change("history_segments", history.replace("original-call", "foreign-call"))
            rejected { reader.openOriginal(attempt, 0) }
            change("history_segments", history)
        } finally { reader.close() }
        rejected { reader.openOriginal(attempt, 0) }
    }
}
