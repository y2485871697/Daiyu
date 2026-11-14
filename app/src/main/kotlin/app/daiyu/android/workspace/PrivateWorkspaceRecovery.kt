package app.daiyu.android.workspace

import android.content.Context
import android.os.Looper
import android.system.Os
import app.daiyu.android.runningmode.AndroidRunningModeAuthorization
import app.daiyu.android.runningmode.AndroidRunningModeStore
import app.daiyu.android.runningmode.RunningMode
import app.daiyu.android.terminal.AndroidPtyHost
import app.daiyu.android.terminal.ChrootTransportTrust
import java.io.File
import java.util.concurrent.TimeUnit

/** Metadata only, before ordinary App file operations. Never retries an operation or user command. */
internal fun interface PrivateWorkspaceRecovery {
    fun prepare(relative: String, recursive: Boolean, expected: PrivateWorkspaceFiles.Identity?, cancelled: () -> Boolean)

    companion object {
        fun android(context: Context): PrivateWorkspaceRecovery = AndroidPrivateWorkspaceRecovery(context.applicationContext)
    }
}

private class AndroidPrivateWorkspaceRecovery(private val context: Context) : PrivateWorkspaceRecovery {
    override fun prepare(relative: String, recursive: Boolean, expected: PrivateWorkspaceFiles.Identity?, cancelled: () -> Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "workspace_worker_required" }
        active(cancelled)
        require(relative.isEmpty() || (!relative.startsWith('/') && !relative.endsWith('/') &&
            relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && '\u0000' !in it })) {
            "workspace_invalid_path"
        }
        val storage = context.filesDir.toPath().toRealPath().toFile()
        val workspace = File(storage, "workspace")
        // Normal first launch creates the root with App ownership; no Root is requested.
        try { Os.lstat(workspace.path) } catch (failure: android.system.ErrnoException) {
            if (failure.errno == android.system.OsConstants.ENOENT) return
            if (failure.errno != android.system.OsConstants.EACCES) throw failure
        }
        val stat = Os.lstat(storage.path)
        check(stat.st_uid == android.os.Process.myUid()) { "workspace_storage_changed" }
        val helper = AndroidPtyHost.helper(context)
        fun arguments(mode: String) = listOf(helper.path, mode, storage.path,
            stat.st_dev.toULong().toString(), stat.st_ino.toULong().toString(), relative,
            (expected?.device ?: 0).toULong().toString(), (expected?.inode ?: 0).toULong().toString(),
            if (recursive) "1" else "0", "DWSP1", (System.nanoTime() / 1_000_000 + 8_000).toString())
        val checked = execute(arguments("--workspace-check"), cancelled)
        if (checked == 0) return
        check(checked == 77) { "workspace_recovery_rejected" }
        check(AndroidRunningModeStore.get(context).mode.value == RunningMode.ROOT) { "workspace_recovery_root_required" }
        val su = AndroidRunningModeAuthorization.rootExecutable() ?: error("workspace_recovery_root_unavailable")
        val trust = ChrootTransportTrust.identity(File(su))
        active(cancelled)
        check(AndroidRunningModeStore.get(context).mode.value == RunningMode.ROOT &&
            ChrootTransportTrust.identity(File(su)) == trust) { "workspace_recovery_root_unavailable" }
        fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        val repair = listOf(su, "-c", "exec " + arguments("--workspace-repair").joinToString(" ") { quote(it) })
        check(execute(repair, cancelled) == 0) { "workspace_recovery_failed" }
        active(cancelled)
        check(execute(arguments("--workspace-check"), cancelled) == 0) { "workspace_recovery_incomplete" }
    }

    private fun execute(argv: List<String>, cancelled: () -> Boolean): Int {
        active(cancelled)
        val receiptFile = File.createTempFile("workspace-recovery-", ".receipt", context.cacheDir)
        val process = try { ProcessBuilder(argv).directory(context.filesDir)
            .redirectOutput(receiptFile).redirectError(File("/dev/null")).apply {
            environment().clear()
            environment()["PATH"] = "/system/bin:/system/xbin"
        }.start() } catch (failure: Throwable) { receiptFile.delete(); throw failure }
        process.outputStream.close()
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        try {
            // The helper has its own absolute deadline and spawns no children. Do not let
            // cancellation race an unfinished metadata update with the user operation.
            while (process.isAlive && System.nanoTime() < deadline) {
                try { process.waitFor(100, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { interrupted = true }
            }
            if (process.isAlive) {
                process.destroyForcibly()
                while (process.isAlive) try { process.waitFor() } catch (_: InterruptedException) { interrupted = true }
                error("workspace_recovery_timeout")
            }
            val code = process.exitValue()
            val bytes = ByteArray(65)
            var size = 0
            receiptFile.inputStream().use { input ->
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
            }
            val receipt = if (size <= 64) String(bytes, 0, size, Charsets.US_ASCII) else null
            if (code == 0) check(receipt == "DWSP1 OK\n") { "workspace_recovery_receipt_missing" }
            if (interrupted) throw InterruptedException()
            active(cancelled)
            return code
        } finally {
            process.inputStream.close()
            process.errorStream.close()
            receiptFile.delete()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun active(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw InterruptedException()
    }
}
