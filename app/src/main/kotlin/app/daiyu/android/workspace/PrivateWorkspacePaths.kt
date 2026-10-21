package app.daiyu.android.workspace

import java.io.File
import java.nio.file.Files

/** Fixed app-owned storage, not a SAF grant or an implicit Linux mount. Construction is I/O-free. */
internal class PrivateWorkspacePaths private constructor(private val filesDir: File,
    internal val rootParts: List<String>, private val create: Boolean) {
    constructor(filesDir: File) : this(filesDir, listOf("workspace"), true)
    internal val storage: File get() = filesDir.canonicalFile
    internal val readOnly: Boolean get() = !create
    val root: File get() = rootParts.fold(filesDir) { parent, name -> File(parent, name) }

    /** Called on the owning worker. Existing home files are neither moved nor deleted. */
    fun ensureRoot(): File {
        var directory = filesDir.canonicalFile
        check(directory.isDirectory) { "workspace_storage_unavailable" }
        for (part in rootParts) {
            directory = File(directory, part)
            check(!Files.isSymbolicLink(directory.toPath())) { "workspace_root_changed" }
            check(directory.isDirectory || create && directory.mkdir()) { "workspace_storage_unavailable" }
            check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile == directory) { "workspace_root_changed" }
        }
        return directory
    }

    companion object {
        /** The caller supplies an approved Rootfs; missing directories are never created. */
        fun existingRoot(filesDir: File, root: File): PrivateWorkspacePaths {
            val storage = filesDir.canonicalFile.toPath()
            val selected = root.toPath()
            require(selected.isAbsolute && selected.normalize() == selected && selected.startsWith(storage) && selected != storage) {
                "workspace_private_root_required"
            }
            val parts = storage.relativize(selected).map { it.toString() }
            require(parts.none { it.isBlank() || it == "." || it == ".." || '\\' in it || '\u0000' in it })
            return PrivateWorkspacePaths(filesDir, parts, false)
        }
    }
}
