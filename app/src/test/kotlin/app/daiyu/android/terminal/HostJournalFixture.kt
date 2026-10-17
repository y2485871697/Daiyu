package app.daiyu.android.terminal

import org.json.JSONObject
import java.io.File

// Windows NIO cannot fsync a directory. Only that acknowledgment is simulated;
// ledger bytes, file fsync, atomic replacement and locks still use the host filesystem.
internal fun hostDirectorySync(directory: File) {
    check(directory.isDirectory)
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        TerminalDurability.syncDirectory(directory)
    }
}

internal fun hostJournal(directory: File, scope: String, limit: Int = 256, binding: JSONObject? = null) =
    OperationJournal(directory, scope, limit, binding, ::hostDirectorySync)


internal fun hostLinuxStore(directory: File) = LinuxEnvironmentStore(directory, ::hostDirectorySync)
internal fun hostLinuxCatalog(directory: File, directorySync: (File) -> Unit = ::hostDirectorySync,
    beforeApprovalGate: () -> Unit = {}) = TrustedLinuxCatalog(directory, directorySync, beforeApprovalGate)
