package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf

/** Presentation only; a validated resumed detail may reuse this without reviving its old owner. */
internal class ProviderPageUiState {
    val configurationListState = LazyListState()
    var modelListState = LazyListState()
        private set
    val modelTab = mutableStateOf(false)
    val modelQuery = mutableStateOf("")

    fun checkpointModelPositionForResume() {
        val previous = modelListState
        modelListState = LazyListState(previous.firstVisibleItemIndex, previous.firstVisibleItemScrollOffset)
    }
}
