import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class RegressionSamplingContract(unittest.TestCase):
    def test_video_reads_delegated_value_through_existing_getter(self):
        text = (TEST / 'ActivityTest.kt').read_text()
        self.assertIn('invokeHost(activity, "getVideoComposerControls") != null', text)
        self.assertNotIn('getDeclaredField("videoComposerControls")', text)
        self.assertIn('replacement still has exactly one binding', text)

    def test_context_top_uses_integer_pixel_layout(self):
        text = (TEST / 'ChatChromeActivityTest.kt').read_text()
        self.assertIn('assertEquals(expectedTopPx.toFloat(), context.top - surface.top, 0f)', text)
        self.assertIn('maxOf(modelHeight, contextHeight)', text)
        self.assertIn('assertEquals(10.sp, layout("37% 上下文").layoutInput.style.fontSize)', text)
        self.assertIn('assertEquals(.9f, placeholder.layoutInput.density.fontScale, .001f)', text)

    def test_real_inputs_keep_source_and_native_system_bar_protection(self):
        text = (TEST / 'ManagedInputActivityTest.kt').read_text()
        self.assertIn('event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN', text)
        self.assertIn('.systemBarsPadding()', text)
        self.assertIn('hidden.contentEquals(unfocused)', text)
        self.assertIn('Real long-press drag retains a text selection', text)

    def test_caret_selection_changes_before_collapse(self):
        text = (TEST / 'ChatComposerImeActivityTest.kt').read_text().split(
            'fun testHiddenImeStopsCaretDrawingEvenWhenDraftRemainsFocused', 1)[1]
        self.assertLess(text.index('setSelection(0, draft.length, relativeToOriginalText = true)'),
                        text.index('setSelection(draft.length, draft.length, relativeToOriginalText = true)'))

    def test_geometric_baseline_waits_for_dialog_ime_and_native_frames(self):
        text = (TEST / 'SubAgentResumeGeometryActivityTest.kt').read_text()
        show = text.split('private fun show(', 1)[1].split('private fun geometry()', 1)[0]
        self.assertIn('dismissIme(view.rootView)', show)
        self.assertIn('awaitImeFrame(sheet, visible = false)', show)
        self.assertIn('awaitStableWindowFrame(view)', show)
        self.assertIn('assertEquals("bottom remains fixed", expected.bottom, actual.bottom, 2f)', text)

    def test_physical_width_fixture_restores_display(self):
        text = (TEST / 'SubAgentCollaborationLayoutTest.kt').read_text()
        self.assertIn('if (name == "testProfileFormKeepsEqualFooterAndScrollsAtPhysicalWidthAndLargeFont")', text)
        self.assertIn('wm size 1216x2432', text)
        self.assertIn('override fun cleanupAfterLegacy()', text)
        self.assertIn('wm size $original', text)
        self.assertIn('assertHeightIsAtLeast(48.dp)', text)
        production = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/components/SubAgentProfileConfigDialog.kt').read_text()
        self.assertNotIn('minimumActionHeight', production)

    def test_pixel_capture_reuses_screen_helper(self):
        text = (TEST / 'ui/screens/chat/conversation/ToolOperationCardTest.kt').read_text()
        method = text.split('fun testCommandAndShortLogKeepLavenderFramesAndMonospace', 1)[1].split('\n    fun ', 1)[0]
        self.assertIn('captureScreenNode(frame).toPixelMap()', method)
        self.assertNotIn('frame.captureToImage()', method)
        self.assertIn('.025f', method)

    def test_browser_hint_uses_resource(self):
        text = (TEST / 'ChatMenuEntryActivityTest.kt').read_text()
        self.assertIn('activity.getString(R.string.om_browser_enter_url_hint)', text)
