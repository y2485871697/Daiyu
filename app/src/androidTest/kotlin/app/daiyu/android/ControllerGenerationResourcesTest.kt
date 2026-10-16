package app.daiyu.android

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.test.AndroidTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.integration.tools.RunGenerationTools
import app.daiyu.android.media.GenerationAssetFence
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.*
import org.json.JSONArray
import java.io.File
import java.util.Base64
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
    private val png get() = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kF9sAAAAASUVORK5CYII=")
    private val attempt get() = GenerationAttempt(owner.binding.identity("item"), "a".repeat(64),
        GenerationProtocol.OPENAI_IMAGES_JSON)

    override fun setUp() {
        super.setUp()
        account = "account"
        db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL," +
            "role TEXT NOT NULL,status TEXT NOT NULL,blocks TEXT NOT NULL,history_segments TEXT NOT NULL)")
        SQLiteGenerationAttemptStore.installSchema(db)
        SQLiteGenerationOutputStore.installSchema(db)
        root = File(context.cacheDir, "generation-reader-${UUID.randomUUID()}")
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

    fun testMetadataAndOriginalComeFromRealRegisteredOutputNotToolPayloadOrPath() {
        assertEquals(GenerationReservation.Started, SQLiteGenerationAttemptStore({ db }, owner.binding).reserve(attempt))
        val assets = resources.assets(GenerationAssetFence(owner, resources::validateOwner))
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
