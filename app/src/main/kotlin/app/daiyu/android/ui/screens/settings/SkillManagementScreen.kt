package app.daiyu.android.ui.screens.settings

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.daiyu.android.skills.SkillConflict
import app.daiyu.android.skills.SkillManagement
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import java.util.concurrent.Executors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SkillManagementScreen(
    managementFactory: () -> SkillManagement,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The controller and its factory belong to this page visit, not to recompositions.
    val controller = remember { SkillManagementPageState(managementFactory) }
    var details by remember { mutableStateOf<SkillManagement.Entry?>(null) }
    DisposableEffect(controller) {
        controller.refresh()
        onDispose { controller.dispose() }
    }

    val state = controller.state
    val switchesEnabled = !controller.busy && controller.confirmed
    val colors = MiuixTheme.colorScheme
    MiuixScaffoldPage(
        title = "技能",
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = controller::refresh, enabled = !controller.busy) {
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
        item(key = "skills_global") {
            SkillSettingsCard(Modifier.padding(top = 16.dp)) {
                SettingsRow("启用技能", trailing = {
                    SkillSwitch(
                        checked = state?.enabled ?: false,
                        enabled = switchesEnabled,
                        label = "启用技能",
                        modifier = Modifier.testTag("skills-global"),
                        onCheckedChange = controller::setGlobalEnabled,
                    )
                })
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
                SkillSettingsCard { SettingsRow("技能列表暂不可用，请刷新") }
            }
        } else if (state != null && state.entries.isEmpty()) {
            item(key = "skills_empty") {
                SkillSettingsCard { SettingsRow("尚未安装技能") }
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

    details?.let { entry ->
        SkillDetailsSheet(entry = entry, onDismiss = { details = null })
    }
}

/** Read-only details use the same bounded bottom-surface pattern as DaiyuChoiceSheet. */
@Composable
private fun SkillDetailsSheet(entry: SkillManagement.Entry, onDismiss: () -> Unit) {
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
                ).navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp)
                    .fillMaxWidth().padding(16.dp).heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)
                    .semantics { paneTitle = entry.skill.name }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    listOf(
                        "ID" to entry.id,
                        "来源" to (entry.skill.source ?: "本地或内置"),
                        "提交" to (entry.skill.commit ?: "无"),
                        "版本" to entry.skill.revision,
                    ).forEach { (key, value) ->
                        Text("$key：$value", style = MiuixTheme.textStyles.body1, color = colors.onSurface)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(16.dp)).background(colors.surface)
                        .clickable(role = Role.Button, onClick = onDismiss).padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("关闭", style = MiuixTheme.textStyles.body1, color = colors.onSurface)
                }
            }
        }
    }
}

@Composable
private fun SkillSettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
            .fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        content = content,
    )
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

/** Only the executor touches SkillManagement; only the main thread updates observable state. */
private class SkillManagementPageState(managementFactory: () -> SkillManagement) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val management by lazy(managementFactory)
    private var active = true

    var state by mutableStateOf<SkillManagement.State?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var confirmed by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun refresh() = load { management.snapshot() }

    fun setGlobalEnabled(enabled: Boolean) {
        if (!confirmed) return
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
            val result = runCatching(operation)
            main.post {
                if (!active) return@post
                busy = false
                result.fold(
                    onSuccess = { freshState ->
                        state = freshState
                        confirmed = true
                        error = null
                    },
                    onFailure = { failure ->
                        // Keep the old values visible, but never write using unconfirmed revisions.
                        confirmed = false
                        error = if (failure is SkillConflict) "技能版本已变化，请刷新" else "操作结果未确认，请刷新"
                    },
                )
            }
        }
    }

    fun dispose() {
        active = false
        // An admitted write may finish; leaving the page is not rollback.
        worker.shutdown()
    }
}
