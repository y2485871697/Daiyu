package app.daiyu.android.model.generation

import app.daiyu.android.delegation.SubAgentProfile
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import java.net.URI

/** Media roles resolve their own saved provider/model, never a text child or the chat default. */
internal object SavedMediaGenerationBindings {
    fun profile(profile: SubAgentProfile, saved: ModelSettings): GenerationProfileSettings {
        require(profile.isMedia && saved.oauth == null) { "媒体生成需要独立API密钥" }
        val source = if (saved.requestOptions.customBody.isBlank()) JSONObject() else JSONObject(saved.requestOptions.customBody)
        val capability = MediaReasoningSettings.resolve(source, profile.role)
        require(capability.status != MediaReasoningSettings.Status.INVALID) { capability.error }
        val body = MediaReasoningSettings.apply(source, profile.role, profile.reasoning)
        val image = profile.role == "image_generation"
        val protocol = if (image) {
            when (body.optJSONObject(ImageRequestParameters.CONFIG_KEY)?.optString("endpoint")) {
                "novelai_native" -> GenerationProtocol.NOVELAI_IMAGE_JSON
                else -> {
                    require(saved.protocol != ProviderProtocol.MESSAGES ||
                        body.optJSONObject(ImageRequestParameters.CONFIG_KEY)?.has("endpoint") == true) {
                        "Messages供应商需显式配置图片endpoint"
                    }
                    GenerationProtocol.OPENAI_IMAGES_JSON
                }
            }
        } else when (capability.transport) {
            "videos_json" -> GenerationProtocol.OPENAI_VIDEO_JSON
            "videos_generations" -> GenerationProtocol.OPENAI_VIDEOS_GENERATIONS_JSON
            "video_generations" -> GenerationProtocol.OPENAI_VIDEO_GENERATIONS_JSON
            "ark_contents" -> GenerationProtocol.ARK_CONTENTS_JSON
            "videos_multipart" -> GenerationProtocol.OPENAI_VIDEO_MULTIPART
            null -> {
                val host = URI(saved.endpoint).host.orEmpty()
                if (host == "volces.com" || host.endsWith(".volces.com") || host == "volcengine.com" ||
                    host.endsWith(".volcengine.com") || host.startsWith("ark.")) GenerationProtocol.ARK_CONTENTS_JSON
                else GenerationProtocol.OPENAI_VIDEO_MULTIPART
            }
            else -> error("未识别的视频生成协议")
        }
        if (image && profile.imageResolution != null) body.put("resolution", profile.imageResolution)
        val uri = URI(saved.endpoint)
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null)
        var path = uri.rawPath.orEmpty().trimEnd('/')
        val suffixes = GenerationProtocol.entries.map { it.suffix } + listOf("/chat/completions", "/responses", "/messages")
        suffixes.sortedByDescending { it.length }.firstOrNull { path.endsWith(it) }?.let { path = path.removeSuffix(it) }
        if (protocol == GenerationProtocol.ARK_CONTENTS_JSON &&
            (path.isEmpty() || path == "/api/coding/v3")) path = "/api/v3"
        val endpoint = URI(uri.scheme, null, uri.host, uri.port, path + protocol.suffix, null, null).toASCIIString()
        val encodedHeaders = JSONObject(saved.customHeaders.encode())
        return GenerationProfileSettings(profile.id, profile.name, protocol.kind, protocol, endpoint,
            saved.model, saved.apiKey, body.toString(), encodedHeaders.keys().asSequence().associateWith { encodedHeaders.getString(it) })
    }
}
