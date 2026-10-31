package app.daiyu.android.backup

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** One read of an untrusted provider, closed before any database or asset publication. */
internal class BackupImportSource private constructor(internal val file: File, val kind: Kind) {
    enum class Kind { COMPLETE, TEXT, ARCHIVE }
    fun open(): InputStream = file.inputStream()
    fun delete(): Boolean = !file.exists() || file.delete()

    companion object {
        private const val MAX_BYTES = 112L * 1024 * 1024
        fun prepare(parent: File, open: () -> InputStream): BackupImportSource =
            prepare(parent, open, onBytesRead = {})

        fun prepare(parent: File, open: () -> InputStream, onBytesRead: (Long) -> Unit): BackupImportSource {
            val file = File.createTempFile("backup-import-", ".stage", parent)
            try {
                onBytesRead(0L)
                open().use { raw ->
                    val source = java.io.PushbackInputStream(raw, 4)
                    val prefix = ByteArray(4)
                    var prefixSize = 0
                    while (prefixSize < prefix.size) {
                        val count = source.read(prefix, prefixSize, prefix.size - prefixSize)
                        if (count < 0) break
                        require(count > 0)
                        prefixSize += count
                    }
                    source.unread(prefix, 0, prefixSize)
                    val limit = if (prefixSize >= 2 && prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte())
                        FullBackupArchive.MAX_ARCHIVE else MAX_BYTES
                    FileOutputStream(file).use { target ->
                        val block = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = source.read(block)
                            if (count < 0) break
                            if (count > 0) onBytesRead(total + count)
                            require(count > 0 && total + count <= limit) { "备份文件超出大小限制或无法读取" }
                            target.write(block, 0, count); total += count
                        }
                    }
                }
                val zip = file.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
                val kind = if (zip) { require(FullBackupArchive.recognizes(file)) { "不支持的备份格式" }; Kind.ARCHIVE } else when (file.inputStream().use { BackupPackageSpool.detectFormat(it) }) {
                    "daiyu-neutral-complete" -> Kind.COMPLETE
                    "daiyu-neutral-text" -> Kind.TEXT
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
