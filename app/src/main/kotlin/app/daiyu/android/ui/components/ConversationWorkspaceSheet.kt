package app.daiyu.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import app.daiyu.android.ui.components.subagent.ConversationAgentHandle
import app.daiyu.android.ui.components.subagent.ConversationAgentIconButton
import app.daiyu.android.ui.components.subagent.ConversationAgentSheet
import app.daiyu.android.ui.components.subagent.ConversationAgentTypography
import app.daiyu.android.ui.components.subagent.conversationAgentColors
import app.daiyu.android.ui.haptics.clickable
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Display-only metadata; filesystem access and the draft belong to the conversation owner. */
data class WorkspaceSourceOption(val id: String, val title: String, val rootPath: String, val initialPath: String)
data class WorkspaceDirectoryOption(val path: String, val parentPath: String?, val folders: List<String>,
    val selectable: Boolean = true)

@Composable
fun ConversationWorkspaceSheet(
    visible: Boolean,
    sources: List<WorkspaceSourceOption>,
    path: String,
    busy: Boolean = false,
    error: String? = null,
    choosing: Boolean = false,
    listing: Boolean = false,
    directory: WorkspaceDirectoryOption? = null,
    onBrowse: () -> Unit,
    onSourceChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onSources: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    WithoutPressRipple {
        ConversationAgentTypography {
            val colors = conversationAgentColors()
            val theme = MiuixTheme.colorScheme
            ConversationAgentSheet(onDismiss, useDefaultHeight = choosing,
                modifier = Modifier.testTag("conversation-workspace-sheet")) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                    ConversationAgentHandle()
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (choosing) "选择工作目录" else "工作区", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f))
                        ConversationAgentIconButton(onClick = onDismiss,
                            modifier = Modifier.size(48.dp).testTag("workspace-close")) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
                        }
                    }
                    if (!choosing) {
                        val shape = RoundedCornerShape(14.dp)
                        Row(Modifier.fillMaxWidth().testTag("workspace-path-card").clip(shape)
                            .background(colors.field).border(1.dp, colors.line, shape)
                            .clickable(enabled = !busy, role = Role.Button,
                                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBrowse)
                            .padding(horizontal = 14.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.FolderOpen, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                            Text(path, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.accent)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ConversationAgentIconButton(enabled = !busy && !listing,
                                onClick = { directory?.parentPath?.let(onNavigate) ?: onSources() },
                                modifier = Modifier.size(48.dp).testTag("workspace-parent")) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "上一级", tint = colors.accent)
                            }
                            Text(directory?.path ?: "/", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).testTag("workspace-current-path"), maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(color = colors.line)
                        Box(Modifier.fillMaxWidth().weight(1f).testTag("workspace-directory-body")) {
                            if (listing) {
                                CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.Center), color = colors.accent)
                            } else {
                                LazyColumn(Modifier.fillMaxSize().testTag("workspace-directories")) {
                                    if (directory == null) {
                                        items(sources, key = { it.id }) { source ->
                                            WorkspaceFolderRow(source.title, "workspace-source:${source.id}", !busy,
                                                onClick = { onSourceChange(source.id) })
                                        }
                                    } else {
                                        items(directory.folders, key = { it }) { name ->
                                            WorkspaceFolderRow(name, "workspace-folder:$name", !busy,
                                                onClick = { onNavigate(directory.path.trimEnd('/') + "/" + name) })
                                        }
                                    }
                                }
                            }
                        }
                        if (error != null) {
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp).testTag("workspace-error")
                                    .semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        HorizontalDivider(color = colors.line)
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConversationAgentButton("取消", enabled = !busy, primary = true,
                                primaryColor = theme.secondary, onPrimaryColor = theme.onSecondary,
                                modifier = Modifier.weight(1f).testTag("workspace-cancel"), onClick = onCancel)
                            ConversationAgentButton("使用", enabled = !busy && !listing && directory?.selectable == true,
                                primary = true, primaryColor = theme.primary, onPrimaryColor = theme.onPrimary,
                                modifier = Modifier.weight(1f).testTag("workspace-save"), onClick = onSave)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFolderRow(name: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = conversationAgentColors()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
        .clickable(enabled = enabled, role = Role.Button,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Rounded.FolderOpen, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
