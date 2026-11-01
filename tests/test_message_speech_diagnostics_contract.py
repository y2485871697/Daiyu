"""Narrow source wiring checks; behavior runs in the cloud JVM suite."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"

class MessageSpeechDiagnosticsContractTest(unittest.TestCase):
    def test_production_entry_host_and_remote_share_existing_store(self):
        main = (SRC / "MainActivity.kt").read_text()
        self.assertIn("VoiceHost(lifecycleScope, audioGate, controller.diagnostics)", main)
        self.assertIn("RemoteVoiceHostPlayer(applicationContext, lifecycleScope, voiceRuntime, frozen, diagnostics = controller.diagnostics)", main)
        for stage in ("SPEECH_REQUEST_ACCEPTED", "SPEECH_CONFIGURATION_READY", "SPEECH_OBSERVER_CANCELLED", "SPEECH_HOST_SPEAKING", "SPEECH_UI_PREPARING"):
            self.assertIn("DiagnosticEvent." + stage, main)

    def test_remote_hooks_surround_existing_transport_and_native_calls(self):
        remote = (SRC / "RemoteVoiceHostPlayer.kt").read_text()
        self.assertLess(remote.index("DiagnosticEvent.SPEECH_HTTP_STARTED"), remote.index("transport.execute(request, maxBytes)"))
        self.assertLess(remote.index("transport.execute(request, maxBytes)"), remote.index("DiagnosticEvent.SPEECH_HTTP_COMPLETED"))
        self.assertLess(remote.index("source.start()"), remote.index("DiagnosticEvent.SPEECH_NATIVE_STARTED"))
        self.assertIn("diagnostics: DiagnosticLogStore? = null", remote)
        self.assertIn("PREPARATION_TIMEOUT_MS = 120_000L", remote)
        self.assertNotIn("recordAsync", (SRC / "ActivityVoiceAccountPreviewHost.kt").read_text())

    def test_fixed_schema_bounded_async_queue_and_no_caller_thread_fallback(self):
        store = (SRC / "diagnostics/DiagnosticLogStore.kt").read_text()
        self.assertIn("ArrayBlockingQueue(64)", store)
        self.assertIn("ThreadPoolExecutor.AbortPolicy()", store)
        self.assertNotIn("CallerRunsPolicy", store)
        self.assertIn("generation !== asyncGeneration", store)
        self.assertIn("DIAGNOSTIC_EVENTS_DROPPED", store)
        signature = store.split("fun recordAsync(", 1)[1].split(")", 1)[0]
        self.assertEqual("event: DiagnosticEvent", signature)
        self.assertIn('setOf("time_ms", "event")', store)
        self.assertIn("MAX_ENTRIES = 1024", store)
        self.assertIn("MAX_BYTES = 512 * 1024", store)

if __name__ == "__main__":
    unittest.main()
