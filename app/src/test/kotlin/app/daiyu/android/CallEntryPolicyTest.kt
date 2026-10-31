package app.daiyu.android

import app.daiyu.android.ui.screens.chat.conversation.*
import app.daiyu.android.voice.VoiceOwner
import app.daiyu.android.voice.duplex.DuplexPhase
import app.daiyu.android.voice.duplex.DuplexState
import org.junit.Assert.*
import org.junit.Test

class CallEntryPolicyTest {
    private fun snapshot(phase: DuplexPhase) = DuplexCallSnapshot(
        sessionKey = 7, conversationId = "chat",
        state = DuplexState(VoiceOwner("chat", "call"), phase, "最后的字幕", "最后的回复"),
        startedAtMillis = 100,
    )

    @Test fun centralClickClosesExpandedMiniAndTerminalPicturesExactlyOnce() {
        for (phase in listOf(DuplexPhase.LISTENING, DuplexPhase.FAILED, DuplexPhase.CANCELLED)) {
            for (mini in listOf(false, true)) {
                val owner = DoubaoCallPresentationOwner()
                owner.update(snapshot(phase))
                if (mini) owner.minimize(7)
                var stops = 0
                fun click() {
                    val picture = owner.state
                    when (callEntryAction(picture != null, picture?.presentation == DoubaoCallPresentation.CLOSING)) {
                        CallEntryAction.HANG_UP -> if (owner.beginClose(picture!!.sessionKey, 900)) {
                            // Simulate the real synchronous backend publication after the freeze.
                            assertEquals(DoubaoCallPresentation.CLOSING, owner.state!!.presentation)
                            stops++
                            owner.update(snapshot(DuplexPhase.CANCELLED).copy(
                                state = snapshot(DuplexPhase.CANCELLED).state.copy(transcript = "", reply = "")))
                            owner.update(null)
                        }
                        CallEntryAction.IGNORE_CLOSING -> Unit
                        CallEntryAction.START -> fail("A retained picture must never start a new call")
                    }
                }
                click()
                val frozen = owner.state
                repeat(3) { click() }
                assertEquals(1, stops)
                assertEquals(frozen, owner.state)
                assertEquals("最后的字幕", owner.state!!.transcript)
                assertFalse(voiceModePickerAdmitted(owner.state != null, false))
                owner.finishClose(7)
                assertEquals(CallEntryAction.START, callEntryAction(owner.state != null, false))
                assertTrue(voiceModePickerAdmitted(owner.state != null, false))
            }
        }
    }

    @Test fun longPressAdmissionRechecksPresenceNotJustBackendActivity() {
        assertEquals(CallEntryAction.START, callEntryAction(false, false))
        assertEquals(CallEntryAction.HANG_UP, callEntryAction(true, false))
        assertTrue(voiceModePickerAdmitted(false, false))
        assertFalse(voiceModePickerAdmitted(false, true)) // UI has not observed the call yet.
        assertFalse(voiceModePickerAdmitted(true, true))
        assertFalse(voiceModePickerAdmitted(true, false)) // Terminal or closing, audio already stopped.
        assertEquals(CallEntryAction.IGNORE_CLOSING, callEntryAction(true, true))
    }

    @Test fun miniArrowStillRestoresWithoutClosingOrReplacingTheSession() {
        val owner = DoubaoCallPresentationOwner()
        owner.update(snapshot(DuplexPhase.LISTENING)); owner.minimize(7); owner.expand(7)
        assertEquals(DoubaoCallPresentation.EXPANDED, owner.state!!.presentation)
        assertEquals(7L, owner.state!!.sessionKey)
        assertNull(owner.state!!.endedAtMillis)
    }

    @Test fun callMotionPulsesAndRetracesTheSameProgressWithExactRestEndpoints() {
        for (endpoint in listOf(0f, 1f)) {
            assertEquals(1f, callEntryScale(endpoint), 0f)
            assertEquals(0f, callEntryRotation(endpoint), 0f)
        }
        assertEquals(1.45f, callEntryScale(.5f), .0001f)
        assertEquals(90f, callEntryRotation(.5f), .0001f)
        assertTrue(callEntryScale(.25f) > callEntryScale(0f))
        assertTrue(callEntryRotation(.25f) > callEntryRotation(0f))
        for (progress in listOf(.1f, .25f, .5f, .75f, .9f)) {
            assertEquals(callEntryScale(progress), callEntryScale(1f - progress), .0001f)
            assertEquals(callEntryRotation(progress), callEntryRotation(1f - progress), .0001f)
        }
        for (invalid in listOf(-1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(1f, callEntryScale(invalid), 0f)
            assertEquals(0f, callEntryRotation(invalid), 0f)
        }
    }

    @Test fun realtimeStatusNoticesNeverEscapeAsDictationIncludingAfterClose() {
        for (status in listOf("双工失败，已停止；检查独立配置后手动重试", "双工 CANCELLED\n\n",
            "双工 LISTENING\n字幕\n回复", "双工结果未确认")) {
            assertEquals("", remoteDictationNoticeStatus(status, true))
            assertEquals("", remoteDictationNoticeStatus(status, false))
        }
        assertEquals("", remoteDictationNoticeStatus("未分类通话状态", true))
    }

    @Test fun startupFailureWithoutAPictureStillExplainsWhyTheCallDidNotStart() {
        val status = "双工未启动：检查独立密钥、麦克风权限或音频占用"
        assertEquals(status, remoteDictationNoticeStatus(status, false))
        assertEquals("", remoteDictationNoticeStatus(status, true))
    }

    @Test fun ordinaryDictationAndPermissionFailuresAreNotGloballySilenced() {
        for (status in listOf("", "豆包听写未启动：检查独立语音设置或音频占用", "语音资源释放未确认",
            "需要麦克风权限；授权后请再次点击开始", "系统语音识别服务不可用", "豆包听写：双工 CANCELLED")) {
            assertEquals(status, remoteDictationNoticeStatus(status, false))
        }
    }
}
