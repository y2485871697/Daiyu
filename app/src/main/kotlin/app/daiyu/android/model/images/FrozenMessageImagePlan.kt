package app.daiyu.android.model.images

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.ModelImagePlan
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.media.ImageAttachmentException
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.media.MessageImageResolver
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

/** A run-local immutable, verified wire plan. No paths, mutable bytes, or caller-owned JSON escape.
 * expectedOwner MUST come from the host's persisted row/segment authorization, not ref.owner.
 * Resolve and encode the entire history before opening a session; never fall back to text.
 */
class FrozenMessageImagePlan private constructor(
    private val history: List<Message>,
    private val endpoint: String,
    private val protocol: ProviderProtocol,
    private val model: String,
    private val blocks: Map<Pair<Int, Int>, String>,
    internal val media: List<app.daiyu.android.model.StreamingMedia>,
) : ModelImagePlan {
    internal fun estimateImageSizes(): Map<String, Pair<Int, Int>> = blocks.entries.associate { (position, raw) ->
        val image = (history[position.first].blocks[position.second] as MessageBlock.Image).reference
        requireNotNull(app.daiyu.android.model.RequestInputEstimate.payload(JSONObject(raw))) to (image.width to image.height)
    }
    val hasImages: Boolean get() = blocks.isNotEmpty()

    internal fun validate(settings: ModelSettings, selectedProtocol: ProviderProtocol, capability: Boolean?, messages: List<Message>) {
        require(endpoint == settings.requestEndpoint() && protocol == selectedProtocol &&
            settings.protocol == selectedProtocol && model == settings.model) {
            "Image plan does not match the selected endpoint/protocol/model"
        }
        ImageInputEncoder.requireSupported(hasImages, capability)
        require(history == snapshot(messages)) { "Image plan does not match history" }
    }

    internal fun block(messageIndex: Int, blockIndex: Int): JSONObject =
        JSONObject(blocks[messageIndex to blockIndex] ?: error("Missing frozen image"))

    companion object {
        fun freeze(
            history: List<Message>,
            provider: ProviderSnapshot,
            resolver: MessageImageResolver,
            expectedOwner: (Message) -> MessageImageOwner,
            encoder: ImageInputEncoder = ImageInputEncoder(),
            isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        ): FrozenMessageImagePlan {
            fun checkCancelled() {
                if (isCancelled()) throw ImageAttachmentException(ImageAttachmentFailure.CANCELLED)
            }
            checkCancelled()
            val expanded = snapshot(history)
            rejectRawImages(expanded, provider.protocol)
            val hasImages = expanded.any { row -> row.blocks.any { it is MessageBlock.Image } }
            ImageInputEncoder.requireSupported(hasImages, provider.imageInputSupported)
            // Bound the ENTIRE expanded history before any resolver can allocate original bytes.
            // Encoding repeats these limits and still verifies the bytes, digest and dimensions.
            encoder.checkMetadataLimits(expanded.flatMap { row ->
                row.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference.bytes }
            })
            val frozen = mutableListOf<app.daiyu.android.media.FrozenImageAttachment>()
            val positions = mutableListOf<Pair<Int, Int>>()
            expanded.forEachIndexed { index, row ->
                val refs = row.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference }
                if (refs.isEmpty()) return@forEachIndexed
                checkCancelled()
                require(row.role == "user") { "Images require user history" }
                require(row.protocolItems.isEmpty()) { "Image messages cannot also replay protocolItems" }
                val owner = expectedOwner(row)
                require(owner.conversationId == row.conversationId.value && owner.messageId == row.id.value) {
                    "Authorized image owner does not match the history row"
                }
                require(refs.all { it.owner == owner }) { "Image reference belongs to another owner" }
                require(refs.map { it.id }.distinct().size == refs.size) { "Duplicate image in a message" }
                val mediaOwner = app.daiyu.android.media.MessageImageOwner(owner.conversationId, owner.messageId)
                val mediaRefs = refs.map { ref -> app.daiyu.android.media.MessageImageReference(
                    mediaOwner, ref.id, ref.mime, ref.bytes, ref.sha256, ref.width, ref.height
                ) }
                val resolved = resolver.freeze(mediaOwner, mediaRefs, true, isCancelled)
                require(resolved.size == mediaRefs.size && resolved.zip(mediaRefs).all { (image, ref) ->
                    image.attachment == ref.asAttachment()
                }) { "Resolver returned different image identities" }
                frozen.addAll(resolved)
                row.blocks.forEachIndexed { blockIndex, block ->
                    if (block is MessageBlock.Image) positions.add(index to blockIndex)
                }
            }
            val (encoded, media) = encoder.streaming(provider.protocol, frozen, provider.imageInputSupported, isCancelled)
            checkCancelled()
            return FrozenMessageImagePlan(expanded, provider.asModelSettings().requestEndpoint(), provider.protocol,
                provider.model, positions.zip(encoded.map { it.toString() }).toMap(), media)
        }

        internal fun snapshot(history: List<Message>): List<Message> = MessageHistory.expand(history).map {
            it.copy(blocks = it.blocks.toList(), protocolItems = it.protocolItems.toList(), historySegments = emptyList())
        }

        /** Saved provider bytes are never an authority for local images, even on ignored rows. */
        internal fun rejectRawImages(history: List<Message>, protocol: ProviderProtocol? = null) {
            history.forEach { row ->
                val mustValidateSyntax = when (protocol) {
                    ProviderProtocol.RESPONSES -> row.role != "user" &&
                        (row.status == RunStatus.COMPLETED || row.role == "assistant" && row.status == RunStatus.INTERRUPTED)
                    ProviderProtocol.MESSAGES -> row.role != "assistant" ||
                        row.status == RunStatus.COMPLETED || row.status == RunStatus.INTERRUPTED
                    else -> true
                }
                row.protocolItems.forEach { raw ->
                    val item = try {
                        app.daiyu.android.model.MessagesRequest.jsonObject(raw,
                            app.daiyu.android.model.MessagesRequest.MAX_MESSAGE_CONTAINER_DEPTH)
                    } catch (failure: JSONException) {
                        if (mustValidateSyntax) throw failure
                        rejectMalformedRawMedia(raw)
                        null
                    }
                    // Even ignored rows cannot authorize raw media; only unused syntax is skipped.
                    if (item != null) rejectRawImageNode(item)
                }
            }
        }

        // Invalid, unused items are never replayed. Conservatively reject media tokens even
        // when malformed syntax or excessive depth prevents structural inspection.
        private val rawUnicodeEscape = Regex("""\\u([0-9a-fA-F]{4})""")
        private val malformedMediaToken = Regex(
            "(?<![A-Za-z0-9_])(?:image|input_image|image_url|output_image|video|input_video|video_url)(?![A-Za-z0-9_])")
        private fun rejectMalformedRawMedia(raw: String) {
            val decoded = rawUnicodeEscape.replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
            require(!malformedMediaToken.containsMatchIn(decoded)) { "Raw protocol image/video input is not authorized" }
        }

        /** Inspect protocol positions, never arbitrary tool arguments, schemas or metadata. */
        internal fun rejectRawImageNode(node: Any?) {
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) {
                    (node.opt(i) as? JSONObject)?.let(::rejectRawImageItem)
                }
                is JSONObject -> {
                    if (node.has("type") || node.has("role")) {
                        rejectRawImageItem(node)
                    } else {
                        // Request/response envelopes. Do not recursively interpret business JSON.
                        listOf("input", "messages", "output").forEach { key ->
                            (node.opt(key) as? JSONArray)?.let(::rejectRawImageNode)
                        }
                    }
                }
            }
        }

        private fun rejectRawImageItem(item: JSONObject) {
            rejectImageBlock(item)
            if (item.has("role") || item.optString("type") == "message") {
                rejectRawImageContent(item.opt("content"))
            }
            // Responses tool outputs can contain typed multimodal content instead of a string.
            if (item.optString("type") in setOf("function_call_output", "computer_call_output")) {
                rejectRawImageContent(item.opt("output"))
            }
            // The built-in code interpreter uses a plural outputs array of logs/images.
            if (item.optString("type") == "code_interpreter_call") {
                rejectRawImageContent(item.opt("outputs"))
            }
        }

        private fun rejectRawImageContent(content: Any?) {
            when (content) {
                is JSONArray -> for (i in 0 until content.length()) {
                    rejectRawImageContent(content.opt(i))
                }
                is JSONObject -> {
                    rejectImageBlock(content)
                    when (content.optString("type")) {
                        "tool_result" -> rejectRawImageContent(content.opt("content"))
                        // Messages document sources may themselves contain text/image blocks.
                        "document" -> content.optJSONObject("source")?.let { source ->
                            if (source.optString("type") == "content") {
                                rejectRawImageContent(source.opt("content"))
                            }
                        }
                    }
                }
            }
        }

        private fun rejectImageBlock(block: JSONObject) {
            // Raw protocol media is never an alternative authority to a verified local plan.
            // This also makes Messages and default Responses fail closed for video input.
            val type = block.optString("type")
            require(type !in setOf("image", "input_image", "image_url", "output_image") && !block.has("image_url")) {
                "Raw protocol image input is not authorized"
            }
            require(type !in setOf("video", "input_video", "video_url") && !block.has("video_url")) {
                "Raw protocol image/video input is not authorized"
            }
        }
    }
}
