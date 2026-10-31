package app.daiyu.android.context

import android.content.Context
import android.os.Looper

/** Wiring only: constructing this on the UI thread never opens preferences, keystore or network.
 * The parent calls freeze/prepare on its reserved preparation worker, then retains its existing
 * revision/owner/send transaction checks. */
internal object ProductionContextPreparation {
    fun create(context: Context, service: ContextService): ContextPreparationCoordinator {
        val app = context.applicationContext ?: context
        return ContextPreparationCoordinator(
            service = service,
            settingsSnapshot = {
                requireWorker()
                ProductionCompressionSettingsStore(app).snapshot().coordinatorSettings()
            },
            policies = ProductionCompressionPolicyResolver,
            chatModel = { snapshot, window, usage, protocol ->
                requireWorker()
                check(usage != null) { "production_summary_requires_runtime_preparation" }
                val configuration = CatalogSummaryExecutionPolicy.chatConfiguration(snapshot, window, protocol)
                requireNotNull(ProviderSummaryModelResolver { configuration }.resolve(configuration.binding, usage))
            },
            models = object : SummaryModelResolver {
                private val provider = ProviderSummaryModelResolver { binding ->
                    requireWorker()
                    if (CatalogSummaryBindings.decode(binding) == null) SummaryProfileStore(app).configuration(binding)
                    else catalogSummaryConfiguration(app, binding)
                }
                override fun resolve(binding: SummaryModelBinding): BoundSummaryModel? = resolve(binding, null)
                override fun resolve(binding: SummaryModelBinding, usage: BoundSummaryUsagePort?,
                    protocol: app.daiyu.android.model.ProviderProtocol?): BoundSummaryModel? {
                    val bound = provider.resolve(binding, usage, protocol) ?: return null
                    check(usage != null) { "production_summary_requires_runtime_preparation" }
                    return bound
                }
            },
        )
    }

    fun childRuntime(context: Context, parent: app.daiyu.android.domain.Message,
        snapshot: app.daiyu.android.model.ProviderSnapshot, agentLabel: String,
        progress: (app.daiyu.android.timeline.CompressionActivity) -> Unit): ProductionRunContext? {
        requireWorker()
        val app = context.applicationContext ?: context
        val settings = ProductionCompressionSettingsStore(app).snapshot().childSettings(snapshot)
        val policy = ProductionCompressionPolicyResolver.resolve(settings)
        if (policy.contextTokens == null) return null
        val configuration = CatalogSummaryExecutionPolicy.childConfiguration(snapshot,
            checkNotNull(policy.contextTokens), settings.summaryModel, settings.summaryProtocol) { binding ->
            if (CatalogSummaryBindings.decode(binding) == null) SummaryProfileStore(app).configuration(binding)
            else catalogSummaryConfiguration(app, binding)
        }
        return ProductionRunContext(policy, parent, { usage ->
            requireNotNull(ProviderSummaryModelResolver { configuration }.resolve(configuration.binding, usage)) { "子代理摘要模型不可用" }
        }, automatic = true, agentLabel = agentLabel, taskId = parent.runId.value, progress = progress)
    }

    private fun requireWorker() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "压缩配置和摘要密钥必须在准备工作线程读取" }
    }
}
