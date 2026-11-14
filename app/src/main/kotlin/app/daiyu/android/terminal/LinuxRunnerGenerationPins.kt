package app.daiyu.android.terminal

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.Base64

internal class LinuxRunnerGenerationUnknown : IllegalStateException("linux_runner_generation_unknown")

/** Process death is not drain proof. A witnessed kernel reboot ends every process from that boot. */
internal class LinuxRunnerGenerationPins(
    directory: File,
    private val fileKey: (java.nio.file.Path) -> Any? = ::strictLinuxFileKey,
    private val bootIdentity: () -> String? = ::linuxKernelBootIdentity,
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
        var recoverySyncPending = false
    }

    private data class KnownPin(val bytes: ByteArray, val key: Any)

    fun <T> admitted(requireEmpty: Boolean = false, allowRetained: Boolean = false, action: () -> T): T = exclusive {
        if (guard.unsettled.isNotEmpty()) throw LinuxRunnerGenerationUnknown()
        if (guard.recoverySyncPending) { directorySync(directory); guard.recoverySyncPending = false }
        recoverAfterReboot()
        val files = pinFiles()
        if (files.size > MAX_PINS) throw LinuxRunnerGenerationUnknown()
        val retained = files.filter { it.name !in guard.known }
        if (retained.isNotEmpty() && (!allowRetained || requireEmpty)) throw LinuxRunnerGenerationUnknown()
        retained.forEach(::validateRetained)
        files.filter { it.name in guard.known }.forEach { file ->
            val known = checkNotNull(guard.known[file.name])
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > MAX_PIN_BYTES ||
                fileKey(file.toPath()) != known.key || !file.readBytes().contentEquals(known.bytes))
                throw LinuxRunnerGenerationUnknown()
        }
        check(!requireEmpty || files.isEmpty()) { "linux_environment_in_use" }
        action()
    }

    fun claim(generation: LinuxGenerationRef, identities: List<String>, requireEmpty: Boolean = false,
        allowRetained: Boolean = false, stillCurrent: () -> Unit): Pin =
        admitted(requireEmpty = requireEmpty, allowRetained = allowRetained) {
        check(pinFiles().size < MAX_PINS) { "linux_runner_pin_limit" }
        stillCurrent()
        val id = PREFIX + UUID.randomUUID().toString().replace("-", "")
        val identity = MessageDigest.getInstance("SHA-256").digest(identities.joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
        check(!Files.exists(File(directory, id).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "linux_runner_pin_collision" }
        val access = if (requireEmpty) "exclusive" else "shared"
        val bytes = "2\n${generation.environmentId}\n${generation.generation}\n$identity\n$id\n$access\n".toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_PIN_BYTES)
        // Register in memory only after both file and directory are durable. A failed claim is UNKNOWN.
        val known = try {
            TerminalDurability.replace(File(directory, id), bytes, directorySync = directorySync)
            KnownPin(bytes, checkNotNull(fileKey(File(directory, id).toPath())) { "linux_runner_pin_identity" }).also {
                currentBoot()?.let { boot -> observeBoot(File(directory, id), it.bytes, it.key, boot, claimed = true) }
            }
        } catch (failure: Throwable) {
            guard.unsettled.add(id)
            throw failure
        }
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
                Files.deleteIfExists(bootFile(id).toPath())
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

    private fun pinFiles(): List<File> = checkNotNull(directory.listFiles()) { "linux_runner_pin_directory" }
        .filter { it.name.startsWith(PREFIX) }

    /** An unjoined run retains shared occupancy, not ownership or a successful result. */
    private fun validateRetained(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > MAX_PIN_BYTES)
            throw LinuxRunnerGenerationUnknown()
        val bytes = file.readBytes()
        val pin = bytes.toString(Charsets.UTF_8).split('\n')
        if (pin.size != 7 || pin[0] != "2" || pin[4] != file.name || pin[5] != "shared" || pin[6].isNotEmpty() ||
            !file.name.matches(Regex("\\.runner-pin-[0-9a-f]{32}")) ||
            !pin[1].matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
            !pin[2].matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
            !pin[3].matches(Regex("[0-9a-f]{64}"))) throw LinuxRunnerGenerationUnknown()
        val witness = bootFile(file.name)
        if (Files.isSymbolicLink(witness.toPath()) || !witness.isFile || witness.length() > MAX_BOOT_BYTES)
            throw LinuxRunnerGenerationUnknown()
        val record = witness.readText(Charsets.UTF_8).split('\n')
        if (record.size != 7 || record[0] != "2" || record[1] != currentBoot() ||
            !validBoot(record[1]) || record[2] != file.name || record[3] != hash(bytes) ||
            fileKey(file.toPath())?.let(::encodedKey) != record[4] || record[5] != "claim" || record[6].isNotEmpty())
            throw LinuxRunnerGenerationUnknown()
    }

    private fun validBoot(value: String) = value.matches(Regex(
        "kernel:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|android:[0-9]{1,10}"))
    private fun currentBoot(): String? = bootIdentity()?.takeIf(::validBoot)

    private fun bootFile(id: String) = File(directory, BOOT_PREFIX + id.removePrefix(PREFIX))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    // Unix NIO keys include device/inode and have a stable representation across boots.
    private fun encodedKey(key: Any) = Base64.getUrlEncoder().withoutPadding().encodeToString(key.toString().toByteArray(Charsets.UTF_8))

    private fun observeBoot(file: File, bytes: ByteArray, key: Any, boot: String, claimed: Boolean = false) {
        val text = if (claimed) "2\n$boot\n${file.name}\n${hash(bytes)}\n${encodedKey(key)}\nclaim\n"
            else "1\n$boot\n${file.name}\n${hash(bytes)}\n${encodedKey(key)}\n"
        val record = text.toByteArray(Charsets.UTF_8)
        check(record.size <= MAX_BOOT_BYTES) { "linux_runner_boot_record_limit" }
        TerminalDurability.replace(bootFile(file.name), record, directorySync = directorySync)
    }

    private fun previousBoot(previous: String, current: String): Boolean = when {
        previous.startsWith("kernel:") && current.startsWith("kernel:") -> previous != current
        previous.startsWith("android:") && current.startsWith("android:") ->
            previous.removePrefix("android:").toLongOrNull()?.let { old ->
                current.removePrefix("android:").toLongOrNull()?.let { it > old }
            } == true
        else -> false
    }

    private fun recoverySync() {
        guard.recoverySyncPending = true
        directorySync(directory)
        guard.recoverySyncPending = false
    }

    private fun recoverAfterReboot() {
        val files = pinFiles()
        if (files.size > MAX_PINS) throw LinuxRunnerGenerationUnknown()
        // In-process owners still require their original handle, even if a file was replaced.
        guard.known.forEach { (id, known) ->
            val file = File(directory, id)
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > MAX_PIN_BYTES ||
                fileKey(file.toPath()) != known.key || !file.readBytes().contentEquals(known.bytes))
                throw LinuxRunnerGenerationUnknown()
        }
        val observations = checkNotNull(directory.listFiles()) { "linux_runner_pin_directory" }
            .filter { it.name.startsWith(BOOT_PREFIX) }
        if (observations.size > MAX_PINS * 2) throw LinuxRunnerGenerationUnknown()
        observations.filter { it.name.endsWith(".tmp") }.forEach { staging ->
            if (!staging.name.matches(Regex("\\.runner-boot-[0-9a-f]{32}\\.tmp")) ||
                Files.isSymbolicLink(staging.toPath()) || !staging.isFile || staging.length() > MAX_BOOT_BYTES)
                throw LinuxRunnerGenerationUnknown()
            Files.delete(staging.toPath())
            recoverySync()
        }
        val boot = currentBoot() ?: return
        val witnesses = observations.filterNot { it.name.endsWith(".tmp") }
        if (witnesses.size > MAX_PINS) throw LinuxRunnerGenerationUnknown()
        // A witness also fences an interrupted unlink/fsync until that exact cleanup is durable.
        witnesses.filter { PREFIX + it.name.removePrefix(BOOT_PREFIX) !in guard.known }.forEach { witness ->
            if (Files.isSymbolicLink(witness.toPath()) || !witness.isFile || witness.length() > MAX_BOOT_BYTES)
                throw LinuxRunnerGenerationUnknown()
            val lines = witness.readText(Charsets.UTF_8).split('\n')
            val id = PREFIX + witness.name.removePrefix(BOOT_PREFIX)
            val validFormat = (lines.size == 6 && lines[0] == "1" && lines[5].isEmpty()) ||
                (lines.size == 7 && lines[0] == "2" && lines[5] == "claim" && lines[6].isEmpty())
            if (!validFormat || !validBoot(lines[1]) || lines[2] != id ||
                !lines[3].matches(Regex("[0-9a-f]{64}")) || lines[4].isEmpty() ||
                !id.matches(Regex("\\.runner-pin-[0-9a-f]{32}"))) throw LinuxRunnerGenerationUnknown()
            val file = File(directory, id)
            if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                recoverySync()
                Files.delete(witness.toPath())
                recoverySync()
            } else if (previousBoot(lines[1], boot)) {
                if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > MAX_PIN_BYTES ||
                    hash(file.readBytes()) != lines[3] || fileKey(file.toPath())?.let(::encodedKey) != lines[4])
                    throw LinuxRunnerGenerationUnknown()
                Files.delete(file.toPath())
                recoverySync()
                Files.delete(witness.toPath())
                recoverySync()
            }
        }
        pinFiles().filter { it.name !in guard.known && !bootFile(it.name).exists() }.forEach { file ->
            // Legacy pins gain a witness, never a guessed age/PID-based termination proof.
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > MAX_PIN_BYTES)
                throw LinuxRunnerGenerationUnknown()
            val bytes = file.readBytes()
            val lines = bytes.toString(Charsets.UTF_8).split('\n')
            val validFormat = (lines.size == 6 && lines[0] == "1" && lines[5].isEmpty()) ||
                (lines.size == 7 && lines[0] == "2" && lines[5] in setOf("shared", "exclusive") && lines[6].isEmpty())
            if (!validFormat || lines[4] != file.name ||
                !file.name.matches(Regex("\\.runner-pin-[0-9a-f]{32}")) ||
                !lines[1].matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
                !lines[2].matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
                !lines[3].matches(Regex("[0-9a-f]{64}"))) throw LinuxRunnerGenerationUnknown()
            observeBoot(file, bytes, fileKey(file.toPath()) ?: throw LinuxRunnerGenerationUnknown(), boot)
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
        private const val BOOT_PREFIX = ".runner-boot-"
        private const val MAX_BOOT_BYTES = 4096
        private const val MAX_PINS = 256
        private const val MAX_PIN_BYTES = 2048L
        private val guards = mutableMapOf<String, Guard>()
    }
}

internal fun linuxKernelBootIdentity(): String? = try {
    File("/proc/sys/kernel/random/boot_id").readText(Charsets.US_ASCII).trim().let { "kernel:$it" }
} catch (_: Exception) { null }
