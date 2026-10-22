package app.daiyu.android

import android.content.Context

/** These switches gate existing explicit entries; changing them never starts audio. */
internal class VoiceSettingsPreferences(context: Context) {
    private val store = context.getSharedPreferences("voice-settings", Context.MODE_PRIVATE)
    fun enabled(feature: String) = store.getBoolean(feature, feature != "input")
    fun setEnabled(feature: String, enabled: Boolean) {
        check(store.edit().putBoolean(feature, enabled).commit())
    }
    fun cloudEnabled(profile: VoiceProfile, default: Boolean) = store.getBoolean("cloud-enabled:${profile.name}", default)
    fun setCloudEnabled(profile: VoiceProfile, enabled: Boolean) {
        check(store.edit().putBoolean("cloud-enabled:${profile.name}", enabled).commit())
    }
    fun cloudEngine(profile: VoiceProfile): String? = store.getString("cloud-engine:${profile.name}", null)
    fun cloudVoice(profile: VoiceProfile, owner: String, model: String): String =
        store.getString("cloud-voice:${profile.name}:$owner:$model", "").orEmpty()
    fun rememberCloud(profile: VoiceProfile, configuration: VoiceConfiguration) {
        if (configuration.engine == "SYSTEM") return
        check(store.edit().putString("cloud-engine:${profile.name}", configuration.engine)
            .putString("cloud-voice:${profile.name}:${configuration.referenceOwner()}:${configuration.historyModel}", configuration.voice).commit())
    }
}
