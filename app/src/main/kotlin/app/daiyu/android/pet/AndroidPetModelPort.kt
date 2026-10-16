package app.daiyu.android.pet

import app.daiyu.android.domain.*
import app.daiyu.android.domain.pet.*
import app.daiyu.android.model.MessagesModel
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.storage.ModelSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Explicit lookup result: resolver must validate all three saved IDs, never select a main-agent fallback.
 * Credentials stay ephemeral here, never in PetState or PetStore. settings.model is the API model name.
 */
data class ResolvedPetModel(val selection: PetModelSelection, val settings: ModelSettings)
fun interface PetModelResolver { fun resolve(selection: PetModelSelection): ResolvedPetModel? }

/** Reuses production Responses/Messages transports, with one isolated prompt, no tools/history,
 * no inherited request body, strict output cap and no retry. Provider usage remains unknown if absent.
 * Ownership transfers to PetHost; close cancels all work and shuts down the pet-only executor.
 */
class AndroidPetModelPort internal constructor(
    private val resolver: PetModelResolver,
    private val transportFactory: (ModelSettings, Int) -> ModelPort
) : PetModelPort, AutoCloseable {
    constructor(resolver: PetModelResolver) : this(resolver, { settings, limit ->
        when (settings.protocol) {
            ProviderProtocol.RESPONSES -> ResponsesModel(settings)
            ProviderProtocol.MESSAGES -> MessagesModel(settings, limit)
            ProviderProtocol.UNKNOWN -> error("Unsupported pet protocol")
        }
    })
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "daiyu-pet-model").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private class Job {
        val cancelled = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val session = AtomicReference<ModelSession?>()
        val future = AtomicReference<Future<*>?>()
        fun cancel() {
            cancelled.set(true)
            runCatching { session.get()?.cancel() }
            future.get()?.cancel(true)
        }
    }
    private val jobs = ConcurrentHashMap<String, Job>()
    override fun complete(request: PetModelRequest, completion: (PetModelResult) -> Unit): PetCancel {
        check(!closed.get()) { "Pet model adapter closed" }
        require(request.selection.configured && request.usageOwner == "pet")
        require(request.maxOutputTokens in 1..120 && request.sessionId == "pet:${request.requestId}")
        val frozenRequest = request.copy(selection = request.selection.copy())
        val job = Job()
        fun deliver(result: PetModelResult) {
            if (job.delivered.compareAndSet(false, true)) runCatching { completion(result) }
        }
        check(jobs.putIfAbsent(frozenRequest.requestId, job) == null)
        try {
            // Resolve at acceptance, before queueing: later configuration edits must not retarget this request.
            // Freeze lookup failures too; the worker reports failure without retrying or falling back.
            val frozenModel = try {
                val resolved = resolver.resolve(frozenRequest.selection) ?: error("Pet selection unavailable")
                require(resolved.selection == frozenRequest.selection) { "Pet selection mismatch" }
                // Do not inherit assistant custom instructions, tools, reasoning or custom request bodies.
                val options = when (resolved.settings.protocol) {
                    ProviderProtocol.RESPONSES -> ProviderRequestOptions("{\"max_output_tokens\":${frozenRequest.maxOutputTokens}}")
                    ProviderProtocol.MESSAGES -> ProviderRequestOptions()
                    ProviderProtocol.UNKNOWN -> error("Unsupported pet protocol")
                }
                ResolvedPetModel(resolved.selection.copy(), resolved.settings.copy(
                    batteryEnabled = false, deviceStatusEnabled = false, requestOptions = options))
            } catch (_: Exception) { null }
            val future = executor.submit {
                try {
                    if (job.cancelled.get() || closed.get()) return@submit
                    val settings = frozenModel?.settings ?: error("Pet selection unavailable")
                    val transport = transportFactory(settings, frozenRequest.maxOutputTokens)
                    val message = Message(MessageId("pet-prompt-${frozenRequest.requestId}"), ConversationId(frozenRequest.sessionId),
                        RunId(frozenRequest.requestId), Message.USER_ROLE,
                        listOf(MessageBlock.Text("pet-input", frozenRequest.prompt)), RunStatus.COMPLETED)
                    val session = transport.open(listOf(message), emptyList<ToolDefinition>())
                    job.session.set(session)
                    if (job.cancelled.get() || closed.get()) { session.cancel(); return@submit }
                    val texts = linkedMapOf<String, String>()
                    var input: Long? = null; var output: Long? = null
                    session.next(emptyList()) { event ->
                        if (job.cancelled.get() || closed.get()) throw InterruptedException()
                        when (event) {
                            is ModelEvent.TextDelta -> texts[event.itemId] = (texts[event.itemId].orEmpty() + event.text).take(8192)
                            is ModelEvent.TextFinal -> texts[event.itemId] = event.text.take(8192)
                            is ModelEvent.Usage -> { input = event.inputTokens; output = event.outputTokens }
                            is ModelEvent.ToolReady -> error("Pet model attempted a tool call")
                            is ModelEvent.ReasoningDelta, is ModelEvent.ReasoningFinal -> error("Pet model attempted reasoning")
                            is ModelEvent.ProtocolOutput -> Unit
                        }
                        require(texts.size <= 16 && texts.values.sumOf { it.length } <= 8192) { "Pet response too large" }
                    }
                    // ModelSession.next returns successfully only for a genuine protocol terminal event.
                    if (!job.cancelled.get() && !closed.get()) {
                        val text = texts.values.joinToString("")
                        if (text.isBlank()) deliver(PetModelResult.Failed("桌宠模型未返回文字"))
                        else deliver(PetModelResult.Completed(text, input, output))
                    }
                } catch (_: Exception) {
                    if (!job.cancelled.get() && !closed.get()) deliver(PetModelResult.Failed("桌宠独立模型请求失败"))
                } finally { jobs.remove(frozenRequest.requestId, job) }
            }
            job.future.set(future)
            if (job.cancelled.get() || closed.get()) job.cancel()
        } catch (failure: Exception) { jobs.remove(frozenRequest.requestId, job); throw failure }
        return PetCancel { job.cancel(); jobs.remove(frozenRequest.requestId, job) }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobs.values.forEach { it.cancel() }; jobs.clear(); executor.shutdownNow()
    }
}
