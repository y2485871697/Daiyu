package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.terminal.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/** Host values only. This is a NEW explicit persistent-PTY API, not TerminalRegistry shell execution. */
internal sealed interface TerminalPtyLaunch {
    class Android(argv: List<String>, environment: Map<String, String>, cwd: File) : TerminalPtyLaunch {
        val argv: List<String> = Collections.unmodifiableList(argv.toList())
        val environment: Map<String, String> = Collections.unmodifiableMap(environment.toMap())
        val cwd: File = cwd.also { require(it.isAbsolute) }.toPath().normalize().toFile()
        init {
            require(this.argv.isNotEmpty() && this.argv.size <= 64 && this.argv.first().startsWith("/"))
            require(this.argv.all { it.isNotEmpty() && '\u0000' !in it } && this.argv.sumOf { it.length } <= 32768)
            require(this.environment.size <= 128 && this.environment.entries.sumOf { it.key.length + it.value.length } <= 32768)
            require(this.environment.all { (key, value) -> key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && '\u0000' !in value })
        }
    }
    /** The pre-approval lease pins the selected launch, not just a reusable generation label.
     * If approval is abandoned, the host must close this snapshot. Once approved the binding owns it. */
    class Linux private constructor(
        val store: LinuxEnvironmentStore,
        val expected: LinuxGenerationRef,
        val tempDirectory: File,
        val hostWorkingDirectory: File,
        private val lease: LinuxEnvironmentStore.Lease,
        val argv: List<String>,
        val environment: Map<String, String>,
    ) : TerminalPtyLaunch, AutoCloseable {
        private var claimed = false
        private var released = false
        @Synchronized internal fun claim() { check(!claimed && !released) { "terminal_launch_already_claimed" }; claimed = true }
        @Synchronized override fun close() { check(!claimed) { "terminal_launch_owned_by_binding" }; release() }
        @Synchronized internal fun release() { if (!released) { lease.close(); released = true } }
        internal fun commandConfiguration(): TerminalConfiguration = lease.configuration(tempDirectory, hostWorkingDirectory)
        internal fun revalidate() {
            val current = commandConfiguration()
            val backend = checkNotNull(current.linux)
            check(backend.shellArgv == argv && backend.environment + current.environment == environment) { "terminal_launch_changed" }
        }
        companion object {
            /** Worker-only metadata/identity checks; does not launch a process. */
            fun freeze(store: LinuxEnvironmentStore, expected: LinuxGenerationRef, tempDirectory: File, hostWorkingDirectory: File): Linux {
                require(tempDirectory.isAbsolute && hostWorkingDirectory.isAbsolute)
                val lease = store.acquire(expected)
                try {
                    val temp = tempDirectory.canonicalFile
                    val cwd = hostWorkingDirectory.canonicalFile
                    val configuration = lease.configuration(temp, cwd)
                    val backend = checkNotNull(configuration.linux)
                    return Linux(store, expected, temp, cwd, lease,
                        Collections.unmodifiableList(backend.shellArgv.toList()),
                        Collections.unmodifiableMap((backend.environment + configuration.environment).toMap()))
                } catch (failure: Throwable) { lease.close(); throw failure }
            }
        }
    }
}

/** Request IDs and both sequence domains are allocated once by the host, never from model call IDs. */
internal sealed class TerminalPtyStep(val id: String) {
    class Open(id: String) : TerminalPtyStep(id)
    class Input(id: String, val requestId: String, val sequence: Long, bytes: ByteArray) : TerminalPtyStep(id) {
        private val frozen = bytes.also { require(it.size in 1..65536) }.copyOf()
        internal fun bytes(): ByteArray = frozen.copyOf()
        internal val size: Int get() = frozen.size
    }
    class Resize(id: String, val requestId: String, val sequence: Long, val rows: Int, val columns: Int) : TerminalPtyStep(id)
    class Interrupt(id: String, val requestId: String, val sequence: Long) : TerminalPtyStep(id)
}
internal data class TerminalPtyRead(val id: String, val offset: Int, val limit: Int = 16384)

/** Freeze first, show the exact effects to the user, then construct TaskAuthorization and approve(). */
internal class TerminalPtyPlan(
    val conversationId: ConversationId,
    val sessionId: String,
    val runId: String,
    val environmentId: String,
    val generation: String,
    val launch: TerminalPtyLaunch,
    journalDirectory: File,
    val sessionScope: String,
    steps: List<TerminalPtyStep>,
    reads: List<TerminalPtyRead> = emptyList(),
    val rows: Int = 24,
    val columns: Int = 80,
) {
    val journalDirectory: File = journalDirectory.also { require(it.isAbsolute) }.toPath().normalize().toFile()
    val steps: List<TerminalPtyStep> = Collections.unmodifiableList(steps.toList())
    val reads: List<TerminalPtyRead> = Collections.unmodifiableList(reads.toList())
    val effects: Map<String, WriteEffect>
    private var claimed = false
    @Synchronized internal fun claim() {
        check(!claimed) { "terminal_plan_already_claimed" }
        (launch as? TerminalPtyLaunch.Linux)?.claim()
        claimed = true
    }
    init {
        require(sessionId.isNotBlank() && runId.isNotBlank())
        require(environmentId.matches(TOKEN) && generation.matches(TOKEN) && sessionScope.matches(TOKEN))
        require(rows in 1..1000 && columns in 1..1000)
        require(this.steps.size in 1..64 && this.steps.first() is TerminalPtyStep.Open && this.steps.drop(1).none { it is TerminalPtyStep.Open })
        require(this.steps.all { it.id.matches(TOKEN) } && this.steps.map { it.id }.distinct().size == this.steps.size)
        require(this.steps.filterIsInstance<TerminalPtyStep.Input>().sumOf { it.size } <= 262144)
        val requests = mutableListOf<String>()
        val inputs = mutableListOf<Long>()
        val controls = mutableListOf<Long>()
        this.steps.forEach { step -> when (step) {
            is TerminalPtyStep.Open -> Unit
            is TerminalPtyStep.Input -> {
                require(step.size in 1..65536); requests.add(step.requestId); inputs.add(step.sequence)
            }
            is TerminalPtyStep.Resize -> {
                require(step.rows in 1..1000 && step.columns in 1..1000)
                requests.add(step.requestId); controls.add(step.sequence)
            }
            is TerminalPtyStep.Interrupt -> { requests.add(step.requestId); controls.add(step.sequence) }
        } }
        require(requests.all { it.matches(TOKEN) } && requests.distinct().size == requests.size)
        require(listOf(inputs, controls).all { domain -> domain.all { it >= 0 } && domain.zipWithNext().all { (a, b) -> a < b } })
        require(this.reads.size <= 64 && this.reads.all { it.id.matches(TOKEN) && it.offset in 0..262144 && it.limit in 1..16384 })
        require(this.reads.map { it.id }.distinct().size == this.reads.size)
        if (launch is TerminalPtyLaunch.Linux) require(launch.expected == LinuxGenerationRef(environmentId, generation))
        val backend = when (launch) {
            is TerminalPtyLaunch.Android -> listOf("android", launch.cwd.path) + launch.argv +
                launch.environment.toSortedMap().flatMap { listOf(it.key, it.value) }
            is TerminalPtyLaunch.Linux -> listOf("linux", launch.expected.environmentId, launch.expected.generation,
                launch.tempDirectory.path, launch.hostWorkingDirectory.path) + launch.argv +
                launch.environment.toSortedMap().flatMap { listOf(it.key, it.value) }
        }
        // Separate encodings avoid ambiguous argv/env boundaries. No wildcard target or command grant.
        val launchKey = when (launch) {
            is TerminalPtyLaunch.Android -> digest(digest(*launch.argv.toTypedArray()),
                digest(*launch.environment.toSortedMap().flatMap { listOf(it.key, it.value) }.toTypedArray()))
            is TerminalPtyLaunch.Linux -> digest(digest(*launch.argv.toTypedArray()),
                digest(*launch.environment.toSortedMap().flatMap { listOf(it.key, it.value) }.toTypedArray()))
        }
        val target = digest(conversationId.value, sessionId, runId, environmentId, generation,
            this.journalDirectory.path, sessionScope, rows.toString(), columns.toString(), launchKey, *backend.toTypedArray())
        effects = Collections.unmodifiableMap(this.steps.associate { step ->
            val operation: String
            val change: String
            when (step) {
                is TerminalPtyStep.Open -> { operation = "open"; change = "persistent-pty:$rows:$columns" }
                is TerminalPtyStep.Input -> { operation = "input"; change = "${step.sequence}:" + java.util.Base64.getEncoder().encodeToString(step.bytes()) }
                is TerminalPtyStep.Resize -> { operation = "resize"; change = "${step.sequence}:${step.rows}:${step.columns}" }
                is TerminalPtyStep.Interrupt -> { operation = "interrupt"; change = step.sequence.toString() }
            }
            step.id to WriteEffect("terminal.pty", operation, target, change)
        })
    }
    companion object {
        private val TOKEN = Regex("[A-Za-z0-9_-]{1,128}")
        private fun digest(vararg values: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
            values.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                hash.update("${bytes.size}:".toByteArray(Charsets.US_ASCII)); hash.update(bytes)
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

internal interface TerminalPtyResource : AutoCloseable {
    fun awaitTermination(timeoutMillis: Long): Boolean
}
internal interface TerminalPtyConnection : TerminalPtyResource {
    fun read(authorization: TaskAuthorization, offset: Int, limit: Int): JSONObject
    fun write(authorization: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray): JSONObject
    fun resize(authorization: TaskAuthorization, requestId: String, sequence: Long, rows: Int, columns: Int): JSONObject
    fun interrupt(authorization: TaskAuthorization, requestId: String, sequence: Long): JSONObject
}

/** Owns successful, late, and failed-start resources until an explicit worker join proves termination. */
internal class CatalogTerminalBinding private constructor(
    val plan: TerminalPtyPlan,
    val authorization: TaskAuthorization,
    private val openConnection: () -> TerminalPtyConnection,
) : AutoCloseable {
    private val lock = Any()
    private var owner: RunExecutionOwner? = null
    private var connection: TerminalPtyConnection? = null
    private var resource: TerminalPtyResource? = null
    private var closed = false
    private var uncertain = false
    private var active = false
    private var cancellationChecks = 0
    private var nextStep = 0
    private var joining = false
    private var drained = false

    fun bind(owner: RunExecutionOwner, capabilities: SessionCapabilities) = synchronized(lock) {
        check(!closed) { "terminal_run_closed" }
        require(owner.conversationId == plan.conversationId && owner.runId.value == plan.runId && capabilities.sessionId == plan.sessionId) {
            "tool_run_owner_mismatch"
        }
        check(this.owner == null || this.owner === owner) { "tool_owner_already_bound" }
        this.owner = owner
    }

    /** Pure, including on replay: no ledger access, opening, probing, sequence allocation or mutation. */
    fun admit(context: ToolExecutionContext, toolName: String, schema: ToolSchema, arguments: ToolArguments): ExecutionReceipt.NotExecuted? = synchronized(lock) {
        when {
            owner == null -> ExecutionReceipt.NotExecuted("terminal_owner_not_bound")
            context.owner !== owner || context.owner.conversationId != plan.conversationId || context.owner.runId.value != plan.runId ->
                ExecutionReceipt.NotExecuted("tool_run_owner_mismatch")
            context.authorization !== authorization -> ExecutionReceipt.NotExecuted("tool_run_authorization_mismatch")
            context.invocation.name != toolName -> ExecutionReceipt.NotExecuted("tool_invocation_mismatch")
            closed -> ExecutionReceipt.NotExecuted("terminal_run_closed")
            else -> {
                val actual = try { schema.parse(context.invocation.arguments) } catch (_: Exception) { return@synchronized ExecutionReceipt.NotExecuted("invalid_arguments") }
                if (actual.canonicalJson() != arguments.canonicalJson()) ExecutionReceipt.NotExecuted("tool_invocation_mismatch") else null
            }
        }
    }

    /** The caller's callback may block or throw. Count it for drain, but never hold our lock. */
    private fun evaluateCancellation(context: ToolExecutionContext): Boolean {
        synchronized(lock) {
            if (closed) return true
            cancellationChecks++
        }
        return try { context.cancelled() }
        finally { synchronized(lock) { cancellationChecks-- } }
    }

    /** Called under lock AFTER the external cancellation check; admission is not a claim. */
    private fun executionDenialLocked(context: ToolExecutionContext, cancelled: Boolean): ExecutionReceipt.NotExecuted? = when {
        closed || cancelled -> ExecutionReceipt.NotExecuted("terminal_run_closed")
        owner == null -> ExecutionReceipt.NotExecuted("terminal_owner_not_bound")
        context.owner !== owner -> ExecutionReceipt.NotExecuted("tool_run_owner_mismatch")
        context.authorization !== authorization -> ExecutionReceipt.NotExecuted("tool_run_authorization_mismatch")
        else -> null
    }

    fun execute(step: TerminalPtyStep, context: ToolExecutionContext): ExecutionReceipt {
        val cancelled = evaluateCancellation(context)
        val current = synchronized(lock) {
            executionDenialLocked(context, cancelled)?.let { return it }
            if (uncertain) return ExecutionReceipt.Unknown("terminal_outcome_unknown_no_resend")
            if (active) return ExecutionReceipt.NotExecuted("terminal_operation_busy")
            if (plan.steps.getOrNull(nextStep) !== step) return ExecutionReceipt.NotExecuted("terminal_step_out_of_order_or_consumed")
            if (plan.effects.getValue(step.id) !in context.authorization.allowedWrites) return ExecutionReceipt.NotExecuted("task_write_not_authorized")
            active = true
            nextStep++ // claim once, before leaving the short lock, even if startup/transport throws
            connection
        }
        try {
            if (step is TerminalPtyStep.Open) return open()
            val live = current ?: return ExecutionReceipt.NotExecuted("terminal_session_unavailable")
            val response = when (step) {
                is TerminalPtyStep.Input -> live.write(authorization, step.requestId, step.sequence, step.bytes())
                is TerminalPtyStep.Resize -> live.resize(authorization, step.requestId, step.sequence, step.rows, step.columns)
                is TerminalPtyStep.Interrupt -> live.interrupt(authorization, step.requestId, step.sequence)
                is TerminalPtyStep.Open -> error("unreachable")
            }
            // A written helper frame is NOT proof of PTY delivery or command completion.
            return if (response.optString("execution") == "not_executed") ExecutionReceipt.NotExecuted(response.optString("error", "terminal_not_executed"))
            else ExecutionReceipt.Unknown("terminal_pty_delivery_and_completion_unconfirmed").also { markOutcomeUnknown() }
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            markOutcomeUnknown()
            return ExecutionReceipt.Unknown("terminal_execution_unconfirmed")
        } finally { synchronized(lock) { active = false } }
    }

    private fun open(): ExecutionReceipt {
        val created = try { openConnection() }
        catch (failure: LinuxPtyStartupFailure) {
            retain(object : TerminalPtyResource {
                override fun close() = failure.cleanup.close()
                override fun awaitTermination(timeoutMillis: Long) = failure.cleanup.awaitTermination(timeoutMillis)
            }); markOutcomeUnknown(); return ExecutionReceipt.Unknown("terminal_startup_unconfirmed")
        } catch (failure: PtyStartupFailure) {
            retain(object : TerminalPtyResource {
                override fun close() = failure.cleanup.requestClose()
                override fun awaitTermination(timeoutMillis: Long) = failure.cleanup.awaitTermination(timeoutMillis)
            }); markOutcomeUnknown(); return ExecutionReceipt.Unknown("terminal_startup_unconfirmed")
        }
        val closeLate = synchronized(lock) { resource = created; connection = created; closed }
        if (closeLate) requestClose(created)
        return ExecutionReceipt.Confirmed(JSONObject().put("pty", true).put("persistent_shell", true)
            .put("session_scope", plan.sessionScope).put("generation", plan.generation)
            .put("opened", true).put("command_completion", "unknown").toString())
    }

    private fun retain(cleanup: TerminalPtyResource) {
        synchronized(lock) { resource = cleanup }
        requestClose(cleanup)
    }
    fun read(read: TerminalPtyRead, context: ToolExecutionContext): ExecutionReceipt {
        val cancelled = evaluateCancellation(context)
        val live = synchronized(lock) {
            executionDenialLocked(context, cancelled)?.let { return it }
            if (active) return ExecutionReceipt.NotExecuted("terminal_operation_busy")
            val current = connection ?: return ExecutionReceipt.NotExecuted("terminal_session_unavailable")
            active = true
            current
        }
        return try {
            val response = live.read(authorization, read.offset, read.limit)
            ExecutionReceipt.Confirmed(response.toString(), isError = !response.optBoolean("ok", false))
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            ExecutionReceipt.NotExecuted("terminal_read_unavailable")
        }
        finally { synchronized(lock) { active = false } }
    }
    fun markOutcomeUnknown() = synchronized(lock) { uncertain = true }

    override fun close() {
        val owned = synchronized(lock) { closed = true; resource }
        owned?.let(::requestClose)
    }
    private fun requestClose(owned: TerminalPtyResource) { try { owned.close() } catch (_: Exception) { /* Retain and retry at worker join. */ } }

    /** No callbacks, polling threads, false Drained, lease release on timeout, or forgotten late opens. */
    fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..30000)
        val owned = synchronized(lock) {
            if (drained) return true
            if (!closed || active || cancellationChecks != 0 || joining) return false
            joining = true
            resource
        }
        var complete = false
        try {
            if (owned != null) requestClose(owned)
            if (owned != null && !owned.awaitTermination(timeoutMillis)) return false
            (plan.launch as? TerminalPtyLaunch.Linux)?.release()
            complete = true
            return true
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return false
        } finally {
            synchronized(lock) {
                joining = false
                if (complete) { drained = true; connection = null; resource = null }
            }
        }
    }

    companion object {
        /** Approval is exact and complete; enabling a capability never creates WRITE permission. No I/O here. */
        fun approve(context: Context, plan: TerminalPtyPlan, authorization: TaskAuthorization): CatalogTerminalBinding =
            approved(plan, authorization) {
                val owner = PtyRunOwner(plan.conversationId, authorization, plan.environmentId, plan.generation)
                when (val launch = plan.launch) {
                    is TerminalPtyLaunch.Android -> wrap(AndroidPtyHost.open(context, owner, launch.argv, launch.environment,
                        launch.cwd, plan.journalDirectory, plan.sessionScope, plan.rows, plan.columns))
                    is TerminalPtyLaunch.Linux -> {
                        launch.revalidate()
                        wrap(AndroidPtyHost.openLinux(context, owner, launch.store, launch.expected,
                            launch.tempDirectory, launch.hostWorkingDirectory, plan.journalDirectory, plan.sessionScope, plan.rows, plan.columns))
                    }
                }
            }

        /** Per-instance transport seam, not a production fallback or a second authorization system. */
        internal fun approved(plan: TerminalPtyPlan, authorization: TaskAuthorization, open: () -> TerminalPtyConnection): CatalogTerminalBinding {
            require(authorization.sessionId == plan.sessionId && authorization.taskId == plan.runId) { "tool_run_owner_mismatch" }
            require(plan.effects.values.all { it in authorization.allowedWrites }) { "terminal_exact_plan_approval_required" }
            plan.claim()
            return CatalogTerminalBinding(plan, authorization, open)
        }
        private fun wrap(session: PtyRunSession) = object : TerminalPtyConnection {
            override fun read(authorization: TaskAuthorization, offset: Int, limit: Int) = session.read(authorization, offset, limit)
            override fun write(authorization: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray) = session.write(authorization, requestId, sequence, bytes)
            override fun resize(authorization: TaskAuthorization, requestId: String, sequence: Long, rows: Int, columns: Int) = session.resize(authorization, requestId, sequence, rows, columns)
            override fun interrupt(authorization: TaskAuthorization, requestId: String, sequence: Long) = session.interrupt(authorization, requestId, sequence)
            override fun close() = session.close()
            override fun awaitTermination(timeoutMillis: Long) = session.awaitTermination(timeoutMillis)
        }
        private fun wrap(session: LinuxPtySession) = object : TerminalPtyConnection {
            override fun read(authorization: TaskAuthorization, offset: Int, limit: Int) = session.read(authorization, offset, limit)
            override fun write(authorization: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray) = session.write(authorization, requestId, sequence, bytes)
            override fun resize(authorization: TaskAuthorization, requestId: String, sequence: Long, rows: Int, columns: Int) = session.resize(authorization, requestId, sequence, rows, columns)
            override fun interrupt(authorization: TaskAuthorization, requestId: String, sequence: Long) = session.interrupt(authorization, requestId, sequence)
            override fun close() = session.close()
            override fun awaitTermination(timeoutMillis: Long) = session.awaitTermination(timeoutMillis)
        }
    }
}
