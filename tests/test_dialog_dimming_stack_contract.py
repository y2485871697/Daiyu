"""Wiring guards; composited-screen tests run in the approved Android batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class DialogDimmingStackContractTest(unittest.TestCase):
    def test_app_scope_is_shared_across_sibling_windows_and_nested_themes(self):
        theme = (SRC / 'ui/app/AgentAppTheme.kt').read_text()
        self.assertIn('LocalDialogDimStack.current', theme)
        self.assertIn('LocalDialogDimStack provides dialogDimStack', theme)

    def test_preview_owns_one_layer_until_the_window_leaves_composition(self):
        preview = (SRC / 'ui/components/PreviewDialog.kt').read_text()
        self.assertIn('previewDialogDimBackground(', preview)
        self.assertIn('LocalDialogDimStack provides dialogDimStack', preview)
        dimming = SRC / 'ui/components/DialogDimming.kt'
        self.assertTrue(dimming.is_file())
        source = dimming.read_text()
        self.assertIn('DisposableEffect(stack, layer)', source)
        self.assertIn('onDispose { stack.remove(layer) }', source)
        self.assertIn('currentMotion?.scrimProgress?.value ?: 1f', source)
        self.assertIn('stack.effectiveAlpha(layer)', source)
        self.assertIn('rememberUpdatedState(motion)', source)
        self.assertIn('val layer = remember(stack)', source)
        self.assertNotIn('remember(stack, motion)', source)
        self.assertNotIn('dismissSource', source)
        self.assertNotIn('WindowCompat', source)


if __name__ == '__main__':
    unittest.main()
