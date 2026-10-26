package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.app.DaiyuPalette

import app.daiyu.android.ui.components.etaInputContainerColor
import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import androidx.compose.ui.platform.LocalView
import app.daiyu.android.ui.haptics.TouchHaptics
import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.clickable
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
import app.daiyu.android.ui.haptics.selectable
import app.daiyu.android.ui.haptics.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import app.daiyu.android.ui.screens.settings.SettingsSwitch as Switch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsSectionTitle(title: String) {
    val grouped = LocalSettingsPageStyle.current
    Text(
        text = title,
        style = MiuixTheme.textStyles.body2.copy(fontSize = if (grouped) 13.sp else 14.sp,
            fontWeight = if (grouped) FontWeight.Medium else FontWeight.SemiBold),
        color = if (grouped) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp,
            top = if (grouped) 24.dp else 28.dp, bottom = if (grouped) 9.dp else 10.dp),
    )
}

@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val grouped = LocalSettingsPageStyle.current
    val container = if (grouped) {
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(usagePalette().sheet))
    } else {
        Modifier.padding(horizontal = 20.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.surface)
    }
    Column(modifier = modifier.then(container), content = content)
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
    showChevron: Boolean = onClick != null,
    textModifier: Modifier = Modifier,
) {
    val feedbackView = LocalView.current
    Row(
        modifier = Modifier.testTag(tag).then(modifier).fillMaxWidth()
            .then(if (onClick != null) Modifier.semantics { semanticsClick { TouchHaptics.action(feedbackView) { onClick() }; true } }.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).then(textModifier), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        } else if (showChevron) {
            DaiyuIcon(DaiyuIcons.ChevronRight, contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 20.dp)
        }
    }
}

@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, enabled: Boolean = true,
    tag: String = "settings-switch:$title", summary: String? = null,
    leading: (@Composable () -> Unit)? = null, accessory: (@Composable () -> Unit)? = null,
    busy: Boolean = false,
    onCheckedChange: (Boolean) -> Unit) {
    val colors = MiuixTheme.colorScheme
    SettingsRow(title = title, tag = tag, summary = summary, leading = leading, modifier = Modifier.toggleable(
        value = checked, role = Role.Switch, enabled = enabled && !busy, onValueChange = onCheckedChange
    ), trailing = {
        accessory?.invoke()
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = DaiyuPalette.switchTrack,
                checkedBorderColor = DaiyuPalette.switchTrack,
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
    embedded: Boolean = false,
) {
    val container = etaInputContainerColor()
    val placeholder = hint.ifEmpty { if (compact || embedded) label else "" }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var secretVisible by remember(password) { mutableStateOf(false) }
    var editorValue by remember { mutableStateOf(TextFieldValue(value)) }
    val focusRequester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    LaunchedEffect(secretVisible) {
        if (restoreFocus) {
            restoreFocus = false
            if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) focusRequester.requestFocus()
        }
    }
    if (password) DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                restoreFocus = false
                secretVisible = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val toggleSecret: () -> Unit = {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            restoreFocus = fieldFocused
            secretVisible = !secretVisible
        }
    }
    val visibilityButton: @Composable () -> Unit = {
        app.daiyu.android.ui.haptics.material.IconButton(
            enabled = enabled, modifier = Modifier.testTag("settings-secret-visibility:$label"), onClick = toggleSecret,
        ) {
            androidx.compose.material3.Icon(
                if (secretVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = if (secretVisible) "隐藏$secretLabel" else "显示$secretLabel",
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    val field: @Composable () -> Unit = {
        // Compose 1.9.0 retains the previous Password semantics when this node is reused.
        key(password && !secretVisible) {
            BasicTextField(
                value = if (editorValue.text == value) editorValue else editorValue.copy(text = value, composition = null),
                onValueChange = { updated ->
                    if (!app.daiyu.android.ui.components.InputDismissal.inProgress || updated.text != editorValue.text) {
                        editorValue = updated
                        if (updated.text != value) onValueChange(updated.text)
                    }
                },
                enabled = enabled,
                singleLine = !multiline,
                minLines = if (multiline && !embedded) 4 else 1,
                maxLines = if (multiline) 8 else 1,
                textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = imeCursorBrush(MiuixTheme.colorScheme.primary),
                visualTransformation = if (password && !secretVisible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
                modifier = Modifier.managedInput().testTag(tag).fillMaxWidth()
                    .focusRequester(focusRequester).onFocusChanged { fieldFocused = it.isFocused }
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = label },
                decorationBox = { editor ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        editor()
                    }
                })
        }
    }
    if (compact) {
        Row(Modifier.managedInput().testTag("$tag-container").fillMaxWidth()
            .then(if (embedded) Modifier else Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            .clip(RoundedCornerShape(16.dp)).background(container)
            .padding(start = if (embedded) 12.dp else 16.dp,
                end = if (password) 0.dp else if (embedded) 12.dp else 16.dp)
            .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { field() }
            if (password) visibilityButton()
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(Modifier.managedInput().testTag("$tag-container").fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(container).heightIn(min = 48.dp)
                .padding(start = 14.dp, end = if (password) 0.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { field() }
                if (password) visibilityButton()
            }
        }
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
