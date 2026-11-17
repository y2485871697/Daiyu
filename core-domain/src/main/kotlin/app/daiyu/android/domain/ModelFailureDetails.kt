package app.daiyu.android.domain

/** Only selected error fields cross this boundary, after redaction and size checks. */
data class ModelProviderDetail private constructor(
    val code: String?, val type: String?, val message: String?, val requestId: String?,
) {
    fun redacted(secrets: Collection<String>): ModelProviderDetail? = sanitized(code, type, message, requestId, secrets)

    companion object {
        private val identifier = Regex("[A-Za-z0-9_./:-]{1,128}")
        private val url = Regex("(?i)https?://[^\\s<>\\\"']+")
        private val credentials = Regex("(?i)(?:bearer|basic)\\s+[^\\s,;\\\"']+")
        private val namedSecret = Regex("(?i)(authorization|proxy-authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|cookie|set-cookie)[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\s,;\\\"'}]+")
        private val keyToken = Regex("\\b(?:sk-|sess-|ghp_|gho_)[A-Za-z0-9_-]{8,}")
        private val echoedRequest = Regex("(?i)[\\\"']?(messages|input|instructions|system|authorization|headers)[\\\"']?\\s*[:=]\\s*[\\[{]")

        fun sanitized(code: String? = null, type: String? = null, message: String? = null,
            requestId: String? = null, secrets: Collection<String> = emptyList()): ModelProviderDetail? {
            fun clean(raw: String?, limit: Int, token: Boolean): String? {
                if (raw.isNullOrBlank() || raw.length > limit) return null
                var value: String = raw
                secrets.asSequence().filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }.forEach {
                    value = value.replace(it, "[REDACTED]")
                }
                value = credentials.replace(value, "[REDACTED]")
                value = namedSecret.replace(value, "$1=[REDACTED]")
                value = keyToken.replace(value, "[REDACTED]")
                value = url.replace(value, "[URL REDACTED]")
                echoedRequest.find(value)?.let { value = value.take(it.range.first) + "[REQUEST REDACTED]" }
                value = value.map { if (it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069') ' ' else it }.joinToString("").trim()
                if (value.isBlank() || token && !identifier.matches(value)) return null
                return value.take(limit)
            }
            val detail = ModelProviderDetail(clean(code, 128, true), clean(type, 128, true),
                clean(message.takeUnless { "" in secrets }, 4096, false), clean(requestId, 128, true))
            return detail.takeIf { it.code != null || it.type != null || it.message != null || it.requestId != null }
        }
    }
}

/** Shared by the conversation error sheet, reconnect timeline and retained child results. */
fun modelFailureDetails(reason: ModelFailure, diagnostic: ModelRequestDiagnostic? = null): String {
    val provider = diagnostic?.providerDetail
    val code = listOfNotNull(provider?.code ?: diagnostic?.providerCode?.wireName ?: modelFailureLabel(reason),
        diagnostic?.httpStatus?.let { "HTTP $it" }).joinToString(" · ")
    val explanation = diagnostic?.unsupportedReasoningEffort?.unsupportedMessage
        ?: diagnostic?.rejection?.takeUnless { it == RequestRejection.UNSPECIFIED }?.userMessage
        ?: when (reason) {
            ModelFailure.NETWORK -> "模型连接中断或暂时无法建立，请检查网络与服务商状态。"
            ModelFailure.TIMEOUT -> "模型请求超时，远端结果尚未确认。"
            ModelFailure.AUTHENTICATION -> "模型服务鉴权失败，请检查密钥与认证方式。"
            ModelFailure.PERMISSION -> "模型服务拒绝访问，请检查账户及模型权限。"
            ModelFailure.RATE_LIMIT -> "模型服务限制请求或额度不足，请检查配额。"
            ModelFailure.SERVER -> "模型服务端发生错误，请检查服务状态。"
            ModelFailure.ENDPOINT -> "模型接口地址未找到或方法不支持，请检查服务地址和请求路径。"
            ModelFailure.REDIRECT -> "模型接口返回重定向，未转发密钥，请填写最终 HTTPS 地址。"
            ModelFailure.CONFIGURATION -> "无法准备模型请求，请检查服务地址及请求头设置。"
            ModelFailure.REQUEST -> "模型服务拒绝请求，具体原因未识别。"
            ModelFailure.PROTOCOL -> "模型服务未返回兼容的事件流，请检查接口协议。"
            ModelFailure.INCOMPLETE -> "模型回复未完整结束，已接收内容保留。"
            ModelFailure.TLS -> "模型 HTTPS 安全连接失败，请检查服务证书和网络。"
        }
    return buildString {
        append(code).append("\n\n").append(explanation)
        diagnostic?.field?.let { append("，涉及字段：").append(it.wireName) }
        append("\n错误分类：").append(modelFailureLabel(reason))
        append("\n本地错误码：").append(reason.name)
        provider?.type?.let { append("\n服务商类型码：").append(it) }
        provider?.message?.let { append("\n中文解释：").append(providerErrorExplanation(it, reason))
            append("\n服务商原文：").append(it) }
        provider?.requestId?.let { append("\n请求 ID：").append(it) }
        diagnostic?.transport?.let {
            append("\n\n传输诊断：").append(when (it.requestKind) {
                ModelRequestKind.POST -> "发送请求"; ModelRequestKind.RESUME -> "恢复原请求" })
            append(" · ").append(when (it.phase) {
                ModelTransportPhase.PREPARING -> "准备请求"; ModelTransportPhase.SENDING -> "发送中"
                ModelTransportPhase.WAITING_HEADERS -> "等待响应头"; ModelTransportPhase.READING_STREAM -> "读取流式回复"
                ModelTransportPhase.DECODING -> "解析回复" })
            append("\n网络原因：").append(when (it.cause) {
                ModelTransportCause.DNS -> "域名解析失败"; ModelTransportCause.CONNECT -> "无法建立连接"
                ModelTransportCause.TIMEOUT -> "连接或读取超时"; ModelTransportCause.TLS -> "安全连接失败"
                ModelTransportCause.SOCKET -> "套接字连接中断"; ModelTransportCause.IO -> "网络读写失败"
                ModelTransportCause.PREMATURE_EOF -> "回复尚未完成，连接已结束"
                ModelTransportCause.PARTIAL_SSE_FRAME -> "流式事件帧未完整接收"
                ModelTransportCause.PROTOCOL -> "回复协议不兼容"; ModelTransportCause.HTTP -> "服务端返回 HTTP 错误"
                ModelTransportCause.PROVIDER_REJECTION -> "服务商拒绝请求"
                ModelTransportCause.CONFIGURATION -> "请求配置错误" })
            append("\n请求耗时：").append(it.requestElapsedMs).append(" 毫秒")
            it.streamElapsedMs?.let { elapsed -> append("；流读取耗时：").append(elapsed).append(" 毫秒") }
            append("\n已接收完整事件帧：").append(it.sseFrames)
            it.lastFrameAgeMs?.let { age -> append("；距末帧：").append(age).append(" 毫秒") }
            append("\n响应头：").append(if (it.responseHeadersSeen) "已收到" else "未收到")
            append("；完成事件：").append(if (it.completionReceived) "已收到" else "未收到")
            append("\n连接结束：").append(if (it.eofSeen) "是" else "否")
            append("；残缺事件帧：").append(if (it.partialFrame) "有" else "无")
            append("；本地断开：").append(if (it.localDisconnectRequested) "是" else "否")
            append("\n连接细节：").append(when (it.socketDetail) {
                ModelSocketDetail.UNKNOWN -> "未取得更具体的连接原因"; ModelSocketDetail.RESET -> "连接被对端重置"
                ModelSocketDetail.CLOSED -> "连接已关闭"; ModelSocketDetail.ABORTED -> "连接被中止"
                ModelSocketDetail.BROKEN_PIPE -> "连接已断开，无法继续写入"; ModelSocketDetail.END_OF_STREAM -> "数据流意外结束"
                ModelSocketDetail.NETWORK_UNREACHABLE -> "网络不可达" })
        }
    }
}

internal fun modelFailureLabel(reason: ModelFailure): String = when (reason) {
    ModelFailure.NETWORK -> "网络连接错误"; ModelFailure.TIMEOUT -> "请求超时"
    ModelFailure.AUTHENTICATION -> "身份验证失败"; ModelFailure.PERMISSION -> "访问权限不足"
    ModelFailure.RATE_LIMIT -> "请求限流或额度不足"; ModelFailure.SERVER -> "服务端错误"
    ModelFailure.ENDPOINT -> "接口地址或方法错误"; ModelFailure.REDIRECT -> "接口重定向"
    ModelFailure.CONFIGURATION -> "请求配置错误"; ModelFailure.REQUEST -> "请求被拒绝"
    ModelFailure.PROTOCOL -> "回复协议错误"; ModelFailure.INCOMPLETE -> "回复未完整结束"
    ModelFailure.TLS -> "安全连接错误"
}

private fun providerErrorExplanation(message: String, reason: ModelFailure): String {
    if (message.any { it in '\u4e00'..'\u9fff' }) return message
    val value = message.lowercase(java.util.Locale.ROOT)
    return when {
        listOf("insufficient", "balance", "credit", "quota", "billing").any(value::contains) -> "账户余额或可用额度不足，请检查服务商账户和计费状态。"
        listOf("rate limit", "too many requests").any(value::contains) -> "请求过于频繁，已触发服务商限流。"
        listOf("api key", "unauthorized", "authentication").any(value::contains) -> "服务商未接受当前身份凭据，请检查密钥和认证配置。"
        listOf("permission", "forbidden", "access denied").any(value::contains) -> "当前账户没有访问此接口或模型的权限。"
        listOf("model not found", "model_not_found", "does not exist").any(value::contains) -> "服务商找不到所选模型或账户无权使用该模型。"
        listOf("context length", "context window", "maximum context", "too many tokens").any(value::contains) -> "请求超过模型的上下文或令牌数量限制。"
        listOf("overload", "capacity", "temporarily unavailable").any(value::contains) -> "服务商暂时过载或容量不足。"
        "unsupported" in value || "not supported" in value -> "服务商不支持请求中的某个参数、取值或能力，具体字段见原文。"
        "timeout" in value || "timed out" in value -> "服务商处理请求超时。"
        else -> "服务商返回了“${modelFailureLabel(reason)}”；以下保留原文供准确排查。"
    }
}
