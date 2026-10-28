package app.daiyu.android.ui.components

import app.daiyu.android.ui.app.destructiveColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import app.daiyu.android.ui.haptics.material.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import app.daiyu.android.ui.components.subagent.ConversationAgentHandle
import app.daiyu.android.ui.components.subagent.ConversationAgentIconButton
import app.daiyu.android.ui.components.subagent.ConversationAgentSheet
import app.daiyu.android.ui.components.subagent.ConversationAgentTypography
import app.daiyu.android.ui.components.subagent.conversationAgentColors
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.haptics.combinedClickable
import app.daiyu.android.ui.app.LocalAppearanceDark
import app.daiyu.android.ui.app.LocalAppearanceSettings
import app.daiyu.android.ui.app.resolveAppearanceActions

internal val RetainedWorkspaceAction = SemanticsPropertyKey<() -> Unit>("RetainedWorkspaceAction")

/** Display-only metadata; filesystem access and the draft belong to the conversation owner. */
data class WorkspaceItemOption(val path: String)
data class WorkspaceFileOption(val name: String, val directory: Boolean)
data class WorkspaceDirectoryOption(val path: String, val parentPath: String?, val folders: List<String>,
    val selectable: Boolean = true, val entries: List<WorkspaceFileOption> = folders.map { WorkspaceFileOption(it, true) },
    val truncated: Boolean = false)

@Composable
fun ConversationWorkspaceSheet(
    visible: Boolean,
    path: String,
    busy: Boolean = false,
    error: String? = null,
    choosing: Boolean = false,
    listing: Boolean = false,
    directory: WorkspaceDirectoryOption? = null,
    onBrowse: () -> Unit,
    onNavigate: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    workspaces: List<WorkspaceItemOption> = listOf(WorkspaceItemOption(path)),
    activeWorkspaceIndex: Int = 0,
    onSelectWorkspace: (Int) -> Unit = {},
    onEditWorkspace: (Int) -> Unit = {},
    editingWorkspace: Boolean = false,
    onDeleteWorkspaces: (Set<Int>) -> Unit = {},
    workspaceRoots: List<String> = emptyList(),
    onCopyPath: (String) -> Unit = {},
    selectionMode: Boolean = false,
    selectedWorkspaces: Set<Int> = emptySet(),
    onLongPressFile: (String) -> Unit = {},
    onSelectionChange: (Boolean, Set<Int>) -> Unit = { _, _ -> },
) {
    if (!visible) return
    WithoutPressRipple {
        ConversationAgentTypography {
            val colors = conversationAgentColors()
            val actions = resolveAppearanceActions(LocalAppearanceSettings.current, LocalAppearanceDark.current)
            ConversationAgentSheet(onDismiss, useDefaultHeight = true,
                modifier = Modifier.testTag("conversation-workspace-sheet")) {
                Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                    ConversationAgentHandle()
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (choosing) { if (editingWorkspace) "更改工作目录" else "选择工作目录" } else "会话工作区", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f))
                    }
                    if (!choosing) {
                        val selecting = selectionMode
                        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("workspace-choices"),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(workspaces.size, key = { it }) { index ->
                                val workspace = workspaces[index]
                                val checked = index in selectedWorkspaces
                                val active = index == activeWorkspaceIndex
                                val shape = RoundedCornerShape(14.dp)
                                Row(Modifier.fillMaxWidth().testTag("workspace-path-card:$index")
                                    .semantics { selected = if (selecting) checked else active }.clip(shape)
                                    .background(colors.field).border(1.dp, if (checked || active) colors.accent else colors.line, shape)
                                    .combinedClickable(enabled = !busy, role = Role.Button,
                                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                                        onLongClickLabel = "选择工作区",
                                        onLongClick = { onSelectionChange(true, selectedWorkspaces + index) },
                                        onClick = {
                                            if (selecting) onSelectionChange(true, if (checked) selectedWorkspaces - index
                                                else selectedWorkspaces + index)
                                            else onSelectWorkspace(index)
                                        })
                                    .padding(horizontal = 14.dp, vertical = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Rounded.FolderOpen, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                                    val display = workspacePathPresentation(workspace.path, workspaceRoots)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(display.name, style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.testTag("workspace-name:$index"),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        display.location?.let { location ->
                                            Text(location, style = MaterialTheme.typography.bodyMedium, color = colors.muted,
                                                modifier = Modifier.testTag("workspace-location:$index"),
                                                maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                        }
                                    }
                                    if (selecting) {
                                        Checkbox(checked = checked, onCheckedChange = null,
                                            colors = CheckboxDefaults.colors(checkedColor = actions.primary,
                                                checkmarkColor = actions.onPrimary))
                                    } else {
                                        if (active) Icon(Icons.Rounded.CheckCircle, "当前工作区", tint = colors.accent)
                                        ConversationAgentIconButton(enabled = !busy, onClick = { onEditWorkspace(index) },
                                            modifier = Modifier.size(48.dp).testTag("workspace-edit:$index")) {
                                            Icon(Icons.Rounded.Edit, "更改工作区目录", tint = colors.accent)
                                        }
                                    }
                                }
                            }
                        }
                        if (error != null) WorkspaceError(error)
                        HorizontalDivider(color = colors.line)
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (selecting) {
                                ConversationAgentButton("取消", enabled = !busy, primary = true,
                                    primaryColor = actions.secondaryContainer, onPrimaryColor = actions.onSecondary,
                                    modifier = Modifier.weight(1f).testTag("workspace-selection-cancel"),
                                    onClick = { onSelectionChange(false, emptySet()) })
                                ConversationAgentButton("删除", enabled = !busy && selectedWorkspaces.isNotEmpty(), primary = true,
                                    primaryColor = destructiveColors().container, onPrimaryColor = destructiveColors().content,
                                    modifier = Modifier.weight(1f).testTag("workspace-delete"),
                                    onClick = { onDeleteWorkspaces(selectedWorkspaces) })
                            } else {
                                ConversationAgentButton("添加", enabled = !busy, primary = true,
                                    primaryColor = actions.primary, onPrimaryColor = actions.onPrimary,
                                    modifier = Modifier.weight(1f).testTag("workspace-add")
                                        .semantics { this[RetainedWorkspaceAction] = onBrowse }, onClick = onBrowse)
                            }
                        }
                    } else {
                        val fullPath = directory?.path ?: path
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ConversationAgentIconButton(enabled = !busy && !listing && directory?.parentPath != null,
                                onClick = { directory?.parentPath?.let(onNavigate) },
                                modifier = Modifier.size(48.dp).testTag("workspace-parent")) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "上一级", tint = colors.accent)
                            }
                            Box(Modifier.weight(1f).heightIn(max = 96.dp)) {
                                key(fullPath) {
                                    SelectionContainer {
                                        Text(fullPath, style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                                .testTag("workspace-current-path"))
                                    }
                                }
                            }
                            ConversationAgentIconButton(enabled = !busy && !listing,
                                onClick = { onCopyPath(fullPath) },
                                modifier = Modifier.size(48.dp).testTag("workspace-copy-path")) {
                                Icon(Icons.Rounded.ContentCopy, "复制完整路径", tint = colors.accent)
                            }
                        }
                        HorizontalDivider(color = colors.line)
                        Box(Modifier.fillMaxWidth().weight(1f).testTag("workspace-directory-body")) {
                            if (listing) {
                                CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.Center), color = colors.accent)
                            } else {
                                LazyColumn(Modifier.fillMaxSize().testTag("workspace-directories")) {
                                    if (directory != null) {
                                        items(directory.entries, key = { it.name }) { entry ->
                                            WorkspaceFolderRow(entry.name, "workspace-${if (entry.directory) "folder" else "file"}:${entry.name}", !busy,
                                                directory = entry.directory, onLongClick = { onLongPressFile(entry.name) },
                                                onClick = { if (entry.directory) onNavigate(directory.path.trimEnd('/') + "/" + entry.name) })
                                        }
                                    }
                                }
                            }
                        }
                        if (directory?.truncated == true) WorkspaceError("仅显示前 100 项，请缩小目录范围")
                        if (error != null) {
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp).testTag("workspace-error")
                                    .semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        HorizontalDivider(color = colors.line)
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConversationAgentButton("取消", enabled = !busy, primary = true,
                                primaryColor = actions.secondaryContainer, onPrimaryColor = actions.onSecondary,
                                modifier = Modifier.weight(1f).testTag("workspace-cancel"), onClick = onCancel)
                            ConversationAgentButton("使用", enabled = !busy && !listing && directory?.selectable == true,
                                primary = true, primaryColor = actions.primary, onPrimaryColor = actions.onPrimary,
                                modifier = Modifier.weight(1f).testTag("workspace-save")
                                        .semantics { this[RetainedWorkspaceAction] = onSave }, onClick = onSave)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFolderRow(name: String, tag: String, enabled: Boolean, directory: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    val colors = conversationAgentColors()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
        .combinedClickable(enabled = enabled, role = Role.Button,
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onLongClickLabel = "文件操作", onLongClick = onLongClick, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(if (directory) Icons.Rounded.FolderOpen else app.daiyu.android.ui.icons.DaiyuIcons.File, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WorkspaceError(error: String) {
    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp).testTag("workspace-error")
            .semantics { liveRegion = LiveRegionMode.Polite })
}
