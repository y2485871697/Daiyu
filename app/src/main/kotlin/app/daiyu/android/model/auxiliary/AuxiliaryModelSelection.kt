package app.daiyu.android.model.auxiliary

import app.daiyu.android.model.ProviderSnapshot

/** Host registry identities, not display names or the main model's defaults. */
internal data class AuxiliaryModelSelection(
    val enabled: Boolean,
    val providerId: String,
    val modelId: String,
) {
    /** Resolve once per run against an already captured registry. There is deliberately no fallback. */
    fun resolve(candidates: List<AuxiliaryModelCandidate>): AuxiliaryModelBinding {
        check(enabled) { "Auxiliary vision is disabled" }
        require(providerId.isNotBlank() && modelId.isNotBlank()) { "Select an auxiliary vision model" }
        val matches = candidates.filter { it.providerId == providerId && it.modelId == modelId }
        require(matches.size == 1) { "Auxiliary vision binding is missing or ambiguous" }
        val candidate = matches.single()
        require(candidate.providerEnabled && candidate.modelEnabled) { "Auxiliary vision model is unavailable" }
        require(candidate.snapshot.imageInputSupported == true) { "Auxiliary vision requires verified image input support" }
        return AuxiliaryModelBinding(providerId, modelId, candidate.snapshot)
    }
}

/** The host must capture credentials, endpoint/protocol/API model and capability from this exact record. */
internal data class AuxiliaryModelCandidate(
    val providerId: String,
    val modelId: String,
    val snapshot: ProviderSnapshot,
    val providerEnabled: Boolean = true,
    val modelEnabled: Boolean = true,
)

internal class AuxiliaryModelBinding internal constructor(
    val providerId: String,
    val modelId: String,
    val snapshot: ProviderSnapshot,
)
