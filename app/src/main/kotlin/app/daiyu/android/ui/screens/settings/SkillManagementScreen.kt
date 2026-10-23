package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.components.materialImeCursorColor
import app.daiyu.android.ui.components.managedInput

import androidx.compose.ui.semantics.SemanticsPropertyKey

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.daiyu.android.ui.haptics.material.Checkbox
import androidx.compose.material3.OutlinedTextField
import app.daiyu.android.ui.haptics.material.IconButton
import app.daiyu.android.ui.haptics.material.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.DaiyuDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import app.daiyu.android.skills.SkillConflict
import app.daiyu.android.skills.SkillManagement
import app.daiyu.android.ui.components.EtaDropdownMenu
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import app.daiyu.android.ui.components.subagent.ConversationAgentHandle
import app.daiyu.android.ui.components.subagent.ConversationAgentSheet
import app.daiyu.android.ui.components.subagent.conversationAgentColors
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.SkillIcons
import app.daiyu.android.ui.icons.DaiyuIcons
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val RetainedSkillRemovalCancel = SemanticsPropertyKey<() -> Unit>("RetainedSkillRemovalCancel")

@Composable
internal fun SkillManagementScreen(
    managementFactory: () -> SkillManagement,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    engineeringUi: Boolean = false,
) {
    // The controller and its factory belong to this page visit, not to recompositions.
    val controller = remember { SkillManagementPageState(managementFactory) }
    var details by remember { mutableStateOf<SkillManagement.Entry?>(null) }
    val context = LocalContext.current
    var pickerRequest by remember { mutableStateOf<SkillManagementPageState.LocalPick?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pickerRequest
        pickerRequest = null
        if (request != null) controller.receiveLocalPick(request, uri?.let { selected ->
            {
                require(selected.scheme == "content") { "invalid_document_uri" }
                context.contentResolver.openInputStream(selected) ?: error("document_unavailable")
            }
        })
    }
    DisposableEffect(controller) {
        controller.refresh()
        onDispose { controller.dispose() }
    }

    val state = controller.state
    val switchesEnabled = !controller.busy && controller.confirmed
    val colors = MiuixTheme.colorScheme
    val importZip: () -> Unit = {
        val request = controller.beginLocalPick()
        if (request != null) {
            pickerRequest = request
            try { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            catch (_: Exception) { pickerRequest = null; controller.receiveLocalPick(request, null) }
        }
    }
    SettingsScaffoldPage(
        title = "技能",
        onBack = onBack,
        modifier = modifier,
        actions = {
            if (engineeringUi) IconButton(onClick = controller::refresh, enabled = !controller.busy) {
                DaiyuIcon(
                    DaiyuIcons.Refresh,
                    contentDescription = "刷新",
                    tint = if (controller.busy) colors.onSurfaceVariantSummary.copy(alpha = 0.38f)
                        else colors.onSurface,
                    size = 24.dp,
                )
            }
        },
    ) {
        if (engineeringUi) item(key = "skills_global") {
            SkillSettingsCard(Modifier.padding(top = 16.dp)) {
                SettingsRow("启用技能", trailing = {
                    SkillSwitch(
                        checked = state?.enabled ?: false,
                        enabled = !controller.busy && controller.globalConfirmed,
                        label = "启用技能",
                        modifier = Modifier.testTag("skills-global"),
                        onCheckedChange = controller::setGlobalEnabled,
                    )
                })
            }
        }
        item(key = "skills_install_title") { SettingsSectionTitle("安装") }
        item(key = "skills_install") {
            SkillSettingsCard {
                if (engineeringUi) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("从公开 GitHub 仓库安装", color = colors.onSurface)
                    OutlinedTextField(value = controller.url, onValueChange = { controller.url = it },
                        enabled = !controller.busy, label = { Text("https://github.com/owner/repo") }, singleLine = true,
                        modifier = Modifier.managedInput().fillMaxWidth().testTag("skills-install-url"),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
                    )
                    SkillActionButton("检查仓库", controller::inspect, primary = true,
                        enabled = !controller.busy && controller.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("skills-inspect"))
                }
                Row(Modifier.testTag("skills-import-zip").fillMaxWidth()
                    .clickable(enabled = !controller.busy, role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = importZip)
                    .heightIn(min = 80.dp).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DaiyuIcon(SkillIcons.Import, null, tint = colors.onSurfaceVariantSummary, size = 26.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("从 ZIP 导入", style = MiuixTheme.textStyles.body1.copy(
                            fontSize = 16.sp, fontWeight = FontWeight.Medium), color = colors.onSurface)
                        Text("选择包含 SKILL.md 的 Skill 包", style = MiuixTheme.textStyles.body2.copy(
                            fontSize = 13.sp, lineHeight = 19.sp), color = colors.onSurfaceVariantSummary)
                    }
                    DaiyuIcon(DaiyuIcons.ChevronRight, null, tint = colors.onSurfaceVariantSummary, size = 20.dp)
                }
            }
        }
        if (engineeringUi || controller.busy || controller.installMessage != null) item(key = "skills_install_status") {
            Column(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (controller.busy) Text("正在处理，请稍候…", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
                if (engineeringUi) Text("仅安装静态技能资源，不执行脚本；安装后供新运行使用。",
                    style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
                controller.installMessage?.let { Text(it, style = MiuixTheme.textStyles.body2, color = colors.onSurface) }
            }
        }
        controller.error?.let { error ->
            item(key = "skills_error") {
                Text(
                    error,
                    style = MiuixTheme.textStyles.body2,
                    color = colors.error,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
        }
        if (state?.entriesUnavailable == true) {
            item(key = "skills_unavailable") {
                SkillSettingsCard { SettingsRow(if (engineeringUi) "技能列表暂不可用，请刷新" else "技能列表暂不可用，请重新进入页面重试") }
            }
        } else if (state != null && state.entries.isEmpty()) {
            item(key = "skills_empty") {
                SkillSettingsCard { SettingsRow("尚未安装技能") }
            }
        } else if (state != null && !engineeringUi) {
            listOf(true to "内置", false to "用户安装").forEach { (builtin, title) ->
                val entries = state.entries.filter { it.builtin == builtin }
                if (entries.isNotEmpty()) {
                    item(key = "skills-heading:$builtin") { SettingsSectionTitle(title) }
                    item(key = "skills-group:$builtin") {
                        SkillSettingsCard {
                            entries.forEach { entry ->
                                SkillProductRow(entry, !controller.busy && controller.confirmed,
                                    onDetails = { details = entry }, onRemove = { controller.prepareRemoval(entry) })
                            }
                        }
                    }
                }
            }
        } else if (state != null) {
            items(state.entries, key = { "skill:${it.id}" }) { entry ->
                SkillSettingsCard {
                    Row(
                        modifier = Modifier.testTag("skill-row:${entry.id}")
                            .fillMaxWidth().clickable { details = entry }
                            .heightIn(min = 72.dp).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.skill.name,
                                    style = MiuixTheme.textStyles.body1,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (entry.builtin) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "内置",
                                        style = MiuixTheme.textStyles.body2,
                                        color = colors.onSurfaceVariantSummary,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                            .background(colors.surface).padding(horizontal = 8.dp),
                                    )
                                }
                            }
                            Text(
                                entry.skill.description,
                                style = MiuixTheme.textStyles.body2,
                                color = colors.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        SkillSwitch(
                            checked = entry.skill.enabled,
                            enabled = switchesEnabled,
                            muted = !state.enabled,
                            label = entry.skill.name,
                            modifier = Modifier.testTag("skill-switch:${entry.id}"),
                            onCheckedChange = { enabled -> controller.setEnabled(entry, enabled) },
                        )
                    }
                }
            }
        }
    }

    controller.inspection?.takeIf { engineeringUi }?.let { inspection ->
        SkillInstallSheet(controller, inspection)
    }
    controller.localPreview?.let { local -> LocalSkillInstallSheet(controller, local) }
    details?.let { entry ->
        SkillDetailsSheet(entry = entry, onDismiss = { if (details === entry) details = null },
            canReinstall = engineeringUi && switchesEnabled && entry.builtin,
            onReinstall = { details = null; controller.prepareReinstall(entry) },
            engineeringUi = engineeringUi,
            canRemove = engineeringUi && switchesEnabled && !entry.builtin,
            onRemove = { details = null; controller.prepareRemoval(entry) })
    }
    controller.reinstall?.let { approved ->
        val entry = approved.entry
        val cancel: () -> Unit = { if (controller.reinstall === approved) controller.cancelReinstall() }
        SkillConfirmationSheet("重装内置技能", cancel, "确认重装", "skills-confirm-reinstall",
            confirmEnabled = !controller.busy, cancelEnabled = !controller.busy,
            onConfirm = { controller.reinstall(approved) }) {
            Text("用当前应用自带正文替换 ${entry.skill.name}？\n当前版本：${entry.skill.revision}\n重装会启用此技能，全局开关不变；当前运行保留原快照。此操作需要可读取的已安装版本。")
        }
    }
    controller.removal?.let { removal ->
        val cancel: () -> Unit = { if (controller.removal === removal) controller.cancelRemoval() }
        SkillConfirmationSheet("删除技能", cancel, "删除", "skills-confirm-remove",
            confirmEnabled = !controller.busy, cancelEnabled = !controller.busy,
            onConfirm = { controller.remove(removal) },
            cancelModifier = Modifier.semantics { this[RetainedSkillRemovalCancel] = cancel }) {
            Text("删除 ${removal.entry.skill.name}？当前运行保留已冻结的正文，新运行不再加载该技能。")
        }
    }

}

@Composable
private fun LocalSkillInstallSheet(controller: SkillManagementPageState, preview: SkillManagement.LocalPreview) {
    val colors = MiuixTheme.colorScheme
    var approved by remember(preview) { mutableStateOf(false) }
    var packageDetails by remember(preview) { mutableStateOf(false) }
    val change = preview.change
    val cancel: () -> Unit = { if (!controller.installing && controller.localPreview === preview) controller.cancelInstall() }
    SkillConfirmationSheet("确认本地技能导入", cancel, "确认导入", "skills-confirm-local-install",
        confirmEnabled = !controller.busy && preview.compatible && (change.previousRevision == null || approved),
        cancelEnabled = !controller.installing, onConfirm = { controller.installLocal(preview, preview.replacements) }) {
        Text(change.candidate.name, style = MiuixTheme.textStyles.body1.copy(
            fontSize = 16.sp, fontWeight = FontWeight.Medium), color = colors.onSurface)
        Text(change.candidate.description, style = MiuixTheme.textStyles.body2.copy(
            fontSize = 14.sp, lineHeight = 21.sp), color = colors.onSurfaceVariantSummary)
        Text(if (preview.compatible) "兼容性静态检查通过；仅导入静态资源，不执行脚本。"
            else "不兼容：${change.compatibility.reason}", color = if (preview.compatible) colors.onSurfaceVariantSummary else colors.error)
        change.previousRevision?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = approved, enabled = !controller.busy, onCheckedChange = { approved = it },
                    modifier = Modifier.testTag("skills-local-replace"))
                Text("替换已安装的 ${change.candidate.name}", color = colors.onSurface)
            }
        }
        Text("仅安装本次确认的内容，正在运行的任务不受影响。", color = colors.onSurfaceVariantSummary)
        SkillActionButton(if (packageDetails) "收起包详情" else "包详情", { packageDetails = !packageDetails },
            modifier = Modifier.fillMaxWidth().testTag("skills-local-details-toggle"))
        if (packageDetails) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())
            .testTag("skills-local-package-details"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("包 SHA-256：${preview.archiveSha256}", color = colors.onSurfaceVariantSummary)
            Text("新版本：${change.candidate.incomingRevision}", color = colors.onSurfaceVariantSummary)
            change.previousRevision?.let { Text("旧版本：$it", color = colors.onSurfaceVariantSummary) }
        }
    }
}

@Composable
private fun SkillInstallSheet(controller: SkillManagementPageState, inspection: SkillManagement.Inspection) {
    val colors = MiuixTheme.colorScheme
    val preview = controller.preview
    var approved by remember(preview) { mutableStateOf(emptySet<String>()) }
    val cancel: () -> Unit = {
        if (!controller.installing && controller.inspection === inspection && controller.preview === preview) controller.cancelInstall()
    }
    Dialog(onDismissRequest = cancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 640.dp)
            .clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh).padding(16.dp)
            .semantics { paneTitle = "安装技能" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (preview == null) "选择技能目录" else "确认精确安装内容", color = colors.onSurface)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${inspection.owner}/${inspection.repo}\ncommit: ${inspection.commit}", color = colors.onSurface)
                if (preview == null) {
                    if (inspection.candidates.isEmpty()) Text("未找到 SKILL.md", color = colors.onSurface)
                    inspection.candidates.forEach { candidate ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = candidate.directory in controller.selected,
                                enabled = !controller.busy,
                                onCheckedChange = { controller.select(candidate.directory, it) },
                                modifier = Modifier.testTag("skills-directory:${candidate.directory}"))
                            Column {
                                Text("${candidate.name} · ${candidate.directory.ifEmpty { "(根目录)" }}", color = colors.onSurface)
                                Text(candidate.description, color = colors.onSurfaceVariantSummary)
                            }
                        }
                    }
                } else {
                    Text("已完成文件/路径/大小与运行时依赖静态检查；通过不保证运行成功，也不会执行脚本。", color = colors.onSurfaceVariantSummary)
                    preview.changes.forEach { change ->
                        val candidate = change.candidate
                        Text("${candidate.name}\n目录：${candidate.directory.ifEmpty { "(根目录)" }}\n新版本：${candidate.incomingRevision}", color = colors.onSurface)
                        Text(
                            if (change.compatibility.available) "兼容性静态检查通过（未发现已知不支持的运行时依赖）"
                            else "不兼容：${change.compatibility.reason}",
                            color = if (change.compatibility.available) colors.onSurfaceVariantSummary else colors.error,
                            modifier = Modifier.testTag("skills-compatibility:${candidate.name}"),
                        )
                        change.previousRevision?.let { old ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = candidate.name in approved, enabled = !controller.busy,
                                    onCheckedChange = { checked -> approved = if (checked) approved + candidate.name else approved - candidate.name },
                                    modifier = Modifier.testTag("skills-replace:${candidate.name}"))
                                Text("批准替换 user:${candidate.name}\n旧版本：$old", color = colors.onSurface)
                            }
                        } ?: Text("新安装（仅创建，不覆盖）", color = colors.onSurfaceVariantSummary)
                    }
                    Text("确认仅用于上述 commit、目录和版本，使用一次即失效。安装将启用所选技能，全局开关保持不变。", color = colors.onSurfaceVariantSummary)
                }
                controller.error?.let { Text(it, color = colors.error) }
                if (controller.busy) Text(if (controller.installing) "安装等待或处理中；离页撤销尚未开始的安装，已开始的安装不回滚。" else "正在静态校验…", color = colors.onSurface)
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkillActionButton("取消", cancel, enabled = !controller.installing,
                    modifier = Modifier.weight(1f).fillMaxHeight())
                if (preview == null) {
                    SkillActionButton("预览安装", controller::prepareInstall, primary = true,
                        enabled = !controller.busy && controller.selected.isNotEmpty(),
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("skills-preview"))
                } else {
                    val needed = preview.changes.filter { it.previousRevision != null }.map { it.candidate.name }.toSet()
                    SkillActionButton("确认安装", { controller.install(preview, preview.replacements) }, primary = true,
                        enabled = !controller.busy && preview.compatible && approved.containsAll(needed),
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("skills-confirm-install"))
                }
            }
        }
    }
}

@Composable
private fun SkillProductRow(entry: SkillManagement.Entry, enabled: Boolean,
    onDetails: () -> Unit, onRemove: () -> Unit) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    val icon = when {
        !entry.builtin -> DaiyuIcons.Skills
        entry.skill.name == "self-improving-agent" -> SkillIcons.Improve
        entry.skill.name == "skill-creator" -> SkillIcons.Create
        entry.skill.name == "skill-installer" -> SkillIcons.Import
        else -> DaiyuIcons.Skills
    }
    Row(Modifier.fillMaxWidth().testTag("skill-row:${entry.id}").heightIn(min = 96.dp)
        .padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        DaiyuIcon(icon, null, tint = colors.onSurfaceVariantSummary, size = 26.dp,
            modifier = Modifier.testTag("skill-icon:${entry.id}"))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.skill.name, style = MiuixTheme.textStyles.body1.copy(
                fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
                color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(entry.skill.description, style = MiuixTheme.textStyles.body2.copy(
                fontSize = 13.sp, lineHeight = 19.sp), color = colors.onSurfaceVariantSummary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        Box {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp).testTag("skill-menu:${entry.id}")) {
                DaiyuIcon(DaiyuIcons.More, "更多操作", tint = colors.onSurfaceVariantSummary, size = 22.dp)
            }
            EtaDropdownMenu(expanded, onDismissRequest = { expanded = false }, alignEnd = true, minWidth = 200.dp,
                modifier = Modifier.testTag("skill-actions:${entry.id}").semantics { paneTitle = "技能操作" }) {
                SkillMenuAction("查看描述", DaiyuIcons.Info) { expanded = false; onDetails() }
                if (!entry.builtin) SkillMenuAction("删除", DaiyuIcons.Delete, enabled = enabled,
                    destructive = true) { expanded = false; onRemove() }
            }
        }
    }
}

@Composable
private fun SkillMenuAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val tint = (if (destructive) colors.error else colors.onSurface).copy(alpha = if (enabled) 1f else .38f)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, interactionSource = remember { MutableInteractionSource() },
            indication = null, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DaiyuIcon(icon, null, tint = tint, size = 20.dp)
        Text(text, style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, lineHeight = 22.sp), color = tint)
    }
}

@Composable
private fun SkillDetailsSheet(entry: SkillManagement.Entry, onDismiss: () -> Unit,
    canRemove: Boolean, onRemove: () -> Unit, canReinstall: Boolean, onReinstall: () -> Unit,
    engineeringUi: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    SkillSheet(entry.skill.name, onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.skill.name, style = MiuixTheme.textStyles.title3.copy(fontSize = 20.sp, lineHeight = 28.sp),
                color = colors.onSurface)
            Text(entry.skill.description.ifBlank { "暂无描述" }, modifier = Modifier.testTag("skill-description:${entry.id}"),
                style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp, lineHeight = 24.sp), color = colors.onSurface)
            if (engineeringUi) listOf("ID" to entry.id, "来源" to (entry.skill.source ?: "本地或内置"),
                "提交" to (entry.skill.commit ?: "无"), "版本" to entry.skill.revision).forEach { (key, value) ->
                Text("$key：$value", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
            }
        }
        if (entry.builtin && engineeringUi) SkillActionButton("重装内置技能", onReinstall,
            enabled = canReinstall, modifier = Modifier.fillMaxWidth().testTag("skill-reinstall:${entry.id}"))
        if (!entry.builtin && engineeringUi) SkillActionButton("删除技能", onRemove,
            enabled = canRemove, modifier = Modifier.fillMaxWidth().testTag("skill-remove:${entry.id}"))
        SkillActionButton("关闭", onDismiss, primary = true,
            modifier = Modifier.fillMaxWidth().testTag("skills-details-close"))
    }
}

@Composable
private fun SkillConfirmationSheet(title: String, onDismiss: () -> Unit, confirmText: String,
    confirmTag: String, confirmEnabled: Boolean, cancelEnabled: Boolean, onConfirm: () -> Unit,
    cancelModifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SkillSheet(title, onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3.copy(fontSize = 20.sp, lineHeight = 28.sp),
                color = MiuixTheme.colorScheme.onSurface)
            content()
        }
        androidx.compose.material3.HorizontalDivider(color = conversationAgentColors().line)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkillActionButton("取消", onDismiss, enabled = cancelEnabled,
                modifier = Modifier.weight(1f).fillMaxHeight().then(cancelModifier))
            SkillActionButton(confirmText, onConfirm, enabled = confirmEnabled, primary = true,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag(confirmTag))
        }
    }
}

@Composable
private fun SkillSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ConversationAgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(18.dp).semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ConversationAgentHandle()
            content()
        }
    }
}

@Composable
private fun SkillActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true,
    primary: Boolean = false, modifier: Modifier = Modifier) {
    ConversationAgentButton(text, enabled = enabled, primary = primary, onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp))
}

@Composable
private fun SkillSettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SettingsCard(modifier, content)
}

@Composable
private fun SkillSwitch(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val checkedTrack = if (muted) colors.onSurfaceVariantSummary else colors.primary
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = label },
        colors = SwitchDefaults.colors(
            checkedThumbColor = if (muted) colors.surface else colors.onPrimary,
            checkedTrackColor = checkedTrack,
            checkedBorderColor = checkedTrack,
            uncheckedThumbColor = colors.onSurfaceVariantSummary,
            uncheckedTrackColor = colors.surface,
            uncheckedBorderColor = colors.onSurfaceVariantSummary,
            disabledCheckedThumbColor = colors.surface,
            disabledCheckedTrackColor = colors.onSurfaceVariantSummary.copy(alpha = 0.38f),
            disabledCheckedBorderColor = colors.onSurfaceVariantSummary.copy(alpha = 0.38f),
            disabledUncheckedThumbColor = colors.onSurfaceVariantSummary.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = colors.surface,
            disabledUncheckedBorderColor = colors.onSurfaceVariantSummary.copy(alpha = 0.38f),
        ),
    )
}

/**
 * One-way page ownership, independent of the worker queue and SkillManagement's I/O lock.
 * Issuing a ticket is NOT admission. Only the worker's successful CAS admits that ticket;
 * revoke competes with that same CAS and permanently closes this owner. No I/O runs here.
 */
private class SkillInstallPageOwner {
    class Ticket
    private val open = Any()
    private val revoked = Any()
    private val state = AtomicReference(open)

    val active: Boolean get() = state.get() !== revoked
    fun ticket(): Ticket? = Ticket().takeIf { state.compareAndSet(open, it) }
    fun admit(ticket: Ticket): Boolean = state.compareAndSet(ticket, open)
    fun revoke(): Boolean = state.getAndSet(revoked) !== revoked
}

/** Only the executor touches SkillManagement; only the main thread updates observable state. */
internal class SkillManagementPageState(
    managementFactory: () -> SkillManagement,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val postToMain: (() -> Unit) -> Unit = { callback ->
        Handler(Looper.getMainLooper()).post { callback() }
        Unit
    },
) {
    private val managementDelegate = lazy(managementFactory)
    private val management by managementDelegate
    private val owner = SkillInstallPageOwner()
    private val active: Boolean get() = owner.active

    var state by mutableStateOf<SkillManagement.State?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var confirmed by mutableStateOf(false)
        private set
    var globalConfirmed by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var url by mutableStateOf("")
    var inspection by mutableStateOf<SkillManagement.Inspection?>(null)
        private set
    var preview by mutableStateOf<SkillManagement.Preview?>(null)
        private set
    var selected by mutableStateOf(emptySet<String>())
        private set
    var installing by mutableStateOf(false)
        private set
    var installMessage by mutableStateOf<String?>(null)
        private set
    var removal by mutableStateOf<SkillManagement.Removal?>(null)
        private set
    class BuiltinReinstall internal constructor(val entry: SkillManagement.Entry)
    var reinstall by mutableStateOf<BuiltinReinstall?>(null)
        private set

    fun prepareReinstall(entry: SkillManagement.Entry) {
        if (!active || busy || !confirmed || !entry.builtin || state?.entries?.contains(entry) != true) return
        cancelInstall()
        removal = null
        worker.execute { runCatching { management.cancelRemoval() } }
        installMessage = null
        reinstall = BuiltinReinstall(entry)
    }

    fun cancelReinstall() {
        if (!active || busy || installing) return
        reinstall = null
    }

    fun reinstall(approved: BuiltinReinstall) {
        if (!active || busy || !confirmed || reinstall !== approved) return
        val ticket = owner.ticket() ?: return
        reinstall = null
        installing = true
        flow(ticket = ticket, operation = {
            management.reinstallBuiltin(approved.entry.id, approved.entry.skill.revision) to runCatching { management.snapshot() }
        }) { (result, refreshed) -> finishInstall(result, refreshed, "重装") }
    }

    private var flowGeneration = 0L
    class LocalPick internal constructor()
    private var pendingPick: LocalPick? = null
    var localPreview by mutableStateOf<SkillManagement.LocalPreview?>(null)
        private set

    fun beginLocalPick(): LocalPick? {
        if (!active || busy) return null
        reinstall = null
        cancelInstall()
        installMessage = null
        removal = null
        worker.execute { runCatching { management.cancelRemoval() } }
        return LocalPick().also { pendingPick = it; busy = true }
    }

    fun receiveLocalPick(request: LocalPick, openStream: (() -> java.io.InputStream)?) {
        if (!active || pendingPick !== request) return
        pendingPick = null; busy = false
        if (openStream != null) flow({ management.previewLocalZip(openStream) }) { localPreview = it }
    }

    fun installLocal(approved: SkillManagement.LocalPreview, replacements: Map<String, String>) {
        if (!active || busy || localPreview !== approved) return
        val ticket = owner.ticket() ?: return
        installing = true
        flow(ticket = ticket, operation = {
            management.installLocalZip(approved, replacements) to runCatching { management.snapshot() }
        }) { (result, refreshed) -> finishInstall(result, refreshed) }
    }

    fun prepareRemoval(entry: SkillManagement.Entry) {
        if (!confirmed || !active || busy) return
        reinstall = null
        inspection = null; preview = null; localPreview = null; selected = emptySet(); installMessage = null
        flow({ management.previewRemoval(entry.id, entry.skill.revision) }) { removal = it }
    }

    fun cancelRemoval() {
        if (!active || installing || busy) return
        removal = null
        worker.execute { runCatching { management.cancelRemoval() } }
    }

    fun remove(approved: SkillManagement.Removal) {
        if (!active || busy || removal !== approved) return
        val ticket = owner.ticket() ?: return
        installing = true
        flow(ticket = ticket, operation = {
            val result = management.remove(approved)
            result to runCatching { management.snapshot() }
        }) { (result, refreshed) ->
            removal = null
            refreshed.fold(onSuccess = { state = it; globalConfirmed = true; confirmed = !it.entriesUnavailable },
                onFailure = { globalConfirmed = false; confirmed = false })
            installMessage = when (result) {
                is SkillManagement.RemovalResult.Removed -> if (result.cleanupPending)
                    "删除已提交，清理待完成；请刷新，不要重复删除。" else "技能已删除，当前运行保留原正文快照。"
                is SkillManagement.RemovalResult.Rejected -> "删除未提交（${result.code}），请刷新后重新确认。"
                SkillManagement.RemovalResult.Unknown -> "删除结果未知，请刷新核对；不会自动重试。"
            }
            if (refreshed.isFailure) error = "列表读取失败，请刷新核对；不要重复删除。"
        }
    }

    fun inspect() {
        if (!active || busy) return
        inspection = null; preview = null; localPreview = null; removal = null; reinstall = null; selected = emptySet(); installMessage = null
        val input = url.trim()
        flow({ management.inspect(input) }) { inspection = it }
    }

    fun select(directory: String, checked: Boolean) {
        if (!active || busy || preview != null) return
        selected = if (checked) selected + directory else selected - directory
    }

    fun prepareInstall() {
        val inspected = inspection ?: return
        val directories = selected.toSet()
        flow({ management.preview(inspected, directories) }) { preview = it }
    }

    fun install(approvedPreview: SkillManagement.Preview, replacements: Map<String, String>) {
        if (!active || busy || preview !== approvedPreview) return
        val ticket = owner.ticket() ?: return
        installing = true
        flow(ticket = ticket, operation = {
            val result = management.install(approvedPreview, replacements)
            result to runCatching { management.snapshot() }
        }) { (result, refreshed) ->
            finishInstall(result, refreshed)
        }
    }

    private fun finishInstall(result: SkillManagement.InstallResult, refreshed: Result<SkillManagement.State>, operation: String = "安装") {
        inspection = null; preview = null; localPreview = null; selected = emptySet()
        refreshed.fold(onSuccess = { state = it; globalConfirmed = true; confirmed = !it.entriesUnavailable },
                onFailure = { globalConfirmed = false; confirmed = false })
        installMessage = when (result) {
            is SkillManagement.InstallResult.Installed -> if (result.cleanupPending)
                "${operation}已提交，清理待完成；请刷新，不要重复提交。" else "${operation}成功，新运行可见；当前运行保持原快照。"
            is SkillManagement.InstallResult.Rejected -> "${operation}未获确认（${result.code}），请刷新后重新确认。"
            SkillManagement.InstallResult.Unknown -> "${operation}结果未知，请刷新核对；不会自动重试。离页不代表回滚。"
        }
        if (refreshed.isFailure) error = "列表读取失败，请刷新核对；不要重复提交安装。"
    }

    fun cancelInstall() {
        if (!active || installing) return
        flowGeneration++
        reinstall = null
        busy = false
        inspection = null; preview = null; localPreview = null; pendingPick = null; selected = emptySet(); error = null
        worker.execute { runCatching { management.cancelInstall() } }
    }

    private fun <T> flow(
        operation: () -> T,
        ticket: SkillInstallPageOwner.Ticket? = null,
        accept: (T) -> Unit,
    ) {
        if (!active || busy) { installing = false; return }
        busy = true; error = null
        val generation = ++flowGeneration
        val writing = installing
        worker.execute {
            // This is the install admission point, not the click or execute() call.
            // Once admitted, finish outside the gate even if the page is then revoked.
            if (ticket != null) {
                if (!owner.admit(ticket)) return@execute
            } else if (!active) return@execute
            val result = runCatching(operation)
            postToMain {
                if (!active || generation != flowGeneration) return@postToMain
                busy = false; installing = false
                result.fold(onSuccess = accept, onFailure = { failure ->
                    if (writing) {
                        inspection = null; preview = null; localPreview = null; removal = null; reinstall = null; selected = emptySet(); globalConfirmed = false; confirmed = false
                        installMessage = "修改结果未知，请刷新核对；不会自动重试。离页不代表回滚。"
                    } else {
                        error = when (failure.message) {
                            "builtin_conflict" -> "所选技能与内置技能同名，不能覆盖内置技能。"
                            "duplicate_skill" -> "所选目录包含重名技能，请调整选择。"
                            "incoming_revision_conflict" -> "下载内容与检查版本不符，请重新检查仓库。"
                            else -> "检查或预览失败，请刷新后重试（未提交安装）。"
                        }
                    }
                })
            }
        }
    }

    fun refresh() {
        if (!active || busy) return
        reinstall = null
        load { management.snapshot() }
    }

    fun setGlobalEnabled(enabled: Boolean) {
        if (!globalConfirmed) return
        load { management.setGlobalEnabled(enabled) }
    }

    fun setEnabled(entry: SkillManagement.Entry, enabled: Boolean) {
        if (!confirmed) return
        load { management.setEnabled(entry.id, enabled, entry.skill.revision) }
    }

    private fun load(operation: () -> SkillManagement.State) {
        if (!active || busy) return
        busy = true
        worker.execute {
            if (!active) return@execute
            val result = runCatching(operation)
            postToMain {
                if (!active) return@postToMain
                busy = false
                result.fold(
                    onSuccess = { freshState ->
                        state = freshState
                        globalConfirmed = true
                        confirmed = !freshState.entriesUnavailable
                        error = null
                    },
                    onFailure = { failure ->
                        // Keep the old values visible, but never write using unconfirmed revisions.
                        globalConfirmed = false
                        confirmed = false
                        error = if (failure is SkillConflict) "技能版本已变化，请刷新" else "操作结果未确认，请刷新"
                    },
                )
            }
        }
    }

    fun dispose() {
        // Revoke synchronously without waiting for management's network/storage lock.
        // Queued tickets now fail admission; an already admitted write may finish.
        if (!owner.revoke()) return
        pendingPick = null; localPreview = null; reinstall = null
        worker.execute {
            if (managementDelegate.isInitialized()) runCatching { management.cancelInstall(); management.cancelRemoval() }
        }
        worker.shutdown()
    }
}
