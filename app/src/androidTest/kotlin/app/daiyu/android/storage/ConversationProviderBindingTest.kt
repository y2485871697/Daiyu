package app.daiyu.android.storage

import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.model.catalog.*
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class ConversationProviderBindingTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var settings: ModelSettingsStore
    private lateinit var repository: ProviderRepository
    private lateinit var database: ConversationDatabase
    private lateinit var alias: String
    override fun setUp() {
        super.setUp()
        alias = "conversation-provider-${UUID.randomUUID()}"
        context = object : android.content.ContextWrapper(testContext(instrumentation.targetContext, alias)) {
            private val root = File(baseContext.cacheDir, alias).apply { mkdirs() }
            override fun getFilesDir() = root
        }
        settings = ModelSettingsStore(context, alias)
        settings.save(ModelSettings("https://example.invalid", "model-a", "fixture", false))
        val catalog = settings.modelCatalogSnapshot()
        assertEquals(CatalogCommitResult.COMMITTED, settings.modelCatalog.compareAndAppend(catalog.providerIdentity, catalog.revision,
            listOf(CatalogModelParameters.newModel(RemoteModel("model-b")))) { false })
        repository = ProviderRepository(context, settings)
        database = ConversationDatabase(context)
        database.initializeModelBindings(repository.selectedConversationModel())
    }
    override fun tearDown() {
        database.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively()
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        java.util.Collections.list(keys.aliases()).filter { it.startsWith("$alias.") }.forEach(keys::deleteEntry)
        super.tearDown()
    }
    fun testSameProviderSelectionFreezesDifferentModelsWithoutChangingGlobalAndIgnoresUnrelatedRevision() {
        val first = database.create(database.selectedAssistantId())
        val second = database.create(database.selectedAssistantId())
        val before = repository.snapshot()
        val globalSettings = settings.load()
        val ticket = checkNotNull(settings.modelCatalogSnapshot().selectionFor("model-b"))
        repository.selectConversationModel(repository.activeEditor(), globalSettings, ticket, { false }) { binding ->
            assertTrue(database.bindModel(first.id, database.modelBinding(first.id), binding))
        }
        val a = repository.freezeConversation(checkNotNull(database.modelBinding(first.id)))
        val b = repository.freezeConversation(checkNotNull(database.modelBinding(second.id)))
        assertEquals("model-b", a.settings.model); assertEquals("model-a", b.settings.model)
        assertEquals(before, repository.snapshot()); assertEquals(globalSettings, settings.load())
        repository.register(before.revision, "unrelated")
        assertTrue(repository.isCurrentConversation(checkNotNull(database.modelBinding(first.id)), a))
    }
    fun testDeleteAndRecreateSameWireNameDoesNotResolveTheOldLocalIdentity() {
        val binding = checkNotNull(database.modelBinding(null))
        val old = checkNotNull(settings.modelCatalogSnapshot().selectionFor("model-a"))
        assertEquals(CatalogLocalEditResult.APPLIED, settings.deleteCatalogModel(old) { false })
        val catalog = settings.modelCatalogSnapshot()
        assertEquals(CatalogCommitResult.COMMITTED, settings.modelCatalog.compareAndAppend(catalog.providerIdentity, catalog.revision,
            listOf(CatalogModelParameters.newModel(RemoteModel("model-a")))) { false })
        try { repository.freezeConversation(binding); fail("old local identity must not rebound") }
        catch (_: IllegalStateException) { }
    }
}
