package app.daiyu.android.backup

import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The container carries only named payloads; archive names never become destination paths. */
internal object FullBackupArchive {
    const val MAX_ARCHIVE = 34L * 1024 * 1024 * 1024
    private const val MAX_DATA = 112L * 1024 * 1024
    private const val MAX_LINUX = 32L * 1024 * 1024 * 1024
    private const val FORMAT = "daiyu-full-backup"
    private val names = setOf("manifest.json", "data.json", "linux.rootfs.gz")

    data class Part(val bytes: Long, val sha256: String) {
        fun json() = JSONObject().put("bytes", bytes).put("sha256", sha256)
    }
    class Prepared internal constructor(val directory: File, val data: File, val linux: File,
        val manifest: JSONObject) : Closeable {
        val id: String = digest(manifest.toString().toByteArray(Charsets.UTF_8))
        fun writeTo(target: OutputStream, progress: (Long) -> Unit = {}) {
            var written = 0L
            ZipOutputStream(target).use { zip ->
                fun entry(name: String, open: () -> InputStream) {
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    open().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(count > 0)
                            zip.write(buffer, 0, count); written += count; progress(written)
                        }
                    }
                    zip.closeEntry()
                }
                entry("manifest.json") { manifest.toString().byteInputStream(Charsets.UTF_8) }
                entry("data.json") { data.inputStream() }
                // The Linux part is already gzip compressed. Avoid a second expensive compression pass.
                zip.setLevel(0)
                entry("linux.rootfs.gz") { linux.inputStream() }
                zip.finish()
            }
        }
        override fun close() { check(directory.deleteRecursively()) { "backup_archive_cleanup" } }
    }

    fun prepare(parent: File, data: File, linux: File, metadata: JSONObject): Prepared {
        val dir = staging(parent)
        try {
            val dataCopy = File(dir, "data"); val linuxCopy = File(dir, "linux")
            val dataPart = copy(data.inputStream(), dataCopy, MAX_DATA)
            val linuxPart = copy(linux.inputStream(), linuxCopy, MAX_LINUX)
            val manifest = JSONObject().put("format", FORMAT).put("version", 1)
                .put("data", dataPart.json()).put("linux", linuxPart.json()).put("environment", metadata)
            validateManifest(manifest)
            return Prepared(dir, dataCopy, linuxCopy, manifest)
        } catch (failure: Throwable) { dir.deleteRecursively(); throw failure }
    }

    fun recognizes(file: File): Boolean = try { ZipFile(file).use { zip ->
        val entry = zip.getEntry("manifest.json") ?: return@use false
        val bytes = zip.getInputStream(entry).use { readBounded(it, 16384) }
        runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).optString("format") == FORMAT }.getOrDefault(false)
    } } catch (_: java.util.zip.ZipException) { false }

    /** All content is verified before the caller can publish data or invoke a privileged restorer. */
    fun read(file: File, parent: File, progress: (Long) -> Unit = {}): Prepared {
        val dir = staging(parent)
        try {
            return ZipFile(file).use { zip ->
                val entries = mutableListOf<ZipEntry>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    require(entries.size < names.size) { "backup_archive_entries" }
                    entries += enumeration.nextElement()
                }
                require(entries.size == names.size)
                require(entries.map { it.name }.toSet() == names && entries.none { it.isDirectory }) { "backup_archive_entries" }
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).use {
                    readBounded(it, 16384).toString(Charsets.UTF_8)
                })
                validateManifest(manifest)
                var total = 0L
                fun extract(name: String, key: String, limit: Long): File {
                    val expected = manifest.getJSONObject(key)
                    val destination = File(dir, key)
                    val part = copy(zip.getInputStream(zip.getEntry(name)), destination, limit) { bytes -> progress(total + bytes) }
                    require(part.bytes == expected.getLong("bytes") && part.sha256 == expected.getString("sha256")) {
                        "backup_archive_checksum"
                    }
                    total += part.bytes
                    return destination
                }
                val data = extract("data.json", "data", MAX_DATA)
                val linux = extract("linux.rootfs.gz", "linux", MAX_LINUX)
                Prepared(dir, data, linux, manifest)
            }
        } catch (failure: Throwable) { dir.deleteRecursively(); throw failure }
    }

    private fun validateManifest(value: JSONObject) {
        require(value.keys().asSequence().toSet() == setOf("format", "version", "data", "linux", "environment"))
        require(value.getString("format") == FORMAT && value.getInt("version") == 1)
        listOf("data" to MAX_DATA, "linux" to MAX_LINUX).forEach { (key, limit) ->
            val part = value.getJSONObject(key)
            require(part.keys().asSequence().toSet() == setOf("bytes", "sha256"))
            require(part.getLong("bytes") in 1..limit && part.getString("sha256").matches(Regex("[a-f0-9]{64}")))
        }
        val environment = value.getJSONObject("environment")
        require(environment.keys().asSequence().toSet() == setOf("distribution", "mode", "abi", "host_uid", "host_gid"))
        require(environment.getString("distribution").matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(environment.getString("mode") in setOf("proot", "chroot"))
        require(environment.getString("abi") in setOf("arm64-v8a", "x86_64"))
        require(environment.getLong("host_uid") in 10000..Int.MAX_VALUE.toLong())
        require(environment.getLong("host_gid") in 10000..Int.MAX_VALUE.toLong())
    }

    private fun staging(parent: File): File = File.createTempFile("full-backup-", ".stage", parent).also {
        check(it.delete() && it.mkdir()); check(it.setReadable(false, false) && it.setWritable(false, false) && it.setExecutable(false, false))
        check(it.setReadable(true, true) && it.setWritable(true, true) && it.setExecutable(true, true))
    }
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val bytes = ByteArray(4096)
        while (true) {
            val n = input.read(bytes); if (n < 0) break
            require(n > 0 && result.size() + n <= limit) { "backup_manifest_limit" }
            result.write(bytes, 0, n)
        }
        return result.toByteArray()
    }
    private fun copy(input: InputStream, output: File, limit: Long, progress: (Long) -> Unit = {}): Part {
        val hash = MessageDigest.getInstance("SHA-256"); var total = 0L
        input.use { source -> FileOutputStream(output).use { target ->
            val block = ByteArray(65536)
            while (true) {
                val count = source.read(block); if (count < 0) break
                require(count > 0 && total <= limit - count) { "backup_archive_limit" }
                target.write(block, 0, count); hash.update(block, 0, count); total += count; progress(total)
            }
            target.fd.sync()
        } }
        return Part(total, hex(hash.digest()))
    }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
