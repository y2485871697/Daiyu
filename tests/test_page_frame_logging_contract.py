"""Production entry-point contracts; timing/storage behavior has real JVM tests."""
from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"

class PageFrameLoggingContract(unittest.TestCase):
    def test_activity_installs_once_before_composition(self):
        source = (APP / "MainActivity.kt").read_text()
        self.assertEqual(1, source.count("WindowFramePerformance.install(this, controller.diagnostics)"))
        self.assertLess(source.index("WindowFramePerformance.install"), source.index("        setContent {"))

    def test_all_static_main_routes_are_privacy_safe_labels(self):
        source = (APP / "MainActivity.kt").read_text()
        routes = set(re.findall(r'retainedPage(?:State)?\s*=\s*"([a-z][a-z-]+)"', source))
        policy = (APP / "diagnostics/FramePerformanceLog.kt").read_text().split("    fun part")[0]
        for route in routes:
            self.assertIn('"' + route + '"', policy)

    def test_nested_route_not_animation_content_is_reported(self):
        source = (APP / "ui/navigation/PageBackGestures.kt").read_text()
        self.assertIn("ObservePageFrames(visualRouteKey)", source)
        self.assertNotIn("ObservePageFrames(contentKey)", source)

    def test_shared_scaffolds_register_content_without_title_logging(self):
        source = (APP / "ui/components/MiuixScaffoldPage.kt").read_text()
        self.assertEqual(2, source.count('ObserveFrameContext("screen", title)'))
        policy = (APP / "diagnostics/PerformanceScreenNames.kt").read_text()
        self.assertIn('title.takeIf { it in names } ?: "detail"', policy)

    def test_all_third_party_dialog_content_paths_register_real_windows(self):
        for path in APP.rglob("*.kt"):
            source = path.read_text()
            if re.search(r"\bWindowDialog\(", source):
                self.assertIn("ObserveDialogFrames(", source, str(path.relative_to(APP)))
        for name in ("DialogMotion.kt", "PreviewDialog.kt"):
            self.assertIn("ObserveDialogFrames(", (APP / "ui/components" / name).read_text())
        self.assertIn("watchNative", (APP / "ui/components/NativeDialogMotion.kt").read_text())

    def test_listener_has_background_handler_and_real_window_metrics(self):
        source = (APP / "diagnostics/WindowFramePerformance.kt").read_text()
        self.assertIn('HandlerThread("daiyu-frame-metrics")', source)
        self.assertIn("addOnFrameMetricsAvailableListener(session.listener, target)", source)
        self.assertIn("removeOnFrameMetricsAvailableListener(session.listener)", source)
        self.assertIn("FrameMetrics.INTENDED_VSYNC_TIMESTAMP", source)
        self.assertIn("Build.VERSION.SDK_INT >= 31", source)
        self.assertIn("done.await(5, TimeUnit.SECONDS)", source)
        self.assertNotIn("Log.d(", source)
        self.assertNotIn("window.title", source)

    def test_writer_is_bounded_and_capture_does_not_take_file_lock(self):
        source = (APP / "diagnostics/FramePerformanceLog.kt").read_text()
        self.assertIn("ArrayBlockingQueue(8)", source)
        capture = source.split("    fun captureEpoch()", 1)[1].split("    fun append", 1)[0]
        self.assertNotIn("synchronized", capture)
        self.assertIn("synchronized(fileLock)", source)
        self.assertIn('"corrupt_rows_skipped"', source)
        self.assertIn('"queue_dropped_windows"', source)

    def test_settings_keeps_existing_switch_and_single_json_export(self):
        source = (APP / "ui/screens/settings/SettingsDiagnosticsHost.kt").read_text()
        self.assertIn('CreateDocument("application/json")', source)
        self.assertIn('picker.launch("daiyu-diagnostics.json")', source)
        self.assertIn('store.exportSnapshot()', source)
        store = (APP / "diagnostics/DiagnosticLogStore.kt").read_text()
        self.assertIn('.put("frame_performance", frames.export())', store)
        self.assertIn('frames.setEnabled(enabled)', store)
        self.assertIn('frames.clear()', store)

    def test_login_and_attachment_have_independent_activity_capture(self):
        for path, page in (("model/oauth/CodexLoginActivity.kt", "oauth-login"),
                           ("attachments/OpenAttachmentActivity.kt", "attachment-receive")):
            source = (APP / path).read_text()
            self.assertIn("WindowFramePerformance.install(this,", source)
            self.assertIn('"' + page + '"', source)
        collector = (APP / "diagnostics/WindowFramePerformance.kt").read_text()
        self.assertIn("registerCapture(host, flushHook)", collector)
        self.assertIn("unregisterCapture(this@WindowFramePerformance)", collector)

    def test_discrete_nonroute_contexts_are_connected(self):
        checks = {
            "ui/screens/terminal/TerminalScreen.kt": ("terminal-mode", "terminal-session"),
            "ui/screens/settings/LinuxSettingsScreen.kt": ("linux-tab",),
            "browser/ported/ui/browser/BrowserSheet.kt": ("browser-tab", "browser-panel"),
            "ui/screens/settings/ProviderManagementRoute.kt": ("provider-tab",),
        }
        for path, tags in checks.items():
            source = (APP / path).read_text()
            for tag in tags:
                self.assertIn('ObserveFrameContext("' + tag + '"', source)

if __name__ == "__main__":
    unittest.main()
