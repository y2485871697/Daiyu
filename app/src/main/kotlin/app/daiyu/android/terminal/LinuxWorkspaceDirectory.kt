package app.daiyu.android.terminal

import java.io.File
import java.nio.file.Files

/** Trusted host values, immutable and scoped to one launch; never installation metadata. */
data class LinuxWorkspaceDirectory(
    val hostDirectory: File,
    val guestDirectory: String,
    val bindWorkspace: Boolean = false,
)

/** Android uses [hostDirectory], Linux uses [linux]. Resolution/approval belongs to the caller. */
data class TerminalWorkspaceDirectory(
    val hostDirectory: File,
    val linux: LinuxWorkspaceDirectory? = null,
    val reference: LinuxGenerationRef? = null,
) {
    init {
        require(hostDirectory.isAbsolute) { "terminal_workspace_absolute_required" }
        require(linux == null || linux.hostDirectory == hostDirectory) { "terminal_workspace_host_mismatch" }
    }
}

/** No mkdir, selection, mount or installation mutation. Rechecked whenever a lease builds argv. */
internal fun LinuxWorkspaceDirectory.validateFor(installation: LinuxInstallation): List<File> {
    LinuxPathSyntax.host(hostDirectory.path)
    LinuxPathSyntax.guest(guestDirectory)
    require(guestDirectory == "/" || guestDirectory.split('/').drop(1).all { it.isNotEmpty() && it != "." }) {
        "linux_workspace_guest_not_normalized"
    }
    require(hostDirectory == hostDirectory.canonicalFile && hostDirectory.isDirectory) { "linux_workspace_host_invalid" }
    if (bindWorkspace) {
        require(guestDirectory == "/workspace") { "linux_workspace_bind_target_invalid" }
        check(installation.executionMode != LinuxExecutionMode.CHROOT) { "linux_chroot_private_workspace_bind_not_supported" }
        val shell = File(installation.rootfs, "bin/sh").canonicalFile.toPath()
        val target = File(installation.rootfs.canonicalFile, "workspace").toPath()
        require(!shell.startsWith(target)) { "linux_bind_overrides_shell" }
        // The main entry must additionally constrain private binds beneath Context.filesDir.
        require(!Files.isSymbolicLink(hostDirectory.toPath())) { "linux_workspace_symlink" }
        return listOf(hostDirectory)
    }
    val root = installation.rootfs
    require(root == root.canonicalFile && !Files.isSymbolicLink(root.toPath())) { "linux_workspace_root_symlink" }
    val expected = if (guestDirectory == "/") root else File(root, guestDirectory.removePrefix("/"))
    require(expected == hostDirectory) { "linux_workspace_host_guest_mismatch" }
    val paths = mutableListOf(root)
    var cursor = root
    guestDirectory.removePrefix("/").split('/').filter { it.isNotEmpty() }.forEach { part ->
        cursor = File(cursor, part)
        require(!Files.isSymbolicLink(cursor.toPath()) && cursor.isDirectory) { "linux_workspace_symlink_or_missing" }
        paths += cursor
    }
    require(expected.canonicalFile == expected) { "linux_workspace_symlink" }
    // A configured shared bind must not shadow the rootfs directory the caller approved.
    require(installation.sharedMounts.values.none { target ->
        guestDirectory == target || guestDirectory.startsWith(target.trimEnd('/') + "/") ||
            target.startsWith(guestDirectory.trimEnd('/') + "/")
    }) { "linux_workspace_shared_mount_conflict" }
    return paths
}
