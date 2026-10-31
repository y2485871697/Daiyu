"""Controller wiring guards; JVM tests exercise ownership and actual Android service lifecycle."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"

class BackgroundExecutionContract(unittest.TestCase):
    def test_only_user_execution_foreground_permissions_and_private_service(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        key = "{http://schemas.android.com/apk/res/android}"
        permissions = {node.get(key + "name") for node in manifest.findall("uses-permission")}
        self.assertTrue({"android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"} <= permissions)
        service = next(n for n in manifest.findall("application/service") if n.get(key + "name") == ".execution.UserExecutionService")
        self.assertEqual("false", service.get(key + "exported"))
        self.assertEqual("specialUse", service.get(key + "foregroundServiceType"))
        self.assertNotIn("android.permission.ACCESS_NOTIFICATION_POLICY", permissions)

    def test_service_is_confirmed_before_model_or_database_admission(self):
        controller = (APP / "ChatController.kt").read_text()
        send = controller.split("private fun sendPrepared(", 1)[1]
        self.assertLess(send.index("!beginExecution(token)"), send.index("val frozenDraft"))
        self.assertIn("retainExecution(token, assistant)", send)
        begin = controller.split("private fun beginExecution(", 1)[1].split("private fun retainExecution", 1)[0]
        for token in ["UserExecutionService.acquire(application)", "token.revoked = true", "token.cancellation.cancel()", "stop(owner)", "backgroundRuns[owner.runId]"]:
            self.assertIn(token, begin)
        service = (APP / "execution/UserExecutionService.kt").read_text()
        self.assertIn("lease.awaitStarted(8_000)", service)
        self.assertIn("START_NOT_STICKY", service)
        self.assertIn("leases.started(current)", service)
        self.assertIn("FOREGROUND_SERVICE_TYPE_SPECIAL_USE", service)

    def test_physical_drain_not_pause_wait_or_window_lifecycle_releases_lease(self):
        controller = (APP / "ChatController.kt").read_text()
        prune = controller.split("private fun pruneExecutionLeases()", 1)[1].split("// Tools can exist", 1)[0]
        for token in ["resources?.initial?.runId == run", "backgroundRuns[run]?.isReleased() == false", "!it.drained", "UserExecutionService.release(lease)"]:
            self.assertIn(token, prune)
        cleanup = controller.split("private fun cleanupRun(", 1)[1].split("@Synchronized internal fun canRetry", 1)[0]
        self.assertLess(cleanup.index("if(!lifetime.deviceCleanup.drained) return"), cleanup.index("pruneExecutionLeases()"))
        self.assertIn("releaseExecutionPreparation(token)", controller)
        self.assertIn("if (!retained) app.daiyu.android.execution.UserExecutionService.release(lease)", controller)
        self.assertIn("unacceptedTools.remove(tools)?.execution?.let(app.daiyu.android.execution.UserExecutionService::release)", controller)
        self.assertIn("if (!token.executionTransferred)", controller)
        service = (APP / "execution/UserExecutionService.kt").read_text()
        self.assertIn("leases.requestStop(current)", service)
        self.assertIn("leases.retireIfIdle(current)", service)
        for forbidden in ["UserExecutionService", "pruneExecutionLeases"]:
            main = (APP / "MainActivity.kt").read_text()
            self.assertNotIn(forbidden, main)

    def test_original_haptic_settings_motor_usage_and_tick_rate_are_not_replaced(self):
        engine = (ROOT / "core-domain/src/main/kotlin/app/daiyu/android/domain/haptics/HapticEngine.kt").read_text()
        port = (APP / "haptics/AndroidHapticPort.kt").read_text()
        self.assertIn("if (now - lastTick < 32L) return null", engine)
        self.assertIn("VibrationAttributes.USAGE_TOUCH", port)
        self.assertNotIn("USAGE_NOTIFICATION", port)
        self.assertNotIn("FLAG_BYPASS", port)
        for token in ["settings.generationOn", "settings.backgroundOn", "settings.backgroundReasoningOn"]:
            self.assertIn(token, engine)

if __name__ == "__main__":
    unittest.main()
