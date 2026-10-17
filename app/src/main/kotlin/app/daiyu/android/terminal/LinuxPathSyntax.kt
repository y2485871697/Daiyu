package app.daiyu.android.terminal

import java.io.File

internal object LinuxPathSyntax {
    fun host(path: String) {
        require(path.length in 1..4096 && path.none { it.code < 32 || it.code == 127 })
        val native = File(path).toPath()
        require(native.isAbsolute)
        if (File.separatorChar == '\\') require(native.root.toString().matches(Regex("[A-Za-z]:\\\\")))
        else require(path.startsWith('/') && ':' !in path)
        require(native.none { it.toString().let { name -> name == "." || name == ".." || ':' in name } })
    }
    fun guest(path: String) {
        require(path.length in 1..4096 && path.startsWith('/') && ':' !in path)
        require(path.none { it.code < 32 || it.code == 127 })
        require(path.split('/').none { it == "." || it == ".." })
    }
    fun guestParts(path: String) = path.split('/').filter { it.isNotEmpty() }
    fun contains(parent: List<String>, child: List<String>) = child.take(parent.size) == parent
}
