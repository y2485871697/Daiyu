package app.daiyu.android.backup

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import android.util.Base64
import app.daiyu.android.ChatController
import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.model.MessagesRequest
import app.daiyu.android.model.MessagesStream
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.testContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Full-package seam coverage for task 03's persisted, authoritative history segments. */
@Suppress("DEPRECATION")
class SegmentedBackupRoundTripTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() {
        init { attachBaseContext(base) }
    }

    private class Fixture(val app: FixtureApplication) {
        val modelCalls = AtomicInteger()
        lateinit var db: ConversationDatabase
        lateinit var controller: ChatController
        val backup get() = NeutralBackupService(app, db)
    }

    private data class Seed(val owner: ConversationId, val rows: List<Message>, val archiveId: String)
    private val fixtures = mutableListOf<Fixture>()

    private fun main(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { block() } catch (t: Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }
    }

    private fun open(f: Fixture) {
        f.db = ConversationDatabase(f.app)
        f.controller = ChatController(f.app, f.db, ModelSettingsStore(f.app),
            f.app.getSharedPreferences("conversation-ui", Context.MODE_PRIVATE), {
                f.modelCalls.incrementAndGet()
                error("Import/reopen must not create a model or replay a historical tool request")
            })
        val ready = CountDownLatch(1)
        val listener: () -> Unit = { if (f.controller.ready) ready.countDown() }
        main { f.controller.observe(listener); listener() }
        try {
            assertTrue("recovery timeout", ready.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertNull(f.controller.problem)
            assertNull(f.controller.active)
        } finally { main { f.controller.unobserve(listener) } }
    }

    private fun fresh(): Fixture {
        val token = UUID.randomUUID().toString()
        val base = testContext(instrumentation.targetContext, "segmented-backup-$token-")
        val files = File(base.cacheDir, "segmented-backup-$token").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        return Fixture(FixtureApplication(isolated)).also { fixtures += it; open(it) }
    }

    override fun tearDown() {
        try {
            fixtures.forEach { f ->
                f.controller.close()
                f.app.deleteDatabase("conversations.db")
                f.app.getSharedPreferences("conversation-ui", 0).edit().clear().commit()
                f.app.getSharedPreferences("model-settings", 0).edit().clear().commit()
                f.app.filesDir.deleteRecursively()
            }
        } finally { super.tearDown() }
    }

    private fun textProtocol(id: String, text: String): String = JSONObject()
        .put("type", "message").put("id", id).put("role", "assistant").put("status", "completed")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text))).toString()

    private fun seed(f: Fixture): Seed {
        val owner = f.db.create("default").id
        val run = RunId("run-${owner.value}")
        val user = Message(MessageId("user-${owner.value}"), owner, run, "user",
            listOf(MessageBlock.Text("question", "合成问题：请先说明，再查询，再解释。")), RunStatus.COMPLETED)
        val id = MessageId("assistant-${owner.value}")
        val callId = "call-${owner.value}"
        val before = MessageBlock.Text("before-${owner.value}", "查询前正文：保留顺序。")
        val tool = MessageBlock.Tool(ToolInvocation(ToolCallId(callId), "synthetic_lookup", "{\"key\":\"fixture\"}"),
            "{\"value\":\"合成结果\"}")
        val after = MessageBlock.Text("after-${owner.value}", "查询后正文：结果仅作资料。")
        val call = JSONObject().put("type", "function_call").put("id", "item-$callId")
            .put("call_id", callId).put("name", tool.invocation.name).put("arguments", tool.invocation.arguments)
            .put("status", "completed").toString()
        val output = JSONObject().put("type", "function_call_output").put("call_id", callId)
            .put("output", tool.result).toString()
        val segments = listOf(
            MessageHistorySegment(id, "assistant", listOf(before), listOf(textProtocol(before.itemId, before.text))),
            MessageHistorySegment(id, "assistant", listOf(tool), listOf(call, output)),
            MessageHistorySegment(id, "assistant", listOf(after), listOf(textProtocol(after.itemId, after.text)))
        )
        val reply = Message(id, owner, run, "assistant", listOf(before, tool, after), RunStatus.COMPLETED,
            protocolItems = segments.flatMap { it.protocolItems }, historySegments = segments)
        f.db.send(user, reply.copy(status = RunStatus.RUNNING))
        f.db.save(reply)
        val rows = listOf(user, reply)
        assertEquals(rows, f.db.messages(owner).map { it.second })

        // A deterministic synthetic archive reference, not a model/compression test. The blob and
        // revision use production codecs, and the full backup must carry and validate this reference.
        val archiveId = "archive-${owner.value}"
        val hash = PrivateBlobs(f.app).put(ArchiveCodec.encode(rows))
        f.db.writableDatabase.insertOrThrow("context_archives", null, ContentValues().apply {
            put("id", archiveId); put("conversation_id", owner.value); put("prefix_count", rows.size)
            put("revision", ContextRevision.digest(rows)); put("summary", "合成历史摘要")
            put("blob_hash", hash); put("created", 1L); put("active", 1)
        })
        return Seed(owner, rows, archiveId)
    }

    private fun payload(text: String) = JSONObject(JSONObject(text).getString("payload"))

    private fun assertExpanded(rows: List<Message>, seed: Seed) {
        val owner = seed.rows.last()
        val expected = listOf(seed.rows.first()) + owner.historySegments.map { segment ->
            Message(segment.messageId, owner.conversationId, owner.runId, segment.role, segment.blocks,
                owner.status, owner.error, segment.protocolItems)
        }
        val expanded = MessageHistory.expand(rows)
        assertEquals(expected, expanded)
        assertEquals(listOf("user", "assistant", "assistant", "assistant"), expanded.map { it.role })
        assertTrue(expanded.all { it.historySegments.isEmpty() })
        assertEquals(listOf(owner.id, owner.id, owner.id), expanded.drop(1).map { it.id })
        assertTrue(expanded.all { it.conversationId == seed.owner && it.runId == owner.runId })
        val protocol = expanded.drop(1).flatMap { it.protocolItems }.map { JSONObject(it) }
        assertEquals(listOf("message", "function_call", "function_call_output", "message"),
            protocol.map { it.getString("type") })
        val tool = owner.blocks[1] as MessageBlock.Tool
        assertEquals(tool.invocation.id.value, protocol[1].getString("call_id"))
        assertEquals(tool.invocation.id.value, protocol[2].getString("call_id"))
        assertEquals(tool.result, protocol[2].getString("output"))
        assertEquals((owner.blocks.first() as MessageBlock.Text).itemId, protocol.first().getString("id"))
        assertEquals((owner.blocks.last() as MessageBlock.Text).itemId, protocol.last().getString("id"))
    }

    private fun assertRestored(f: Fixture, seed: Seed) {
        assertEquals(29, f.db.readableDatabase.version)
        val rows = f.db.messages(seed.owner).map { it.second }
        assertEquals(seed.rows, rows)
        assertEquals(3, rows.last().historySegments.size)
        assertExpanded(rows, seed)
        val hash = f.db.readableDatabase.rawQuery(
            "SELECT blob_hash FROM context_archives WHERE id=? AND conversation_id=?",
            arrayOf(seed.archiveId, seed.owner.value)).use { c ->
            assertTrue(c.moveToFirst()); c.getString(0)
        }
        val bytes = PrivateBlobs(f.app).read(hash)
        val archived = ArchiveCodec.decode(bytes, hash)
        assertEquals(seed.rows, archived)
        assertTrue(ArchiveCodec.encode(seed.rows).contentEquals(bytes))
        assertExpanded(archived, seed)
        val contexts = ContextService(f.app, f.db)
        assertEquals(seed.owner, contexts.archiveOwner(seed.archiveId))
        assertEquals(seed.rows, contexts.archivePage(seed.archiveId, expectedOwner = seed.owner).messages)
        assertEquals(0, f.modelCalls.get())
        assertNull(f.controller.active)
    }

    fun testSegmentedHistorySurvivesCompletePackageArchiveReopenAndDuplicateWithoutReplay() {
        val source = fresh()
        val seed = seed(source)
        val textBefore = source.db.exportBackup()
        val complete = source.backup.exportPackage()
        val inventory = source.backup.preflight(complete)
        assertEquals(1, inventory.conversations); assertEquals(2, inventory.messages)
        assertEquals(1, inventory.archives); assertEquals(0, inventory.attachments)
        assertEquals(ArchiveCodec.encode(seed.rows).size.toLong(), inventory.blobBytes)
        val packagePayload = payload(complete)
        assertEquals(textBefore, packagePayload.getString("text_backup"))
        val reference = packagePayload.getJSONObject("tables").getJSONArray("context_archives").getJSONObject(0)
        val blob = packagePayload.getJSONArray("blobs").getJSONObject(0)
        assertEquals(reference.getString("blob_hash"), blob.getString("sha256"))
        assertEquals(seed.rows, ArchiveCodec.decode(Base64.decode(blob.getString("data"), Base64.NO_WRAP),
            reference.getString("blob_hash")))

        val target = fresh()
        assertEquals(inventory, target.backup.preflight(complete))
        val imported = complete.byteInputStream().use { target.backup.importPackage(it) }
        assertFalse(imported.alreadyImported); assertEquals(inventory, imported.inventory)
        assertEquals(JSONObject(complete).getString("sha256"), imported.packageId)
        assertEquals(textBefore, target.db.exportBackup())
        assertRestored(target, seed)
        val restoredPackage = target.backup.exportPackage()
        assertEquals(complete, restoredPackage)
        val duplicate = target.backup.importPackage(complete)
        assertTrue(duplicate.alreadyImported); assertEquals(imported.packageId, duplicate.packageId)
        assertEquals(restoredPackage, target.backup.exportPackage())

        target.controller.close()
        open(target)
        assertRestored(target, seed)
        assertTrue(target.backup.importPackage(complete).alreadyImported)
        assertEquals(restoredPackage, target.backup.exportPackage())
        assertEquals(1, target.db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null).use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(textBefore, source.db.exportBackup())
        assertRestored(source, seed)
    }

    fun testMessagesStreamSegmentsSurviveCompletePackageReopenAndHistoryReplayWithoutModelCalls() {
        val source = fresh()
        val owner = source.db.create("default").id
        val run = RunId("messages-run-${owner.value}")
        val callId = "messages-call-${owner.value}"
        val result = "合成工具结果；不执行真实工具"
        fun stream(messageId: String, text: String, withTool: Boolean): Pair<String, List<MessageBlock>> {
            val frames = mutableListOf(
                """{"type":"message_start","message":{"id":"$messageId","role":"assistant","content":[]}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"","fixture_marker":"$messageId"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}""",
                """{"type":"content_block_stop","index":0}"""
            )
            if (withTool) frames += listOf(
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"$callId","name":"synthetic_lookup","input":{}}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"key\":\"fixture\"}"}}""",
                """{"type":"content_block_stop","index":1}"""
            )
            frames += listOf(
                """{"type":"message_delta","delta":{"stop_reason":"${if (withTool) "tool_use" else "end_turn"}"}}""",
                """{"type":"message_stop"}"""
            )
            val events = mutableListOf<ModelEvent>()
            val saved = frames.joinToString("") { "data: $it\n\n" }.reader().buffered().use {
                MessagesStream.read(it, { false }) { event -> events += event }
            }
            assertEquals(listOf(ModelEvent.TextFinal("$messageId:0", text)), events.filterIsInstance<ModelEvent.TextFinal>())
            assertEquals(messageId, JSONObject(saved).getJSONArray("content").getJSONObject(0).getString("fixture_marker"))
            val blocks = events.mapNotNull { event -> when (event) {
                is ModelEvent.TextFinal -> MessageBlock.Text(event.itemId, event.text)
                is ModelEvent.ToolReady -> MessageBlock.Tool(event.invocation, result)
                else -> null
            } }
            return saved to blocks
        }
        val before = stream("messages-before-${owner.value}", "调用前正文", true)
        val after = stream("messages-after-${owner.value}", "调用后正文", false)
        val id = MessageId("messages-assistant-${owner.value}")
        val segments = listOf(
            MessageHistorySegment(id, "assistant", before.second, listOf(before.first)),
            MessageHistorySegment(id, "assistant", after.second, listOf(after.first)))
        val user = Message(MessageId("messages-user-${owner.value}"), owner, run, "user",
            listOf(MessageBlock.Text("question", "合成问题")), RunStatus.COMPLETED)
        val reply = Message(id, owner, run, "assistant", segments.flatMap { it.blocks }, RunStatus.COMPLETED,
            protocolItems = segments.flatMap { it.protocolItems }, historySegments = segments)
        source.db.send(user, reply.copy(status = RunStatus.RUNNING)); source.db.save(reply)
        val rows = listOf(user, reply)
        assertEquals(rows, source.db.messages(owner).map { it.second })
        val complete = source.backup.exportPackage()
        val inventory = source.backup.preflight(complete)
        assertEquals(1, inventory.conversations); assertEquals(2, inventory.messages)
        assertEquals(0, inventory.archives); assertEquals(0, inventory.attachments)
        val target = fresh()
        assertEquals(inventory, target.backup.preflight(complete))
        val imported = complete.byteInputStream().use { target.backup.importPackage(it) }
        assertFalse(imported.alreadyImported); assertEquals(inventory, imported.inventory)
        target.controller.close(); open(target)
        val restored = target.db.messages(owner).map { it.second }
        assertEquals(rows, restored); assertEquals(segments, restored.last().historySegments)
        assertEquals(complete, target.backup.exportPackage())
        val expected = JSONArray()
            .put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "合成问题"))))
            .put(JSONObject(before.first))
            .put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "tool_result").put("tool_use_id", callId).put("content", result))))
            .put(JSONObject(after.first))
        assertEquals(expected.toString(), MessagesRequest.history(restored).toString())
        assertEquals(0, source.modelCalls.get()); assertEquals(0, target.modelCalls.get())
        assertNull(source.controller.active); assertNull(target.controller.active)
    }

    private fun seal(envelope: JSONObject, contents: JSONObject): String {
        val raw = contents.toString()
        return envelope.put("payload", raw).put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))).toString()
    }

    private fun tamperArchivedHistory(complete: String): String {
        val envelope = JSONObject(complete)
        val contents = payload(complete)
        val blob = contents.getJSONArray("blobs").getJSONObject(0)
        val archive = JSONArray(String(Base64.decode(blob.getString("data"), Base64.NO_WRAP), Charsets.UTF_8))
        archive.getJSONObject(1).getJSONArray("history_segments").getJSONObject(0)
            .put("message_id", "foreign-assistant-owner")
        // Re-sign every affected byte/hash/size, so rejection cannot be an outer checksum failure.
        val bytes = archive.toString().toByteArray(Charsets.UTF_8)
        val hash = PrivateBlobs.digest(bytes)
        blob.put("sha256", hash).put("bytes", bytes.size).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        contents.getJSONObject("tables").getJSONArray("context_archives").getJSONObject(0).put("blob_hash", hash)
        contents.getJSONObject("inventory").put("blob_bytes", bytes.size)
        return seal(envelope, contents)
    }

    private fun rejected(block: () -> Unit) {
        var failure: Exception? = null
        try { block() } catch (e: Exception) { failure = e }
        assertNotNull("history/owner corruption must be rejected", failure)
    }

    fun testResignedHistoryIdentityTamperAndCrossOwnerArchiveLeaveTargetUnchanged() {
        val source = fresh()
        seed(source)
        val otherOwner = source.db.create("default").id
        val complete = source.backup.exportPackage()
        val wrongOwnerContents = payload(complete)
        wrongOwnerContents.getJSONObject("tables").getJSONArray("context_archives").getJSONObject(0)
            .put("conversation_id", otherOwner.value)
        val wrongOwner = seal(JSONObject(complete), wrongOwnerContents)
        val target = fresh()
        val existing = seed(target)
        val before = target.backup.exportPackage()
        val filesBefore = File(target.app.filesDir, "neutral-context-blobs").list().orEmpty().toSet()
        listOf(tamperArchivedHistory(complete), wrongOwner).forEach { corrupt ->
            rejected { target.backup.preflight(corrupt) }
            rejected { target.backup.importPackage(corrupt) }
            assertEquals(before, target.backup.exportPackage())
            assertEquals(filesBefore, File(target.app.filesDir, "neutral-context-blobs").list().orEmpty().toSet())
            assertEquals(0, target.db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null).use {
                it.moveToFirst(); it.getInt(0)
            })
            assertRestored(target, existing)
        }
        target.controller.close()
        open(target)
        assertEquals(before, target.backup.exportPackage())
        assertRestored(target, existing)
        assertEquals(complete, source.backup.exportPackage())
        assertEquals(0, source.modelCalls.get())
    }
    fun testSingleConversationPackagePreservesSegmentedProtocolAndArchiveWithoutOtherConversationOrReplay() {
        val source=fresh();val selected=seed(source);val other=seed(source)
        val before=source.backup.exportPackage();val text=source.backup.exportConversation(selected.owner)
        val inventory=source.backup.preflight(text)
        assertEquals(1,inventory.conversations);assertEquals(2,inventory.messages);assertEquals(1,inventory.archives)
        assertFalse(payload(text).getString("text_backup").contains(other.owner.value))
        val target=fresh();target.backup.importPackage(text)
        assertEquals(listOf(selected.owner),target.db.conversations().map {it.id});assertRestored(target,selected)
        target.controller.close();open(target);assertRestored(target,selected)
        assertTrue(target.backup.importPackage(text).alreadyImported)
        assertEquals(before,source.backup.exportPackage());assertEquals(0,source.modelCalls.get());assertEquals(0,target.modelCalls.get())
    }

}
