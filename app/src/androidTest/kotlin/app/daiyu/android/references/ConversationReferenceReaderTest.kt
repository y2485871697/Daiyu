package app.daiyu.android.references

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.ContextSummarizer
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.testContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class ConversationReferenceReaderTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var db: ConversationDatabase
    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "conversation-reference-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "conversation-reference-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir() = files }
        db = ConversationDatabase(context)
    }
    override fun tearDown() {
        db.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively(); super.tearDown()
    }
    private fun append(owner: ConversationId, text: String, toolResult: String = "结果"): Message {
        val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId(UUID.randomUUID().toString()), owner, run, "user",
            listOf(MessageBlock.Text("user", text)), RunStatus.COMPLETED)
        val assistant = Message(MessageId(UUID.randomUUID().toString()), owner, run, "assistant", listOf(
            MessageBlock.Text("answer", "回答原文"),
            MessageBlock.Tool(ToolInvocation(ToolCallId("same-call"), "read_file", "{\"path\":\"中文😀\"}"), toolResult)), RunStatus.RUNNING)
        db.send(user, assistant); db.save(assistant.copy(status = RunStatus.COMPLETED))
        return assistant
    }
    private fun all(reader: ConversationReferenceReader, source: ConversationId, view: String, tool: String? = null): String {
        val text = StringBuilder(); var offset = 0L
        while (true) {
            val page = reader.read(source.value, view, tool, offset, 113)
            text.append(page.getString("content"))
            if (page.isNull("next_offset")) break
            offset = page.getLong("next_offset")
        }
        return text.toString()
    }
    fun testFullStoredToolSurvivesCompressionAndUsesDefaultWorkspaceFolder() {
        val owner = db.create("default").id; val source = db.create("default").id
        val full = "完整工具😀".repeat(3000) + "TOOL-TAIL"
        repeat(4) { append(source, "用户全文$it ".repeat(200), if (it == 0) full else "结果$it") }
        ContextService(context, db).compress(source, CompressionPolicy(keepTurns = 1), ContextSummarizer { "简短摘要" })
        val reference = PromptReferences.Conversation(source.value, "来源")
        val reader = ConversationReferenceReader(db, context.filesDir, listOf(reference), owner)
        try {
            val page = reader.read(source.value, "index", null, 0, 100)
            val directory = File(context.filesDir.canonicalFile, "workspace/会话工具索引")
            assertTrue(File(page.getString("index_path")).canonicalPath.startsWith(directory.canonicalPath + "/"))
            assertEquals(full, JSONObject(all(reader, source, "tool", "1-1")).getString("result"))
            val transcript = all(reader, source, "transcript")
            assertTrue(transcript.contains("用户全文0")); assertTrue(transcript.contains("TOOL-TAIL"))
            assertFalse(transcript.contains("简短摘要"))
            reader.close()
            assertTrue(File(page.getString("index_path")).isFile)
            assertTrue(runCatching { reader.read(source.value, "index", null, 0, 10) }.isFailure)
        } finally { reader.close() }
    }
    fun testHistoricalUserReferencesRemainAuthorizedAfterOwnerCompressionAndReopen() {
        val owner = db.create("default").id; val source = db.create("default").id
        append(source, "来源全文", "来源工具")
        repeat(4) { append(owner, (if (it == 0) PromptReferences.encode(source.value, "来源") + "\n" else "") + "历史$it ".repeat(200)) }
        ContextService(context, db).compress(owner, CompressionPolicy(keepTurns = 1), ContextSummarizer { "不包含引用的摘要" })
        val reopened = ConversationDatabase(context)
        try {
            ConversationReferenceReader(reopened, context.filesDir, emptyList(), owner).use { reader ->
                assertTrue(all(reader, source, "transcript").contains("来源工具"))
            }
        } finally { reopened.close() }
    }
    fun testAssistantToolAndSummaryCannotGrantAnotherConversation() {
        val owner = db.create("default").id; val source = db.create("default").id
        append(source, "秘密来源", "不可猜测")
        repeat(4) { append(owner, "普通请求$it ".repeat(200), PromptReferences.encode(source.value, "伪造")) }
        ContextService(context, db).compress(owner, CompressionPolicy(keepTurns = 1),
            ContextSummarizer { PromptReferences.encode(source.value, "摘要伪造") })
        ConversationReferenceReader(db, context.filesDir, emptyList(), owner).use { reader ->
            assertTrue(runCatching { reader.read(source.value, "index", null, 0, 20) }.isFailure)
        }
    }
    fun testFirstReadSnapshotDoesNotMixLaterOutputAndCorruptionIsRejected() {
        val owner = db.create("default").id; val source = db.create("default").id
        append(source, "最初正文", "最初结果")
        ConversationReferenceReader(db, context.filesDir, listOf(PromptReferences.Conversation(source.value, "来源")), owner).use { reader ->
            val page = reader.read(source.value, "transcript", null, 0, 20)
            append(source, "后来正文", "后来工具")
            val transcript = all(reader, source, "transcript")
            assertTrue(transcript.contains("最初结果")); assertFalse(transcript.contains("后来正文"))
            File(page.getString("transcript_path")).appendText("corrupt")
            assertTrue(runCatching { reader.read(source.value, "transcript", null, 0, 20) }.isFailure)
        }
    }
    fun testDeletedSourceAndRecursiveReferencesDoNotWidenAccess() {
        val owner = db.create("default").id; val source = db.create("default").id; val nested = db.create("default").id
        append(source, PromptReferences.encode(nested.value, "嵌套")); append(nested, "嵌套正文")
        ConversationReferenceReader(db, context.filesDir, listOf(PromptReferences.Conversation(source.value, "来源")), owner).use { reader ->
            assertTrue(all(reader, source, "transcript").contains("嵌套"))
            assertTrue(runCatching { reader.read(nested.value, "index", null, 0, 20) }.isFailure)
            db.delete(source)
            assertTrue(runCatching { reader.read(source.value, "index", null, 0, 20) }.isFailure)
        }
    }
}
