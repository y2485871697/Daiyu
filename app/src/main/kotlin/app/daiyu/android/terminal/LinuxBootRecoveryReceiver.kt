package app.daiyu.android.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import kotlin.concurrent.thread

/** Upgrade observation runs without opening an Activity or starting a Linux process. */
class LinuxBootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        thread(name = "linux-boot-observation") {
            try { observeExistingLinuxPins(context.applicationContext) }
            catch (_: Exception) { /* A failed observation leaves admission fenced. */ }
            finally { pending.finish() }
        }
    }
}

internal fun observeExistingLinuxPins(context: Context) {
    val files = context.filesDir.canonicalFile
    val boot = androidLinuxBootIdentity(context)
    fun observe(directory: File) {
        if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile) return
        try {
            LinuxRunnerGenerationPins(directory, bootIdentity = { boot },
                directorySync = TerminalDurability::syncDirectory).admitted { }
        } catch (_: Exception) {
            // Each unconfirmed domain stays fenced; other domains still need their boot witness.
        }
    }
    observe(File(files, "linux-rootfs-access-v1"))
    val terminal = File(files, "user-terminal")
    observe(File(terminal, "linux-selection"))
    val generations = File(terminal, "workspace-generations")
    if (generations.isDirectory && generations.canonicalFile == generations.absoluteFile) {
        val stores = checkNotNull(generations.listFiles())
        check(stores.size <= 4096) { "linux_runner_pin_directory_limit" }
        stores.forEach(::observe)
    }
}
