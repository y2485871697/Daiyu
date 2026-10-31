package app.daiyu.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallPresentation
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallUi
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallVisualState
import app.daiyu.android.voice.duplex.DuplexPhase

/** Keeps the departing picture independent of the already stopped call. */
internal class DoubaoCallPresentationOwner {
    var state by mutableStateOf<DoubaoCallUi?>(null)
        private set

    fun update(snapshot: DuplexCallSnapshot?) {
        val previous = state
        if (previous?.presentation == DoubaoCallPresentation.CLOSING &&
            (snapshot == null || snapshot.sessionKey == previous.sessionKey)) return
        if (snapshot == null) { state = null; return }
        val sameCall = previous?.sessionKey == snapshot.sessionKey
        state = DoubaoCallUi(
            sessionKey = snapshot.sessionKey,
            conversationKey = snapshot.conversationId,
            presentation = if (sameCall) previous!!.presentation else DoubaoCallPresentation.EXPANDED,
            visualState = when (snapshot.state.phase) {
                DuplexPhase.IDLE, DuplexPhase.CONNECTING -> DoubaoCallVisualState.CONNECTING
                DuplexPhase.LISTENING -> if (snapshot.muted) DoubaoCallVisualState.MUTED else DoubaoCallVisualState.LISTENING
                DuplexPhase.THINKING, DuplexPhase.SPEAKING -> DoubaoCallVisualState.RESPONDING
                DuplexPhase.COMPLETE, DuplexPhase.CANCELLED -> DoubaoCallVisualState.ENDED
                DuplexPhase.FAILED, DuplexPhase.UNKNOWN -> DoubaoCallVisualState.ERROR
            },
            startedAtMillis = snapshot.startedAtMillis,
            endedAtMillis = snapshot.endedAtMillis,
            transcript = snapshot.state.transcript,
            reply = snapshot.state.reply,
            reason = snapshot.state.reason,
            muted = snapshot.muted,
            speaker = snapshot.speaker,
            captionsEnabled = if (sameCall) previous!!.captionsEnabled else true,
        )
    }

    /** Ordinary edits minimize a live call, but never remove its departing picture. */
    fun onDraftChanged(conversationKey: String?, duplexActive: Boolean): Boolean {
        val current = state ?: return duplexActive
        if (current.conversationKey != conversationKey) return false
        if (duplexActive && current.presentation == DoubaoCallPresentation.EXPANDED)
            state = current.copy(presentation = DoubaoCallPresentation.MINI)
        return duplexActive || current.presentation == DoubaoCallPresentation.CLOSING
    }

    fun expand(key: Long) = changePresentation(key, DoubaoCallPresentation.EXPANDED)
    fun minimize(key: Long) = changePresentation(key, DoubaoCallPresentation.MINI)

    private fun changePresentation(key: Long, presentation: DoubaoCallPresentation) {
        val current = state ?: return
        if (current.sessionKey == key && current.presentation != DoubaoCallPresentation.CLOSING)
            state = current.copy(presentation = presentation)
    }

    fun toggleCaptions(key: Long) {
        val current = state ?: return
        if (current.sessionKey == key && current.presentation != DoubaoCallPresentation.CLOSING)
            state = current.copy(captionsEnabled = !current.captionsEnabled)
    }

    fun beginClose(key: Long, nowMillis: Long): Boolean {
        val current = state ?: return false
        if (current.sessionKey != key || current.presentation == DoubaoCallPresentation.CLOSING) return false
        state = current.copy(presentation = DoubaoCallPresentation.CLOSING,
            endedAtMillis = current.endedAtMillis ?: nowMillis)
        return true
    }

    fun finishClose(key: Long) {
        if (state?.sessionKey == key && state?.presentation == DoubaoCallPresentation.CLOSING) state = null
    }

    fun clear() { state = null }
}
