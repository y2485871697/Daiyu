package app.daiyu.android.ui.components

import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChildStopChoiceSheet(onDismiss: () -> Unit, onContinue: () -> Unit,
    onPause: () -> Unit, onStop: () -> Unit) {
    val density = LocalDensity.current
    val sheetState = remember(density) {
        SheetState(skipPartiallyExpanded = true,
            positionalThreshold = { with(density) { 56.dp.toPx() } },
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            initialValue = SheetValue.Expanded)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        modifier = Modifier.dialogEnterMotion().testTag("child-stop-choice-sheet"),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)
            .semantics { contentDescription = "子任务处理" }) {
            Text("子任务处理", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
            ChildStopActionRow("继续", Icons.Rounded.PlayArrow, "main-stop-continue-children", onClick = onContinue)
            ChildStopActionRow("暂停", Icons.Rounded.Pause, "main-stop-keep-children", onClick = onPause)
            ChildStopActionRow("停止", Icons.Rounded.Stop, "main-stop-cancel-children",
                tint = MaterialTheme.colorScheme.error, onClick = onStop)
        }
    }
}

@Composable
private fun ChildStopActionRow(label: String, icon: ImageVector, tag: String,
    tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag)
        .clickable(role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null) {
            onClick()
        }.padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
