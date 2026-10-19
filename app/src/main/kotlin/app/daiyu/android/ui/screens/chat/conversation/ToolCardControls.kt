package app.daiyu.android.ui.screens.chat.conversation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ToolCardControls(step: WorkStepUi, onAction: (ToolCardActionRequest) -> Unit) {
    val actions = toolCardActions(step.category)
    if (actions.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            TextButton(text = action.label, modifier = Modifier.heightIn(min = 44.dp).testTag("tool-action:${action.name}"),
                onClick = { onAction(step.actionRequest(action)) })
        }
    }
    when (step.category) {
        "image_read" -> ToolMediaSurface(step, null, false, null, null, onAction = onAction)
        "browser" -> if (step.categoryDetails?.rich?.fields?.any { it.label == "动作" && it.value == "screenshot" } == true)
            ToolMediaSurface(step, null, false, null, null, onAction = onAction)
        "speech" -> ToolAudioControls(step, onAction = onAction)
    }
}

@Composable
internal fun ToolMediaSurface(step: WorkStepUi, thumbnail: Bitmap?, video: Boolean, durationMs: Long?,
    assetKey: String?, onPreview: (() -> Unit)? = null, onAction: (ToolCardActionRequest) -> Unit) {
    val action = if (video) ToolCardAction.PLAY_VIDEO else ToolCardAction.OPEN_IMAGE
    Column(Modifier.fillMaxWidth().testTag("tool-media-surface:${step.toolCallId}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 240.dp).clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClickLabel = if (onPreview != null) "预览图片" else action.label) {
                if (onPreview != null) onPreview() else onAction(step.actionRequest(action, assetKey))
            },
            contentAlignment = Alignment.Center) {
            if (thumbnail != null) Image(thumbnail.asImageBitmap(), if (video) "视频封面" else "图片",
                Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit)
            if (video || thumbnail == null) Icon(if (video) DaiyuIcons.Resume else DaiyuIcons.Image,
                action.label, Modifier.size(36.dp), tint = MiuixTheme.colorScheme.primary)
            if (video && durationMs != null) Text(mediaTime(durationMs), Modifier.align(Alignment.BottomEnd)
                .padding(8.dp).clip(RoundedCornerShape(4.dp)).background(MiuixTheme.colorScheme.surface).padding(4.dp),
                style = MiuixTheme.textStyles.footnote1)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(action, ToolCardAction.SAVE_MEDIA).forEach { item ->
                TextButton(text = item.label, modifier = Modifier.heightIn(min = 44.dp).testTag("tool-action:${item.name}"),
                    onClick = { onAction(step.actionRequest(item, assetKey)) })
            }
        }
    }
}

@Composable
internal fun ToolAudioControls(step: WorkStepUi, position: Float = 0f, durationMs: Long? = null,
    playing: Boolean = false, onAction: (ToolCardActionRequest) -> Unit) {
    var seeking by remember(step.owner, step.toolCallId) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.surfaceVariant)
        .padding(10.dp).testTag("tool-audio:${step.toolCallId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (playing) DaiyuIcons.Pause else DaiyuIcons.Resume, if (playing) "暂停" else "播放",
                Modifier.size(44.dp).clickable(role = Role.Button) { onAction(step.actionRequest(if (playing) ToolCardAction.PAUSE_AUDIO else ToolCardAction.PLAY_AUDIO)) }
                    .padding(10.dp), tint = MiuixTheme.colorScheme.primary)
            Text(if (durationMs == null) "--:-- / --:--" else "${mediaTime((durationMs * position).toLong())} / ${mediaTime(durationMs)}",
                style = MiuixTheme.textStyles.footnote1)
        }
        Slider(value = seeking ?: position.coerceIn(0f, 1f), onValueChange = { seeking = it }, onValueChangeFinished = {
            seeking?.let { onAction(step.actionRequest(ToolCardAction.SEEK_AUDIO, position = it)) }
            seeking = null
        }, modifier = Modifier.fillMaxWidth().testTag("tool-audio-position"))
        TextButton(text = ToolCardAction.SAVE_MEDIA.label, modifier = Modifier.heightIn(min = 44.dp),
            onClick = { onAction(step.actionRequest(ToolCardAction.SAVE_MEDIA)) })
    }
}
