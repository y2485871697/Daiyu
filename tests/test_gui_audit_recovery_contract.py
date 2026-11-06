from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
K = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class GuiAuditRecoveryContract(unittest.TestCase):
    def test_gestures_use_accessibility_and_keep_lease_until_callback(self):
        service = ET.parse(ROOT / 'app/src/main/res/xml/gui_accessibility_service.xml').getroot()
        self.assertEqual('true', service.get('{http://schemas.android.com/apk/res/android}canPerformGestures'))
        runtime = (K / 'gui/GuiAccessibilityService.kt').read_text()
        session = (K / 'gui/GuiRunSession.kt').read_text()
        self.assertIn('current.dispatchGesture(request,', runtime)
        self.assertIn('gestures.containsKey(lease)', runtime)
        self.assertIn('GESTURE_CANCELLED_UNCONFIRMED', runtime)
        self.assertIn('awaitAction(it, cancelled)', session)
        self.assertIn('IME_SUBMIT_UNSUPPORTED_API_NOT_EXECUTED', runtime)
        self.assertIn('node.actionList.none', runtime)

    def test_expanded_tree_is_fresh_bounded_and_reverified_with_same_limits(self):
        runtime = (K / 'gui/GuiAccessibilityService.kt').read_text()
        factory = (K / 'gui/GuiScreenToolFactory.kt').read_text()
        self.assertIn('require(maxNodes in 128..512)', runtime)
        self.assertIn('maxNodes = observed.maxNodes', runtime)
        self.assertIn('count > observed.maxNodes', runtime)
        self.assertIn('observe_again_with_max_nodes_512', factory)
        self.assertIn('ArgumentType.Integer(0, 511)', factory)

    def test_foreign_window_content_only_preserves_exact_external_nodes(self):
        runtime = (K / 'gui/GuiAccessibilityService.kt').read_text()
        start = runtime.index('val keepExternalNodes =')
        end = runtime.index('if (observed != null && observed.generation', start)
        scoped = runtime[start:end]
        self.assertIn('event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED', scoped)
        self.assertIn('observed.windowId >= 0 && otherWindow', scoped)
        self.assertIn('!isSelfLease(lease) && retained.isNotEmpty()', scoped)
        self.assertIn('contentChanged = true', scoped)
        self.assertNotIn('generation++', scoped)
        self.assertIn('changed(value, contentOnly = localContent,', scoped)
        self.assertIn('invalidateVisual = !content || lease == null || isSelfLease(lease)', scoped)
        self.assertIn('if (contentChanged && action.nodeId == null)', runtime)
        self.assertIn('if (contentChanged) return recordObservationRejection', runtime)

    def test_visual_freshness_is_separate_from_node_content_but_hard_clears_still_fence(self):
        runtime = (K / 'gui/GuiAccessibilityService.kt').read_text()
        self.assertIn('private var visualGeneration = 0L', runtime)
        self.assertIn('changed(value, contentOnly, invalidateVisual = true)', runtime)
        self.assertIn('generation++; clearNodes(invalidateVisual)', runtime)
        self.assertIn('if (invalidateVisual) { visualGeneration++; visualCapture = null }', runtime)
        self.assertIn('gate.validateObservation(lease, id, generation)', runtime)
        visual = runtime[runtime.index('internal fun visualScreenshot('):runtime.index('internal fun screenshot(')]
        self.assertIn('captured.visualGeneration != visualGeneration', visual)
        self.assertIn('val startVisualGeneration = visualGeneration', visual)
        self.assertIn('visualGeneration != startVisualGeneration', visual)
        self.assertNotIn('captured.generation', visual)
        self.assertIn('finalWindow != initial', visual)
        self.assertIn('CAPTURE_CONTENT_CHANGED_NOT_EXECUTED', visual)
        self.assertIn('GUI_OBSERVATION_TTL_MILLIS', visual)

    def test_repeatable_flag_is_opt_in_and_durably_saved(self):
        scope = (K / 'integration/tools/RunOrdinaryPlatformTools.kt').read_text()
        store = (K / 'integration/tools/SQLiteToolLedgerStore.kt').read_text()
        ledger = (ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain/tools/ToolLedger.kt').read_text()
        self.assertIn('repeatConfirmed = true', scope)
        self.assertIn('put("repeatable", if (entry.repeatable) 1 else 0)', store)
        self.assertIn('ADD COLUMN repeatable INTEGER NOT NULL DEFAULT 0', store)
        self.assertIn('it.state == LedgerState.UNKNOWN', ledger)
        self.assertIn('if (request.repeatConfirmed) null', ledger)


if __name__ == '__main__':
    unittest.main()
