package app.daiyu.android.voice.offline

internal object SpeechInputPolicy {
    fun visible(generating: Boolean, enabled: Boolean) = generating || enabled
    fun canEnable(ready: Boolean, checking: Boolean, downloading: Boolean) = ready && !checking && !downloading
    fun timedOut(elapsedMs: Long, hasText: Boolean) = elapsedMs >= 60_000 || (!hasText && elapsedMs >= 8_000)
}
