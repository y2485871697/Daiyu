package app.daiyu.android.voice.account

import android.content.Context

/** Parent-owned per-directory resources. Construct/load is local-only and never sends a request. */
internal class VoiceAccountResources(
    val voices: PersonalVoices,
    val preferences: VoiceCatalogPreferences,
    val transport: AccountHttpTransport,
) {
    val projects = VoiceProjectCatalog(transport)
    companion object {
        fun forContext(context: Context, transport: AccountHttpTransport = OkHttpAccountTransport()): VoiceAccountResources {
            return VoiceAccountResources(PersonalVoices.forContext(context, transport),
                VoiceCatalogPreferences(VoiceCatalogSecretStore(context)), transport)
        }
    }
}
