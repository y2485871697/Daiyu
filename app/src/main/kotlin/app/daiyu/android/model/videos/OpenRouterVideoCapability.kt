package app.daiyu.android.model.videos

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings

/** 宿主确认当前模型和端点支持内联原始 MP4，并取得上传授权。
 * 仅接受指定 HTTPS 端点，排除网关别名、替代路径、查询参数和重定向。
 */
class OpenRouterVideoCapability private constructor(
    private val model: String,
    private val endpoint: String,
    internal val maxVideoBytes: Long,
    internal val maxTotalBytes: Long,
    internal val maxVideos: Int,
) {
    internal fun validate(settings: ModelSettings) {
        require(settings.protocol == ProviderProtocol.RESPONSES &&
            settings.requestEndpoint() == endpoint && settings.model == model) {
            "Original video permission does not match endpoint/protocol/model"
        }
        // Freeze calls this before reading any original; opening a session checks it again.
        VideoRequestPolicy.preflight(settings)
    }

    override fun toString() = "OpenRouterVideoCapability(<explicit permission>)"

    companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/responses"

        /** Call only with trusted, current capability evidence AND explicit upload configuration.
         * Defaults cap the entire expanded history at 16 MiB raw (~22.4 MiB base64); the existing
         * 32,000,000-byte full request ceiling still applies, including text/images/tools/options.
         * Smaller model/provider budgets must be passed by the host; these are local ceilings,
         * not a claim about provider limits.
         */
        fun grant(
            model: String,
            endpoint: String,
            verifiedInlineMp4Support: Boolean,
            uploadEnabled: Boolean,
            maxVideoBytes: Long = 16L * 1024 * 1024,
            maxTotalBytes: Long = 16L * 1024 * 1024,
            maxVideos: Int = 4,
        ): OpenRouterVideoCapability {
            require(verifiedInlineMp4Support && uploadEnabled) { "Original video is not explicitly authorized" }
            require(endpoint == ENDPOINT) { "Only the explicit OpenRouter Responses video extension is supported" }
            require(model.isNotBlank() && model.length <= 200 && model == model.trim())
            require(maxVideoBytes in 1..(16L * 1024 * 1024))
            require(maxTotalBytes in 1..(16L * 1024 * 1024))
            require(maxVideos in 1..4)
            return OpenRouterVideoCapability(model, endpoint, maxVideoBytes, maxTotalBytes, maxVideos)
        }
    }
}
