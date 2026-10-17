package app.daiyu.android.title

import app.daiyu.android.domain.*
import app.daiyu.android.model.ModelFactory
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.ProviderSnapshot
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class TitleOutcome {
    SAVED, DISABLED, BINDING_UNAVAILABLE, STALE, INELIGIBLE,
    INVALID_INPUT, MODEL_FAILED, INVALID_TITLE, OWNER_REVOKED, ALREADY_STARTED,
}

class TitleJob internal constructor(
    internal val coordinatorIdentity: Any,
    internal val original: TitleRevision,
    internal val binding: TitleModelBinding,
    internal val provider: ProviderSnapshot,
    internal val configurationRevision: String,
    val parentRunId: RunId,
    val requestId: String,
) {
    internal var started = false
    internal var revoked = false
    internal var session: ModelSession? = null
    val conversationId: ConversationId get() = original.conversationId
}

data class TitlePreparation(val outcome: TitleOutcome?, val job: TitleJob? = null)

/** Worker-only production coordinator, fixed to the preserved ConversationTitleModel algorithm.
 * prepare runs after accepted-send commit; generate only after terminal parent-save commit.
 * No executor, automatic retry/replay, main-model lookup, or parallel usage ledger is created. */
class TitleModelCoordinator internal constructor(
    private val settings: TitleModelSettingsStore,
    private val commits: SqliteTitleCommitAdapter,
    private val createModel: (ProviderSnapshot) -> ModelPort,
) : AutoCloseable {
    constructor(settings: TitleModelSettingsStore, commits: SqliteTitleCommitAdapter) :
        this(settings, commits, { frozen ->
            ModelFactory.create(requestSnapshot(frozen), ConversationTitleModel.INSTRUCTIONS)
        })

    private val gate = settings.admissionGate
    private val identity = Any()
    private var closed = false
    private val jobs = mutableSetOf<TitleJob>()
    init {
        settings.observeRevocation(identity) {
            // Called under the shared settings/admission monitor, never cancel a transport here.
            jobs.forEach { it.revoked = true }
            jobs.mapNotNull { it.session }.also { jobs.clear() }
        }
    }

    fun prepare(conversationId: ConversationId, parentRunId: RunId): TitlePreparation = synchronized(gate) {
        if (closed) return@synchronized TitlePreparation(TitleOutcome.OWNER_REVOKED)
        val config = try { settings.snapshot() } catch (_: Exception) {
            return@synchronized TitlePreparation(TitleOutcome.BINDING_UNAVAILABLE)
        }
        if (!config.enabled) return@synchronized TitlePreparation(TitleOutcome.DISABLED)
        val selected = config.binding ?: return@synchronized TitlePreparation(TitleOutcome.BINDING_UNAVAILABLE)
        val provider = try { settings.resolve(selected) } catch (_: Exception) {
            return@synchronized TitlePreparation(TitleOutcome.BINDING_UNAVAILABLE)
        }
        val original = commits.capture(conversationId) ?: return@synchronized TitlePreparation(TitleOutcome.STALE)
        // Host eligibility, not a replacement title heuristic: only the admitted first run and
        // the unchanged ConversationDatabase initial title may be automatically named.
        if (original.titleTouched || original.originalTitle != "新会话" || original.firstRunId != parentRunId.value)
            return@synchronized TitlePreparation(TitleOutcome.INELIGIBLE)
        val job = TitleJob(identity, original, selected, provider, checkNotNull(config.revision), parentRunId, "title:${UUID.randomUUID()}")
        jobs.add(job)
        TitlePreparation(null, job)
    }

    fun generate(job: TitleJob, committedFirstTurn: List<Message>): TitleOutcome {
        synchronized(gate) {
            if (closed || job.coordinatorIdentity !== identity || job.revoked) return TitleOutcome.OWNER_REVOKED
            if (job.started) return TitleOutcome.ALREADY_STARTED
            job.started = true
        }
        var deadline: Timer? = null
        val timedOut = AtomicBoolean(false)
        var requestStarted = false
        var saved = false
        var usageFailed = false
        var reported: ModelEvent.Usage? = null
        fun receipt() = ParentTitleUsage(job.requestId, job.conversationId, job.parentRunId, reported)
        try {
            if (committedFirstTurn.isEmpty() || committedFirstTurn.any {
                it.conversationId != job.conversationId || it.runId != job.parentRunId || it.status != RunStatus.COMPLETED
            } || committedFirstTurn.none { it.role == Message.ASSISTANT_ROLE } ||
                committedFirstTurn.none { it.role == Message.USER_ROLE && it.id.value == job.original.firstMessageId })
                return TitleOutcome.INVALID_INPUT
            val question = commits.firstQuestion(job.original) ?: return TitleOutcome.STALE
            if (question.isBlank()) return TitleOutcome.INVALID_INPUT
            try { settings.whileCurrent(job.binding, job.provider, job.configurationRevision) {} }
            catch (_: Exception) { return TitleOutcome.BINDING_UNAVAILABLE }
            val request = ConversationTitleModel.request(question, job.conversationId, job.parentRunId)
            // Factory/open may block or reach transport code. Neither runs under the
            // admission/settings monitor. A config/close winner permanently revokes this job;
            // a late open is cancelled before it can issue next(), never rebound to a new key.
            val session = createModel(job.provider).open(request, emptyList<ToolDefinition>())
            val admitted = synchronized(gate) {
                if (closed || job.revoked) false else {
                    try {
                        settings.whileCurrent(job.binding, job.provider, job.configurationRevision) {
                            job.session = session
                        }
                        true
                    } catch (_: TitleModelUnavailableException) { job.revoked = true; false }
                }
            }
            if (!admitted) {
                try { session.cancel() } catch (_: Exception) { }
                return TitleOutcome.OWNER_REVOKED
            }
            deadline = Timer("title-model-deadline", true).also { timer ->
                timer.schedule(object : TimerTask() {
                    override fun run() {
                        timedOut.set(true)
                        try { session.cancel() } catch (_: Exception) { }
                    }
                }, ConversationTitleModel.TIMEOUT_MS)
            }
            val finals = linkedMapOf<String, String>()
            synchronized(gate) {
                if (closed || job.revoked) return TitleOutcome.OWNER_REVOKED
                if (timedOut.get()) return TitleOutcome.MODEL_FAILED
                try {
                    settings.whileCurrent(job.binding, job.provider, job.configurationRevision) {
                        requestStarted = true
                    }
                } catch (_: TitleModelUnavailableException) { return TitleOutcome.BINDING_UNAVAILABLE }
            }
            session.next(emptyList()) { event ->
                // Preserve actual usage even on a cancellation/deadline boundary.
                if (event is ModelEvent.Usage) reported = event
                synchronized(gate) { check(!closed && !job.revoked && !timedOut.get()) }
                when (event) {
                    is ModelEvent.TextFinal -> {
                        finals[event.itemId] = event.text
                        require(finals.size <= 32 && finals.values.sumOf { it.length.toLong() } <= ConversationTitleModel.OUTPUT_LIMIT)
                    }
                    is ModelEvent.Usage -> reported = event
                    is ModelEvent.ToolReady -> error("标题模型不能调用工具")
                    else -> Unit // Deltas/reasoning are not a successfully completed title.
                }
            }
            if (timedOut.get()) return TitleOutcome.MODEL_FAILED
            if (finals.isEmpty()) return TitleOutcome.INVALID_TITLE
            val title = try { ConversationTitleModel.normalize(finals.values.joinToString("")) }
                catch (_: IllegalArgumentException) { return TitleOutcome.INVALID_TITLE }
            return synchronized(gate) {
                if (closed || job.revoked) return@synchronized TitleOutcome.OWNER_REVOKED
                if (timedOut.get()) return@synchronized TitleOutcome.MODEL_FAILED
                try {
                    settings.whileCurrent(job.binding, job.provider, job.configurationRevision) {
                        saved = commits.commit(job.original, title, receipt())
                        if (saved) TitleOutcome.SAVED else TitleOutcome.STALE
                    }
                } catch (_: TitleModelUnavailableException) { TitleOutcome.BINDING_UNAVAILABLE }
            }
        } catch (failure: TitleUsagePersistenceException) {
            usageFailed = true
            throw failure // Never turn a missing durable usage receipt into apparent success.
        } catch (_: Exception) {
            return synchronized(gate) {
                if (closed || job.revoked) TitleOutcome.OWNER_REVOKED else TitleOutcome.MODEL_FAILED
            }
        } finally {
            deadline?.cancel()
            val session = synchronized(gate) { jobs.remove(job); job.session.also { job.session = null } }
            try { session?.cancel() } catch (_: Exception) { }
            if (requestStarted && !saved && !usageFailed) commits.recordRequestUsage(receipt())
        }
    }

    fun revoke(job: TitleJob) {
        val session = synchronized(gate) {
            if (job.coordinatorIdentity !== identity) return
            job.revoked = true
            jobs.remove(job)
            job.session
        }
        try { session?.cancel() } catch (_: Exception) { }
    }

    override fun close() {
        val sessions = synchronized(gate) {
            closed = true
            settings.removeRevocation(identity)
            jobs.forEach { it.revoked = true }
            jobs.mapNotNull { it.session }.also { jobs.clear() }
        }
        sessions.forEach { try { it.cancel() } catch (_: Exception) { } }
    }

    companion object {
        /** Fixed transport budget only; no caller-supplied body can override model/tools/prompt. */
        internal fun requestSnapshot(provider: ProviderSnapshot): ProviderSnapshot {
            val field = when (provider.protocol) {
                ProviderProtocol.RESPONSES -> "max_output_tokens"
                ProviderProtocol.MESSAGES -> "max_tokens"
                ProviderProtocol.UNKNOWN -> throw TitleModelUnavailableException()
            }
            return provider.asModelSettings().copy(requestOptions = ProviderRequestOptions(
                customBody = "{\"$field\":${ConversationTitleModel.OUTPUT_LIMIT}}"),
                batteryEnabled = false, deviceStatusEnabled = false).requestSnapshot()
        }
    }
}
