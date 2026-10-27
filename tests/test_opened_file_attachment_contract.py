"""Resolver and production wiring contracts; not a substitute for Android execution."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
A = "{http://schemas.android.com/apk/res/android}"
class OpenedFileAttachmentContract(unittest.TestCase):
    def test_open_with_and_shares_are_on_one_attachment_receiver(self):
        tree = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        activity = next(a for a in tree.findall("application/activity") if a.get(A+"name") == ".attachments.OpenAttachmentActivity")
        filters = activity.findall("intent-filter")
        self.assertEqual(3, len(filters))
        view, untyped, share = filters
        self.assertEqual("content", untyped.find("data").get(A+"scheme"))
        self.assertIsNone(untyped.find("data").get(A+"mimeType"))
        self.assertEqual(["android.intent.action.VIEW"], [v.get(A+"name") for v in view.findall("action")])
        self.assertEqual("content", view.find("data").get(A+"scheme"))
        self.assertEqual("*/*", view.find("data").get(A+"mimeType"))
        self.assertEqual({"android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"}, {v.get(A+"name") for v in share.findall("action")})
        self.assertIsNone(share.find("data").get(A+"scheme"))
        self.assertFalse(any(c.get(A+"name") == "android.intent.category.BROWSABLE" for c in activity.iter("category")))
    def test_owned_provider_is_not_exported_or_grantable(self):
        tree = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        provider = next(p for p in tree.findall("application/provider") if p.get(A+"name") == ".attachments.OpenedFileProvider")
        self.assertEqual("false", provider.get(A+"exported"))
        self.assertNotEqual("true", provider.get(A+"grantUriPermissions"))
    def test_no_launcher_alias_receives_documents(self):
        tree = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        for alias in tree.findall("application/activity-alias"):
            self.assertFalse(any(v.get(A+"name") == "android.intent.action.VIEW" for v in alias.iter("action")))
    def test_main_consumes_after_ready_and_never_sends_from_handoff(self):
        main = (ROOT / "app/src/main/kotlin/app/daiyu/android/MainActivity.kt").read_text()
        body = main.split("private fun consumeOpenedAttachments()",1)[1].split("private fun currentAssistantLabel",1)[0]
        for token in ["generationResumed()", "controller.ready", "selectionReady", "batch.conversation != selected?.id?.value", "updateDraft(next)"]:
            self.assertIn(token, body)
        for token in ["controller.send", "sendImageDraft(", "importUserBackup"]: self.assertNotIn(token, body)
        self.assertGreaterEqual(main.count("consumeOpenedAttachments()"),5)
    def test_temporary_permission_is_copied_not_faked_as_persisted(self):
        receiver = (ROOT / "app/src/main/kotlin/app/daiyu/android/attachments/OpenAttachmentActivity.kt").read_text()
        self.assertIn("store.import(", receiver)
        self.assertIn("linkedSetOf<Uri>()", receiver)
        self.assertNotIn("takePersistableUriPermission", receiver)
        self.assertNotIn("FLAG_GRANT_WRITE_URI_PERMISSION",receiver)
        self.assertIn("if (state != null) { finish(); return }", receiver)
    def test_file_tools_preserve_external_grant_check_and_only_allow_exact_owned_document(self):
        source = (ROOT / "app/src/main/kotlin/app/daiyu/android/platform/AndroidPlatformReads.kt").read_text()
        self.assertIn("!OpenedFileStore(app).owns(uri) &&",source)
        self.assertIn("app.checkUriPermission(uri",source)
        self.assertIn("uri_read_grant_required",source)
if __name__ == "__main__": unittest.main()
