package app.daiyu.android.ui.screens.chat.conversation

import kotlin.math.PI
import kotlin.math.sin

/** The central entry is a hang-up while ANY call picture, including terminal/closing, exists. */
internal enum class CallEntryAction { START, HANG_UP, IGNORE_CLOSING }

internal fun callEntryAction(callUiPresent: Boolean, closing: Boolean): CallEntryAction = when {
    closing -> CallEntryAction.IGNORE_CLOSING
    callUiPresent -> CallEntryAction.HANG_UP
    else -> CallEntryAction.START
}

/** Rechecked by the activity, not just by the potentially stale composer's menu state. */
internal fun voiceModePickerAdmitted(callUiPresent: Boolean, duplexActive: Boolean): Boolean =
    !callUiPresent && !duplexActive

/**
 * RemoteVoiceHost shares a status stream between ASR and realtime calls. Terminal calls are
 * no longer duplexActive, so that flag cannot identify dictation notices. The retained snapshot
 * covers active/terminal sessions; the producer's realtime prefix also covers a queued terminal
 * status after the picture has been removed. A startup failure with no picture must stay visible.
 * Keep ASR errors unchanged, including errors published after their target has been released.
 */
internal fun remoteDictationNoticeStatus(status: String, hasCallSnapshot: Boolean): String =
    when {
        hasCallSnapshot -> ""
        status.trimStart().startsWith("双工未启动：") -> status
        status.trimStart().startsWith("双工") -> ""
        else -> status
    }

private fun callEntryPulse(progress: Float): Float {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    // Exact endpoints keep the original 20dp resting circle without a residual transform.
    return if (p == 0f || p == 1f) 0f else sin(p * PI).toFloat()
}

internal fun callEntryScale(progress: Float): Float = 1f + 0.45f * callEntryPulse(progress)
internal fun callEntryRotation(progress: Float): Float = 90f * callEntryPulse(progress)
