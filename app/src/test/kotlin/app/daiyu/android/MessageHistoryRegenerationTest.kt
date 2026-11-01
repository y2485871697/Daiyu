package app.daiyu.android

import app.daiyu.android.context.ContextService
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.domain.tools.LedgerEntry
import app.daiyu.android.domain.tools.LedgerState
import app.daiyu.android.integration.tools.SQLiteToolLedgerStore
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.model.MessagesRequest
import app.daiyu.android.model.NativeCompletionRequest
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ResponsesRequest
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageActionStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real SQLite originals/CAS/ledger plus actual provider request builders. No network. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MessageHistoryRegenerationTest {
    private lateinit var db: ConversationDatabase
    private lateinit var root: File
    private lateinit var context: ContextService
    private lateinit var actions: MessageActionStore
    private lateinit var ledger: SQLiteToolLedgerStore
    private data class Seed(val id: ConversationId, val old: Message, val user: Message, val answer: Message)
    private val prompt = "核实 Unicode：黛玉，路径 C:\\reports\\\"quoted\"\n" + "只读检查，不重放未知动作。".repeat(80)
    private val call = ToolInvocation(ToolCallId("old-unknown-call"), "run_child_models",
        JSONObject().put("tasks", JSONArray()
            .put(JSONObject().put("profile_id", "review-甲").put("prompt", prompt))
            .put(JSONObject().put("profile_id", "review-乙").put("prompt", "核对批次结果\\保留未知")))
            .toString())

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("conversations.db")
        root = Files.createTempDirectory("history-regeneration").toFile()
        db = ConversationDatabase(app)
        context = ContextService(app, db)
        actions = MessageActionStore(db, context, MessageImageAssetStore(File(root, "images")))
        ledger = SQLiteToolLedgerStore(db)
    }
    @After fun tearDown() {
        db.close(); RuntimeEnvironment.getApplication().deleteDatabase("conversations.db"); root.deleteRecursively()
    }
    private fun seed(segmented: Boolean = false): Seed {
        val id = db.create("default").id
        fun turn(index: Int, blocks: List<MessageBlock>, status: RunStatus,
            transform: (Message) -> Message = { it }): Pair<Message, Message> {
            val run = RunId(UUID.randomUUID().toString())
            val user = Message(MessageId(UUID.randomUUID().toString()), id, run, Message.USER_ROLE,
                listOf(MessageBlock.Text("q", "question-$index")), RunStatus.COMPLETED)
            val answer = user.copy(id = MessageId(UUID.randomUUID().toString()), role = Message.ASSISTANT_ROLE,
                blocks = blocks, status = RunStatus.RUNNING)
            db.send(user, answer)
            val ended = transform(answer.copy(status = status))
            db.save(ended)
            return user to ended
        }
        val old = turn(0, listOf(MessageBlock.Tool(call)), RunStatus.FAILED) { original ->
            val raw = listOf("opaque-native-snapshot-must-not-be-replayed")
            val segments = if (!segmented) emptyList() else listOf(
                MessageHistorySegment(original.id, Message.ASSISTANT_ROLE, original.blocks, raw),
                MessageHistorySegment(MessageId("virtual-user-${id.value}"), Message.USER_ROLE,
                    listOf(MessageBlock.Text("supplement", "old supplement"))),
                MessageHistorySegment(original.id, Message.ASSISTANT_ROLE,
                    listOf(MessageBlock.Text("partial", "old partial text"))))
            original.copy(error = "工具结果未知；已停止，禁止重放，请仅使用只读方式核实",
                protocolItems = raw, historySegments = segments,
                blocks = original.blocks + if (segmented) listOf(MessageBlock.Text("partial", "old partial text")) else emptyList())
        }.second
        val (user, answer) = turn(1, listOf(MessageBlock.Text("normal", "normal later answer")), RunStatus.COMPLETED)
        ledger.transaction { it.add(LedgerEntry(id.value, call.id.value, old.runId.value,
            "original-request", "protected-unknown-effect-${id.value}", "original-owner", code = "lost_receipt")) }
        db.draft(id, "unsent composer")
        return Seed(id, old, user, answer)
    }
    // Fixture-only replacement models an imported/corrected legacy row, not a runner save.
    private fun replaceHistorical(row: Message) {
        assertEquals(1, db.writableDatabase.update("messages", ContextService.messageValues(row),
            "id=?", arrayOf(row.id.value)))
    }
    private fun runHistory(history: List<Message>, id: ConversationId): List<Message> {
        var opened: List<Message>? = null
        val next = Message(MessageId(UUID.randomUUID().toString()), id, RunId(UUID.randomUUID().toString()),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val runner = ChatRunner(ModelPort { supplied ->
            opened = supplied
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    assertTrue(outputs.isEmpty())
                    sink(ModelEvent.TextFinal("answer", "done"))
                }
                override fun cancel() {}
            }
        }, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { error("must not replay historical tools") }) {}
        assertEquals(RunStatus.COMPLETED, runner.run(next, history).status)
        return checkNotNull(opened)
    }
    private fun assertWirePairs(history: List<Message>) {
        val response = ResponsesRequest.history(history)
        fun count(rows: JSONArray, predicate: (org.json.JSONObject) -> Boolean): Int =
            (0 until rows.length()).count { predicate(rows.getJSONObject(it)) }
        assertEquals(1, count(response) { it.optString("type") == "function_call" && it.optString("call_id") == call.id.value })
        val responseCall = (0 until response.length()).map(response::getJSONObject)
            .single { it.optString("type") == "function_call" && it.optString("call_id") == call.id.value }
        assertEquals(call.arguments, responseCall.getString("arguments"))
        assertEquals(1, count(response) { it.optString("type") == "function_call_output" &&
            it.optString("call_id") == call.id.value && it.getString("output") == ModelHistory.UNKNOWN_TOOL_RESULT })
        val messages = MessagesRequest.history(history)
        val content = JSONArray()
        for (i in 0 until messages.length()) {
            val blocks = messages.getJSONObject(i).getJSONArray("content")
            for (j in 0 until blocks.length()) content.put(blocks.getJSONObject(j))
        }
        assertEquals(1, count(content) { it.optString("type") == "tool_use" && it.optString("id") == call.id.value })
        val messageCall = (0 until content.length()).map(content::getJSONObject)
            .single { it.optString("type") == "tool_use" && it.optString("id") == call.id.value }
        assertEquals(JSONObject(call.arguments).toString(), messageCall.getJSONObject("input").toString())
        assertEquals(prompt, messageCall.getJSONObject("input").getJSONArray("tasks").getJSONObject(0).getString("prompt"))
        assertEquals(1, count(content) { it.optString("type") == "tool_result" &&
            it.optString("tool_use_id") == call.id.value && it.getString("content") == ModelHistory.UNKNOWN_TOOL_RESULT })
        val native = NativeCompletionRequest.history(ProviderProtocol.CHAT_COMPLETIONS, history)
        val nativeCalls = (0 until native.length()).map(native::getJSONObject)
            .mapNotNull { it.optJSONArray("tool_calls") }
            .flatMap { calls -> (0 until calls.length()).map(calls::getJSONObject) }
        assertEquals(1, nativeCalls.count { it.getString("id") == call.id.value })
        assertEquals(JSONObject(call.arguments).toString(), nativeCalls.single { it.getString("id") == call.id.value }
            .getJSONObject("function").getString("arguments"))
        assertEquals(1, count(native) { it.optString("role") == "tool" &&
            it.optString("tool_call_id") == call.id.value && it.getString("content") == ModelHistory.UNKNOWN_TOOL_RESULT })
        val gemini = NativeCompletionRequest.history(ProviderProtocol.GEMINI, history)
        val geminiParts = (0 until gemini.length()).flatMap { row ->
            val parts = gemini.getJSONObject(row).getJSONArray("parts")
            (0 until parts.length()).map { parts.getJSONObject(it) }
        }
        val geminiCalls = geminiParts.mapNotNull { it.optJSONObject("functionCall") }
        val geminiResults = geminiParts.mapNotNull { it.optJSONObject("functionResponse") }
        assertEquals(1, geminiCalls.size)
        assertEquals(1, geminiResults.size)
        val geminiCall = geminiCalls.single()
        val geminiResult = geminiResults.single()
        assertEquals(call.id.value, geminiCall.getString("id"))
        assertEquals(call.id.value, geminiResult.getString("id"))
        assertEquals(call.name, geminiCall.getString("name"))
        assertEquals(call.name, geminiResult.getString("name"))
        assertEquals(JSONObject(call.arguments).toString(), geminiCall.getJSONObject("args").toString())
        assertEquals(prompt, geminiCall.getJSONObject("args").getJSONArray("tasks").getJSONObject(0).getString("prompt"))
        assertEquals(ModelHistory.UNKNOWN_TOOL_RESULT, geminiResult.getJSONObject("response").getString("output"))
    }

    @Test fun oldUnknownThenNormalRoundCanRegenerateEditAndSendWithIdenticalUnknownSemantics() {
        for (segmented in listOf(false, true)) {
            val seed = seed(segmented)
            val before = context.displayHistory(seed.id)
            val revision = ContextRevision.digest(before)
            val ledgerBefore = ledger.transaction { it.toList() }
            val targets = actions.snapshot(seed.id).targets
            val regenerate = actions.prepareRegeneration(targets.getValue(seed.answer.id.value))
            val edit = actions.prepareRegeneration(targets.getValue(seed.user.id.value), "edited later question")
            assertEquals(regenerate.prefix, edit.prefix)
            assertEquals(before, regenerate.original)
            val regenerationContext = context.prepareActionSend(seed.id, regenerate.prefix,
                CompressionPolicy(), listOf(regenerate.user)).messages
            val editContext = context.prepareActionSend(seed.id, edit.prefix,
                CompressionPolicy(), listOf(edit.user)).messages
            val ordinaryContext = context.prepareSend(seed.id).messages
            val regenerated = runHistory(regenerationContext, seed.id)
            val edited = runHistory(editContext, seed.id)
            val ordinary = runHistory(ordinaryContext, seed.id)
            assertEquals(regenerated, edited)
            assertEquals(regenerated, ordinary.take(regenerated.size))
            listOf(regenerated, edited, ordinary).forEach(::assertWirePairs)
            assertEquals(before, context.displayHistory(seed.id))
            assertEquals(revision, ContextRevision.digest(context.displayHistory(seed.id)))
            assertNull(context.displayHistory(seed.id).single { it.id == seed.old.id }
                .blocks.filterIsInstance<MessageBlock.Tool>().single().result)
            assertEquals(ledgerBefore, ledger.transaction { it.toList() })
            assertTrue(ledgerBefore.all { it.state == LedgerState.UNKNOWN && it.payload.isEmpty() })
            assertEquals("unsent composer", db.conversations().single { it.id == seed.id }.draft)
            assertNotNull(runCatching { actions.rejectRetry(actions.snapshot(seed.id).steps.getValue(call.id.value)) }.exceptionOrNull())
        }
    }

    @Test fun activePendingStillBlocksActionsAndIsNotProjected() {
        val seed = seed()
        replaceHistorical(seed.old.copy(status = RunStatus.RUNNING))
        val before = context.displayHistory(seed.id)
        val targets = actions.snapshot(seed.id).targets
        assertEquals(before, ModelHistory.project(before))
        assertNotNull(runCatching { ModelHistory.requireResolvedTools(before) }.exceptionOrNull())
        assertNotNull(runCatching { actions.prepareRegeneration(targets.getValue(seed.answer.id.value)) }.exceptionOrNull())
        assertNotNull(runCatching { actions.prepareRegeneration(targets.getValue(seed.user.id.value), "edit") }.exceptionOrNull())
        assertEquals(before, context.displayHistory(seed.id))
    }

    @Test fun originalRevisionCASStillRejectsStalePreparationAndCommit() {
        val seed = seed(true)
        val target = actions.snapshot(seed.id).targets.getValue(seed.answer.id.value)
        val prepared = actions.prepareRegeneration(target)
        replaceHistorical(seed.old.copy(error = "revised unknown evidence; still unknown"))
        val changed = context.displayHistory(seed.id)
        assertNotNull(runCatching { actions.prepareRegeneration(target) }.exceptionOrNull())
        assertNotNull(runCatching { DatabaseTransactions { db.writableDatabase }.run {
            actions.commitRevision(it, prepared)
        } }.exceptionOrNull())
        assertEquals(changed, context.displayHistory(seed.id))
        assertEquals(LedgerState.UNKNOWN, ledger.transaction { it.single().state })
    }

    @Test fun visionAuthorizationAcceptsOnlyTheExactDeterministicUnknownView() {
        val seed = seed(true)
        val original = context.displayHistory(seed.id)
        val authorization = ControllerVisionAuthorization(db, seed.answer, emptyList()) { true }
        val projected = ModelHistory.project(original)
        assertTrue(authorization.authorize(projected).isEmpty())
        val forged = projected.map { if (it.id == seed.old.id) it.copy(blocks =
            listOf(MessageBlock.Tool(call, "pretend-success"))) else it }
        assertNotNull(runCatching { authorization.authorize(forged) }.exceptionOrNull())
        assertNotNull(runCatching { authorization.authorize(MessageHistory.expand(projected)) }.exceptionOrNull())
        assertEquals(original, context.displayHistory(seed.id))
    }

    @Test fun acceptedEditRetainsOriginalUnknownAndLedgerRatherThanPersistingProjection() {
        val seed = seed(true)
        val edit = actions.prepareRegeneration(actions.snapshot(seed.id).targets.getValue(seed.user.id.value), "edited")
        val rows = ledger.transaction { it.toList() }
        val user = edit.user.copy(runId = RunId(UUID.randomUUID().toString()))
        val answer = user.copy(id = MessageId(UUID.randomUUID().toString()), role = Message.ASSISTANT_ROLE,
            blocks = emptyList(), status = RunStatus.RUNNING)
        DatabaseTransactions { db.writableDatabase }.run { transaction ->
            actions.commitRevision(transaction, edit)
            db.send(user, answer, preserveDraft = true)
        }
        db.save(answer.copy(status = RunStatus.COMPLETED))
        assertEquals(seed.old, context.displayHistory(seed.id).single { it.id == seed.old.id })
        assertEquals(rows, ledger.transaction { it.toList() })
        assertWirePairs(runHistory(context.prepareSend(seed.id).messages, seed.id))
    }
}
