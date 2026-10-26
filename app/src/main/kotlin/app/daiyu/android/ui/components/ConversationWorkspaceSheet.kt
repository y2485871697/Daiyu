package app.daiyu.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import app.daiyu.android.ui.components.subagent.ConversationAgentHandle
import app.daiyu.android.ui.components.subagent.ConversationAgentIconButton
import app.daiyu.android.ui.components.subagent.ConversationAgentSheet
import app.daiyu.android.ui.components.subagent.ConversationAgentTypography
import app.daiyu.android.ui.components.subagent.conversationAgentColors
import app.daiyu.android.ui.haptics.selectable

/** Display-only source metadata. Approval, defaults and filesystem access belong to the owner. */
data class WorkspaceSourceOption(val id: String, val title: String, val rootPath: String, val initialPath: String)

/**
 * A controlled conversation editor: the owner supplies an active session's draft and
 * validates/saves it. Closing never saves, and saving never implicitly dismisses.
 */
@Composable
fun ConversationWorkspaceSheet(
    visible: Boolean,
    sources: List<WorkspaceSourceOption>,
    selectedSourceId: String,
    path: String,
    busy: Boolean = false,
    error: String? = null,
    onSourceChange: (String) -> Unit,
    onPathChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    WithoutPressRipple {
        ConversationAgentTypography {
            val colors = conversationAgentColors()
            ConversationAgentSheet(onDismiss, modifier = Modifier.testTag("conversation-workspace-sheet")) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                    ConversationAgentHandle()
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("会话工作区", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        ConversationAgentIconButton(onClick = onDismiss,
                            modifier = Modifier.size(48.dp).testTag("workspace-close")) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
                        }
                    }
                    // Only the body scrolls: the footer remains reachable with IME,
                    // larger fonts and short viewports. The sheet supplies safe insets.
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("来源", style = MaterialTheme.typography.titleSmall, color = colors.accent)
                        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sources.forEach { source ->
                                val selected = source.id == selectedSourceId
                                val shape = RoundedCornerShape(14.dp)
                                Row(Modifier.fillMaxWidth().testTag("workspace-source:${source.id}")
                                    .clip(shape).background(if (selected) colors.soft else colors.field)
                                    .border(1.dp, if (selected) colors.accent else colors.line, shape)
                                    .selectable(selected = selected, enabled = !busy, role = Role.RadioButton,
                                        onClick = { onSourceChange(source.id) })
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(source.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(source.rootPath, style = MaterialTheme.typography.bodySmall, color = colors.muted,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    RadioButton(selected = selected, onClick = null, enabled = !busy)
                                }
                            }
                        }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.field)
                            .border(1.dp, colors.line, RoundedCornerShape(14.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("路径", style = MaterialTheme.typography.titleSmall, color = colors.accent)
                            BasicTextField(value = path, onValueChange = onPathChange, enabled = !busy,
                                modifier = Modifier.managedInput().fillMaxWidth().heightIn(min = 56.dp)
                                    .testTag("workspace-path").semantics { contentDescription = "工作区路径" },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
                                cursorBrush = imeCursorBrush(colors.accent),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                                minLines = 2, maxLines = 4)
                        }
                        if (error != null) {
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("workspace-error").semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                    HorizontalDivider(color = colors.line)
                    ConversationAgentButton("完成", enabled = !busy, primary = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("workspace-save"), onClick = onSave)
                }
            }
        }
    }
}
