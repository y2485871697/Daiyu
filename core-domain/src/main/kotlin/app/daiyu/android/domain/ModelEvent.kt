package app.daiyu.android.domain

sealed interface ModelEvent {
    data class TextDelta(val itemId: String, val text: String) : ModelEvent
    data class TextFinal(val itemId: String, val text: String) : ModelEvent
    data class ReasoningDelta(val itemId: String, val text: String) : ModelEvent
    data class ReasoningFinal(val itemId: String, val text: String) : ModelEvent
    data class ProtocolOutput(val items: List<String>) : ModelEvent
    data class ToolReady(val invocation: ToolInvocation) : ModelEvent
}
data class ToolOutput(val callId: ToolCallId, val output: String)
interface ModelSession {
    /** Blocking, ordered delivery. Return only after a successful protocol terminal event. */
    fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit)
    fun cancel()
}
fun interface ModelPort {
    fun open(history: List<Message>): ModelSession
    fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession = open(history)
}
fun interface BatteryTool { fun execute(invocation: ToolInvocation): String }
enum class ReadOnlyTool(val toolName: String, val description: String) {
    BATTERY("get_battery_status", "读取当前设备电量和充电状态；无写入能力"),
    DEVICE_STATUS("device_status", "读取设备电量、内存、应用所在存储空间、Android版本与开机时长；无标识和写入能力")
}

interface ConversationStore {
    fun save(message: Message)
}

/** Only fixed messages cross the model boundary; no service body or credentials. */
enum class ModelFailure(val userMessage: String) {
    AUTHENTICATION("鉴权失败（HTTP 401），请检查密钥与服务认证方式"),
    PERMISSION("访问被拒绝（HTTP 403），请检查账户及模型访问权限"),
    ENDPOINT("接口地址未找到或方法不支持，请检查服务地址和 Responses 路径"),
    REDIRECT("接口返回重定向；未转发密钥，请填写最终 HTTPS 地址"),
    RATE_LIMIT("服务限制请求或额度不足（HTTP 429），请检查配额或稍后手动重试"),
    SERVER("模型服务端发生错误，请检查服务状态；未自动重试"),
    REQUEST("服务拒绝本次请求，请检查模型名称及 Responses 能力"),
    PROTOCOL("服务未返回兼容的 Responses 事件流，请检查接口协议"),
    INCOMPLETE("回复未完整结束；已接收内容保留，未执行未确认工具"),
    TIMEOUT("请求超时，远端结果尚未确认；已接收内容保留，未自动重发"),
    TLS("HTTPS 安全连接失败，请检查服务证书和网络"),
    NETWORK("网络连接失败，请检查服务地址和网络；未自动重试")
}
class ModelRequestException(val failure: ModelFailure) : RuntimeException("Model request failed")
