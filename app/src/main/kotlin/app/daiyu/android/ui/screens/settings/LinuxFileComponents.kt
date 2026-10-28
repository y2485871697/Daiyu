package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.haptics.miuix.IconButton
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class LinuxFilePanel(val content: @Composable () -> Unit, val actions: @Composable () -> Unit) {
    @Composable operator fun invoke() = content()
}

@Composable
internal fun LinuxFileEntryCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
        .clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.surface), content = content)
}

@Composable
internal fun LinuxFileDirectoryHeader(path: String, parentTag: String, onParent: (() -> Unit)?,
    createTag: String = "", onCreate: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = { onParent?.invoke() }, enabled = onParent != null,
            modifier = Modifier.size(48.dp).testTag(parentTag)) {
            DaiyuIcon(DaiyuIcons.Back, "上级目录", size = 22.dp,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (onParent != null) 1f else .4f))
        }
        Text(path.ifEmpty { "/" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (createTag.isNotEmpty()) IconButton(onClick = { onCreate?.invoke() }, enabled = onCreate != null,
            modifier = Modifier.size(48.dp).testTag(createTag)) {
            DaiyuIcon(DaiyuIcons.Add, "新建文件夹", size = 22.dp, tint = MiuixTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun LinuxFileEmptyState(message: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 176.dp).padding(24.dp).testTag("linux-file-empty-state"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        DaiyuIcon(DaiyuIcons.Folder, null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .55f), size = 36.dp)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
