"""Source guards; Android regressions are executed in the authorized test batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class ChatSettingsFeedbackContractTest(unittest.TestCase):
    def test_busy_banner_uses_actual_progress_not_a_generic_backup_label(self):
        host = (SRC / "MainActivity.kt").read_text()
        status = host.split("statusText = controller.problem ?: when {", 1)[1].split("composer = ComposerUi", 1)[0]
        self.assertIn("controller.dataBusy -> controller.dataStatus", status)
        self.assertNotIn('"处理备份中"', status)
        for text in ('!controller.ready -> "恢复会话中"', 'pendingTextSend != null', 'imageHost.sending'):
            self.assertIn(text, status)
        controller = (SRC / "ChatController.kt").read_text()
        self.assertIn("initialStatus = null", controller)
        for text in ('initialStatus = "正在准备导出"', 'initialStatus="正在读取备份"'):
            self.assertIn(text, controller)

    def test_provider_success_is_silent_but_warning_and_failure_remain(self):
        controller = (SRC / "ChatController.kt").read_text()
        save = controller.split("internal fun saveProviderSettings(", 1)[1].split("private fun switchChatReasoningModel", 1)[0]
        self.assertIn("try { providers.catalogSnapshot(providers.editor(editor.slotId)); null }", save)
        self.assertNotIn('"提供商设置已保存"', save)
        self.assertIn("提供商设置已保存；模型目录尚不可用，请重新打开详情", save)
        self.assertIn("提供商或余额保存未完整确认；请重新打开详情核对", save)
        self.assertIn("providers.save(editor, value, expected", save)
        self.assertIn("owner.commit", save)

    def test_silent_result_preserves_busy_gate_callback_and_error_handling(self):
        controller = (SRC / "ChatController.kt").read_text()
        task = controller.split("@Synchronized internal fun dataTask(", 1)[1].split("internal val supplementDraft", 1)[0]
        for text in ("work:()->String?", "work:((String,Boolean)->Unit)->String?", "var value:String?=failureNotice",
                     "value=work(report);success=true", "dataNotice=notice;dataBusy=false", "completed(success)",
                     "check(idleAvailable() && !hasLiveChildTasks())", "catch(failure:Exception)"):
            self.assertIn(text, task)
        host = (SRC / "MainActivity.kt").read_text()
        self.assertIn("controller.consumeDataNotice()?.let { toast(it) }", host)

    def test_android_regressions_hold_pending_save_and_cover_quiet_success_and_failure(self):
        base = ROOT / "app/src/androidTest/kotlin/app/daiyu/android"
        speed = (base / "ChatGptSpeedActivityTest.kt").read_text()
        for text in ("testPendingSpeedSaveKeepsChatViewportStableForEveryMode", "configurationChange", "release.await",
                     'node("chat-status").assertDoesNotExist()', "beforeMessages.top", "beforeComposer.top",
                     "GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST, GptSpeedMode.NORMAL"):
            self.assertIn(text, speed)
        provider = (base / "ControllerProviderSaveNoticeTest.kt").read_text()
        for text in ("testSuccessfulProviderSaveHasNoNoticeAndStillCommits", "testRejectedProviderSaveKeepsFailureNoticeAndOldSettings",
                     "assertNull(fixture.controller.consumeDataNotice())", "assertEquals(before, fixture.settings.load())", "owner.revoke()",
                     "testOtherDataTaskStillPublishesItsSuccessNotice", 'assertEquals("other task completed", fixture.controller.consumeDataNotice())'):
            self.assertIn(text, provider)


if __name__ == "__main__":
    unittest.main()
