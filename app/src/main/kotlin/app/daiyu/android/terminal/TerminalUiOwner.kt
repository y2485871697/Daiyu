package app.daiyu.android.terminal

import android.content.Context
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Application-owned UI identity and one retained terminal host. Construction/observation never
 * opens a terminal. Keep this owner above Activities/navigation; do not close it from a Composable,
 * a page's coroutine scope, or Activity.onDestroy(). Only the real owner teardown calls close().
 *
 * [trustedLinux] must read the parent's CURRENT trusted configuration, not the host's cached
 * selection/choices or model arguments. The default denies Linux. The host remains responsible
 * for validating the selected installation and acquiring its lease on its worker.
 * [newId] is a test seam: it must return fresh, globally unique, path-safe IDs of at most 96 chars.
 * All explicit opens of [host] must go through this owner so caller publication stays coherent.
 */
class TerminalUiOwner(
    val host: TerminalEnvironmentHost,
    private val trustedLinux: () -> Set<LinuxGenerationRef> = { emptySet() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private val lock = Any()

    /** Ephemeral UI identity, NOT a persisted conversation or an instruction to create one. */
    val uiSessionId: String = "terminal-ui-${freshId()}"
    private val standaloneConversation = ConversationId(uiSessionId)
    private val mutableCaller = MutableStateFlow(TaskAuthorization(uiSessionId, "terminal-idle"))

    /**
     * Stable idle identity until the first accepted open, then the EXACT grant of the last
     * accepted run. Failure, inspection, navigation and process exit never replace it.
     * These UI grants intentionally have no model-tool WRITE effects.
     */
    val caller: StateFlow<TaskAuthorization> = mutableCaller.asStateFlow()

    /** Explicit user action only. true means host admission, not successful asynchronous startup. */
    fun openAndroid(conversationId: ConversationId? = null, workspace: TerminalWorkspaceDirectory? = null): Boolean = synchronized(lock) {
        open(conversationId, null, workspace)
    }

    /** A stale persisted selection cannot authorize a Linux run without the current parent gate. */
    fun openLinux(reference: LinuxGenerationRef, conversationId: ConversationId? = null,
        workspace: TerminalWorkspaceDirectory? = null): Boolean = synchronized(lock) {
        if (reference !in trustedLinux()) return@synchronized false
        open(conversationId, reference, workspace)
    }

    private fun open(conversationId: ConversationId?, linux: LinuxGenerationRef?, workspace: TerminalWorkspaceDirectory?): Boolean {
        // UNKNOWN delivery and cleanup retain host ownership; neither is a reason to replay/open.
        if (!host.state.value.canOpen) return false
        val runId = freshId()
        val authorization = TaskAuthorization(uiSessionId, "terminal-run-$runId")
        val request = TerminalOpenRequest(
            owner = PtyRunOwner(
                conversationId ?: standaloneConversation,
                authorization,
                linux?.environmentId ?: TerminalEnvironmentHost.ANDROID_ENVIRONMENT,
                linux?.generation ?: TerminalEnvironmentHost.ANDROID_GENERATION,
            ),
            sessionScope = "terminal-$runId",
            linux = linux,
            workspace = workspace,
        )
        if (!host.open(request)) return false
        mutableCaller.value = authorization
        return true
    }

    private fun freshId(): String = newId().also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,96}"))) { "Invalid terminal UI ID" }
    }

    /** Real application-owner teardown only; host keeps its asynchronous cleanup responsibility. */
    override fun close() = synchronized(lock) { host.close() }

    companion object {
        /** No I/O or launch. This fixed trusted configuration is valid for this owner's lifetime. */
        fun android(context: Context, trustedInstallations: List<LinuxInstallation> = emptyList()): TerminalUiOwner {
            val installations = trustedInstallations.map { it.copy(sharedMounts = it.sharedMounts.toMap()) }
            val references = installations.map { LinuxGenerationRef(it.environmentId, it.generation) }.toSet()
            return TerminalUiOwner(TerminalEnvironmentHost.android(context, installations), { references })
        }
    }
}
