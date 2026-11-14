package app.daiyu.android.model.generation

import java.net.URI
import java.util.Collections
import java.util.Locale

/** 由可信设置提供的生成专用配置，与聊天默认设置及模型参数分开。 */
class GenerationModelProfile(
    val id: String,
    val label: String,
    val protocol: GenerationProtocol,
    val endpoint: String,
    private val credential: String,
    val model: String,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(label.isNotBlank() && model.isNotBlank() && model.length <= 512)
        require(credential.isNotBlank() && credential.length <= 8192 && credential.all { it.code in 32..126 })
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port == 443))
        val suffix = when (protocol) {
            GenerationProtocol.OPENAI_IMAGES_JSON -> "/images/generations"
            GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> "/images/edits"
            GenerationProtocol.OPENAI_VIDEO_MULTIPART -> "/videos"
        }
        require(uri.rawPath.endsWith(suffix) && '%' !in uri.rawPath && "//" !in uri.rawPath && uri.normalize() == uri)
    }

    fun freeze(batchId: String, items: List<GenerationRunConfig.Item>): GenerationRunConfig.Batch =
        GenerationRunConfig.Batch(batchId, protocol, endpoint, credential, model, items)

    override fun toString() = "GenerationModelProfile(<redacted>)"
}

/** 精确下载站点列表，空列表只允许内联输出。
 * 宿主另行提供网络、DNS 和付费授权检查，传输层禁止重定向。
 */
class GenerationDownloadHosts(hosts: Set<String>) {
    val hosts: Set<String> = Collections.unmodifiableSet(hosts.toSet())
    init {
        require(this.hosts.all { host ->
            host == host.lowercase(Locale.ROOT) && host.length <= 253 &&
                host.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) &&
                host.contains('.') && !host.endsWith(".local") && !host.endsWith(".localhost") &&
                !host.endsWith(".internal") && !host.all { it.isDigit() || it == '.' } &&
                host.split('.').all { it.isNotEmpty() && it.length <= 63 && !it.startsWith('-') && !it.endsWith('-') }
        }) { "generation_download_host_invalid" }
    }

    fun allows(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null &&
            uri.rawFragment == null && (uri.port == -1 || uri.port == 443) &&
            uri.normalize() == uri && uri.path.orEmpty().split('/').none { it == "." || it == ".." } &&
            '%' !in uri.rawAuthority
    }.getOrDefault(false)

    override fun toString() = "GenerationDownloadHosts(<reviewed authorities>)"
}
