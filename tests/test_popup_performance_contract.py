"""Narrow automatic selector wiring; runtime behavior is tested on the cloud JVM/device."""
from pathlib import Path
import unittest

APP = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android'


class PopupPerformanceContract(unittest.TestCase):
    def test_only_fixed_selector_calls_opt_into_the_shared_dialog(self):
        for path, kind in (
            ('ui/screens/assistants/AssistantPickerDialogs.kt', 'ASSISTANT_PICKER'),
            ('ui/screens/chat/conversation/ChatModelPicker.kt', 'MODEL_PICKER'),
        ):
            source = (APP / path).read_text()
            self.assertEqual(1, source.count('PopupPerformanceCapture.Type.' + kind))
        for path in ('ui/components/PreviewDialog.kt', 'ui/screens/assistants/AssistantPreviewComponents.kt',
                     'ui/screens/chat/conversation/ChatSelectionSheet.kt'):
            source = (APP / path).read_text()
            self.assertIn('automaticPopup: app.daiyu.android.diagnostics.PopupPerformanceCapture.Type? = null', source)
            self.assertNotIn('title.contains', source)
        window = (APP / 'diagnostics/WindowFramePerformance.kt').read_text()
        self.assertIn('DisposableEffect(host, window, kind, automaticPopup)', window)
        self.assertIn('host?.unregisterWindow(window, lease)', window)
        self.assertIn('if (popupLeases[window] !== lease) return', window)
        self.assertIn('else if (popupLeases.containsKey(window)) return', window)
        self.assertEqual(1, window.count('HandlerThread("daiyu-frame-metrics")'))
        self.assertIn('if (!diagnostic && kind != "chat-scroll") return', window)
        self.assertIn('NON_DRAG_SCROLL', window)
        self.assertIn('!PopupPerformance.capture.observing(this, window)) return', window)

    def test_short_owner_epoch_guard_and_independent_composer_stacks(self):
        source = (APP / 'diagnostics/PopupPerformance.kt').read_text()
        for token in ('schedule(4000)', 'if (active !== lease) return', 'if (lease.closed) return',
                      'lease.token.epoch == epoch()', 'lease.window === window',
                      'sourceToken = lease.token', 'BackPerformanceRecorder.End.SUPERSEDED'):
            self.assertIn(token, source)
        self.assertNotIn('recorder.commit(', source)
        self.assertNotIn('BuildConfig', source)
        trace = (APP / 'diagnostics/ComposeSystemTrace.kt').read_text()
        self.assertIn('private val popupSamples = BackCompositionSamples(', trace)
        self.assertIn('popupSamples.start(PopupPerformance.capture.recorder.compositionToken(), info)', trace)
        self.assertIn('popupSamples.end(PopupPerformance.capture.recorder.compositionToken())', trace)
        runtime = (APP / 'diagnostics/RuntimeDiagnostics.kt').read_text()
        self.assertIn('"POPUP_PERFORMANCE"', runtime)
        self.assertIn('"WINDOW_REGISTERED"', runtime)
        self.assertIn('if (popupOpened != null)', runtime)
        self.assertIn('boundary.epoch == backGeneration.get()', runtime)
        self.assertIn('ArrayBlockingQueue(128)', runtime)
