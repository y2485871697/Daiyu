package app.daiyu.android.context

import android.content.Context
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class ProductionCompressionRefreshTest {
    private val parent = Message(MessageId("assistant"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private val first = SummaryModelBinding("provider", "first")
    private val second = SummaryModelBinding("provider", "second")
    private val provider = ProviderSnapshot.capture(ModelSettings("https://chat.invalid/v1", "chat-model",
        "synthetic-key", false, contextWindowOverride = 4096), ProviderProtocol.CHAT_COMPLETIONS)
    private lateinit var store: ProductionCompressionSettingsStore
    private val calls = mutableListOf<Pair<SummaryModelBinding, ProviderProtocol?>>()
    private var onOpen: () -> Unit = {}
    private var chatResolutions = 0
    private var unavailable = false

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = ProductionCompressionSettingsStore(context.getSharedPreferences("live-compression-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        store.save(ProductionCompressionSettings(automatic = false, contextWindowTokens = 4096, reserveTokens = 64))
    }

    private fun model(binding: SummaryModelBinding, protocol: ProviderProtocol?): BoundSummaryModel {
        val selected = binding to protocol
        return BoundSummaryModel(binding, SummaryModelTransport { _, _ ->
            onOpen()
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    calls += selected
                    sink(ModelEvent.TextFinal("summary", "short preserved evidence"))
                }
                override fun cancel() {}
            }
        }, 2000000, 1500000, { SummaryRequestDeadline(10000) })
    }

    private fun runtime(): ProductionRunContext {
        val read = { store.snapshot().coordinatorSettings() }
        val coordinator = ContextPreparationCoordinator(ContextService(RuntimeEnvironment.getApplication()),
            settingsSnapshot = read, policies = ProductionCompressionPolicyResolver,
            models = object : SummaryModelResolver {
                override fun resolve(binding: SummaryModelBinding): BoundSummaryModel? = error("usage required")
                override fun resolve(binding: SummaryModelBinding, usage: BoundSummaryUsagePort?,
                    protocol: ProviderProtocol?): BoundSummaryModel? {
                    assertNotNull(usage)
                    return if (unavailable) null else model(binding, protocol)
                }
            }, chatModel = { snapshot, window, usage, protocol ->
                chatResolutions++
                assertSame(provider, snapshot); assertEquals(4096L, window); assertNotNull(usage)
                model(SummaryModelBinding("chat", snapshot.model), protocol)
            }, liveSettingsSnapshot = read)
        val frozen = coordinator.freeze(parent.conversationId, emptyList(),
            contextWindowOverride = 4096, chatProvider = provider)
        return ProductionRunContext(frozen, parent)
    }

    private fun history() = (0..7).map { index -> parent.copy(id = MessageId("u$index"), role = Message.USER_ROLE,
        status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text("source", "source evidence ".repeat(120)))) }
    private fun compact(runtime: RunContextRuntime): List<Message> {
        val request = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "owner", RequestOperation.NEXT)
        return checkNotNull(runtime.compact(history(), 1000000, 1000000, request, {}, {}, { false }))
    }

    @Test fun sameRunReloadsAutomaticModelAndProtocolWithoutChangingItsWindow() {
        val root = runtime()
        assertFalse(root.compressionSnapshot().automatic)
        store.save(store.snapshot().copy(automatic = true, contextWindowTokens = 16384,
            summaryModel = first, summaryProtocol = ProviderProtocol.RESPONSES))
        val selected = root.compressionSnapshot()
        assertTrue(selected.automatic)
        assertEquals(root.windowTokens, selected.windowTokens)
        assertEquals(root.sendBudgetTokens, selected.sendBudgetTokens)
        compact(selected)
        assertEquals(listOf(first to ProviderProtocol.RESPONSES), calls)
        store.save(store.snapshot().copy(automatic = false))
        assertFalse(root.compressionSnapshot().automatic)
        assertTrue(selected.automatic)
    }

    @Test fun activeCompressionKeepsItsSnapshotAndTheNextOneUsesTheNewSettings() {
        val root = runtime()
        store.save(store.snapshot().copy(automatic = true, summaryModel = first,
            summaryProtocol = ProviderProtocol.CHAT_COMPLETIONS))
        val selected = root.compressionSnapshot()
        onOpen = {
            store.save(store.snapshot().copy(summaryModel = second, summaryProtocol = ProviderProtocol.RESPONSES))
        }
        val result = compact(selected)
        assertTrue(result.first().blocks.filterIsInstance<MessageBlock.Text>().single().text.contains("<compacted-summary>"))
        assertEquals(listOf(first to ProviderProtocol.CHAT_COMPLETIONS), calls)
        onOpen = {}
        compact(root.compressionSnapshot())
        assertEquals(listOf(first to ProviderProtocol.CHAT_COMPLETIONS, second to ProviderProtocol.RESPONSES), calls)
    }

    @Test fun disablingCustomModelUsesTheFrozenChatModelAndLatestProtocol() {
        val root = runtime()
        store.save(store.snapshot().copy(automatic = true, summaryModel = first))
        compact(root.compressionSnapshot())
        store.save(store.snapshot().copy(summaryModel = null, summaryProtocol = ProviderProtocol.RESPONSES,
            contextWindowTokens = 65536))
        compact(root.compressionSnapshot())
        assertEquals(1, chatResolutions)
        assertEquals(SummaryModelBinding("chat", "chat-model") to ProviderProtocol.RESPONSES, calls.last())
    }

    @Test fun missingNewCustomModelFailsWithoutFallingBackToOldOrChatModel() {
        val root = runtime()
        store.save(store.snapshot().copy(automatic = true, summaryModel = first))
        unavailable = true
        try { compact(root.compressionSnapshot()); fail("unavailable model accepted") }
        catch (failure: IllegalArgumentException) { assertEquals("压缩模型不可用", failure.message) }
        assertTrue(calls.isEmpty()); assertEquals(0, chatResolutions)
    }

    private fun <T> worker(block: () -> T): T {
        val task = FutureTask(block)
        val thread = Thread(task)
        thread.start()
        try { return task.get(10, TimeUnit.SECONDS) }
        finally { task.cancel(true); thread.join(5000) }
    }

    @Test fun productionFactoryWiresLiveSettingsForAnAlreadyCreatedRun() = worker {
        val context = RuntimeEnvironment.getApplication()
        val production = ProductionCompressionSettingsStore(context)
        production.save(ProductionCompressionSettings(automatic = false, contextWindowTokens = 4096, reserveTokens = 64))
        val coordinator = ProductionContextPreparation.create(context, ContextService(context))
        val root = ProductionRunContext(coordinator.freeze(parent.conversationId, emptyList(),
            contextWindowOverride = 4096, chatProvider = provider), parent)
        assertFalse(root.compressionSnapshot().automatic)
        production.save(production.snapshot().copy(automatic = true))
        assertTrue(root.compressionSnapshot().automatic)
        production.save(production.snapshot().copy(automatic = false))
        assertFalse(root.compressionSnapshot().automatic)
    }

    @Test fun childFactoryStillKeepsAutomaticCompressionOnWithGlobalSwitchOff() = worker {
        val context = RuntimeEnvironment.getApplication()
        val production = ProductionCompressionSettingsStore(context)
        production.save(ProductionCompressionSettings(automatic = false, contextWindowTokens = 65536, reserveTokens = 64))
        val root = checkNotNull(ProductionContextPreparation.childRuntime(context, parent, provider, "child") {})
        production.save(production.snapshot().copy(summaryProtocol = ProviderProtocol.RESPONSES))
        val selected = root.compressionSnapshot()
        assertTrue(selected.automatic)
        assertEquals(4096L, selected.windowTokens)
    }
}
