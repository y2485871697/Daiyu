package app.daiyu.android.terminal

import android.content.Context
import app.daiyu.android.runningmode.AndroidRunningModeStore
import app.daiyu.android.runningmode.RunningMode
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Fixed, package-installed host code only. Imported guest programs and helpers are never run. */
internal class LinuxBackupProcess(private val context: Context) {
    private val maxBytes = 32L * 1024 * 1024 * 1024

    fun pack(rootfs: File, mode: LinuxExecutionMode, target: File, progress: (Long) -> Unit) {
        val source = rootfs.canonicalFile
        val stat = android.system.Os.lstat(source.path)
        val args = listOf("--rootfs-pack", source.path, stat.st_dev.toULong().toString(), stat.st_ino.toULong().toString())
        execute(mode, args, transfer = { process ->
            process.outputStream.close()
            FileOutputStream(target).use { raw ->
                GZIPOutputStream(raw, 65536).use { gzip -> copy(process.inputStream, gzip, progress) }
            }
        })
        FileOutputStream(target, true).use { it.fd.sync() }
    }

    fun unpack(archive: File, destination: File, mode: LinuxExecutionMode, sourceUid: Int, sourceGid: Int,
        progress: (Long) -> Unit) {
        val stat = android.system.Os.lstat(destination.path)
        val args = listOf("--rootfs-unpack", destination.path, stat.st_dev.toULong().toString(), stat.st_ino.toULong().toString(),
            sourceUid.toString(), sourceGid.toString(), android.os.Process.myUid().toString(), android.system.Os.getgid().toString())
        execute(mode, args, transfer = { process ->
            process.outputStream.use { target ->
                GZIPInputStream(archive.inputStream(), 65536).use { input -> copy(input, target, progress) }
            }
            val receipt = process.inputStream.bufferedReader(Charsets.US_ASCII).readTextBounded(128)
            check(receipt == "DRFS1 OK\n") { "linux_restore_receipt_missing" }
        })
    }

    fun remove(destination: File, mode: LinuxExecutionMode) {
        val stat = android.system.Os.lstat(destination.path)
        execute(mode, listOf("--rootfs-remove", destination.path, stat.st_dev.toULong().toString(), stat.st_ino.toULong().toString())) { process ->
            process.outputStream.close()
            check(process.inputStream.bufferedReader(Charsets.US_ASCII).readTextBounded(128) == "DRFS1 OK\n")
        }
    }

    private fun execute(mode: LinuxExecutionMode, arguments: List<String>, transfer: (Process) -> Unit) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "linux_backup_worker_required" }
        val native = AndroidPtyHost.helper(context)
        val argv = listOf(native.path) + arguments
        val command = if (mode == LinuxExecutionMode.CHROOT) {
            check(AndroidRunningModeStore.get(context).mode.value == RunningMode.ROOT) { "linux_backup_root_required" }
            RootScopeLaunch.requireTrustedTransport()
            fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
            listOf("/system/bin/su", "-c", "exec " + argv.joinToString(" ") { quote(it) })
        } else argv
        val process = ProcessBuilder(command).directory(context.filesDir).apply {
            environment().clear(); environment()["PATH"] = "/system/bin:/system/xbin"
        }.start()
        // Always drain stderr, but never expose host paths or guest-controlled names in UI errors.
        var diagnostic = ""
        val errors = Thread({ diagnostic = process.errorStream.bufferedReader().use { it.readTextBounded(4096, discardOverflow = true) } },
            "daiyu-rootfs-backup-stderr").apply { isDaemon = true; start() }
        var failure: Throwable? = null
        try { transfer(process) } catch (caught: Throwable) { failure = caught }
        finally {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            // Do not release a rootfs snapshot lease or delete a destination while a helper can still use it.
            var interrupted = false
            while (true) {
                try { process.waitFor(); break } catch (_: InterruptedException) { interrupted = true }
            }
            while (errors.isAlive) try { errors.join() } catch (_: InterruptedException) { interrupted = true }
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure?.let { throw it }
        check(process.exitValue() == 0) {
            when {
                diagnostic.contains("mount") -> "linux_backup_mounted_directory"
                diagnostic.contains("Permission") || diagnostic.contains("permission") -> "linux_backup_permission_denied"
                else -> "linux_backup_native_failed"
            }
        }
    }

    private fun copy(source: InputStream, target: OutputStream, progress: (Long) -> Unit) {
        var total = 0L; val buffer = ByteArray(65536)
        while (true) {
            val count = source.read(buffer); if (count < 0) break
            require(count > 0 && total <= maxBytes - count) { "linux_backup_size_limit" }
            target.write(buffer, 0, count); total += count; progress(total)
        }
    }
    private fun Reader.readTextBounded(limit: Int, discardOverflow: Boolean = false): String {
        val result = StringBuilder(); val buffer = CharArray(1024)
        while (true) {
            val count = read(buffer); if (count < 0) break
            if (discardOverflow) result.append(buffer, 0, minOf(count, (limit - result.length).coerceAtLeast(0)))
            else { check(result.length + count <= limit); result.append(buffer, 0, count) }
        }
        return result.toString()
    }
}
