package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ProductionContextPreparationTest : InstrumentationTestCase() {
    private lateinit var base: Context
    private lateinit var db: ConversationDatabase
    private lateinit var service: ContextService
    private val binding = SummaryModelBinding("summary-provider", "summary-model")

    override fun setUp() {
        super.setUp()
        val isolated = testContext(instrumentation.targetContext, "p09-factory-${UUID.randomUUID()}-")
        val files = File(isolated.cacheDir, "p09-factory-${UUID.randomUUID()}").apply { mkdirs() }
        base = object : ContextWrapper(isolated) { override fun getFilesDir(): File = files }
        db = ConversationDatabase(base)
        service = ContextService(base, db)
    }
    override fun tearDown() {
        db.close(); base.deleteDatabase("conversations.db"); base.filesDir.deleteRecursively()
        base.getSharedPreferences(ProductionCompressionSettingsStore.NAMESPACE, Context.MODE_PRIVATE).edit().clear().commit()
        base.getSharedPreferences(SummaryProfileStore.METADATA_NAMESPACE, Context.MODE_PRIVATE).edit().clear().commit()
        super.tearDown()
    }
    private fun guarded(onRead: (String) -> Unit): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            onRead(name)
            return super.getSharedPreferences(name, mode)
        }
    }

    fun testCreateOnMainThreadDoesNotReadPreferencesAndFreezeReadsLatestSettingsOnWorker() {
        val reads = mutableListOf<String>()
        val context = guarded { name ->
            check(name == ProductionCompressionSettingsStore.NAMESPACE) { "unexpected profile/main read" }
            reads += name
        }
        val result = AtomicReference<ContextPreparationCoordinator>()
        instrumentation.runOnMainSync { result.set(ProductionContextPreparation.create(context, service)) }
        assertTrue(reads.isEmpty())
        // Persist after create: the factory must not have captured stale defaults.
        ProductionCompressionSettingsStore(base).save(ProductionCompressionSettings(keepTurns = 7))
        val frozen = result.get().freeze(ConversationId("fixture"), emptyList())
        assertEquals(7, frozen.policy.keepTurns)
        assertEquals(listOf(ProductionCompressionSettingsStore.NAMESPACE), reads)
    }

    fun testDisabledAndUnknownWindowNeverAccessAnySummaryProfileOrMainModel() {
        for (settings in listOf(
            ProductionCompressionSettings(false, 32000, summaryModel = binding),
            ProductionCompressionSettings(true, summaryModel = binding))) {
            ProductionCompressionSettingsStore(base).save(settings)
            val context = guarded { name -> check(name == ProductionCompressionSettingsStore.NAMESPACE) }
            val coordinator = ProductionContextPreparation.create(context, service)
            val frozen = coordinator.freeze(ConversationId("fixture"), emptyList())
            assertFalse(frozen.policy.enabled)
            assertNull(frozen.summarizer)
        }
    }

    fun testEnabledProfileMissingIsExplicitlyUnavailableAndDoesNotReadMainModel() {
        ProductionCompressionSettingsStore(base).save(ProductionCompressionSettings(true, 32000, summaryModel = binding))
        val reads = mutableListOf<String>()
        val coordinator = ProductionContextPreparation.create(guarded { name ->
            check(name != "model-settings") { "forbidden main-model fallback" }; reads += name
        }, service)
        try { coordinator.freeze(ConversationId("fixture"), emptyList()); fail("missing profile") }
        catch (failure: SummaryProfileUnavailableException) {
            assertEquals(SummaryProfileUnavailableReason.MISSING, failure.reason)
        }
        assertTrue(reads.contains(SummaryProfileStore.METADATA_NAMESPACE))
        assertFalse(reads.contains("model-settings"))
    }

    fun testFreezeOnMainThreadIsRejectedBeforeAnyPreferenceRead() {
        val coordinator = ProductionContextPreparation.create(guarded { error("must not read on main") }, service)
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try { coordinator.freeze(ConversationId("fixture"), emptyList()) }
            catch (caught: Throwable) { failure.set(caught) }
        }
        assertTrue(failure.get() is IllegalStateException)
    }
}
