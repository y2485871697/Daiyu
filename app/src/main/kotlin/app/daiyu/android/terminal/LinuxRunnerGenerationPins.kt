package app.daiyu.android.terminal

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

internal class LinuxRunnerGenerationUnknown : IllegalStateException("linux_runner_generation_unknown")

/** Process death releases the OS lock, never an execution pin. Only the original drain can settle it. */
internal class LinuxRunnerGenerationPins(
    directory: File,
    private val fileKey: (java.nio.file.Path) -> Any? = ::strictLinuxFileKey,
    private val directorySync: (File) -> Unit,
) {
    private val requestedDirectory = directory.absoluteFile
    private val directory = directory.canonicalFile
    private val guard = synchronized(guards) { guards.getOrPut(this.directory.path) { Guard() } }
    private class Guard {
        var rootKey: Any? = null
        var lockKey: Any? = null
        val known = mutableMapOf<String, KnownPin>()
        val unsettled = mutableSetOf<String>()
    }

    private data class KnownPin(val bytes: ByteArray, val key: Any)

    fun <T> admitted(requireEmpty: Boolean = false, action: () -> T): T = exclusive {
        val files = checkNotNull(directory.listFiles()) { "linux_runner_pin_directory" }
            .filter { it.name.startsWith(PREFIX) }
        if (guard.unsettled.isNotEmpty() || files.size > MAX_PINS ||
            files.map { it.name }.toSet() != guard.known.keys) throw LinuxRunnerGenerationUnknown()
        files.forEach { file ->
            val known = checkNotNull(guard.known[file.name])
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > MAX_PIN_BYTES ||
                fileKey(file.toPath()) != known.key || !file.readBytes().contentEquals(known.bytes))
                throw LinuxRunnerGenerationUnknown()
        }
        check(!requireEmpty || files.isEmpty()) { "linux_environment_in_use" }
        action()
    }

    fun claim(generation: LinuxGenerationRef, identities: List<String>, requireEmpty: Boolean = false,
        stillCurrent: () -> Unit): Pin = admitted(requireEmpty = requireEmpty) {
        check(guard.known.size < MAX_PINS) { "linux_runner_pin_limit" }
        stillCurrent()
        val id = PREFIX + UUID.randomUUID().toString().replace("-", "")
        val identity = MessageDigest.getInstance("SHA-256").digest(identities.joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
        check(!Files.exists(File(directory, id).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "linux_runner_pin_collision" }
        val bytes = "1\n${generation.environmentId}\n${generation.generation}\n$identity\n$id\n".toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_PIN_BYTES)
        // Register in memory only after both file and directory are durable. A failed claim is UNKNOWN.
        TerminalDurability.replace(File(directory, id), bytes, directorySync = directorySync)
        val known = KnownPin(bytes, checkNotNull(fileKey(File(directory, id).toPath())) { "linux_runner_pin_identity" })
        guard.known[id] = known
        Pin(id)
    }

    inner class Pin internal constructor(private val id: String) {
        private val known = checkNotNull(guard.known[id])
        private var settled = false
        /** Caller must hold the original session's positive termination proof, not just request close. */
        fun settleAfterTermination() = exclusive {
            if (settled) return@exclusive
            check(guard.known[id] === known) { "linux_runner_pin_owner" }
            try {
                val file = File(directory, id)
                if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    check(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= MAX_PIN_BYTES &&
                        fileKey(file.toPath()) == known.key && file.readBytes().contentEquals(known.bytes)) { "linux_runner_pin_changed" }
                    Files.delete(file.toPath())
                } else check(id in guard.unsettled) { "linux_runner_pin_missing" }
                // A previous failed sync may already have unlinked the file. Retry the original sync only.
                directorySync(directory)
                guard.known.remove(id)
                guard.unsettled.remove(id)
                settled = true
            } catch (failure: Throwable) {
                guard.unsettled.add(id)
                throw failure
            }
        }
    }

    private fun <T> exclusive(action: () -> T): T = synchronized(guard) {
        if (requestedDirectory.canonicalFile != directory || Files.isSymbolicLink(directory.toPath()))
            throw LinuxRunnerGenerationUnknown()
        val rootKey = fileKey(directory.toPath()) ?: throw LinuxRunnerGenerationUnknown()
        if (guard.rootKey != null && guard.rootKey != rootKey) throw LinuxRunnerGenerationUnknown()
        guard.rootKey = rootKey
        val lockFile = File(directory, ".runner-pins.lock")
        if (Files.isSymbolicLink(lockFile.toPath())) throw LinuxRunnerGenerationUnknown()
        val channel = RandomAccessFile(lockFile, "rw").channel
        try {
            val lockKey = fileKey(lockFile.toPath()) ?: throw LinuxRunnerGenerationUnknown()
            if (guard.lockKey != null && guard.lockKey != lockKey) throw LinuxRunnerGenerationUnknown()
            guard.lockKey = lockKey
            val lock = channel.lock()
            try {
                if (fileKey(directory.toPath()) != rootKey || fileKey(lockFile.toPath()) != lockKey)
                    throw LinuxRunnerGenerationUnknown()
                action()
            } finally { runCatching { lock.release() } }
        } finally { runCatching { channel.close() } }
    }

    companion object {
        private const val PREFIX = ".runner-pin-"
        private const val MAX_PINS = 256
        private const val MAX_PIN_BYTES = 2048L
        private val guards = mutableMapOf<String, Guard>()
    }
}
