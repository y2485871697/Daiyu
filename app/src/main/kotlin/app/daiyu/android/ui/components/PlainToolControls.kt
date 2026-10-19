package app.daiyu.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun PlainToolButton(
    icon: ImageVector, label: String, tag: String, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Box(modifier.size(40.dp).testTag(tag).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick,
    ), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.8f else 0.3f))
    }
}
