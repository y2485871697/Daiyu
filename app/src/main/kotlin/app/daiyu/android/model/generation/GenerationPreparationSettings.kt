package app.daiyu.android.model.generation

import java.net.URI
import java.util.Collections
import java.util.Locale

/** Explicit generation-only profile, supplied by trusted settings, never by model arguments.
 * No chat profile/default resolver and no serialisation of credentials are provided here.
 */
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

/** Exact, separately reviewed download authorities, not suffixes, wildcards or paid permission.
 * Empty means deny all downloads (inline outputs still work). No DNS/network occurs here.
 * The parent must additionally supply its network/DNS policy at leaf construction. This value
 * is not a DNS-pinning implementation. Redirects must be denied by the GenerationHttp transport.
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
            uri.normalize() == uri && '%' !in uri.rawAuthority
    }.getOrDefault(false)

    override fun toString() = "GenerationDownloadHosts(<reviewed authorities>)"
}
