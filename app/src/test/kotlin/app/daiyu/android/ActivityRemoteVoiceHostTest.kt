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
        assertEquals("听写草稿保存失败", status)
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
        assertEquals("", status)
    }

    @Test fun staleEditorDoesNotInvokeCommit() {
        var calls = 0
        val status = ActivityRemoteVoiceHost.finalDraftStatus("recognized text", { false }) { calls++ }
        assertEquals(0, calls)
        assertEquals("听写草稿保存失败", status)
        assertFalse(status.contains("已填入"))
    }

    @Test fun blankFinalDoesNotInvokeCommit() {
        var calls = 0
        val status = ActivityRemoteVoiceHost.finalDraftStatus("  \n", { true }) { calls++ }
        assertEquals(0, calls)
        assertEquals("未识别到有效内容", status)
        assertFalse(status.contains("已填入"))
    }

    @Test fun connectingSecondClickCancelsInsteadOfOpeningAudio() {
        val input = ActivityRemoteVoiceHost.DictationInputState()
        var cancels = 0
        input.repeatClick { cancels++; input.end() }
        assertEquals(1, cancels)
        assertTrue(input.ended)
    }

    @Test fun captureSecondClickCancelsOnce() {
        val input = ActivityRemoteVoiceHost.DictationInputState()
        var cancels = 0
        input.capturing()
        repeat(2) { input.repeatClick { cancels++; input.end() } }
        assertEquals(1, cancels)
        assertTrue(input.ended)
    }

    @Test fun endedInputCannotCancelOrResume() {
        val input = ActivityRemoteVoiceHost.DictationInputState()
        input.end()
        input.repeatClick { fail("ended input must not cancel again") }
        assertTrue(input.ended)
    }
}
