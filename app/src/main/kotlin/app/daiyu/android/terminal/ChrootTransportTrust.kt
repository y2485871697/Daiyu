package app.daiyu.android.terminal

import android.system.Os
import android.system.OsConstants
import java.io.File

/** Fixed system transports: inspect ownership/mode rather than su-specific access() hooks. */
internal object ChrootTransportTrust {
    internal data class Entry(val uid: Int, val mode: Int, val link: String? = null)

    fun requireTrusted(file: File) {
        require(android.os.Process.myUid() >= 10000)
        requireTrusted(file) { path ->
            val stat = Os.lstat(path.path)
            Entry(stat.st_uid, stat.st_mode,
                if (OsConstants.S_ISLNK(stat.st_mode)) Os.readlink(path.path) else null)
        }
    }

    internal fun requireTrusted(file: File, read: (File) -> Entry) {
        require(file.isAbsolute && file.toPath() == file.toPath().normalize())
        var links = 0
        fun inspect(input: File) {
            val path = input.toPath().normalize()
            require(path.isAbsolute)
            var current = path.root
            val root = read(current.toFile())
            require(root.uid == 0 && root.mode and 0xf000 == 0x4000 && root.mode and 0x12 == 0)
            val parts = path.map { it.toString() }
            for ((index, part) in parts.withIndex()) {
                current = current.resolve(part)
                val entry = read(current.toFile())
                require(entry.uid == 0)
                if (entry.mode and 0xf000 == 0xa000) {
                    require(++links <= 40)
                    val target = File(requireNotNull(entry.link))
                    require(target.path.isNotEmpty() && target.toPath().none { it.toString() in setOf(".", "..") })
                    var resolved = if (target.isAbsolute) target.toPath() else current.parent.resolve(target.path)
                    for (remaining in parts.drop(index + 1)) resolved = resolved.resolve(remaining)
                    inspect(resolved.toFile())
                    return
                }
                require(entry.mode and 0x12 == 0)
                require(entry.mode and 0xf000 == if (index == parts.lastIndex) 0x8000 else 0x4000)
                if (index == parts.lastIndex) require(entry.mode and 0x49 != 0)
            }
            require(parts.isNotEmpty())
        }
        inspect(file)
    }
}
