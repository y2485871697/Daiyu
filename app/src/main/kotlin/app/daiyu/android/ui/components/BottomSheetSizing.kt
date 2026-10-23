package app.daiyu.android.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun defaultBottomSheetHeight(maxHeight: Dp): Dp {
    val availableHeight = (maxHeight - 20.dp).coerceAtLeast(0.dp)
    return minOf((maxHeight - 48.dp).coerceIn(300.dp, 520.dp), availableHeight)
}
