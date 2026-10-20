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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yukonga.miuix.kmp.basic.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
        style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 10.dp),
    )
}

@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surface),
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
    modifier: Modifier = Modifier,
    tag: String = "settings-row:$title",
) {
    Row(
        modifier = Modifier.testTag(tag).then(modifier).fillMaxWidth()
            .then(if (onClick != null) Modifier.semantics { semanticsClick { onClick(); true } }.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                color = MiuixTheme.colorScheme.onSurface)
            if (!summary.isNullOrBlank()) {
                Text(summary, style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 19.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Row(Modifier.widthIn(max = 112.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
        } else if (onClick != null) {
            DaiyuIcon(DaiyuIcons.ChevronRight, contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 20.dp)
        }
    }
}

@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, enabled: Boolean = true,
    tag: String = "settings-switch:$title", summary: String? = null,
    leading: (@Composable () -> Unit)? = null, accessory: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit) {
    val colors = MiuixTheme.colorScheme
    SettingsRow(title = title, tag = tag, summary = summary, leading = leading, modifier = Modifier.toggleable(
        value = checked, role = Role.Switch, enabled = enabled, onValueChange = onCheckedChange
    ), trailing = {
        accessory?.invoke()
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
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
internal fun SettingsRadioRow(title: String, selected: Boolean, enabled: Boolean = true, summary: String? = null, onClick: () -> Unit) {
    Box(Modifier.testTag("settings-radio:$title").fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, enabled = enabled, onClick = onClick)) {
        SettingsRow(title = title, summary = summary, trailing = {
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
    tag: String = "settings-field:$label",
    secretLabel: String = "密钥",
    compact: Boolean = false,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var secretVisible by remember(password) { mutableStateOf(false) }
    if (password) DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) secretVisible = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val container = if (compact) Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        .clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 16.dp, vertical = 10.dp)
    else Modifier.fillMaxWidth().padding(16.dp)
    Column(container, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = if (compact) MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 16.sp) else MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
            if (password && !compact) TextButton(text = if (secretVisible) "隐藏$secretLabel" else "显示$secretLabel",
                enabled = enabled, modifier = Modifier.testTag("settings-secret-visibility:$label"), onClick = {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) secretVisible = !secretVisible
                })
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = !multiline,
            minLines = if (multiline) 4 else 1,
            maxLines = if (multiline) 8 else 1,
            textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            visualTransformation = if (password && !secretVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.testTag(tag).fillMaxWidth()
                .then(if (compact) Modifier else Modifier.clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant).padding(14.dp))
                .semantics { contentDescription = label },
            decorationBox = { editor ->
                if (!compact) Box {
                    if (value.isEmpty()) Text(hint, style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    editor()
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(hint, style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        editor()
                    }
                    if (password && compact) androidx.compose.material3.IconButton(
                        enabled = enabled, modifier = Modifier.testTag("settings-secret-visibility:$label"),
                        onClick = {
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) secretVisible = !secretVisible
                        },
                    ) {
                        androidx.compose.material3.Icon(
                            if (secretVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            contentDescription = if (secretVisible) "隐藏$secretLabel" else "显示$secretLabel",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
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
