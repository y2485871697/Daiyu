import pathlib
import unittest

SRC = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageList.kt'


class BoundedTailGrowthContractTest(unittest.TestCase):
    def test_capped_viewport_content_growth_runs_same_frame_correction(self):
        ui = SRC.read_text()
        container = ui.split('internal fun StreamingTailContainer(', 1)[1].split('internal fun ChatMessageList(', 1)[0]
        self.assertIn('onContentPositioned: (() -> Unit)? = null', container)
        # Content growth inside a fixed-height viewport must report itself before draw.
        self.assertIn('if (it.isAttached) onContentPositioned()', container)
        self.assertIn('onContentPositioned = { if (turn.listKey == currentTailKey) correctFollowClearance() }', ui)


if __name__ == '__main__':
    unittest.main()
