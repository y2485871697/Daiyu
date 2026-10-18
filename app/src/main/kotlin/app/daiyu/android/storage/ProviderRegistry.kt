package app.daiyu.android.storage

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class RegisteredProvider(val slotId: String, val name: String, val enabled: Boolean)
internal data class ProviderRegistrySnapshot(
    val revision: Long, val providers: List<RegisteredProvider>, val activeSlotId: String?,
)
internal class StaleProviderRegistryException : IllegalStateException("提供商列表已变化，请重新打开")

/** Non-secret registration metadata. Primary refers to the existing store, never a copy.
 * Atomic replacement covers process crashes, not power-loss durability. An empty registry
 * stays empty; corrupt files never resurrect primary or silently choose another provider.
 */
internal class ProviderRegistry(file: File) {
    private val file = file.canonicalFile
    private val monitor = monitors.computeIfAbsent(this.file.path) { Any() }

    fun snapshot(): ProviderRegistrySnapshot = access { it }
    internal fun <T> access(block: (ProviderRegistrySnapshot) -> T): T = exclusive { block(readOrCreate()) }
    internal fun change(expectedRevision: Long,
        update: (ProviderRegistrySnapshot) -> ProviderRegistrySnapshot,
    ): ProviderRegistrySnapshot = exclusive {
        val old = readOrCreate()
        if (old.revision != expectedRevision) throw StaleProviderRegistryException()
        check(old.revision < Long.MAX_VALUE) { "提供商列表版本已耗尽" }
        val next = freeze(update(old).copy(revision = old.revision + 1))
        validate(next)
        write(next)
        next
    }
    private fun readOrCreate(): ProviderRegistrySnapshot {
        if (file.exists()) return read()
        val initial = freeze(ProviderRegistrySnapshot(0,
            listOf(RegisteredProvider(PRIMARY, "主模型服务商", true)), PRIMARY))
        write(initial)
        return initial
    }
    private fun read(): ProviderRegistrySnapshot {
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "提供商列表不可用" }
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "提供商列表格式无效" }
            val revision = input.readLong()
            val active = input.readUTF().ifEmpty { null }
            val count = input.readInt()
            require(count in 0..MAX_PROVIDERS)
            val providers = List(count) { RegisteredProvider(input.readUTF(), input.readUTF(), input.readBoolean()) }
            require(input.read() == -1) { "提供商列表有尾随数据" }
            return freeze(ProviderRegistrySnapshot(revision, providers, active)).also(::validate)
        }
    }
    private fun write(snapshot: ProviderRegistrySnapshot) {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC); data.writeInt(VERSION); data.writeLong(snapshot.revision)
                data.writeUTF(snapshot.activeSlotId.orEmpty()); data.writeInt(snapshot.providers.size)
                snapshot.providers.forEach { data.writeUTF(it.slotId); data.writeUTF(it.name); data.writeBoolean(it.enabled) }
            }
        }.toByteArray()
        require(bytes.size <= MAX_BYTES)
        val pending = File(file.path + ".pending")
        try {
            FileOutputStream(pending).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // Cleanup cannot turn an already published replacement into a reported failure.
            runCatching { pending.delete() }
        }
    }
    private fun <T> exclusive(block: () -> T): T = synchronized(monitor) {
        val parent = checkNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "提供商列表目录不可用" }
        val paths = heldPaths.get()
        if (file.path in paths) return@synchronized block()
        val channel = RandomAccessFile(File(file.path + ".lock"), "rw").channel
        try {
            val lease = channel.lock()
            paths.add(file.path)
            try { block() } finally { paths.remove(file.path); runCatching { lease.release() } }
        } finally { runCatching { channel.close() } }
    }
    private fun freeze(value: ProviderRegistrySnapshot) = value.copy(
        providers = Collections.unmodifiableList(ArrayList(value.providers)))
    private fun validate(value: ProviderRegistrySnapshot) {
        require(value.revision >= 0 && value.providers.size <= MAX_PROVIDERS)
        require(value.providers.map { it.slotId }.toSet().size == value.providers.size)
        value.providers.forEach { require(validSlotId(it.slotId)); requireValidName(it.name) }
        require(value.activeSlotId == null || value.providers.any { it.slotId == value.activeSlotId && it.enabled }) {
            "活动提供商不存在或已禁用"
        }
    }
    companion object {
        const val PRIMARY = "primary"
        private const val MAGIC = 0x44595052
        private const val VERSION = 1
        private const val MAX_PROVIDERS = 64
        private const val MAX_BYTES = 64 * 1024
        private val monitors = ConcurrentHashMap<String, Any>()
        private val heldPaths = ThreadLocal.withInitial { mutableSetOf<String>() }
        internal fun validSlotId(value: String): Boolean = value == PRIMARY ||
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        internal fun requireValidName(value: String) {
            require(value.isNotBlank() && value == value.trim() && value.length <= 200 &&
                value.none { it.code < 0x20 || it.code == 0x7f }) { "提供商名称无效" }
        }
    }
}
