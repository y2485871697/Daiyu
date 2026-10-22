package app.daiyu.android.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.assistants.AssistantPreviewField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.DaiyuDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.TextButton
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
    preview: Boolean = false,
) {
    // Do not trim or reject empty text: moving out of a folder and clearing search need it.
    var value by remember(title, initial) { mutableStateOf(initial) }
    if (preview) {
        ChatSelectionSheet(title, onDismiss, footer = {
            AssistantPreviewButton("取消", onDismiss, Modifier.weight(1f).testTag("dialog-cancel"))
            AssistantPreviewButton(confirmText, { onConfirm(value) }, Modifier.weight(1f).testTag("dialog-confirm"), primary = true)
        }) {
            AssistantPreviewField(value, { value = it }, hint, Modifier.fillMaxWidth(), fieldModifier = Modifier.testTag("prompt-input"))
        }
        return
    }
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            TextField(
                value = value,
                onValueChange = { value = it },
                label = hint,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.testTag("prompt-input").fillMaxWidth(),
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
    preview: Boolean = false,
) {
    if (preview) {
        ChatSelectionSheet(title, onDismiss, footer = {
            AssistantPreviewButton("取消", onDismiss, Modifier.weight(1f).testTag("dialog-cancel"))
            AssistantPreviewButton(confirmText, onConfirm, Modifier.weight(1f).testTag("dialog-confirm"), primary = !destructive, danger = destructive)
        }) { Text(message, fontSize = 12.sp, lineHeight = 19.2.sp, modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) }
        return
    }
    WindowDialog(show = true, title = title, summary = message, onDismissRequest = onDismiss) {
        MiuixDialogActions(
            confirmText = confirmText,
            destructive = destructive,
            onCancel = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

internal val RetainedChoiceSelect = SemanticsPropertyKey<() -> Unit>("RetainedChoiceSelect")

/** A bounded, scrollable bottom choice surface using the same Miuix palette and typography. */
@Composable
internal fun DaiyuChoiceSheet(
    title: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    engineeringUi: Boolean = false,
    preview: Boolean = false,
) {
    if (preview) {
        ChatSelectionSheet(title, onDismiss, footer = {
            AssistantPreviewButton("取消", onDismiss, Modifier.fillMaxWidth())
        }) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                itemsIndexed(options) { index, option ->
                    val select = { onSelect(index) }
                    val retained = if (engineeringUi) Modifier.semantics { this[RetainedChoiceSelect] = select } else Modifier
                    AssistantPreviewButton(option, select,
                        Modifier.fillMaxWidth().testTag("prompt-choice:$index").then(retained), actionRow = true)
                }
            }
        }
        return
    }
    val colors = MiuixTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        animateContent = false,
    ) {
        Box(
            Modifier.fillMaxSize().testTag("choice-sheet-viewport").dialogDimBackground(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding(),
        ) {
            Column(
                modifier = Modifier.dialogEnterMotion().align(Alignment.BottomCenter).widthIn(max = 640.dp)
                    .fillMaxWidth().padding(top = 12.dp).heightIn(max = 560.dp)
                    .testTag("daiyu-choice-sheet")
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(colors.surfaceContainer)
                    .semantics { paneTitle = title }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        clickFeedback = false,
                        onClick = {},
                    ).bottomSheetContentInsets().padding(16.dp),
            ) {
                Text(title, style = MiuixTheme.textStyles.headline1, color = colors.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    itemsIndexed(options) { index, option ->
                        val select = { onSelect(index) }
                        val retained = if (engineeringUi) Modifier.semantics { this[RetainedChoiceSelect] = select } else Modifier
                        Box(
                            Modifier.testTag("prompt-choice:$index").fillMaxWidth().heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .then(retained).clickable(onClick = select).padding(horizontal = 12.dp, vertical = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(option, style = MiuixTheme.textStyles.body1, color = colors.onSurface)
                        }
                    }
                }
                TextButton(text = "取消", onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("choice-sheet-cancel"))
            }
        }
    }
}
