package app.daiyu.android.storage

import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.context.ProductionRunContext
import app.daiyu.android.context.TokenTailCompression
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.testContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32

/** Synthetic PNG and private test database only; never reads or controls a screen. */
@Suppress("DEPRECATION")
class ScreenProtocolHistoryAndroidTest : InstrumentationTestCase() {
    private fun output(): String {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val image = ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); bitmap.recycle(); stream.toByteArray()
        }
        val data = ByteArray(1_360_000)
        val type = "raNd".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }.value.toInt()
        val chunk = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(data.size).array()); write(type); write(data)
            write(ByteBuffer.allocate(4).putInt(crc).array())
        }.toByteArray()
        val png = image.copyOfRange(0, image.size - 12) + chunk + image.copyOfRange(image.size - 12, image.size)
        return JSONObject().put("capture_id", "synthetic").put("mime_type", "image/png")
            .put("width", 4).put("height", 3).put("data_base64", Base64.getEncoder().encodeToString(png)).toString()
    }

    fun testLargeVisualStreamingProjectionAndContextSnapshotDoNotWaitForWriter() {
        val context = testContext(instrumentation.targetContext, "screen-projection-${UUID.randomUUID()}-")
        val database = ConversationDatabase(context)
        var runtime: SQLiteRuntimeStore? = null
        val release = java.util.concurrent.CountDownLatch(1)
        val held = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val holding = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val store = SQLiteRuntimeStore.acquire(database).also { runtime = it; it.recoverPending() }
            val conversation = database.create("default").id
            val initial = Message(MessageId("projection"), conversation, RunId("run"), "assistant", emptyList(), RunStatus.RUNNING)
            val model = app.daiyu.android.ui.model.ContextUsageModel("synthetic", "synthetic", 1, 100_000)
            store.bindContextUsage(initial, model)
            val assistants = app.daiyu.android.assistants.SqliteAssistantStore(database)
            val profile = assistants.assistants.value.first()
            val result = JSONObject(output()).put("screen_read_mode", "visual_fallback")
                .put("node_tree_available", false).put("node_failure_code", "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
                .put("visual_actions_available", true).toString()
            val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("screen"), "screen", "{\"operation\":\"observe\"}"), result)
            var builds = 0
            val cache = app.daiyu.android.ui.screens.chat.conversation.ToolPresentationCache({ n, a, r ->
                builds++; app.daiyu.android.ui.screens.chat.conversation.ToolCardPresentations.present(n, a, r)
            })
            val writer = executor.submit {
                synchronized(store) {
                    val db = database.writableDatabase
                    db.beginTransaction()
                    try {
                        holding.set(true); held.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    } finally { holding.set(false); db.endTransaction() }
                }
            }
            assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val began = android.os.SystemClock.elapsedRealtimeNanos()
            instrumentation.runOnMainSync {
                assertSame(model, store.contextUsageModel(conversation))
                assertNull(store.contextUsageModel(ConversationId("other")))
                assertEquals(profile.name, assistants.assistants.value.first { it.id == profile.id }.name)
                repeat(100) { index ->
                    val message = initial.copy(blocks = listOf(tool, MessageBlock.Text("tail", "stream-$index")))
                    val turn = app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper.map(listOf(message), presentations = cache)
                        .single() as app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi.Assistant
                    assertTrue(turn.workProcess!!.segments.flatMap { it.steps }.single().hasScreenScreenshot)
                }
                assertTrue("Main projection waited for the runtime monitor or SQLite writer", holding.get())
            }
            assertEquals(1, builds)
            android.util.Log.i("DaiyuFreezeRegression", "projections=100 builds=$builds elapsed_ns=${android.os.SystemClock.elapsedRealtimeNanos() - began}")
            release.countDown(); writer.get(5, java.util.concurrent.TimeUnit.SECONDS)
            cache.clear()
            store.close(); runtime = null
            assertTrue(runCatching { store.contextUsageModel(conversation) }.exceptionOrNull() is IllegalStateException)
        } finally {
            release.countDown(); executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            runtime?.close(); database.close(); context.deleteDatabase("conversations.db")
        }
    }

    fun testLargeScreenProtocolCommitsReadsAndRecoversWithoutReplayingAction() {
        val context = testContext(instrumentation.targetContext, "screen-history-${UUID.randomUUID()}-")
        val database = ConversationDatabase(context)
        var runtime: SQLiteRuntimeStore? = null
        try {
            val store = SQLiteRuntimeStore.acquire(database).also { runtime = it; it.recoverPending() }
            val conversation = database.create("default").id
            val user = Message(MessageId("user"), conversation, RunId("run"), "user",
                listOf(MessageBlock.Text("question", "synthetic")), RunStatus.COMPLETED)
            val assistant = user.copy(id = MessageId("assistant"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
            database.send(user, assistant)
            val args = """{"operation":"screenshot"}"""
            val call = ToolInvocation(ToolCallId("screen"), "screen", args)
            val result = output()
            val rawCall = JSONObject().put("type", "function_call").put("call_id", "screen").put("name", "screen").put("arguments", args).toString()
            val rawResult = JSONObject().put("type", "function_call_output").put("call_id", "screen").put("output", result).toString()
            assertTrue(rawResult.length > 1_000_000)
            val blocks = listOf(MessageBlock.Tool(call, result))
            val items = listOf(rawCall, rawResult)
            val message = assistant.copy(blocks = blocks, protocolItems = items, historySegments = listOf(
                MessageHistorySegment(assistant.id, "assistant", blocks, items)))
            val first = RunCheckpoint(message, listOf(user), ExecutionPhase.BOUNDARY,
                tools = listOf(ToolRecord(call, ToolDisposition.SUCCEEDED, result)), owner = "owner", revision = 1)
            store.commitCheckpoint(first)
            assertTextCheckpoint(database, first.message.id.value, result.length.toLong() * 3)
            assertEquals(first, store.readCommittedCheckpoint(first))
            val action = ToolInvocation(ToolCallId("action"), "screen_action", "{}")
            val nextBlocks = blocks + MessageBlock.Tool(action)
            val next = first.copy(revision = 2, phase = ExecutionPhase.TOOL_EXECUTING,
                tools = first.tools + ToolRecord(action, ToolDisposition.IN_FLIGHT), message = message.copy(blocks = nextBlocks,
                    historySegments = listOf(MessageHistorySegment(message.id, "assistant", nextBlocks, items))))
            database.writableDatabase.execSQL("CREATE TEMP TRIGGER checkpoint_write_failure BEFORE UPDATE ON messages WHEN OLD.id='assistant' " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic_checkpoint_failure'); END")
            try {
                assertTrue(runCatching { store.commitCheckpoint(next) }.exceptionOrNull() is android.database.sqlite.SQLiteException)
                assertEquals(first, store.readCommittedCheckpoint(first))
                assertEquals(first.message, database.messages(conversation).last().second)
            } finally {
                database.writableDatabase.execSQL("DROP TRIGGER checkpoint_write_failure")
            }
            store.commitCheckpoint(next)
            assertTextCheckpoint(database, next.message.id.value, result.length.toLong() * 3)
            assertEquals(next, store.readCommittedCheckpoint(next))
            assertEquals(next.message, database.messages(conversation).last().second)
            store.close(); runtime = null
            val reopened = SQLiteRuntimeStore.acquire(database).also { runtime = it }
            val recovered = reopened.reconcileCheckpoint(first)
            assertEquals(RunStatus.INTERRUPTED, recovered.message.status)
            assertEquals(ToolDisposition.UNKNOWN, recovered.tools.last().disposition)
            assertEquals(result, (recovered.message.blocks.first() as MessageBlock.Tool).result)
            assertEquals(recovered, reopened.reconcileCheckpoint(first))
        } finally {
            runtime?.close(); database.close(); context.deleteDatabase("conversations.db")
        }
    }
    fun testLargeObserveReceiptPassesProductionCapacityBeforeNativeContinuationAdmission() {
        for (billed in listOf(false, true)) {
            val context = testContext(instrumentation.targetContext, "screen-capacity-${UUID.randomUUID()}-")
            val database = ConversationDatabase(context)
            var store: SQLiteRuntimeStore? = null
            var runner: ChatRunner? = null
            val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val runtime = SQLiteRuntimeStore.acquire(database).also { store = it; it.recoverPending() }
                val conversation = database.create("default").id
                val user = Message(MessageId("user"), conversation, RunId("run"), "user",
                    listOf(MessageBlock.Text("question", "synthetic screen task")), RunStatus.COMPLETED)
                val assistant = user.copy(id = MessageId("assistant"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
                database.send(user, assistant)
                val args = "{\"operation\":\"observe\"}"
                val output = JSONObject(output()).put("screen_read_mode", "visual_fallback")
                    .put("node_tree_available", false).put("node_failure_code", "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
                    .put("visual_actions_available", true).toString()
                assertTrue(JSONObject(output).getString("data_base64").length >= 1_800_000)
                val capacity = ProductionRunContext(CompressionPolicy(true, 272000, reserveTokens = 4096),
                    assistant, null, automatic = false)
                val call = ToolInvocation(ToolCallId("screen"), "screen", args)
                assertTrue(TokenTailCompression.textTokens(output) > capacity.sendBudgetTokens)
                assertTrue(capacity.localTokens(listOf(user, assistant.copy(blocks = listOf(MessageBlock.Tool(call, output)))), emptyList()) < capacity.sendBudgetTokens)
                val posts = java.util.concurrent.atomic.AtomicInteger()
                val tools = java.util.concurrent.atomic.AtomicInteger()
                val sent = mutableListOf<ByteArrayOutputStream>()
                val model = ResponsesModel(ModelSettings("https://example.invalid/v1/responses", "synthetic", "synthetic-key", false), {
                    val turn = posts.getAndIncrement()
                    val item = JSONObject().put("type", "function_call").put("id", "fc").put("call_id", "screen")
                        .put("name", "screen").put("arguments", args)
                    val answer = JSONObject().put("id", "answer").put("type", "message").put("role", "assistant")
                        .put("content", org.json.JSONArray().put(JSONObject().put("type", "output_text").put("text", "已完成截图续答")))
                    val response = JSONObject().put("status", "completed").put("output", org.json.JSONArray().put(if (turn == 0) item else answer))
                    if (billed) response.put("usage", JSONObject().put("input_tokens", 12508).put("output_tokens", 22))
                    val deltas = if (turn == 0) "" else listOf("已", "完成", "截图续答").joinToString("") { text ->
                        "data: ${JSONObject().put("type", "response.output_text.delta").put("item_id", "answer").put("content_index", 0).put("delta", text)}\n\n"
                    }
                    val events = (if (turn == 0) "data: ${JSONObject().put("type", "response.output_item.done").put("item", item)}\n\n" else deltas) +
                        "data: ${JSONObject().put("type", "response.completed").put("response", response)}\n\n"
                    val buffer = ByteArrayOutputStream().also(sent::add)
                    object : javax.net.ssl.HttpsURLConnection(java.net.URL("https://example.invalid/fixture")) {
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy() = false
                        override fun getCipherSuite() = "fixture"
                        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
                        override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
                        override fun getOutputStream(): java.io.OutputStream {
                            if (turn == 1) {
                                val committed = SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, assistant.id.value)!!
                                assertTrue(committed.requestUnconfirmed)
                                assertNull(committed.context!!.capacity)
                                assertEquals(output, (committed.message.blocks.single() as MessageBlock.Tool).result)
                                assertEquals(ToolDisposition.SUCCEEDED, committed.tools.single().disposition)
                            }
                            return buffer
                        }
                        override fun getInputStream() = events.byteInputStream()
                        override fun getResponseCode() = 200
                        override fun getContentType() = "text/event-stream"
                    }
                }, true)
                val active = ChatRunner(model, database, BatteryTool { invocation ->
                    assertEquals(call, invocation); tools.incrementAndGet(); output
                }, RunnerOptions(runtimeStore = runtime, contextRuntime = capacity)) {}.also { runner = it }
                val future = executor.submit<Message> {
                    worker.set(Thread.currentThread())
                    active.run(assistant, listOf(user))
                }
                val completed = try {
                    future.get(120, java.util.concurrent.TimeUnit.SECONDS)
                } catch (error: java.util.concurrent.TimeoutException) {
                    val thread = worker.get()
                    throw AssertionError("Native screen continuation timed out: billed=$billed, posts=${posts.get()}, tools=${tools.get()}, worker=${thread?.name}/${thread?.state}\n" +
                        thread?.stackTrace?.joinToString("\n").orEmpty(), error)
                }
                assertEquals(RunStatus.COMPLETED, completed.status)
                assertEquals(2, posts.get()); assertEquals(1, tools.get())
                val wire = sent.last().toString("UTF-8")
                assertFalse(wire.contains("data_base64")); assertTrue(wire.contains("input_image"))
                val saved = SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, assistant.id.value)!!
                assertNull(saved.context!!.capacity)
                assertEquals(output, database.messages(conversation).last().second.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
                assertEquals(output, saved.tools.single().result)
                assertEquals("已完成截图续答", saved.message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("") { it.text })
                assertEquals(ExecutionPhase.TERMINAL, saved.phase)
                assertFalse(saved.requestUnconfirmed)
                assertTextCheckpoint(database, assistant.id.value, output.length.toLong() * 3)
            } finally {
                runner?.stop(); executor.shutdownNow(); executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
                store?.close(); database.close(); context.deleteDatabase("conversations.db")
            }
        }
    }

    private fun assertTextCheckpoint(database: ConversationDatabase, messageId: String, minimumBytes: Long) {
        database.readableDatabase.rawQuery("SELECT typeof(payload),length(CAST(payload AS BLOB)) FROM runtime_checkpoints WHERE message_id=?",
            arrayOf(messageId)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("text", cursor.getString(0))
            assertTrue(cursor.getLong(1) >= minimumBytes)
        }
    }

    fun testRealCheckpointSizedUtf8CountMatchesAndroidAndKeepsRecoveryReserve() {
        val payload = "x".repeat(13_578_756)
        repeat(8) {
            val bytes = app.daiyu.android.context.Utf8Payload.byteCount(payload, Long.MAX_VALUE)
            assertEquals(13_578_756L, bytes)
            RunCheckpointCodec.checkEncodedSize(bytes, true)
        }
        for (sample in listOf("中😼", "x".repeat(4095) + "😼", "a\uD800b", "\uDC00")) {
            assertEquals(sample.toByteArray(Charsets.UTF_8).size.toLong(),
                app.daiyu.android.context.Utf8Payload.byteCount(sample, Long.MAX_VALUE))
        }
        android.database.sqlite.SQLiteDatabase.create(null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE encoding_probe(id INTEGER PRIMARY KEY,payload TEXT)")
            val writer = CheckpointJsonWriter()
            for (sample in listOf("中🙂\u0000\"\\/", "x".repeat(4095) + "</🙂", "a\ud800b\udfff")) {
                val root = JSONObject().put("value", sample)
                val legacy = root.toString()
                val encoded = writer.writeUtf8(root, 64 * 1024)
                sqlite.execSQL("DELETE FROM encoding_probe")
                sqlite.compileStatement("INSERT INTO encoding_probe VALUES(1,?)").use { statement ->
                    statement.bindString(1, legacy); statement.executeInsert()
                }
                sqlite.compileStatement("INSERT INTO encoding_probe VALUES(2,CAST(? AS TEXT))").use { statement ->
                    statement.bindBlob(1, encoded); statement.executeInsert()
                }
                val rows = sqlite.rawQuery("SELECT typeof(payload),CAST(payload AS BLOB) FROM encoding_probe ORDER BY id", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) {
                        assertEquals("text", cursor.getString(0)); add(cursor.getBlob(1))
                    } }
                }
                assertTrue(java.util.Arrays.equals(legacy.toByteArray(Charsets.UTF_8), rows[1]))
                if (!sample.contains('\ud800')) assertTrue(java.util.Arrays.equals(rows[0], rows[1]))
                assertTrue(JSONObject(String(rows[1], Charsets.UTF_8)).has("value"))
            }
        }
        val max = RunCheckpointCodec.MAX_BYTES.toLong()
        RunCheckpointCodec.checkEncodedSize(max - RunCheckpointCodec.RECOVERY_RESERVE, true)
        assertTrue(runCatching { RunCheckpointCodec.checkEncodedSize(max - RunCheckpointCodec.RECOVERY_RESERVE + 1, true) }.isFailure)
        RunCheckpointCodec.checkEncodedSize(max, false)
        assertTrue(runCatching { RunCheckpointCodec.checkEncodedSize(max + 1, false) }.isFailure)
        assertTrue(runCatching { RunCheckpointCodec.checkEncodedSize(Long.MAX_VALUE, false) }.isFailure)
        android.util.Log.i("DaiyuFreezeRegression", "utf8_count_chars=13578756 repeats=8 exact=true")
    }

}
