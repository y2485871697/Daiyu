package app.daiyu.android.model.generation

import org.json.JSONObject

class ImageGenerationClient(private val io: GenerationIo) {
    fun prepare(request: GenerationRequest): GenerationRequestPlan {
        if (request.protocol.isVideo) throw GenerationPreflightException(GenerationFailure.INVALID_REQUEST)
        return GenerationRequestPlan.prepare(request, io.assets)
    }
    fun submit(request: GenerationRequest): Submission = try { submit(prepare(request)) }
        catch (failure: GenerationPreflightException) { Submission.NotExecuted(failure.failure) }
    fun submit(plan: GenerationRequestPlan): Submission = if (plan.kind != GenerationKind.IMAGE)
        Submission.NotExecuted(GenerationFailure.INVALID_REQUEST) else io.submit(plan) { parse(plan, it) }
    fun submitBatch(requests: List<GenerationRequest>): List<GenerationBatchItem> =
        io.batch(requests, { !it.isVideo }, ::submit)

    private fun parse(plan: GenerationRequestPlan, response: GenerationHttpResponse): Submission {
        val bytes = response.bytes
        val parsed = when {
            plan.protocol == GenerationProtocol.NOVELAI_IMAGE_JSON -> NovelAiImageProtocol.parse(bytes) { io.cancellation.throwIfCancelled() }
            bytes.hasSupportedImageMagic() -> ImageGenerationParser.Parsed(listOf(ImageGenerationParser.ImageRef(bytes = bytes,
                mimeType = bytes.sniffGenerationImageMimeType())))
            else -> {
                val text = bytes.toString(Charsets.UTF_8)
                val root = runCatching { JSONObject(text) }.getOrNull()
                if (root?.has("error") == true && !root.isNull("error")) return Submission.Unknown(GenerationFailure.REMOTE_FAILED)
                ImageGenerationParser.parse(text)
            }
        }
        if (parsed.images.isEmpty() || parsed.images.size > GenerationRequestPlan.MAX_BATCH)
            return Submission.Unknown(GenerationFailure.PROTOCOL)
        val outputs = parsed.images.mapIndexed { index, image ->
            try {
                val source = if (image.bytes != null) {
                    require(image.bytes.isNotEmpty() && image.bytes.size <= GenerationRequestPlan.MAX_IMAGE_BYTES)
                    GenerationSource.Inline(image.bytes, image.bytes.sniffGenerationImageMimeType().ifEmpty { image.mimeType })
                } else {
                    val url = requireNotNull(image.url)
                    GenerationRequestPlan.checkedDownload(url)
                    GenerationSource.Remote(url)
                }
                GenerationOutput(index, source)
            } catch (_: Exception) { GenerationOutput(index, failure = GenerationFailure.PROTOCOL) }
        }
        return if (outputs.any { it.source != null }) Submission.Completed(outputs) else Submission.Unknown(GenerationFailure.PROTOCOL)
    }
}
