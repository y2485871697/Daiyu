package app.daiyu.android.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

@Composable
internal fun FloatingPageActionsLayout(
    modifier: Modifier = Modifier,
    content: @Composable (bottomSpacerMinHeight: Dp) -> Unit,
    actions: @Composable () -> Unit,
) {
    var actionsHeight by remember { mutableIntStateOf(0) }
    val bottomSpacerMinHeight = with(LocalDensity.current) { actionsHeight.toDp() }
    Box(modifier.fillMaxSize()) {
        content(bottomSpacerMinHeight)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { actionsHeight = it.height }) {
            actions()
        }
    }
}
