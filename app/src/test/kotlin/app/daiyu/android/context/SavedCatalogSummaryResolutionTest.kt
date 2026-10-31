package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.catalog.*
import app.daiyu.android.model.oauth.CodexOAuthProtocol
import app.daiyu.android.storage.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavedCatalogSummaryResolutionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var store: ModelSettingsStore
    private lateinit var repository: ProviderRepository
    private lateinit var binding: SummaryModelBinding
    private lateinit var target: ProviderCatalogRunTarget

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val namespace = "summary-resolution-${UUID.randomUUID()}"
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = temporary.root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$namespace-$name", mode)
        }
        preferences = context.getSharedPreferences("model-settings", Context.MODE_PRIVATE)
        // A synthetic OAuth reference avoids a device keystore; no transport is opened.
        check(preferences.edit().putString("endpoint", CodexOAuthProtocol.BASE_URL)
            .putString("responses-path", "/responses").putString("model", "summary-model")
            .putString("oauth-reference", UUID.randomUUID().toString()).commit())
        store = ModelSettingsStore(context)
        val catalog = store.modelCatalog.register(listOf(SavedCatalogModel("summary-model", "Summary",
            CatalogModelParameters(contextWindowOverride = 32768))))
        repository = ProviderRepository(context, store)
        target = ProviderCatalogRunTarget(repository.activeEditor(), catalog.providerIdentity, catalog.revision,
            catalog.models.single().settings.localIdentity, "summary-model", ProviderProtocol.RESPONSES)
        binding = CatalogSummaryBindings.encode(target)
    }

    private fun appendOtherModel() {
        val catalog = checkNotNull(store.localModelCatalogSnapshot())
        assertEquals(CatalogCommitResult.COMMITTED, store.modelCatalog.compareAndAppend(catalog.providerIdentity,
            catalog.revision, listOf(SavedCatalogModel("other-model", "Other", CatalogModelParameters()))) { false })
    }

    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }

    @Test fun unrelatedRegistryAndCatalogChangesDoNotExpirePersistedSelection() {
        val before = preferences.all.toMap()
        repository.register(repository.snapshot().revision, "Unrelated provider")
        appendOtherModel()
        rejects<StaleProviderRegistryException> { repository.freezeCatalog(target) }
        val result = catalogSummaryConfiguration(context, binding)
        assertEquals(binding, result.binding)
        assertEquals("summary-model", result.provider.model)
        assertEquals(32768L, result.contextWindowTokens)
        assertEquals(before, preferences.all)
        assertEquals(ProviderRegistry.PRIMARY, repository.snapshot().activeSlotId)
        assertEquals(target, CatalogSummaryBindings.decode(binding))
    }

    @Test fun currentChatModelDoesNotReplaceTheSavedSummaryChoice() {
        appendOtherModel()
        store.modelCatalog.configurationChange {
            check(preferences.edit().putString("model", "other-model").commit())
        }
        assertEquals("other-model", store.load().model)
        assertEquals("summary-model", catalogSummaryConfiguration(context, binding).provider.model)
        assertEquals("other-model", store.load().model)
    }

    @Test fun selectedModelEditsApplyNextTimeButNeverMutateAnExistingFrozenConfiguration() {
        val frozen = catalogSummaryConfiguration(context, binding)
        val ticket = checkNotNull(store.localModelCatalogSnapshot()).selectionFor("summary-model")!!
        assertEquals(CatalogLocalEditResult.APPLIED, store.modelCatalog.replaceSelection(ticket,
            ticket.record.copy(displayName = "Renamed", settings = ticket.record.settings.copy(contextWindowOverride = 65536))) { false })
        rejects<IllegalStateException> { repository.freezeCatalog(target) }
        assertEquals(65536L, catalogSummaryConfiguration(context, binding).contextWindowTokens)
        assertEquals(32768L, frozen.contextWindowTokens)
        assertEquals(32768, frozen.provider.contextWindowOverride)
    }

    @Test fun deletedAndRecreatedSameApiModelDoesNotAcquireTheOldSelection() {
        val ticket = checkNotNull(store.localModelCatalogSnapshot()).selectionFor("summary-model")!!
        assertEquals(CatalogLocalEditResult.APPLIED, store.modelCatalog.deleteSelections(listOf(ticket)) { false })
        rejects<IllegalStateException> { catalogSummaryConfiguration(context, binding) }
        val catalog = checkNotNull(store.localModelCatalogSnapshot())
        assertEquals(CatalogCommitResult.COMMITTED, store.modelCatalog.compareAndAppend(catalog.providerIdentity,
            catalog.revision, listOf(SavedCatalogModel("summary-model", "Replacement", CatalogModelParameters(contextWindowOverride = 32768)))) { false })
        rejects<IllegalStateException> { catalogSummaryConfiguration(context, binding) }
    }

    @Test fun disabledProviderDoesNotResolveOrFallBack() {
        repository.setEnabled(repository.snapshot().revision, ProviderRegistry.PRIMARY, false)
        rejects<IllegalStateException> { catalogSummaryConfiguration(context, binding) }
    }

    @Test fun deletedProviderDoesNotResolveOrReappear() {
        repository.delete(repository.snapshot().revision, ProviderRegistry.PRIMARY)
        rejects<IllegalStateException> { catalogSummaryConfiguration(context, binding) }
        assertTrue(repository.snapshot().providers.isEmpty())
    }

    @Test fun replacedProviderIdentityIsNotJustARevisionRefresh() {
        store.modelCatalog.configurationChange {
            store.modelCatalog.replaceRegisteredProvider(listOf(SavedCatalogModel("summary-model", "Replacement",
                CatalogModelParameters(localIdentity = target.localIdentity, contextWindowOverride = 32768))))
        }
        rejects<IllegalStateException> { catalogSummaryConfiguration(context, binding) }
    }

    @Test fun protocolChangeStillRequiresReselection() {
        store.modelCatalog.configurationChange {
            check(preferences.edit().putString("protocol", "chat_completions").commit())
        }
        rejects<IllegalStateException> { catalogSummaryConfiguration(context, binding) }
    }

    @Test fun catalogDescriptorCapturesCurrentRevisionsAndRejectsChangesAfterConsent() {
        appendOtherModel()
        repository.rename(repository.snapshot().revision, ProviderRegistry.PRIMARY, "Renamed")
        val descriptor = catalogSummaryDescriptor(context, binding)
        assertEquals(binding, descriptor.binding)
        assertNotEquals(binding.providerId, descriptor.revision)
        assertEquals("summary-model", catalogSummaryConfiguration(context, descriptor).provider.model)
        store.modelCatalog.configurationChange { }
        rejects<SummaryProfileChangedException> { catalogSummaryConfiguration(context, descriptor) }
    }

    @Test fun manualSelectionRefreshesBeforeConsentButKeepsStrictOperationTicketAfterward() {
        appendOtherModel()
        repository.register(repository.snapshot().revision, "Unrelated")
        val descriptor = ManualSummaryTargets.describe(repository,
            ContextManagementSelection(binding, ProviderProtocol.RESPONSES))
        assertTrue(ManualSummaryTargets.isManual(descriptor.binding))
        assertEquals("summary-model", ManualSummaryTargets.configuration(repository, descriptor).provider.model)
        store.modelCatalog.configurationChange { }
        rejects<SummaryProfileChangedException> { ManualSummaryTargets.configuration(repository, descriptor) }
    }
}
