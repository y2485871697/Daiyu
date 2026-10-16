package app.daiyu.android.terminal

import android.content.Context
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
) : AutoCloseable {
    /**
     * All parent Linux open callbacks must use this entry, not terminalOwner.openLinux directly.
     * The admission lock spans the owner's check AND host admission, so a concurrent revocation
     * cannot return and then admit a previously checked open. Already admitted runs retain leases.
     */
    fun openLinux(reference: LinuxGenerationRef, conversationId: ConversationId? = null): Boolean =
        admission.admit(reference) { terminalOwner.openLinux(reference, conversationId) }

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
            val admission = LinuxSettingsAdmission(snapshot.map { LinuxGenerationRef(it.environmentId, it.generation) }.toSet())
            // Host metadata is fixed for this entire owner lifetime. New approvals never replace it.
            val owner = TerminalUiOwner(TerminalEnvironmentHost.android(context.applicationContext, snapshot), admission::current)
            val settings = LinuxSettingsController(LinuxSettingsCatalogAdapter(catalog), admission, startupEntries = snapshot)
            return LinuxSettingsApplication(owner, settings, admission)
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
    fun deny(reference: LinuxGenerationRef) = synchronized(lock) { allowed = allowed - reference }
    fun admit(reference: LinuxGenerationRef, action: () -> Boolean): Boolean = synchronized(lock) {
        reference in allowed && action()
    }
}
