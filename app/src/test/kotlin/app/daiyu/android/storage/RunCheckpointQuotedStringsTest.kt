package app.daiyu.android.storage

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

open class RunCheckpointQuotedStringsTest {
    private val call = ToolInvocation(ToolCallId("screen-call"), "screen", "{\"screen\":true}")
    private val result = ("screen 中文 \"\\/\n\u0000".repeat(2500)).take(30_000)
    private fun fresh(text: String) = String(text.toCharArray())
    private fun checkpoint(output: String = result, text: String = "stream"): RunCheckpoint {
        val message = Message(MessageId("assistant"), ConversationId("c"), RunId("r"), "assistant",
            listOf(MessageBlock.Tool(call, fresh(output)), MessageBlock.Text("text", text)), RunStatus.RUNNING)
        val prior = message.copy(id = MessageId("prior"), status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Tool(call, fresh(output))))
        return RunCheckpoint(message, listOf(prior), ExecutionPhase.STREAMING,
            listOf(ToolRecord(call, ToolDisposition.SUCCEEDED, fresh(output))),
            listOf(ToolOutput(call.id, fresh(output))), owner = "owner", revision = 1)
    }

    private fun assertEquivalent(value: RunCheckpoint, writer: CheckpointJsonWriter): String {
        val legacy = RunCheckpointCodec.encode(value)
        val actual = RunCheckpointCodec.encode(value, writer)
        assertEquals(legacy, actual)
        assertArrayEquals(legacy.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
        assertEquals(RunCheckpointCodec.decode(legacy), RunCheckpointCodec.decode(actual))
        return actual
    }

    @Test fun realCodecQuotesFourCopiesOnceAcrossOneHundredStreamingCheckpoints() {
        val writer = CheckpointJsonWriter()
        repeat(100) { tick ->
            val value = checkpoint(text = "new streamed text " + "x".repeat(tick)).copy(revision = tick + 1L)
            val wire = assertEquivalent(value, writer)
            if (tick == 0 || tick == 99) assertEquals(value, RunCheckpointCodec.decode(wire))
        }
        assertEquals(1L, writer.largeStringQuoteCalls)
        assertEquals(1, writer.cachedEntryCount)
        assertEquals(result.length.toLong() + JSONObject.quote(result).length, writer.retainedChars)
        // Same call ID is not a cache key: changed content must be re-quoted and emitted.
        val changed = checkpoint(output = "changed " + result)
        assertEquals(changed, RunCheckpointCodec.decode(assertEquivalent(changed, writer)))
        assertEquals(2L, writer.largeStringQuoteCalls)
        assertEquivalent(checkpoint(), writer)
        assertEquals(2L, writer.largeStringQuoteCalls)
    }

    @Test fun realCodecColdHitEvictedDisabledAndOversizedPathsAreIdentical() {
        val value = checkpoint()
        for (writer in listOf(CheckpointJsonWriter(), CheckpointJsonWriter(maxEntries = 0),
            CheckpointJsonWriter(maxRetainedChars = 0), CheckpointJsonWriter(maxRetainedChars = 1000),
            CheckpointJsonWriter(maxEntries = 1))) {
            repeat(2) { assertEquivalent(value, writer) }
            assertEquivalent(checkpoint(output = "other " + result), writer)
            assertEquivalent(value, writer)
            writer.clear()
            assertEquals(0L, writer.retainedChars)
            assertEquals(0, writer.cachedEntryCount)
            assertEquivalent(value, writer)
        }
    }

    @Test fun versionsContextDuplicatePhysicalIdsTransportAndReasoningRemainIdentical() {
        val base = checkpoint()
        val request = RequestCheckpoint("request", "attempt", base.owner, RequestOperation.RESUME)
        val summary = RequestCheckpoint("summary", "summary-attempt", base.owner, RequestOperation.NEXT)
        val transport = TransportCheckpoint("resp_test", 3,
            JSONObject().put("response_id", "resp_test").put("sequence", 3).put("text", "中\u0000\\/\u2028\u2029").toString())
        val resumed = base.copy(request = request, usageRequests = listOf(request), transport = transport)
        val usage = resumed.copy(usage = listOf(RequestUsageCheckpoint(request, ModelEvent.Usage(10, 5, 2, 1))))
        val reasoning = ReportedReasoning("reasoning", "reported 中\u0000", blockOffset = 0)
        val first = base.message.copy(historySegments = emptyList(), reportedReasoning = listOf(reasoning))
        val second = first.copy(blocks = listOf(MessageBlock.Text("later", "later text")))
        val context = usage.copy(history = listOf(first, second), context = RunContextCheckpoint(
            input = RequestInputCheckpoint(request, 12, historyTokens = 10),
            calibration = RequestInputCheckpoint(request, 12, 11, 10), pressure = request,
            summaries = listOf(summary), compactions = 1),
            usage = usage.usage + RequestUsageCheckpoint(summary, ModelEvent.Usage(7, 3), UsageRequestSource.SUMMARY))
        val segments = listOf(
            MessageHistorySegment(base.message.id, "assistant", base.message.blocks),
            MessageHistorySegment(base.message.id, "assistant", listOf(MessageBlock.Text("later", "later text"))))
        val capacity = context.copy(message = base.message.copy(historySegments = segments),
            context = context.context!!.copy(capacity = ContextCapacityCheckpoint("capacity", 1, 120, 100, 80, 1)))
        val writer = CheckpointJsonWriter()
        for ((version, value) in listOf(6 to base, 7 to resumed, 8 to usage, 9 to context, 10 to capacity)) {
            repeat(2) {
                val wire = assertEquivalent(value, writer)
                assertEquals(version, JSONObject(wire).getInt("version"))
                assertEquals(value, RunCheckpointCodec.decode(wire))
            }
        }
        assertEquals(1L, writer.largeStringQuoteCalls)
    }

    @Test fun archiveSurrogateNormalizationStillPrecedesTheWriter() {
        val malformed = "\ud800\"\\/\u0000\u2028\u2029中\udfff".repeat(500)
        val base = checkpoint(output = malformed, text = "Unicode 🙂\u0000\u0001\b\t\n\r\u001f\"\\/\u2028\u2029")
        val value = base.copy(message = base.message.copy(historySegments = listOf(
            MessageHistorySegment(base.message.id, "assistant", base.message.blocks, listOf(malformed)))))
        val writer = CheckpointJsonWriter()
        repeat(2) { assertEquivalent(value, writer) }
        // Compare with the legacy normalized envelope, not with the raw domain strings.
        val legacy = JSONObject(RunCheckpointCodec.encode(value))
        val cached = JSONObject(RunCheckpointCodec.encode(value, writer))
        assertEquals(legacy.getJSONArray("message").toString(), cached.getJSONArray("message").toString())
        assertEquals(legacy.getJSONArray("history").toString(), cached.getJSONArray("history").toString())
    }

    @Test fun warmedCacheNeverServesAsValidationOrSuccessEvidence() {
        val writer = CheckpointJsonWriter()
        val base = checkpoint()
        assertEquivalent(base, writer)
        val invalid = listOf(base.copy(owner = ""), base.copy(revision = 0), base.copy(messageReconciled = false),
            base.copy(phase = ExecutionPhase.TERMINAL),
            base.copy(tools = listOf(ToolRecord(call, ToolDisposition.UNKNOWN, result))),
            base.copy(outputs = listOf(ToolOutput(call.id, "wrong"))),
            base.copy(history = base.history + base.history),
            base.copy(history = listOf(base.history.single().copy(conversationId = ConversationId("foreign")))))
        for (value in invalid) {
            reject { RunCheckpointCodec.encode(value) }
            reject { RunCheckpointCodec.encode(value, writer) }
        }
        // CheckpointPreservation does not freeze these lists. Even the same object
        // graph must be rebuilt and revalidated after its caller changes a collection.
        val rows = base.history.toMutableList()
        val mutable = base.copy(history = rows)
        assertEquivalent(mutable, writer)
        rows.add(rows.single())
        reject { RunCheckpointCodec.encode(mutable, writer) }
        rows.removeAt(1)
        assertEquivalent(mutable, writer)
        for (disposition in listOf(ToolDisposition.SUCCEEDED, ToolDisposition.FAILED, ToolDisposition.NOT_EXECUTED)) {
            val confirmed = base.copy(tools = listOf(ToolRecord(call, disposition, result)))
            assertEquals(disposition, RunCheckpointCodec.decode(assertEquivalent(confirmed, writer)).tools.single().disposition)
        }
        assertEquals(1L, writer.largeStringQuoteCalls)
    }

    @Test fun encodedByteLimitAndRunningRecoveryReserveRemainUnchanged() {
        val max = RunCheckpointCodec.MAX_BYTES
        val reserve = RunCheckpointCodec.RECOVERY_RESERVE
        RunCheckpointCodec.checkEncodedSize(max - reserve, true)
        reject { RunCheckpointCodec.checkEncodedSize(max - reserve + 1, true) }
        RunCheckpointCodec.checkEncodedSize(max, false)
        reject { RunCheckpointCodec.checkEncodedSize(max + 1, false) }
        val writer = CheckpointJsonWriter()
        val before = checkpoint().copy(owner = "x", revision = 9)
        val after = ProcessRecovery.interrupt(before).copy(owner = "recovery-owner", revision = 10, messageReconciled = true)
        val beforeBytes = assertEquivalent(before, writer).toByteArray(Charsets.UTF_8).size
        val afterBytes = assertEquivalent(after, writer).toByteArray(Charsets.UTF_8).size
        assertTrue(afterBytes - beforeBytes in 1 until reserve)
        assertEquals(RunStatus.INTERRUPTED, RunCheckpointCodec.decode(RunCheckpointCodec.encode(after, writer)).message.status)
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("invalid checkpoint accepted") } catch (_: Exception) { }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class AndroidRunCheckpointQuotedStringsTest : RunCheckpointQuotedStringsTest()
