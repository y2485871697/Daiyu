package app.daiyu.android.workspace

internal object WorkspaceEntryPolicy {
    private val systemRoots = setOf("bin", "boot", "dev", "etc", "home", "lib", "lib32", "lib64", "libx32",
        "media", "mnt", "opt", "proc", "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var", "lost+found")
    private val userRoots = setOf("home", "root", "opt", "srv", "tmp")
    fun mutable(relative: String, rootfs: Boolean, symbolicLink: Boolean = false): Boolean {
        if (relative.isEmpty() || symbolicLink) return false
        val parts = relative.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || '\\' in it || '\u0000' in it }) return false
        if (!rootfs) return true
        val first = parts.first()
        return first !in systemRoots || parts.size > 1 && first in userRoots
    }
    fun requireName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name &&
            name.none { it.isISOControl() } && !name.startsWith(".workspace-import-") &&
            name.toByteArray(Charsets.UTF_8).size <= 255) { "workspace_invalid_name" }
    }
}
