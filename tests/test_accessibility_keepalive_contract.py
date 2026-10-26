from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
A = "{http://schemas.android.com/apk/res/android}"

class AccessibilityKeepAliveContract(unittest.TestCase):
    def read(self, name):
        return (SRC / "gui/keepalive" / name).read_text()

    def test_module_has_real_system_only_entry(self):
        base = ROOT / "app/src/main/resources/META-INF/xposed"
        self.assertEqual("system", (base / "scope.list").read_text().strip())
        self.assertEqual("app.daiyu.android.gui.keepalive.AccessibilityKeepAliveModule",
                         (base / "java_init.list").read_text().strip())
        module = self.read("AccessibilityKeepAliveModule.kt")
        for value in ("onSystemServerStarting", 'it.name == "startOtherServices"',
                      "chain.proceed()", "HandlerThread", "AccessibilityKeepAliveEnforcer(handler)"):
            self.assertIn(value, module)
        self.assertIn('compileOnly("io.github.libxposed:api:102.0.0")', (ROOT / "app/build.gradle.kts").read_text())
        self.assertIn('resources.merges += "META-INF/xposed/*"', (ROOT / "app/build.gradle.kts").read_text())

    def test_module_uses_public_worker_and_releases_failed_start(self):
        module = self.read("AccessibilityKeepAliveModule.kt")
        self.assertNotIn("BackgroundThread", module)
        self.assertNotIn('getDeclaredMethod("getHandler")', module)
        self.assertIn("Process.THREAD_PRIORITY_BACKGROUND", module)
        self.assertIn("Handler(checkNotNull(thread.looper))", module)
        self.assertIn("if (!value.start(context)) return", module)
        self.assertIn("if (enforcer == null) worker?.quitSafely()", module)
        self.assertLess(module.index("thread.start()"), module.index("thread.looper"))
        self.assertLess(module.index("if (!value.start(context))"), module.index("enforcer = value"))
        enforcer = self.read("AccessibilityKeepAliveEnforcer.kt")
        self.assertIn("fun start(context: Context): Boolean", enforcer)
        self.assertIn("if (!queued) started.set(false)", enforcer)
        self.assertIn("return queued", enforcer)

    def test_no_manual_switch_or_task_privileged_backend(self):
        panel = (SRC / "ui/screens/settings/GuiSettingsPanel.kt").read_text()
        self.assertNotIn("强制保持", panel)
        source = "\n".join(p.read_text() for p in (SRC / "gui/keepalive").glob("*.kt"))
        for value in ("ProcessBuilder", "Shizuku", "sendOrderedBroadcast", "ACTION_SET", "openForUser(", "getRootInActiveWindow"):
            self.assertNotIn(value, source)

    def test_names_are_only_daiyu_and_disclosure_remains(self):
        for folder in ("values", "values-zh"):
            values = ET.parse(ROOT / f"app/src/main/res/{folder}/gui_strings.xml").getroot()
            self.assertEqual("代鱼", values.find("string[@name='gui_accessibility_label']").text)
            description = values.find("string[@name='gui_accessibility_description']").text
            self.assertIn("系统服务不等于授权 Agent", description)
            self.assertIn("首次手动授予", description)

    def test_health_provider_is_system_only_and_has_no_screen_payload(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        provider = next(p for p in manifest.findall("application/provider")
                        if p.get(A + "name") == ".gui.keepalive.AccessibilityHealthProvider")
        self.assertEqual("android.permission.MANAGE_ACCESSIBILITY", provider.get(A + "permission"))
        self.assertEqual("${applicationId}.accessibility.health", provider.get(A + "authorities"))
        source = self.read("AccessibilityHealthProvider.kt")
        for value in ("Binder.getCallingUid() != Process.SYSTEM_UID", "HEALTH_METHOD", "EXTRA_PROTOCOL_VERSION", "GuiRuntime.hasServiceConnection()"):
            self.assertIn(value, source)
        for value in ("rootInActiveWindow", "takeScreenshot", "getText", "openForUser"):
            self.assertNotIn(value, source)

    def test_real_connection_is_volatile_and_old_leases_still_revoke(self):
        source = (SRC / "gui/GuiAccessibilityService.kt").read_text()
        self.assertIn("@Volatile private var service: GuiAccessibilityService? = null", source)
        self.assertIn("internal fun hasServiceConnection(): Boolean = service != null", source)
        self.assertIn("targetBinding = null; clearNodes(); service = null; generation++; gate.disconnect()", source)
        self.assertIn("if (!enabled) GuiRuntime.disconnected", source)

    def test_default_eligibility_requires_first_actual_system_grant(self):
        source = self.read("AccessibilityKeepAliveEnforcer.kt")
        self.assertIn('SETTING_NAME, -1)', source)
        self.assertIn("accessibilityKeepAliveAuthorized(saved, uid, configured)", source)
        self.assertLess(source.index("ensureObserversRegistered(context)", source.index("private fun reconcile")),
                        source.index("val enabled = isEnforcementEnabled", source.index("private fun reconcile")))
        self.assertIn("Settings.Global.putInt(resolver, AccessibilityProtectionProtocol.SETTING_NAME, uid)", source)

    def test_package_invalidations_and_queued_repair_are_fenced(self):
        source = self.read("AccessibilityKeepAliveEnforcer.kt")
        for value in ("ACTION_PACKAGE_DATA_CLEARED", "ACTION_PACKAGE_REMOVED", "EXTRA_REPLACING",
                      "grantInvalidated = true", "SETTING_NAME, -2)", "!isExpectedServiceListed(context)",
                      "saved >= 0 && saved != uid", "generation != lifecycleGeneration", "FLAG_STOPPED"):
            self.assertIn(value, source)

    def test_no_exception_can_escape_post_and_repair_flag_is_released(self):
        source = self.read("AccessibilityKeepAliveEnforcer.kt")
        self.assertIn("try { block() }", source)
        self.assertIn("if (!queued) {\n                repairInProgress.set(false)", source)
        self.assertIn('enforce(context, "repair_schedule_failed")', source)

    def test_unknown_health_never_rebinds(self):
        source = self.read("AccessibilityKeepAliveEnforcer.kt")
        unknown = source.split("AccessibilityConnectionStatus.UNKNOWN ->", 1)[1].split("private fun", 1)[0]
        self.assertNotIn("beginServiceRepair", unknown)
        self.assertNotIn("scheduleHealthCheck", unknown)

    def test_exact_target_and_only_system_binding_permission(self):
        source = self.read("AccessibilityKeepAliveEnforcer.kt")
        for value in ("it.packageName == APP_PACKAGE", "it.name == SERVICE_CLASS", "it.enabled", "it.applicationInfo?.enabled == true", "Manifest.permission.BIND_ACCESSIBILITY_SERVICE"):
            self.assertIn(value, source)
        self.assertIn('SERVICE_CLASS = "app.daiyu.android.gui.GuiAccessibilityService"', self.read("AccessibilityProtectionProtocol.kt"))
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        grants = {p.get(A + "name") for p in manifest.findall("uses-permission")}
        self.assertNotIn("android.permission.WRITE_SECURE_SETTINGS", grants)
        self.assertNotIn("android.permission.MANAGE_ACCESSIBILITY", grants)

if __name__ == "__main__":
    unittest.main()
