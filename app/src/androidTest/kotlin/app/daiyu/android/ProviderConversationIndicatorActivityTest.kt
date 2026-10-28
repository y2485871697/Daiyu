package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.storage.ConversationModelBinding
import app.daiyu.android.storage.ConversationModelSelections
import app.daiyu.android.storage.ProviderRegistry

class ProviderConversationIndicatorActivityTest : WiredActivityTestCase() {
    private fun otherProvider(): ConversationModelBinding {
        val repo = fixture.controller.providers
        val saved = fixture.settings.load()
        val registered = repo.registerConfigured(repo.snapshot().revision, "Same model other provider", saved,
            protocolChangeAuthorized = false, enabled = true, admit = {})
        val other = registered.providers.single { it.name == "Same model other provider" }
        repo.withEditor(repo.editor(other.slotId)) { store ->
            val catalog = store.modelCatalogSnapshot()
            if (catalog.models.none { it.id == saved.model }) {
                assertEquals(CatalogCommitResult.COMMITTED, store.modelCatalog.compareAndAppend(
                    catalog.providerIdentity, catalog.revision,
                    listOf(CatalogModelParameters.fromCurrent(saved))) { false })
            }
        }
        val record = repo.catalogSnapshot(repo.editor(other.slotId)).models.single { it.id == saved.model }
        return ConversationModelBinding(other.slotId, record.settings.localIdentity)
    }

    private fun bind(owner: ConversationId?, replacement: ConversationModelBinding) {
        assertTrue(fixture.database.bindModel(owner, fixture.database.modelBinding(owner), replacement))
        assertEquals(replacement, fixture.database.modelBinding(owner))
        assertEquals(replacement.providerSlotId, fixture.controller.freezeConversationProvider(owner).editor.slotId)
    }

    private fun assertCurrent(slot: String) {
        reveal("provider-open-$slot", "模型提供商").assertIsDisplayed()
        awaitUniqueTag("provider-current-$slot")
        node("provider-current-$slot").assertIsDisplayed()
        ui.onAllNodesWithContentDescription("当前提供商", useUnmergedTree = true).assertCountEquals(1)
    }

    fun testComposerIndicatorUsesItsExactProviderNotGlobalDefaultOrSameModelName() {
        val binding = otherProvider()
        bind(null, binding)
        val repo = fixture.controller.providers
        val before = repo.snapshot()
        val settings = fixture.settings.load()
        assertEquals(ProviderRegistry.PRIMARY, before.activeSlotId)
        assertEquals(settings.model, fixture.controller.conversationModelEditing(null).second.settings.model)
        openProviderManagement()
        assertCurrent(binding.providerSlotId)
        node("provider-current-primary").assertDoesNotExist()
        reveal("provider-open-primary", "模型提供商").performTouchInput { longClick() }
        node("provider-open-primary").assertIsOn()
        reveal("provider-open-${binding.providerSlotId}", "模型提供商").assertIsOff()
        ui.onAllNodesWithContentDescription("当前提供商", useUnmergedTree = true).assertCountEquals(0)
        node("provider-cancel-selection").performClick()
        assertCurrent(binding.providerSlotId)
        assertEquals(before, repo.snapshot())
        assertEquals(settings, fixture.settings.load())
        assertEquals(binding, fixture.database.modelBinding(null))
        assertEquals(0, fixture.opens.get())
    }

    fun testChangingHistoricalOwnerRefreshesIndicatorAndPreservesEveryBinding() {
        val other = otherProvider()
        val first = fixture.database.create(fixture.database.selectedAssistantId())
        val second = fixture.database.create(fixture.database.selectedAssistantId())
        val firstBinding = checkNotNull(fixture.database.modelBinding(first.id))
        bind(second.id, other)
        val draftBinding = fixture.database.modelBinding(null)
        val registry = fixture.controller.providers.snapshot()
        val settings = fixture.settings.load()
        mainThread { activity.select(first) }
        openProviderManagement()
        assertCurrent(firstBinding.providerSlotId)
        mainThread { activity.select(second) }
        assertCurrent(other.providerSlotId)
        node("provider-current-${firstBinding.providerSlotId}").assertDoesNotExist()
        mainThread { activity.select(first) }
        assertCurrent(firstBinding.providerSlotId)
        node("provider-current-${other.providerSlotId}").assertDoesNotExist()
        assertEquals(firstBinding, fixture.database.modelBinding(first.id))
        assertEquals(other, fixture.database.modelBinding(second.id))
        assertEquals(draftBinding, fixture.database.modelBinding(null))
        assertEquals(registry, fixture.controller.providers.snapshot())
        assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }

    fun testNoBoundModelNeverFallsBackToGlobalProvider() {
        ConversationModelSelections.write(fixture.database.writableDatabase, null, null)
        assertNull(fixture.database.modelBinding(null))
        val registry = fixture.controller.providers.snapshot()
        assertEquals(ProviderRegistry.PRIMARY, registry.activeSlotId)
        openProviderManagement()
        ui.onAllNodesWithContentDescription("当前提供商", useUnmergedTree = true).assertCountEquals(0)
        assertNull(fixture.database.modelBinding(null))
        assertEquals(registry, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }

    fun testResumeClearsOldIndicatorWhenTheBoundLocalIdentityCannotResolve() {
        val binding = otherProvider()
        bind(null, binding)
        openProviderManagement()
        assertCurrent(binding.providerSlotId)
        val unavailable = binding.copy(localModelIdentity = java.util.UUID.randomUUID().toString())
        assertTrue(fixture.database.bindModel(null, binding, unavailable))
        val registry = fixture.controller.providers.snapshot()
        realPauseResume(activity)
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("provider-add") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        ui.onAllNodesWithContentDescription("当前提供商", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(unavailable, fixture.database.modelBinding(null))
        assertEquals(registry, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }
}
