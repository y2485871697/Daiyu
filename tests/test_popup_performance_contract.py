"""Narrow automatic selector wiring; runtime behavior is tested on the cloud JVM/device."""
from pathlib import Path
import unittest

APP = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android'


class PopupPerformanceContract(unittest.TestCase):
    def test_request_is_host_gated_and_does_not_masquerade_as_window_registration(self):
        activity = (APP / 'MainActivity.kt').read_text()
        assistant = activity.split('private fun openAssistantPicker(', 1)[1].split('private fun selectCurrentConversationAssistant(', 1)[0]
        model = activity.split('private fun openChatModels()', 1)[1].split('private fun dismissChatModels()', 1)[0]
        self.assertLess(assistant.index('requestPopup('), assistant.index('assistantPicker=true'))
        self.assertLess(model.index('requestPopup('), model.index('dismissChatModels()'))
        self.assertIn('Type.ASSISTANT_PICKER', assistant)
        self.assertIn('Type.MODEL_PICKER', model)
        window = (APP / 'diagnostics/WindowFramePerformance.kt').read_text()
        request = window.split('internal fun requestPopup(', 1)[1].split('private val contexts', 1)[0]
        self.assertIn('if (resumed && !closed) RuntimeDiagnostics.popupRequest(type)', request)
        self.assertNotIn('capture.start', request)
        runtime = (APP / 'diagnostics/RuntimeDiagnostics.kt').read_text()
        request = runtime.split('internal fun popupRequest(', 1)[1].split('internal fun popupBoundary(', 1)[0]
        for token in ('requestEpoch: Long = backEpoch()', 'at: Long = System.nanoTime()',
                      'requestEpoch != backGeneration.get()', 'requestEpoch == backGeneration.get()',
                      '"OPEN_REQUEST"', '.put("event_ns", at)'):
            self.assertIn(token, request)
        self.assertNotIn('sampling_start', request)
        self.assertNotIn('opened_ns', request)
        trace = (APP / 'diagnostics/ComposeSystemTrace.kt').read_text()
        self.assertEqual(1, trace.count('rootName = ::popupCompositionRoot'))
        sampler = (APP / 'diagnostics/BackCompositionSamples.kt').read_text()
        self.assertIn('if (depth == 0 && rootName?.invoke(name) == false) return', sampler)
        self.assertLess(sampler.index('rootName?.invoke(name)'), sampler.index('if (calls == MAX_CALLS'))
        self.assertIn('const val MAX_CALLS = 4096', sampler)

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
