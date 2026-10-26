package app.daiyu.android.workspace

import android.content.Context
import app.daiyu.android.terminal.LinuxGenerationRef
import app.daiyu.android.terminal.LinuxInstallation
import app.daiyu.android.terminal.LinuxSettingsApplication
import app.daiyu.android.terminal.LinuxWorkspaceDirectory
import app.daiyu.android.terminal.TerminalWorkspaceDirectory

internal data class ConversationWorkspaceSource(val id: String, val title: String, val rootPath: String,
    val initialPath: String, val reference: LinuxGenerationRef? = null)
internal data class ConversationWorkspaceEditor(val binding: String?, val sources: List<ConversationWorkspaceSource>,
    val selectedSourceId: String, val path: String)

/** Resolves only the owner-supplied binding; it never changes the global Linux selection. */
internal class ConversationWorkspaceAccess(private val context: Context,
    private val terminal: () -> LinuxSettingsApplication?) {
    private fun roots(): List<LinuxInstallation> = terminal()?.workspaceRootsOnWorker().orEmpty()
    private fun sourceId(root: LinuxInstallation) = "${root.environmentId}:${root.generation}"
    fun editor(raw: String?): ConversationWorkspaceEditor {
        val roots = roots()
        val binding = ConversationWorkspaceCodec.decode(raw)
        val defaultPath = java.io.File(context.filesDir, "workspace").absolutePath
        val sources = listOf(ConversationWorkspaceSource("private", "应用私有目录", context.filesDir.absolutePath, defaultPath)) +
            roots.map { ConversationWorkspaceSource(sourceId(it), "${it.distribution} Rootfs", it.rootfs.absolutePath,
                "/", LinuxGenerationRef(it.environmentId, it.generation)) }
        val source = when (binding) {
            is ConversationWorkspaceBinding.Private -> "private"
            is ConversationWorkspaceBinding.Rootfs -> roots.firstOrNull {
                it.environmentId == binding.environmentId && it.generation == binding.generation
            }?.let(::sourceId) ?: "${binding.environmentId}:${binding.generation}"
        }
        val path = when (binding) {
            is ConversationWorkspaceBinding.Private -> binding.path ?: defaultPath
            is ConversationWorkspaceBinding.Rootfs -> binding.guestPath
        }
        val displayed = if (sources.any { it.id == source }) sources else sources +
            ConversationWorkspaceSource(source, "Rootfs 不可用", "", path)
        return ConversationWorkspaceEditor(raw, displayed, source, path)
    }
    fun binding(sourceId: String, path: String): ConversationWorkspaceBinding {
        val trimmed = path.trim()
        return if (sourceId == "private") {
            val default = java.io.File(context.filesDir, "workspace").absolutePath
            ConversationWorkspaceBinding.Private(trimmed.takeUnless { it == default })
        } else {
            val root = roots().firstOrNull { sourceId(it) == sourceId } ?: error("workspace_rootfs_changed")
            ConversationWorkspaceBinding.Rootfs(root.environmentId, root.generation, trimmed)
        }
    }
    fun resolve(raw: String?): ResolvedConversationWorkspace {
        val binding = ConversationWorkspaceCodec.decode(raw)
        val approved = (binding as? ConversationWorkspaceBinding.Rootfs)?.let { selected ->
            roots().firstOrNull { it.environmentId == selected.environmentId && it.generation == selected.generation }
                ?.let { WorkspaceRootfsRoot(it.environmentId, it.generation, it.rootfs) }
                ?: error("workspace_rootfs_changed")
        }
        return ConversationWorkspaceResolver(context.filesDir).resolve(binding, approved)
    }
    fun freeze(raw: String?): ConversationWorkspaceRun {
        val resolved = resolve(raw)
        val pin = resolved.rootfs?.let {
            checkNotNull(terminal()) { "workspace_rootfs_changed" }.pinWorkspaceRootOnWorker(
                LinuxGenerationRef(it.environmentId, it.generation))
        }
        return try {
            check(resolve(raw).hostDirectory == resolved.hostDirectory) { "workspace_rootfs_changed" }
            ConversationWorkspaceRun(resolved, pin)
        } catch (failure: Throwable) { pin?.close(); throw failure }
    }
}

internal class ConversationWorkspaceRun(val resolved: ResolvedConversationWorkspace,
    private val pin: AutoCloseable?) : AutoCloseable {
    fun linux(reference: LinuxGenerationRef): LinuxWorkspaceDirectory {
        val root = resolved.rootfs
        if (root != null) check(root.environmentId == reference.environmentId && root.generation == reference.generation) {
            "workspace_linux_environment_mismatch"
        }
        return LinuxWorkspaceDirectory(resolved.hostDirectory, resolved.guestDirectory ?: "/workspace", bindWorkspace = root == null)
    }
    fun terminal(reference: LinuxGenerationRef? = null): TerminalWorkspaceDirectory =
        TerminalWorkspaceDirectory(resolved.hostDirectory, reference?.let(::linux), reference)
    private val references = java.util.concurrent.atomic.AtomicInteger(1)
    private val ownerClosed = java.util.concurrent.atomic.AtomicBoolean(false)
    fun retain(): AutoCloseable {
        while (true) {
            val count = references.get()
            check(count > 0) { "workspace_run_closed" }
            if (references.compareAndSet(count, count + 1)) break
        }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) release() }
    }
    private fun release() { if (references.decrementAndGet() == 0) pin?.close() }
    override fun close() { if (ownerClosed.compareAndSet(false, true)) release() }
}
