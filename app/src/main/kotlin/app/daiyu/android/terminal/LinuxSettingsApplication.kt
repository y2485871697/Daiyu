package app.daiyu.android.terminal

import android.content.Context
import android.os.Looper
import app.daiyu.android.integration.tools.TerminalPtyLaunch
import app.daiyu.android.domain.ConversationId
import java.io.File

/**
 * One instance per application lifetime, created BEFORE publishing the application's terminal.
 * Loading is suspend/IO and may fail; failure must not be disguised as an empty trusted catalog.
 * Never create this on page entry, Activity recreation, or in order to escape an owned/UNKNOWN run.
 */
class LinuxSettingsApplication private constructor(
    val terminalOwner: TerminalUiOwner,
    val settings: LinuxSettingsController,
    private val admission: LinuxSettingsAdmission,
    private val terminalSelection: TerminalTrustedLinuxSelection,
) : AutoCloseable {
    /**
     * All parent Linux open callbacks must use this entry, not terminalOwner.openLinux directly.
     * The admission lock spans the owner's check AND host admission, so a concurrent revocation
     * cannot return and then admit a previously checked open. Already admitted runs retain leases.
     */
    fun openLinux(reference: LinuxGenerationRef, conversationId: ConversationId? = null): Boolean =
        LinuxRuntimePlatform.unavailableReason() == null &&
            admission.admit(reference) { terminalOwner.openLinux(reference, conversationId) }

    /**
     * Worker-only, non-starting chat approval seam. Uses the SAME startup trust snapshot and
     * selection directory as the terminal owner. Does not select, create directories, install,
     * download, or fall back. Both directories must already exist. Caller owns the returned
     * unclaimed lease until it hands the launch to a preparation; close it on construction failure.
     */
    internal fun freezeTerminalLaunchOnWorker(
        reference: LinuxGenerationRef,
        tempDirectory: File,
        hostWorkingDirectory: File,
    ): TerminalPtyLaunch.Linux {
        check(Looper.myLooper() != Looper.getMainLooper()) { "terminal_freeze_requires_worker" }
        LinuxRuntimePlatform.requireSupported()
        return admission.freeze(reference) {
            terminalSelection.withSelection(reference) { store ->
                TerminalPtyLaunch.Linux.freeze(store, reference, tempDirectory, hostWorkingDirectory)
            }
        }
    }

    /** Real application teardown only. A settings page must only close its own page handle. */
    override fun close() {
        settings.close()
        terminalOwner.close()
    }

    companion object {
        suspend fun android(
            context: Context,
            catalog: TrustedLinuxCatalog = TrustedLinuxCatalog(File(context.filesDir, "trusted-linux-catalog")),
        ): LinuxSettingsApplication {
            val snapshot = catalog.load()
            val supported = LinuxRuntimePlatform.unavailableReason() == null
            val admission = LinuxSettingsAdmission(if (supported)
                snapshot.map { LinuxGenerationRef(it.environmentId, it.generation) }.toSet() else emptySet())
            // Host metadata is fixed for this entire owner lifetime. New approvals never replace it.
            val owner = TerminalUiOwner(TerminalEnvironmentHost.android(context.applicationContext, snapshot), admission::current)
            val access = LinuxSettingsCatalogAdapter(catalog) { LinuxRuntimePlatform.requireSupported(); Unit }
            val installation = app.daiyu.android.terminal.install.LinuxInstallCoordinator.android(context, access)
            val settings = LinuxSettingsController(access, admission, startupEntries = snapshot, installation = installation)
            val selection = TerminalTrustedLinuxSelection(File(context.filesDir, "user-terminal/linux-selection"), snapshot)
            return LinuxSettingsApplication(owner, settings, admission, selection)
        }
    }
}

/** A monotone, process-local denial set. Persistence failure never restores a revoked capability. */
internal class LinuxSettingsAdmission(snapshot: Set<LinuxGenerationRef>) {
    private val lock = Any()
    @Volatile private var allowed = snapshot.toSet()

    // Lock-free immutable snapshot avoids reversing admission -> TerminalUiOwner's lock order.
    fun current(): Set<LinuxGenerationRef> = allowed.toSet()
    fun contains(reference: LinuxGenerationRef): Boolean = reference in allowed
    /** Denial cannot return while a new approval is acquiring its pin. Existing pins remain owned. */
    fun <T> freeze(reference: LinuxGenerationRef, action: () -> T): T = synchronized(lock) {
        check(reference in allowed) { "linux_trusted_installation_unavailable" }
        action()
    }
    fun deny(reference: LinuxGenerationRef) = synchronized(lock) { allowed = allowed - reference }
    fun admit(reference: LinuxGenerationRef, action: () -> Boolean): Boolean = synchronized(lock) {
        reference in allowed && action()
    }
}
