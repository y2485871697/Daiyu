"""Hot-path wiring guards; not a substitute for a physical frame measurement."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class ThinkingJankContract(unittest.TestCase):
    def test_reasoning_counts_are_lazy_until_engine_admission(self):
        forwarder = (APP / 'haptics/HapticsActivityHost.kt').read_text().split('internal class HapticsReasoningForwarder', 1)[1]
        self.assertEqual(2, forwarder.count('current.output(reasoning = true) {'))
        self.assertNotIn('current.output((graphemes', forwarder)
        self.assertIn('text[event.itemId] = next', forwarder)
        self.assertIn('current.reasoningCompleted(event.itemId)', forwarder)
        for path, call in [('haptics/HapticsHostBinding.kt', 'session.backgroundOutput(reasoning, graphemes)'),
                           ('haptics/HapticsRuntime.kt', 'engine.backgroundOutput(owner, reasoning, graphemes)')]:
            self.assertIn(call, (APP / path).read_text())
        engine = (ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain/haptics/HapticEngine.kt').read_text()
        lazy = engine.split('fun backgroundOutput(owner: Owner, reasoning: Boolean = false, graphemes: () -> Int)', 1)[1].split('fun toolAppeared', 1)[0]
        self.assertLess(lazy.index('backgroundVisible(host.conversation)'), lazy.index('graphemes()'))
        self.assertLess(lazy.index('!allowed(host, reasoning)'), lazy.index('graphemes()'))

    def test_inline_prefix_is_cached_by_actual_grapheme_end(self):
        markdown = (APP / 'ui/screens/chat/conversation/MessageMarkdown.kt').read_text()
        self.assertIn('boundaries.end(limit)', markdown)
        self.assertIn('val visible = remember(body, end)', markdown)
        self.assertIn('body.subSequence(0, end)', markdown)
        self.assertNotIn('body.subSequence(0, boundaries.end(limit))', markdown)

if __name__ == '__main__':
    unittest.main()
