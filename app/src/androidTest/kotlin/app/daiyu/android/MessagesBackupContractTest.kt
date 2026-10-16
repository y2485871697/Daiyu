package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.model.MessagesRequest
import app.daiyu.android.model.MessagesStream
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

@Suppress("DEPRECATION")
class MessagesBackupContractTest : InstrumentationTestCase() {
    private inner class Fixture {
        private val prefix = "messages-backup-${UUID.randomUUID()}-"
        val source = testContext(instrumentation.targetContext, "${prefix}source-")
        val target = testContext(instrumentation.targetContext, "${prefix}target-")
        var from = ConversationDatabase(source)
        var to = ConversationDatabase(target)
    }
    private fun fixture(test: (Fixture) -> Unit) {
        val f = Fixture()
        try {
            assertFalse(instrumentation.targetContext.getDatabasePath("conversations.db").canonicalPath == f.source.getDatabasePath("conversations.db").canonicalPath)
            assertFalse(f.source.getDatabasePath("conversations.db").canonicalPath == f.target.getDatabasePath("conversations.db").canonicalPath)
            assertEquals(13, f.from.readableDatabase.version); assertEquals(13, f.to.readableDatabase.version)
            test(f)
        } finally {
            try { f.from.close() } finally {
                try { f.to.close() } finally {
                    f.source.deleteDatabase("conversations.db")
                    f.target.deleteDatabase("conversations.db")
                }
            }
        }
    }
    private fun event(type: String, vararg fields: Pair<String, Any>) =
        JSONObject().put("type", type).also { value -> fields.forEach { value.put(it.first, it.second) } }
    private fun stream(tool: Boolean): Pair<String, List<ModelEvent>> {
        val block = if (tool) event("tool_use", "id" to "call", "name" to "device_status", "input" to JSONObject())
            else event("text", "text" to "")
        val wire = mutableListOf(
            event("message_start", "message" to JSONObject().put("id", "fixture").put("role", "assistant").put("content", JSONArray())),
            event("content_block_start", "index" to 0, "content_block" to block))
        if (tool) {
            for (part in listOf("{", "}")) wire += event("content_block_delta", "index" to 0,
                "delta" to event("input_json_delta", "partial_json" to part))
        } else wire += event("content_block_delta", "index" to 0,
            "delta" to event("text_delta", "text" to "结束\n\"中\""))
        wire += event("content_block_stop", "index" to 0)
        wire += event("message_delta", "delta" to JSONObject().put("stop_reason", if (tool) "tool_use" else "end_turn"))
        wire += event("message_stop")
        val body = wire.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }
        val events = mutableListOf<ModelEvent>()
        return MessagesStream.read(body.reader().buffered(), { false }, events::add) to events
    }
    private fun seed(db: ConversationDatabase, suffix: String, finished: Boolean = true): Message {
        val conversation = db.create("default"); val run = RunId("run-$suffix")
        val user = Message(MessageId("user-$suffix"), conversation.id, run, "user",
            listOf(MessageBlock.Text("question", "查询\n\"原文\"")), RunStatus.COMPLETED)
        val assistant = Message(MessageId("assistant-$suffix"), conversation.id, run, "assistant", emptyList(), RunStatus.RUNNING)
        db.send(user, assistant)
        val call = stream(true)
        val invocation = call.second.filterIsInstance<ModelEvent.ToolReady>().single().invocation
        val userSegment = MessageHistorySegment(user.id, user.role, user.blocks)
        val pendingBlock = MessageBlock.Tool(invocation)
        val pendingSegment = MessageHistorySegment(assistant.id, "assistant", listOf(pendingBlock), listOf(call.first))
        val pending = assistant.copy(blocks = listOf(pendingBlock), protocolItems = listOf(call.first),
            historySegments = listOf(userSegment, pendingSegment))
        db.save(pending)
        if (!finished) return pending.copy(status = RunStatus.STOPPED).also(db::save)
        val result = "结果\n\"中\""
        val rawResult = " \n" + JSONObject().put("role", "user").put("content", JSONArray().put(
            event("tool_result", "tool_use_id" to invocation.id.value, "content" to result))).toString() + "\t "
        val completedBlock = MessageBlock.Tool(invocation, result)
        val toolSegment = pendingSegment.copy(blocks = listOf(completedBlock), protocolItems = listOf(call.first, rawResult))
        val tail = stream(false)
        val textBlock = MessageBlock.Text("tail", tail.second.filterIsInstance<ModelEvent.TextFinal>().single().text)
        val tailSegment = MessageHistorySegment(assistant.id, "assistant", listOf(textBlock), listOf(tail.first))
        return assistant.copy(status = RunStatus.COMPLETED, blocks = listOf(completedBlock, textBlock),
            protocolItems = toolSegment.protocolItems + tailSegment.protocolItems,
            historySegments = listOf(userSegment, toolSegment, tailSegment)).also(db::save)
    }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun altered(backup: String, segmentIndex: Int = 1, edit: (JSONObject) -> Unit): String {
        val envelope = JSONObject(backup); val payload = JSONObject(envelope.getString("payload"))
        val owner = payload.getJSONArray("messages").getJSONObject(1)
        val segments = owner.getJSONArray("history_segments"); edit(segments.getJSONObject(segmentIndex))
        val blocks = JSONArray(); val protocol = JSONArray()
        repeat(segments.length()) { i ->
            val segment = segments.getJSONObject(i)
            if (segment.getString("role") == "assistant") {
                val b = segment.getJSONArray("blocks"); val p = segment.getJSONArray("protocol")
                repeat(b.length()) { blocks.put(b.get(it)) }; repeat(p.length()) { protocol.put(p.get(it)) }
            }
        }
        owner.put("blocks", blocks).put("protocol", protocol)
        val text = payload.toString()
        return envelope.put("payload", text).put("sha256", digest(text)).toString()
    }
    private fun rejected(f: Fixture, text: String, before: String) {
        val envelope = JSONObject(text)
        assertEquals(digest(envelope.getString("payload")), envelope.getString("sha256"))
        try { f.to.preflightBackup(text); fail("protocol preflight must reject") } catch (_: Exception) {}
        assertEquals(before, f.to.exportBackup())
        try { f.to.importBackup(text); fail("invalid protocol must reject") } catch (_: Exception) {}
        assertEquals(before, f.to.exportBackup())
    }
    fun testStreamRawSegmentedBackupRoundTripAndReopen() = fixture { f ->
        val expected = seed(f.from, "source"); val backup = f.from.exportBackup()
        f.from.close(); f.from = ConversationDatabase(f.source)
        assertEquals(expected, f.from.messages(expected.conversationId).last().second)
        assertEquals(backup, f.from.exportBackup()); assertEquals(1 to 2, f.to.importBackup(backup))
        val imported = f.to.messages(expected.conversationId).last().second
        assertEquals(expected, imported); assertEquals(expected.protocolItems, imported.protocolItems)
        expected.historySegments.zip(imported.historySegments).forEach { (a, b) -> assertEquals(a.protocolItems, b.protocolItems) }
        assertEquals(backup, f.to.exportBackup())
        f.to.close(); f.to = ConversationDatabase(f.target)
        assertEquals(expected, f.to.messages(expected.conversationId).last().second)
        assertReplay(f.to, expected)
        assertEquals(backup, f.to.exportBackup())
    }
    fun testValidationKeepsUnfinishedToolWithoutSyntheticResult() = fixture { f ->
        val expected = seed(f.from, "unfinished", finished = false); val backup = f.from.exportBackup()
        f.to.preflightBackup(backup); assertEquals(1 to 2, f.to.importBackup(backup))
        f.to.close(); f.to = ConversationDatabase(f.target)
        val restored = f.to.messages(expected.conversationId).last().second
        assertEquals(expected, restored); assertEquals(RunStatus.STOPPED, restored.status)
        assertNull((restored.blocks.single() as MessageBlock.Tool).result)
        assertEquals(1, restored.protocolItems.size)
        assertEquals("tool_use", JSONObject(restored.protocolItems.single()).getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals(backup, f.to.exportBackup())
    }
    fun testResignedProtocolFailuresLeaveOriginalDataAndDrafts() = fixture { f ->
        val incoming = seed(f.from, "source"); val existing = seed(f.to, "target")
        f.to.draft(existing.conversationId, "原会话草稿"); f.to.draft("原未创建草稿")
        val backup = f.from.exportBackup(); val before = f.to.exportBackup()
        assertFalse(incoming.conversationId == existing.conversationId)
        val incomingIds = f.from.messages(incoming.conversationId).map { it.second.id }.toSet()
        assertTrue(f.to.messages(existing.conversationId).none { it.second.id in incomingIds }); f.to.preflightBackup(backup)
        fun scalar(value: Any) = altered(backup) { segment ->
            val items = segment.getJSONArray("protocol"); val result = JSONObject(items.getString(1))
            result.getJSONArray("content").getJSONObject(0).put("content", value); items.put(1, result.toString())
            segment.getJSONArray("blocks").getJSONObject(0).put("result", value.toString())
        }
        for (value in listOf<Any>(7, true, JSONObject.NULL)) { f.to.preflightBackup(scalar(value.toString())); rejected(f, scalar(value), before) }
        rejected(f, altered(backup) { segment ->
            segment.getJSONArray("protocol").put(event("reasoning", "summary" to JSONArray()).toString())
        }, before)
        rejected(f, altered(backup) { segment ->
            val result = segment.getJSONArray("protocol").getString(1)
            segment.put("protocol", JSONArray().put(result)).put("blocks", JSONArray())
        }, before)
        for ((segmentIndex, rawIndex, field) in listOf(Triple(2, 0, "text"), Triple(1, 0, "name"), Triple(1, 1, "content"))) {
            fun changed(value: Any?) = altered(backup, segmentIndex) { segment ->
                val items = segment.getJSONArray("protocol"); val raw = JSONObject(items.getString(rawIndex))
                raw.getJSONArray("content").getJSONObject(0).put(field, value)
                items.put(rawIndex, raw.toString())
                if (field == "content") segment.getJSONArray("blocks").getJSONObject(0).put("result", value.toString())
            }
            for (value in listOf<Any>(7, true)) f.to.preflightBackup(changed(value.toString()))
            for (value in listOf<Any?>(null, JSONObject.NULL, 7, true, JSONObject(), JSONArray())) rejected(f, changed(value), before)
            rejected(f, altered(backup, segmentIndex) { segment ->
                val items = segment.getJSONArray("protocol"); val raw = JSONObject(items.getString(rawIndex))
                raw.getJSONArray("content").getJSONObject(0).remove("type"); items.put(rawIndex, raw.toString())
            }, before)
        }
        assertEquals("原未创建草稿", f.to.draft())
        assertFalse(f.to.conversations().any { it.id == incoming.conversationId })
        assertTrue(f.to.messages(incoming.conversationId).isEmpty())
        f.to.close(); f.to = ConversationDatabase(f.target)
        assertEquals(before, f.to.exportBackup()); assertEquals(1 to 2, f.to.importBackup(backup))
        assertEquals("原未创建草稿", f.to.draft()); assertEquals(existing, f.to.messages(existing.conversationId).last().second)
    }
    fun testLateDraftConflictRollsBackInsertedConversationAndMessages() = fixture { f ->
        val incoming = seed(f.from, "source"); val existing = seed(f.to, "target")
        f.from.draft("来源未创建草稿"); f.to.draft(existing.conversationId, "原会话草稿"); f.to.draft("目标未创建草稿")
        val backup = f.from.exportBackup(); val before = f.to.exportBackup(); f.to.preflightBackup(backup)
        try { f.to.importBackup(backup); fail("late composer conflict must reject") } catch (failure: IllegalArgumentException) {
            assertEquals("未创建会话草稿冲突；未覆盖", failure.message)
        }
        assertEquals(before, f.to.exportBackup()); assertFalse(f.to.conversations().any { it.id == incoming.conversationId })
        assertTrue(f.to.messages(incoming.conversationId).isEmpty())
        f.to.close(); f.to = ConversationDatabase(f.target); assertEquals(before, f.to.exportBackup())
        f.to.draft(""); assertEquals(1 to 2, f.to.importBackup(backup))
        assertEquals(incoming, f.to.messages(incoming.conversationId).last().second)
        assertEquals("来源未创建草稿", f.to.draft())
    }
    private fun assertReplay(db: ConversationDatabase, expected: Message) {
        val replay = MessagesRequest.history(db.messages(expected.conversationId).map { it.second })
        assertEquals(4, replay.length())
        assertEquals(listOf("user", "assistant", "user", "assistant"),
            (0 until replay.length()).map { replay.getJSONObject(it).getString("role") })
        assertEquals(expected.protocolItems.map { JSONObject(it).toString() },
            (1 until replay.length()).map { replay.getJSONObject(it).toString() })
        val tools = expected.blocks.filterIsInstance<MessageBlock.Tool>()
        val results = replay.getJSONObject(2).getJSONArray("content")
        assertEquals(tools.size, results.length())
        assertEquals(tools.map { it.invocation.id.value },
            (0 until results.length()).map { results.getJSONObject(it).getString("tool_use_id") })
        assertEquals(tools.map { it.result },
            (0 until results.length()).map { results.getJSONObject(it).getString("content") })
        assertEquals(expected, db.messages(expected.conversationId).last().second)
    }
    fun testCompleteStreamToolBatchWithTextBacksUpReopensAndReplaysExactlyOnce() = fixture { f ->
        val wire = mutableListOf(event("message_start", "message" to JSONObject().put("id", "batch")
            .put("role", "assistant").put("content", JSONArray())))
        val starts = listOf(event("text", "text" to ""),
            event("tool_use", "id" to "batch-a", "name" to "lookup_a", "input" to JSONObject()),
            event("tool_use", "id" to "batch-b", "name" to "lookup_b", "input" to JSONObject()), event("text", "text" to ""))
        starts.forEachIndexed { index, block ->
            wire += event("content_block_start", "index" to index, "content_block" to block)
            if (block.getString("type") == "text") {
                wire += event("content_block_delta", "index" to index, "delta" to event("text_delta",
                    "text" to if (index == 0) "查询前\n\"中\"" else "批次后正文"))
            } else for (fragment in listOf("{\"n\":", "$index}")) {
                wire += event("content_block_delta", "index" to index,
                    "delta" to event("input_json_delta", "partial_json" to fragment))
            }
            wire += event("content_block_stop", "index" to index)
        }
        wire += event("message_delta", "delta" to JSONObject().put("stop_reason", "tool_use")); wire += event("message_stop")
        val events = mutableListOf<ModelEvent>()
        val body = wire.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }
        val savedCall = MessagesStream.read(body.reader().buffered(), { false }, events::add)
        assertEquals(listOf("batch-a", "batch-b"), events.filterIsInstance<ModelEvent.ToolReady>().map { it.invocation.id.value })
        assertEquals(listOf("查询前\n\"中\"", "批次后正文"), events.filterIsInstance<ModelEvent.TextFinal>().map { it.text })
        val content = JSONObject(savedCall).getJSONArray("content")
        assertEquals(listOf("text", "tool_use", "tool_use", "text"), (0 until content.length()).map { content.getJSONObject(it).getString("type") })
        val blocks = events.mapNotNull { e -> when (e) {
            is ModelEvent.TextFinal -> MessageBlock.Text(e.itemId, e.text)
            is ModelEvent.ToolReady -> MessageBlock.Tool(e.invocation, "${e.invocation.id.value}\n\"合成结果\"")
            else -> null
        } }
        val results = JSONArray().apply { blocks.filterIsInstance<MessageBlock.Tool>().forEach {
            put(event("tool_result", "tool_use_id" to it.invocation.id.value, "content" to requireNotNull(it.result)))
        } }
        val savedResults = " \n" + JSONObject().put("role", "user").put("content", results).toString() + "\t "
        val c = f.from.create("default"); val run = RunId("batch-run")
        val user = Message(MessageId("batch-user"), c.id, run, "user", listOf(MessageBlock.Text("question", "完整批次问题")), RunStatus.COMPLETED)
        val initial = Message(MessageId("batch-owner"), c.id, run, "assistant", emptyList(), RunStatus.RUNNING)
        f.from.send(user, initial)
        val tail = stream(false); val tailBlock = MessageBlock.Text("batch-tail", tail.second.filterIsInstance<ModelEvent.TextFinal>().single().text)
        val expected = initial.copy(blocks = blocks + tailBlock, status = RunStatus.COMPLETED,
            protocolItems = listOf(savedCall, savedResults, tail.first), historySegments = listOf(
                MessageHistorySegment(user.id, "user", user.blocks),
                MessageHistorySegment(initial.id, "assistant", blocks, listOf(savedCall, savedResults)),
                MessageHistorySegment(initial.id, "assistant", listOf(tailBlock), listOf(tail.first))))
        f.from.save(expected); val backup = f.from.exportBackup()
        f.from.close(); f.from = ConversationDatabase(f.source)
        assertEquals(expected, f.from.messages(c.id).last().second); assertEquals(backup, f.from.exportBackup())
        f.to.preflightBackup(backup); assertEquals(1 to 2, f.to.importBackup(backup))
        f.to.close(); f.to = ConversationDatabase(f.target)
        assertEquals(13, f.to.readableDatabase.version); assertEquals(listOf(user, expected), f.to.messages(c.id).map { it.second })
        assertReplay(f.to, expected); assertEquals(backup, f.to.exportBackup())
    }
    fun testLegacyResponsesBackupKeepsOldPathAndRejectsMissingTypeOrMixedItems() = fixture { f ->
        val c = f.from.create("default"); val run = RunId("legacy-responses-run")
        val user = Message(MessageId("legacy-responses-user"), c.id, run, "user", listOf(MessageBlock.Text("question", "旧 Responses 问题")), RunStatus.COMPLETED)
        val initial = Message(MessageId("legacy-responses-owner"), c.id, run, "assistant", emptyList(), RunStatus.RUNNING)
        val protocol = listOf(event("reasoning", "summary" to JSONArray(), "encrypted_content" to "synthetic").toString(),
            event("message", "role" to "assistant", "content" to JSONArray().put(event("output_text", "text" to "旧正文\n\"中\""))).toString(),
            event("function_call", "call_id" to "legacy-call", "name" to "lookup", "arguments" to "{}").toString(),
            event("function_call_output", "call_id" to "legacy-call", "output" to "旧结果").toString())
        val expected = initial.copy(status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("legacy-text", "旧正文\n\"中\""), MessageBlock.Tool(ToolInvocation(ToolCallId("legacy-call"), "lookup", "{}"), "旧结果")), protocolItems = protocol)
        f.from.send(user, initial); f.from.save(expected); val backup = f.from.exportBackup()
        val sourceMessages = JSONObject(JSONObject(backup).getString("payload")).getJSONArray("messages")
        repeat(sourceMessages.length()) { assertFalse(sourceMessages.getJSONObject(it).has("history_segments")) }
        val existing = seed(f.to, "responses-target"); f.to.draft(existing.conversationId, "既有会话草稿"); f.to.draft("既有未创建草稿")
        val before = f.to.exportBackup(); f.to.preflightBackup(backup)
        fun changed(edit: (JSONArray) -> Unit): String {
            val envelope = JSONObject(backup); val payload = JSONObject(envelope.getString("payload"))
            edit(payload.getJSONArray("messages").getJSONObject(1).getJSONArray("protocol"))
            val text = payload.toString(); return envelope.put("payload", text).put("sha256", digest(text)).toString()
        }
        for (index in protocol.indices) rejected(f, changed { items ->
            val raw = JSONObject(items.getString(index)); raw.remove("type"); items.put(index, raw.toString())
        }, before)
        rejected(f, changed { it.put(stream(false).first) }, before)
        assertEquals("既有未创建草稿", f.to.draft()); assertEquals(1 to 2, f.to.importBackup(backup))
        assertEquals(expected, f.to.messages(c.id).last().second); assertEquals(existing, f.to.messages(existing.conversationId).last().second)
        val after = f.to.exportBackup(); f.to.close(); f.to = ConversationDatabase(f.target)
        assertEquals(13, f.to.readableDatabase.version); assertEquals(after, f.to.exportBackup())
        assertEquals(protocol, f.to.messages(c.id).last().second.protocolItems)
        assertTrue(f.to.messages(c.id).last().second.historySegments.isEmpty()); assertEquals("既有未创建草稿", f.to.draft())
    }

}
