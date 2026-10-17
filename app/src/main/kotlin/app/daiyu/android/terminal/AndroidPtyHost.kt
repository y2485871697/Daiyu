package app.daiyu.android.terminal

import android.content.Context
import android.os.Process
import java.io.File

/**
 * Android-only trusted host boundary. Neither Context, owner nor launch values are
 * model arguments: the host must freeze them after central authorization.
 * This object does not issue WRITE grants or register a model-facing tool.
 */
object AndroidPtyHost {
    private const val APPLICATION_ID = "app.daiyu.android"
    private const val HELPER_NAME = "libdaiyu_pty.so"

    /**
     * Resolve only the package-manager-installed helper for this application/UID.
     * No asset extraction, downloads, chmod, PATH search or filesDir fallback.
     * An absent ABI payload/extraction is a pre-start failure, not a shell result.
     */
    @JvmStatic
    fun helper(context: Context): File {
        check(context.packageName == APPLICATION_ID) { "pty_helper_wrong_application" }
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getApplicationInfo(APPLICATION_ID, 0)
        check(installed.packageName == APPLICATION_ID && installed.uid == Process.myUid()) {
            "pty_helper_wrong_application"
        }
        val apk = File(checkNotNull(installed.sourceDir) { "pty_helper_installation_unavailable" }).canonicalFile
        val installation = checkNotNull(apk.parentFile) { "pty_helper_installation_unavailable" }
        val nativePath = installed.nativeLibraryDir
        check(!nativePath.isNullOrBlank()) { "pty_helper_unavailable_for_installed_abi" }
        val nativeDirectory = File(nativePath).canonicalFile
        // For this user-installed app, native payloads must be under the same
        // immutable code installation as base.apk, never in app-writable data.
        check(apk.isFile && !apk.canWrite() && !installation.canWrite() &&
            nativeDirectory != installation && nativeDirectory.toPath().startsWith(installation.toPath())) {
            "pty_helper_untrusted_installation"
        }
        check(nativeDirectory.isDirectory) { "pty_helper_unavailable_for_installed_abi" }
        check(!nativeDirectory.canWrite()) { "pty_helper_untrusted_installation" }
        val expected = File(nativeDirectory, HELPER_NAME)
        val executable = expected.canonicalFile
        check(executable == expected && executable.parentFile == nativeDirectory) {
            "pty_helper_untrusted_installation"
        }
        check(executable.isFile) { "pty_helper_unavailable_for_installed_abi" }
        check(executable.canExecute() && !executable.canWrite()) { "pty_helper_not_executable_or_immutable" }
        return executable
    }

    /**
     * Worker-only actual PTY entrypoint; the helper path is deliberately not an
     * argument. Ownership, durable UNKNOWN/no-replay behavior and startup cleanup
     * stay in PtyRunSession/PtySession. close() only requests shutdown: retain any
     * resource lease until the returned session's awaitTermination() returns true.
     */
    @JvmStatic
    fun open(
        context: Context,
        owner: PtyRunOwner,
        argv: List<String>,
        environment: Map<String, String>,
        cwd: File,
        journalDirectory: File,
        sessionScope: String,
        rows: Int = 24,
        columns: Int = 80,
    ): PtyRunSession = PtyRunSession.open(
        owner, helper(context), argv, environment, cwd, journalDirectory, sessionScope, rows, columns,
    )

    /** Worker-only Linux entry. Keep the result, or LinuxPtyStartupFailure.cleanup,
     * until awaitTermination confirms process exit; closing alone retains its lease. */
    @JvmStatic
    fun openLinux(
        context: Context,
        owner: PtyRunOwner,
        store: LinuxEnvironmentStore,
        expected: LinuxGenerationRef,
        tempDirectory: File,
        hostWorkingDirectory: File,
        journalDirectory: File,
        sessionScope: String,
        rows: Int = 24,
        columns: Int = 80,
    ): LinuxPtySession {
        return store.acquire(expected).use { guard ->
            LinuxRuntimePlatform.requireSupported(guard.executionMode)
            LinuxPtySession.open(
                owner, store, expected, helper(context), tempDirectory, hostWorkingDirectory,
                journalDirectory, sessionScope, rows, columns,
            )
        }
    }

}
