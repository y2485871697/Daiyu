package app.daiyu.android.model.generation

import org.json.JSONObject

/**
 * Explicit /videos multipart resource only; one reference at most, never silently take first().
 * Submit returns a handle without an implicit polling loop. poll performs exactly one GET.
 */
class VideoGenerationClient(private val io: GenerationIo) {
    fun prepare(request: GenerationRequest): GenerationRequestPlan {
        if (request.protocol != GenerationProtocol.OPENAI_VIDEO_MULTIPART)
            throw GenerationPreflightException(GenerationFailure.INVALID_REQUEST)
        return GenerationRequestPlan.prepare(request, io.assets)
    }

    fun submit(request: GenerationRequest): Submission = try {
        submit(prepare(request))
    } catch (failure: GenerationPreflightException) { Submission.NotExecuted(failure.failure) }

    fun submit(plan: GenerationRequestPlan): Submission {
        if (plan.protocol != GenerationProtocol.OPENAI_VIDEO_MULTIPART)
            return Submission.NotExecuted(GenerationFailure.INVALID_REQUEST)
        return io.submit(plan) { parse(plan, it) }
    }

    fun submitBatch(requests: List<GenerationRequest>): List<GenerationBatchItem> =
        io.batch(requests, { it == GenerationProtocol.OPENAI_VIDEO_MULTIPART }, ::submit)

    fun poll(plan: GenerationRequestPlan, handle: GenerationHandle): Submission =
        io.poll(plan, handle) { parse(plan, it, handle.taskId) }

    private fun parse(plan: GenerationRequestPlan, response: GenerationHttpResponse, expectedId: String? = null): Submission {
        val root = JSONObject(response.bytes.toString(Charsets.UTF_8))
        if (root.has("error")) return Submission.Unknown(GenerationFailure.REMOTE_FAILED)
        val id = root.get("id") as String
        require(GenerationIo.validTaskId(id) && (expectedId == null || expectedId == id))
        val handle = GenerationHandle(plan.identity, plan.fingerprint, id, plan.endpoint + "/" + id,
            plan.endpoint + "/" + id + "/content")
        return when (root.get("status") as String) {
            "queued", "in_progress" -> Submission.Pending(handle)
            "completed" -> Submission.Completed(listOf(GenerationOutput(0,
                GenerationSource.Remote(handle.contentUrl, authenticated = true))))
            "failed" -> Submission.Unknown(GenerationFailure.REMOTE_FAILED, handle = handle)
            else -> Submission.Unknown(GenerationFailure.PROTOCOL, handle = handle)
        }
    }
}
