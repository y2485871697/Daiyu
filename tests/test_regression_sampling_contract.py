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

    def test_tool_card_native_framebuffer_keeps_every_intermediate_in_phone_configuration(self):
        import re
        text = (TEST / 'ui/screens/chat/conversation/ToolCardReadabilityTest.kt').read_text()
        setup = text.split('override fun setUp()', 1)[1].split('override fun cleanupAfterLegacy()', 1)[0]
        self.assertIn('verifyDisplay("320x640", "160")', setup)
        steps = re.findall(r'changeDisplay\("wm (size|density) ([0-9x]+)", "([0-9x]+)", "([0-9]+)"\)', setup)
        self.assertEqual(steps, [
            ('size', '560x1120', '560x1120', '160'),
            ('density', '280', '560x1120', '280'),
            ('size', '980x1960', '980x1960', '280'),
            ('density', '490', '980x1960', '490'),
            ('size', '1216x2432', '1216x2432', '490'),
            ('density', '525', '1216x2432', '525'),
        ])
        states = [('320x640', '160')]
        for kind, value, expected_size, expected_density in steps:
            size, density = states[-1]
            if kind == 'size':
                size = value
            else:
                density = value
            self.assertEqual((size, density), (expected_size, expected_density))
            states.append((size, density))
        for size, density in states + list(reversed(states)):
            width, height = map(int, size.split('x'))
            self.assertGreaterEqual(width * 160 / int(density), 320)
            self.assertLessEqual(width * 160 / int(density), 560)
            self.assertEqual(height, width * 2)
        self.assertLess(setup.index('wm density 525'), setup.index('super.setUp()'))
        self.assertIn('private val phoneDensity = 525f / 160f', text)
        self.assertIn('LocalDensity provides Density(phoneDensity, fontScale)', text)
        self.assertIn('activity.window.decorView.width == 1216', setup)
        self.assertIn('activity.resources.displayMetrics.densityDpi == 525', setup)
        self.assertIn('hasVisualOverflow', text)

    def test_tool_card_rolls_back_verified_steps_after_setup_failure_and_stops_on_restore_failure(self):
        text = (TEST / 'ui/screens/chat/conversation/ToolCardReadabilityTest.kt').read_text()
        change = text.split('private fun changeDisplay(', 1)[1].split('override fun setUp()', 1)[0]
        self.assertLess(change.index('displayRestore.addFirst('), change.index('displayState(command)'))
        self.assertIn('DisplayRestoreStep(undo, effectiveSize(sizes), effectiveDensity(densities))', change)
        self.assertIn('verifyDisplay(size, density)', change)
        self.assertIn('Override size:', change)
        self.assertIn('Override density:', change)
        self.assertEqual(change.count('?: "reset"'), 2)
        verify = text.split('private fun verifyDisplay(', 1)[1].split('private fun changeDisplay(', 1)[0]
        self.assertIn('effectiveSize(actualSize) == size && effectiveDensity(actualDensity) == density', verify)
        cleanup = text.split('override fun cleanupAfterLegacy()', 1)[1].split('private val phoneDensity', 1)[0]
        self.assertIn('try { super.cleanupAfterLegacy() }', cleanup)
        self.assertIn('while (displayRestore.isNotEmpty())', cleanup)
        self.assertIn('val step = displayRestore.first()', cleanup)
        self.assertLess(cleanup.index('displayState(step.command)'), cleanup.index('verifyDisplay(step.size, step.density)'))
        self.assertLess(cleanup.index('verifyDisplay(step.size, step.density)'), cleanup.index('displayRestore.removeFirst()'))
        self.assertNotIn('catch', cleanup)
        self.assertIn('} finally {', cleanup)
        self.assertIn('instrumentation.waitForIdleSync()', cleanup)
        base = (TEST / 'ComposeActivityTestCase.kt').read_text()
        lifecycle = base.split('override fun runBare()', 1)[1].split('protected open fun cleanupAfterLegacy()', 1)[0]
        self.assertIn('try { runLegacy() }', lifecycle)
        self.assertIn('finally {', lifecycle)
        self.assertIn('try { cleanupAfterLegacy() }', lifecycle)
