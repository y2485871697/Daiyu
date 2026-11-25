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

    def test_document_follow_pins_content_end_inside_capped_viewport(self):
        ui = SRC.read_text()
        branch = ui.split('} else if (documentFollow && following && origin != null) {', 1)[1].split('} else {', 1)[0]
        # Pinning to the item origin left each new line below the edge until the next list scroll.
        self.assertIn('state.scrollOffset = if (state.contentHeight > viewport.height) maximum.toFloat() else 0f', branch)
        self.assertNotIn('state.scrollOffset = placed.toFloat()', branch)


    def test_capped_viewport_advances_pinned_end_in_the_growing_measure(self):
        ui = SRC.read_text()
        container = ui.split('internal fun StreamingTailContainer(', 1)[1].split('internal fun ChatMessageList(', 1)[0]
        inner = container.split('state.contentHeight = body.height + reserve', 1)
        self.assertEqual(len(inner), 2)
        # The outer extent sees H one frame late; the inner measure must move the pinned end itself.
        self.assertIn('val growth = state.contentHeight - previousHeight', inner[1].split('layout(body.width, height)', 1)[0])
        self.assertIn('state.scrollOffset = state.maxOffset.toFloat()', inner[1].split('layout(body.width, height)', 1)[0])
        self.assertIn('withoutReadObservation { state.contentHeight }', inner[0])
        self.assertIn('previousHeight >= currentViewportHeight && state.maxOffset > 0', inner[1])
        self.assertIn('state.bounded && growth < 0 && state.maxOffset > 0', inner[1])


if __name__ == '__main__':
    unittest.main()
