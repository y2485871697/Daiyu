package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.LegacyLibraryHost
import app.daiyu.android.LegacyLibraryState
import app.daiyu.android.LegacyTextSection
import top.yukonga.miuix.kmp.basic.TextButton

/** A read-only compatibility library, never a chat/model/tool surface. Picker ownership stays in Main. */
@Composable
internal fun LegacyLibraryScreen(
    page: LegacyLibraryHost.Page,
    onPickSource: (LegacyLibraryHost.SourcePicker) -> Unit,
    onPickTarget: (LegacyLibraryHost.SavePicker) -> Unit,
    onBack: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(page, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) page.revoke()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) page.revoke()
        onDispose { page.revoke(); lifecycle.removeObserver(observer) }
    }
    SideEffect { page.checkOwner() }
    val leave = { page.revoke(); onBack() }
    BackHandler(onBack = leave)
    LegacyLibraryContent(
        state = page.state,
        onBack = leave,
        onPickSource = { page.requestSource()?.let(onPickSource) },
        onConfirm = page::confirmPreview,
        onCancel = page::cancelPending,
        onHistories = page::histories,
        onOpen = page::openHistory,
        onSave = { key -> page.requestSave(key)?.let(onPickTarget) },
    )
}

@Composable
private fun LegacyLibraryContent(
    state: LegacyLibraryState,
    onBack: () -> Unit,
    onPickSource: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onHistories: () -> Unit,
    onOpen: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    SettingsScaffoldPage("旧版兼容资料库", onBack) {
        item("scope") {
            SettingsCard {
                SettingsNote("仅供只读查看旧版 JSON / ZIP。不会写入原生会话、执行历史请求、调用模型或工具。原始引用仅作文字展示，不读取文件或联网。")
                SettingsNote("完整备份不包含此兼容资料库；本页未提供删除功能。所有导入和原件另存都必须单独确认。")
            }
        }
        item("actions") {
            TextButton(text = "选择 JSON / ZIP 预览", onClick = onPickSource, enabled = state.active && !state.busy,
                modifier = Modifier.testTag("legacy-source"))
            TextButton(text = "读取独立导入历史", onClick = onHistories, enabled = state.active && !state.busy,
                modifier = Modifier.testTag("legacy-histories"))
            if (state.pending || state.busy || state.canConfirm) TextButton(text = "撤销未完成授权（不回滚已开始写入）", onClick = onCancel,
                modifier = Modifier.testTag("legacy-cancel"))
            if (state.busy) SettingsNote("操作进行中；离开或切换历史会立即撤销本页后续授权。已开始的持久化结果可能需要重新读取历史核实。")
            state.notice?.let { SettingsNote(it, error = state.uncertain) }
        }
        if (state.previewSha256 != null) item("preview-confirm") {
            SettingsCard {
                SettingsNote("只读预览；尚未导入。SHA-256：${state.previewSha256}")
                TextButton(text = "确认将本次预览导入兼容库（一次性）", onClick = onConfirm,
                    enabled = state.active && !state.busy && state.canConfirm,
                    modifier = Modifier.testTag("legacy-confirm"))
            }
        }
        items(state.histories.size, key = { "history:${state.histories[it]}" }) { index ->
            val id = state.histories[index]
            TextButton(text = "打开独立历史 ${index + 1}（完整身份见索引）", onClick = { onOpen(id) }, enabled = state.active,
                modifier = Modifier.testTag("legacy-history:$index"))
        }
        state.importId?.let { id -> item("opened-id") {
            LegacyTextPages(LegacyTextSection("当前兼容库 importId", id), state.contentKey)
        } }
        // Each section is independently paged. No Markdown, images, links, intents or execution.
        state.sections.forEachIndexed { index, section ->
            item("section:$index") { LegacyTextPages(section, state.contentKey) }
        }
        if (state.originals.isNotEmpty()) item("originals-title") {
            SettingsSectionTitle("原件库存（保存前重新读取历史并校验）")
        }
        items(state.originals.size, key = { "original:${state.originals[it].key}" }) { index ->
            val original = state.originals[index]
            SettingsCard {
                SettingsNote("库存 ${index + 1} · ${original.bytes} bytes · SHA-256 ${original.sha256}")
                LegacyTextPages(LegacyTextSection("原件条目", original.key), state.contentKey)
                TextButton(text = "选择明确的 SAF 目标另存此原件", onClick = { onSave(original.key) },
                    enabled = state.active && !state.busy && !state.pending,
                    modifier = Modifier.testTag("legacy-save:$index"))
            }
        }
    }
}

/** Bounded Text nodes and a bounded number of nodes; every UTF-16 code unit remains reachable. */
@Composable
private fun LegacyTextPages(section: LegacyTextSection, contentKey: Any?) {
    val pageSize = 4096
    val pages = maxOf(1, (section.text.length.toLong() + pageSize - 1).div(pageSize).toInt())
    var page by remember(contentKey, section) { mutableIntStateOf(0) }
    val selected = page.coerceIn(0, pages - 1)
    val start = selected * pageSize
    val end = minOf(section.text.length.toLong(), start.toLong() + pageSize).toInt()
    SettingsSectionTitle(section.title)
    SettingsNote("第 ${selected + 1} / $pages 页 · 共 ${section.text.length} UTF-16 单元（未截断）")
    // Keep surrogate pairs together at page boundaries, without omitting or duplicating units.
    val safeStart = if (start > 0 && start < section.text.length && section.text[start].isLowSurrogate() &&
        section.text[start - 1].isHighSurrogate()) start - 1 else start
    val safeEnd = if (end > 0 && end < section.text.length && section.text[end].isLowSurrogate() &&
        section.text[end - 1].isHighSurrogate()) end - 1 else end
    SettingsNote(if (section.text.isEmpty()) "（空）" else section.text.substring(safeStart, safeEnd))
    Row {
        TextButton(text = "上一页", onClick = { page = selected - 1 }, enabled = selected > 0)
        TextButton(text = "下一页", onClick = { page = selected + 1 }, enabled = selected + 1 < pages)
    }
}
