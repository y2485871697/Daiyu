"""Source contracts only. Android/Compose behavior is tested separately on device."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
HAPTICS = SRC / "ui/haptics"


class TouchHapticsContractTest(unittest.TestCase):
    def test_no_unrouted_foundation_interaction_imports(self):
        for path in SRC.rglob("*.kt"):
            if HAPTICS in path.parents:
                continue
            self.assertNotRegex(path.read_text(), r"import androidx\.compose\.foundation\.(?:selection\.)?(?:clickable|combinedClickable|selectable|toggleable)\s", str(path))

    def test_no_unrouted_standard_controls_including_aliases_and_qualified_calls(self):
        names = "Button|TextButton|OutlinedButton|FilledTonalButton|IconButton|Switch|Checkbox|RadioButton|DropdownMenuItem"
        for path in SRC.rglob("*.kt"):
            if HAPTICS in path.parents:
                continue
            text = path.read_text()
            self.assertNotRegex(text, rf"(?:androidx\.compose\.material3|top\.yukonga\.miuix\.kmp\.basic)\.(?:{names})\b", str(path))
            if "import androidx.compose.material3.*" in text:
                for name in names.split("|"):
                    if re.search(rf"(?<![\w.]){name}\s*\(", text):
                        self.assertRegex(text, rf"(?m)^import .+(?:\.{name}| as {name})$", str(path))

    def test_native_buttons_use_the_same_owner(self):
        for path in SRC.rglob("*.kt"):
            if HAPTICS in path.parents:
                continue
            self.assertNotRegex(path.read_text(), r"\b(?:setOnClickListener|setPositiveButton|setNegativeButton|setNeutralButton)\b", str(path))
        views = (HAPTICS / "HapticViews.kt").read_text()
        self.assertIn("if (view.isEnabled) TouchHaptics.action(view)", views)
        self.assertEqual(views.count("getButton(which)"), 3)

    def test_combined_click_has_one_long_press_owner(self):
        text = (HAPTICS / "HapticModifiers.kt").read_text()
        self.assertEqual(text.count("hapticFeedbackEnabled = false"), 2)
        self.assertEqual(text.count("onLongClick?.let { hapticLongClick(enabled, it) }"), 2)
        self.assertEqual(text.count("onDoubleClick?.let { hapticClick(enabled, it) }"), 2)
        for parameter in ("role = role", "onClickLabel = onClickLabel", "onLongClickLabel = onLongClickLabel", "indication = indication", "interactionSource = interactionSource"):
            self.assertIn(parameter, text)
        self.assertNotIn("pointerInput", text)

    def test_feedback_is_scoped_to_accepted_actions_not_time_debounce(self):
        text = (HAPTICS / "TouchHaptics.kt").read_text()
        self.assertIn("actionDepth == 0", text)
        self.assertIn("finally { actionDepth-- }", text)
        self.assertNotRegex(text, r"SystemClock|currentTimeMillis|delay\(|postDelayed")
        self.assertNotIn("vibrate(", text)
        self.assertNotIn("performHapticFeedback(", text)

    def test_null_passive_callbacks_and_enabled_are_preserved(self):
        text = (HAPTICS / "material/HapticMaterialControls.kt").read_text()
        self.assertEqual(text.count("onCheckedChange: ((Boolean) -> Unit)?"), 2)
        self.assertEqual(text.count("onCheckedChange?.let { hapticChange(enabled, it) }"), 2)
        self.assertIn("onClick: (() -> Unit)?", text)
        self.assertIn("onClick?.let { hapticClick(enabled, it) }", text)
        self.assertIn("content: @Composable RowScope.() -> Unit", text)
        actions = (HAPTICS / "HapticActions.kt").read_text()
        self.assertEqual(actions.count("if (enabled)"), 3)

    def test_continuous_slider_only_acknowledges_committed_adjustments(self):
        text = (HAPTICS / "material/HapticMaterialControls.kt").read_text().split("internal fun Slider(", 1)[1]
        on_change, on_finished = text.split("onValueChangeFinished = {", 1)
        self.assertNotIn("TouchHaptics.action", on_change)
        self.assertIn("if (next != value) changed.value = true", on_change)
        self.assertIn("changed.value = false", on_finished)
        self.assertIn("if (acknowledge) TouchHaptics.action(view)", on_finished)

    def test_existing_touch_settings_and_preview_are_single_owner(self):
        text = (SRC / "ui/screens/settings/HapticsSettingsScreen.kt").read_text()
        self.assertNotIn("if (old.touch) session.touch()", text)
        self.assertIn("if (!old.touch && next.touch) session.touch()", text)
        self.assertIn("session.preview(intensity)", text)
        self.assertIn("Slider as IntensityPreviewSlider", text)
        self.assertIn("IntensityPreviewSlider(value = settings.intensity", text)
        self.assertIn("onCheckedChange = null", text)

    def test_overlay_feedback_does_not_enable_background_generation(self):
        text = (SRC / "haptics/HapticsRuntime.kt").read_text()
        ui = text.split("fun uiTouch(", 1)[1].split("private fun cancelUi", 1)[0]
        self.assertIn("TYPE_APPLICATION_OVERLAY", ui)
        self.assertIn("TYPE_ACCESSIBILITY_OVERLAY", ui)
        self.assertIn("(!engine.foreground && !ownedOverlay)", ui)
        self.assertIn("!store.read().touch", ui)
        self.assertIn("!view.isAttachedToWindow", ui)
        self.assertIn("view.windowVisibility != View.VISIBLE", ui)
        self.assertNotIn("engine.setForeground", ui)

    def test_empty_consumers_do_not_vibrate(self):
        cases = {
            "ui/components/DaiyuDialogs.kt": "clickFeedback = false,\n                        onClick = {}",
            "ui/screens/browser/BrowserVisualScreen.kt": "clickFeedback = false, onClick = {}",
            "ui/screens/chat/conversation/ChatMessageList.kt": "clickFeedback = false, onClick = {}, onLongClick",
        }
        for file, fragment in cases.items():
            self.assertIn(fragment, (SRC / file).read_text())
        for file in (SRC / "ui/screens/chat/conversation").glob("Tool*Dialog.kt"):
            self.assertNotIn(".conversationClickable {}", file.read_text())

    def test_custom_semantics_links_and_slider_accessibility_are_covered(self):
        for file in ("ui/screens/settings/SettingsUiComponents.kt", "ui/screens/settings/WorkspaceSettingsScreen.kt", "ui/screens/settings/IconAppearanceEditor.kt", "ui/PlatformActionApprovalHost.kt", "ui/screens/chat/conversation/MessageMarkdown.kt"):
            text = (SRC / file).read_text()
            self.assertIn("TouchHaptics.action(feedbackView)", text, file)
            self.assertIn("val feedbackView = LocalView.current", text, file)
        self.assertIn("TouchHaptics.action(view) { preview(index); commit(index) }", (SRC / "ui/components/subagent/ConversationThinkingPicker.kt").read_text())
        text = (SRC / "ui/components/subagent/ThinkingEffortPicker.kt").read_text()
        self.assertIn("CompositionLocalProvider(LocalConversationAgentStyle provides !mainChat)", text)
        self.assertIn("ConversationThinkingPicker(show, effort, options, onDismiss, onEffortChange, description)", text)
        self.assertNotIn("WindowDialog", text)
        self.assertNotIn("SliderDefaults", text)

    def test_programmatic_native_check_restore_has_no_haptic_observer(self):
        for file, group in (("question/QuestionHost.kt", "group"), ("question/QuestionCardView.kt", "options")):
            text = (SRC / file).read_text()
            self.assertIn(f"{group}.getChildAt(index).setHapticClickListener {{}}", text)
        pet = (SRC / "pet/PetViewFactory.kt").read_text()
        self.assertIn("control.setHapticClickListener {}", pet)
        self.assertIn("if (!rendering)", pet)
        self.assertNotIn("TouchHaptics", pet.split("override fun onProgressChanged", 1)[1].split("override fun onStartTrackingTouch", 1)[0])

    def test_library_feedback_bridge_respects_same_runtime(self):
        text = (HAPTICS / "ComposeHaptics.kt").read_text()
        self.assertIn("LocalHapticFeedback provides feedback", text)
        self.assertIn("TouchHaptics.longPress(view)", text)
        self.assertIn("TouchHaptics.tick(view)", text)
        self.assertNotIn("android.os.Vibrator", text)
        self.assertIn("ProvideTouchHaptics { WithoutPressRipple", (SRC / "ui/app/AgentAppTheme.kt").read_text())

    def test_async_save_has_no_success_delayed_duplicate(self):
        for file in ("ui/components/SubAgentGptSpeedButton.kt", "ui/components/SubAgentProfileConfigDialog.kt"):
            text = (SRC / file).read_text()
            self.assertNotIn("TouchHaptics.click", text)
            self.assertNotIn("TouchHaptics.longPress", text)


if __name__ == "__main__":
    unittest.main()
