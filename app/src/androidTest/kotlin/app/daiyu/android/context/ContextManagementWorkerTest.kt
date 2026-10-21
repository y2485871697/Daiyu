package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

/** Real local DB/archive/coordinator, strictly fake tool-free model transport. No network. */
@Suppress("DEPRECATION")
class ContextManagementWorkerTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var service: ContextService
    private val binding = SummaryModelBinding("independent-summary", "exact-model")
    private var configuration = ProductionCompressionSettings(automatic = false, contextWindowTokens = 1_000_000,
        reserveTokens = 1000, summaryModel = binding).coordinatorSettings()
    private var resolutions = 0
    private var requests = 0
    private var cancellations = 0
    private var onRequest: () -> Unit = {}
    private var resolve: () -> BoundSummaryModel? = { bound() }
    private var target: SummaryProfileDescriptor? = SummaryProfileDescriptor(binding, "revision-1",
        "https://summary.invalid/v1/responses", "actual-api-model")
    private lateinit var worker: ContextManagementWorker

    private class Lease(override val owner: ContextManagementOwner) : ContextManagementLease {
        override val cancellation = ContextPreparationCancellation()
        var current = true
        override fun checkCurrent() { check(current); cancellation.check() }
        override fun finish() = Unit // Fixture only: adapter tests verify parent lease drain.
    }

    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "context-management-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "context-management-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        database = ConversationDatabase(context)
        service = ContextService(context, database)
        worker = ContextManagementWorker(service, { configuration }, ProductionCompressionPolicyResolver,
            object : ContextManagementSummaryProfiles {
                override fun describe(binding: SummaryModelBinding) = target?.takeIf { it.binding == binding }
                override fun describeSelection(selection: ContextManagementSelection): SummaryProfileDescriptor {
                    val selected = selection.binding ?: SummaryModelBinding("explicit-chat", "selected-current")
                    target = SummaryProfileDescriptor(selected, "selected-once", "https://selected.invalid/v1/responses", "selected-api")
                    return checkNotNull(target)
                }
                override fun freeze(expected: SummaryProfileDescriptor, usagePort: BoundSummaryUsagePort): BoundSummaryModel? {
                    if (target != expected) throw SummaryProfileChangedException()
                    resolutions++
                    return resolve()
                }
            })
    }
    override fun tearDown() {
        database.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively()
        super.tearDown()
    }
    private fun seed(turns: Int = 6): Lease {
        val id = database.create("default").id
        repeat(turns) { n ->
            val run = RunId("r-$n-${id.value}")
            val user = Message(MessageId("u-$n-${id.value}"), id, run, "user",
                listOf(MessageBlock.Text("u", "事实$n ".repeat(100))), RunStatus.COMPLETED)
            val assistant = Message(MessageId("a-$n-${id.value}"), id, run, "assistant",
                listOf(MessageBlock.Text("a", "已确认$n ".repeat(100))), RunStatus.RUNNING)
            database.send(user, assistant); database.save(assistant.copy(status = RunStatus.COMPLETED))
        }
        return Lease(ContextManagementOwner(id, 17))
    }
    private fun bound(selectedBinding: SummaryModelBinding = binding) = BoundSummaryModel(selectedBinding, SummaryModelTransport { history, _ ->
        requests++
        assertEquals(1, history.size)
        assertEquals("user", history.single().role)
        assertTrue((history.single().blocks.single() as MessageBlock.Text).text.contains("不可信"))
        object : ModelSession {
            override fun cancel() { cancellations++ }
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                assertTrue(outputs.isEmpty())
                onRequest()
                sink(ModelEvent.TextFinal("summary", "已核实事实，保留不确定性与未完成行动"))
            }
        }
    }, 2_000_000, 1_900_000, { SummaryRequestDeadline(60_000) })

    private fun failure(action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("must reject")
    }
    private fun priorArchive(lease: Lease): PreparedContext {
        service.compress(lease.owner.conversationId, CompressionPolicy(keepTurns = 2), ContextSummarizer { "旧有效摘要" })
        return service.prepareSend(lease.owner.conversationId)
    }

    fun testOneShotSelectionUsesNewBindingWithoutSavingOrChangingAutomaticPolicy() {
        val lease = seed()
        val saved = configuration
        val before = worker.snapshot(lease)
        val selectedBinding = SummaryModelBinding("explicit-only", "chosen-summary")
        val selected = worker.selectCompression(lease, before,
            ContextManagementSelection(selectedBinding, app.daiyu.android.model.ProviderProtocol.RESPONSES))
        assertSame(saved, selected.savedConfiguration)
        assertEquals(selectedBinding, selected.configuration!!.summaryModel)
        assertEquals(saved, configuration)
        assertEquals(0, requests); assertEquals(0, resolutions)
        resolve = { bound(selectedBinding) }
        assertNotNull(worker.compress(lease, selected).archiveId)
        assertEquals(1, requests); assertEquals(1, resolutions)
        assertEquals(saved, configuration)
        assertFalse(configuration.automatic)
    }

    fun testChangedSavedSettingsAfterOneShotSelectionRejectBeforeSummaryFreeze() {
        val lease = seed()
        val selected = worker.selectCompression(lease, worker.snapshot(lease),
            ContextManagementSelection(null, app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS))
        configuration = configuration.copy(reserveTokens = 2000)
        failure { worker.compress(lease, selected) }
        assertEquals(0, resolutions); assertEquals(0, requests)
    }

    fun testMainThreadMisuseRejectsBeforeReadingStorageOrResolvingModel() {
        val lease = seed()
        val before = database.exportBackup()
        var rejected: Exception? = null
        instrumentation.runOnMainSync {
            try { worker.snapshot(lease) } catch (failure: Exception) { rejected = failure }
        }
        assertTrue(rejected is IllegalStateException)
        assertEquals(before, database.exportBackup())
        assertEquals(0, resolutions); assertEquals(0, requests)
    }

    fun testBrowseIsExactReadOnlyEvenWhenAboveBudgetAndDoesNotResolveModel() {
        val lease = seed()
        val originals = service.displayHistory(lease.owner.conversationId)
        configuration = configuration.copy(contextWindowTokens = 1024, reserveTokens = 0)
        val before = database.exportBackup()
        val snapshot = worker.snapshot(lease)
        assertEquals(originals, snapshot.originals)
        assertEquals(originals, snapshot.effective.messages)
        assertEquals(ContextRevision.digest(originals), snapshot.historyRevision)
        assertEquals(estimateTokens(originals), snapshot.effective.estimatedTokens)
        assertEquals(before, database.exportBackup())
        assertEquals(0, resolutions); assertEquals(0, requests)
    }

    fun testRealMetadataBrowsingAndSameBindingEndpointModelKeyOrResaveRejectOldConsentWithZeroIO() {
        val lease = seed()
        val namespace = "management-profile-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        val original = SummaryProfileSettings(binding,
            ModelSettings("https://summary.invalid", "actual-api-model", "synthetic-key", false),
            SummaryProfileBudgets(2_000_000, 1_900_000, 97_000, 1000, 2000, 60_000))
        var current = original.provider
        var credentialReads = 0
        val store = SummaryProfileStore(prefs, { credentialReads++; current },
            { model, _, _ -> current = model }, namespace)
        val profileWorker = ContextManagementWorker(service, { configuration }, ProductionCompressionPolicyResolver,
            object : ContextManagementSummaryProfiles {
                override fun describe(binding: SummaryModelBinding) = store.descriptor(binding)
                override fun freeze(expected: SummaryProfileDescriptor, usagePort: BoundSummaryUsagePort): BoundSummaryModel {
                    store.configuration(expected) // Same production atomic compare-and-capture port.
                    resolutions++
                    return bound()
                }
            })
        try {
            for (changed in listOf(original.copy(provider = original.provider.copy(endpoint = "https://other.invalid")),
                original.copy(provider = original.provider.copy(model = "other-api-model")),
                original.copy(provider = original.provider.copy(apiKey = "synthetic-other-key")), original)) {
                store.save(original)
                val before = database.exportBackup()
                val snapshot = profileWorker.snapshot(lease)
                assertEquals(original.provider.requestEndpoint(), snapshot.summaryTarget!!.endpoint)
                assertEquals(original.provider.model, snapshot.summaryTarget!!.apiModel)
                assertTrue(snapshot.canCompress)
                assertEquals(before, database.exportBackup())
                assertEquals(0, credentialReads)
                store.save(changed)
                assertTrue(failure { profileWorker.compress(lease, snapshot) } is SummaryProfileChangedException)
                assertEquals(0, credentialReads); assertEquals(0, resolutions); assertEquals(0, requests)
                assertEquals(before, database.exportBackup())
            }
            val refreshed = profileWorker.snapshot(lease)
            assertNotNull(profileWorker.compress(lease, refreshed).archiveId)
            assertEquals(1, credentialReads); assertEquals(1, resolutions); assertEquals(1, requests)
            val readOnly = profileWorker.snapshot(lease)
            profileWorker.archive(lease, readOnly, 0)
            assertEquals(1, credentialReads) // Browsing and paging still never decrypt keys.
            assertEquals(1, requests)
        } finally { prefs.edit().clear().commit() }
    }

    fun testUnavailableTargetLeavesOriginalsBrowsableAndCannotAuthorizeCompression() {
        val lease = seed()
        target = null
        val snapshot = worker.snapshot(lease)
        assertFalse(snapshot.canCompress)
        assertEquals(service.displayHistory(lease.owner.conversationId), snapshot.originals)
        failure { worker.compress(lease, snapshot) }
        assertEquals(0, resolutions); assertEquals(0, requests)
    }

    fun testManualBelowAutomaticThresholdUsesExistingPreparationAndDoesNotSaveAutoSwitch() {
        val lease = seed(); val id = lease.owner.conversationId
        val expected = worker.snapshot(lease)
        assertFalse(configuration.automatic)
        assertFalse(ProductionCompressionPolicyResolver.resolve(configuration.copy(automatic = true))
            .shouldCompress(estimateTokens(expected.originals)))
        val receipt = worker.compress(lease, expected)
        assertNotNull(receipt.archiveId)
        assertEquals(expected.historyRevision, receipt.expectedHistoryRevision)
        assertEquals(1, resolutions); assertEquals(1, requests)
        assertFalse(configuration.automatic)
        assertEquals(expected.originals, service.displayHistory(id))
        val logical = MessageHistory.expand(expected.originals)
        val cut = TokenTailCompression.selectStart(logical, configuration.contextWindowTokens)
        assertTrue(cut > 0)
        assertEquals(logical.drop(cut), service.prepareSend(id).messages.drop(1))
        assertEquals(expected.originals.take(cut), service.archivePage(receipt.archiveId!!, expectedOwner = id).messages)
        assertTrue(cancellations > 0)
    }

    fun testArchivePagesAreScopedAndLosslessAndNeverResolveModels() {
        val lease = seed(30); val id = lease.owner.conversationId
        val originals = service.displayHistory(id)
        val archive = service.compress(id, CompressionPolicy(keepTurns = 1), ContextSummarizer { "本机档案摘要" })!!
        val snapshot = worker.snapshot(lease)
        assertEquals(archive, snapshot.effective.archiveId)
        val before = database.exportBackup()
        val first = worker.archive(lease, snapshot, 0)
        val second = worker.archive(lease, snapshot, first.page.nextOffset!!)
        assertEquals(58, first.page.total)
        assertEquals(originals.dropLast(2), first.page.messages + second.page.messages)
        assertNull(second.page.nextOffset)
        assertEquals(before, database.exportBackup())
        assertEquals(0, resolutions); assertEquals(0, requests)
        val other = seed()
        failure { worker.archive(other, snapshot, 0) }
        val otherSnapshot = worker.snapshot(other)
        failure { worker.archive(other, otherSnapshot.copy(effective = snapshot.effective), 0) }
        assertEquals(0, requests)
    }

    fun testStaleFullHistoryArchiveConfigurationAndOwnerRejectBeforeAnyRequest() {
        val lease = seed(); val id = lease.owner.conversationId
        val initial = worker.snapshot(lease)
        val last = initial.originals.last()
        DatabaseTransactions { database.writableDatabase }.run { sql ->
            assertEquals(1, sql.update("messages", android.content.ContentValues().apply {
                put("blocks", ArchiveCodec.blocks(listOf(MessageBlock.Text("changed", "retained tail changed"))).toString())
            }, "id=? AND conversation_id=? AND run_id=? AND status='COMPLETED'",
                arrayOf(last.id.value, last.conversationId.value, last.runId.value)))
        }
        failure { worker.compress(lease, initial) }
        val changed = worker.snapshot(lease)
        priorArchive(lease)
        failure { worker.compress(lease, changed) }
        val archived = worker.snapshot(lease)
        configuration = configuration.copy(reserveTokens = 2000)
        failure { worker.compress(lease, archived) }
        val current = worker.snapshot(lease)
        lease.current = false
        failure { worker.compress(lease, current) }
        assertEquals(0, resolutions); assertEquals(0, requests)
        assertNotNull(service.prepareSend(id).archiveId)
    }

    fun testUnknownWindowMissingAndWrongIndependentCandidateNeverFallbackOrRequest() {
        val lease = seed()
        val saved = configuration
        for (invalid in listOf(saved.copy(contextWindowTokens = null), saved.copy(summaryModel = null))) {
            configuration = invalid
            val snapshot = worker.snapshot(lease)
            assertFalse(snapshot.canCompress)
            failure { worker.compress(lease, snapshot) }
        }
        configuration = saved
        resolve = { null }
        failure { worker.compress(lease, worker.snapshot(lease)) }
        resolve = { bound().copy(binding = SummaryModelBinding("main-model", "not-authorized")) }
        failure { worker.compress(lease, worker.snapshot(lease)) }
        assertEquals(0, requests)
        assertNull(service.prepareSend(lease.owner.conversationId).archiveId)
    }

    fun testFailureCancellationAndBudgetKeepOriginalsAndPriorArchive() {
        val lease = seed(); val id = lease.owner.conversationId
        val original = service.displayHistory(id)
        val before = priorArchive(lease)
        onRequest = { error("fake summary failed") }
        failure { worker.compress(lease, worker.snapshot(lease)) }
        assertEquals(before, service.prepareSend(id)); assertEquals(original, service.displayHistory(id))
        onRequest = { lease.cancellation.cancel() }
        assertTrue(failure { worker.compress(lease, worker.snapshot(lease)) } is CancellationException)
        assertEquals(before, service.prepareSend(id)); assertEquals(original, service.displayHistory(id))
        val newLease = Lease(lease.owner)
        onRequest = {}
        resolve = { bound().copy(inputBudgetTokens = 1) }
        val calls = requests
        failure { worker.compress(newLease, worker.snapshot(newLease)) }
        assertEquals(calls, requests)
        assertEquals(before, service.prepareSend(id)); assertEquals(original, service.displayHistory(id))
    }

    fun testFullHistoryCASIncludesRetainedTailNotJustArchivedPrefix() {
        val lease = seed(); val id = lease.owner.conversationId
        val before = priorArchive(lease)
        val snapshot = worker.snapshot(lease)
        onRequest = {
            val tail = snapshot.originals.last()
            DatabaseTransactions { database.writableDatabase }.run { sql ->
                assertEquals(1, sql.update("messages", android.content.ContentValues().apply {
                    put("blocks", ArchiveCodec.blocks(listOf(MessageBlock.Text("new", "tail changed during summary"))).toString())
                }, "id=? AND conversation_id=? AND run_id=? AND status='COMPLETED'",
                    arrayOf(tail.id.value, tail.conversationId.value, tail.runId.value)))
            }
        }
        failure { worker.compress(lease, snapshot) }
        assertEquals(1, requests)
        assertEquals(before.archiveId, service.prepareSend(id).archiveId)
        assertEquals(snapshot.originals.dropLast(1), service.displayHistory(id).dropLast(1))
        assertEquals("tail changed during summary", (service.displayHistory(id).last().blocks.single() as MessageBlock.Text).text)
    }

    fun testNoSafePrefixAndPreCancelledPreparationNeverOpenTransport() {
        val lease = seed(1)
        DatabaseTransactions { database.writableDatabase }.run { sql ->
            sql.delete("messages", "conversation_id=? AND role='assistant'", arrayOf(lease.owner.conversationId.value))
        }
        assertEquals(1, service.displayHistory(lease.owner.conversationId).size)
        assertNull(worker.compress(lease, worker.snapshot(lease)).archiveId)
        assertEquals(0, requests)
        val snapshot = worker.snapshot(lease)
        lease.cancellation.cancel()
        assertTrue(failure { worker.compress(lease, snapshot) } is CancellationException)
        assertEquals(0, requests)
        val empty = seed(0)
        assertFalse(worker.snapshot(empty).canCompress)
        val missing = Lease(ContextManagementOwner(ConversationId("deleted-owner"), 19))
        failure { worker.snapshot(missing) }
    }
    fun testProductionWorkerDescribesCatalogSelectionWithoutReadingCredentials() {
        val appContext = object : ContextWrapper(context) { override fun getApplicationContext(): Context = this }
        val store = app.daiyu.android.storage.ModelSettingsStore(appContext)
        val settings = ModelSettings("https://catalog-summary.invalid", "summary", "catalog-summary-key", false)
        store.save(settings)
        val repository = app.daiyu.android.storage.ProviderRepository(appContext, primary = store)
        val editor = repository.activeEditor()
        val catalog = repository.catalogSnapshot(editor)
        val record = catalog.models.single()
        val selected = CatalogSummaryBindings.encode(app.daiyu.android.storage.ProviderCatalogRunTarget(
            editor, catalog.providerIdentity, catalog.revision, record.settings.localIdentity, record.id, settings.protocol))
        ProductionCompressionSettingsStore(appContext).save(ProductionCompressionSettings(automatic = false,
            contextWindowTokens = 1_000_000, reserveTokens = 1000, summaryModel = selected))
        appContext.getSharedPreferences("model-settings", Context.MODE_PRIVATE).edit()
            .putString("key", "malformed-private-ciphertext").commit()
        val lease = seed()
        val snapshot = ContextManagementWorker.production(appContext, service).snapshot(lease)
        assertTrue(snapshot.canCompress)
        assertEquals(selected, snapshot.summaryTarget!!.binding)
        assertEquals(settings.requestEndpoint(), snapshot.summaryTarget!!.endpoint)
        assertEquals(settings.model, snapshot.summaryTarget!!.apiModel)
        assertEquals(0, requests)
    }

}
