"""Source guards; Android frame/input tests remain for the approved build batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
COMPONENTS = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/components'


class BottomSheetDismissMotionContractTest(unittest.TestCase):
    def test_shared_state_animates_current_progress_and_scrim_before_owner_callback(self):
        text = (COMPONENTS / 'DialogMotion.kt').read_text()
        scope = text.partition('internal fun DialogMotionProvider(')[2].split('\n@Composable', 1)[0]
        self.assertIn('LaunchedEffect(motion.dismissSource)', scope)
        self.assertIn('motion.progress.animateTo(target, dialogEnterSpec(largeScreen))', scope)
        self.assertIn('motion.scrimProgress.animateTo(target, tween(300, easing = DecelerateEasing(1.5f)))', scope)
        self.assertLess(scope.index('coroutineScope {'), scope.index('latestDismiss()'))
        self.assertNotIn('snapTo(', scope)
        self.assertNotIn('delay(', scope)

    def test_requests_keep_first_source_and_recover_if_owner_vetoes(self):
        text = (COMPONENTS / 'DialogMotion.kt').read_text()
        self.assertIn('if (dismissSource == null) dismissSource = source', text)
        self.assertIn('BottomSheetDismissSource.System -> latestDismiss()', text)
        self.assertIn('BottomSheetDismissSource.Outside -> latestOutsideDismiss()', text)
        self.assertIn('withFrameNanos { }', text)
        self.assertIn('motion.cancelDismiss()', text)
        self.assertIn('rememberUpdatedState(onDismiss)', text)
        self.assertNotIn('finally { onDismiss', text)

    def test_both_windows_only_gate_bottoms_and_preview_preserves_outside_callback(self):
        motion = (COMPONENTS / 'DialogMotion.kt').read_text()
        preview = (COMPONENTS / 'PreviewDialog.kt').read_text()
        for text in (motion, preview):
            self.assertIn('remember(bottomSheet) { if (bottomSheet) BottomSheetMotion() else null }', text)
            self.assertIn('motion?.requestDismiss(BottomSheetDismissSource.System)', text)
            self.assertIn('DialogMotionProvider(motion,', text)
            self.assertIn('dialogDismissInputGuard()', text)
        self.assertIn('motion?.requestDismiss(BottomSheetDismissSource.Outside) ?: onOutsideDismiss()', preview)
        self.assertIn('onOutsideDismiss = onOutsideDismiss', preview)
        self.assertIn('if (bottomSheet) window?.setWindowAnimations(0) else window?.usePlatformDialogFade()', preview)

    def test_exit_blocks_pointer_and_accessibility_actions_without_unfocusing_window(self):
        text = (COMPONENTS / 'DialogMotion.kt').read_text()
        guard = text.partition('internal fun Modifier.dialogDismissInputGuard()')[2].split('\n@Composable', 1)[0]
        for token in ('clearAndSetSemantics', 'PointerEventPass.Initial', 'event.changes.forEach { it.consume() }'):
            self.assertIn(token, guard)
        self.assertNotIn('FLAG_NOT_FOCUSABLE', text)

    def test_internal_choice_motion_and_dim_share_the_window_exit(self):
        text = (COMPONENTS / 'DialogMotion.kt').read_text()
        self.assertIn('LocalBottomSheetMotion.current?.let { return it.progress }', text)
        self.assertIn('snapshotFlow { motion.scrimProgress.value }', text)
        self.assertIn('LocalBottomSheetMotion provides motion', text)
        self.assertIn('drawRect(color.copy(alpha = color.alpha * shared.scrimProgress.value))', text)
        choice = (COMPONENTS / 'DaiyuDialogs.kt').read_text()
        self.assertIn('animateContent = false', choice)
        self.assertIn('Modifier.dialogEnterMotion().align(Alignment.BottomCenter)', choice)


if __name__ == '__main__':
    unittest.main()
