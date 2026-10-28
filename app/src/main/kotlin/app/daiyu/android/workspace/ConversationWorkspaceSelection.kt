package app.daiyu.android.workspace

import java.io.File

/** Selection is narrower than resolution of existing conversation bindings. */
internal class ConversationWorkspaceSelection(private val filesDir: File) {
    private fun root() = File(filesDir.canonicalFile, "workspace")

    fun binding(sourceId: String, path: String): ConversationWorkspaceBinding.Private {
        require(sourceId == "private") { "workspace_selection_root_required" }
        val root = root()
        val isRoot = path == root.path || path == File(filesDir, "workspace").absolutePath
        val resolved = ConversationWorkspaceResolver(filesDir).resolve(
            ConversationWorkspaceBinding.Private(path.takeUnless { isRoot }))
        val host = resolved.hostDirectory
        require(host.toPath().startsWith(root.toPath())) { "workspace_selection_root_required" }
        return ConversationWorkspaceBinding.Private(host.path.takeUnless { host == root })
    }

    fun parentPath(directory: File): String? = if (directory == root()) null else directory.parentFile.path
}
