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
    val requestBodyJson: String = "",
    headers: Map<String, String> = emptyMap(),
) {
    val headers: Map<String, String> = Collections.unmodifiableMap(headers.toMap())
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(label.isNotBlank() && model.isNotBlank() && model.length <= 512)
        require(credential.isNotBlank() && credential.length <= 8192 && credential.all { it.code in 32..126 })
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port == 443))
        require(uri.rawPath.endsWith(protocol.suffix) && '%' !in uri.rawPath && "//" !in uri.rawPath && uri.normalize() == uri)
        require(requestBodyJson.length <= 262144)
        if (requestBodyJson.isNotBlank()) org.json.JSONObject(requestBodyJson)
        GenerationRequestPlan.validateHeaders(this.headers)
    }

    fun freeze(batchId: String, items: List<GenerationRunConfig.Item>): GenerationRunConfig.Batch =
        GenerationRunConfig.Batch(batchId, protocol, endpoint, credential, model,
            expandItems(items), requestBodyJson, headers)

    private fun expandItems(items: List<GenerationRunConfig.Item>): List<GenerationRunConfig.Item> {
        if (protocol.isVideo) return items
        return items.flatMap { item ->
            val explicitJson = org.json.JSONObject()
            item.parameters.filterKeys { it in GenerationRequestPlan.imageParameterKeys }.forEach { (key, value) ->
                explicitJson.put(key, GenerationRequestPlan.parameterValue(value))
            }
            val explicit = ImageGenerationOptions.fromJson(explicitJson)
            val parsed = ImagePromptOptions.parse(item.prompt, explicit)
            val body = if (requestBodyJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(requestBodyJson)
            item.parameters.filterKeys { it !in GenerationRequestPlan.imageParameterKeys }.forEach { (key, value) ->
                body.put(key, GenerationRequestPlan.parameterValue(value))
            }
            val prepared = if (GrokImageProfile.applies(endpoint, model, body))
                GrokImageProfile.prepare(body, parsed.options, explicit, item.references.size, item.mask != null)
            else ImageRequestParameters.prepare(body, parsed.options, explicit, 1)
            val count = prepared.options.count ?: 1
            if (prepared.options.concurrency == null || count == 1) listOf(item) else (0 until count).map { index ->
                GenerationRunConfig.Item("${item.id}_$index", item.prompt, item.references, item.mask,
                    item.parameters + mapOf("n" to "1", "concurrency" to prepared.options.concurrency.toString()))
            }
        }.also { require(it.size <= 32) }
    }

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
