package app.daiyu.android.terminal

import android.content.Context
import android.os.Looper
import app.daiyu.android.runningmode.AndroidRunningModeStore
import app.daiyu.android.runningmode.RunningMode
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
    private val executionModes: Map<LinuxGenerationRef, LinuxExecutionMode>,
    private val allowChroot: () -> Boolean,
    private val workspaceSnapshot: List<Pair<LinuxInstallation, List<String>>>,
    private val workspaceInspector: LinuxEnvironmentStore,
    private val terminalOwnerFactory: () -> TerminalUiOwner,
) : AutoCloseable {
    val terminalSessions = TerminalSessionWorkspace(terminalOwner,
        createOwner = { terminalOwnerFactory() },
        admitLinux = { reference, open ->
            executionModes[reference]?.let { mode ->
                LinuxRuntimePlatform.unavailableReason(mode) == null &&
                    (mode != LinuxExecutionMode.CHROOT || allowChroot())
            } == true && admission.admit(reference, open)
        })

    /**
     * All parent Linux open callbacks must use this entry, not terminalOwner.openLinux directly.
     * The admission lock spans the owner's check AND host admission, so a concurrent revocation
     * cannot return and then admit a previously checked open. Already admitted runs retain leases.
     */
    fun openLinux(reference: LinuxGenerationRef, conversationId: ConversationId? = null,
        workspace: TerminalWorkspaceDirectory? = null): Boolean =
        executionModes[reference]?.let {
            LinuxRuntimePlatform.unavailableReason(it) == null && (it != LinuxExecutionMode.CHROOT || allowChroot())
        } == true &&
            admission.admit(reference) { terminalOwner.openLinux(reference, conversationId, workspace) }

    /**
     * Worker-only, non-starting chat approval seam. Uses the SAME startup trust snapshot and
     * trust source as the terminal owner. Does not select, create workspace/install directories,
     * download, or fall back; generation pin bookkeeping is separate. Both directories must already exist. Caller owns the returned
     * unclaimed lease until it hands the launch to a preparation; close it on construction failure.
     */
    internal fun freezeTerminalLaunchOnWorker(
        reference: LinuxGenerationRef,
        tempDirectory: File,
        hostWorkingDirectory: File,
        workspace: LinuxWorkspaceDirectory? = null,
    ): TerminalPtyLaunch.Linux {
        check(Looper.myLooper() != Looper.getMainLooper()) { "terminal_freeze_requires_worker" }
        return admission.freeze(reference) {
            requireWorkspaceGenerationUnchanged(reference)
            val mode = checkNotNull(executionModes[reference]) { "linux_trusted_installation_unavailable" }
            check(mode != LinuxExecutionMode.CHROOT || allowChroot()) { "linux_root_mode_required" }
            LinuxRuntimePlatform.requireSupported(mode)
            val freeze: (LinuxEnvironmentStore) -> TerminalPtyLaunch.Linux = { store ->
                TerminalPtyLaunch.Linux.freeze(store, reference, tempDirectory, hostWorkingDirectory, workspace)
            }
            if (workspace == null) terminalSelection.withSelection(reference, freeze)
            else terminalSelection.withInstallation(reference, freeze)
        }
    }

    internal fun freezeSelectedCommandLaunchOnWorker(tempDirectory: File,
        hostWorkingDirectory: File, workspace: LinuxWorkspaceDirectory? = null,
        reference: LinuxGenerationRef? = null): TerminalPtyLaunch.Linux? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "terminal_freeze_requires_worker" }
        val reference = reference ?: terminalSelection.status().selected ?: return null
        return freezeTerminalLaunchOnWorker(reference, tempDirectory, hostWorkingDirectory, workspace)
    }

    internal fun <T> withSelectedRootfsBackup(pack: (LinuxInstallation) -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "linux_backup_worker_required" }
        val reference = terminalSelection.status().selected ?: error("linux_environment_not_selected")
        val handle = admission.freeze(reference) {
            requireWorkspaceGenerationUnchanged(reference)
            val mode = checkNotNull(executionModes[reference])
            check(mode != LinuxExecutionMode.CHROOT || allowChroot()) { "linux_backup_root_required" }
            LinuxRuntimePlatform.requireSupported(mode)
            terminalSelection.acquireSelectedRootfsBackup(reference)
        }
        return handle.use { pack(it.installation) }
    }

    /** Approved startup snapshots only. Pure inspection: no selection, mkdir or installation. */
    internal fun workspaceRootsOnWorker(): List<LinuxInstallation> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "terminal_freeze_requires_worker" }
        val selected = terminalOwner.host.state.value.linux.selected
        return workspaceSnapshot.mapNotNull { (installation, identity) ->
            val ref = LinuxGenerationRef(installation.environmentId, installation.generation)
            try {
                admission.freeze(ref) {
                    if (workspaceInspector.installationIdentity(installation) == identity)
                        installation.copy(sharedMounts = installation.sharedMounts.toMap()) else null
                }
            } catch (_: Exception) { null }
        }.sortedBy { if (LinuxGenerationRef(it.environmentId, it.generation) == selected) 0 else 1 }
    }

    private fun requireWorkspaceGenerationUnchanged(reference: LinuxGenerationRef) {
        val (installation, identity) = workspaceSnapshot.single {
            LinuxGenerationRef(it.first.environmentId, it.first.generation) == reference
        }
        check(workspaceInspector.installationIdentity(installation) == identity) { "linux_installation_changed" }
    }

    /** Non-executing approved-generation pin, independent of global selection.
     * Caller retains this through Android termination. Does not claim root or start Linux. */
    internal fun pinWorkspaceRootOnWorker(reference: LinuxGenerationRef): AutoCloseable {
        check(Looper.myLooper() != Looper.getMainLooper()) { "terminal_freeze_requires_worker" }
        return admission.freeze(reference) {
            requireWorkspaceGenerationUnchanged(reference)
            terminalSelection.withInstallation(reference) { store -> store.acquire(reference) }
        }
    }

    /** Real application teardown only. A settings page must only close its own page handle. */
    override fun close() {
        settings.close()
        terminalSessions.close()
    }

    companion object {
        suspend fun android(
            context: Context,
            catalog: TrustedLinuxCatalog = TrustedLinuxCatalog(File(context.filesDir.canonicalFile, "trusted-linux-catalog"),
                File(context.filesDir.canonicalFile, "linux-chroot-installations")),
        ): LinuxSettingsApplication {
            val runningMode = AndroidRunningModeStore.get(context)
            val allowChroot = { runningMode.mode.value == RunningMode.ROOT }
            val rootfsAccess = LinuxRootfsBackupAccess.forFiles(context.filesDir)
            val snapshot = catalog.load()
            val modes = snapshot.associate { LinuxGenerationRef(it.environmentId, it.generation) to it.executionMode }
            val admission = LinuxSettingsAdmission(snapshot.filter { LinuxRuntimePlatform.unavailableReason(it.executionMode) == null }
                .map { LinuxGenerationRef(it.environmentId, it.generation) }.toSet())
            // Host metadata is fixed for this entire owner lifetime. New approvals never replace it.
            val owner = TerminalUiOwner(TerminalEnvironmentHost.android(context.applicationContext, snapshot), admission::current)
            val access = LinuxSettingsCatalogAdapter(catalog) { mode -> LinuxRuntimePlatform.requireSupported(mode); Unit }
            val installation = app.daiyu.android.terminal.install.LinuxInstallCoordinator.android(context, access)
            val chrootInstallation = app.daiyu.android.terminal.install.LinuxInstallCoordinator.android(context, access, LinuxExecutionMode.CHROOT)
            val settings = LinuxSettingsController(access, admission, startupEntries = snapshot, installation = installation,
                chrootInstallation = chrootInstallation, allowChroot = allowChroot)
            val selection = TerminalTrustedLinuxSelection(File(context.filesDir, "user-terminal/linux-selection"), snapshot,
                chrootRoot = File(context.filesDir.canonicalFile, "linux-chroot-installations"), rootfsAccess = rootfsAccess)
            val inspector = LinuxEnvironmentStore(File(context.filesDir, "user-terminal/linux-selection"),
                TerminalDurability::syncDirectory, ::strictLinuxFileKey,
                File(context.filesDir.canonicalFile, "linux-chroot-installations"), initializeDirectory = false)
            val workspaceSnapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                snapshot.map { it to inspector.installationIdentity(it) }
            }
            return LinuxSettingsApplication(owner, settings, admission, selection, modes, allowChroot, workspaceSnapshot, inspector) {
                TerminalUiOwner(TerminalEnvironmentHost.android(context.applicationContext, snapshot), admission::current)
            }
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
