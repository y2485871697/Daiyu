package app.daiyu.android.model

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.ModelImagePlan
import app.daiyu.android.media.ImageAttachmentException
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.model.images.ImageInputEncoder
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject

internal object ImageRequestPreflight {
    // Conservative local ceiling for BOTH protocols, including full history, tools and options.
    // Decimal bytes, below a 32 MiB envelope. A provider/partner may impose a lower limit.
    const val MAX_REQUEST_BYTES = 32_000_000

    fun history(settings: ModelSettings, protocol: ProviderProtocol, capability: Boolean?,
                history: List<Message>, input: ModelImagePlan?): FrozenMessageImagePlan? {
        settings.requestOptions.validate(protocol)
        val expanded = FrozenMessageImagePlan.snapshot(history)
        FrozenMessageImagePlan.rejectRawImages(expanded, protocol)
        val hasImages = expanded.any { row -> row.blocks.any { it is MessageBlock.Image } }
        ImageInputEncoder.requireSupported(hasImages, capability)
        val plan = input?.let {
            require(it is FrozenMessageImagePlan) { "Unrecognized image plan" }
            it
        }
        require(!hasImages || plan != null) { "Images require an explicit frozen plan" }
        plan?.validate(settings, protocol, capability, expanded)
        // Custom policy may never introduce an alternative, unauthenticated image representation.
        if (settings.requestOptions.customBody.isNotEmpty()) {
            FrozenMessageImagePlan.rejectRawImageNode(JSONObject(settings.requestOptions.customBody))
        }
        return plan
    }

    /** Called after ALL request options, once per round and before connect. Transmit these bytes. */
    fun bytes(request: JSONObject, settings: ModelSettings, plan: FrozenMessageImagePlan?,
              isCancelled: () -> Boolean): ByteArray {
        return prepare(request, settings, plan, isCancelled).use { body ->
            val output = java.io.ByteArrayOutputStream()
            body.writeTo(output, isCancelled)
            output.toByteArray()
        }
    }

    internal fun prepare(request: JSONObject, settings: ModelSettings, plan: FrozenMessageImagePlan?,
        isCancelled: () -> Boolean, videos: app.daiyu.android.model.videos.FrozenMessageVideoPlan? = null,
        uploaded: Set<String> = emptySet()): PreparedRequestBody {
        checkCancelled(isCancelled)
        if (plan?.hasImages == true || settings.hasModelOverrides) {
            if (settings.protocol != ProviderProtocol.GEMINI || request.has("model")) {
                require(request.opt("model") == settings.model) { "Custom body cannot change the model bound to attachments" }
            }
        }
        MessagesRequest.validateObject(request, MessagesRequest.MAX_JSON_CONTAINER_DEPTH)
        return PreparedRequestBody.prepare(request, plan?.media.orEmpty().filterNot { it.token in uploaded } + videos?.media.orEmpty(), isCancelled)
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw ImageAttachmentException(ImageAttachmentFailure.CANCELLED)
    }
}
