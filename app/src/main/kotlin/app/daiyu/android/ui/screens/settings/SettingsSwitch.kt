package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.haptics.material.Switch
import app.daiyu.android.ui.haptics.toggleable

@Composable
internal fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val target = if (onCheckedChange != null) {
        Modifier.size(48.dp).toggleable(
            value = checked, enabled = enabled, role = Role.Switch,
            interactionSource = interactionSource, indication = null,
            onValueChange = onCheckedChange,
        )
    } else Modifier.size(36.4.dp, 22.4.dp)
    Box(modifier.then(target), contentAlignment = Alignment.Center) {
        // Scale only the Material 1.4 track; keep interaction and accessibility unscaled.
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.requiredSize(52.dp, 32.dp).scale(.7f),
            enabled = enabled,
            colors = colors,
            interactionSource = interactionSource,
        )
    }
}
