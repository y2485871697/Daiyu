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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ToolMediaSurface(step: WorkStepUi, thumbnail: Bitmap?, video: Boolean, durationMs: Long?,
    assetKey: String?, onPreview: (() -> Unit)? = null, onAction: (ToolCardActionRequest) -> Unit) {
    val action = if (video) ToolCardAction.PLAY_VIDEO else ToolCardAction.OPEN_IMAGE
    Column(Modifier.fillMaxWidth().testTag("tool-media-surface:${step.toolCallId}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 140.dp).clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .conversationClickable(role = Role.Button, onClickLabel = if (onPreview != null) "预览图片" else action.label) {
                if (onPreview != null) onPreview() else onAction(step.actionRequest(action, assetKey))
            },
            contentAlignment = Alignment.Center) {
            if (thumbnail != null) Image(thumbnail.asImageBitmap(), if (video) "视频封面" else "图片",
                Modifier.fillMaxWidth().heightIn(max = 140.dp), contentScale = ContentScale.Crop)
            if (video || thumbnail == null) Icon(if (video) DaiyuIcons.Resume else DaiyuIcons.Image,
                action.label, Modifier.size(36.dp), tint = MiuixTheme.colorScheme.primary)
            if (video && durationMs != null) Text(mediaTime(durationMs), Modifier.align(Alignment.BottomEnd)
                .padding(8.dp).clip(RoundedCornerShape(4.dp)).background(MiuixTheme.colorScheme.surface).padding(4.dp),
                style = MiuixTheme.textStyles.footnote1)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(action, ToolCardAction.SAVE_MEDIA).forEach { item ->
                ToolMiniAction(text = item.label, modifier = Modifier.testTag("tool-action:${item.name}"),
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
                Modifier.size(48.dp).conversationClickable(role = Role.Button) { onAction(step.actionRequest(if (playing) ToolCardAction.PAUSE_AUDIO else ToolCardAction.PLAY_AUDIO)) }
                    .padding(12.dp), tint = MiuixTheme.colorScheme.primary)
            Text(if (durationMs == null) "--:-- / --:--" else "${mediaTime((durationMs * position).toLong())} / ${mediaTime(durationMs)}",
                style = MiuixTheme.textStyles.footnote1)
        }
        Slider(value = seeking ?: position.coerceIn(0f, 1f), onValueChange = { seeking = it }, onValueChangeFinished = {
            seeking?.let { onAction(step.actionRequest(ToolCardAction.SEEK_AUDIO, position = it)) }
            seeking = null
        }, modifier = Modifier.fillMaxWidth().testTag("tool-audio-position"))
        ToolMiniAction(text = ToolCardAction.SAVE_MEDIA.label,
            onClick = { onAction(step.actionRequest(ToolCardAction.SAVE_MEDIA)) })
    }
}

@Composable
internal fun ToolMiniAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).conversationClickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 20.sp),
            color = conversationColors().accent.copy(alpha = if (enabled) 1f else .4f))
    }
}
