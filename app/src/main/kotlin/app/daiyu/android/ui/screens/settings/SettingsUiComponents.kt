package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        content = content,
    )
}

@Composable
internal fun SettingsRow(
    title: String,
    onClick: (() -> Unit)? = null,
    summary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.testTag("settings-row:$title").fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface)
            if (!summary.isNullOrBlank()) {
                Text(summary, style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        } else if (onClick != null) {
            DaiyuIcon(DaiyuIcons.ChevronRight, contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 20.dp)
        }
    }
}

@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MiuixTheme.colorScheme
    SettingsRow(title = title, trailing = {
        Switch(
            modifier = Modifier.testTag("settings-switch:$title"),
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                checkedBorderColor = colors.primary,
                uncheckedThumbColor = colors.onSurfaceVariantSummary,
                uncheckedTrackColor = colors.surface,
                uncheckedBorderColor = colors.onSurfaceVariantSummary,
            ),
        )
    })
}

@Composable
internal fun SettingsRadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.testTag("settings-radio:$title").fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)) {
        SettingsRow(title = title, trailing = {
            if (selected) {
                DaiyuIcon(DaiyuIcons.Check, contentDescription = "已选择",
                    tint = MiuixTheme.colorScheme.primary, size = 24.dp)
            }
        })
    }
}

/** Foundation editor keeps password/custom JSON out of saveable state. */
@Composable
internal fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    password: Boolean = false,
    multiline: Boolean = false,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = !multiline,
            minLines = if (multiline) 4 else 1,
            maxLines = if (multiline) 8 else 1,
            textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.testTag("settings-field:$label").fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(MiuixTheme.colorScheme.surface).padding(12.dp)
                .semantics { contentDescription = label },
            decorationBox = { editor ->
                Box {
                    if (value.isEmpty()) Text(hint, style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    editor()
                }
            },
        )
    }
}

@Composable
internal fun SettingsNote(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
    )
}
