from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class ToastConciseCopyContractTest(unittest.TestCase):
    def text(self, path):
        return (HOST / path).read_text(encoding='utf-8')

    def test_gui_invalid_arguments_show_only_the_cause(self):
        main = self.text('MainActivity.kt')
        self.assertIn('toast("GUI 参数无效")', main)
        self.assertNotIn('GUI 参数无效；未打开授权、未执行动作', main)
        self.assertIn('toast(decision.message)', main)
        self.assertNotIn('toast("未执行：${decision.message}")', main)

    def test_rejected_and_unknown_sends_remain_distinct(self):
        main = self.text('MainActivity.kt')
        self.assertIn('toast(if (unknown) "发送结果未确认" else "本次发送未受理")', main)
        self.assertIn('toast(controller.problem ?: "本次发送未受理")', main)
        send = main.split("private fun sendDraft(", 1)[1].split("private fun cancelPendingTextSend(", 1)[0]
        self.assertIn('toast(if (unknown) "发送结果未确认" else "本次发送未受理")', send)
        self.assertNotIn('草稿保留，不会自动重发', main)
        self.assertNotIn('草稿保留，不自动重试', main)

    def test_unconfirmed_operations_do_not_claim_failure_or_success(self):
        controller = self.text('ChatController.kt')
        for reason in ['系统操作结果未确认', '副屏清理结果未确认', '生成保存或清理结果未确认']:
            self.assertIn('"' + reason + '"', controller)
        self.assertNotIn('原资源保留，不自动重连或重发', controller)

    def test_model_failure_messages_and_title_success_stay_separate(self):
        controller = self.text('ChatController.kt')
        self.assertIn('TitleOutcome.SAVED -> null', controller)
        self.assertIn('"独立标题配置不可用"', controller)
        self.assertNotIn('会话标题已保存', controller)
        self.assertNotIn('聊天结果不受影响', controller)

    def test_task_surface_codes_keep_specific_readable_causes(self):
        policy = self.text('tasksurface/TaskSurfacePolicy.kt')
        causes = {
            'TASK_SURFACE_RUN_ENDED': '任务已结束',
            'TASK_SURFACE_SETTINGS_UNAVAILABLE': '执行位置设置不可用',
            'TASK_SURFACE_TARGET_REQUIRED': '缺少目标应用',
            'TASK_SURFACE_ASK_CANCELLED': '已取消执行位置选择',
        }
        for code, reason in causes.items():
            self.assertIn('"' + code + '" -> "' + reason + '"', policy)
        self.assertIn('"后台执行不可用"', policy)
        self.assertNotIn('不会切换到前台执行', policy)

    def test_compound_rejections_keep_all_actual_causes(self):
        main = self.text('MainActivity.kt')
        controller = self.text('ChatController.kt')
        self.assertIn('"生成未受理，请重新预览"', main)
        self.assertIn('"会话未就绪、恢复失败或有任务正在进行"', main)
        self.assertIn('"原视频目录证据、授权或原件校验失败"', controller)
        self.assertIn('"独立摘要配置不可用（${failure.reason.name}），请重新保存"', controller)

    def test_backup_protocol_match_is_not_rewritten_as_display_copy(self):
        controller = self.text('ChatController.kt')
        self.assertIn('"备份来源已变化；未自动重试" -> "源数据已变化，请重新导出"', controller)

    def test_voice_notices_keep_the_error_without_draft_tails(self):
        for path in ['voice/ComposerDictationHost.kt', 'voice/offline/OfflineComposerHost.kt',
                     'ActivityCascadeVoiceHost.kt', 'ActivityRemoteVoiceHost.kt']:
            text = self.text(path)
            self.assertNotIn('草稿保留', text)
            self.assertNotIn('草稿已保留', text)
            self.assertNotIn('不自动重试', text)

    def test_voice_resource_notices_are_concise_in_both_locales(self):
        for locale in ['values', 'values-zh']:
            text = (ROOT / 'app/src/main/res' / locale / 'voice_host.xml').read_text(encoding='utf-8')
            self.assertIn('<string name="voice_host_initialization_failed">系统语音引擎初始化失败</string>', text)
            self.assertIn('<string name="voice_host_playback_failed">系统朗读失败</string>', text)


if __name__ == '__main__':
    unittest.main()
