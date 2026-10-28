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
    private var connectionSaved by mutableStateOf(true)
    private val old = SavedCatalogModel("existing", "Local name", "old customized parameters")

    override fun tearDown() {
        try { super.tearDown() } finally { if (::directory.isInitialized) directory.deleteRecursively() }
    }

    private fun show(twoTabs: Boolean = true) {
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
                        twoTabs = twoTabs,
                        isRemoteModel = { it.settings.startsWith("remote:") },
                        catalogConnectionSaved = connectionSaved,
                        localModelActions = LocalCatalogModelActions<String>(
                            currentModelId = old.id,
                            rename = { _, _, _, _ -> error("Unexpected local rename in import test") },
                            delete = { _, _, _ -> error("Unexpected local delete in import test") },
                            deleteSelected = { tickets, isCancelled, completed ->
                                Thread {
                                    val result = catalog.deleteSelections(tickets, isCancelled)
                                    activity.runOnUiThread { completed(result) }
                                }.start()
                            },
                        ),
                        fetchCatalog = { _, isCancelled, callback ->
                            cancelled = isCancelled; fetched = callback; true
                        },
                    )
                }
            }
        }
        ui.waitForIdle()
    }

    private fun startFetch(twoTabs: Boolean = true) {
        mainThread { fetched = null }
        if (twoTabs) selectProviderTab("模型")
        reveal("model-catalog-fetch").performClick()
        ui.waitUntil(5_000) { fetched != null }
    }

    private fun fetch() {
        startFetch()
        mainThread { checkNotNull(fetched).invoke(listOf(RemoteModel("existing", "Remote rename"), RemoteModel("add"), RemoteModel("unchecked"))) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("provider-model-selection").fetchSemanticsNodes().isNotEmpty() }
        awaitDialogEnter(node("dialog-confirm"))
        dismissIme()
    }

    fun testFetchAndConfirmationAppendOnlyCheckedWithoutSavingOrSelectingActiveModel() {
        show()
        fetch()
        assertEquals(listOf(old), catalog.current()!!.models)
        node("provider-model-option:existing").assertDoesNotExist()
        node("provider-model-list").performScrollToKey("add")
        awaitVisualIdle()
        node("provider-model-option:add").assertIsOff()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        node("provider-model-option:add").assertIsOn()
        node("dialog-confirm").assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        ui.waitUntil(5_000) { catalog.current()!!.models.size == 2 }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("provider-model-selection").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(old, SavedCatalogModel("add", "add", "defaults")), catalog.current()!!.models)
        ui.runOnIdle { assertEquals("existing", draft.model); assertEquals(0, saves) }
    }

    fun testFetchCleansObsoleteRemoteBeforeDialogAndCancelKeepsManualModel() {
        show()
        val before = catalog.current()!!
        val obsolete = SavedCatalogModel("obsolete", "Obsolete", "remote:custom")
        assertEquals(app.daiyu.android.model.catalog.CatalogCommitResult.COMMITTED,
            catalog.compareAndAppend(before.providerIdentity, before.revision, listOf(obsolete)) { false })
        fetch()
        assertEquals(listOf(old), catalog.current()!!.models)
        node("provider-model-option:existing").assertDoesNotExist()
        node("provider-model-option:obsolete").assertDoesNotExist()
        node("dialog-cancel").performClick()
        assertEquals(listOf(old), catalog.current()!!.models)
        ui.onNodeWithText("obsolete").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, saves) }
    }

    fun testOnlyCleanupRefreshesLocalListWithoutOpeningNewModelDialog() {
        show()
        val before = catalog.current()!!
        val obsolete = SavedCatalogModel("obsolete", "Obsolete", "remote:custom")
        assertEquals(app.daiyu.android.model.catalog.CatalogCommitResult.COMMITTED,
            catalog.compareAndAppend(before.providerIdentity, before.revision, listOf(obsolete)) { false })
        startFetch()
        mainThread { checkNotNull(fetched).invoke(listOf(RemoteModel("EXISTING"))) }
        ui.waitUntil(5_000) { catalog.current()!!.models == listOf(old) }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("已清理 1 个过时模型；没有可添加的新模型").fetchSemanticsNodes().isNotEmpty() }
        node("provider-model-selection").assertDoesNotExist()
        ui.onNodeWithText("Obsolete").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, saves) }
    }

    fun testUnsavedConnectionDoesNotStartFetchOrCleanup() {
        show()
        mainThread { connectionSaved = false }
        selectProviderTab("模型")
        reveal("model-catalog-fetch").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("请先保存提供商设置，再拉取模型").fetchSemanticsNodes().isNotEmpty() }
        assertNull(fetched)
        assertEquals(listOf(old), catalog.current()!!.models)
        node("provider-model-selection").assertDoesNotExist()
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
        // Keep this independent of tab-change cancellation.
        mainThread { draft = draft.copy(endpoint = "https://changed.invalid") }
        ui.waitForIdle()
        assertTrue(checkNotNull(cancelled).invoke())
        mainThread { late(listOf(RemoteModel("late"))) }
        ui.waitForIdle()
        node("provider-model-selection").assertDoesNotExist()
        assertEquals(listOf(old), catalog.current()!!.models)
    }

    fun testRevisionChangeWhileDialogOpenFailsClosed() {
        show()
        fetch()
        node("provider-model-list").performScrollToKey("add")
        awaitVisualIdle()
        node("provider-model-option:add").assertIsOff()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        node("provider-model-option:add").assertIsOn()
        catalog.configurationChange { }
        node("dialog-confirm").assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("provider-model-selection").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(old), catalog.current()!!.models)
        node("page:模型设置").performScrollToIndex(1)
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        ui.onNodeWithText("服务商或目录已变化，请重新获取").assertIsDisplayed()
        ui.runOnIdle { assertEquals("existing", draft.model); assertEquals(0, saves) }
    }

    fun testChangingTabsFencesLateCallbackWithoutSavingOrSelectingActiveModel() {
        show()
        reveal("settings-radio:配置").assertIsSelected()
        node("model-local-manage").assertDoesNotExist()
        startFetch()
        val late = checkNotNull(fetched)
        reveal("settings-radio:配置").performClick().assertIsSelected()
        assertTrue(checkNotNull(cancelled).invoke())
        mainThread { late(listOf(RemoteModel("late"))) }
        ui.waitForIdle()
        node("provider-model-selection").assertDoesNotExist()
        assertEquals(listOf(old), catalog.current()!!.models)
        ui.runOnIdle { assertEquals("existing", draft.model); assertEquals(0, saves) }
    }
    fun testSinglePageSaveFencesLateCatalogWithoutTabChangeOrDraftEdit() {
        show(twoTabs = false)
        startFetch(twoTabs = false)
        val late = checkNotNull(fetched)
        assertFalse(checkNotNull(cancelled).invoke())
        reveal("model-save").performClick()
        assertTrue(checkNotNull(cancelled).invoke())
        mainThread { late(listOf(RemoteModel("late-after-save"))) }
        ui.waitForIdle()
        node("provider-model-selection").assertDoesNotExist()
        assertEquals(listOf(old), catalog.current()!!.models)
        ui.runOnIdle { assertEquals(1, saves); assertEquals("existing", draft.model) }
    }

}
