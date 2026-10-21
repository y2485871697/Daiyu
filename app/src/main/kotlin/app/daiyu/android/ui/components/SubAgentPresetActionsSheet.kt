package app.daiyu.android.ui.components

import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daiyu.android.delegation.SubAgentPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentPresetActionsSheet(preset: SubAgentPreset, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AssistantSheetMotion(entering = sheetState.currentValue == SheetValue.Hidden) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp, dragHandle = { BottomSheetDefaults.DragHandle() }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)
                .semantics { contentDescription = "子代理组操作面板" }) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Rounded.AccountTree, null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(preset.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                SubAgentPresetActionRow("重命名组", Icons.Rounded.Edit, MaterialTheme.colorScheme.onSurface, onRename)
                SubAgentPresetActionRow("删除组", Icons.Rounded.DeleteOutline, MaterialTheme.colorScheme.error, onDelete)
            }
        }
    }
}
@Composable
private fun SubAgentPresetActionRow(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .clickable(role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null) {
            onClick()
        }.padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
