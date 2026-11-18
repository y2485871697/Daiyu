package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.media.video.VideoReference
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.videos.OpenRouterVideoCapability
import app.daiyu.android.model.videos.VideoRequestPolicy

/** Admission to a fresh capability probe, not proof that a model supports video. */
internal fun canProbeNativeVideo(provider: ProviderSnapshot, videos: List<VideoReference>): Boolean {
    if (provider.protocol != ProviderProtocol.RESPONSES ||
        provider.asModelSettings().requestEndpoint() != OpenRouterVideoCapability.ENDPOINT ||
        videos.size !in 1..4 || videos.any { it.mime != "video/mp4" || it.bytes !in 1..(16L * 1024 * 1024) } ||
        videos.sumOf { it.bytes } > 16L * 1024 * 1024) return false
    return runCatching { VideoRequestPolicy.preflight(provider.asModelSettings()) }.isSuccess
}

/** Only a confirmed first-request rejection can change routes; never replay accepted output. */
internal fun nativeVideoWithFileFallback(native: ModelPort, files: () -> ModelPort): ModelPort = object : ModelPort {
    private fun openUsing(open: (ModelPort) -> ModelSession): ModelSession = object : RequestRetryModelSession {
        @Volatile private var current = open(native)
        private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        private var first = true
        private var definitions: List<ToolDefinition>? = null
        override val canResume get() = (current as? ResumableModelSession)?.canResume == true
        override val retriesCurrentRequest get() = (current as? RequestRetryModelSession)?.retriesCurrentRequest == true
        override fun updateToolDefinitions(definitions: List<ToolDefinition>) {
            this.definitions = definitions.toList(); current.updateToolDefinitions(definitions)
        }
        override fun prepareContinuation(continuation: RequestContinuation) {
            val retry = current as? RequestRetryModelSession
            if (retry != null) retry.prepareContinuation(continuation)
            else require(!continuation.hasAcceptedOutput && continuation.textPrefix.isEmpty())
        }
        override fun disconnectTransport() { (current as? RequestRetryModelSession)?.disconnectTransport() }
        override fun resume(sink: (ModelEvent) -> Unit) = checkNotNull(current as? ResumableModelSession).resume(sink)
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            val mayFallback = first && outputs.isEmpty()
            first = false
            var delivered = false
            try {
                current.next(outputs) { event ->
                    if (event !is ModelEvent.InputEstimate) delivered = true
                    sink(event)
                }
            } catch (failure: ModelRequestException) {
                if (!mayFallback || delivered || cancelled.get() || Thread.currentThread().isInterrupted ||
                    !isUnsupportedNativeVideo(failure)) throw failure
                current.cancel()
                val replacement = open(files())
                current = replacement
                if (cancelled.get()) { replacement.cancel(); throw InterruptedException("video_file_fallback_cancelled") }
                definitions?.let(replacement::updateToolDefinitions)
                replacement.next(emptyList(), sink)
            }
        }
        override fun cancel() { cancelled.set(true); current.cancel() }
    }
    override fun open(history: List<Message>) = openUsing { it.open(history) }
    override fun open(history: List<Message>, tools: Set<ReadOnlyTool>) = openUsing { it.open(history, tools) }
    override fun open(history: List<Message>, definitions: List<ToolDefinition>) = openUsing { it.open(history, definitions) }
    override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan) =
        openUsing { it.open(history, definitions, images) }
}

private fun isUnsupportedNativeVideo(failure: ModelRequestException): Boolean {
    val detail = failure.diagnostic ?: return false
    if (failure.failure != ModelFailure.REQUEST || detail.httpStatus !in setOf(400, 422)) return false
    val message = detail.providerDetail?.message?.lowercase().orEmpty()
    return Regex("""\b(?:unsupported|not supported)\s+['"]?(?:input_video|video_url|video input)\b""").containsMatchIn(message) ||
        Regex("""\b(?:input_video|video_url|video input)['"]?\s+(?:is |are )?(?:unsupported|not supported)\b""").containsMatchIn(message) ||
        Regex("""\bdoes not support\s+['"]?(?:input_video|video_url|video input)\b""").containsMatchIn(message)
}
