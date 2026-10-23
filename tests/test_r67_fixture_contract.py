"""Source wiring guards for the R66 failures; not device execution evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class R67FixtureContract(unittest.TestCase):
    def test_switch_fixtures_avoid_system_bars_without_relaxing_track_or_busy_oracles(self):
        size = (TEST / 'ui/screens/settings/SettingsSwitchSizeTest.kt').read_text()
        self.assertEqual(2, size.count('Box(Modifier.fillMaxSize().safeDrawingPadding())'))
        for token in ('36.4f * pixelsPerDp', '22.4f * pixelsPerDp', '48f * pixelsPerDp',
                      'assertEquals(1, calls)', 'assertEquals(2, calls)'):
            self.assertIn(token, size)
        busy = (TEST / 'ui/screens/settings/SettingsSwitchBusyAppearanceTest.kt').read_text()
        self.assertIn('expectedChange = true) > 0', busy)
        self.assertIn('assertEquals(0, differentPixels(disabled, captureScreenNode(pending)', busy)
        self.assertIn('if (expectedChange) android.util.Log.i', busy)
        self.assertIn('else android.util.Log.e', busy)
        self.assertIn('> .01f', busy)
        for token in ('closestDistance', 'closest=$closest', 'DaiyuSwitchSize', 'switch-size-$diagnostic.png',
                      'distance < .015f', '2f)', 'interactive-dark-$dark-scale-$scale-$direction',
                      'row-dark-$dark-scale-$scale'):
            self.assertIn(token, size)


    def test_collaboration_footer_uses_its_own_exact_contract_and_real_edge_touches(self):
        source = (TEST / 'CollaborationEmptyStateTest.kt').read_text()
        self.assertNotIn('AssistantPreviewPalette', source)
        self.assertIn('import androidx.compose.ui.graphics.Color', source)
        self.assertIn('import androidx.compose.ui.geometry.Offset', source)
        for token in ('assertHeightIsEqualTo(48.dp)', '0xFFC6B7D5 else 0xFF756980',
                      '0xFF211735 else 0xFFFFFFFF', 'click(Offset(centerX, 2f * density))',
                      'click(Offset(centerX, height - 2f * density))',
                      'assertEquals(before, prefs.all)', 'expectedPrimary.blue, background.blue, .01f'):
            self.assertIn(token, source)

    def test_provider_remeasures_original_input_in_actual_box_and_rejects_undersizing(self):
        source = (TEST / 'ProviderSettingsLayoutActivityTest.kt').read_text()
        helper = source.split('private fun measuredLabelLayout', 1)[1].split('fun testConnection', 1)[0]
        for token in ('input.fontFamilyResolver', 'input.density', 'input.layoutDirection',
                      'text = input.text', 'style = input.style', 'overflow = input.overflow',
                      'softWrap = input.softWrap', 'maxLines = input.maxLines',
                      'placeholders = input.placeholders',
                      'Constraints.fixed(result.size.width, result.size.height)'):
            self.assertIn(token, helper)
        self.assertIn('assertEquals(semanticsLayout.size, layout.size)', source)
        self.assertIn('semanticsLayout.copy(size = IntSize(1, semanticsLayout.size.height))).hasVisualOverflow', source)
        self.assertIn('layout.hasVisualOverflow,', source)
        self.assertIn('semanticsParagraph=${semanticsLayout.multiParagraph.width}', source)
        component = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings/ProviderSettingsComponents.kt').read_text()
        self.assertIn('with(LocalDensity.current) { 22.sp.toDp() } + 24.dp', component)

        for token in ('requestedFontScale=$fontScale', 'layoutDensity=${layout.layoutInput.density}',
                      'heightDeltaPx=${bounds.height - 48f * density}', 'DaiyuProviderLayout',
                      'bounds.height > 48f * density'):
            self.assertIn(token, source)


    def test_settings_popup_requires_new_window_nonzero_geometry_and_hidden_ime(self):
        source = (TEST / 'BrowserVisualLayoutTest.kt').read_text()
        method = source.split('fun testTaskBrowserKeepsOriginalPopupWithoutExtraDescriptionsOrTaskButtons()', 1)[1].split('fun testBusyTabKeeps', 1)[0]
        focus = method.index('awaitNodeWindowFocus(userAgent)')
        hide = method.index('dismissIme()', focus)
        frame = method.index('awaitImeFrame(userAgent, visible = false)', hide)
        stable = method.index('awaitBrowserWindowStable(userAgent, requireImeHidden = true)', frame)
        self.assertLess(stable, method.index('userAgent.assertIsDisplayed()'))
        helper = source.split('private fun awaitBrowserWindowStable', 1)[1].split('private class StateVisit', 1)[0]
        for token in ('view.windowToken != token', 'bounds.width <= 0f', 'bounds.height <= 0f',
                      '!insets.isVisible(ime)', 'insets.getInsets(ime).bottom == 0', '!imeReady'):
            self.assertIn(token, helper)
        for token in ('performScrollTo', 'Thread.sleep', 'SystemClock.sleep'):
            self.assertNotIn(token, method)

    def test_preview_keeps_hardware_drawing_across_route_change_and_checks_source_color(self):
        source = (TEST / 'ui/navigation/PageBackGesturesActivityTest.kt').read_text()
        method = source.split('fun testPreviousPagePreviewCancelAndCommittedDeparture()', 1)[1].split('@OptIn', 1)[0]
        enabled = method.index('setDrawingEnabled(true)')
        self.assertLess(enabled, method.index('mount(capturePreviousPage = true)'))
        self.assertLess(method.index('finally'), method.index('setDrawingEnabled(false)'))
        production = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/navigation/PageBackGestures.kt').read_text()
        for token in ('if (app.daiyu.android.BuildConfig.DEBUG)', 'DaiyuBackRecord',
                      'current=${frame.key == layers.currentKey} explicit=$insideExplicitRecord',
                      'insideExplicitRecord = false', 'this@drawWithContent.drawContent()'):
            self.assertIn(token, production)

        for token in ('logPreviewPixels("source", sourceImage)', 'logPreviewPixels("target", previewImage)',
                      'page-back-$phase.png', 'DaiyuBackPreview'):
            self.assertIn(token, source)

        for token in ('source previous page must be red', 'source.red > 0.9f && source.blue < 0.1f',
                      'recorded previous page must be red', 'backdrop.red > 0.9f && backdrop.blue < 0.1f',
                      'dispatchOnBackCancelled()', 'page-back-departure'):
            self.assertIn(token, source)


if __name__ == '__main__':
    unittest.main()
