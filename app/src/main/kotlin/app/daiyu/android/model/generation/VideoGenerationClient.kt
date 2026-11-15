package app.daiyu.android.model.generation

/** A frozen explicit transport, one billable POST, and read-only queries of its original task. */
class VideoGenerationClient(private val io: GenerationIo) {
    fun prepare(request: GenerationRequest): GenerationRequestPlan {
        if (!request.protocol.isVideo) throw GenerationPreflightException(GenerationFailure.INVALID_REQUEST)
        return GenerationRequestPlan.prepare(request, io.assets)
    }
    fun submit(request: GenerationRequest): Submission = try { submit(prepare(request)) }
        catch (failure: GenerationPreflightException) { Submission.NotExecuted(failure.failure) }
    fun submit(plan: GenerationRequestPlan): Submission = if (plan.kind != GenerationKind.VIDEO)
        Submission.NotExecuted(GenerationFailure.INVALID_REQUEST) else io.submit(plan) { resolveContent(plan, parse(plan, it)) }
    fun submitBatch(requests: List<GenerationRequest>): List<GenerationBatchItem> =
        io.batch(requests, { it.isVideo }, ::submit)
    fun poll(plan: GenerationRequestPlan, handle: GenerationHandle): Submission =
        io.poll(plan, handle) { resolveContent(plan, parse(plan, it, handle.taskId)) }

    private fun resolveContent(plan: GenerationRequestPlan, result: Submission): Submission {
        val output = (result as? Submission.Completed)?.outputs?.singleOrNull() ?: return result
        val source = output.source as? GenerationSource.Remote ?: return result
        if (!source.authenticated) return result
        val id = source.url.removeSuffix("/content").substringAfterLast('/')
        val urls = plan.taskUrls(id)
        val handle = GenerationHandle(plan.identity, plan.fingerprint, id, urls.first, urls.second)
        return io.content(plan, handle) { parse(plan, it, id, allowTask = false) }
    }

    private fun parse(plan: GenerationRequestPlan, response: GenerationHttpResponse, expectedId: String? = null, allowTask: Boolean = true): Submission {
        val bytes = response.bytes
        GenerationVideoCodec.sniffMime(bytes)?.let { mime ->
            return Submission.Completed(listOf(GenerationOutput(0, GenerationSource.Inline(bytes, mime))))
        }
        val parsed = VideoGenerationParser.parse(bytes.toString(Charsets.UTF_8))
        if (parsed.failed) {
            val id = parsed.taskId ?: expectedId
            val handle = id?.takeIf(GenerationIo::validTaskId)?.let {
                val urls = plan.taskUrls(it)
                GenerationHandle(plan.identity, plan.fingerprint, it, urls.first, urls.second)
            }
            return Submission.Unknown(GenerationFailure.REMOTE_FAILED, handle = handle)
        }
        if (parsed.taskId != null && expectedId != null && parsed.taskId != expectedId)
            return Submission.Unknown(GenerationFailure.PROTOCOL)
        if (parsed.videos.isNotEmpty()) {
            if (parsed.videos.size > GenerationRequestPlan.MAX_BATCH) return Submission.Unknown(GenerationFailure.PROTOCOL)
            return Submission.Completed(parsed.videos.mapIndexed { index, video ->
                try {
                    val source = if (video.bytes != null) {
                        require(video.bytes.isNotEmpty() && video.bytes.size <= GenerationRequestPlan.MAX_VIDEO_BYTES)
                        GenerationSource.Inline(video.bytes, GenerationVideoCodec.sniffMime(video.bytes) ?: video.mimeType)
                    } else {
                        val url = requireNotNull(video.url)
                        GenerationRequestPlan.checkedDownload(url)
                        GenerationSource.Remote(url)
                    }
                    GenerationOutput(index, source)
                } catch (_: Exception) { GenerationOutput(index, failure = GenerationFailure.PROTOCOL) }
            })
        }
        if (!allowTask || (parsed.taskId == null && parsed.status == null))
            return Submission.Unknown(GenerationFailure.PROTOCOL)
        val id = parsed.taskId ?: expectedId ?: return Submission.Unknown(GenerationFailure.PROTOCOL)
        if (!GenerationIo.validTaskId(id)) return Submission.Unknown(GenerationFailure.PROTOCOL)
        val (poll, content) = plan.taskUrls(id)
        val handle = GenerationHandle(plan.identity, plan.fingerprint, id, poll, content)
        return when {
            VideoGenerationParser.isTerminalSuccess(parsed.status) && plan.protocol == GenerationProtocol.ARK_CONTENTS_JSON -> Submission.Unknown(GenerationFailure.PROTOCOL, handle = handle)
            VideoGenerationParser.isTerminalSuccess(parsed.status) -> Submission.Completed(listOf(GenerationOutput(0,
                GenerationSource.Remote(content, authenticated = true))))
            parsed.status == null || VideoGenerationParser.isInProgress(parsed.status) -> Submission.Pending(handle)
            else -> Submission.Unknown(GenerationFailure.PROTOCOL, handle = handle)
        }
    }
}
