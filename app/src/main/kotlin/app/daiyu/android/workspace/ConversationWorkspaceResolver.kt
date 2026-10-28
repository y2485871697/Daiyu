package app.daiyu.android.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Resolves only app-private directories. Construction has no I/O; resolve belongs on a worker.
 * No environment lookup, mount, copy, deletion or migration is performed here.
 */
internal class ConversationWorkspaceResolver(private val filesDir: File) {
    fun resolve(
        binding: ConversationWorkspaceBinding,
        approvedRootfs: WorkspaceRootfsRoot? = null,
    ): ResolvedConversationWorkspace = try {
        when (binding) {
            is ConversationWorkspaceBinding.Private -> {
                val host = if (binding.path == null) defaultPrivateRoot() else {
                    require(normalPrivateAbsolutePath(binding.path)) { "workspace_private_root_required" }
                    existingPrivateDirectory(File(binding.path))
                }
                ResolvedConversationWorkspace(binding, host, null, null)
            }
            is ConversationWorkspaceBinding.Rootfs -> {
                check(approvedRootfs != null &&
                    approvedRootfs.environmentId == binding.environmentId &&
                    approvedRootfs.generation == binding.generation) { "workspace_rootfs_changed" }
                require(normalAbsolutePath(binding.guestPath)) { "workspace_path_unavailable" }
                val root = existingPrivateDirectory(approvedRootfs.rootfs)
                val parts = if (binding.guestPath == "/") emptyList() else binding.guestPath.drop(1).split('/')
                val host = descend(root, parts)
                ResolvedConversationWorkspace(binding, host, binding.guestPath, approvedRootfs)
            }
        }
    } catch (failure: IOException) {
        throw IllegalStateException("workspace_path_unavailable", failure)
    } catch (failure: SecurityException) {
        throw IllegalStateException("workspace_path_unavailable", failure)
    }

    private fun defaultPrivateRoot(): File {
        val storage = storageDirectory()
        val expected = File(storage, "workspace")
        rejectSymlink(expected)
        val root = try {
            PrivateWorkspacePaths(filesDir).ensureRoot()
        } catch (failure: IllegalStateException) {
            val message = if (failure.message == "workspace_root_changed") {
                "workspace_symlink_not_supported"
            } else {
                "workspace_path_unavailable"
            }
            throw IllegalStateException(message, failure)
        }
        check(root == expected) { "workspace_path_unavailable" }
        return checkedDirectory(root)
    }

    private fun storageDirectory(): File {
        // Android may supply /data/user/... while its canonical spelling is /data/data/...
        // Only this trusted prefix may be canonicalized before examining individual components.
        val storage = filesDir.canonicalFile
        check(storage.isDirectory) { "workspace_path_unavailable" }
        return storage
    }

    private fun existingPrivateDirectory(selected: File): File {
        require('\u0000' !in selected.path) { "workspace_private_root_required" }
        val path = selected.toPath()
        require(path.isAbsolute && path.normalize() == path) {
            "workspace_private_root_required"
        }
        val storage = storageDirectory()
        val canonicalBase = storage.toPath()
        val suppliedBase = filesDir.absoluteFile.toPath().normalize()
        val base = listOf(canonicalBase, suppliedBase)
            .filter { path.startsWith(it) }
            .maxByOrNull { it.nameCount }
            ?: throw IllegalArgumentException("workspace_private_root_required")
        require(path != base) { "workspace_private_root_required" }
        return descend(storage, base.relativize(path).map { it.toString() })
    }

    private fun descend(root: File, parts: List<String>): File {
        var directory = checkedDirectory(root)
        for (part in parts) {
            // Do not canonicalize an unchecked child: a guest absolute symlink must never
            // become an Android host path, even when its eventual target is app-private.
            require(part.isNotEmpty() && part != "." && part != ".." && '/' !in part && '\u0000' !in part) {
                "workspace_path_unavailable"
            }
            directory = checkedDirectory(File(directory, part))
        }
        return directory
    }

    private fun checkedDirectory(directory: File): File {
        rejectSymlink(directory)
        check(directory.isDirectory) { "workspace_path_unavailable" }
        check(directory.canonicalFile == directory) { "workspace_path_unavailable" }
        rejectSymlink(directory)
        return directory
    }

    private fun rejectSymlink(directory: File) {
        check(!Files.isSymbolicLink(directory.toPath())) { "workspace_symlink_not_supported" }
    }

    private fun normalPrivateAbsolutePath(path: String): Boolean {
        if ('\u0000' in path) return false
        val hostPath = File(path).toPath()
        val root = hostPath.root ?: return false
        val spelling = path.replace(File.separatorChar, '/')
        val prefix = root.toString().replace(File.separatorChar, '/')
        return hostPath.isAbsolute && spelling.startsWith(prefix) &&
            normalAbsolutePath("/" + spelling.substring(prefix.length))
    }

    private fun normalAbsolutePath(path: String): Boolean =
        path.startsWith('/') && '\u0000' !in path &&
            (path == "/" || path.drop(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." })
}
