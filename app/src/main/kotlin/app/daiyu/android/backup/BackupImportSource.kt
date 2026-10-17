package app.daiyu.android.backup

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** One read of an untrusted provider, closed before any database or asset publication. */
internal class BackupImportSource private constructor(private val file: File, val kind: Kind) {
    enum class Kind { COMPLETE, TEXT, LEGACY }
    fun open(): InputStream = file.inputStream()
    fun delete(): Boolean = !file.exists() || file.delete()

    companion object {
        private const val MAX_BYTES = 112L * 1024 * 1024
        fun prepare(parent: File, open: () -> InputStream): BackupImportSource {
            val file = File.createTempFile("backup-import-", ".stage", parent)
            try {
                open().use { source ->
                    FileOutputStream(file).use { target ->
                        val block = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = source.read(block)
                            if (count < 0) break
                            require(count > 0 && total + count <= MAX_BYTES) { "备份文件超出大小限制或无法读取" }
                            target.write(block, 0, count); total += count
                        }
                    }
                }
                val zip = file.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
                val kind = if (zip) Kind.LEGACY else when (file.inputStream().use { BackupPackageSpool.detectFormat(it) }) {
                    "daiyu-neutral-complete" -> Kind.COMPLETE
                    "daiyu-neutral-text" -> Kind.TEXT
                    "eta-backup", "eta-conversation" -> Kind.LEGACY
                    else -> error("不支持的备份格式")
                }
                return BackupImportSource(file, kind)
            } catch (failure: Throwable) {
                if (!file.delete()) failure.addSuppressed(IllegalStateException("备份临时文件清理失败"))
                throw failure
            }
        }
    }
}
