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
data class ResolvedPetModel(val selection: PetModelSelection, val settings: ModelSettings,
    /** Captures the registry epoch, not just the three stable IDs. Must be worker-safe. */
    val isCurrent: () -> Boolean = { true })
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
            ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI -> error("Pet model does not yet support ${settings.protocol.wireName}")
            ProviderProtocol.UNKNOWN -> error("Unsupported pet protocol")
        }
    })
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "daiyu-pet-model").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val authority = Any()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    // Production binds this once, before use, to PetController's serialized effect boundary.
    // Always acquire owner BEFORE authority; never hold authority while entering the controller.
    @Volatile private var owner: Any = Any()
    internal fun bindOwner(monitor: Any) {
        check(jobs.isEmpty() && generation.get() == 0L && !closed.get())
        owner = monitor
    }
    internal fun invalidateConfiguration() = synchronized(owner) {
        val retired = synchronized(authority) {
            generation.incrementAndGet()
            jobs.entries.filter { it.value.generation != generation.get() }.map { it.key to it.value }
        }
        retired.forEach { (id, job) -> job.cancel(); jobs.remove(id, job) }
    }
    private class Job(val generation: Long) {
        val cancelled = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        private val session = AtomicReference<ModelSession?>()
        val future = AtomicReference<Future<*>?>()
        fun publishSession(opened: ModelSession) {
            session.set(opened)
            // Cancellation may have observed null while open was still outside our locks.
            // Either that caller or this late publisher takes the session, never both.
            if (cancelled.get()) cleanSession()
        }
        fun cleanSession() {
            val retired = session.getAndSet(null) ?: return
            // A transport may block in cancel. Never run it inline (including under a reentrant
            // controller owner), on the model worker, or on an executor stopped by close.
            Thread({ runCatching { retired.cancel() } }, "daiyu-pet-model-cleanup").apply {
                isDaemon = true
            }.start()
        }
        fun cancel() {
            cancelled.set(true)
            future.get()?.cancel(true)
            cleanSession()
        }
    }
    private val jobs = ConcurrentHashMap<String, Job>()
    override fun complete(request: PetModelRequest, completion: (PetModelResult) -> Unit): PetCancel {
        check(!closed.get()) { "Pet model adapter closed" }
        require(request.selection.configured && request.usageOwner == "pet")
        require(request.maxOutputTokens in 1..120 && request.sessionId == "pet:${request.requestId}")
        val frozenRequest = request.copy(selection = request.selection.copy())
        val job = synchronized(authority) {
            check(!closed.get()) { "Pet model adapter closed" }
            Job(generation.get()).also { check(jobs.putIfAbsent(frozenRequest.requestId, it) == null) }
        }
        fun live(model: ResolvedPetModel?): Boolean = !job.cancelled.get() && !closed.get() &&
            job.generation == generation.get() && (model?.isCurrent?.invoke() != false)
        fun deliver(model: ResolvedPetModel?, result: PetModelResult) {
            synchronized(owner) {
                // Check INSIDE the effect owner's monitor, including late usage receipts. A check
                // before waiting for the controller would allow a stale callback to cross revocation.
                if (live(model) && job.delivered.compareAndSet(false, true)) runCatching { completion(result) }
            }
        }
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
                    ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI -> error("Pet model does not yet support ${resolved.settings.protocol.wireName}")
                    ProviderProtocol.UNKNOWN -> error("Unsupported pet protocol")
                }
                resolved.copy(selection = resolved.selection.copy(), settings = resolved.settings.copy(
                    batteryEnabled = false, deviceStatusEnabled = false, requestOptions = options))
            } catch (_: Exception) { null }
            val future = executor.submit {
                try {
                    if (!live(frozenModel)) return@submit
                    val settings = frozenModel?.settings ?: error("Pet selection unavailable")
                    val transport = transportFactory(settings, frozenRequest.maxOutputTokens)
                    val message = Message(MessageId("pet-prompt-${frozenRequest.requestId}"), ConversationId(frozenRequest.sessionId),
                        RunId(frozenRequest.requestId), Message.USER_ROLE,
                        listOf(MessageBlock.Text("pet-input", frozenRequest.prompt)), RunStatus.COMPLETED)
                    // Recheck after factory/queue delays, serialized with generation revocation.
                    // External open must stay outside authority/owner: an arbitrary transport can
                    // block here. This is not a physical-send fence for arbitrary transports.
                    synchronized(authority) { if (!live(frozenModel)) return@submit }
                    // Production Responses/Messages open only constructs a logical session; HTTP
                    // starts in next. Publish even a late return so cancel/close cannot leak it.
                    val session = transport.open(listOf(message), emptyList<ToolDefinition>())
                    job.publishSession(session)
                    if (!live(frozenModel)) return@submit
                    val texts = linkedMapOf<String, String>()
                    var input: Long? = null; var output: Long? = null
                    session.next(emptyList()) { event ->
                        if (!live(frozenModel)) throw InterruptedException()
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
                    if (live(frozenModel)) {
                        val text = texts.values.joinToString("")
                        if (text.isBlank()) deliver(frozenModel, PetModelResult.Failed("桌宠模型未返回文字"))
                        else deliver(frozenModel, PetModelResult.Completed(text, input, output))
                    }
                } catch (_: Exception) {
                    deliver(frozenModel, PetModelResult.Failed("桌宠独立模型请求失败"))
                } finally {
                    job.cleanSession()
                    jobs.remove(frozenRequest.requestId, job)
                }
            }
            job.future.set(future)
            if (job.cancelled.get() || closed.get()) job.cancel()
        } catch (failure: Exception) { jobs.remove(frozenRequest.requestId, job); throw failure }
        return PetCancel { job.cancel(); jobs.remove(frozenRequest.requestId, job) }
    }
    override fun close() {
        synchronized(owner) {
            val retired = synchronized(authority) {
                if (!closed.compareAndSet(false, true)) return
                generation.incrementAndGet()
                snapshotForDrain(jobs.values).also { jobs.clear() }
            }
            retired.forEach { it.cancel() }
            executor.shutdownNow()
        }
    }
}

internal fun <T> snapshotForDrain(values: Collection<T>): List<T> =
    buildList { values.forEach { add(it) } }
