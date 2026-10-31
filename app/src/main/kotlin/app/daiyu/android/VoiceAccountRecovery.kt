package app.daiyu.android

import app.daiyu.android.voice.account.VoiceAccountIdentity

/** Restore the personal-account entry only; playback profiles and pending legacy selection stay intact. */
internal fun VoiceProfileEnvelope.recoverPersonalAccounts(): VoiceProfileEnvelope {
    val recovered = accounts.toMutableMap()
    for (engine in listOf("DOUBAO", "MIMO")) {
        if (engine in recovered) continue
        val candidates = (profiles.values + listOfNotNull(legacy))
            .filter { it.engine == engine && it.key.isNotBlank() }
        val identities = candidates.groupBy {
            if (engine == "DOUBAO") VoiceAccountIdentity.from(it.speech()).fingerprint else it.referenceOwner()
        }
        if (identities.size == 1) recovered[engine] = identities.values.single().first()
    }
    return if (recovered == accounts) this else VoiceProfileEnvelope(profiles, legacy, recovered)
}
