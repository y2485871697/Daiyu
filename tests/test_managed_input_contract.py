"""检查受管输入控件及窗口焦点的源码连接。"""
from pathlib import Path
import json
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
SOURCE = MAIN / 'ui/components/ManagedInput.kt'


class ManagedInputContractTest(unittest.TestCase):
    def test_all_censused_ordinary_fields_are_registered_and_cursor_aware(self):
        inventory = json.loads((ROOT / 'tests/fixtures/managed_input_fields.json').read_text())
        self.assertGreater(len(inventory), 25)
        self.assertEqual(52, sum(row['calls'] for row in inventory))
        for row in inventory:
            text = (ROOT / row['path']).read_text()
            with self.subTest(path=row['path']):
                if row['path'].endswith('/assistants/AssistantPickerDialogs.kt'):
                    self.assertRegex(text, r'\bAssistantSearchField\s*\(\s*searchQuery\s*,\s*\{\s*searchQuery\s*=\s*it\s*\}')
                    text = (MAIN / 'ui/screens/assistants/AssistantSearchField.kt').read_text()
                    self.assertRegex(text, r'\bBasicTextField\s*\(')
                if row['path'].endswith('/conversation/ChatModelPicker.kt'):
                    self.assertIn('GroupedPickerSearch(query, { query = it }, "搜索模型 / 供应商", "搜索模型或供应商",', text)
                    self.assertIn('Modifier.testTag("chat-model-search")', text)
                    text = (MAIN / 'ui/components/GroupedPickerComponents.kt').read_text()
                    self.assertRegex(text, r'\bBasicTextField\s*\(')
                self.assertGreaterEqual(text.count('.managedInput()'), row['calls'])
                self.assertGreaterEqual(text.count('imeCursorBrush(') + text.count('materialImeCursorColor('), row['calls'])

    def test_browser_default_permission_removes_only_permission_editor_fields(self):
        parent = (MAIN / 'ui/BrowserApprovalHost.kt').read_text()
        self.assertNotIn('OutlinedTextField(', parent)
        child = (MAIN / 'ui/ChildBrowserApprovalHost.kt').read_text()
        self.assertEqual(1, child.count('OutlinedTextField('))
        self.assertIn('Modifier.managedInput().testTag("child-browser-prompt")', child)
        self.assertIn('materialImeCursorColor(MaterialTheme.colorScheme.primary)', child)

    def test_window_resolution_prefers_dialog_and_rejects_foreign_tokens(self):
        text = SOURCE.read_text().split('private fun View.inputWindow()', 1)[1].split('private fun View.visibleInputBounds()', 1)[0]
        self.assertLess(text.index('DialogWindowProvider'), text.index('context.inputActivity()'))
        self.assertIn('it.decorView.windowToken == windowToken', text)

    def test_callback_delegates_once_then_revalidates_current_focus(self):
        text = SOURCE.read_text()
        dispatch = text.split('override fun dispatchTouchEvent', 1)[1].split('    init {', 1)[0]
        self.assertEqual(1, dispatch.count('original.dispatchTouchEvent(event)'))
        self.assertLess(dispatch.index('original.dispatchTouchEvent(event)'), dispatch.index('dismiss(before)'))
        for token in ('before in entries', 'before.focused()', 'window.decorView.hasWindowFocus()',
                      'window.currentFocus === nativeFocus', 'return handled'):
            self.assertIn(token, dispatch)
        self.assertNotIn('.consume(', text)
        self.assertNotIn('setOnTouchListener', text)

    def test_completed_short_tap_only_and_protect_every_input_region(self):
        text = SOURCE.read_text()
        for token in ('entries.none { it.bounds()?.contains(event.rawX, event.rawY)',
                      'MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL', 'event.pointerCount != 1',
                      'hypot(event.rawX - x, event.rawY - y) > slop', 'ViewConfiguration.getLongPressTimeout()',
                      'MotionEvent.ACTION_UP && tap && outside', 'InputDismissal.insideInput = !outside',
                      'finally { InputDismissal.insideInput = previousInside }'):
            self.assertIn(token, text)

    def test_native_and_decor_bounds_use_screen_coordinates(self):
        text = SOURCE.read_text()
        for token in ('rootView.getLocationOnScreen(origin)', 'RectF(bounds).apply { offset(',
                      'field.visibleInputBounds()', 'window.decorView.visibleInputBounds()'):
            self.assertIn(token, text)

    def test_background_keeps_system_ime_state_and_explicit_dismiss_still_hides(self):
        text = SOURCE.read_text()
        self.assertNotIn('LifecycleEventObserver', text)
        self.assertNotIn('Lifecycle.Event.ON_STOP', text)
        self.assertNotIn('Lifecycle.Event.ON_PAUSE', text)
        self.assertNotIn('Lifecycle.Event.ON_RESUME', text)
        self.assertNotIn('.show(', text)
        self.assertNotIn('.setText(', text)
        self.assertIn('ViewCompat.getWindowInsetsController(view)', text)
        self.assertIn('hideSoftInputFromWindow(view.windowToken, 0)', text)

    def test_window_keeps_ime_state_without_lifecycle_or_visibility_replay(self):
        text = SOURCE.read_text().split('private class InputWindow(', 1)[1]
        init = text.split('    init {', 1)[1].split('    private fun dismiss(', 1)[0]
        self.assertIn('SOFT_INPUT_STATE_UNCHANGED', init)
        self.assertIn('mode and LayoutParams.SOFT_INPUT_MASK_STATE.inv()', init)
        self.assertNotIn('SOFT_INPUT_STATE_ALWAYS_HIDDEN', text)
        self.assertNotIn('SOFT_INPUT_STATE_VISIBLE', text)
        self.assertNotIn('OnPreDrawListener', text)
        self.assertIn('Build.VERSION.SDK_INT < 30', text)
        self.assertIn('OnGlobalLayoutListener { rememberLegacyImeState() }', text)
        policy = text.split('private fun rememberLegacyImeState()', 1)[1].split('private val original =', 1)[0]
        for token in ('!root.hasWindowFocus()', 'Lifecycle.State.RESUMED',
                      'insets.isVisible(WindowInsetsCompat.Type.ime())', 'entries.any { it.focused() }',
                      'visibleImeFrame ?: return', 'frame.bottom <= shown.bottom',
                      'focused && awaitingVisibleIme && !visible', 'SOFT_INPUT_STATE_ALWAYS_VISIBLE',
                      '!= managedImeState', 'ownsImeState = false', 'if (!ownsImeState) return',
                      'mode and LayoutParams.SOFT_INPUT_MASK_STATE.inv()',
                      'composeDialog && currentState == LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED',
                      'currentState != managedImeState && !dialogReset',
                      'state == managedImeState && currentState == state'):
            self.assertIn(token, policy)
        for token in ('showSoftInput', 'hideSoftInput', 'requestFocus', 'postOnAnimation', 'Thread.sleep'):
            self.assertNotIn(token, policy)
        remove = text.split('    fun remove(entry:', 1)[1]
        self.assertIn('== managedImeState', remove)
        self.assertIn('originalImeState != managedImeState', remove)
        self.assertIn('removeOnGlobalLayoutListener(legacyImeLayout)', remove)
        self.assertIn('removeCallbacks(resumeImeObservation)', remove)
        self.assertIn('decor?.post(resumeImeObservation)', text)
        self.assertIn('or originalImeState', remove)
        self.assertIn('if (restored == 0) window.attributes = window.attributes.apply { softInputMode = restored }', remove)

    def test_home_selection_is_prepared_after_back_and_verified_before_leaving(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ManagedInputActivityTest.kt').read_text()
        home = text.split('private fun fieldHomeRoundTrip', 1)[1].split('    fun testWindowImePolicyRestoresZero', 1)[0]
        self.assertLess(home.index('sendKeyDownUpSync'), home.index('SemanticsActions.SetSelection'))
        self.assertLess(home.index('assertEquals(1, selection.end)'), home.index('GLOBAL_ACTION_HOME'))
        self.assertNotIn('assertTrue(it(5, 1, true))', home)
        self.assertIn('!= wantedSelection', home)
        self.assertLess(home.index('stopped.await('), home.index('awaitHomeWindowSettled()'))
        self.assertLess(home.index('awaitHomeWindowSettled()'), home.index('restoreOriginalHost(original)'))
        driver = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ComposeActivityTestCase.kt').read_text()
        background = driver.split('protected fun awaitHomeWindowSettled()', 1)[1].split('protected fun restoreOriginalHost', 1)[0]
        for token in ('window.isFocused', 'TYPE_APPLICATION', 'TYPE_INPUT_METHOD',
                      '!owner.isNullOrEmpty() && owner != activity.packageName', 'home.takeUnless { imeShown }',
                      'root?.recycle()', 'window.recycle()', 'FLAG_RETRIEVE_INTERACTIVE_WINDOWS',
                      'stable >= 2', 'flags = oldFlags'):
            self.assertIn(token, background)
        for token in ('showSoftInput', 'hideSoftInput', 'requestFocus', 'Thread.sleep'):
            self.assertNotIn(token, background)
        self.assertIn('testWindowImePolicyRestoresZeroAndKeepsLaterWindowModes', text)
        self.assertIn('listOf(0 to null, resize to pan, resize to (pan or hidden), unchanged to null)', text)
        self.assertIn('awaitControl("policy Dialog attached and window-focused")', text)
        self.assertIn('awaitStableWindowFrame(decor)', text)
        self.assertIn('box.width > 0 && box.height > 0', text)
        self.assertIn('assertTrue(field.isAttachedToWindow)', text)

    def test_registration_cleanup_preserves_later_window_wrappers(self):
        text = SOURCE.read_text()
        for token in ('onDispose { detach() }', 'removeOnAttachStateChangeListener(listener)',
                      'if (window.callback === callback)',
                      'InputWindows.windows.remove(window, this)'):
            self.assertIn(token, text)

    def test_brush_and_material_hidden_cursor_keep_visible_original_color(self):
        text = SOURCE.read_text()
        self.assertIn('if (WindowInsets.isImeVisible) visibleColor else Color.Unspecified', text)
        self.assertIn('if (WindowInsets.isImeVisible) visibleColor else Color.Transparent', text)
        self.assertIn('SolidColor(imeCursorColor(visibleColor))', text)

    def test_native_fields_register_and_pet_draft_is_not_owned_by_focus(self):
        for name in ('question/QuestionHost.kt', 'question/QuestionCardView.kt', 'pet/PetViewFactory.kt'):
            self.assertIn('manageInput(', (MAIN / name).read_text())
        text = (MAIN / 'pet/PetViewFactory.kt').read_text()
        self.assertIn('field.text.toString() == previous', text)
        self.assertNotIn('if (!assistant.hasFocus()) assistant.setText', text)
        self.assertIn('lastModel = s.settings.model', text)
        self.assertIn('if (field.text.toString() != next) field.setText(next)', text)

    def test_textfieldvalue_guards_only_our_blur_callback(self):
        text = (MAIN / 'ui/screens/settings/SettingsUiComponents.kt').read_text()
        self.assertIn('!app.daiyu.android.ui.components.InputDismissal.inProgress || updated.text != editorValue.text', text)
        self.assertIn('if (updated.text != value) onValueChange(updated.text)', text)
        chat = (MAIN / 'ui/screens/chat/conversation/ChatScreen.kt').read_text()
        self.assertIn('!app.daiyu.android.ui.components.InputDismissal.insideInput', chat)

    def test_web_dom_and_terminal_program_cursor_are_not_rewritten(self):
        terminal = (MAIN / 'ui/screens/terminal/TerminalConsole.kt').read_text()
        self.assertNotIn('managedInput()', terminal)
        self.assertNotIn('imeCursor', terminal)
        browser = (ROOT / 'components/openminis-browser/adapter/src/main/kotlin/app/daiyu/android/browser/openminis/AndroidOpenMinisBrowserHost.kt').read_text()
        self.assertNotIn('caret-color', browser)
        self.assertNotIn('document.activeElement.blur()', browser)

    def test_instrumentation_uses_actual_touches_and_real_window_ime(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ManagedInputActivityTest.kt').read_text()
        for token in ('exerciseFields(false)', 'exerciseFields(true)', 'tapField("managed-second")',
                      'assertEquals(5, selection.start)', 'assertEquals(1, selection.end)',
                      'GLOBAL_ACTION_HOME', 'awaitHostWindowFocus(original)',
                      'awaitFieldIme("managed-first", visible = false)', '动作次数 1',
                      'realLongPress("managed-first")', 'realDrag("managed-first")', 'fixture.database.exportBackup()', 'fixture.opens.get()'):
            self.assertIn(token, text)
        home = text.split('private fun fieldHomeRoundTrip', 1)[1].split('    fun testWindowImePolicyRestoresZero', 1)[0]
        returned = home.split('awaitHostWindowFocus(original)', 1)[1]
        self.assertIn('awaitFieldIme(tag, visible = !hiddenBeforeHome)', returned)
        self.assertIn('node(tag).assertIsFocused()', returned)
        self.assertIn('sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)', home)
        for forbidden in ('dismissIme()', 'tapField(', '.show(', 'requestFocus('):
            self.assertNotIn(forbidden, returned)
        self.assertIn('DaiyuTextInputDialog(', text)
        self.assertIn('fieldHomeRoundTrip("prompt-input", hiddenBeforeHome)', text)
        self.assertNotIn('Thread.sleep', text)
        taps = text.split('private fun exerciseFields', 1)[1].split('fun testHiddenIme', 1)[0]
        self.assertNotIn('dismissIme()', taps)
        self.assertIn('uiAutomation.injectInputEvent(event, true)', text)
        self.assertIn('node(tag).assertIsFocused()', text)
        self.assertIn('Hidden IME must not draw a steady caret either', text)
        self.assertIn('image[it % width + inset, it / width + inset]', text)
        self.assertIn('testNativeFieldsKeepDraftAndSelectionWhenOutsideTapClearsFocus', text)
        self.assertNotIn('performTouchInput', text)

    def test_real_touches_map_layout_window_bounds_to_screen_before_injection(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ManagedInputActivityTest.kt').read_text()
        bounds = text.split('private fun screenBounds(', 1)[1].split('private fun injectGesture(', 1)[0]
        for token in ('awaitNodeWindowFocus(interaction)', 'stableScreenBounds(view, touchBounds)',
                      'val coordinates = target.layoutInfo.coordinates', 'coordinates.boundsInWindow()',
                      'view.getLocationOnScreen(screen)', 'view.getLocationInWindow(window)',
                      '(screen[0] - window[0]).toFloat()', '(screen[1] - window[1]).toFloat()',
                      '!coordinates.isAttached', 'screen-bounds tag=$tag'):
            self.assertIn(token, bounds)
        self.assertLess(bounds.index('awaitNodeWindowFocus(interaction)'), bounds.index('stableScreenBounds(view, touchBounds)'))
        stable = text.split('private fun stableScreenBounds(', 1)[1].split('private fun screenBounds(', 1)[0]
        for token in ('awaitStableWindowFrame(view) { sample() }', 'view.windowToken != token',
                      '!view.isAttachedToWindow', '!view.hasWindowFocus()', 'view.isLayoutRequested',
                      'FLAG_RETRIEVE_INTERACTIVE_WINDOWS', 'view.createAccessibilityNodeInfo() ?: return null',
                      'val id = try { node.windowId } finally { node.recycle() }',
                      'val windows = automation.windows', 'windows.singleOrNull { it.id == id && it.isFocused }',
                      'window.getBoundsInScreen(nativeBounds)', 'windowBounds.left <= bounds.left',
                      'windowBounds.top <= bounds.top', 'windowBounds.right >= bounds.right',
                      'windowBounds.bottom >= bounds.bottom', 'if (contained && !occluded) TouchFrame(bounds, windowBounds, id)',
                      'TouchFrame(bounds, windowBounds, id)', 'instrumentation.runOnMainSync { current = sample() }',
                      'if (current == stable) return stable.bounds', 'uptimeMillis() < deadline',
                      'finally', 'flags = oldFlags'):
            self.assertIn(token, stable)
        self.assertLess(stable.index('instrumentation.runOnMainSync { current = sample() }'),
                        stable.index('if (current == stable) return stable.bounds'))
        self.assertNotIn('view.accessibilityWindowId', stable)
        self.assertNotIn('injectInputEvent', stable)
        self.assertNotIn('requestFocus', stable)
        native_tap = text.split('fun tap(view: android.view.View)', 1)[1].split('fun awaitNativeIme', 1)[0]
        self.assertIn('stableScreenBounds(view)', native_tap)
        self.assertNotIn('mainThread', native_tap)
        self.assertNotIn('target.boundsInRoot', bounds)
        self.assertIn('ui.waitUntil(5_000)', text.split('private fun awaitFieldFocus(', 1)[1].split('private fun awaitFieldIme(', 1)[0])
        self.assertIn('tag=$tag expected=$focused observed=$observed', text)
        tap = text.split('private fun tapField(', 1)[1].split('private fun exerciseFields(', 1)[0]
        self.assertEqual(1, tap.count('realTap(tag)'))
        self.assertNotIn('requestFocus', tap)
        self.assertNotIn('awaitDialogEnter', bounds)

    def test_dialog_clears_previous_ime_before_creating_new_window(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ManagedInputActivityTest.kt').read_text()
        setup = text.split('private fun showFields(', 1)[1].split('private fun awaitNoImeWindow(', 1)[0]
        self.assertLess(setup.index('dismissIme()'), setup.index('awaitNoImeWindow(activity.window.decorView)'))
        self.assertLess(setup.index('awaitNoImeWindow(activity.window.decorView)'), setup.index('activity.setContent'))
        waiter = text.split('private fun awaitNoImeWindow(', 1)[1].split('private data class TouchFrame(', 1)[0]
        for token in ('FLAG_RETRIEVE_INTERACTIVE_WINDOWS', 'awaitStableWindowFrame(view)',
                      'automation.windows.none', 'TYPE_INPUT_METHOD', '!bounds.isEmpty',
                      'absent.takeIf { it }', 'finally', 'flags = oldFlags'):
            self.assertIn(token, waiter)
        self.assertNotIn('requestFocus', setup + waiter)
        self.assertNotIn('injectInputEvent', setup + waiter)

    def test_touch_geometry_rejects_higher_windows_without_reinjecting(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ManagedInputActivityTest.kt').read_text()
        stable = text.split('private fun stableScreenBounds(', 1)[1].split('@OptIn', 1)[0]
        for token in ('val windows = automation.windows', 'windows.singleOrNull', 'val occluded = windows.any',
                      'other.id == id', 'other.layer <= window.layer', 'other.getBoundsInScreen(overlay)',
                      '!overlay.isEmpty', '.overlaps(touch)', 'contained && !occluded',
                      'instrumentation.runOnMainSync { current = sample() }'):
            self.assertIn(token, stable)
        precise = stable.split('if (android.os.Build.VERSION.SDK_INT >= 30)', 1)[1].split('} else {', 1)[0]
        for token in ('val overlay = android.graphics.Region()', 'other.getRegionInScreen(overlay)',
                      'overlay.op(targetPixels, android.graphics.Region.Op.INTERSECT)', '!overlay.isEmpty'):
            self.assertIn(token, precise)
        for forbidden in ('getBoundsInScreen', '.bounds', 'quickReject', '.center'):
            self.assertNotIn(forbidden, precise)
        self.assertIn('RectF(touch.left, touch.top, touch.right, touch.bottom).roundOut(targetPixels)', stable)
        self.assertIn('Touch geometry rejected: $lastSample', stable)
        self.assertEqual(2, stable.count('automation.windows'))
        self.assertIn('other.id == readyWindowId && other.isFocused && !bounds.isEmpty', stable)
        self.assertLess(stable.index('var readyWindowId = -1'), stable.index('fun sample(): TouchFrame?'))
        self.assertNotIn('requestFocus', stable)
        self.assertNotIn('injectInputEvent', stable)

    def test_ordinary_outside_target_is_separate_from_editing_affordances(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ManagedInputActivityTest.kt').read_text()
        setup = text.split('private fun showFields(', 1)[1].split('private fun awaitNoImeWindow(', 1)[0]
        self.assertIn('Spacer(Modifier.height(48.dp))\n                            androidx.compose.material3.Text("外部标题", Modifier.testTag("managed-outside"))', setup)
        self.assertEqual(1, setup.count('Spacer(Modifier.height(48.dp))'))
        self.assertNotIn('clearFocus', setup)
        self.assertNotIn('fieldToolbar?.hide', setup)

    def test_compose_regions_read_live_coordinates_and_window_origin_per_hit(self):
        text = SOURCE.read_text()
        self.assertIn('InputEntry(view, { state.focused }, { state.bounds(view) })', text)
        self.assertIn('this.onGloballyPositioned { state.coordinates = it }', text)
        region = text.split('private class ComposeInputRegion {', 1)[1].split('/** Native', 1)[0]
        for token in ('var coordinates: LayoutCoordinates?', 'fun bounds(view: View): RectF?',
                      '!view.isAttachedToWindow', 'coordinates?.takeIf { it.isAttached }',
                      'current.boundsInWindow()', 'view.getLocationOnScreen(screen)',
                      'view.getLocationInWindow(window)', 'return RectF(', 'screen[0] - window[0]',
                      'screen[1] - window[1]'):
            self.assertIn(token, region)
        self.assertNotIn('var bounds: RectF?', region)
        self.assertNotIn('requestFocus', region)


if __name__ == '__main__':
    unittest.main()
