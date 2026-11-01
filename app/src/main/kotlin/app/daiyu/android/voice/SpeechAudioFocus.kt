package app.daiyu.android.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler

/** Platform focus for one prepared track; VoiceHost remains the application audio owner. */
internal class SpeechAudioFocus(
    private val context: Context,
    attributes: AudioAttributes,
    handler: Handler,
    onLoss: () -> Unit,
) {
    private val manager = checkNotNull(context.getSystemService(AudioManager::class.java))
    private var registered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!closed && requested) onLoss()
        }
    }
    private var requested = false
    private var closed = false
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (!closed && requested && change in setOf(AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) onLoss()
        }, handler)
        .build()

    fun acquire(): Boolean {
        check(!closed && !requested)
        requested = true
        // Keep the request releasable if the platform throws after accepting it.
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            requested = false
            return false
        }
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        return true
    }

    fun closeStrict() {
        if (closed) return
        closed = true
        val abandon = requested
        requested = false
        try {
            if (registered) { registered = false; context.unregisterReceiver(noisy) }
        } finally {
            if (abandon) check(manager.abandonAudioFocusRequest(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "speech_audio_focus_release_failed"
            }
        }
    }
}
