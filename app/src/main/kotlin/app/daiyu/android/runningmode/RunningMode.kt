package app.daiyu.android.runningmode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal enum class RunningMode { NORMAL, ROOT, SHIZUKU }

internal interface RunningModePersistence {
    fun read(): String?
    fun write(value: String)
}

internal class RunningModeStore(private val persistence: RunningModePersistence) {
    private val mutable = MutableStateFlow(runCatching {
        persistence.read()?.let(RunningMode::valueOf) ?: RunningMode.NORMAL
    }.getOrDefault(RunningMode.NORMAL))
    val mode = mutable.asStateFlow()
    @Synchronized fun invalidate(value: RunningMode) {
        if (mutable.value != value) return
        mutable.value = RunningMode.NORMAL
        runCatching { persistence.write(RunningMode.NORMAL.name) }
    }
    @Synchronized fun save(value: RunningMode): Boolean = try {
        persistence.write(value.name)
        mutable.value = value
        true
    } catch (_: Exception) { false }
}

internal interface RunningModeAuthorization {
    suspend fun requestRoot(): Boolean
    suspend fun requestShizuku(): Boolean
    suspend fun shizukuGranted(): Boolean
}

internal data class RunningModeUi(
    val pending: RunningMode? = null,
    val rootFailure: String? = null,
    val shizukuFailure: String? = null,
    val normalFailure: String? = null,
)

internal class RunningModeEditor(private val store: RunningModeStore,
    private val authorization: RunningModeAuthorization) : AutoCloseable {
    private val mutable = MutableStateFlow(RunningModeUi())
    val state = mutable.asStateFlow()
    private var active = true
    private var revision = 0L

    suspend fun select(mode: RunningMode, awaitForeground: suspend () -> Unit = {}) {
        synchronized(this) {
            if (!active || mutable.value.pending != null) return
            revision++
            mutable.value = mutable.value.copy(pending = mode, rootFailure = null, shizukuFailure = null, normalFailure = null)
        }
        try {
            val allowed = when (mode) {
                RunningMode.NORMAL -> true
                RunningMode.ROOT -> authorization.requestRoot()
                RunningMode.SHIZUKU -> authorization.requestShizuku()
            }
            awaitForeground()
            val confirmed = allowed && (mode != RunningMode.SHIZUKU || authorization.shizukuGranted())
            withContext(Dispatchers.IO) {
                synchronized(this@RunningModeEditor) {
                    if (!active) return@withContext
                    if (!confirmed) store.invalidate(mode)
                    val failure = when {
                        !confirmed -> if (mode == RunningMode.ROOT) "设备无root" else "授权失败"
                        !store.save(mode) -> "保存失败"
                        else -> null
                    }
                    mutable.value = RunningModeUi(
                        rootFailure = failure.takeIf { mode == RunningMode.ROOT },
                        shizukuFailure = failure.takeIf { mode == RunningMode.SHIZUKU },
                        normalFailure = failure.takeIf { mode == RunningMode.NORMAL })
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            withContext(Dispatchers.IO) {
                synchronized(this@RunningModeEditor) {
                    if (active) {
                        store.invalidate(mode)
                        mutable.value = RunningModeUi(
                            rootFailure = "设备无root".takeIf { mode == RunningMode.ROOT },
                            shizukuFailure = "授权失败".takeIf { mode == RunningMode.SHIZUKU })
                    }
                }
            }
        } finally {
            synchronized(this) { mutable.value = mutable.value.copy(pending = null) }
        }
    }

    suspend fun refreshShizuku() {
        val observedRevision = synchronized(this) {
            if (!active || mutable.value.pending != null || store.mode.value != RunningMode.SHIZUKU) return
            revision
        }
        val granted = try { authorization.shizukuGranted() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        withContext(Dispatchers.IO) {
            synchronized(this@RunningModeEditor) {
                if (active && revision == observedRevision && mutable.value.pending == null && store.mode.value == RunningMode.SHIZUKU && !granted) {
                    store.invalidate(RunningMode.SHIZUKU)
                    mutable.value = RunningModeUi(shizukuFailure = "授权失败")
                }
            }
        }
    }

    @Synchronized override fun close() { active = false }
}
