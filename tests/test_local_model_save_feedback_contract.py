"""Targeted local-model save guards; pixel assertions live in Android instrumentation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/app/daiyu/android"


class LocalModelSaveFeedbackContractTest(unittest.TestCase):
    def test_only_local_edit_success_is_silent(self):
        controller = (MAIN / "ChatController.kt").read_text()
        edit = controller.split("private fun editConfiguredCatalogModel(", 1)[1].split("internal fun exportUserBackup", 1)[0]
        self.assertIn("CatalogLocalEditResult.APPLIED -> null", edit)
        self.assertNotIn("本地模型已更新", edit)
        for expected in ('failureNotice = "本地模型修改未保存"', 'CatalogLocalEditResult.STALE -> "目录已变化，请重新选择模型"',
                         'CatalogLocalEditResult.CANCELLED -> "本地模型修改已取消"', 'CatalogLocalEditResult.FAILED -> throw',
                         'completed(if (success) result else', 'result = change()'):
            self.assertIn(expected, edit)

    def test_busy_visual_override_does_not_enable_controls(self):
        dialog = (MAIN / "ui/screens/settings/CatalogModelEditorDialog.kt").read_text()
        for expected in ("saving: Boolean = false", "val interactive = enabled && !saving", "if (interactive) onDismiss()",
                         "if (interactive && canSave && formValid) onSave(editedValue)",
                         "confirmEnabled = interactive && canSave && formValid, cancelEnabled = interactive", "preserveEnabledColors = saving"):
            self.assertIn(expected, dialog)
        actions = (MAIN / "ui/components/MiuixDialogActions.kt").read_text()
        self.assertIn("preserveEnabledColors: Boolean = false", actions)
        for name in ("cancel", "confirm"):
            for expected in (f"if ({name}Enabled)", f"enabled = {name}Enabled", f"disabledColor = {name}Colors.color",
                             f"disabledTextColor = {name}Colors.textColor"):
                self.assertIn(expected, actions)

    def test_save_flag_is_owned_by_the_pending_request_and_released_on_pause(self):
        screen = (MAIN / "ui/screens/settings/LocalCatalogModelsScreen.kt").read_text()
        save = screen.split("fun saveForm(", 1)[1].split("DisposableEffect(", 1)[0]
        self.assertIn("request != null", save)
        self.assertIn("request = token; savingEditor = true", save)
        self.assertIn("request === token && !token.get()", save)
        self.assertIn("request = null; savingEditor = false", save)
        self.assertIn("editing = null; adding = null", save)
        self.assertEqual(2, screen.count("saving = savingEditor"))
        pause = screen.split("fun pause()", 1)[1].split("fun reload()", 1)[0]
        self.assertIn("request?.set(true)", pause)
        self.assertIn("savingEditor = false", pause)

    def test_android_regressions_hold_pending_save_compare_pixels_and_check_real_commit(self):
        base = ROOT / "app/src/androidTest/kotlin/app/daiyu/android"
        ui = (base / "ui/screens/settings/CatalogModelEditorLayoutTest.kt").read_text()
        for expected in ("testPendingSaveKeepsActionColorsAndBlocksInputUntilRetryOrClose", "holdSave = true", "before.getValue(tag)",
                         "assertIsNotEnabled()", "assertFalse(action())", "saving = false; errorText", "listOf(false, true)"):
            self.assertIn(expected, ui)
        controller = (base / "ControllerProviderSaveNoticeTest.kt").read_text()
        for expected in ("testLocalModelEditCommitsSilentlyAndStaleEditKeepsItsReason", "value.applyTo(original)",
                         "CatalogLocalEditResult.STALE", "assertNull(fixture.controller.consumeDataNotice())", "testOtherDataTaskStillPublishesItsSuccessNotice"):
            self.assertIn(expected, controller)


if __name__ == "__main__":
    unittest.main()
