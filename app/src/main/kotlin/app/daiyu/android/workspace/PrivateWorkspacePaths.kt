package app.daiyu.android.workspace

import java.io.File
import java.nio.file.Files

/** Fixed app-owned storage, not a SAF grant or an implicit Linux mount. Construction is I/O-free. */
internal class PrivateWorkspacePaths(private val filesDir: File) {
    internal val storage: File get() = filesDir.absoluteFile
    val root: File get() = File(filesDir, "workspace")

    /** Called on the owning worker. Existing home files are neither moved nor deleted. */
    fun ensureRoot(): File {
        val parent = filesDir.canonicalFile
        check(parent.isDirectory) { "workspace_storage_unavailable" }
        val directory = File(parent, "workspace")
        check(!Files.isSymbolicLink(directory.toPath())) { "workspace_root_changed" }
        check(directory.isDirectory || directory.mkdir()) { "workspace_storage_unavailable" }
        check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile == directory) { "workspace_root_changed" }
        return directory
    }
}
