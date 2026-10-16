package app.daiyu.android.terminal

import android.content.Context
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

    init {
        scope.launch {
            val loaded = try { LinuxSettingsApplication.android(context.applicationContext) }
                catch (_: Exception) { null }
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
