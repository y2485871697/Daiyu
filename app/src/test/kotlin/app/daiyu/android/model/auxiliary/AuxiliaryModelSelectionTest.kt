package app.daiyu.android.model.auxiliary

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

class AuxiliaryModelSelectionTest {
    private fun candidate(provider: String = "aux", model: String = "vision", capability: Boolean? = true) =
        AuxiliaryModelCandidate(provider, model, ProviderSnapshot.capture(
            ModelSettings("https://example.invalid/v1/responses", "api-$model", "fixture-key", false),
            ProviderProtocol.RESPONSES, capability))
    private val selection = AuxiliaryModelSelection(true, "aux", "vision")
    private fun reject(work: () -> Unit) { assertNotNull(runCatching(work).exceptionOrNull()) }

    @Test fun bindsOnlyExactProviderAndRegistryModelNotFirstOrMain() {
        val main = candidate("main", "text")
        val selected = candidate()
        val result = selection.resolve(listOf(main, candidate("other"), selected))
        assertSame(selected.snapshot, result.snapshot)
        assertEquals("aux", result.providerId)
        assertEquals("vision", result.modelId)
        assertEquals("api-vision", result.snapshot.model)
    }

    @Test fun absentDisabledUnknownAndAmbiguousBindingsNeverFallBack() {
        val main = candidate("main", "text")
        reject { selection.resolve(listOf(main)) }
        reject { selection.copy(enabled = false).resolve(listOf(candidate(), main)) }
        reject { selection.copy(providerId = "").resolve(listOf(candidate(), main)) }
        reject { selection.resolve(listOf(candidate().copy(providerEnabled = false), main)) }
        reject { selection.resolve(listOf(candidate().copy(modelEnabled = false), main)) }
        reject { selection.resolve(listOf(candidate(capability = null), main)) }
        reject { selection.resolve(listOf(candidate(capability = false), main)) }
        reject { selection.resolve(listOf(candidate(), candidate(), main)) }
    }
}
