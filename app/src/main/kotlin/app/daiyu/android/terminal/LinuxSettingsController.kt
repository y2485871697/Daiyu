package app.daiyu.android.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** User input only. No inferred paths, scanned hashes, default shared directories or source URLs. */
data class LinuxSettingsMount(val host: String = "", val guest: String = "")
data class LinuxSettingsDraft(
    val distribution: String = "",
    val environmentId: String = "",
    val rootfs: String = "",
    val proot: String = "",
    val prootSha256: String = "",
    val loader: String = "",
    val loaderSha256: String = "",
    val guestWorkingDirectory: String = "/",
    val sharedMounts: List<LinuxSettingsMount> = emptyList(),
) {
    internal fun installation(): LinuxInstallation {
        // Check before associate: duplicate hosts must not silently disappear from confirmation.
        require(sharedMounts.map { it.host }.distinct().size == sharedMounts.size) { "duplicate_mount_host" }
        return LinuxInstallation(distribution, environmentId, "replaced-by-catalog", File(rootfs),
            File(proot), prootSha256, File(loader), loaderSha256, guestWorkingDirectory,
            sharedMounts.associate { it.host to it.guest })
    }

    internal fun snapshot() = copy(sharedMounts = sharedMounts.map { it.copy() })
}

data class LinuxSettingsState(
    val busy: Boolean = false,
    val revoked: Boolean = false,
    val entries: List<LinuxInstallation> = emptyList(),
    val preview: FrozenLinuxApproval? = null,
    val pendingApproval: FrozenLinuxApproval? = null,
    val pendingRemoval: LinuxGenerationRef? = null,
    val listConfirmed: Boolean = false,
    val notice: String? = null,
    val error: Boolean = false,
)

internal interface LinuxSettingsCatalogAccess {
    fun freeze(configuration: LinuxInstallation): FrozenLinuxApproval
    suspend fun load(): List<LinuxInstallation>
    suspend fun approve(approval: FrozenLinuxApproval): List<LinuxInstallation>
    // Implementations must invoke admit once at their actual IO gate, not before dispatching.
    suspend fun approveInstallation(approval: FrozenLinuxApproval, admit: () -> Boolean): List<LinuxInstallation>
    suspend fun registrationRecorded(approval: FrozenLinuxApproval): Boolean
    suspend fun remove(reference: LinuxGenerationRef): List<LinuxInstallation>
}

internal class LinuxSettingsCatalogAdapter(private val catalog: TrustedLinuxCatalog) : LinuxSettingsCatalogAccess {
    override fun freeze(configuration: LinuxInstallation) = catalog.freezeForApproval(configuration)
    override suspend fun load() = catalog.load()
    override suspend fun approve(approval: FrozenLinuxApproval) = catalog.approve(approval)
    override suspend fun approveInstallation(approval: FrozenLinuxApproval, admit: () -> Boolean) =
        catalog.approveInstallation(approval, admit)
    override suspend fun registrationRecorded(approval: FrozenLinuxApproval) = catalog.registrationRecorded(approval)
    override suspend fun remove(reference: LinuxGenerationRef) = catalog.remove(reference)
}

/**
 * App-owned coordinator, not an owner of terminal resources. A page handle is a one-shot capability:
 * entering another page or leaving revokes it synchronously, including all its captured callbacks.
 * All catalog work (even preview preparation) runs on the worker, never the composition thread.
 */
class LinuxSettingsController internal constructor(
    private val catalog: LinuxSettingsCatalogAccess,
    private val admission: LinuxSettingsAdmission,
    private val worker: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    startupEntries: List<LinuxInstallation> = emptyList(),
    val installation: app.daiyu.android.terminal.install.LinuxInstallCoordinator? = null,
) : AutoCloseable {
    // For a failed later refresh, keep known startup refs visible so a broken file can be revoked.
    // These rows are explicitly unconfirmed, never a replacement for the admission gate.
    private val startupEntries = startupEntries.map { it.copy(sharedMounts = it.sharedMounts.toMap()) }
    private val lock = Any()
    private val operations = Mutex()
    private var current: LinuxSettingsPageOwner? = null
    private var closed = false

    // One unresolved mutation at a time. Neither a read nor page revocation is a durability receipt.
    // Keep the original object/reference even if rename made its active row appear/disappear.
    private sealed class PendingOperation(val failure: String) {
        class Approval(val approval: FrozenLinuxApproval) : PendingOperation(
            "批准未获持久化确认；可能已经写入。请重试同一冻结预览，不要重建代次来重试。")
        class Removal(val reference: LinuxGenerationRef) : PendingOperation(
            "撤销未获持久化确认；本进程仍禁止该配置的新启动。请对同一代次重试撤销。")
    }
    private var pending: PendingOperation? = null

    // Called only under lock. Project app-owned intent into this visit, never reuse a page callback.
    private fun withPending(state: LinuxSettingsState): LinuxSettingsState {
        val approval = (pending as? PendingOperation.Approval)?.approval
        return state.copy(
            preview = approval ?: state.preview.takeUnless { it === state.pendingApproval },
            pendingApproval = approval,
            pendingRemoval = (pending as? PendingOperation.Removal)?.reference,
            listConfirmed = state.listConfirmed && pending == null,
            error = if (pending != null) !state.busy else state.error,
            notice = pending?.failure ?: state.notice,
        )
    }

    fun enterPage(): LinuxSettingsPageOwner = synchronized(lock) {
        check(!closed) { "linux_settings_closed" }
        current?.let(::revokeLocked)
        LinuxSettingsPageOwner(this, withPending(LinuxSettingsState(entries = startupEntries)))
            .also { current = it; refresh(it) }
    }

    fun availableThisProcess(reference: LinuxGenerationRef): Boolean = admission.contains(reference)

    internal fun revoke(page: LinuxSettingsPageOwner): Boolean = synchronized(lock) {
        val wasCurrent = active(page)
        revokeLocked(page)
        if (current === page) current = null
        wasCurrent
    }

    private fun revokeLocked(page: LinuxSettingsPageOwner) {
        if (current === page) installation?.cancel()
        page.mutable.value = page.mutable.value.copy(revoked = true, busy = false, preview = null)
    }

    private fun active(page: LinuxSettingsPageOwner) = !closed && current === page && !page.mutable.value.revoked

    internal fun installAction(page: LinuxSettingsPageOwner,
        action: (app.daiyu.android.terminal.install.LinuxInstallCoordinator) -> Boolean): Boolean = synchronized(lock) {
        if (!active(page) || page.mutable.value.busy || pending != null || page.mutable.value.preview != null) false
        else installation?.let(action) ?: false
    }

    internal fun cancelInstall(page: LinuxSettingsPageOwner): Boolean = synchronized(lock) {
        if (!active(page)) false else { installation?.cancel(); true }
    }

    internal fun discardPreview(page: LinuxSettingsPageOwner): Boolean = synchronized(lock) {
        if (!active(page) || page.mutable.value.busy || pending != null) return@synchronized false
        page.mutable.value = page.mutable.value.copy(preview = null, notice = null, error = false)
        true
    }

    internal fun preview(page: LinuxSettingsPageOwner, draft: LinuxSettingsDraft): Boolean {
        // Capture before enqueue; a caller's mutable list must not change the displayed approval.
        val captured = draft.snapshot()
        return submit(page, failure = "预览失败：请检查完整绝对路径、标识、64 位小写期望摘要和共享映射。",
            clearPreview = true, eligible = { pending == null && installation?.blocksManual != true }) {
            val frozen = catalog.freeze(captured.installation())
            return@submit { state -> state.copy(preview = frozen, notice = "仅生成冻结预览，尚未批准；文件验证在批准时执行。") }
        }
    }

    internal fun refresh(page: LinuxSettingsPageOwner): Boolean = submit(page,
        failure = "读取或验证目录失败；不是空列表。未覆盖目录，已有显示可能过期。", invalidateListOnFailure = true) {
        val entries = catalog.load()
        return@submit { state -> state.copy(entries = entries, listConfirmed = true, notice = "已读取并验证批准目录。") }
    }

    internal fun approve(page: LinuxSettingsPageOwner, approval: FrozenLinuxApproval): Boolean {
        val mutation = PendingOperation.Approval(approval)
        return submit(page, failure = mutation.failure, mutation = mutation,
            invalidateListOnFailure = true, eligible = {
                installation?.blocksManual != true && it.preview === approval && (pending == null ||
                    (pending as? PendingOperation.Approval)?.approval === approval)
            }) {
            val entries = catalog.approve(approval)
            return@submit { state -> state.copy(entries = entries, preview = null, listConfirmed = true,
                notice = "完整配置已批准并保存；新增配置仅下次应用进程启动生效。未选择或启动终端。") }
        }
    }

    internal fun remove(page: LinuxSettingsPageOwner, reference: LinuxGenerationRef): Boolean {
        val mutation = synchronized(lock) {
            (pending as? PendingOperation.Removal)?.takeIf { it.reference == reference }
                ?: PendingOperation.Removal(reference)
        }
        return submit(page, failure = mutation.failure, mutation = mutation,
            invalidateListOnFailure = true, eligible = { state ->
                // Revocation of a known reference is independent of installation recovery.
                // In particular, UNKNOWN must not prevent synchronous admission denial.
                if (pending == null) state.entries.any { ref(it) == reference }
                else (pending as? PendingOperation.Removal)?.reference == reference
            }, beforeEnqueue = { admission.deny(reference) }) {
            val entries = catalog.remove(mutation.reference)
            return@submit { state -> state.copy(entries = entries, listConfirmed = true,
                notice = "撤销已保存；已阻止新启动。未终止存量会话、删除文件或改变 selection。") }
        }
    }

    private fun submit(
        page: LinuxSettingsPageOwner,
        failure: String,
        invalidateListOnFailure: Boolean = false,
        clearPreview: Boolean = false,
        mutation: PendingOperation? = null,
        eligible: (LinuxSettingsState) -> Boolean = { true },
        beforeEnqueue: () -> Unit = {},
        operation: suspend () -> (LinuxSettingsState) -> LinuxSettingsState,
    ): Boolean = synchronized(lock) {
        val state = page.mutable.value
        if (!active(page) || state.busy || !eligible(state)) return@synchronized false
        beforeEnqueue() // Synchronous denial, before persistence and even if its outcome is unknown.
        if (mutation != null && pending == null) pending = mutation
        val attempted = if (mutation != null) pending else null
        page.mutable.value = withPending(state.copy(busy = true, error = false, notice = null,
            preview = if (clearPreview) null else state.preview))
        worker.launch {
            operations.withLock {
                // Revocation fences queued IO, not the app-owned pending intent. A new visit
                // must explicitly retry it; started IO may still supply its durability receipt.
                if (!synchronized(lock) { active(page) }) return@withLock
                val result = runCatching { operation() } // Never hold the controller lock for IO.
                synchronized(lock) {
                    if (result.isSuccess && attempted != null && pending === attempted) pending = null
                    if (active(page)) {
                        val now = page.mutable.value
                        page.mutable.value = withPending(result.fold(
                            { change -> change(now).copy(busy = false, error = false) },
                            { now.copy(busy = false, error = true, notice = failure,
                                listConfirmed = if (invalidateListOnFailure) false else now.listConfirmed) },
                        ))
                    }
                }
            }
        }
        true
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            current?.let(::revokeLocked)
            current = null
        }
        installation?.close()
        worker.cancel()
    }

    private fun ref(value: LinuxInstallation) = LinuxGenerationRef(value.environmentId, value.generation)
}

/** Keep the exact handle across recompositions; never reuse it for a subsequent route visit. */
class LinuxSettingsPageOwner internal constructor(
    private val controller: LinuxSettingsController,
    initial: LinuxSettingsState = LinuxSettingsState(),
) : AutoCloseable {
    internal val mutable = MutableStateFlow(initial)
    val state: StateFlow<LinuxSettingsState> = mutable.asStateFlow()
    fun refresh() = controller.refresh(this)
    fun preview(draft: LinuxSettingsDraft) = controller.preview(this, draft)
    fun discardPreview() = controller.discardPreview(this)
    fun approve(approval: FrozenLinuxApproval) = controller.approve(this, approval)
    fun remove(reference: LinuxGenerationRef) = controller.remove(this, reference)
    fun prepareInstall(distribution: String, mirrorId: String) = controller.installAction(this) { it.prepare(distribution, mirrorId) }
    fun discardInstall() = controller.installAction(this) { it.discard() }
    fun recoverInstall() = controller.installAction(this) { it.recover() }
    fun install(prepared: app.daiyu.android.terminal.install.PreparedLinuxInstall) =
        controller.installAction(this) { it.downloadAndInstall(prepared) }
    fun confirmPublishedDurability(prepared: app.daiyu.android.terminal.install.PreparedLinuxInstall) =
        controller.installAction(this) { it.confirmPublishedDurability(prepared) }
    fun prepareInstalledRegistration() = controller.installAction(this) { it.prepareRegistration() }
    fun approveInstalled(approval: FrozenLinuxApproval) = controller.installAction(this) { it.approveRegistration(approval) }
    fun cancelInstall() = controller.cancelInstall(this)
    /** Returns true only once, when this was still the active page; useful for back callbacks. */
    fun revoke(): Boolean = controller.revoke(this)
    override fun close() { revoke() }
}
