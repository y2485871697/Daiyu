"""Voice refresh smoke stays targeted without reducing other validation scopes."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("voice_refresh_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class VoiceRefreshSmokeScopeTest(unittest.TestCase):
    def test_exact_voice_pair_selects_voice_contracts_in_either_order(self):
        for classes in (recorder.VOICE_REFRESH_PLATFORM_CLASSES, tuple(reversed(recorder.VOICE_REFRESH_PLATFORM_CLASSES))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
            self.assertEqual("voice-refresh-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.VOICE_REFRESH_MODULES, "-v"], command)
        self.assertIn("test_voice_recognition_layout_contract", recorder.VOICE_REFRESH_MODULES)
        self.assertIn("test_voice_settings_parity_contract", recorder.VOICE_REFRESH_MODULES)
        self.assertIn("test_voice_refresh_smoke_scope", recorder.VOICE_REFRESH_MODULES)
        self.assertNotIn("test_option_selection_sheet_contract", recorder.VOICE_REFRESH_MODULES)

    def test_focused_refresh_class_uses_the_same_required_contracts(self):
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke",
            SMOKE_TEST_CLASSES="app.daiyu.android.VoiceRecognitionRefreshActivityTest"))
        self.assertEqual("voice-refresh-targeted", scope)
        self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.VOICE_REFRESH_MODULES, "-v"], command)

    def test_partial_extended_duplicate_or_other_modes_keep_full_scope(self):
        pair = ",".join(recorder.VOICE_REFRESH_PLATFORM_CLASSES)
        for classes in (recorder.VOICE_REFRESH_PLATFORM_CLASSES[0], pair + ",app.daiyu.android.OtherTest",
                        pair + "," + recorder.VOICE_REFRESH_PLATFORM_CLASSES[0]):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))[1])
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=pair))[1])
