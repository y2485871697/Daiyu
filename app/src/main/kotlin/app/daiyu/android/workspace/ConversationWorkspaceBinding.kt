package app.daiyu.android.workspace

import java.io.File

/** A conversation's explicit workspace choice; it does not select a global Linux environment. */
internal sealed interface ConversationWorkspaceBinding {
    data class Private(val path: String? = null) : ConversationWorkspaceBinding

    data class Rootfs(
        val environmentId: String,
        val generation: String,
        val guestPath: String,
    ) : ConversationWorkspaceBinding
}

/** Supplied by the caller only after approving this exact environment and generation. */
internal data class WorkspaceRootfsRoot(
    val environmentId: String,
    val generation: String,
    val rootfs: File,
)

internal data class ResolvedConversationWorkspace(
    val binding: ConversationWorkspaceBinding,
    val hostDirectory: File,
    val guestDirectory: String?,
    val rootfs: WorkspaceRootfsRoot?,
)
