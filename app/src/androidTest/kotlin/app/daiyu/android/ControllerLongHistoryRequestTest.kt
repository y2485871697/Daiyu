package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.ProductionCompressionSettings
import app.daiyu.android.context.ProductionCompressionSettingsStore
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.estimateTokens
import app.daiyu.android.model.MessagesModel
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.tasksurface.TaskSurfacePreference
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Production preparation, SQLite, Runner and HTTP serialization; no socket or paid request. */
@Suppress("DEPRECATION")
class ControllerLongHistoryRequestTest : InstrumentationTestCase() {
    private class App(base: Context) : Application() {
        val balances = fixtureProviderBalances()
        private val root = File(base.cacheDir, "long-history-${UUID.randomUUID()}").apply { check(mkdirs()) }
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
    }
    private class LocalHttps(url: URL, events: String) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        private val incoming = events.byteInputStream(Charsets.UTF_8)
        override fun connect() {}
        override fun disconnect() { incoming.close() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = incoming
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream; charset=utf-8"
    }
    fun testResponsesNextRequestRetainsLargeReopenedHistory() = checkNextRequest(ProviderProtocol.RESPONSES)
    fun testMessagesNextRequestRetainsLargeReopenedHistory() = checkNextRequest(ProviderProtocol.MESSAGES)

    private fun awaitState(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var done = false
        while (!done && System.nanoTime() < deadline) {
            controlMain { done = predicate() }
            if (!done) Thread.sleep(10)
        }
        assertTrue(description, done)
    }
    private fun completeWire(protocol: ProviderProtocol): String {
        fun event(type: String) = JSONObject().put("type", type)
        if (protocol == ProviderProtocol.RESPONSES) {
            val message = event("message").put("id", "next-answer").put("role", "assistant")
                .put("status", "completed").put("content", JSONArray().put(
                    event("output_text").put("text", "synthetic next answer").put("annotations", JSONArray())))
            return "data: ${event("response.completed").put("response", JSONObject().put("status", "completed")
                .put("output", JSONArray().put(message)))}\n\n"
        }
        return listOf(
            event("message_start").put("message", JSONObject().put("id", "next-answer").put("role", "assistant")
                .put("content", JSONArray()).put("usage", JSONObject().put("input_tokens", 3))),
            event("content_block_start").put("index", 0).put("content_block", event("text").put("text", "")),
            event("content_block_delta").put("index", 0).put("delta", event("text_delta").put("text", "synthetic next answer")),
            event("content_block_stop").put("index", 0),
            event("message_delta").put("delta", JSONObject().put("stop_reason", "end_turn"))
                .put("usage", JSONObject().put("output_tokens", 3)),
            event("message_stop"),
        ).joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }
    }
    private fun checkNextRequest(protocol: ProviderProtocol) {
        val token = UUID.randomUUID().toString()
        val app = App(testContext(instrumentation.targetContext, "long-history-$token-"))
        val alias = "app.daiyu.android.test.long-history.$token"
        var db = ConversationDatabase(app)
        var controller: ChatController? = null
        val head = "EARLIEST_HEAD_$token"
        val tail = "EARLIEST_TAIL_$token"
        val pending = "NEXT_ROUND_$token"
        val before = "BEFORE_TOOL_$token"
        val after = "AFTER_TOOL_$token"
        val result = "FULL_TOOL_RESULT_汉🙂_$token"
        val call = ToolInvocation(ToolCallId("old-call-$token"), "lookup",
            JSONObject().put("marker", "old-arguments-$token").toString())
        val large = head + "汉字🙂é".repeat(270_000) + tail
        try {
            assertTrue(large.codePointCount(0, large.length) > 1_000_000)
            assertTrue(large.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024)
            val owner = db.create("default")
            val run = RunId("historical-run-$token")
            val user = Message(MessageId("earliest-user-$token"), owner.id, run, "user",
                listOf(MessageBlock.Text("large-body", large)), RunStatus.COMPLETED)
            val initial = Message(MessageId("earliest-assistant-$token"), owner.id, run, "assistant", emptyList(), RunStatus.RUNNING)
            db.send(user, initial)
            var rounds = 0
            var executions = 0
            val messagesFirst = JSONObject().put("role", "assistant").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", before))
                .put(JSONObject().put("type", "tool_use").put("id", call.id.value)
                    .put("name", call.name).put("input", JSONObject(call.arguments)))).toString()
            val messagesResult = JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "tool_result").put("tool_use_id", call.id.value)
                    .put("content", result))).toString()
            val messagesLast = JSONObject().put("role", "assistant").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", after))).toString()
            val session = object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    when (rounds++) {
                        0 -> {
                            check(outputs.isEmpty())
                            sink(ModelEvent.TextFinal("before", before)); sink(ModelEvent.ToolReady(call))
                            if (protocol == ProviderProtocol.MESSAGES)
                                sink(ModelEvent.ProtocolOutput(listOf(messagesFirst)))
                        }
                        1 -> {
                            check(outputs == listOf(ToolOutput(call.id, result)))
                            sink(ModelEvent.TextFinal("after", after))
                            if (protocol == ProviderProtocol.MESSAGES)
                                sink(ModelEvent.ProtocolOutput(listOf(messagesFirst, messagesResult, messagesLast)))
                        }
                        else -> error("unexpected historical round")
                    }
                }
                override fun cancel() {}
            }
            val answer = ChatRunner(ModelPort { session }, db, BatteryTool { executions++; result }, {})
                .run(initial, listOf(user))
            assertEquals(RunStatus.COMPLETED, answer.status)
            assertEquals(1, executions)
            assertFalse(answer.historySegments.isEmpty())
            // Keep the oldest row outside the default 50-message display page.
            repeat(31) { index ->
                val r = RunId("filler-$index-$token")
                val u = Message(MessageId("filler-user-$index-$token"), owner.id, r, "user",
                    listOf(MessageBlock.Text("u", "filler question $index")), RunStatus.COMPLETED)
                val a = Message(MessageId("filler-assistant-$index-$token"), owner.id, r, "assistant",
                    listOf(MessageBlock.Text("a", "filler answer $index")), RunStatus.RUNNING)
                db.send(u, a); db.save(a.copy(status = RunStatus.COMPLETED))
            }
            db.close(); db = ConversationDatabase(app)
            assertEquals(50, db.messages(owner.id).size)
            assertFalse(db.messages(owner.id).any { it.second.id == user.id })
            val prepared = ContextService(app, db).prepareSend(owner.id)
            assertNull(prepared.archiveId)
            assertEquals(64, prepared.messages.size)
            assertTrue(prepared.messages.first().blocks == listOf(MessageBlock.Text("large-body", large)))
            assertEquals(answer, prepared.messages[1])
            val budgetUser = user.copy(id = MessageId("budget-$token"), runId = RunId("budget-$token"),
                blocks = listOf(MessageBlock.Text("pending", pending)))
            assertTrue(estimateTokens(prepared.messages + budgetUser) < 10_000_000L - 4096L)
            ProductionCompressionSettingsStore(app).save(ProductionCompressionSettings(
                automatic = false, contextWindowTokens = 10_000_000L, reserveTokens = 4096L))
            val settings = ModelSettingsStore(app, alias)
            settings.save(ModelSettings("https://long-history.invalid", "long-history-fixture", "synthetic-key", false,
                protocol = protocol, contextWindowOverride = 10_000_000), protocolChangeAuthorized = true)
            val connections = CopyOnWriteArrayList<LocalHttps>()
            val factory: (ModelSettings) -> ModelPort = { config ->
                val connect: (URL) -> HttpsURLConnection = { url ->
                    check(connections.isEmpty()) { "unexpected retry/summary; never open a real connection" }
                    LocalHttps(url, completeWire(protocol)).also(connections::add)
                }
                if (protocol == ProviderProtocol.RESPONSES) ResponsesModel(config, connect) else MessagesModel(config, connect)
            }
            val c = ChatController(app, db, settings, app.getSharedPreferences("conversation-ui", 0), factory,
                taskSurfaceStore = isolatedTaskSurfaceStore(TaskSurfacePreference.FOREGROUND),
                titleModelFactory = { error("unexpected title model request") }, providerBalanceHost = { app.balances })
            controller = c
            awaitState("controller ready after reopen") { c.ready }
            assertNull(c.problem)
            val reopenedOwner = db.conversations().single { it.id == owner.id }
            db.draft(owner.id, pending)
            val accepted = AtomicReference<ChatController.TextSendResult>()
            val done = CountDownLatch(1)
            controlMain {
                assertTrue(c.sendText(reopenedOwner, 1, 1, pending) { accepted.set(it); done.countDown() })
            }
            assertTrue("send acceptance timed out", done.await(60, TimeUnit.SECONDS))
            assertTrue(accepted.get() is ChatController.TextSendResult.Accepted)
            awaitState("next real runner completed") { c.active == null }
            assertNull(c.problem)
            assertEquals(1, connections.size)
            val connection = connections.single()
            assertEquals("POST", connection.requestMethod)
            assertEquals(if (protocol == ProviderProtocol.RESPONSES) "/v1/responses" else "/v1/messages", connection.url.path)
            val bytes = connection.sent.toByteArray()
            assertTrue("actual HTTP body exceeds 2 MiB", bytes.size > 2 * 1024 * 1024)
            val wire = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val request = JSONObject(wire)
            assertEquals("long-history-fixture", request.getString("model"))
            assertHistory(protocol, request, large, head, tail, pending, before, after, call, result)
            val expected = mutableListOf("user" to large, "assistant" to before,
                "assistant" to "call:${call.id.value}", "user" to result, "assistant" to after)
            repeat(31) { index ->
                expected += "user" to "filler question $index"
                expected += "assistant" to "filler answer $index"
            }
            expected += "user" to pending
            assertTrue("every history row, role and tool must reach the wire once in order",
                normalizedHistory(protocol, request) == expected)
            assertEquals(1, executions)
            assertEquals(66, ContextService(app, db).displayHistory(owner.id).size)
            assertNull(ContextService(app, db).prepareSend(owner.id).archiveId)
            assertEquals(RunStatus.COMPLETED, db.messages(owner.id).last().second.status)
        } finally {
            controller?.stop(); controller?.close(); app.balances.close(); db.close()
            app.deleteDatabase("conversations.db"); app.filesDir.deleteRecursively()
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it.startsWith("$alias.") }.forEach { keys.deleteEntry(it) }
        }
    }
    private fun normalizedHistory(protocol: ProviderProtocol, request: JSONObject): List<Pair<String, String>> {
        val array = request.getJSONArray(if (protocol == ProviderProtocol.RESPONSES) "input" else "messages")
        return (0 until array.length()).flatMap { index ->
            val row = array.getJSONObject(index)
            if (protocol == ProviderProtocol.RESPONSES) listOf(when (row.optString("type")) {
                "function_call" -> "assistant" to "call:${row.getString("call_id")}"
                "function_call_output" -> "user" to row.getString("output")
                else -> row.getString("role") to row.getString("content")
            }) else {
                val content = row.getJSONArray("content")
                (0 until content.length()).map { blockIndex ->
                    val block = content.getJSONObject(blockIndex)
                    row.getString("role") to when (block.getString("type")) {
                        "text" -> block.getString("text")
                        "tool_use" -> "call:${block.getString("id")}"
                        "tool_result" -> block.getString("content")
                        else -> error("unexpected historical block")
                    }
                }
            }
        }
    }
    private fun assertHistory(protocol: ProviderProtocol, request: JSONObject, large: String,
        head: String, tail: String, pending: String, before: String, after: String,
        call: ToolInvocation, result: String) {
        if (protocol == ProviderProtocol.RESPONSES) {
            val input = request.getJSONArray("input")
            val rows = (0 until input.length()).map(input::getJSONObject)
            val restored = rows.first().getString("content")
            assertTrue("whole oldest payload reaches HTTP bytes", restored == large)
            assertTrue(restored.startsWith(head)); assertTrue(restored.endsWith(tail))
            assertEquals(pending, rows.last().getString("content"))
            assertEquals(1, rows.count { it.optString("content") == pending })
            val invocation = rows.single { it.optString("type") == "function_call" }
            val output = rows.single { it.optString("type") == "function_call_output" }
            assertEquals(call.id.value, invocation.getString("call_id"))
            assertEquals(call.name, invocation.getString("name")); assertEquals(call.arguments, invocation.getString("arguments"))
            assertEquals(call.id.value, output.getString("call_id")); assertEquals(result, output.getString("output"))
            val beforeIndex = rows.indexOfFirst { it.optString("content") == before }
            val afterIndex = rows.indexOfFirst { it.optString("content") == after }
            assertTrue(beforeIndex >= 0 && beforeIndex < rows.indexOf(invocation))
            assertTrue(rows.indexOf(invocation) < rows.indexOf(output) && rows.indexOf(output) < afterIndex)
        } else {
            val messages = request.getJSONArray("messages")
            val rows = (0 until messages.length()).map(messages::getJSONObject)
            val restored = rows.first().getJSONArray("content").getJSONObject(0).getString("text")
            assertTrue("whole oldest payload reaches HTTP bytes", restored == large)
            assertTrue(restored.startsWith(head)); assertTrue(restored.endsWith(tail))
            assertEquals(pending, rows.last().getJSONArray("content").getJSONObject(0).getString("text"))
            val blocks = rows.flatMap { row ->
                val content = row.getJSONArray("content")
                (0 until content.length()).map(content::getJSONObject)
            }
            assertEquals(1, blocks.count { it.optString("text") == pending })
            val invocation = blocks.single { it.optString("type") == "tool_use" }
            val output = blocks.single { it.optString("type") == "tool_result" }
            assertEquals(call.id.value, invocation.getString("id")); assertEquals(call.name, invocation.getString("name"))
            assertEquals(JSONObject(call.arguments).getString("marker"), invocation.getJSONObject("input").getString("marker"))
            assertEquals(call.id.value, output.getString("tool_use_id")); assertEquals(result, output.getString("content"))
            val beforeIndex = blocks.indexOfFirst { it.optString("text") == before }
            val afterIndex = blocks.indexOfFirst { it.optString("text") == after }
            assertTrue(beforeIndex >= 0 && beforeIndex < blocks.indexOf(invocation))
            assertTrue(blocks.indexOf(invocation) < blocks.indexOf(output) && blocks.indexOf(output) < afterIndex)
        }
    }
}
