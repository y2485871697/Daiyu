package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelRequestDiagnostic
import app.daiyu.android.domain.RequestRejection

internal fun reconnectErrorDetails(reason: ModelFailure, diagnostic: ModelRequestDiagnostic? = null): String {
    val code = listOfNotNull(diagnostic?.providerCode?.wireName ?: reason.name,
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
    val field = diagnostic?.field?.let { "，涉及字段：${it.wireName}" }.orEmpty()
    return "$code\n\n$explanation$field"
}
