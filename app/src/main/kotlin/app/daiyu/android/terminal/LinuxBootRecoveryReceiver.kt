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
    if (!File(context.filesDir, "linux-rootfs-access-v1").isDirectory) return
    try {
        LinuxRootfsBackupAccess.forFiles(context.filesDir) { androidLinuxBootIdentity(context) }.inspectAdmission()
    } catch (_: LinuxRunnerGenerationUnknown) {
        // Expected in the original boot: observing a legacy pin is not termination proof.
    }
}
