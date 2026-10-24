"""Exact local repair boundaries; these guards are not Android runtime acceptance."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
UI = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class R77CandidateRepairsContract(unittest.TestCase):
    def test_dictation_sets_real_ui_preferences_and_waits_for_actual_events(self):
        source = (TEST / 'DictationActivityTest.kt').read_text()
        for token in ('instrumentation.targetContext', 'getSharedPreferences("voice-settings", 0)',
                      'putBoolean("input", true)', 'ChatMenuAction.VOICE_INPUT.name',
                      'settings.remove("input")', 'entry.remove("mode")',
                      'listeningPublished.countDown()', 'listeningPublished.await(5, TimeUnit.SECONDS)',
                      'stoppedDelivered.await(5, TimeUnit.SECONDS)', 'events.captureStopped()'):
            self.assertIn(token, source)
        callback = source.split('override fun captureStopped()', 1)[1].split('},', 1)[0]
        self.assertLess(callback.index('events.captureStopped()'), callback.index('stoppedDelivered.countDown()'))
        self.assertIn('assertEquals("capture stopped before final", 0L, released.count)', source)

    def test_header_uses_available_column_width_without_changing_typography_or_overflow(self):
        source = (UI / 'ChatSelectionSheet.kt').read_text()
        self.assertIn('Text(title, modifier = Modifier.fillMaxWidth()', source)
        self.assertIn('modifier = Modifier.fillMaxWidth().padding(top = 4.dp)', source)
        self.assertIn('fontSize = titleFontSize, lineHeight = 26.sp', source)
        self.assertIn('fontSize = subtitleFontSize, lineHeight = 18.sp', source)
        self.assertIn('it.hasVisualOverflow', (TEST / 'ChatAttachmentSizingActivityTest.kt').read_text())

    def test_menu_reads_live_snapshot_from_each_content_draw_without_changing_placement(self):
        source = (UI / 'ChatMenuLayer.kt').read_text()
        test = (TEST / 'ChatMenuPlacementActivityTest.kt').read_text()
        self.assertIn('SideEffect { diagnostic?.invoke(probe::snapshot) }', source)
        self.assertIn('val wantedY = if (fitsBelow) anchor.bottom.toInt() + offsetY', source)
        self.assertIn('val y = wantedY.coerceIn(margin,', source)
        self.assertIn('surface.place(x, y)', source)
        self.assertIn('diagnostic.get()?.invoke()', test)
        self.assertIn('contentClipped=${it.boundsInRoot()}', test)
        self.assertIn('stable.top >= anchor.bottom', test)
        self.assertIn('stable.bottom <= anchor.top', test)
        self.assertNotIn('roundToInt()', source)
        self.assertLess(test.index('awaitNodeWindowFocus(hostNode)'), test.index('dismissIme()'))
        self.assertLess(test.index('dismissIme()'), test.index('awaitImeFrame(hostNode, visible = false)'))
        self.assertLess(test.index('awaitImeFrame(hostNode, visible = false)'), test.index('repeat(2)'))

    def test_spacing_keeps_original_wait_and_geometry_oracle_with_last_sample_diagnostics(self):
        source = (TEST / 'ui/screens/chat/conversation/ChatMessageSpacingTest.kt').read_text()
        self.assertIn('ui.waitUntil(5_000)', source)
        self.assertIn('displayed && gap in 8f..28f', source)
        self.assertIn('tail.size.height > viewport.height', source)
        self.assertIn('Compact tail after composer growth: $lastGeometry', source)
        self.assertNotIn('autoAdvance = false', source)
        production = (UI / 'ChatMessageList.kt').read_text()
        self.assertNotIn('LaunchedEffect(listState, occlusionPx)', production)
        show = source.split('private fun show(', 1)[1].split('private fun assertCompactTail(', 1)[0]
        self.assertLess(show.index('awaitNodeWindowFocus(host)'), show.index('dismissIme()'))
        self.assertLess(show.index('dismissIme()'), show.index('awaitImeFrame(host, visible = false)'))
        self.assertLess(show.index('awaitImeFrame(host, visible = false)'), show.index('assertCompactTail("初始末尾")'))
        oracle = source.split('private fun assertCompactTail(', 1)[1].split('fun testCompleted', 1)[0]
        self.assertIn('awaitImeFrame(ui.onNodeWithTag("spacing-host"), visible = false)', oracle)
        self.assertNotIn('dismissIme()', oracle)
        for token in ('"多行草稿\\n".repeat(5)', 'attachmentHeight = 48.dp',
                      'surface().height > before.height + 24f * density',
                      'surface().height > multiline.height + 40f * density'):
            self.assertIn(token, source)

    def test_system_education_dismissal_is_exact_and_single_shot(self):
        navigation = (TEST / 'AssistantNavigationActivityTest.kt').read_text()
        back = navigation.split('private fun back(system: Boolean) {', 1)[1].split('fun testHeader', 1)[0]
        for earlier, later in (('awaitNodeWindowFocus(current)', 'dismissIme()'),
                               ('dismissIme()', 'awaitImeFrame(current, visible = false)'),
                               ('awaitImeFrame(current, visible = false)', 'if (system)')):
            self.assertLess(back.index(earlier), back.index(later))
        self.assertEqual(1, back.count('sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)'))
        source = (TEST / 'DesktopEducationTooltip.kt').read_text()
        for token in ('Desktop Windowing Education Tooltip of Task=', 'com.android.systemui',
                      'Tap handle to access menu and display options', 'windows.size == 1',
                      'getBoundsInScreen', 'node.positionOnScreen', 'center.x < bounds.left - 1f',
                      'ui.waitUntil(5_000) { educationWindows().isEmpty() }',
                      'finally', 'flags = originalFlags'):
            self.assertIn(token, source)
        self.assertIn('automation.injectInputEvent(event, true)', source)
        self.assertNotIn('if (windows.isEmpty()) return', source)
        discovery = source.split('var observed = false', 1)[1].split('val window = checkNotNull(ready)', 1)[0]
        for token in ('ui.waitUntil(5_000)', 'val windows = educationWindows()', 'observed = true',
                      'if (root == null) false', 'if (verified) ready = candidate',
                      'if (observed) throw AssertionError', 'No tooltip observed within discovery deadline'):
            self.assertIn(token, discovery)
        self.assertLess(source.index('val window = checkNotNull(ready)'), source.index('blank.assertIsDisplayed()'))
        self.assertEqual(1, source.count('inject(android.view.MotionEvent.ACTION_DOWN)'))
        self.assertEqual(1, source.count('inject(android.view.MotionEvent.ACTION_UP)'))
        self.assertNotIn('performTouchInput', source)
        for token in ('executeShellCommand', 'performAction', 'captureToImage', 'settings put', 'Thread.sleep'):
            self.assertNotIn(token, source)
        for name in ('ToolOperationCardTest.kt', 'ToolCardReadabilityTest.kt'):
            test = (TEST / 'ui/screens/chat/conversation' / name).read_text()
            self.assertIn('dismissDesktopEducationTooltip(instrumentation, ui,', test)
            self.assertIn('awaitPointerEvent().changes.forEach { it.consume() }', test)
        operation = (TEST / 'ui/screens/chat/conversation/ToolOperationCardTest.kt').read_text()
        self.assertIn('captureScreenNode(frame)', operation)
        self.assertIn('Color(if (dark) 0x13BEA8D7 else 0x108A779A).compositeOver(backdrop)', operation)

    def test_focus_and_pet_keep_real_input_and_visible_geometry(self):
        assistant = (TEST / 'AssistantNavigationActivityTest.kt').read_text()
        self.assertLess(assistant.index('awaitNodeWindowFocus(current)'),
                        assistant.index('instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)'))
        pet = (TEST / 'pet/PetCupboardDialogTest.kt').read_text()
        for token in ('Modifier.fillMaxSize()', 'FrameLayout.LayoutParams.WRAP_CONTENT',
                      'sprite.getGlobalVisibleRect(visible)', 'sprite.height * 90L', '.perform(click())'):
            self.assertIn(token, pet)


if __name__ == '__main__':
    unittest.main()
