package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.ChatApplication
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.balance.*
import app.daiyu.android.storage.ProviderRegistry
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ChatModelPickerBalanceBindingTest : WiredActivityTestCase() {
    private class Memory : BalancePersistence {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(record: ByteArray?) { bytes = record?.clone() }
    }

    fun testSharedPickerReadsEachSlotAndUpdatesWithoutStartingQueriesOrPolling() {
        val primary = ProviderRegistry.PRIMARY
        val secondary = UUID.randomUUID().toString()
        val queries = AtomicInteger()
        var primaryAmount = "1.93"
        fun runtime(slot: String, amount: () -> String) = BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery {
            queries.incrementAndGet()
            BalanceResult.Available(BalanceMoney(amount().toBigDecimal(), "USD"), 1L)
        }).also { runtime ->
            runBlocking {
                runtime.save(BalanceConfiguration(slot, "https://example.test", "fixture-key", "", "USD",
                    BalanceOption(enabled = true, apiPath = "/balance", resultPath = "balance")))
                assertTrue(runtime.manualRefresh())
            }
        }
        val runtimes = mapOf(primary to runtime(primary) { primaryAmount }, secondary to runtime(secondary) { "25.5" })
        val initialQueries = queries.get()
        val host = ProviderBalanceRuntimeHost(factory = { runtimes.getValue(it) })
        val app = instrumentation.targetContext.applicationContext as ChatApplication
        val original = app.providerBalanceHost
        val models = listOf(ModelCatalogEntryUi("model"))
        fun amount(slot: String, text: String) = ui.onNode(hasText(formatBalanceDisplay(text)) and
            hasAnyAncestor(hasTestTag("chat-model-provider-balance:$slot")), useUnmergedTree = true)
        try {
            mainThread {
                app.providerBalanceHost = host
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                        ChatModelPicker(ChatModelPickerState("model", models, providerId = primary,
                            providers = listOf(ChatModelProviderUi(primary, "Alpha", models),
                                ChatModelProviderUi(secondary, "Beta", models))), {}, {})
                    }
                }
            }
            ui.waitUntil(5_000) {
                host.cached(primary) != null && host.cached(secondary) != null &&
                    ui.onAllNodesWithTag("chat-model-provider-balance:$secondary", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
            }
            amount(primary, "1.93").assertIsDisplayed()
            amount(secondary, "25.5").assertIsDisplayed()
            assertEquals(initialQueries, queries.get())
            runtimes.values.forEach { assertFalse(it.isPolling) }
            primaryAmount = "7.77"
            runBlocking { assertTrue(runtimes.getValue(primary).manualRefresh()) }
            ui.waitUntil(5_000) { ui.onAllNodesWithText("7.77", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            amount(primary, "7.77").assertIsDisplayed()
            amount(secondary, "25.5").assertIsDisplayed()
            node("chat-model-provider:$secondary").performClick()
            amount(primary, "7.77").assertIsDisplayed()
            amount(secondary, "25.5").assertIsDisplayed()
            assertEquals(initialQueries + 1, queries.get())
        } finally {
            try {
                mainThread {
                    try { activity.setContent {} }
                    finally { app.providerBalanceHost = original }
                }
            } finally { host.close() }
        }
        assertEquals(initialQueries + 1, queries.get())
        runtimes.values.forEach { assertFalse(it.isPolling) }
    }
}
