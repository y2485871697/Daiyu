package app.daiyu.android.terminal.install

import android.content.Context
import app.daiyu.android.terminal.FrozenLinuxApproval
import app.daiyu.android.terminal.LinuxExecutionMode
import app.daiyu.android.terminal.LinuxRuntimePlatform
import app.daiyu.android.terminal.LinuxInstallation
import app.daiyu.android.terminal.LinuxSettingsCatalogAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Publication, registration and selection receipts; none opens a terminal. */
data class LinuxInstallState(
    val busy: Boolean = false,
    val unavailableReason: String? = null,
    val prepared: PreparedLinuxInstall? = null,
    val receipt: LinuxInstallReceipt? = null,
    val registration: FrozenLinuxApproval? = null,
    val registrationPending: Boolean = false,
    val registered: Boolean = false,
    val unknown: Boolean = false,
    val notice: String? = null,
    val recoveryBlocked: Boolean = false,
    val cleanupPending: Boolean = false,
    val operationFailed: Boolean = false,
    val discarding: Boolean = false,
) {
    val canDiscard: Boolean get() = prepared != null && !busy && !unknown && !recoveryBlocked &&
        !cleanupPending && !registrationPending && !registered && receipt == null && registration == null
}

internal interface LinuxInstallTransactions {
    fun prepare(artifact: LinuxRootfsArtifact, mirrorId: String, installation: LinuxInstallation): PreparedLinuxInstall
    fun install(prepared: PreparedLinuxInstall, cancellation: LinuxInstallCancellation): LinuxInstallReceipt
    fun confirmPublishedDurability(prepared: PreparedLinuxInstall): LinuxInstallReceipt
    fun retryCleanup(prepared: PreparedLinuxInstall): LinuxInstallReceipt? = error("linux_install_cleanup_not_retained")
    fun remember(prepared: PreparedLinuxInstall)
    fun rememberRegistration(prepared: PreparedLinuxInstall, approval: FrozenLinuxApproval)
    fun completeRegistration(prepared: PreparedLinuxInstall, approval: FrozenLinuxApproval)
    fun discard(prepared: PreparedLinuxInstall)
    fun recover(helpers: PackagedProotHelpers): RecoveredLinuxInstall?
    fun recoverChroot(abi: String): RecoveredLinuxInstall? = error("linux_chroot_recovery_unavailable")
}

private class RootlessInstallTransactions(private val installer: RootlessLinuxInstaller) : LinuxInstallTransactions {
    override fun prepare(artifact: LinuxRootfsArtifact, mirrorId: String, installation: LinuxInstallation) =
        installer.prepare(artifact, mirrorId, installation)
    override fun install(prepared: PreparedLinuxInstall, cancellation: LinuxInstallCancellation): LinuxInstallReceipt {
        cancellation.check()
        installer.rememberRequest(prepared)
        return installer.install(installer.approveDownloadAndInstall(prepared), cancellation)
    }
    override fun confirmPublishedDurability(prepared: PreparedLinuxInstall) = installer.confirmPublishedDurability(prepared)
    override fun retryCleanup(prepared: PreparedLinuxInstall) = installer.retryCleanup(prepared)
    override fun remember(prepared: PreparedLinuxInstall) = installer.rememberRequest(prepared)
    override fun rememberRegistration(prepared: PreparedLinuxInstall, approval: FrozenLinuxApproval) =
        installer.rememberRegistration(prepared, approval)
    override fun completeRegistration(prepared: PreparedLinuxInstall, approval: FrozenLinuxApproval) =
        installer.completeRegistration(prepared, approval)
    override fun discard(prepared: PreparedLinuxInstall) = installer.discardRequest(prepared)
    override fun recover(helpers: PackagedProotHelpers) = installer.recoverRequest(helpers)
    override fun recoverChroot(abi: String) = installer.recoverChrootRequest(abi)
}

/** Application-lived; retains the exact request and FrozenLinuxApproval across page recreation. */
class LinuxInstallCoordinator internal constructor(
    private val installer: LinuxInstallTransactions,
    private val helpers: () -> PackagedProotHelpers,
    private val privateRoot: File,
    private val catalog: LinuxSettingsCatalogAccess,
    private val worker: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    unavailableReason: String? = null,
    val executionMode: LinuxExecutionMode = LinuxExecutionMode.PROOT,
    private val chrootAbi: () -> String = { LinuxRuntimePlatform.requireSupported(LinuxExecutionMode.CHROOT) },
) : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow(LinuxInstallState(unavailableReason = unavailableReason))
    val state: StateFlow<LinuxInstallState> = mutable.asStateFlow()
    private var token: LinuxInstallCancellation? = null
    private var closed = false
    private var admissionEpoch = 0L
    private var activeOperation: Any? = null
    private var operationEpoch = 0L

    init { recover() }

    /** Reconstruct descriptions; retire recorded requests, never approve or replay a probe. */
    internal fun recover(): Boolean = submit(recovering = true, eligible = { !it.cleanupPending && (it.prepared == null || it.recoveryBlocked || it.unknown) }) {
        val original = synchronized(lock) { mutable.value }
        val recovered = if (executionMode == LinuxExecutionMode.CHROOT) installer.recoverChroot(chrootAbi())
            else installer.recover(helpers())
        if (original.unknown) {
            val previous = checkNotNull(original.prepared)
            check(recovered != null && previous.installation == recovered.prepared.installation &&
                previous.artifact.id == recovered.prepared.artifact.id && previous.mirror == recovered.prepared.mirror) { "linux_install_unknown_request_changed" }
            // A clean disk scan is not termination evidence for the retained probe.
            original.copy(notice = "已只读核验原请求；UNKNOWN 仍保留，未执行探针、清理锁或授予授权。")
        } else if (recovered == null) LinuxInstallState(notice = "未发现待恢复请求；未下载、执行或注册。")
        else if (!recovered.unknown && recovered.registration != null &&
            catalog.registrationRecorded(recovered.registration) &&
            catalog.load().none { it == recovered.registration.installation }) {
            // A previous approval (including a tombstone) completes this request. Recovery
            // never re-approves or manufactures a generation, and never clears UNKNOWN.
            installer.completeRegistration(recovered.prepared, recovered.registration)
            LinuxInstallState(registered = true, notice = "原注册请求已有持久记录，已退役；未恢复已撤销的授权或删除安装文件。")
        } else LinuxInstallState(prepared = recovered.prepared,
            receipt = if (recovered.published && !recovered.unknown)
                LinuxInstallReceipt(recovered.prepared.installation, false) else null,
            registration = recovered.registration, unknown = recovered.unknown,
            notice = if (recovered.unknown) "恢复结果 UNKNOWN：原 writer 锁或残留仍在；不能证实探针退出，禁止重放或清锁。"
                else "已只读重建原请求；未授予任何授权。请核对原路径、代次及 helper，并重新明确确认。")
    }

    val blocksManual: Boolean get() = synchronized(lock) {
        val value = mutable.value
        value.busy || (value.prepared != null && !value.registered) || value.registrationPending || value.unknown || value.recoveryBlocked || value.cleanupPending
    }

    /** Only distribution and mirror IDs are accepted, never caller URLs, hashes, paths or ABI. */
    internal fun prepare(distribution: String, mirrorId: String): Boolean = submit(
        eligible = { !it.unknown && !it.recoveryBlocked && !it.registrationPending && (it.prepared == null || it.registered) }
    ) {
        val packaged = if (executionMode == LinuxExecutionMode.PROOT) helpers() else null
        val abi = packaged?.abi ?: chrootAbi()
        val artifact = requireNotNull(LinuxRootfsCatalog.find(distribution, abi)) {
            "linux_install_unsupported_catalog_entry"
        }
        val generation = UUID.randomUUID().toString()
        val target = File(File(File(privateRoot, distribution), generation), "rootfs")
        val prepared = installer.prepare(artifact, mirrorId, LinuxInstallation(distribution,
            distribution, generation, target, packaged?.proot, packaged?.prootSha256.orEmpty(),
            packaged?.loader, packaged?.loaderSha256.orEmpty(), "/", emptyMap(), executionMode))
        synchronized(lock) { mutable.value = LinuxInstallState(busy = true, prepared = prepared) }
        installer.remember(prepared) // Durable description only; no download/registration capability.
        LinuxInstallState(prepared = prepared, notice = "请核对固定条目和完整目标；尚未下载或批准注册。")
    }

    internal fun discard(): Boolean = state.value.prepared?.let(::discard) ?: false

    internal fun discard(prepared: PreparedLinuxInstall): Boolean = submit(
        eligible = { it.canDiscard && it.prepared === prepared },
        before = { it.copy(discarding = true) },
    ) {
        installer.discard(prepared)
        LinuxInstallState()
    }

    /** One install click covers publication, trusted registration and persisted selection. */
    internal fun downloadAndInstall(prepared: PreparedLinuxInstall): Boolean = submit(
        eligible = { it.prepared === prepared && it.receipt == null && !it.unknown && !it.recoveryBlocked },
        cancellable = true,
    ) {
        val activeToken = synchronized(lock) { checkNotNull(token) }
        val epoch = synchronized(lock) { operationEpoch }
        val receipt = installer.install(prepared, activeToken)
        // Never register from a visible marker without a durable publication receipt.
        installedState(prepared, receipt, epoch)
    }

    /** A click explicitly retries publication durability, not another download or registration. */
    internal fun confirmPublishedDurability(prepared: PreparedLinuxInstall): Boolean = submit(
        eligible = { it.prepared === prepared && it.receipt?.directorySyncConfirmed == false && !it.unknown }
    ) { installedState(prepared, installer.confirmPublishedDurability(prepared)) }

    internal fun retryCleanup(prepared: PreparedLinuxInstall): Boolean = submit(cleaning = true,
        eligible = { it.prepared === prepared && it.cleanupPending && !it.unknown }) {
        val returnedReceipt = installer.retryCleanup(prepared)
        val receipt = synchronized(lock) {
            mutable.value = mutable.value.copy(cleanupPending = false)
            returnedReceipt ?: mutable.value.receipt
        }
        if (receipt == null) LinuxInstallState(prepared = prepared,
            notice = "原事务资源清理及租约释放已确认；未重放下载或探针。可重新核对并明确批准原请求。")
        else installedState(prepared, receipt)
    }

    private suspend fun installedState(prepared: PreparedLinuxInstall, receipt: LinuxInstallReceipt,
        epoch: Long = synchronized(lock) { operationEpoch }): LinuxInstallState {
        val retainedRegistration = synchronized(lock) { mutable.value.registration }
        val published = LinuxInstallState(prepared = prepared, receipt = receipt, registration = retainedRegistration,
            notice = if (receipt.directorySyncConfirmed) "文件已持久化，正在启用环境。"
                else "目标已可见，但发布持久化未确认。检查文件不等于发布成功；请重试确认同一请求。")
        // Even a later freeze failure must not erase a publication receipt or enable re-download.
        synchronized(lock) { mutable.value = published.copy(busy = true) }
        if (!receipt.directorySyncConfirmed) return published
        val approval = retainedRegistration ?: catalog.freeze(receipt.installation)
        val frozen = published.copy(registration = approval)
        // Retain the original registration identity across persistence failures and restart.
        synchronized(lock) { mutable.value = frozen.copy(busy = true) }
        return registerAndSelect(prepared, approval, epoch)
    }

    internal fun prepareRegistration(): Boolean = submit(eligible = {
        it.receipt?.directorySyncConfirmed == true && it.registration == null && !it.unknown
    }) {
        val current = synchronized(lock) { mutable.value }
        val approval = catalog.freeze(checkNotNull(current.receipt).installation)
        val frozen = current.copy(registration = approval,
            notice = "正在完成环境设置。")
        synchronized(lock) { mutable.value = frozen.copy(busy = true) }
        registerAndSelect(checkNotNull(current.prepared), approval, synchronized(lock) { operationEpoch })
    }

    /** Explicit retry retains the exact frozen object after registration or selection failure. */
    internal fun approveRegistration(approval: FrozenLinuxApproval): Boolean = synchronized(lock) {
        val epoch = admissionEpoch
        submit(
            eligible = { it.registration === approval && it.receipt?.directorySyncConfirmed == true && !it.registered },
            before = { it.copy(registrationPending = true) },
            fenceQueued = true,
        ) {
            val prepared = checkNotNull(synchronized(lock) { mutable.value.prepared })
            registerAndSelect(prepared, approval, epoch)
        }
    }

    private suspend fun registerAndSelect(prepared: PreparedLinuxInstall, approval: FrozenLinuxApproval,
        epoch: Long): LinuxInstallState {
        synchronized(lock) { mutable.value = mutable.value.copy(registrationPending = true) }
        installer.rememberRegistration(prepared, approval)
        val entries = catalog.approveInstallation(approval) {
            synchronized(lock) {
                !closed && admissionEpoch == epoch && mutable.value.registration === approval
            }
        }
        val active = entries.any { it == approval.installation }
        if (active) {
            // A completed catalog write is retained, but leaving the page must not start a new selection write.
            catalog.select(approval.installation) {
                synchronized(lock) { !closed && admissionEpoch == epoch && mutable.value.registration === approval }
            }
        }
        installer.completeRegistration(prepared, approval)
        return synchronized(lock) { mutable.value }.copy(registrationPending = false, registered = true,
            notice = if (active) "环境已启用，重启应用后可用。"
                else "环境授权已撤销；未重新启用或改变当前选择。")
    }

    /** Direct token cancellation, also called on page revocation. Not just coroutine cancellation. */
    fun cancel() {
        val activeToken = synchronized(lock) { admissionEpoch++; token }
        activeToken?.cancel()
    }

    private fun submit(
        eligible: (LinuxInstallState) -> Boolean,
        cancellable: Boolean = false,
        fenceQueued: Boolean = false,
        recovering: Boolean = false,
        cleaning: Boolean = false,
        before: (LinuxInstallState) -> LinuxInstallState = { it },
        operation: suspend () -> LinuxInstallState,
    ): Boolean = synchronized(lock) {
        val original = mutable.value
        if (closed || original.unavailableReason != null || original.busy ||
            (original.cleanupPending && !cleaning) || (original.unknown && !recovering) || !eligible(original)) return@synchronized false
        token = if (cancellable) LinuxInstallCancellation() else null
        val epoch = admissionEpoch
        operationEpoch = epoch
        val identity = Any().also { activeOperation = it }
        mutable.value = before(original).copy(busy = true, notice = null, operationFailed = false)
        worker.launch {
            // Linearize admission against page revocation/close, before the FIRST IO.
            // Once admitted, cancellation cannot discard a save's durability receipt.
            if (fenceQueued && !synchronized(lock) {
                val admitted = !closed && admissionEpoch == epoch && activeOperation === identity
                if (!admitted && activeOperation === identity) {
                    activeOperation = null
                    mutable.value = mutable.value.copy(busy = false,
                        notice = "排队注册已撤销，未开始写入；请在当前页面明确重试原冻结配置。")
                }
                admitted
            }) return@launch
            val result = runCatching { operation() }
            synchronized(lock) {
                activeOperation = null
                token = null
                mutable.value = result.fold({ it.copy(busy = false, operationFailed = false, discarding = false) }, { failure ->
                    mutable.value.copy(busy = false, operationFailed = true, discarding = false,
                        receipt = (failure as? LinuxInstallCleanupPending)?.receipt ?: mutable.value.receipt,
                        cleanupPending = mutable.value.cleanupPending || failure is LinuxInstallCleanupPending,
                        unknown = mutable.value.unknown || failure is LinuxInstallProbeUnknown,
                        recoveryBlocked = mutable.value.recoveryBlocked || recovering,
                        notice = when {
                            failure is LinuxInstallCleanupPending -> "原事务清理未完成：保留发布回执和原失败；禁止安装及注册。请显式重试原事务清理，不清理 UNKNOWN 探针。"
                            recovering -> "恢复验证失败：保留原 journal/目标/锁，禁止新代次绕过。${failure.message?.take(160)}"
                            failure is LinuxInstallProbeUnknown -> "探测终止结果 UNKNOWN：保留 staging、进程清理句柄及 writer 租约。禁止重试安装或清理锁。"
                            mutable.value.registrationPending -> "注册持久化未确认；请重试同一冻结配置。刷新或离页不代表成功。"
                            else -> "操作未确认成功：${failure.message?.take(160) ?: "linux_install_failed"}"
                        })
                })
            }
        }
        true
    }

    override fun close() {
        synchronized(lock) { closed = true }
        cancel()
        // Do not cancel a running IO coroutine: it owns the receipt/UNKNOWN cleanup decision.
        // The supervisor has no work once the current operation is done.
    }

    companion object {
        internal fun android(context: Context, catalog: LinuxSettingsCatalogAccess,
            mode: LinuxExecutionMode = LinuxExecutionMode.PROOT): LinuxInstallCoordinator {
            val application = context.applicationContext
            val runner = AndroidLinuxInstallProbeRunner(application)
            val installer = if (mode == LinuxExecutionMode.CHROOT) RootlessLinuxInstaller.forChroot(application, runner)
                else RootlessLinuxInstaller.forApp(application, runner)
            return LinuxInstallCoordinator(
                RootlessInstallTransactions(installer),
                PackagedProotHelperProvider(application)::resolve,
                File(application.filesDir.canonicalFile, if (mode == LinuxExecutionMode.CHROOT) "linux-chroot-installations" else "linux-installations"), catalog,
                unavailableReason = LinuxRuntimePlatform.unavailableReason(mode), executionMode = mode)
        }
    }
}
