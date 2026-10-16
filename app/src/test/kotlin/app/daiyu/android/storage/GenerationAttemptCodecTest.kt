package app.daiyu.android.storage

import app.daiyu.android.model.generation.*
import org.junit.Assert.*
import org.junit.Test

class GenerationAttemptCodecTest {
    private val identity = GenerationIdentity("operation", "item")
    private val handle = GenerationHandle(identity, "fingerprint", "task",
        "https://api.example/videos/task", "https://api.example/videos/task/content")
    private fun rejects(work: () -> Unit) {
        try { work() } catch (_: Exception) { return }
        fail("expected rejection")
    }

    @Test fun binaryRoundTripPreservesEveryOutcomeAndSourceFlag() {
        val values = listOf<Submission>(
            Submission.Completed(listOf(
                GenerationOutput(0, GenerationSource.Inline(byteArrayOf(0, 1, -1), "image/png")),
                GenerationOutput(1, GenerationSource.Remote("https://cdn.example/?secret=signed", true)),
                GenerationOutput(2, failure = GenerationFailure.INVALID_MEDIA))),
            Submission.Pending(handle),
            Submission.Unknown(GenerationFailure.HTTP, 503, handle),
            Submission.Unknown(GenerationFailure.TRANSPORT),
            Submission.NotExecuted(GenerationFailure.CANCELLED))
        values.forEach { value ->
            val wire = GenerationAttemptCodec.encode(value)
            assertArrayEquals(wire, GenerationAttemptCodec.encode(GenerationAttemptCodec.decode(wire)))
        }
        val result = GenerationAttemptCodec.decode(GenerationAttemptCodec.encode(values.first())) as Submission.Completed
        assertArrayEquals(byteArrayOf(0, 1, -1), (result.outputs[0].source as GenerationSource.Inline).bytes)
        assertTrue((result.outputs[1].source as GenerationSource.Remote).authenticated)
        assertEquals(GenerationFailure.INVALID_MEDIA, result.outputs[2].failure)
    }

    @Test fun completedIsAbsorbingAndSparseMergesNeverEraseKnownIndices() {
        val first = Submission.Completed(listOf(
            GenerationOutput(0, GenerationSource.Remote("https://cdn.example/original")),
            GenerationOutput(2, failure = GenerationFailure.INVALID_MEDIA)))
        val second = Submission.Completed(listOf(
            GenerationOutput(0, GenerationSource.Remote("https://cdn.example/changed")),
            GenerationOutput(1, failure = GenerationFailure.PROTOCOL),
            GenerationOutput(2, GenerationSource.Inline(byteArrayOf(7), "image/png"))))
        val merged = GenerationAttemptCodec.merge(first, second) as Submission.Completed
        assertEquals(listOf(0, 1, 2), merged.outputs.map { it.index })
        assertEquals("https://cdn.example/original", (merged.outputs[0].source as GenerationSource.Remote).url)
        assertArrayEquals(byteArrayOf(7), (merged.outputs[2].source as GenerationSource.Inline).bytes)
        listOf(Submission.Pending(handle), Submission.Unknown(GenerationFailure.REMOTE_FAILED, handle = handle),
            Submission.NotExecuted(GenerationFailure.CANCELLED), Submission.Completed(emptyList())).forEach {
            assertArrayEquals(GenerationAttemptCodec.encode(merged),
                GenerationAttemptCodec.encode(GenerationAttemptCodec.merge(merged, it)))
        }
    }

    @Test fun pendingAndTerminalFailureDoNotLoseTheirHandle() {
        val pending = Submission.Pending(handle)
        assertEquals(pending, GenerationAttemptCodec.merge(pending, Submission.Unknown(GenerationFailure.TRANSPORT)))
        val failed = GenerationAttemptCodec.merge(pending, Submission.Unknown(GenerationFailure.REMOTE_FAILED))
        assertEquals(Submission.Unknown(GenerationFailure.REMOTE_FAILED, handle = handle), failed)
        assertEquals(failed, GenerationAttemptCodec.merge(failed, pending))
        rejects { GenerationAttemptCodec.merge(pending, Submission.Pending(handle.copy(taskId = "other"))) }
        val attempt = GenerationAttempt(identity, "fingerprint", GenerationProtocol.OPENAI_VIDEO_MULTIPART)
        GenerationAttemptCodec.validateHandle(attempt, pending)
        rejects { GenerationAttemptCodec.validateHandle(attempt.copy(fingerprint = "foreign"), pending) }
        rejects { GenerationAttemptCodec.validateHandle(attempt.copy(protocol = GenerationProtocol.OPENAI_IMAGES_JSON), pending) }
    }

    @Test fun rejectsTruncationTrailingBytesVersionsDuplicatesAndExcessivePayloads() {
        val bytes = GenerationAttemptCodec.encode(Submission.Pending(handle))
        rejects { GenerationAttemptCodec.decode(bytes.copyOf(bytes.size - 1)) }
        rejects { GenerationAttemptCodec.decode(bytes + byteArrayOf(1)) }
        rejects { GenerationAttemptCodec.decode(bytes.copyOf().also { it[3] = 2 }) }
        rejects { GenerationAttemptCodec.encode(Submission.Completed(listOf(
            GenerationOutput(0, failure = GenerationFailure.PROTOCOL), GenerationOutput(0, failure = GenerationFailure.HTTP)))) }
        rejects { GenerationAttemptCodec.encode(Submission.Completed(listOf(GenerationOutput(-1, failure = GenerationFailure.PROTOCOL)))) }
        rejects { GenerationAttemptCodec.encode(Submission.Completed(listOf(GenerationOutput(0)))) }
        rejects { GenerationAttemptCodec.encode(Submission.Completed(listOf(
            GenerationOutput(0, GenerationSource.Remote("x".repeat(16385)))))) }
        rejects { GenerationAttemptCodec.encode(Submission.Completed(listOf(
            GenerationOutput(0, GenerationSource.Inline(ByteArray(20 * 1024 * 1024 + 1), "image/png"))))) }
        // Each sibling is legal but the aggregate encoding must still be bounded.
        val medium = GenerationSource.Inline(ByteArray(17 * 1024 * 1024), "image/png")
        rejects { GenerationAttemptCodec.encode(Submission.Completed(listOf(
            GenerationOutput(0, medium), GenerationOutput(1, medium)))) }
    }
}
