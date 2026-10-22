package app.daiyu.android

import android.app.Application
import android.os.Looper
import app.daiyu.android.voice.account.AccountPreviewRequest
import app.daiyu.android.voice.account.PersonalVoices
import app.daiyu.android.voice.account.VoiceAccountPreviewPort
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceAccountPanelLeafTest {
    private val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", "key", "seed-tts-2.0", "saved")
    private fun voice() = PersonalVoices.Voice("S_one", "one", DoubaoPersonalVoice.accountFingerprint("key"),
        status = 2, models = setOf(5), accepted = true, demo = "https://example.test/never-fetch")

    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun everyAvailabilityOperationStrictlyStopsLoadingAndPlayingOnMainBeforeItsBlock() = runTest {
        for (started in listOf(false, true)) {
            for (operation in listOf("training", "query", "import")) {
                val events = mutableListOf<String>()
                val guard = AccountLeafPreviewGuard(VoiceAccountPreviewPort { _, listener ->
                    if (started) listener.started()
                    VoiceAccountPreviewPort.Lease {
                        assertEquals(Looper.getMainLooper(), Looper.myLooper())
                        events += "stop"
                        // Deliberately no stopped()/new play event: execute may not wait for one.
                    }
                })
                guard.preview.toggle(config, voice())
                assertEquals(!started, guard.preview.state.value.loading)
                guard.execute({ true }, {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    assertFalse(guard.preview.state.value.active)
                    events += "host-proof"
                    true
                }) {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    events += operation
                }
                assertEquals(listOf("stop", "host-proof", operation), events)
            }
        }
    }

    @Test fun swallowedLeaseStopFailurePermanentlyRejectsFollowingOperations() = runTest {
        var stops = 0
        var calls = 0
        var proofs = 0
        val guard = AccountLeafPreviewGuard(VoiceAccountPreviewPort { _, _ ->
            VoiceAccountPreviewPort.Lease { stops++; error("strict output shutdown failed") }
        })
        guard.preview.toggle(config, voice())
        for (operation in listOf("training", "query", "import")) {
            assertTrue(operation, runCatching {
                guard.execute({ true }, { proofs++; true }) { calls++ }
            }.isFailure)
        }
        assertEquals(1, stops)
        assertEquals(0, calls)
        assertEquals(0, proofs)
        assertTrue(guard.stopUnconfirmed)
        assertFalse(guard.preview.state.value.active)
        // A cleared leaf state must not turn a failed stop into permission to start again.
        guard.preview.toggle(config, voice())
        assertFalse(guard.preview.state.value.active)
        assertEquals(1, stops)
    }

    @Test fun hostFalseThrowAndDefaultAreFailClosedEvenIfLeafHasNoLease() = runTest {
        for (mode in listOf("false", "throw", "default")) {
            var calls = 0
            val guard = AccountLeafPreviewGuard(VoiceAccountPreviewPort { _, _ -> error("must not play") })
            val result = runCatching {
                if (mode == "default") guard.execute({ true }) { calls++ }
                else guard.execute({ true }, {
                    if (mode == "throw") error("host stop failed")
                    false
                }) { calls++ }
            }
            assertTrue(mode, result.isFailure)
            // Once a stop proof failed, a later success cannot silently clear this leaf's refusal.
            assertTrue(runCatching { guard.execute({ true }, { true }) { calls++ } }.isFailure)
            assertEquals(0, calls)
        }
    }

    @Test fun alreadySwallowedHostFailureStillNeedsAnExplicitHostProof() = runTest {
        var calls = 0
        var stopped = false
        val guard = AccountLeafPreviewGuard(VoiceAccountPreviewPort { _, _ ->
            VoiceAccountPreviewPort.Lease { stopped = true /* host may have swallowed its own failure */ }
        })
        guard.preview.toggle(config, voice())
        assertTrue(runCatching {
            guard.execute({ true }, { assertTrue(stopped); false }) { calls++ }
        }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun configurationInvalidatedByStopNeverStartsAnOperation() = runTest {
        var current = true
        var calls = 0
        val guard = AccountLeafPreviewGuard(VoiceAccountPreviewPort { _, _ ->
            VoiceAccountPreviewPort.Lease { current = false }
        })
        guard.preview.toggle(config, voice())
        assertTrue(runCatching { guard.execute({ current }, { true }) { calls++ } }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun eachConfirmedPreviewCreatesANewRequestIdentityAfterStrictStop() = runTest {
        val requests = mutableListOf<AccountPreviewRequest>()
        var stops = 0
        val guard = AccountLeafPreviewGuard(VoiceAccountPreviewPort { request, _ ->
            requests += request
            VoiceAccountPreviewPort.Lease { stops++ }
        })
        // These blocks are what the panel's one-shot pending confirmation invokes, not render/select.
        assertTrue(requests.isEmpty())
        val metadataVoice = voice().copy(accepted = false)
        repeat(2) {
            guard.execute({ true }, { true }) { guard.preview.toggle(config, metadataVoice) }
        }
        assertEquals(2, requests.size)
        assertNotSame(requests[0], requests[1])
        assertEquals(1, stops)
        requests.forEach {
            assertTrue(it.demoOnly)
            assertFalse(it.voice.accepted)
            assertEquals(metadataVoice.id, it.voice.id)
            assertEquals(metadataVoice.account, it.voice.account)
            assertEquals(metadataVoice.demo, it.demoUrl)
        }
    }
}
