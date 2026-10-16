package app.daiyu.android.model.generation

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GenerationContractsTest {
    @Test fun inputsRequestsAndWireBodiesAreSnapshots() {
        val bytes = byteArrayOf(1, 2, 3)
        val input = GenerationInput(bytes, "image/png")
        bytes[0] = 9
        input.bytes[1] = 8
        assertArrayEquals(byteArrayOf(1, 2, 3), input.bytes)
        val refs = mutableListOf(input)
        val request = generationRequest(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART, refs = refs)
        refs.clear()
        val plan = GenerationRequestPlan.prepare(request, TestGenerationAssets())
        val before = plan.body
        plan.body.fill(0)
        assertArrayEquals(before, plan.body)
        val wire = GenerationHttpRequest("POST", plan.endpoint, mapOf("x" to "y"), before, 100)
        wire.body!!.fill(0)
        assertArrayEquals(before, wire.body)
    }

    @Test fun sensitivePayloadsAreNotInDebugStrings() {
        val request = generationRequest()
        val plan = GenerationRequestPlan.prepare(request, TestGenerationAssets())
        val wire = GenerationHttpRequest("POST", plan.endpoint, mapOf("Authorization" to "secret-key"), plan.body, 100)
        for (text in listOf(request.toString(), plan.toString(), wire.toString(),
            GenerationSource.Remote("https://cdn.example/a?token=secret-key").toString(),
            GenerationHttpResponse(200, "private-prompt".toByteArray()).toString())) {
            assertFalse(text.contains("secret-key"))
            assertFalse(text.contains("private-prompt"))
        }
    }
}

internal fun generationRequest(
    item: String = "item1",
    protocol: GenerationProtocol = GenerationProtocol.OPENAI_IMAGES_JSON,
    endpoint: String = when (protocol) {
        GenerationProtocol.OPENAI_IMAGES_JSON -> "https://api.example/v1/images/generations"
        GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> "https://api.example/v1/images/edits"
        GenerationProtocol.OPENAI_VIDEO_MULTIPART -> "https://api.example/v1/videos"
    },
    refs: List<GenerationInput> = emptyList(),
    mask: GenerationInput? = null,
    parameters: Map<String, String> = emptyMap(),
    prompt: String = "private-prompt",
): GenerationRequest = GenerationRequest(GenerationIdentity("operation1", item), protocol, endpoint,
    "secret-key", "configured-model", prompt, refs, mask, parameters)

internal class TestGenerationAssets : GenerationAssets {
    val commits = mutableListOf<GenerationIdentity>()
    var inspections = 0
    var reject = false
    override fun inspect(kind: GenerationKind, bytes: ByteArray, declaredMime: String): GenerationMediaInfo {
        inspections++
        if (reject) throw IOException("sensitive validation detail")
        return GenerationMediaInfo(if (kind == GenerationKind.VIDEO) "video/mp4" else declaredMime.ifEmpty { "image/png" },
            16, if (bytes.firstOrNull() == 99.toByte()) 32 else 16)
    }
    override fun commit(identity: GenerationIdentity, outputIndex: Int, kind: GenerationKind,
        bytes: ByteArray, info: GenerationMediaInfo): GenerationAssetReceipt {
        commits += identity
        return GenerationAssetReceipt("asset-$outputIndex")
    }
}

/** Only a test fixture. Production MUST inject a durable atomic store; there is no default. */
internal class TestAttemptStore : GenerationAttemptStore {
    val rows = mutableMapOf<GenerationIdentity, GenerationAttempt>()
    val results = mutableMapOf<GenerationIdentity, Submission>()
    var failReserve = false
    var failFinish = false
    var reserveCalls = 0
    @Synchronized override fun reserve(attempt: GenerationAttempt): GenerationReservation {
        reserveCalls++
        if (failReserve) throw IOException("sensitive store error")
        val previous = rows[attempt.identity]
        if (previous != null) return GenerationReservation.Existing(previous.fingerprint, results[attempt.identity])
        rows[attempt.identity] = attempt
        return GenerationReservation.Started
    }
    @Synchronized override fun finish(attempt: GenerationAttempt, submission: Submission) {
        if (failFinish) throw IOException("write failed")
        check(rows[attempt.identity]?.fingerprint == attempt.fingerprint)
        if (results[attempt.identity] is Submission.Completed) return
        results[attempt.identity] = submission
    }
}

internal class TestGenerationHttp : GenerationHttp {
    val requests = mutableListOf<GenerationHttpRequest>()
    var respond: (GenerationHttpRequest) -> GenerationHttpResponse = { jsonResponse("{\"data\":[{\"b64_json\":\"AQID\"}]}") }
    override fun execute(request: GenerationHttpRequest): GenerationHttpResponse {
        requests += request
        return respond(request)
    }
}

internal fun jsonResponse(json: String, code: Int = 200) =
    GenerationHttpResponse(code, json.toByteArray(Charsets.UTF_8), "application/json")

internal class GenerationFixture(
    val store: TestAttemptStore = TestAttemptStore(),
    val http: TestGenerationHttp = TestGenerationHttp(),
    val assets: TestGenerationAssets = TestGenerationAssets(),
    allowed: (String) -> Boolean = { true },
    cancelled: () -> Boolean = { false },
) {
    val io = GenerationIo(assets, store, allowed, http, cancelled)
    val images = ImageGenerationClient(io)
    val videos = VideoGenerationClient(io)
}
