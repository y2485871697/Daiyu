package app.daiyu.android.model.videos

import app.daiyu.android.domain.Message
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.media.video.VideoAssetStore
import app.daiyu.android.media.video.VideoAttachmentException
import app.daiyu.android.media.video.VideoAttachmentFailure
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import java.util.Base64

/** A deliberately small Responses-only sidecar. References must be loaded from accepted message
 * rows, not drafts, previews, raw protocol JSON or a provider URI. Existing domain image/text
 * blocks stay unchanged. Videos are appended in list order after that row's text/image blocks.
 * The parent is responsible for supplying EVERY retained video row, including history segments.
 */
class MessageVideoInput(val owner: MessageVideoOwner, references: List<MessageVideoReference>) {
    val references: List<MessageVideoReference> = java.util.Collections.unmodifiableList(references.toList())
    override fun toString() = "MessageVideoInput(<references>)"
}

/** Immutable encoded original bytes bound to a complete expanded history AND one explicit grant.
 * No filesystem/provider IO occurs after freezing. Never persist this plan or its data URLs.
 */
class FrozenMessageVideoPlan private constructor(
    private val history: List<Message>,
    private val capability: OpenRouterVideoCapability,
    private val blocks: Map<Int, List<String>>,
) {
    internal fun validate(settings: ModelSettings, grant: OpenRouterVideoCapability?, messages: List<Message>) {
        require(grant === capability) { "Original video requires the same explicit capability" }
        capability.validate(settings)
        require(history == FrozenMessageImagePlan.snapshot(messages)) { "Video plan does not match history" }
    }

    internal fun validateRequest(settings: ModelSettings, request: JSONObject, input: org.json.JSONArray) {
        capability.validate(settings)
        VideoRequestPolicy.finalPayload(settings, request, input)
    }

    internal fun hasVideos(messageIndex: Int): Boolean = blocks[messageIndex]?.isNotEmpty() == true
    internal fun blocks(messageIndex: Int): List<JSONObject> = blocks[messageIndex].orEmpty().map { JSONObject(it) }
    override fun toString() = "FrozenMessageVideoPlan(<original video payload redacted>)"

    companion object {
        fun freeze(
            history: List<Message>,
            provider: ProviderSnapshot,
            capability: OpenRouterVideoCapability,
            inputs: List<MessageVideoInput>,
            store: VideoAssetStore,
            expectedOwner: (Message) -> MessageVideoOwner,
            isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        ): FrozenMessageVideoPlan {
            fun checkCancelled() {
                if (isCancelled()) throw VideoAttachmentException(VideoAttachmentFailure.CANCELLED)
            }
            checkCancelled()
            capability.validate(provider.asModelSettings())
            val expanded = FrozenMessageImagePlan.snapshot(history)
            FrozenMessageImagePlan.rejectRawImages(expanded)
            val source = inputs.toList()
            require(source.isNotEmpty() && source.all { it.references.isNotEmpty() }) { "Video inputs must not be empty" }
            require(source.map { it.owner }.toSet().size == source.size) { "Duplicate video message owner" }
            val positions = linkedMapOf<Int, List<MessageVideoReference>>()
            var count = 0
            var total = 0L
            // Validate ALL ownership, types and budgets before opening ANY original.
            source.forEach { input ->
                checkCancelled()
                val matches = expanded.withIndex().filter { (_, row) ->
                    row.conversationId.value == input.owner.conversationId && row.id.value == input.owner.messageId
                }
                require(matches.size == 1) { "Video owner must match exactly one expanded history row" }
                val (index, row) = matches.single()
                require(row.role == "user" && row.protocolItems.isEmpty()) { "Videos require user history without protocol replay" }
                require(expectedOwner(row) == input.owner) { "Video message owner is not authorized" }
                require(input.references.all { it.owner == input.owner }) { "Video reference belongs to another owner" }
                require(input.references.map { it.id }.toSet().size == input.references.size) { "Duplicate video reference" }
                input.references.forEach { ref ->
                    if (ref.mime != "video/mp4") throw VideoAttachmentException(VideoAttachmentFailure.UNSUPPORTED_VIDEO)
                    if (ref.bytes <= 0) throw VideoAttachmentException(VideoAttachmentFailure.CORRUPT)
                    if (ref.bytes > capability.maxVideoBytes) throw VideoAttachmentException(VideoAttachmentFailure.TOO_LARGE)
                    count++
                    if (count > capability.maxVideos) throw VideoAttachmentException(VideoAttachmentFailure.TOO_MANY)
                    total += ref.bytes
                    if (total > capability.maxTotalBytes) throw VideoAttachmentException(VideoAttachmentFailure.TOO_LARGE)
                }
                positions[index] = input.references
            }
            val encoded = positions.mapValues { (_, references) -> references.map { ref ->
                checkCancelled()
                val original = store.readForModel(ref.owner, ref, capability.maxVideoBytes, isCancelled)
                checkCancelled()
                val data = Base64.getEncoder().encodeToString(original)
                checkCancelled()
                JSONObject().put("type", "input_video").put("video_url", "data:video/mp4;base64,$data").toString()
            } }
            checkCancelled()
            return FrozenMessageVideoPlan(expanded, capability, encoded)
        }
    }
}
