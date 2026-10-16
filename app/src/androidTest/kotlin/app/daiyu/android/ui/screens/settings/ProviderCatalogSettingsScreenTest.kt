package app.daiyu.android.ui.screens.settings

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.model.catalog.CatalogRecordCodec
import app.daiyu.android.model.catalog.FileSavedModelCatalogPersistence
import app.daiyu.android.model.catalog.RemoteModel
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.model.catalog.SavedModelCatalogPersistence
import app.daiyu.android.model.catalog.SavedModelCatalogSnapshot
import app.daiyu.android.ui.app.AgentAppTheme
import java.io.File
import java.util.UUID

/** Exercises the real wrapper and real durable backend, with only network admission faked. */
class ProviderCatalogSettingsScreenTest : WiredActivityTestCase() {
    private lateinit var directory: File
    private lateinit var catalog: FileSavedModelCatalogPersistence<String>
    private var draft by mutableStateOf(ModelSettingsDraftUi(model = "existing", endpoint = "https://same.invalid", apiKey = "not-saved"))
    @Volatile private var fetched: ((List<RemoteModel>?) -> Unit)? = null
    @Volatile private var cancelled: (() -> Boolean)? = null
    private var saves = 0
    private val old = SavedCatalogModel("existing", "Local name", "old customized parameters")

    override fun tearDown() {
        try { super.tearDown() } finally { if (::directory.isInitialized) directory.deleteRecursively() }
    }

    private fun show() {
        directory = File(instrumentation.targetContext.cacheDir, "catalog-ui-${UUID.randomUUID()}")
        catalog = FileSavedModelCatalogPersistence(File(directory, "catalog"), object : CatalogRecordCodec<String> {
            override fun encode(record: String) = record
            override fun decode(encoded: String) = encoded
        })
        val identity = catalog.register(listOf(old)).providerIdentity
        val checkedPersistence = object : SavedModelCatalogPersistence<String> by catalog {
            override fun read(providerIdentity: String): SavedModelCatalogSnapshot<String>? {
                check(Thread.currentThread() !== Looper.getMainLooper().thread) { "Catalog read on UI" }
                return catalog.read(providerIdentity)
            }
        }
        fetched = null; cancelled = null; saves = 0
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), false) {
                    ProviderCatalogSettingsScreen(
                        draft = draft, onDraftChange = { draft = it }, onSave = { saves++ }, onBack = {},
                        providerIdentity = identity, persistence = checkedPersistence,
                        createModel = { SavedCatalogModel(it.id, it.displayName, "defaults") },
                        fetchCatalog = { _, isCancelled, callback ->
                            cancelled = isCancelled; fetched = callback; true
                        },
                    )
                }
            }
        }
        ui.waitForIdle()
    }

    private fun startFetch() {
        mainThread { fetched = null }
        reveal("model-catalog-fetch").performClick()
        ui.waitUntil(5_000) { fetched != null }
    }

    private fun fetch() {
        startFetch()
        mainThread { checkNotNull(fetched).invoke(listOf(RemoteModel("existing", "Remote rename"), RemoteModel("add"), RemoteModel("unchecked"))) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("provider-model-selection").fetchSemanticsNodes().isNotEmpty() }
    }

    fun testFetchAndConfirmationAppendOnlyCheckedWithoutSavingOrSelectingActiveModel() {
        show()
        fetch()
        assertEquals(listOf(old), catalog.current()!!.models)
        node("provider-model-option:existing").performClick()
        node("provider-model-option:add").performClick()
        node("dialog-confirm").performClick()
        ui.waitUntil(5_000) { catalog.current()!!.models.size == 2 }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("provider-model-selection").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(old, SavedCatalogModel("add", "add", "defaults")), catalog.current()!!.models)
        ui.runOnIdle { assertEquals("existing", draft.model); assertEquals(0, saves) }
    }

    fun testCancelAndDraftChangeFenceBothDialogAndLateCallback() {
        show()
        fetch()
        node("provider-model-option:add").performClick()
        node("dialog-cancel").performClick()
        assertEquals(listOf(old), catalog.current()!!.models)
        assertTrue(checkNotNull(cancelled).invoke())
        startFetch()
        val late = checkNotNull(fetched)
        editModel("Base URL", "https://changed.invalid")
        assertTrue(checkNotNull(cancelled).invoke())
        mainThread { late(listOf(RemoteModel("late"))) }
        ui.waitForIdle()
        node("provider-model-selection").assertDoesNotExist()
        assertEquals(listOf(old), catalog.current()!!.models)
    }

    fun testRevisionChangeWhileDialogOpenFailsClosed() {
        show()
        fetch()
        node("provider-model-option:add").performClick()
        catalog.configurationChange { }
        node("dialog-confirm").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("provider-model-selection").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(old), catalog.current()!!.models)
        ui.onNodeWithText("服务商或目录已变化，请重新获取").assertExists()
        ui.runOnIdle { assertEquals("existing", draft.model); assertEquals(0, saves) }
    }
}
