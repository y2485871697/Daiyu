package app.daiyu.android.integration.tools

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * App-side durable owner-intent bridge, NOT an owner receipt store or a tool ledger.
 *
 * The host supplies a pre-existing, dedicated 0700 directory in its private storage. POSIX file
 * permissions, file/directory fsync, atomic same-directory rename and advisory file locks are
 * mandatory. Unsupported storage fails closed. Ancestors must not be replaceable by untrusted
 * writers; like OwnerReceiptStore this is not a sandbox against hostile same-UID filesystem races.
 * Do not share this directory with the root owner's receipt store, or delete/rotate its contents.
 *
 * Every mutation appends a checksummed, bounded immutable event. A pending file is created and
 * its directory synced BEFORE writing, so interrupted claims leave evidence. No cleanup, repair,
 * overwrite, retry or reset API exists. Valid pending claims permit only lookup; corrupt pending
 * events make the journal unavailable. All observations, including UNKNOWN, remain in the log.
 *
 * A JVM mutex (shared across instances) encloses opening/closing the OS-lock channel: closing a
 * second channel for the same file must never accidentally drop another thread's POSIX lock.
 * The file lock serializes cooperating processes. No owner IPC occurs under either lock.
 */
internal class R08PersistentJournal internal constructor(
    private val root: Path,
    private val fault: (WritePoint) -> Unit
) : R08VirtualDisplayJournal {
    constructor(trustedPrivateRoot: Path) : this(trustedPrivateRoot, {})

    internal enum class WritePoint {
        PENDING_CREATED, BEFORE_WRITE, BEFORE_FILE_SYNC, FILE_SYNCED,
        BEFORE_RENAME, RENAMED, BEFORE_DIRECTORY_SYNC, DIRECTORY_SYNCED
    }

    private val owner: UserPrincipal
    private val gate: ReentrantLock

    init {
        owner = try {
            if (!root.isAbsolute || root != root.normalize()) throw failure()
            checkAncestors(root)
            if (!Files.isDirectory(root, NOFOLLOW_LINKS) ||
                Files.getPosixFilePermissions(root, NOFOLLOW_LINKS) != DIR_MODE) throw failure()
            Files.getOwner(root, NOFOLLOW_LINKS)
        } catch (_: Exception) {
            throw failure()
        }
        gate = gates.computeIfAbsent(root) { ReentrantLock() }
    }

    override fun toString(): String = "R08PersistentJournal(private)"

    override fun claim(intent: R08VirtualDisplayIntent): R08VirtualDisplayPermit = try {
        validate(intent)
        locked {
            val history = readHistory()
            val existing = history.identities[intent.invocationId]
            if (existing != null) {
                existing.requireIdentity(intent.bindingKey, intent.generation)
                existing.intent?.let { if (it != intent) throw failure() }
                // Includes a central semantic replay with no local claim. It cannot become execution.
                R08VirtualDisplayPermit.LOOKUP_ONLY
            } else {
                if (history.pending) throw failure()
                append(history.next, Event(CLAIM, intent.bindingKey, intent.generation,
                    intent.invocationId, intent, null))
                R08VirtualDisplayPermit.EXECUTE_ONCE
            }
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        R08VirtualDisplayPermit.UNKNOWN
    }

    override fun complete(intent: R08VirtualDisplayIntent, evidence: R08VirtualDisplayEvidence) {
        guarded {
            validate(intent)
            locked {
                val history = readHistory()
                val existing = history.identities[intent.invocationId] ?: throw failure()
                if (history.pending || existing.intent != intent) throw failure()
                append(history.next, Event(COMPLETE, intent.bindingKey, intent.generation,
                    intent.invocationId, intent, evidence))
            }
        }
    }

    override fun finalOutcome(bindingKey: String, generation: String, invocationId: String,
                              evidence: R08VirtualDisplayEvidence) {
        guarded {
            validateIdentity(bindingKey, generation, invocationId)
            locked {
                val history = readHistory()
                if (history.pending) throw failure()
                history.identities[invocationId]?.requireIdentity(bindingKey, generation)
                // Intentionally has NO intent and NO claim: central replay is observation only.
                append(history.next, Event(FINAL, bindingKey, generation, invocationId, null, evidence))
            }
        }
    }

    private fun <T> guarded(block: () -> T): T = try { block() } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        throw failure()
    }

    private fun <T> locked(block: () -> T): T {
        gate.lockInterruptibly()
        try {
            checkPrivate(root, true)
            val lock = root.resolve(LOCK_NAME)
            val channel = if (!Files.exists(lock, NOFOLLOW_LINKS)) {
                try {
                    FileChannel.open(lock, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        PosixFilePermissions.asFileAttribute(FILE_MODE))
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    checkPrivate(lock, false)
                    FileChannel.open(lock, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
                }
            } else {
                checkPrivate(lock, false)
                FileChannel.open(lock, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
            }
            channel.use {
                checkPrivate(lock, false)
                channel.lock().use {
                    channel.force(true)
                    syncDirectory(root)
                    // Also cement the supplied root's directory entry (host owns its ancestors).
                    syncDirectory(root.parent ?: throw failure())
                    return block()
                }
            }
        } finally {
            gate.unlock()
        }
    }

    private class Identity(val binding: String, val generation: String,
                           val intent: R08VirtualDisplayIntent?) {
        fun requireIdentity(binding: String, generation: String) {
            if (this.binding != binding || this.generation != generation) throw failure()
        }
        override fun toString() = "R08JournalIdentity(private)"
    }

    private class Event(val kind: Int, val binding: String, val generation: String,
                        val invocation: String, val intent: R08VirtualDisplayIntent?,
                        val evidence: R08VirtualDisplayEvidence?) {
        override fun toString() = "R08JournalEvent(private)"
    }

    private class History(val identities: Map<String, Identity>, val next: Long, val pending: Boolean)

    private fun readHistory(): History {
        val entries = Files.newDirectoryStream(root).use { stream ->
            stream.filter { it.fileName.toString() != LOCK_NAME }.sortedBy { it.fileName.toString() }
        }
        val identities = HashMap<String, Identity>()
        var next = 0L
        var pending = false
        for (path in entries) {
            val name = path.fileName.toString()
            if (!EVENT_NAME.matches(name) || pending || next == Long.MAX_VALUE) throw failure()
            if (name.substring(0, 20) != sequence(next)) throw failure()
            pending = name.endsWith(".pending")
            checkPrivate(path, false)
            val event = decode(readBounded(path))
            val old = identities[event.invocation]
            old?.requireIdentity(event.binding, event.generation)
            when (event.kind) {
                CLAIM -> {
                    // A claim is only legal as the first event for this invocation, even after FINAL.
                    if (old != null) throw failure()
                    identities[event.invocation] = Identity(event.binding, event.generation, event.intent)
                }
                COMPLETE -> if (old?.intent == null || old.intent != event.intent) throw failure()
                FINAL -> if (old == null) {
                    identities[event.invocation] = Identity(event.binding, event.generation, null)
                }
                else -> throw failure()
            }
            next++
        }
        return History(identities, next, pending)
    }

    private fun append(index: Long, event: Event) {
        val bytes = encode(event)
        val target = root.resolve(sequence(index) + ".event")
        val pending = root.resolve(sequence(index) + ".pending")
        // No REPLACE_EXISTING. Under the lock neither name can legitimately exist.
        if (Files.exists(target, NOFOLLOW_LINKS) || Files.exists(pending, NOFOLLOW_LINKS)) throw failure()
        FileChannel.open(pending, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(FILE_MODE)).use { channel ->
            syncDirectory(root)
            fault(WritePoint.PENDING_CREATED)
            fault(WritePoint.BEFORE_WRITE)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            fault(WritePoint.BEFORE_FILE_SYNC)
            channel.force(true)
            fault(WritePoint.FILE_SYNCED)
        }
        fault(WritePoint.BEFORE_RENAME)
        Files.move(pending, target, ATOMIC_MOVE)
        fault(WritePoint.RENAMED)
        fault(WritePoint.BEFORE_DIRECTORY_SYNC)
        syncDirectory(root)
        fault(WritePoint.DIRECTORY_SYNCED)
    }

    private fun checkPrivate(path: Path, directory: Boolean) {
        checkAncestors(path)
        if ((if (directory) !Files.isDirectory(path, NOFOLLOW_LINKS) else !Files.isRegularFile(path, NOFOLLOW_LINKS)) ||
            Files.getOwner(path, NOFOLLOW_LINKS) != owner ||
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != (if (directory) DIR_MODE else FILE_MODE)) throw failure()
    }

    private fun readBounded(path: Path): ByteArray = FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
        val size = channel.size()
        if (size !in 40L..MAX_RECORD_BYTES.toLong()) throw failure()
        val buffer = ByteBuffer.allocate(size.toInt())
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw failure()
        if (channel.read(ByteBuffer.allocate(1)) != -1) throw failure()
        buffer.array()
    }

    companion object {
        private const val MAGIC = 0x52303831 // R081, format version 1
        private const val CLAIM = 1
        private const val COMPLETE = 2
        private const val FINAL = 3
        private const val MAX_PARAMS_BYTES = 32 * 1024
        private const val MAX_INVOCATION_BYTES = 1024
        private const val MAX_RECORD_BYTES = 40 * 1024
        private const val LOCK_NAME = ".r08.lock"
        private val DIR_MODE = PosixFilePermissions.fromString("rwx------")
        private val FILE_MODE = PosixFilePermissions.fromString("rw-------")
        private val HASH = Regex("[0-9a-f]{64}")
        private val GENERATION = Regex("[A-Za-z0-9_-]{1,64}")
        private val EVENT_NAME = Regex("[0-9]{20}\\.(event|pending)")
        private val OPERATIONS = setOf("start", "initialize", "launch", "input", "release")
        // Retained for process lifetime: removing an idle entry races a waiter/new instance.
        private val gates = ConcurrentHashMap<Path, ReentrantLock>()

        private fun failure() = IOException("R08 journal unavailable or conflicting")
        private fun sequence(value: Long) = value.toString().padStart(20, '0')
        private fun checkAncestors(path: Path) {
            var cursor: Path? = path
            while (cursor != null) {
                if (Files.isSymbolicLink(cursor)) throw failure()
                cursor = cursor.parent
            }
        }
        private fun syncDirectory(path: Path) {
            FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { it.force(true) }
        }
        private fun utf8(value: String): ByteArray {
            // Reject malformed UTF-16 rather than silently persisting replacement characters.
            val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        private fun validateIdentity(binding: String, generation: String, invocation: String) {
            if (!HASH.matches(binding) || !GENERATION.matches(generation) || invocation.isBlank() ||
                invocation.length > MAX_INVOCATION_BYTES || invocation.any { it.code < 32 || it.code == 127 } ||
                utf8(invocation).size > MAX_INVOCATION_BYTES) throw failure()
        }
        private fun validate(intent: R08VirtualDisplayIntent) {
            validateIdentity(intent.bindingKey, intent.generation, intent.invocationId)
            if (!HASH.matches(intent.operationId) || intent.operation !in OPERATIONS ||
                intent.canonicalParameters.isEmpty() || intent.canonicalParameters.length > MAX_PARAMS_BYTES ||
                utf8(intent.canonicalParameters).size > MAX_PARAMS_BYTES) throw failure()
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in listOf(intent.bindingKey, intent.generation, intent.invocationId)) {
                val bytes = utf8(part)
                digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
                digest.update(bytes)
            }
            val expected = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (intent.operationId != expected) throw failure()
        }
        private fun writeString(out: DataOutputStream, value: String) {
            val bytes = utf8(value)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        private fun readString(input: DataInputStream, limit: Int): String {
            val size = input.readInt()
            if (size < 0 || size > limit || size > input.available()) throw failure()
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
        private fun encode(event: Event): ByteArray {
            val body = ByteArrayOutputStream()
            DataOutputStream(body).use { out ->
                out.writeInt(MAGIC)
                out.writeByte(event.kind)
                writeString(out, event.binding)
                writeString(out, event.generation)
                writeString(out, event.invocation)
                if (event.kind != FINAL) {
                    val intent = event.intent ?: throw failure()
                    writeString(out, intent.operationId)
                    writeString(out, intent.operation)
                    writeString(out, intent.canonicalParameters)
                }
                if (event.kind != CLAIM) out.writeByte(event.evidence?.ordinal ?: throw failure())
            }
            val payload = body.toByteArray()
            val bytes = payload + MessageDigest.getInstance("SHA-256").digest(payload)
            if (bytes.size > MAX_RECORD_BYTES) throw failure()
            return bytes
        }
        private fun decode(bytes: ByteArray): Event {
            val body = bytes.copyOfRange(0, bytes.size - 32)
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body),
                    bytes.copyOfRange(bytes.size - 32, bytes.size))) throw failure()
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != MAGIC) throw failure()
            val kind = input.readUnsignedByte()
            if (kind !in CLAIM..FINAL) throw failure()
            val binding = readString(input, 64)
            val generation = readString(input, 64)
            val invocation = readString(input, MAX_INVOCATION_BYTES)
            validateIdentity(binding, generation, invocation)
            val intent = if (kind != FINAL) R08VirtualDisplayIntent(binding, generation, invocation,
                readString(input, 64), readString(input, 16), readString(input, MAX_PARAMS_BYTES)).also { validate(it) } else null
            val evidence = if (kind != CLAIM) {
                R08VirtualDisplayEvidence.entries.getOrNull(input.readUnsignedByte()) ?: throw failure()
            } else null
            if (input.available() != 0) throw failure()
            return Event(kind, binding, generation, invocation, intent, evidence)
        }
    }
}
