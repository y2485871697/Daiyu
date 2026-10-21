package app.daiyu.android.context

import android.content.Context
import android.os.Looper

/** Wiring only: constructing this on the UI thread never opens preferences, keystore or network.
 * The parent calls freeze/prepare on its reserved preparation worker, then retains its existing
 * revision/owner/send transaction checks. No duplicate compression or deadline implementation. */
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
            chatModel = { snapshot, window, usage ->
                requireWorker()
                check(usage != null) { "production_summary_requires_runtime_preparation" }
                val configuration = CatalogSummaryExecutionPolicy.chatConfiguration(snapshot, window)
                requireNotNull(ProviderSummaryModelResolver { configuration }.resolve(configuration.binding, usage))
            },
            models = object : SummaryModelResolver {
                private val provider = ProviderSummaryModelResolver { binding ->
                    requireWorker()
                    if (CatalogSummaryBindings.decode(binding) == null) SummaryProfileStore(app).configuration(binding)
                    else catalogSummaryConfiguration(app, binding)
                }
                override fun resolve(binding: SummaryModelBinding): BoundSummaryModel? = resolve(binding, null)
                override fun resolve(binding: SummaryModelBinding, usage: BoundSummaryUsagePort?): BoundSummaryModel? {
                    val bound = provider.resolve(binding, usage) ?: return null
                    check(usage != null) { "production_summary_requires_runtime_preparation" }
                    return bound
                }
            },
        )
    }

    private fun requireWorker() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "压缩配置和摘要密钥必须在准备工作线程读取" }
    }
}
