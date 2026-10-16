package app.daiyu.android.model.generation

import org.json.JSONObject
import java.util.Base64

/** Explicit Images API only. No chat, provider guessing, prompt-option parsing or fallback POST. */
class ImageGenerationClient(private val io: GenerationIo) {
    fun prepare(request: GenerationRequest): GenerationRequestPlan {
        if (!accepts(request.protocol)) throw GenerationPreflightException(GenerationFailure.INVALID_REQUEST)
        return GenerationRequestPlan.prepare(request, io.assets)
    }

    fun submit(request: GenerationRequest): Submission = try {
        submit(prepare(request))
    } catch (failure: GenerationPreflightException) { Submission.NotExecuted(failure.failure) }

    fun submit(plan: GenerationRequestPlan): Submission {
        if (!accepts(plan.protocol)) return Submission.NotExecuted(GenerationFailure.INVALID_REQUEST)
        return io.submit(plan, ::parse)
    }

    /** All children are preflighted before the first POST; execution is deliberately sequential. */
    fun submitBatch(requests: List<GenerationRequest>): List<GenerationBatchItem> =
        io.batch(requests, ::accepts, ::submit)

    private fun accepts(protocol: GenerationProtocol): Boolean = protocol == GenerationProtocol.OPENAI_IMAGES_JSON ||
        protocol == GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART

    private fun parse(response: GenerationHttpResponse): Submission {
        val root = JSONObject(response.bytes.toString(Charsets.UTF_8))
        if (root.has("error")) return Submission.Unknown(GenerationFailure.REMOTE_FAILED)
        val data = root.getJSONArray("data")
        if (data.length() !in 1..GenerationRequestPlan.MAX_BATCH) return Submission.Unknown(GenerationFailure.PROTOCOL)
        val outputs = (0 until data.length()).map { index ->
            try {
                val item = data.getJSONObject(index)
                val hasInline = item.has("b64_json") && !item.isNull("b64_json")
                val hasUrl = item.has("url") && !item.isNull("url")
                require(hasInline != hasUrl)
                val source = if (hasInline) {
                    val encoded = item.get("b64_json") as String
                    require(encoded.isNotEmpty() && encoded.length <= GenerationRequestPlan.MAX_JSON_BYTES)
                    val bytes = Base64.getDecoder().decode(encoded)
                    require(bytes.isNotEmpty() && bytes.size <= GenerationRequestPlan.MAX_IMAGE_BYTES)
                    GenerationSource.Inline(bytes, "image/png")
                } else {
                    val url = item.get("url") as String
                    GenerationRequestPlan.checkedDownload(url)
                    GenerationSource.Remote(url)
                }
                GenerationOutput(index, source)
            } catch (_: Exception) { GenerationOutput(index, failure = GenerationFailure.PROTOCOL) }
        }
        return if (outputs.any { it.source != null }) Submission.Completed(outputs)
        else Submission.Unknown(GenerationFailure.PROTOCOL)
    }
}
