package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LinuxFilePanelHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = MiuixTheme.colorScheme.onSurface)
        actions()
    }
}

@Composable
internal fun LinuxFileAction(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.testTag(tag).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Medium),
            color = MiuixTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .45f))
    }
}

@Composable
internal fun LinuxFileDescription(text: String) {
    Text(text, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 20.sp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
internal fun LinuxFileDirectoryHeader(path: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DaiyuIcon(DaiyuIcons.Folder, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 18.dp)
        Text(path.ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 20.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
