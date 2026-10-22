package app.daiyu.android.delegation

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.model.catalog.SavedModelCatalogSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SavedCatalogSubAgentModelSourceTest {
    private val provider = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val selection = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private fun snapshot(revision: Long = 1) = SavedModelCatalogSnapshot(provider, revision,
        listOf(SavedCatalogModel("wire/model-a", "Displayed model", CatalogModelParameters(localIdentity = selection))))

    @Test fun projectionKeepsThreeIdentitiesSeparateAndNeverGuessesCapabilities() {
        val row = snapshot().models.single().copy(id = "gpt-image-video-reasoner")
        val value = projectSubAgentProvider(snapshot().copy(models = listOf(row)), ProviderProtocol.RESPONSES)
        assertEquals(provider, value.id)
        val model = value.models.single()
        assertEquals(selection, model.id)
        assertEquals("gpt-image-video-reasoner", model.modelId)
        assertFalse(model.supportsImageGeneration); assertFalse(model.supportsVideoGeneration)
        assertFalse(model.supportsSpeechSynthesis); assertTrue(model.supportsGptSpeed)
        assertTrue(model.reasoningEfforts.isEmpty()); assertFalse(model.reasoningMandatory)
        assertFalse(model.reasoningCanDisable); assertFalse(model.mediaReasoningSupported); assertFalse(model.grokImage)
        assertEquals(value, projectSubAgentProvider(snapshot().copy(models = listOf(row)), ProviderProtocol.RESPONSES))
    }

    @Test fun gptSpeedFollowsTheRequestProtocolAndActualApiModel() {
        for ((api, expected) in listOf("gpt-6.1-sol" to true, "vendor/GPT-6" to true,
            "foo-gpt-6" to false, "claude-gpt" to false, "gemini" to false)) {
            for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS)) {
                val record = snapshot().models.single().copy(id = api, displayName = "gpt-display-only")
                val model = projectSubAgentProvider(snapshot().copy(models = listOf(record)), protocol).models.single()
                assertEquals(protocol == ProviderProtocol.RESPONSES && expected, model.supportsGptSpeed)
            }
        }
    }

    @Test fun sameDisplayNameAndCaseDistinctApiModelsKeepTheirSavedOrderAndIdentity() {
        val first = snapshot().models.single().copy(id = "Model-A", displayName = "Same")
        val second = first.copy(id = "model-a", settings = first.settings.copy(localIdentity = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"))
        val models = projectSubAgentProvider(snapshot().copy(models = listOf(first, second)), ProviderProtocol.RESPONSES).models
        assertEquals(listOf("Model-A", "model-a"), models.map { it.modelId })
        assertEquals(listOf(first.settings.localIdentity, second.settings.localIdentity), models.map { it.id })
    }

    @Test fun duplicateSelectionIdentityIsRejectedRatherThanAutoRebound() {
        assertThrows(IllegalArgumentException::class.java) {
            projectSubAgentProvider(snapshot().copy(models = snapshot().models + snapshot().models.single().copy(id = "other")), ProviderProtocol.RESPONSES)
        }
    }

    @Test fun invalidPerModelPolicyCannotBeSelectedForAnotherProtocol() {
        val record = snapshot().models.single()
        val bad = record.copy(settings = record.settings.copy(requestOptions = ProviderRequestOptions(speedMode = GptSpeedMode.FAST)))
        assertFalse(projectSubAgentProvider(snapshot().copy(models = listOf(bad)), ProviderProtocol.MESSAGES).models.single().isEnabled)
    }

    @Test fun refreshReadsOnlySuppliedSnapshotsAndClearsAnOlderSuccessOnFailure() = runBlocking {
        val source = SavedCatalogSubAgentModelSource()
        var reads = 0
        source.refresh({ reads++; snapshot() }, { ProviderProtocol.RESPONSES })
        assertEquals(2, reads)
        assertEquals(selection, source.models.single().id)
        source.refresh({ snapshot() }, { error("private synthetic connection failure") })
        assertTrue(source.providers.value.isEmpty())
        source.refresh({ null }, { fail("must not load connection without catalog"); ProviderProtocol.UNKNOWN })
        assertTrue(source.models.isEmpty())
    }

    @Test fun revisionOrProviderChangeDuringValidationIsRejected() = runBlocking {
        for (replacement in listOf(snapshot(2), snapshot().copy(providerIdentity = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))) {
            val source = SavedCatalogSubAgentModelSource()
            var reads = 0
            source.refresh({ if (++reads == 1) snapshot() else replacement }, { ProviderProtocol.RESPONSES })
            assertTrue(source.models.isEmpty())
        }
    }

    @Test fun leavingWhileReadIsPendingCannotRepublishProvider() = runBlocking {
        val source = SavedCatalogSubAgentModelSource()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            source.refresh({ snapshot() }, {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); ProviderProtocol.RESPONSES
            })
        }
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); source.clear() }
        finally { release.countDown() }
        job.join()
        assertTrue(source.models.isEmpty())
    }

    @Test fun cancellationIsPropagatedWithoutPublishingAProvider() = runBlocking {
        val source = SavedCatalogSubAgentModelSource()
        try {
            source.refresh({ snapshot() }, { throw CancellationException("synthetic cancellation") })
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        assertTrue(source.providers.value.isEmpty())
    }
    @Test fun slowerRefreshCannotOverwriteANewerSuccessOrFailure() = runBlocking {
        for (newFails in listOf(false, true)) {
            val source = SavedCatalogSubAgentModelSource()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val old = launch(start = CoroutineStart.UNDISPATCHED) {
                source.refresh({ snapshot() }, {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); ProviderProtocol.RESPONSES
                })
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                source.refresh({ snapshot(2) }, { if (newFails) error("new refresh failed") else ProviderProtocol.RESPONSES })
                val newest = source.providers.value
                assertEquals(!newFails, newest.isNotEmpty())
                release.countDown(); old.join()
                assertEquals(newest, source.providers.value)
            } finally { release.countDown(); old.join() }
        }
    }

    @Test fun cancellingARealPendingJobPreventsItsResultFromPublishing() = runBlocking {
        val source = SavedCatalogSubAgentModelSource()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            source.refresh({ snapshot() }, {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); ProviderProtocol.RESPONSES
            })
        }
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); job.cancel() }
        finally { release.countDown(); job.join() }
        assertTrue(job.isCancelled)
        assertTrue(source.providers.value.isEmpty())
    }

    @Test fun invalidSelectionDirectoryClearsTheOlderSuccessfulSnapshot() = runBlocking {
        val source = SavedCatalogSubAgentModelSource()
        source.refresh({ snapshot() }, { ProviderProtocol.RESPONSES })
        assertFalse(source.providers.value.isEmpty())
        val duplicate = snapshot().copy(models = snapshot().models + snapshot().models.single().copy(id = "other"))
        source.refresh({ duplicate }, { ProviderProtocol.RESPONSES })
        assertTrue(source.providers.value.isEmpty())
    }

}
