"""Permission declarations and settings wiring; not Android grant persistence tests."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
SETTINGS = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings"


class PermissionCheckContractTest(unittest.TestCase):
    def test_checked_permissions_are_declared_once_without_legacy_caps(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        permissions = manifest.findall("uses-permission")
        for name in ("PACKAGE_USAGE_STATS", "QUERY_ALL_PACKAGES", "POST_NOTIFICATIONS"):
            with self.subTest(permission=name):
                nodes = [n for n in permissions if n.get(ANDROID + "name") == "android.permission." + name]
                self.assertEqual(1, len(nodes))
                self.assertIsNone(nodes[0].get(ANDROID + "maxSdkVersion"))

    def test_usage_opens_current_package_with_legacy_and_missing_handler_fallbacks(self):
        access = (SETTINGS / "PermissionCheckAccess.kt").read_text()
        self.assertIn("Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri)", access)
        self.assertIn("Build.VERSION.SDK_INT >= 29", access)
        self.assertIn("catch (error: ActivityNotFoundException)", access)
        self.assertIn("item != PermissionCheckItem.USAGE || intent.data == null", access)
        self.assertIn("context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))", access)

    def test_app_list_status_checks_actual_normal_permission(self):
        access = (SETTINGS / "PermissionCheckAccess.kt").read_text()
        self.assertIn('Build.VERSION.SDK_INT < 30 || permission("android.permission.QUERY_ALL_PACKAGES")', access)
        platform = (ROOT / "app/src/main/kotlin/app/daiyu/android/platform/PlatformSettingsAccess.kt").read_text()
        self.assertNotIn("不申请全包可见性", platform)

    def test_posting_and_listener_access_remain_separate(self):
        access = (SETTINGS / "PermissionCheckAccess.kt").read_text()
        self.assertIn("PermissionCheckItem.NOTIFICATION_ACCESS -> CrossAppNotifications.systemGranted(context)", access)
        self.assertIn("PermissionCheckItem.TASK_NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()", access)
        self.assertIn("Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)", access)
        self.assertIn(".putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)", access)
        for mutation in ("revokeRuntimePermission", "setNotificationsEnabled", "deleteNotificationChannel"):
            self.assertNotIn(mutation, access)

    def test_resume_rereads_system_without_persisting_an_authorization(self):
        screen = (SETTINGS / "PermissionCheckScreen.kt").read_text()
        self.assertIn("event == Lifecycle.Event.ON_RESUME) revision++", screen)
        self.assertIn("system.granted(it)", screen)
        self.assertNotIn("getSharedPreferences", screen)
        self.assertNotIn("putBoolean", screen)

    def test_permission_whitelist_tracks_reads_and_explicit_execution_service(self):
        boundary = (ROOT / "scripts/verify_boundaries.py").read_text()
        for name in ("PACKAGE_USAGE_STATS", "QUERY_ALL_PACKAGES", "POST_NOTIFICATIONS"):
            self.assertIn('"' + name + '": None', boundary)
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        names = {n.get(ANDROID + "name") for n in manifest.findall("uses-permission")}
        self.assertTrue(names.isdisjoint({"android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.ACCESS_BACKGROUND_LOCATION"}))


if __name__ == "__main__":
    unittest.main()
