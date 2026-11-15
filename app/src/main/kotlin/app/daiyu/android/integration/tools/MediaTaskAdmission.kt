package app.daiyu.android.integration.tools

import app.daiyu.android.model.generation.GenerationModelProfile
import app.daiyu.android.model.generation.GenerationSettingsSnapshot

internal class OrdinaryGenerationConfiguration(
    val settings: GenerationSettingsSnapshot,
    val bind: (GenerationModelProfile) -> MediaTaskAdmission,
)

/** One original media invocation; invalidation is irreversible and never replays a paid request. */
internal class MediaTaskAdmission(
    private val current: () -> Boolean,
    private val subscribe: ((() -> Unit) -> AutoCloseable) = { AutoCloseable {} },
) {
    @Volatile private var invalid = false
    fun admitted(): Boolean {
        if (!invalid && !current()) invalid = true
        return !invalid
    }
    fun observePause(pause: () -> Unit): AutoCloseable {
        val observer = subscribe { if (!admitted()) pause() }
        if (!admitted()) pause()
        return observer
    }
}

internal class MediaTaskControl(
    val profileId: String,
    val role: String,
    val runtime: RunGenerationTools,
    private val leaf: GenerationCatalogLeaf,
) {
    @Volatile var stopConfirmed = false
        private set
    @Volatile var replacementCallId: String? = null
        private set

    fun stop(timeoutMillis: Long = 30_000): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<RunCatalogCloseResult>()
        leaf.observeClose { result.set(it); done.countDown() }
        leaf.close()
        val drained = try {
            done.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS) && result.get() is RunCatalogCloseResult.Drained
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (drained) stopConfirmed = true
        return drained
    }

    @Synchronized fun claimReplacement(callId: String): Boolean {
        if (!stopConfirmed || replacementCallId != null) return false
        replacementCallId = callId
        return true
    }
}
