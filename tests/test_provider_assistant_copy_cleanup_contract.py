"""Targeted presentation-only cleanup; persistence and failure feedback stay wired."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"

def source(path):
    return (SRC / path).read_text()

class SettingsCopyCleanupContractTest(unittest.TestCase):
    def test_provider_directory_uses_short_names_without_add_row_descriptions(self):
        page = source("ui/screens/settings/ProviderDirectoryPage.kt")
        self.assertIn('ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS -> "OpenAi"', page)
        self.assertIn('SettingsRow("新增 OpenAi",', page)
        self.assertNotIn('Custom OpenAI-compatible', page)
        create = page.split('item("provider-create")', 1)[1].split('item("provider-count")', 1)[0]
        self.assertNotIn('summary =', create)
        for callback in ('onAddOpenAi', 'onAddAnthropic', 'onAddSpeech'):
            self.assertIn(callback, create)
        self.assertIn('it.wireName == labels.getString("$feature.protocol", null)', source("ui/screens/settings/SettingsModelSource.kt"))

    def test_authentication_omits_only_the_footer(self):
        page = source("ui/screens/settings/ProviderDirectoryPage.kt").split('internal fun ProviderAuthenticationPage(', 1)[1]
        self.assertNotIn('OAuth 通过服务商网页登录；API Key 使用您手动配置的密钥。', page)
        for kept in ('OpenAI 官方接口或兼容的第三方站点', '使用 OpenAI Codex 登录', 'SettingsNote(it, error = true)', 'onApiKey else null', 'onOAuth else null'):
            self.assertIn(kept, page)

    def test_speech_probe_omits_static_caption_not_button_or_result(self):
        page = source("ui/screens/settings/ModelSettingsScreen.kt")
        self.assertNotIn('测试会合成“你好”，可能产生语音服务用量；不会播放或录音。', page)
        self.assertIn('enabled = enabled && !connection.running, onClick = connection.start', page)
        self.assertIn('connection.status?.let { SettingsNote(it) }', page)

    def test_request_body_caption_keeps_json_guidance(self):
        page = source("ui/screens/settings/ProviderInlineEditors.kt")
        self.assertIn('值使用 JSON 格式，字符串请加双引号。', page)
        self.assertNotIn('不是请求头', page)
        self.assertIn('可覆盖 User-Agent；认证与传输请求头由系统管理。', page)
        self.assertIn('ProviderRequestOptions(customBody = body).validate(draft.protocol)', page)

    def test_memory_editor_keeps_switch_and_draft_without_label_or_status(self):
        page = source("ui/screens/assistants/AssistantEditScreen.kt")
        self.assertNotIn('R.string.assistant_memory_content', page)
        self.assertNotIn('R.string.assistant_enabled else R.string.assistant_disabled', page)
        for kept in ('assistant-memory-switch', 'assistant-edit-memory', 'memoryEditor.edit(it); memoryDraft = it', 'R.string.assistant_memory_caption', 'R.string.assistant_memory_placeholder', 'if (memoryOverLimit)'):
            self.assertIn(kept, page)

    def test_recognition_save_omits_success_and_local_caption_not_errors(self):
        page = source("VoiceSettingsPanel.kt")
        self.assertNotIn('notice = "已保存"', page)
        self.assertNotIn('无需账户。上一页下载语音包后，即可离线识别。', page)
        for kept in ('if (alive.get()) { onSaved(); notice = "" }', 'runtime.save(next, target)', 'preferences.rememberCloud(target, next)', '未保存，请检查模型、接口地址、音色和密钥。', '设置未保存', 'if (notice.isNotBlank()) item { SettingsNote(notice, error = notice.startsWith("未")) }'):
            self.assertIn(kept, page)

    def test_upstream_format_has_no_protocol_description(self):
        page = source("ui/screens/settings/ProviderSettingsComponents.kt")
        section = page.split('internal fun ProviderProtocolFields(', 1)[1].split('internal fun ProviderChoiceRow(', 1)[0]
        self.assertIn('ProviderChoiceRow("上游格式",', section)
        self.assertNotIn('summary =', section)
        self.assertNotIn('Endpoint 模式', section)
        self.assertIn('listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)', section)
        self.assertIn('onChange(draft.selectProtocol(protocols[choice]))', section)

    def test_all_four_balance_templates_omit_only_preset_description(self):
        page = source("ui/screens/settings/ProviderBalanceFields.kt")
        selector = page.split('ProviderChoiceRow("预设模板",', 1)[1].split('if (options.preset ==', 1)[0]
        self.assertNotIn('summary =', selector)
        self.assertIn('change(BalanceOption.applyPreset(presets[it], options))', selector)
        self.assertIn('listOf("自定义", "NewAPI", "Sub2API", "DeepSeek")', page)
        self.assertNotIn('从 NewAPI 的个人安全设置获取；留空使用提供商 API Key。', page)
        self.assertIn('summary = "从提供商获取账户余额"', page)
        self.assertIn('enabled = enabled && testBalance != null && valid && !test.running, onClick = test.start', page)
