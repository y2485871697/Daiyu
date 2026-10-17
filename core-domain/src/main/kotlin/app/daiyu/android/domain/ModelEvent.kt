package app.daiyu.android.domain

sealed interface ModelEvent {
    data class TextDelta(val itemId: String, val text: String) : ModelEvent
    data class TextFinal(val itemId: String, val text: String) : ModelEvent
    data class ReasoningDelta(val itemId: String, val text: String) : ModelEvent
    data class ReasoningFinal(val itemId: String, val text: String) : ModelEvent
    /** Replacement snapshot cumulative within one ModelSession, including submitted outputs, excluding open history. */
    data class ProtocolOutput(val items: List<String>) : ModelEvent
    data class ToolReady(val invocation: ToolInvocation) : ModelEvent
    /** Complete provider-reported cumulative snapshot for one request, not a session total or
     * billing receipt. null is unknown, not zero. Cache reads are a subset of total input,
     * not an additional charge. A later snapshot replaces all fields, even null or lower counts.
     * Providers normalize partial wire observations and apply their own upper count bound. */
    data class Usage(val inputTokens: Long?, val outputTokens: Long?, val cachedTokens: Long? = null) : ModelEvent {
        init {
            require(inputTokens == null || inputTokens >= 0)
            require(outputTokens == null || outputTokens >= 0)
            require(cachedTokens == null || cachedTokens >= 0)
            require(inputTokens == null || cachedTokens == null || cachedTokens <= inputTokens)
        }
    }
}
data class ToolOutput(val callId: ToolCallId, val output: String)
interface ModelSession {
    /** Blocking, ordered delivery. Return only after a successful protocol terminal event. */
    fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit)
    fun cancel()
}
/** Optional transport-only resume capability. Implementations must not repost a model request or
 * tool outputs, and must deliver only events not acknowledged by a successful sink return.
 * If the server cannot resume with those guarantees, canResume must be false. */
interface ResumableModelSession : ModelSession {
    val canResume: Boolean
    fun resume(sink: (ModelEvent) -> Unit)
}
/** Explicit acknowledgement: a stale gate returning Unit is NOT an acknowledgement. */
enum class ModelDeliveryAck { ACCEPTED, REJECTED }
data class TransportCheckpoint(val responseId: String, val sequence: Long, val decoderState: String) {
    init { require(responseId.matches(Regex("resp_[A-Za-z0-9_-]{1,180}"))); require(sequence >= 0) }
}
data class ModelEventBatch(val events: List<ModelEvent>, val checkpoint: TransportCheckpoint)
class ModelDeliveryRejectedException : IllegalStateException("Provider delivery was not accepted")

/** Only the original process/session may use this interface. The checkpoint is evidence, never
 * authority to open or recover a session after process death. A batch is one wire sequence;
 * terminal batches include Usage AND ProtocolOutput before their cursor is acknowledged. */
interface CheckpointedResumableModelSession : ResumableModelSession {
    fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck)
    fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck)
    /** Interrupt just the current read. Unlike cancel(), this does not revoke the session. */
    fun disconnectTransport()
}
fun interface ModelPort {
    fun open(history: List<Message>): ModelSession
    fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession = open(history)
    fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession = open(history)
    /** Explicit platform-frozen image input; legacy transports must not silently drop it. */
    fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
        throw UnsupportedOperationException("This model does not accept frozen image input")
}
/** Opaque platform input, with no filesystem or permission API in the domain. */
interface ModelImagePlan
/** parameters is a JSON Schema supplied by the platform tool catalogue. */
data class ToolDefinition(val name: String, val description: String, val parameters: String, val sideEffect: Boolean = false)
fun interface BatteryTool { fun execute(invocation: ToolInvocation): String }
interface CatalogTool : BatteryTool { val definitions: List<ToolDefinition> }
enum class ReadOnlyTool(val toolName: String, val description: String) {
    BATTERY("get_battery_status", "读取当前设备电量和充电状态；无写入能力"),
    DEVICE_STATUS("device_status", "读取设备电量、内存、应用所在存储空间、Android版本与开机时长；无标识和写入能力")
}

interface ConversationStore {
    fun save(message: Message)
    /** Recovery adapters should atomically save RUNNING or verify an identical terminal row.
     * The legacy fallback supports first recovery only; override for crash-safe reconciliation. */
    fun reconcileRecovery(message: Message) = save(message)
}

/** Only fixed messages cross the model boundary; no service body or credentials. */
enum class ModelFailure(val userMessage: String) {
    AUTHENTICATION("鉴权失败（HTTP 401），请检查密钥与服务认证方式"),
    PERMISSION("访问被拒绝（HTTP 403），请检查账户及模型访问权限"),
    ENDPOINT("接口地址未找到或方法不支持，请检查服务地址和请求路径"),
    REDIRECT("接口返回重定向；未转发密钥，请填写最终 HTTPS 地址"),
    RATE_LIMIT("服务限制请求或额度不足（HTTP 429），请检查配额或稍后手动重试"),
    SERVER("模型服务端发生错误，请检查服务状态；未自动重试"),
    REQUEST("服务拒绝本次请求，请检查模型名称及所选协议能力"),
    PROTOCOL("服务未返回兼容的事件流，请检查所选接口协议"),
    INCOMPLETE("回复未完整结束；已接收内容保留，未执行未确认工具"),
    TIMEOUT("请求超时，远端结果尚未确认；已接收内容保留，未自动重发"),
    TLS("HTTPS 安全连接失败，请检查服务证书和网络"),
    NETWORK("网络连接失败，请检查服务地址和网络；未自动重试")
}
class ModelRequestException(val failure: ModelFailure) : RuntimeException("Model request failed")

/** A local reconnect budget expiry, never a retryable provider/network failure. */
open class TransportReconnectDeadlineException(lateSuccess: Boolean = false) : RuntimeException(
    if (lateSuccess) "错误重连期限已到，未接纳迟到的响应；此前正文和工具结果已保留。"
    else "错误重连期限已到，模型请求仍未恢复；此前正文和工具结果已保留。"
) {
    val userMessage: String = checkNotNull(message)
}
