"""Window wiring guards; actual composited-pixel regressions await the approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class PreviewDialogStatusBarContractTest(unittest.TestCase):
    def test_preview_does_not_force_status_text_or_navigation_bar_appearance(self):
        source = (SRC / "ui/components/PreviewDialog.kt").read_text()
        self.assertNotIn("isAppearanceLightStatusBars", source)
        self.assertNotIn("isAppearanceLightNavigationBars", source)
        self.assertNotIn("WindowCompat", source)
        self.assertNotIn("activity.window", source)

    def test_default_scrim_reuses_original_window_dimming_without_double_darkening(self):
        source = (SRC / "ui/components/PreviewDialog.kt").read_text()
        self.assertIn("val dimAmount = remember(window) { window?.attributes?.dimAmount ?: 0f }", source)
        self.assertLess(source.index("val dimAmount ="), source.index("window?.setDimAmount(0f)"))
        self.assertIn("scrimColor ?: Color.Black.copy(alpha = dimAmount)", source)
        self.assertIn(".previewDialogDimBackground(dialogDimStack,", source)
        self.assertIn("Color.Black.copy(alpha = dimAmount), motion)", source)
        self.assertNotIn("0x2620172d", source)
        self.assertNotIn("0x55000000", source)

    def test_workspace_and_selection_keep_the_shared_window_path(self):
        workspace = (SRC / "ui/components/ConversationWorkspaceSheet.kt").read_text()
        agent = (SRC / "ui/components/subagent/ConversationAgentStyle.kt").read_text()
        selection = (SRC / "ui/screens/chat/conversation/ChatSelectionSheet.kt").read_text()
        self.assertIn("ConversationAgentSheet(onDismiss", workspace)
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = true)", agent)
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = true,", selection)



MEDIA = SRC / "ui/media"

class PreviewDialogContract(unittest.TestCase):
    def test_status_bar_effect_only_uses_preview_dialog_window(self):
        source = (MEDIA / 'ChatMediaPreviewSystemBars.kt').read_text()
        self.assertIn('view.parent as? DialogWindowProvider', source)
        self.assertIn('BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE', source)
        self.assertIn('onDispose { lease?.close() }', source)
        self.assertNotIn('Activity', source.split('internal class', 1)[1])
        self.assertIn('ChatMediaPreviewHideStatusBars()', (MEDIA / 'ChatMediaPreviewHost.kt').read_text())

    def test_download_uses_original_stream_and_video_awaits_release(self):
        storage = (MEDIA / 'ChatMediaPreviewStorage.kt').read_text()
        self.assertIn('item.openOriginal()', storage)
        self.assertIn('chatMediaPreviewCopy(original.stream', storage)
        self.assertNotIn('Bitmap.compress', storage)
        video = (MEDIA / 'ChatMediaPreviewVideo.kt').read_text()
        self.assertIn('awaitCancellation()', video)
        self.assertIn('view.stopPlayback()', video)
        self.assertIn('Lifecycle.State.RESUMED', video)

    def test_committed_video_invalidates_projection_even_when_editor_no_longer_matches(self):
        source = (SRC / 'MainActivity.kt').read_text()
        receipt = source.split('private fun settleRetainedVideoReceipt()', 1)[1].split('// A mount', 1)[0]
        self.assertLess(receipt.index('messageMediaCommitRevision++'), receipt.index('if (accepted && conversation != null)'))
        self.assertIn('editorGeneration, messageMediaCommitRevision)', source)

    def test_new_media_sources_have_no_old_brand_or_package(self):
        for path in MEDIA.glob('*.kt'):
            self.assertNotRegex(path.read_text(), r'(?i)\beta\b|io\.github\.mangi|eta[-_/]')


if __name__ == "__main__":
    unittest.main()
