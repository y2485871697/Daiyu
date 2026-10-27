package app.daiyu.android.context

import android.content.Context
import android.os.Looper
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ProviderRepository

/** Exact host selection identity, not a title, parent branch, or the last visible message. */
internal data class ContextManagementOwner(val conversationId: ConversationId, val generation: Long) {
    init { require(generation >= 0 && conversationId.value.isNotBlank() && conversationId.value.length <= 200) }
}

/** Null binding is explicit current-model intent for one manual operation, never an error fallback. */
internal data class ContextManagementSelection(val binding: SummaryModelBinding?, val protocol: ProviderProtocol)

internal data class ContextManagementSnapshot(
    val conversationId: ConversationId,
    val historyRevision: String,
    val originals: List<Message>,
    val effective: PreparedContext,
    val configuration: CompressionSettings?,
    val summaryTarget: SummaryProfileDescriptor?,
    val savedConfiguration: CompressionSettings? = configuration,
) {
    val canCompress: Boolean get() = originals.isNotEmpty() && configuration?.let {
        it.contextWindowTokens != null && it.summaryModel != null &&
            summaryTarget?.binding == it.summaryModel
    } == true
}

/** Worker-only port: describe reads metadata, never credentials. freeze MUST compare the entire
 * descriptor (including non-reusable revision) and capture one candidate atomically with save.
 * No network until that retained candidate is consumed by the existing coordinator/service. */
internal interface ContextManagementSummaryProfiles {
    fun describe(binding: SummaryModelBinding): SummaryProfileDescriptor?
    fun describeSelection(selection: ContextManagementSelection): SummaryProfileDescriptor =
        throw UnsupportedOperationException("当前摘要配置不支持一次性选择；请刷新")
    fun freeze(expected: SummaryProfileDescriptor, usagePort: BoundSummaryUsagePort): BoundSummaryModel?
}

internal data class ContextManagementArchive(
    val archiveId: String,
    val offset: Int,
    val page: ArchivePage,
)

internal data class ContextCompressionReceipt(val archiveId: String?, val expectedHistoryRevision: String)

/** A lease MUST be the parent's existing Preparation reservation, not a separate mutex.
 * checkCurrent checks selection generation, parent token identity and revocation, without I/O.
 * The backend checks persisted owner existence in its read transaction.
 * cancellation is that SAME Preparation.cancellation; nonthrowing finish releases after worker drain.
 * Implementations must not hold a controller monitor while doing I/O or cancelling transports. */
internal interface ContextManagementLease {
    val owner: ContextManagementOwner
    val cancellation: ContextPreparationCancellation
    fun checkCurrent()
    fun finish()
    fun compressionStarted() = Unit
}

internal interface ContextManagementBackend {
    fun snapshot(lease: ContextManagementLease): ContextManagementSnapshot
    fun selectCompression(lease: ContextManagementLease, expected: ContextManagementSnapshot,
        selection: ContextManagementSelection): ContextManagementSnapshot =
        throw UnsupportedOperationException("当前上下文后端不支持一次性选择；请刷新")
    fun archive(lease: ContextManagementLease, snapshot: ContextManagementSnapshot,
        offset: Int): ContextManagementArchive
    fun compress(lease: ContextManagementLease,
        expected: ContextManagementSnapshot): ContextCompressionReceipt
    fun runCompression(lease: ContextManagementLease, expected: ContextManagementSnapshot,
        selection: ContextManagementSelection?, parent: Message,
        windowTokens: Long, sendBudgetTokens: Long,
        currentProvider: app.daiyu.android.model.ProviderSnapshot? = null,
        progress: (app.daiyu.android.timeline.CompressionActivity) -> Unit): app.daiyu.android.domain.runtime.RunContextRuntime =
        throw UnsupportedOperationException("运行中压缩不可用")
}

/** Worker-only implementation. UI receives neither this object nor a ContextService.
 * No sends, branches, revisions, tools, retries, capacity guesses or new boundary algorithm. */
internal class ContextManagementWorker(
    private val service: ContextService,
    private val settings: () -> CompressionSettings,
    private val policies: CompressionPolicyResolver,
    private val profiles: ContextManagementSummaryProfiles,
    private val chatModel: (app.daiyu.android.model.ProviderSnapshot, Long, BoundSummaryUsagePort) -> BoundSummaryModel = { snapshot, window, usage ->
        val configuration = CatalogSummaryExecutionPolicy.chatConfiguration(snapshot, window)
        requireNotNull(ProviderSummaryModelResolver { configuration }.resolve(configuration.binding, usage))
    },
) : ContextManagementBackend {
    override fun snapshot(lease: ContextManagementLease): ContextManagementSnapshot {
        checkLease(lease)
        // Broken settings must not make intact local history inaccessible. No credentials read here.
        val configuration = try { settings() } catch (_: Exception) { null }
        val target = try { configuration?.summaryModel?.let(profiles::describe) }
            catch (_: Exception) { null }
        checkLease(lease)
        return service.readQuery {
            // Never take the parent monitor while holding SQLite's transaction/connection lock.
            lease.cancellation.check()
            val id = lease.owner.conversationId
            require(service.isUsageOwner(id.value)) { "会话不存在" }
            val originals = freezeContextMessages(service.displayHistory(id))
            // No summarizer / policy: this is a strictly read-only projection, even above budget.
            val effective = service.prepareSend(id).let { it.copy(messages = freezeContextMessages(it.messages)) }
            ContextManagementSnapshot(id, ContextRevision.digest(originals), originals, effective, configuration, target)
        }
    }

    override fun selectCompression(lease: ContextManagementLease, expected: ContextManagementSnapshot,
        selection: ContextManagementSelection): ContextManagementSnapshot {
        checkLease(lease)
        service.readQuery { validateSnapshot(lease, expected) }
        val saved = requireNotNull(expected.savedConfiguration) { "压缩配置不可用；请重新保存设置后刷新" }
        require(settings() == saved) { "压缩设置已改变；请刷新后重新选择" }
        val target = profiles.describeSelection(selection)
        val selected = expected.copy(configuration = saved.copy(summaryModel = target.binding),
            summaryTarget = target, savedConfiguration = saved)
        require(selected.canCompress) { "需要完整历史、明确窗口和可用摘要模型" }
        checkLease(lease)
        require(settings() == saved) { "压缩设置已改变；请刷新后重新选择" }
        service.readQuery { validateSnapshot(lease, expected) }
        checkLease(lease)
        return selected
    }

    override fun archive(lease: ContextManagementLease, snapshot: ContextManagementSnapshot,
        offset: Int): ContextManagementArchive {
        checkLease(lease)
        return service.readQuery {
            validateSnapshot(lease, snapshot)
            val archive = requireNotNull(snapshot.effective.archiveId) { "当前快照没有有效档案" }
            require(service.archiveOwner(archive) == lease.owner.conversationId) { "档案不属于当前会话" }
            val page = service.archivePage(archive, offset, PAGE_SIZE, expectedOwner = lease.owner.conversationId)
            ContextManagementArchive(archive, offset, page.copy(messages = freezeContextMessages(page.messages)))
        }
    }

    override fun runCompression(lease: ContextManagementLease, expected: ContextManagementSnapshot,
        selection: ContextManagementSelection?, parent: Message,
        windowTokens: Long, sendBudgetTokens: Long,
        currentProvider: app.daiyu.android.model.ProviderSnapshot?,
        progress: (app.daiyu.android.timeline.CompressionActivity) -> Unit): app.daiyu.android.domain.runtime.RunContextRuntime {
        checkLease(lease)
        require(parent.conversationId == lease.owner.conversationId)
        val saved = requireNotNull(expected.savedConfiguration)
        require(settings() == saved) { "压缩设置已改变，请重新选择" }
        val useCurrent = if (selection == null) expected.configuration?.summaryModel == null else selection.binding == null
        val frozenCurrent = if (useCurrent) {
            val provider = requireNotNull(currentProvider) { "当前运行模型不可用" }
            if (selection == null) provider else ManualSummaryTargets.forSummary(provider.asModelSettings(), selection.protocol).requestSnapshot()
        } else null
        val target = if (useCurrent) null else if (selection == null) requireNotNull(expected.summaryTarget)
            else profiles.describeSelection(selection)
        require(sendBudgetTokens > 0 && sendBudgetTokens <= windowTokens)
        val policy = policies.resolve(saved.copy(automatic = true, summaryModel = target?.binding,
            contextWindowTokens = windowTokens, reserveTokens = windowTokens - sendBudgetTokens))
        return ProductionRunContext(policy, parent, resolve = { usage ->
            checkLease(lease)
            require(settings() == saved) { "压缩设置已改变，请重新选择" }
            if (frozenCurrent != null) chatModel(frozenCurrent, windowTokens, usage)
            else requireNotNull(profiles.freeze(checkNotNull(target), usage)) { "压缩模型不可用" }
        }, automatic = false, progress = progress)
    }

    override fun compress(lease: ContextManagementLease,
        expected: ContextManagementSnapshot): ContextCompressionReceipt {
        checkLease(lease)
        service.readQuery { validateSnapshot(lease, expected) }
        require(expected.canCompress) { "需要明确窗口和可用摘要模型" }
        val saved = requireNotNull(expected.savedConfiguration)
        val selected = requireNotNull(expected.configuration)
        require(selected.copy(summaryModel = saved.summaryModel) == saved) { "本次压缩配置不匹配；请刷新后重新确认" }
        require(settings() == saved) { "压缩设置已改变；请刷新后重新确认" }
        // Explicit manual consent enables this SINGLE frozen preparation, not the saved auto switch.
        // Existing coordinator still freezes/validates the exact independent candidate and cancellation.
        val target = requireNotNull(expected.summaryTarget)
        val operation = service.manualSummaryUsage(lease)
        val operations = mutableListOf(operation)
        var success = false
        var failure: Throwable? = null
        try {
            val policy = policies.resolve(selected.copy(automatic = true))
            val bound = requireNotNull(profiles.freeze(target, operation.port)) { "所选摘要模型不可用，请重新选择" }
            require(bound.binding == target.binding) { "摘要目标不匹配；请刷新后重新确认" }
            val summarizer = ModelContextSummarizer(bound, lease.cancellation, nextModel = {
                checkLease(lease)
                val next = service.manualSummaryUsage(lease).also { operations += it }
                checkNotNull(bound.rebind) { "摘要模型不支持分块用量绑定" }.invoke(next.port)
            })
            checkLease(lease)
            require(settings() == saved) { "压缩设置已改变；请刷新后重新确认" }
            val archive = service.compress(expected.conversationId, policy, summarizer,
                pendingMessages = emptyList(), cancelled = { lease.cancellation.isCancelled },
                onStarted = lease::compressionStarted)
            // No post-publication history check or second request. Usage close remains in finally;
            // a close failure is an accounting error, never permission to repeat paid work.
            success = true
            return ContextCompressionReceipt(archive, expected.historyRevision)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            // No Runner follows manual compression; its own worker must close every admitted attempt.
            var closeError: Throwable? = failure
            operations.forEach { current ->
                try { current.finish(success) } catch (caught: Throwable) {
                    if (closeError == null) closeError = caught else closeError?.addSuppressed(caught)
                }
            }
            if (failure == null) closeError?.let { throw it }
        }
    }

    private fun validateSnapshot(lease: ContextManagementLease, expected: ContextManagementSnapshot) {
        // Already inside SQLite: revocation propagates through the same atomic cancellation token.
        lease.cancellation.check()
        val id = lease.owner.conversationId
        require(id == expected.conversationId && service.isUsageOwner(id.value)) { "会话已变化或已删除" }
        require(ContextRevision.digest(service.displayHistory(id)) == expected.historyRevision) {
            "完整历史已变化；请刷新"
        }
        require(service.prepareSend(id).archiveId == expected.effective.archiveId) { "活动档案已变化；请刷新" }
    }

    private fun checkLease(lease: ContextManagementLease) {
        requireWorker()
        lease.cancellation.check()
        lease.checkCurrent()
    }

    companion object {
        const val PAGE_SIZE = 50

        private fun requireWorker() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "上下文读取与摘要准备必须在父工作线程执行" }
        }

        /** Shares the production v2 settings, policy and retained provider candidate resolver.
         * Parent must obtain its lazy shared service and construct this on its worker AFTER admission:
         * first ContextService/archive-store initialization may mkdir; never evaluate it on the UI.
         * All reads are local; only explicit compress may open summary I/O. */
        fun production(context: Context, service: ContextService): ContextManagementWorker {
            val app = context.applicationContext ?: context
            return ContextManagementWorker(service, settings = {
                requireWorker()
                ProductionCompressionSettingsStore(app).snapshot().coordinatorSettings().let { saved ->
                    if (saved.contextWindowTokens != null) saved else {
                        val repository = ProviderRepository(app)
                        val editor = repository.activeEditor()
                        val window = repository.withEditor(editor) { store ->
                            store.modelCatalog.readConfiguration {
                                val model = store.summaryRequestMetadata().model
                                store.modelCatalog.readRegisteredModel(model)?.settings?.contextWindowOverride?.toLong()
                            }
                        }
                        saved.copy(contextWindowTokens = window)
                    }
                }
            }, policies = ProductionCompressionPolicyResolver,
                profiles = object : ContextManagementSummaryProfiles {
                    override fun describeSelection(selection: ContextManagementSelection): SummaryProfileDescriptor {
                        requireWorker()
                        return ManualSummaryTargets.describe(ProviderRepository(app), selection)
                    }
                    override fun describe(binding: SummaryModelBinding): SummaryProfileDescriptor {
                        requireWorker()
                        return when {
                            ManualSummaryTargets.isManual(binding) -> ManualSummaryTargets.descriptor(ProviderRepository(app), binding)
                            CatalogSummaryBindings.decode(binding) != null -> catalogSummaryDescriptor(app, binding)
                            else -> SummaryProfileStore(app).descriptor(binding)
                        }
                    }
                    override fun freeze(expected: SummaryProfileDescriptor, usagePort: BoundSummaryUsagePort): BoundSummaryModel? {
                        requireWorker()
                        val configuration = when {
                            ManualSummaryTargets.isManual(expected.binding) -> ManualSummaryTargets.configuration(ProviderRepository(app), expected)
                            CatalogSummaryBindings.decode(expected.binding) != null -> catalogSummaryConfiguration(app, expected)
                            else -> SummaryProfileStore(app).configuration(expected)
                        }
                        return ProviderSummaryModelResolver(lookup = { binding ->
                            require(binding == expected.binding)
                            configuration // Retain the exact descriptor revision; do not resolve again.
                        }, usagePort = usagePort).resolve(expected.binding)
                    }
                })
        }
    }
}
