package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.model.ResponsesRequest
import app.daiyu.android.model.ResponsesSearchProtocol
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

@Suppress("DEPRECATION")
class ResponsesSearchBackupContractTest : InstrumentationTestCase() {
    private fun fixture(test: (ConversationDatabase, ConversationDatabase) -> Unit) {
        val prefix = "search-backup-${UUID.randomUUID()}-"
        val source = testContext(instrumentation.targetContext, prefix + "source-")
        val target = testContext(instrumentation.targetContext, prefix + "target-")
        val from = ConversationDatabase(source); val to = ConversationDatabase(target)
        try { test(from, to) } finally {
            try { from.close() } finally { to.close(); source.deleteDatabase("conversations.db"); target.deleteDatabase("conversations.db") }
        }
    }
    private fun seed(db: ConversationDatabase, segmented: Boolean, chat: Boolean = false): Message {
        val c = db.create("default"); val run = RunId("search-run")
        val user = Message(MessageId("u"), c.id, run, "user", listOf(MessageBlock.Text("u", "question")), RunStatus.COMPLETED)
        val initial = Message(MessageId("a"), c.id, run, "assistant", emptyList(), RunStatus.RUNNING)
        db.send(user, initial)
        val search = """{"type":"web_search_call","id":"ws","status":"failed","action":{"type":"search"}}"""
        val item = JSONObject("""{"type":"message","id":"msg","role":"assistant","status":"completed","content":[{"type":"output_text","text":"facts","annotations":[{"type":"url_citation","start_index":0,"end_index":5,"url":"https://example.org/source","title":"Source"}]}]}""")
        val chatItem = JSONObject().put("role", "assistant").put("content", "facts").put("annotations", JSONArray()
            .put(JSONObject().put("type", "url_citation").put("url_citation", JSONObject().put("start_index", 0)
                .put("end_index", 5).put("url", "https://example.org/source").put("title", "Source"))))
        val protocol = if (chat) listOf(chatItem.toString()) else listOf(search, item.toString())
        val displayed = if (chat) app.daiyu.android.model.ChatSearchProtocol.displayText(chatItem)
            else ResponsesSearchProtocol.displayText(item, "facts")
        val blocks = listOf(MessageBlock.Text("msg", displayed))
        return initial.copy(status = RunStatus.COMPLETED, blocks = blocks, protocolItems = protocol,
            historySegments = if (segmented) listOf(MessageHistorySegment(user.id, user.role, user.blocks),
                MessageHistorySegment(initial.id, initial.role, blocks, protocol)) else emptyList()).also(db::save)
    }
    fun testRawSourcesSurviveSqliteReopenTextBackupAndSegmentedReplay() {
        for (chat in listOf(false, true)) for (segmented in listOf(false, true)) fixture { from, to ->
            val expected = seed(from, segmented, chat); val backup = from.exportBackup()
            from.close(); from.readableDatabase
            assertEquals(expected, from.messages(expected.conversationId).last().second)
            to.preflightBackup(backup); assertEquals(1 to 2, to.importBackup(backup))
            to.close(); to.readableDatabase
            val actual = to.messages(expected.conversationId).last().second
            assertEquals(expected, actual); assertEquals(backup, to.exportBackup())
            if (chat) {
                val replay = app.daiyu.android.model.NativeCompletionRequest.history(
                    app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS, listOf(actual))
                val replayed = (0 until replay.length()).map { replay.getJSONObject(it) }.single { it.optString("role") == "assistant" }
                assertEquals("https://example.org/source", replayed.getJSONArray("annotations").getJSONObject(0)
                    .getJSONObject("url_citation").getString("url"))
            } else {
                val replay = ResponsesRequest.history(listOf(actual))
                val search = (0 until replay.length()).map { replay.getJSONObject(it) }.single { it.optString("type") == "web_search_call" }
                assertEquals("failed", search.getString("status"))
            }
            assertTrue((actual.blocks.single() as MessageBlock.Text).text.contains("](<https://example.org/source>)"))
        }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun testResignedUnsafeSourcesRejectBothProtocolLocationsWithoutChangingDatabase() {
        for (chat in listOf(false, true)) for (segmented in listOf(false, true)) fixture { from, to ->
            seed(from, segmented, chat); to.draft("existing draft")
            val before = to.exportBackup(); val valid = from.exportBackup()
            for (url in listOf("javascript:alert(1)", "intent://open", "https://user@example.org/source")) {
                val envelope = JSONObject(valid); val payload = JSONObject(envelope.getString("payload"))
                val owner = payload.getJSONArray("messages").getJSONObject(1)
                val protocol = if (segmented) owner.getJSONArray("history_segments").getJSONObject(1).getJSONArray("protocol")
                    else owner.getJSONArray("protocol")
                val index = if (chat) 0 else 1
                val item = JSONObject(protocol.getString(index))
                if (chat) item.getJSONArray("annotations").getJSONObject(0).getJSONObject("url_citation").put("url", url)
                else item.getJSONArray("content").getJSONObject(0).getJSONArray("annotations").getJSONObject(0).put("url", url)
                protocol.put(index, item.toString())
                if (segmented) owner.put("protocol", JSONArray(protocol.toString()))
                val text = payload.toString()
                val polluted = envelope.put("payload", text).put("sha256", digest(text)).toString()
                try { to.preflightBackup(polluted); fail("unsafe citation") } catch (_: IllegalArgumentException) {}
                try { to.importBackup(polluted); fail("unsafe citation") } catch (_: IllegalArgumentException) {}
                assertEquals(before, to.exportBackup()); assertEquals("existing draft", to.draft())
            }
        }
    }
}
