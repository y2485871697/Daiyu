package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.model.generation.GenerationDownloadHosts
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationProfileSettings
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.model.generation.GenerationSettingsEditingSnapshot
import app.daiyu.android.model.generation.GenerationSettingsSnapshot
import app.daiyu.android.model.generation.GenerationSettingsValues
import top.yukonga.miuix.kmp.basic.TextButton

/** Memory only. Never rememberSaveable, log, persist as plaintext, or include in a backup. */
data class GenerationProfileDraftUi(
    val id: String? = null,
    val label: String = "",
    val kind: GenerationKind? = null,
    val protocol: GenerationProtocol? = null,
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
) {
    override fun toString() = "GenerationProfileDraftUi(<redacted>)"
}

data class GenerationSettingsDraftUi(
    val profiles: List<GenerationProfileDraftUi> = listOf(GenerationProfileDraftUi()),
    val maxPolls: String = "4",
    val downloadsEnabled: Boolean = false,
    val downloadHosts: String = "",
    val downloadHostsReviewed: Boolean = false,
    val expectedRevision: String? = null,
    val accountScope: String? = null,
    val confirmIncompleteSaveRecovery: Boolean = false,
) {
    /** No trim, filtering, protocol inference, endpoint rewriting or parameter dropping. The store
     * performs authoritative validation. Blank/duplicate host lines are errors, not ignored input. */
    fun toSettingsValues(): GenerationSettingsValues {
        require(maxPolls.matches(Regex("0|[1-9][0-9]*"))) { "轮询上限须为 0–32 的整数" }
        val polls = maxPolls.toIntOrNull()
        require(polls != null && polls in 0..32) { "轮询上限须为 0–32 的整数" }
        val hosts = if (downloadHosts.isEmpty()) emptyList() else downloadHosts.split('\n')
        require(hosts.distinct().size == hosts.size) { "下载 host 不得重复" }
        GenerationDownloadHosts(hosts.toSet())
        require(!downloadsEnabled || downloadHostsReviewed) { "启用下载须另行明确确认精确 host 列表" }
        return GenerationSettingsValues(profiles.map {
            GenerationProfileSettings(it.id, it.label, requireNotNull(it.kind) { "请明确选择生成类型" },
                requireNotNull(it.protocol) { "请明确选择生成协议" }, it.endpoint, it.model, it.apiKey)
        }, polls, downloadsEnabled, hosts.toSet())
    }

    override fun toString() = "GenerationSettingsDraftUi(<redacted>)"
}

private fun GenerationProfileSettings.toDraft() = GenerationProfileDraftUi(id, label, kind, protocol, endpoint, model, apiKey)

fun GenerationSettingsSnapshot.toGenerationSettingsDraft() = GenerationSettingsDraftUi(
    profiles = values.profiles.map { it.toDraft() }, maxPolls = values.maxPolls.toString(),
    downloadsEnabled = values.downloadsEnabled, downloadHosts = values.downloadHosts.joinToString("\n"),
    expectedRevision = revision, accountScope = accountScope,
)

fun GenerationSettingsEditingSnapshot.toGenerationSettingsDraft() = GenerationSettingsDraftUi(
    profiles = values.profiles.map { it.toDraft() }, maxPolls = values.maxPolls.toString(),
    downloadsEnabled = values.downloadsEnabled, downloadHosts = values.downloadHosts.joinToString("\n"),
    expectedRevision = revision, accountScope = accountScope,
)

/** Controlled settings component: no store, effect, HTTP, model discovery, paid request or approval.
 * Parent owns draft/lifecycle, performs off-main-thread load/save, displays sanitized errors, and
 * adopts ONLY the read-back snapshot returned by save. Key fields are masked, not blanked on load.
 * Changing kind/protocol never clears other raw input; incompatible combinations fail validation.
 */
@Composable
fun GenerationSettingsScreen(
    draft: GenerationSettingsDraftUi,
    onDraftChange: (GenerationSettingsDraftUi) -> Unit,
    onSave: (GenerationSettingsDraftUi) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    credentialUnavailableIds: Set<String> = emptySet(),
    incompleteSave: Boolean = false,
    enabled: Boolean = true,
) {
    fun update(index: Int, profile: GenerationProfileDraftUi) {
        onDraftChange(draft.copy(profiles = draft.profiles.mapIndexed { i, previous -> if (i == index) profile else previous }))
    }
    SettingsScaffoldPage(title = "独立生成模型设置", onBack = onBack, modifier = modifier) {
        item(key = "generation_explanation") {
            SettingsNote("仅保存生成配置，不使用聊天模型，不联网、不探测能力，也不授权付费运行。每次运行仍需单独预览并确认。")
            SettingsNote("endpoint 必须是服务商完整 HTTPS 生成地址；不会补路径或自动选择协议。附加请求参数由运行编辑器保留并校验，本页不会丢弃或替换参数。")
            draft.accountScope?.let { SettingsNote("持久账户范围：$it") }
        }
        draft.profiles.forEachIndexed { index, profile ->
            item(key = "generation_profile_$index") {
                SettingsSectionTitle("生成配置 ${index + 1}")
                profile.id?.let { SettingsNote("持久配置 ID：$it") }
                if (profile.id?.let { it in credentialUnavailableIds } == true)
                    SettingsNote("此配置的密钥丢失或绑定不符；请重新输入后明确保存。", error = true)
                SettingsCard {
                    SettingsTextField("显示名称", profile.label, { update(index, profile.copy(label = it)) }, enabled = enabled)
                    SettingsRadioRow("图片", profile.kind == GenerationKind.IMAGE, enabled = enabled) {
                        update(index, profile.copy(kind = GenerationKind.IMAGE))
                    }
                    SettingsRadioRow("视频", profile.kind == GenerationKind.VIDEO, enabled = enabled) {
                        update(index, profile.copy(kind = GenerationKind.VIDEO))
                    }
                    SettingsRadioRow("OpenAI 图片生成 JSON", profile.protocol == GenerationProtocol.OPENAI_IMAGES_JSON, enabled = enabled) {
                        update(index, profile.copy(protocol = GenerationProtocol.OPENAI_IMAGES_JSON))
                    }
                    SettingsRadioRow("OpenAI 图片编辑 Multipart", profile.protocol == GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART, enabled = enabled) {
                        update(index, profile.copy(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART))
                    }
                    SettingsRadioRow("OpenAI 视频 Multipart", profile.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART, enabled = enabled) {
                        update(index, profile.copy(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART))
                    }
                    SettingsTextField("完整生成 endpoint", profile.endpoint, { update(index, profile.copy(endpoint = it)) }, enabled = enabled)
                    SettingsTextField("生成模型名", profile.model, { update(index, profile.copy(model = it)) }, enabled = enabled)
                    SettingsTextField("生成 API Key", profile.apiKey, { update(index, profile.copy(apiKey = it)) }, password = true, enabled = enabled, compact = true)
                    TextButton(text = "移除此配置", enabled = enabled && draft.profiles.size > 1,
                        onClick = { onDraftChange(draft.copy(profiles = draft.profiles.filterIndexed { i, _ -> i != index })) },
                        modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
        }
        item(key = "generation_add_profile") {
            TextButton(text = "添加独立生成配置", enabled = enabled && draft.profiles.size < 16,
                onClick = { onDraftChange(draft.copy(profiles = draft.profiles + GenerationProfileDraftUi())) },
                modifier = Modifier.testTag("generation-add-profile").fillMaxWidth().padding(12.dp))
        }
        item(key = "generation_limits") {
            SettingsSectionTitle("运行配置")
            SettingsCard {
                SettingsTextField("最多轮询次数（0–32）", draft.maxPolls,
                    { onDraftChange(draft.copy(maxPolls = it)) }, enabled = enabled)
            }
        }
        item(key = "generation_downloads") {
            SettingsSectionTitle("单独选择下载权限范围")
            SettingsNote("空集合禁止下载。只接受小写精确 HTTPS host（每行一个域名），不能输入 URL、端口、IP、通配符、空白或后缀匹配。不会自动加入 endpoint 的 host。")
            SettingsCard {
                SettingsTextField("允许下载的精确 host", draft.downloadHosts,
                    { onDraftChange(draft.copy(downloadHosts = it, downloadHostsReviewed = false)) }, multiline = true, enabled = enabled)
                SettingsSwitchRow("允许从所列 host 下载", draft.downloadsEnabled, enabled = enabled) {
                    onDraftChange(draft.copy(downloadsEnabled = it, downloadHostsReviewed = false))
                }
                SettingsSwitchRow("已单独核对并确认以上下载 host", draft.downloadHostsReviewed, enabled = enabled && draft.downloadsEnabled) {
                    onDraftChange(draft.copy(downloadHostsReviewed = it))
                }
            }
            SettingsNote("这是下载 allowlist，不是付费授权；父层仍必须实施网络/DNS 安全策略并拒绝重定向。")
        }
        item(key = "generation_save") {
            if (incompleteSave) {
                SettingsNote("上次保存未完成，当前禁止生成。请检查全部原值和密钥后明确重新保存。", error = true)
                SettingsCard {
                    SettingsSwitchRow("确认恢复未完成的保存", draft.confirmIncompleteSaveRecovery, enabled = enabled) {
                        onDraftChange(draft.copy(confirmIncompleteSaveRecovery = it))
                    }
                }
            }
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
            TextButton(text = "保存生成配置（不运行）", onClick = { onSave(draft) }, enabled = enabled,
                modifier = Modifier.testTag("generation-settings-save").fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp))
        }
    }
}
