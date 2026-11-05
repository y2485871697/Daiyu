package app.daiyu.android.storage

import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
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
        val data = ByteArray(1_280_000)
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
            assertEquals(first, store.readCommittedCheckpoint(first))
            val action = ToolInvocation(ToolCallId("action"), "screen_action", "{}")
            val nextBlocks = blocks + MessageBlock.Tool(action)
            val next = first.copy(revision = 2, phase = ExecutionPhase.TOOL_EXECUTING,
                tools = first.tools + ToolRecord(action, ToolDisposition.IN_FLIGHT), message = message.copy(blocks = nextBlocks,
                    historySegments = listOf(MessageHistorySegment(message.id, "assistant", nextBlocks, items))))
            store.commitCheckpoint(next)
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
}
