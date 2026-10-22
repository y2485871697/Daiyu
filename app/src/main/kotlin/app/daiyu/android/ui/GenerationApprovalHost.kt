package app.daiyu.android.ui

import app.daiyu.android.ui.components.managedInput
import app.daiyu.android.ui.components.materialImeCursorColor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import app.daiyu.android.ui.components.DaiyuAlertDialog as AlertDialog
import androidx.compose.material3.*
import app.daiyu.android.ui.haptics.material.Button
import app.daiyu.android.ui.haptics.material.TextButton
import app.daiyu.android.ui.haptics.material.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.daiyu.android.*
import app.daiyu.android.model.generation.*
import java.security.MessageDigest

/** Parent-owned form. Copy changes must also increment the parent's editor revision. Nothing in
 * this form, including selected profile/download hosts, is an authorization or saved approval.
 */
internal data class GenerationApprovalForm(
    val profileId: String? = null,
    val prompt: String = "",
    val size: String = "",
    val duration: String = "",
    val maxPolls: String = "4",
    val references: List<GenerationInput> = emptyList(),
    val mask: GenerationInput? = null,
    val otherParameters: Map<String, String> = emptyMap(),
) {
    fun selection(): GenerationBatchSelection {
        val parameters = otherParameters.toMutableMap()
        if (size.isNotEmpty()) { require("size" !in parameters); parameters["size"] = size }
        if (duration.isNotEmpty()) { require("duration" !in parameters); parameters["duration"] = duration }
        return GenerationBatchSelection("batch", requireNotNull(profileId), listOf(
            GenerationRunConfig.Item("item", prompt, references, mask, parameters)))
    }
    override fun toString() = "GenerationApprovalForm(<redacted>)"
}

/** Optional adjunct, not a replacement SEND and not mounted by this change.
 * Parent supplies explicit generation settings and immutable local media, reserves identity, and
 * increments editor.revision on every form/settings/attachment change (even edit-then-revert).
 * Parent must retain confirmationHandoff before composition, including on false/throw from
 * onConfirmed. Its cleanup notification only schedules the existing bounded cleanup worker.
 * After true the parent owns the token until runner/leaf/ledger drain. No blocking close here.
 * Download host edits come from a separately reviewed trusted settings page, never model output.
 */
@Composable
internal fun GenerationApprovalHost(
    identity: GenerationPreparationIdentity,
    editor: GenerationEditorSnapshot,
    profiles: List<GenerationModelProfile>,
    downloads: GenerationDownloadHosts,
    form: GenerationApprovalForm,
    confirmationHandoff: GenerationConfirmationHandoff,
    onFormChange: (GenerationApprovalForm) -> Unit,
    onPickReference: () -> Unit,
    onPickMask: () -> Unit,
    onConfirmed: (ConfirmedGenerationPreparation) -> Boolean,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var preparation by remember(identity) { mutableStateOf<ControllerGenerationPreparation?>(null) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var preparedForm by remember(identity) { mutableStateOf<GenerationApprovalForm?>(null) }
    var preparedProfiles by remember(identity) { mutableStateOf<List<GenerationModelProfile>>(emptyList()) }
    var preparedDownloads by remember(identity) { mutableStateOf<GenerationDownloadHosts?>(null) }
    val sourceCurrent = preparedForm == form && preparedProfiles == profiles && preparedDownloads === downloads
    // No hidden enable toggle and no default profile. Every POST requires a separate button.
    val frozen = preparation
    DisposableEffect(frozen) { onDispose { frozen?.cancel() } }
    LaunchedEffect(identity, editor.revision, editor.text, editor.attachmentIds, form, profiles, downloads) {
        preparation?.let {
            if (it.preview.identity != identity || !it.preview.editor.matches(editor) || !sourceCurrent) {
                it.cancel(); preparation = null
                error = "草稿或设置已改变，请重新预览并确认。"
            }
        }
    }
    fun edit(value: GenerationApprovalForm) {
        preparation?.cancel(); preparation = null; error = null
        onFormChange(value)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("本次图片 / 视频生成（独立付费授权）")
        Text("不使用聊天模型。选择生成专用配置只是在填写表单，并不授权请求。")
        if (profiles.isEmpty()) Text("未配置独立生成模型；不会回退到聊天模型。")
        profiles.forEach { profile ->
            OutlinedButton(onClick = { edit(form.copy(profileId = profile.id)) }) {
                Text("${if (form.profileId == profile.id) "已选择：" else "选择："}${profile.label} · ${profile.protocol.name}")
            }
        }
        OutlinedTextField(form.prompt, { edit(form.copy(prompt = it)) }, label = { Text("原始 prompt（不改写）") },
            modifier = Modifier.managedInput().fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
            )
        OutlinedTextField(form.size, { edit(form.copy(size = it)) }, label = { Text("size（原值；当前客户端不支持，非空将阻止确认）") },
            modifier = Modifier.managedInput().fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
            )
        OutlinedTextField(form.duration, { edit(form.copy(duration = it)) }, label = { Text("视频 duration（原值；当前不支持）") },
            modifier = Modifier.managedInput().fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
            )
        OutlinedTextField(form.maxPolls, { edit(form.copy(maxPolls = it)) }, label = { Text("maxPolls：0–32，仅轮询原任务，不重发 POST") },
            singleLine = true,
                modifier = Modifier.managedInput(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
            )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickReference) { Text("选择原图 / 视频参考图") }
            OutlinedButton(onClick = onPickMask) { Text("选择 mask") }
        }
        form.references.forEachIndexed { index, input -> GenerationInputPreview("原图 $index", input) }
        form.mask?.let { GenerationInputPreview("mask", it) }
        if (form.references.isNotEmpty() || form.mask != null) {
            TextButton(onClick = { edit(form.copy(references = emptyList(), mask = null)) }) { Text("移除原图与 mask") }
        }
        Text("下载授权单独审核；仅允许 HTTPS:443，禁止所有重定向。当前精确 host：${downloads.hosts.sorted().joinToString().ifEmpty { "无（拒绝下载）" }}")
        Button(enabled = form.profileId != null && preparation == null, onClick = {
            error = null
            preparation = runCatching {
                require(form.maxPolls.matches(Regex("[0-9]{1,2}")))
                ControllerGenerationPreparation.prepare(identity, editor, profiles, listOf(form.selection()),
                    form.maxPolls.toInt(), downloads).also {
                    preparedForm = form.copy(references = form.references.toList(), otherParameters = form.otherParameters.toMap())
                    preparedProfiles = profiles.toList()
                    preparedDownloads = downloads
                }
            }.getOrElse { error = "无法准备：请检查独立模型、原始参数和 maxPolls。未发送任何请求。"; null }
        }) { Text("完整参数预览（不发送）") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (frozen != null) {
        val preview = frozen.preview
        AlertDialog(
            onDismissRequest = { frozen.cancel(); preparation = null; onCancelled() },
            title = { Text("确认本次精确付费生成") },
            text = {
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GenerationPreviewContent(preview)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = preview.blockers.isEmpty() && preview.identity == identity && preview.editor.matches(editor) && sourceCurrent,
                    onClick = {
                        // The parent-owned slot retains the token before any callback can
                        // partially start work. Rejection only seals admission on this thread.
                        val accepted = runCatching {
                            check(sourceCurrent)
                            confirmationHandoff.confirm(frozen, editor, identity, onConfirmed)
                        }.getOrNull()
                        preparation = null
                        if (accepted == null) error = "草稿或运行槽已失效，请重新预览。"
                        else if (!accepted) error = "父级未接纳本次运行；授权已撤销，等待清理。"
                    }) { Text("明确同意本次付费生成") }
            },
            dismissButton = { TextButton(onClick = { frozen.cancel(); preparation = null; onCancelled() }) { Text("取消，不发送") } },
        )
    }
}

@Composable
internal fun GenerationPreviewContent(preview: GenerationPreparationPreview) {
    val identity = preview.identity
    Text("账户范围：${identity.accountScope}\n会话：${identity.conversationId.value}\n运行：${identity.runId.value}\n回复：${identity.assistantMessageId.value}\n编辑 revision：${preview.editor.revision}")
    Text("编辑器原文（${preview.editor.text.length} 字符）：\n${preview.editor.text}")
    Text("草稿附件 ID：${preview.editor.attachmentIds.joinToString("\n")}")
    Text("maxPolls = ${preview.config.maxPolls}；凭据已冻结但不显示。")
    preview.config.batches.forEach { batch ->
        Text("batch_id = ${batch.id}\n配置 = ${preview.profileLabels[batch.id]}\nprotocol = ${batch.protocol.name}\nendpoint = ${batch.endpoint}\nmodel = ${batch.model}")
        batch.items.forEach { item ->
            Text("item_id = ${item.id}\nprompt（${item.prompt.length} 字符，原值）：\n${item.prompt}")
            Text("parameters（原值，不裁剪或替换）：")
            if (item.parameters.isEmpty()) Text("{}；size / duration 均未发送；image n = 1（协议固定）")
            item.parameters.forEach { (key, value) -> Text("[$key]（${value.length} 字符）=\n$value") }
            item.references.forEachIndexed { index, input -> GenerationInputPreview("原图 $index", input) }
            if (item.references.isEmpty()) Text("原图：无")
            item.mask?.let { GenerationInputPreview("mask", it) } ?: Text("mask：无")
        }
    }
    Text("下载单独限制：${preview.downloads.hosts.sorted().joinToString().ifEmpty { "拒绝全部远程下载" }}；只允许 HTTPS:443，拒绝重定向；仍须父级 DNS/地址策略通过。")
    preview.blockers.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("这是付费生成的明确确认，不是模型能力开关。执行前仍须通过完整媒体/protocol preflight。UNKNOWN/取消可能已计费，禁止更换 run、item 或 call ID 自动重试。")
}

@Composable
private fun GenerationInputPreview(label: String, input: GenerationInput) {
    val bytes = remember(input) { input.bytes }
    val digest = remember(input) { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } }
    Text("$label：${input.mimeType}，${bytes.size} bytes，SHA-256=$digest")
    // Display-only bounded thumbnail, not authoritative media preflight or a rewritten input.
    val bitmap = remember(input) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1))
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, contentDescription = "$label 冻结原图预览", contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp))
    else Text("无法显示预览；不会转换或忽略原图，执行时须通过媒体验证。")
}
