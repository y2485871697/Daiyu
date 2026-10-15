package app.daiyu.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Confirm callbacks are deliberately not followed by dismissal; the host owns visibility. */
@Composable
internal fun DaiyuTextInputDialog(
    title: String,
    initial: String,
    hint: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Do not trim or reject empty text: moving out of a folder and clearing search need it.
    var value by remember(title, initial) { mutableStateOf(initial) }
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            TextField(
                value = value,
                onValueChange = { value = it },
                label = hint,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = confirmText,
                onCancel = onDismiss,
                onConfirm = { onConfirm(value) },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
internal fun DaiyuConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(show = true, title = title, summary = message, onDismissRequest = onDismiss) {
        MiuixDialogActions(
            confirmText = confirmText,
            destructive = destructive,
            onCancel = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

/** A bounded, scrollable bottom choice surface using the same Miuix palette and typography. */
@Composable
internal fun DaiyuChoiceSheet(
    title: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(colors.onSurface.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ).imePadding().navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp)
                    .fillMaxWidth().padding(12.dp).heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)
                    .semantics { paneTitle = title }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ).padding(16.dp),
            ) {
                Text(title, style = MiuixTheme.textStyles.headline1, color = colors.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    itemsIndexed(options) { index, option ->
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(index) }.padding(horizontal = 12.dp, vertical = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(option, style = MiuixTheme.textStyles.body1, color = colors.onSurface)
                        }
                    }
                }
                TextButton(text = "取消", onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}
