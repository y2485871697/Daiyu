package app.daiyu.android.terminal

import app.daiyu.android.domain.ConversationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

data class TerminalCommandBlock(
    val id: String,
    val command: String,
    val cwd: String,
    val output: String = "",
    val running: Boolean = true,
    val exitCode: Int? = null,
    val truncated: Boolean = false,
    val interrupted: Boolean = false,
)

data class TerminalSessionEntry(
    val id: String,
    val simple: Boolean,
    val linux: LinuxGenerationRef?,
    val label: String,
    val state: TerminalEnvironmentState,
    val cwd: String = "",
    val blocks: List<TerminalCommandBlock> = emptyList(),
    val restarting: Boolean = false,
) {
    val running: Boolean get() = if (simple) blocks.any { it.running } else state.ownsResources
}

data class TerminalWorkspaceState(
    val simple: Boolean = true,
    val linuxSelected: Boolean = false,
    val activeSessionId: String? = null,
    val sessions: List<TerminalSessionEntry> = emptyList(),
    val linux: TerminalLinuxState = TerminalLinuxState(),
    val error: String? = null,
) {
    val active: TerminalSessionEntry? get() = sessions.find { it.id == activeSessionId }
}

/** Sessions retain their original host and exact authorization until cleanup actually finishes. */
class TerminalSessionWorkspace(
    private val initialOwner: TerminalUiOwner,
    private val createOwner: () -> TerminalUiOwner,
    private val admitLinux: (LinuxGenerationRef, () -> Boolean) -> Boolean,
) : AutoCloseable {
    private class Session(val owner: TerminalUiOwner, var entry: TerminalSessionEntry,
        val conversation: ConversationId?, val workspace: TerminalWorkspaceDirectory?,
        val workspacePin: AutoCloseable?) {
        var offset = 0L
        var setup = false
        var ready = false
        var restart = false
        var removed = false
        var pendingCommand: String? = null
        var decoder = TerminalBlockProtocol()
        var observation: Job? = null
        var setupTimeout: Job? = null
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Cleanup survives workspace observation teardown, especially UNKNOWN native ownership.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = linkedMapOf<String, Session>()
    private val retired = mutableSetOf<TerminalUiOwner>()
    private var closed = false
    private val activeByMode = mutableMapOf<Boolean, String>()
    private val environmentByMode = mutableMapOf(true to false, false to false)
    private val mutableState = MutableStateFlow(TerminalWorkspaceState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            initialOwner.host.state.collect { host -> synchronized(lock) {
                if (!closed) publish { it.copy(linux = host.linux) }
            } }
        }
    }

    fun refresh() = initialOwner.host.refreshLinux()

    fun setSimple(simple: Boolean) = synchronized(lock) {
        if (closed || mutableState.value.simple == simple) return@synchronized
        publish { it.copy(simple = simple, activeSessionId = activeByMode[simple],
            linuxSelected = environmentByMode[simple] == true, error = null) }
    }

    fun selectEnvironment(linux: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        val state = mutableState.value
        environmentByMode[state.simple] = linux
        val matching = sessions.values.lastOrNull { it.entry.simple == state.simple &&
            (it.entry.linux != null) == linux && !it.removed && it.entry.state.ownsResources }
        if (matching == null) activeByMode.remove(state.simple) else activeByMode[state.simple] = matching.entry.id
        publish { it.copy(linuxSelected = linux, activeSessionId = matching?.entry?.id, error = null) }
    }

    fun select(id: String) = synchronized(lock) {
        val entry = sessions[id]?.takeUnless { it.removed }?.entry ?: return@synchronized
        if (closed || entry.simple != mutableState.value.simple) return@synchronized
        activeByMode[entry.simple] = id
        environmentByMode[entry.simple] = entry.linux != null
        publish { it.copy(activeSessionId = id, linuxSelected = entry.linux != null, error = null) }
    }

    /** Pin transfers only on true. Retained across stop/restart until session disposal and
     * confirmed host cleanup; false leaves ownership with caller. */
    fun create(conversation: ConversationId? = null, workspace: TerminalWorkspaceDirectory? = null,
        workspacePin: AutoCloseable? = null): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val state = mutableState.value
        val linux = if (state.linuxSelected) workspace?.reference ?: state.linux.selected else null
        if (state.simple && sessions.values.count { it.entry.simple } >= 6) return@synchronized false
        if (state.linuxSelected && (linux == null || (workspace?.reference == null && state.linux.state != "FILES_READY"))) return@synchronized false
        val owner = createOwner()
        val accepted = if (linux == null) owner.openAndroid(conversation, workspace)
            else admitLinux(linux) { owner.openLinux(linux, conversation, workspace) }
        if (!accepted) {
            if (owner !== initialOwner) owner.close()
            publish { it.copy(error = "无法打开终端会话") }
            return@synchronized false
        }
        val id = owner.uiSessionId
        val label = if (linux == null) "Android" else state.linux.distribution?.replaceFirstChar { it.uppercase() } ?: "Linux"
        val session = Session(owner, TerminalSessionEntry(id, state.simple, linux, label, owner.host.state.value), conversation, workspace, workspacePin)
        sessions[id] = session
        activeByMode[state.simple] = id
        publish { it.copy(activeSessionId = id, error = null) }
        session.observation = scope.launch { owner.host.state.collect { update(session, it) } }
        true
    }

    fun send(expectedId: String?, text: String, conversation: ConversationId? = null,
        workspace: TerminalWorkspaceDirectory? = null) = synchronized(lock) {
        if (closed || mutableState.value.activeSessionId != expectedId || text.isEmpty()) return@synchronized
        if (expectedId == null && !create(conversation, workspace)) return@synchronized
        val session = sessions[mutableState.value.activeSessionId] ?: return@synchronized
        if (session.removed || session.restart) return@synchronized
        if (!session.entry.simple) {
            if (session.entry.state.acceptsInput) session.owner.host.send(session.owner.caller.value, text)
        } else if (!session.entry.state.ownsResources) {
            val command = text.trim()
            restart(session.entry.id)
            session.pendingCommand = command.takeIf { it.isNotEmpty() }
        } else if (session.entry.running) {
            session.owner.host.send(session.owner.caller.value, text.trimEnd('\r', '\n') + "\n")
        } else {
            val command = text.trim()
            if (command.isEmpty()) return@synchronized
            if (session.ready) execute(session, command) else session.pendingCommand = command
        }
    }

    fun resize(id: String, rows: Int, columns: Int) = synchronized(lock) {
        val session = sessions[id] ?: return@synchronized
        if (!closed && !session.removed && mutableState.value.activeSessionId == id && !session.entry.simple && session.entry.state.acceptsInput)
            session.owner.host.resize(session.owner.caller.value, rows, columns)
    }

    fun stop(id: String) = synchronized(lock) {
        sessions[id]?.takeUnless { closed || it.removed }?.let {
            if (it.owner.host.requestClose(it.owner.caller.value)) {
                it.entry = it.entry.copy(blocks = it.entry.blocks.map { block ->
                    if (block.running) block.copy(running = false, interrupted = true) else block
                })
                publish { state -> state }
            }
        }
    }

    fun restart(id: String) = synchronized(lock) {
        val session = sessions[id] ?: return@synchronized
        if (closed || session.removed || session.restart) return@synchronized
        session.restart = true
        session.entry = session.entry.copy(restarting = true)
        publish { it }
        if (session.entry.state.ownsResources) session.owner.host.requestClose(session.owner.caller.value)
        else reopen(session)
    }

    fun closeSession(id: String) = synchronized(lock) {
        val session = sessions.remove(id) ?: return@synchronized
        session.removed = true; session.restart = false
        session.setupTimeout?.cancel()
        retired += session.owner
        session.owner.close()
        cleanupScope.launch {
            session.owner.host.state.first { !it.ownsResources }
            session.workspacePin?.close()
            synchronized(lock) { retired.remove(session.owner); session.observation?.cancel() }
        }
        if (activeByMode[session.entry.simple] == id) {
            val next = sessions.values.lastOrNull { it.entry.simple == session.entry.simple }
            if (next == null) activeByMode.remove(session.entry.simple) else {
                activeByMode[session.entry.simple] = next.entry.id
                environmentByMode[session.entry.simple] = next.entry.linux != null
            }
        }
        publish { current ->
            val next = sessions[activeByMode[current.simple]]?.entry
            current.copy(activeSessionId = next?.id,
                linuxSelected = next?.let { it.linux != null } ?: current.linuxSelected)
        }
    }

    private fun update(session: Session, host: TerminalEnvironmentState) = synchronized(lock) {
        if (closed || session.removed) return@synchronized
        session.entry = session.entry.copy(state = host)
        if (session.entry.simple) {
            val index = (session.offset - host.outputStart).coerceIn(0, host.output.length.toLong()).toInt()
            session.decoder.accept(host.output.substring(index)) { event ->
                when (event) {
                    is TerminalBlockProtocol.Event.Ready -> {
                        session.ready = true; session.setupTimeout?.cancel()
                        session.entry = session.entry.copy(cwd = event.cwd)
                    }
                    is TerminalBlockProtocol.Event.Output -> {
                        val blocks = session.entry.blocks.toMutableList()
                        val index = blocks.indexOfLast { it.running }
                        if (index >= 0) {
                            val combined = blocks[index].output + event.text
                            blocks[index] = blocks[index].copy(output = combined.take(64_000),
                                truncated = blocks[index].truncated || combined.length > 64_000)
                        }
                        session.entry = session.entry.copy(blocks = blocks)
                    }
                    is TerminalBlockProtocol.Event.Finished -> {
                        session.entry = session.entry.copy(cwd = event.cwd, blocks = session.entry.blocks.map {
                            if (it.id == event.id) it.copy(running = false, exitCode = event.code) else it
                        })
                    }
                }
            }
            session.offset = host.outputStart + host.output.length
            if (host.acceptsInput && !session.setup) {
                startProtocol(session)
            }
            if (session.ready) session.pendingCommand?.let { session.pendingCommand = null; execute(session, it) }
            if (!host.acceptsInput && host.phase == "UNKNOWN") session.pendingCommand = null
            if (!host.ownsResources) session.entry = session.entry.copy(blocks = session.entry.blocks.map {
                if (it.running) it.copy(running = false, exitCode = host.exitCode) else it
            })
        }
        publish { it }
        if (session.restart && host.canOpen) reopen(session)
    }

    private fun execute(session: Session, command: String) {
        if (!session.entry.state.acceptsInput || session.entry.running) return
        val id = UUID.randomUUID().toString()
        val caller = session.owner.caller.value
        val input = session.owner.host.prepareInput(caller, session.decoder.command(id, command))
        if (input == null) {
            publish { it.copy(error = "terminal_input_or_owner_unavailable") }
            return
        }
        val protocol = session.decoder
        session.entry = session.entry.copy(blocks = (session.entry.blocks + TerminalCommandBlock(id, command, session.entry.cwd)).takeLast(200))
        session.owner.host.submit(caller, input).thenAccept { result -> synchronized(lock) {
            if (!closed && !session.removed && session.decoder === protocol && !result.ok) {
                session.entry = session.entry.copy(blocks = session.entry.blocks.map {
                    if (it.id == id) it.copy(running = false, output = it.output + result.error.orEmpty()) else it
                })
                publish { it.copy(error = result.error) }
            }
        } }
        publish { it }
    }

    private fun startProtocol(session: Session) {
        val caller = session.owner.caller.value
        val protocol = session.decoder
        val input = session.owner.host.prepareInput(caller, protocol.setup(session.entry.cwd))
        session.setup = true
        fun fail(error: String?) {
            session.pendingCommand = null
            session.owner.host.requestClose(caller)
            publish { it.copy(error = error ?: "会话已退出") }
        }
        if (input == null) { fail("terminal_input_or_owner_unavailable"); return }
        session.setupTimeout = scope.launch {
            delay(10_000)
            synchronized(lock) {
                if (!closed && !session.removed && session.decoder === protocol && !session.ready) fail(null)
            }
        }
        session.owner.host.submit(caller, input).thenAccept { result -> synchronized(lock) {
            if (!closed && !session.removed && session.decoder === protocol && !result.ok) {
                session.setupTimeout?.cancel()
                fail(result.error)
            }
        } }
    }

    private fun reopen(session: Session) {
        val oldId = session.entry.id
        val newId = UUID.randomUUID().toString()
        sessions.remove(oldId)
        sessions[newId] = session
        if (activeByMode[session.entry.simple] == oldId || !session.entry.simple) {
            activeByMode[session.entry.simple] = newId
            environmentByMode[session.entry.simple] = session.entry.linux != null
        }
        session.entry = session.entry.copy(id = newId)
        if (mutableState.value.simple == session.entry.simple && activeByMode[session.entry.simple] == newId)
            publish { it.copy(activeSessionId = newId, linuxSelected = session.entry.linux != null) }
        session.restart = false
        session.setupTimeout?.cancel()
        session.ready = false; session.setup = false; session.offset = 0L
        session.decoder = TerminalBlockProtocol()
        val reference = session.entry.linux
        val accepted = if (reference == null) session.owner.openAndroid(session.conversation, session.workspace)
            else admitLinux(reference) { session.owner.openLinux(reference, session.conversation, session.workspace) }
        session.entry = session.entry.copy(restarting = false, state = session.owner.host.state.value)
        publish { it.copy(error = if (accepted) null else "无法重启终端会话") }
    }

    private fun publish(change: (TerminalWorkspaceState) -> TerminalWorkspaceState) {
        mutableState.value = change(mutableState.value).copy(sessions = sessions.values.map { it.entry })
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        sessions.values.forEach { session ->
            session.owner.close()
            cleanupScope.launch {
                session.owner.host.state.first { !it.ownsResources }
                session.workspacePin?.close()
            }
        }
        retired.forEach { it.close() }
        initialOwner.close()
        scope.cancel()
    }
}

/** A per-run delimiter keeps shell output separate from command status without replaying input. */
internal class TerminalBlockProtocol(private val token: String = UUID.randomUUID().toString()) {
    sealed interface Event {
        data class Ready(val cwd: String) : Event
        data class Output(val text: String) : Event
        data class Finished(val id: String, val code: Int?, val cwd: String) : Event
    }
    private val prefix = "\u001b]daiyu-$token;"
    private var pending = ""
    private var ready = false
    fun setup(cwd: String = ""): String = (if (cwd.isEmpty()) "" else "cd '${cwd.replace("'", "'\\''")}'; ") + "exec 9>&1; stty -echo -icanon; PS1= PS2=; export TERM=dumb NO_COLOR=1; printf '\\033]daiyu-$token;ready;%s\\007' \"\$PWD\" >&9\r"
    fun command(id: String, command: String): String =
        "eval '${command.replace("'", "'\\''")}'; printf '\\033]daiyu-$token;done;$id;%s;%s\\007' \"\$?\" \"\$PWD\" >&9\r"
    fun accept(text: String, emit: (Event) -> Unit) {
        pending += text
        while (pending.isNotEmpty()) {
            val start = pending.indexOf(prefix)
            if (start < 0) {
                var keep = minOf(prefix.length - 1, pending.length)
                while (keep > 0 && !pending.endsWith(prefix.take(keep))) keep--
                val output = pending.dropLast(keep)
                if (ready && output.isNotEmpty()) emit(Event.Output(output))
                pending = pending.takeLast(keep)
                return
            }
            if (ready && start > 0) emit(Event.Output(pending.take(start)))
            pending = pending.substring(start)
            val end = pending.indexOf('\u0007')
            if (end < 0) return
            val fields = pending.substring(prefix.length, end).split(';', limit = 4)
            when (fields.firstOrNull()) {
                "ready" -> { ready = true; emit(Event.Ready(fields.drop(1).joinToString(";"))) }
                "done" -> if (fields.size == 4) emit(Event.Finished(fields[1], fields[2].toIntOrNull(), fields[3]))
            }
            pending = pending.substring(end + 1)
        }
    }
}
