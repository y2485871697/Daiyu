"""Sidebar capability wiring; Android interaction regressions are compiled, not run here."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class ImageSidebarVisibilityContract(unittest.TestCase):
    def test_sidebar_retains_thumbnails_only_while_chat_is_retained(self):
        source = (SRC / "MainActivity.kt").read_text()
        opening = source.split("private fun openSidebar()", 1)[1].split("private fun dismissSidebar()", 1)[0]
        self.assertIn('keepThumbnails = retainedPage == "chat"', opening)
        self.assertIn("imageHost.setVisible(false, selected?.id?.value,", opening)
        self.assertIn("speechHost.setVisible(false)", opening)

    def test_route_visibility_does_not_retain_thumbnails_on_other_pages(self):
        source = (SRC / "MainActivity.kt").read_text()
        self.assertIn('imageHost.setVisible(value == "chat" && !sidebarVisible, selected?.id?.value,\n'
                      '                keepThumbnails = value == "chat")', source)
        self.assertNotIn("keepThumbnails = true", source)

    def test_interaction_end_does_not_clear_or_renew_the_thumbnail_loader(self):
        source = (SRC / "ImageChatHost.kt").read_text()
        transition = source.split("fun setVisible(", 1)[1].split("private fun renewPreviews", 1)[0]
        self.assertIn("val showThumbnails = value || keepThumbnails", transition)
        self.assertIn("thumbnailsVisible == showThumbnails", transition)
        interactive = transition.split("if (interactionEnded) {", 1)[1].split("        }", 1)[0]
        self.assertIn("cancelTransient(session)", interactive)
        self.assertIn("session.attempt?.cancel()", interactive)
        self.assertNotIn("lease.close()", interactive)
        self.assertNotIn("historyLoader =", interactive)
        self.assertIn("if (thumbnailsChanged && showThumbnails)", transition)
        self.assertIn("if (!showThumbnails) { lease.close(); historyLoader = null }", transition)

    def test_originals_and_thumbnails_have_independent_revocation(self):
        source = (SRC / "ImageChatHost.kt").read_text()
        media = source.split("private fun renewMediaPreviews(", 1)[1].split("fun syncConversation", 1)[0]
        self.assertIn("mediaPreviews?.close()", media)
        self.assertIn("if (visible && !closed && conversation != null) PreviewLease(controller, conversation) else null", media)
        self.assertNotIn("historyLoader =", media)
        close = source.split("    fun close() {", 1)[1].split("    private fun attach", 1)[0]
        self.assertIn("thumbnailsVisible = false", close)
        self.assertIn("lease.close(); mediaPreviews?.close()", close)
        self.assertIn("historyLoader = null; mediaPreviews = null", close)

    def test_owner_cancellation_guards_and_real_host_regressions_remain(self):
        loader = (SRC / "ui/screens/chat/conversation/MessageImageLoader.kt").read_text()
        host = (SRC / "ImageChatHost.kt").read_text()
        self.assertIn("image.expectedOwner != image.reference.owner", loader)
        self.assertIn("throw cancelled", loader)
        self.assertIn('bitmap.recycle(); throw CancellationException("Image page left")', host)
        regression = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ImageChatHostActivityTest.kt").read_text()
        for check in ("testSidebarKeepsHistoryImageAndLoaderAcrossRepeatedOpenAndDismiss",
                      "testRealPageExitRevokesThumbnailRetainedWhileSidebarWasOpen",
                      "assertSame(loader, host().historyLoader)", "assertFalse(media.isOpen())",
                      'node("drawer-dock-settings").performClick()', "assertFalse(pickerOpened)",
                      "loader.load(image.expectedOwner, image.reference, 128)"):
            self.assertIn(check, regression)


if __name__ == "__main__":
    unittest.main()
