package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test

/** Tests the host's actual local transition/commit helpers; no transport, Android audio or gate. */
class ActivityRemoteVoiceHostTest {
    @Test fun rejectedUnitCommitKeepsDraftAndDoesNotClaimSuccess() {
        val original = "existing unsent draft"
        var draft = original
        var calls = 0
        val status = ActivityRemoteVoiceHost.finalDraftStatus("recognized text", { true }) {
            calls++
            // Mirrors Main's post-update check when pending-send prevents updateDraft.
            check(draft == "recognized text") { "draft_commit_rejected" }
        }
        assertEquals(1, calls)
        assertEquals(original, draft)
        assertTrue(status.contains("未填入草稿"))
        assertTrue(status.contains("原草稿已保留"))
        assertFalse(status.contains("已填入"))
    }

    @Test fun confirmedUnitCommitIsTheOnlySuccessPath() {
        var draft = "old draft"
        var calls = 0
        val status = ActivityRemoteVoiceHost.finalDraftStatus("recognized text", { true }) {
            calls++
            draft = "recognized text"
            check(draft == "recognized text")
        }
        assertEquals(1, calls)
        assertEquals("recognized text", draft)
        assertEquals("听写已填入草稿，请检查后手动发送", status)
    }

    @Test fun staleEditorDoesNotInvokeCommit() {
        var calls = 0
        val status = ActivityRemoteVoiceHost.finalDraftStatus("recognized text", { false }) { calls++ }
        assertEquals(0, calls)
        assertTrue(status.contains("原草稿已保留"))
        assertFalse(status.contains("已填入"))
    }

    @Test fun blankFinalDoesNotInvokeCommit() {
        var calls = 0
        val status = ActivityRemoteVoiceHost.finalDraftStatus("  \n", { true }) { calls++ }
        assertEquals(0, calls)
        assertTrue(status.contains("草稿未更改"))
        assertFalse(status.contains("已填入"))
    }

    @Test fun connectingSecondClickCancelsInsteadOfFinishingUnopenedAudio() {
        val input = ActivityRemoteVoiceHost.DictationInputState()
        var cancels = 0
        var finishes = 0
        var lateOpens = 0
        input.repeatClick(cancel = { cancels++; input.end() }, finish = { finishes++ })
        // Same fence used by Attempt.current() before audio.open on a late connection.
        if (!input.ended) { lateOpens++; input.capturing() }
        assertEquals(1, cancels)
        assertEquals(0, finishes)
        assertEquals(0, lateOpens)
        assertTrue(input.ended)
    }

    @Test fun captureSecondClickFinishesExactlyOnceAndWaitingClickIsNoOp() {
        val input = ActivityRemoteVoiceHost.DictationInputState()
        var cancels = 0
        var drains = 0
        input.capturing()
        repeat(2) {
            input.repeatClick(cancel = { cancels++ }, finish = {
                if (input.finishCapture()) drains++
            })
        }
        assertEquals(0, cancels)
        assertEquals(1, drains)
        assertTrue(input.finishing)
        assertFalse(input.ended)
    }

    @Test fun finishCaptureCannotCloseAudioDuringConnectionOrAfterEnd() {
        val input = ActivityRemoteVoiceHost.DictationInputState()
        assertFalse(input.finishCapture())
        assertFalse(input.finishing)
        input.end()
        assertFalse(input.finishCapture())
        input.repeatClick(cancel = { fail("ended input must not cancel again") },
            finish = { fail("ended input must not finish") })
        assertTrue(input.ended)
    }
}
