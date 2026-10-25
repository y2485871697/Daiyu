package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daiyu.android.ChatApplication
import app.daiyu.android.balance.BalanceState
import app.daiyu.android.storage.ProviderRegistry
import kotlinx.coroutines.CancellationException

@Composable
internal fun modelPickerProviderBalance(provider: ChatModelProviderUi): BalanceState? {
    val host = (LocalContext.current.applicationContext as? ChatApplication)?.providerBalanceHost
    if (host == null || !ProviderRegistry.validSlotId(provider.id)) return provider.balance
    return key(host, provider.id) {
        val runtime by produceState(initialValue = host.cached(provider.id)) {
            try { value = host.get(provider.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { value = null }
        }
        runtime?.let { balanceRuntime ->
            key(balanceRuntime) { balanceRuntime.state.collectAsStateWithLifecycle().value }
        }
    }
}
