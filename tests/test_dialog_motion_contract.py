"""Source contracts only; pixel/Compose animation regressions run with the next approved build."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class DialogMotionContractTest(unittest.TestCase):
    def text(self, relative):
        return (SRC / relative).read_text()

    def test_centered_theme_selects_platform_alpha_only_resources_without_activity_motion(self):
        root = ET.parse(ROOT / "app/src/main/res/values/styles.xml").getroot()
        styles = {node.attrib["name"]: node for node in root.findall("style")}
        def items(name):
            return {node.attrib["name"]: node.text for node in styles[name].findall("item")}
        self.assertEqual(items("Theme.Daiyu")["android:dialogTheme"], "@style/Theme.Daiyu.CenterDialog")
        self.assertNotIn("android:windowAnimationStyle", items("Theme.Daiyu"))
        for name in ("Theme.Daiyu.CenterDialog", "Theme.Daiyu.Dialog"):
            self.assertEqual(items(name)["android:windowAnimationStyle"], "@style/Animation.Daiyu.CenterDialog")
        self.assertEqual(styles["Theme.Daiyu.Dialog"].attrib["parent"], "android:style/ThemeOverlay.Material.Dialog.Alert")
        self.assertEqual(styles["Animation.Daiyu.CenterDialog"].get("parent"), "android:style/Animation.Dialog")
        self.assertEqual(items("Animation.Daiyu.CenterDialog"), {
            "android:windowEnterAnimation": "@anim/daiyu_center_dialog_enter",
            "android:windowExitAnimation": "@anim/daiyu_center_dialog_exit"})

    def test_center_fades_match_material_dialog_timing_without_translation_or_scale(self):
        android = "{http://schemas.android.com/apk/res/android}"
        for direction, start, end, duration, points in [
            ("enter", 0.0, 1.0, 220, (0.2, 0.0, 0.0, 1.0)),
            ("exit", 1.0, 0.0, 150, (0.3, 0.0, 1.0, 1.0)),
        ]:
            with self.subTest(direction=direction):
                name = "daiyu_center_dialog_" + direction
                animation = ET.parse(ROOT / "app/src/main/res/anim" / (name + ".xml")).getroot()
                self.assertEqual(animation.tag, "alpha")
                self.assertEqual(list(animation), [])
                self.assertEqual(float(animation.get(android + "fromAlpha")), start)
                self.assertEqual(float(animation.get(android + "toAlpha")), end)
                self.assertEqual(int(animation.get(android + "duration")), duration)
                self.assertEqual(int(animation.get(android + "startOffset", "0")), 0)
                self.assertEqual(int(animation.get(android + "repeatCount", "0")), 0)
                self.assertEqual(animation.get(android + "interpolator"), "@interpolator/" + name)
                interpolator = ET.parse(ROOT / "app/src/main/res/interpolator" / (name + ".xml")).getroot()
                self.assertEqual(interpolator.tag, "pathInterpolator")
                self.assertEqual(tuple(float(interpolator.get(android + key)) for key in
                    ("controlX1", "controlY1", "controlX2", "controlY2")), points)

    def test_collaboration_panel_keeps_bottom_sheet_motion_not_centered_fade(self):
        panel = self.text("ui/components/ConversationCollaborationDialog.kt")
        self.assertIn("ConversationAgentSheet(onDismiss,", panel)
        self.assertNotIn("bottomSheetMotion", panel)
        style = self.text("ui/components/subagent/ConversationAgentStyle.kt")
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = true)", style)
        self.assertNotIn("bottomSheetMotion", style)
        self.assertIn("modifier.align(Alignment.BottomCenter)", style)
        self.assertIn(".padding(10.dp)", style)

    def test_dialog_primitives_cannot_bypass_motion_adapters(self):
        allowed = {"ui/components/DialogMotion.kt", "ui/components/PreviewDialog.kt"}
        for path in SRC.rglob("*.kt"):
            relative = path.relative_to(SRC).as_posix()
            if relative in allowed:
                continue
            text = path.read_text()
            with self.subTest(path=relative):
                self.assertNotRegex(text, r"(?m)^import androidx\.compose\.ui\.window\.Dialog(?:[ \t]+as[^\n]*)?$")
                self.assertNotIn("androidx.compose.ui.window.Dialog(", text)
                self.assertNotRegex(text, r"(?m)^import androidx\.compose\.material3\.AlertDialog(?:[ \t]+as[^\n]*)?$")
                self.assertNotIn("androidx.compose.material3.AlertDialog(", text)
                if "import androidx.compose.material3.*" in text and re.search(r"(?<![\w.])AlertDialog\(", text):
                    self.assertIn("DaiyuAlertDialog as AlertDialog", text)

    def test_original_assistant_spring_and_stationary_scrim(self):
        motion = self.text("ui/components/DialogMotion.kt")
        self.assertIn("dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f", motion)
        self.assertIn("folmeSpring(damping = 0.9f, response = 0.3f)", motion)
        self.assertIn("840f", motion)
        self.assertIn("480f", motion)
        self.assertIn("translationY = (1f - value) * size.height", motion)
        self.assertIn("tween(300, easing = DecelerateEasing(1.5f))", motion)
        preview = self.text("ui/components/PreviewDialog.kt")
        self.assertIn(".dialogDimBackground(", preview)
        self.assertIn("val motion = if (bottomSheet) Modifier.dialogEnterMotion() else Modifier", preview)
        self.assertIn("if (bottomSheet) window?.setWindowAnimations(0) else window?.usePlatformDialogFade()", preview)
        choices = self.text("ui/components/DaiyuDialogs.kt")
        self.assertIn("animateContent = false", choices)
        self.assertIn("Modifier.dialogEnterMotion().align(Alignment.BottomCenter)", choices)

    def test_native_centered_dialogs_restore_platform_motion_before_show(self):
        native = self.text("ui/components/NativeDialogMotion.kt")
        self.assertIn("window?.usePlatformDialogFade()", native)
        self.assertIn("android.R.attr.windowAnimationStyle", native)
        self.assertIn("finally { attributes.recycle() }", native)
        self.assertIn("setWindowAnimations(animation)", native)
        self.assertNotIn("android.R.style.Animation_Dialog", native)
        self.assertNotIn("ValueAnimator", native)
        self.assertNotIn("translationY", native)
        for file in ["ui/GenerationMediaDialog.kt", "ui/FeatureHubDialog.kt", "question/QuestionHost.kt"]:
            with self.subTest(file=file):
                text = self.text(file)
                self.assertIn("prepareDaiyuCenterMotion()", text)
                self.assertNotIn("startDaiyuEnterMotion()", text)
                self.assertNotIn("setWindowAnimations(0)", text)
        for file, receiver in [("ui/FeatureHubDialog.kt", "dialog"), ("question/QuestionHost.kt", "window")]:
            text = self.text(file)
            self.assertLess(text.index(receiver + ".prepareDaiyuCenterMotion()"), text.index(receiver + ".show()"))
        sheet = self.text("ui/components/SubAgentPresetActionsSheet.kt")
        self.assertIn("ModalBottomSheet(", sheet)
        self.assertIn("SheetState(", sheet)
        self.assertIn("initialValue = SheetValue.Expanded", sheet)
        self.assertIn("skipPartiallyExpanded = true", sheet)
        self.assertIn("56.dp.toPx()", sheet)
        self.assertIn("125.dp.toPx()", sheet)
        self.assertIn("modifier = Modifier.dialogEnterMotion()", sheet)
        self.assertIn("dragHandle = { BottomSheetDefaults.DragHandle() }", sheet)
        self.assertNotIn("AssistantSheetMotion", sheet)
        self.assertNotIn("MotionScheme", self.text("ui/components/DialogMotion.kt"))
        self.assertNotIn(".motionScheme", self.text("ui/components/DialogMotion.kt"))

    def test_centered_wrappers_restore_platform_fade_without_delaying_dismissal(self):
        motion = self.text("ui/components/DialogMotion.kt")
        self.assertIn("internal fun PlatformDialogFade()", motion)
        self.assertIn("window?.usePlatformDialogFade()", motion)
        dialog = motion.split("internal fun DaiyuDialog(", 1)[1].split("internal fun DaiyuAlertDialog(", 1)[0]
        self.assertIn("bottomSheet: Boolean = false", dialog)
        self.assertIn("if (bottomSheet) DisablePlatformDialogAnimation() else PlatformDialogFade()", dialog)
        self.assertIn("val contentMotion = if (bottomSheet && animateContent) Modifier.dialogEnterMotion() else Modifier", dialog)
        self.assertIn("motion?.requestDismiss(BottomSheetDismissSource.System) ?: onDismissRequest()", dialog)
        alert = motion.split("internal fun DaiyuAlertDialog(", 1)[1]
        self.assertIn("confirmButton = { PlatformDialogFade(); confirmButton() }", alert)
        self.assertIn("modifier = modifier", alert)
        self.assertNotIn("dialogEnterMotion()", alert)
        self.assertNotIn("delay(", motion)

    def test_preview_center_uses_one_window_fade_and_bottoms_keep_their_motion(self):
        preview = self.text("ui/components/PreviewDialog.kt")
        self.assertIn("if (bottomSheet) window?.setWindowAnimations(0) else window?.usePlatformDialogFade()", preview)
        self.assertIn("animate = bottomSheet", preview)
        self.assertIn("val motion = if (bottomSheet) Modifier.dialogEnterMotion() else Modifier", preview)
        self.assertIn("BoxWithConstraints(motion.fillMaxSize()", preview)
        motion = self.text("ui/components/DialogMotion.kt")
        self.assertIn("if (!animate) return drawWithContent", motion)
        self.assertIn("drawRect(color)", motion)
        choice = self.text("ui/components/DaiyuDialogs.kt").split("internal fun DaiyuChoiceSheet(", 1)[1]
        self.assertIn("bottomSheet = true", choice)
        browser = self.text("ui/screens/browser/BrowserVisualScreen.kt")
        self.assertRegex(browser, r"BrowserSheet\(\s*pool, onBack, presentation,\s*addressInputCurrent = \{ visit != null && ownerCurrent\(\) \},\s*\)")
        self.assertNotIn("DaiyuDialog(", browser)
        sheet = self.text("browser/ported/ui/browser/BrowserSheet.kt")
        standard = self.text("browser/ported/ui/chat/StandardChatSheet.kt")
        self.assertIn("StandardChatSheet(", sheet)
        self.assertIn("ModalBottomSheet(", standard)
        self.assertIn("rememberModalBottomSheetState(skipPartiallyExpanded = true)", standard)
        self.assertIn("onDismissRequest = onDismiss", standard)

    def test_android_regression_covers_center_bottom_and_immediate_owner_disposal(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/CenteredDialogMotionActivityTest.kt").read_text()
        for fragment in ["DaiyuDialog(dismiss)", "DaiyuAlertDialog(dismiss", "PreviewDialog(dismiss)",
                         "compact = true", "bottomSheet = true", "DaiyuChoiceSheet(",
                         "dialog.prepareDaiyuCenterMotion()", "assertEquals(1, dismissals)",
                         "assertEquals(1, disposals)", "window.attributes.windowAnimations",
                         "android.R.attr.windowAnimationStyle", "assertFalse(dialog.isShowing)",
                         "android.R.attr.dialogTheme", "ContextThemeWrapper(activity, theme)",
                         "android.R.attr.windowExitAnimation", "AnimationUtils.loadAnimation(context, resource)",
                         "loaded.getTransformation(", "transformation.matrix.getValues(matrix)",
                         '"Centered fade must have identity matrix: $sample"',
                         "identity[index], matrix[index], .0001f", "for (step in 0..64)",
                         "if (entering) 0f else 1f, transformation.alpha, .0001f",
                         "if (entering) 1f else 0f, transformation.alpha, .0001f",
                         "Triple(android.R.attr.windowEnterAnimation, 220L, true)",
                         "Triple(android.R.attr.windowExitAnimation, 150L, false)",
                         "expectedDuration = duration", "0L, animation.startOffset",
                         'assertStationaryFade("negative-$index", create = create)',
                         "ConversationAgentSheet(dismiss)",
                         'assertWindowAnimation("collaboration-close", platformFade = false)',
                         "testFadeVerifierAcceptsIdentityTranslateAndScale", "testFadeVerifierRejectsRealMotionAndWrongAlphaEndpoints",
                         "assertOneShot(prototype, diagnostic)", "0, animation.repeatCount",
                         "animation.animations.forEach { assertOneShot(it, diagnostic) }", "repeatMode = Animation.REVERSE"]:
            self.assertIn(fragment, test)
        self.assertIn('assertWindowAnimation("bottom-preview", platformFade = false)', test)
        self.assertIn('assertWindowAnimation("daiyu-choice-sheet", platformFade = false)', test)

    def test_production_glass_menu_uses_original_popup_motion_and_exit_retention(self):
        motion = self.text("ui/components/PopupMotion.kt")
        for token in ["FractionAnimationSpec", "AlphaEnterAnimationSpec", "AlphaExitAnimationSpec"]:
            self.assertIn("ListPopupDefaults." + token, motion)
        self.assertIn("PopupExitRetentionMillis = 160L", motion)
        self.assertIn("tween(durationMillis = 150)", motion)
        layer = self.text("ui/screens/chat/conversation/ChatMenuLayer.kt")
        for fragment in ["rememberPopupMotion(menu.expanded())", "0.15f + 0.85f * motion.fraction.value",
                         "TransformOrigin(1f, 0f)", ".etaMenuClipReveal(", "if ((expanded || mounted) && layer != null)",
                         "delay(PopupExitRetentionMillis)", "PointerEventPass.Initial"]:
            self.assertIn(fragment, layer)
        self.assertIn("if (!menu.expanded()) event.changes.forEach { it.consume() }", layer)
        # Keep the existing single-pass measure/place and page-owned backdrop.
        self.assertIn("val surface = measurables[1].measure", layer)
        self.assertIn("surface.place(x, y)", layer)
        self.assertIn("captureForTopBar(backdrop)", layer)
        self.assertIn("else if (layer == null && expanded)", layer)
        self.assertIn("LaunchedEffect(layer, menu, expanded)", layer)
        self.assertIn("layer.active?.takeIf { it !== menu }?.onDismiss?.invoke()", layer)
        self.assertIn("clipWhenFullyRevealed = false", layer)
        self.assertIn("else Modifier.clearAndSetSemantics {}", layer)
        self.assertIn("rememberPopupMotion(expanded)", self.text("ui/components/EtaDropdownMenu.kt"))

    def test_more_button_is_transparent_but_keeps_selection_semantics(self):
        text = self.text("ui/screens/chat/conversation/ChatTopBar.kt")
        button = text[text.index('val moreShape'):text.index('ChatOverflowMenu(expanded')]
        self.assertNotIn(".background(", button)
        self.assertNotIn(".border(", button)
        self.assertIn("indication = null", button)
        self.assertIn("selected = expanded", button)
        self.assertIn("actions.onMore(); expanded = true", button)


if __name__ == "__main__":
    unittest.main()
