package app.daiyu.android.terminal

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface TerminalApplicationState {
    data object Loading : TerminalApplicationState
    data class Ready(val application: LinuxSettingsApplication) : TerminalApplicationState
    data object Unavailable : TerminalApplicationState
}

/** One process owner. Catalog IO finishes before any terminal can be opened. */
internal class TerminalApplicationProvider(context: Context) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closed = false
    private val mutableState = MutableStateFlow<TerminalApplicationState>(TerminalApplicationState.Loading)
    val state: StateFlow<TerminalApplicationState> = mutableState.asStateFlow()
    @Volatile var initializationFailure: String? = null
        private set

    init {
        scope.launch {
            val loaded = try { LinuxSettingsApplication.android(context.applicationContext) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    initializationFailure = terminalInitializationFailureCode(failure)
                    null
                }
            val release = synchronized(lock) {
                if (closed) true else {
                    mutableState.value = loaded?.let(TerminalApplicationState::Ready)
                        ?: TerminalApplicationState.Unavailable
                    false
                }
            }
            if (release) loaded?.close()
        }
    }

    override fun close() {
        val loaded = synchronized(lock) {
            if (closed) return
            closed = true
            (mutableState.value as? TerminalApplicationState.Ready)?.application.also {
                mutableState.value = TerminalApplicationState.Unavailable
            }
        }
        scope.cancel()
        loaded?.close()
    }
}

internal fun terminalInitializationFailureCode(failure: Exception): String {
    val message = failure.message
    return when (message) {
        "linux_catalog_symlink", "linux_catalog_corrupt", "linux_catalog_version",
        "linux_catalog_noncanonical", "linux_catalog_duplicate_reference",
        "linux_path_identity_unavailable", "linux_catalog_store_limit", "linux_environment_store_limit" -> message
        else -> when (failure) {
            is SecurityException -> "security_denied"
            is java.io.IOException -> "io_failed"
            is org.json.JSONException -> "catalog_json_failed"
            else -> "initialization_failed"
        }
    }
}
