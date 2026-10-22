"""Source contracts for diagnostic row summaries; Android behavior is tested separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings/SettingsDiagnosticsHost.kt"
ACTIVITY = ROOT / "app/src/androidTest/kotlin/app/daiyu/android/SettingsDiagnosticsActivityTest.kt"


class SettingsDiagnosticSummariesContractTest(unittest.TestCase):
    def setUp(self):
        self.source = HOST.read_text()
        self.row = self.source.split("internal fun SettingsDiagnosticRow(", 1)[1]

    def test_export_summary_is_the_fixed_action_description(self):
        action = self.row.split('"export_logs" ->', 1)[1].split('"clear_logs" ->', 1)[0]
        self.assertIn('summary = "导出诊断事件 JSON；不包含聊天、密钥或路径"', action)
        self.assertNotRegex(action, r"summary\s*=\s*(?:status|ui\.)")

    def test_clear_summary_is_the_fixed_action_description(self):
        action = self.row.split('"clear_logs" ->', 1)[1]
        self.assertIn('summary = "只清除诊断日志，不影响聊天与执行记录"', action)
        self.assertNotRegex(action, r"summary\s*=\s*(?:status|ui\.)")

    def test_logging_row_still_displays_status_and_operation_notices(self):
        logging = self.row.split('"logging" ->', 1)[1].split('"export_logs" ->', 1)[0]
        self.assertIn("summary = status ?: ", logging)
        self.assertIn("else -> ui.notice", self.row)
        for status in ("正在处理诊断日志…", "正在读取诊断日志…", "诊断日志不可用；可清除后重新记录"):
            self.assertIn(status, self.row)
        self.assertIn('mutate(if (enabled) "已开启诊断记录" else "已关闭诊断记录")', self.source)

    def test_operation_availability_keeps_busy_and_corruption_guards_not_recording_enabled(self):
        self.assertIn("val available = ui.state.loaded && !ui.state.unavailable && !ui.busy", self.row)
        self.assertIn("onClick = if (available) ui.export else null", self.row)
        self.assertIn("onClick = if (ui.state.loaded && !ui.busy) ui.clear else null", self.row)
        for action in ("export", "clear"):
            block = self.source.split(action + " = {", 1)[1].split("},", 1)[0]
            self.assertNotIn("state.enabled", block)
            self.assertIn("admitted()", block)
            self.assertIn("!busy && !pickerPending", block)

    def test_android_regression_covers_real_off_on_off_and_scoped_descriptions(self):
        source = ACTIVITY.read_text()
        case = source.split("fun testActionSummariesStayFixedAcrossLoggingToggleAndClearCancellation()", 1)[1].split("fun testRealProducer", 1)[0]
        self.assertGreaterEqual(case.count("assertActionSummaries()"), 4)
        self.assertEqual(case.count('.assertIsOff()'), 2)
        self.assertIn('.assertIsOn()', case)
        self.assertIn('awaitControl("logging disabled")', case)
        self.assertIn('node("dialog-cancel").performClick()', case)
        self.assertIn('assertArrayEquals(saved, fixture.diagnostics.exportSnapshot())', case)
        self.assertIn('assertEquals(databaseBefore, fixture.database.exportBackup())', case)
        self.assertIn('assertEquals(0, fixture.opens.get())', case)
        self.assertIn('hasText(summary) and hasAnyAncestor(hasTestTag("settings-row:$title"))', source)
        self.assertIn('val actionableRow = hasTestTag("settings-row:$title") and hasClickAction()', source)
        self.assertIn('ui.onAllNodes(actionableRow).fetchSemanticsNodes().isNotEmpty()', source)
        self.assertIn('node("settings-row:$title").assertIsEnabled().assertHasClickAction()', source)
        self.assertNotRegex(case, r"Thread\.sleep|delay\(")
        export = source.split("fun testExportWritesOnlyStructuredSnapshotToAnExplicitDocumentTarget()", 1)[1].split("fun testDiagnosticWriteFailure", 1)[0]
        self.assertIn('awaitControl("logging disabled before export")', export)
        self.assertIn('assertArrayEquals(fixture.diagnostics.exportSnapshot(), actual)', export)
        self.assertIn('assertFalse(fixture.diagnostics.state.value.enabled)', export)


if __name__ == "__main__":
    unittest.main()
