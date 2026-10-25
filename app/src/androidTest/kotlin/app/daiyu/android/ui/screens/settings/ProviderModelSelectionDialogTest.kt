package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.model.catalog.ModelSelectionState
import app.daiyu.android.model.catalog.RemoteModel
import app.daiyu.android.ui.app.AgentAppTheme

/** Standalone presentation contract tests; no fetch/current-model controller API is changed. */
class ProviderModelSelectionDialogTest : WiredActivityTestCase() {
    private val models = listOf(RemoteModel("alpha", "Same name"), RemoteModel("beta", "Same name"),
        RemoteModel("gamma", "Third model"))
    private val confirmations = mutableListOf<ModelSelectionState>()
    private var dismissals = 0
    private var savingState by mutableStateOf(false)

    private fun show(models: List<RemoteModel> = this.models, saving: Boolean = false,
        enabled: Boolean = true, failConfirmation: Boolean = false) {
        confirmations.clear()
        dismissals = 0
        mainThread {
            savingState = saving
            activity.setContent {
                var selection by androidx.compose.runtime.remember {
                    mutableStateOf(ModelSelectionState.capture("host-provider", 19, models))
                }
                var open by androidx.compose.runtime.remember { mutableStateOf(true) }
                var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
                AgentAppTheme(AppearanceSettings(), false) {
                    if (open) ProviderModelSelectionDialog(
                        selection = selection,
                        onSelectionChange = { selection = it },
                        onConfirm = {
                            confirmations += it
                            if (failConfirmation) error = "导入失败，原目录保留" else open = false
                        },
                        onDismiss = { dismissals++; open = false },
                        enabled = enabled,
                        isSaving = savingState,
                        errorText = error,
                    )
                }
            }
        }
        awaitDialogEnter(node("dialog-confirm"))
        awaitVisualIdle()
    }

    fun testEmptyConfirmationAndVisibleBulkSelectionCounts() {
        show()
        node("dialog-confirm").assertIsNotEnabled()
        node("provider-model-count").assertTextEquals("0/3")
        node("provider-model-select-all").performClick()
        node("dialog-confirm").assertIsEnabled()
        node("provider-model-count").assertTextEquals("3/3")
        node("provider-model-invert").performClick()
        node("dialog-confirm").assertIsNotEnabled()
        node("dialog-cancel").performClick()
        node("provider-model-selection").assertDoesNotExist()
        ui.runOnIdle { assertTrue(confirmations.isEmpty()); assertEquals(1, dismissals) }
    }

    fun testSearchUsesStableIdsAndPreservesHiddenSelections() {
        show()
        node("provider-model-option:alpha").performClick().assertIsOn()
        node("provider-model-option:beta").assertIsOff()
        val search = node("provider-model-search").assertIsDisplayed().assertIsEnabled().assert(hasSetTextAction())
        ui.onNodeWithTag("provider-model-search-icon", useUnmergedTree = true).assertIsDisplayed()
        search.performTextReplacement(" GAMMA ").assertEditableText(" GAMMA ")
        node("provider-model-count").assertTextEquals("1/3")
        node("provider-model-select-all").performClick()
        node("provider-model-count").assertTextEquals("2/3")
        node("provider-model-invert").performClick()
        node("provider-model-count").assertTextEquals("1/3")
        search.performTextReplacement("").assertEditableText("")
        dismissIme()
        node("provider-model-list").performScrollToKey("alpha")
        awaitVisualIdle()
        node("provider-model-option:alpha").assertIsDisplayed().assertIsOn()
        node("provider-model-list").performScrollToKey("beta")
        awaitVisualIdle()
        node("provider-model-option:beta").assertIsDisplayed().assertIsOff()
        node("dialog-confirm").assertIsEnabled().assertIsDisplayed().performClick()
        ui.runOnIdle {
            assertEquals("one real confirmation callback", 1, confirmations.size)
            val result = confirmations.single()
            assertEquals(setOf("alpha"), result.selectedIds)
            assertEquals("host-provider", result.providerIdentity)
            assertEquals(19L, result.expectedRevision)
            assertEquals(0, dismissals)
        }
    }

    fun testNoMatchesKeepsCountAndDisablesOnlyVisibleBulkActions() {
        show()
        node("provider-model-list").performScrollToKey("beta")
        awaitVisualIdle()
        node("provider-model-option:beta").assertIsDisplayed().performClick().assertIsOn()
        node("provider-model-count").assertTextEquals("1/3")
        node("provider-model-search").performTextReplacement("not present")
        node("provider-model-count").assertTextEquals("1/3")
        node("provider-model-empty").assertTextEquals("没有匹配的模型")
        node("provider-model-select-all").assertIsNotEnabled()
        node("provider-model-invert").assertIsNotEnabled()
        node("dialog-confirm").assertIsEnabled()
        node("dialog-cancel").performClick()
        ui.runOnIdle { assertTrue(confirmations.isEmpty()); assertEquals(1, dismissals) }
    }

    fun testBulkActionsAndCountShareRowAndFooterStaysBelowLongCatalog() {
        show(models = List(30) { RemoteModel("model-$it", "Model $it") })
        node("provider-model-count").assertTextEquals("0/30")
        val parts = listOf("provider-model-search", "provider-model-bulk-actions", "provider-model-select-all",
            "provider-model-invert", "provider-model-count", "provider-model-list", "dialog-cancel", "dialog-confirm")
            .associateWith { node(it).assertIsDisplayed().fetchSemanticsNode() }
        ui.runOnIdle {
            fun bounds(tag: String) = parts.getValue(tag).boundsInRoot
            val row = bounds("provider-model-bulk-actions")
            val all = bounds("provider-model-select-all")
            val invert = bounds("provider-model-invert")
            val count = bounds("provider-model-count")
            assertTrue("bulk actions stay left", all.center.x < row.center.x && invert.center.x < row.center.x)
            assertTrue("count stays right", count.center.x > row.center.x)
            assertTrue("controls do not overlap", all.right <= invert.left + 1f && invert.right <= count.left + 1f)
            assertTrue("count fits the row", count.right <= row.right + 1f)
            assertEquals("all and count share a row", all.center.y, count.center.y, 1f)
            assertEquals("invert and count share a row", invert.center.y, count.center.y, 1f)
            assertTrue("count is trailing", row.right - count.right < count.left - invert.right)
            assertTrue("search is above actions", bounds("provider-model-search").bottom <= row.top + 1f)
            assertTrue("footer stays below list", bounds("provider-model-list").bottom <=
                minOf(bounds("dialog-cancel").top, bounds("dialog-confirm").top) + 1f)
        }
        node("provider-model-list").performScrollToKey("model-29")
        awaitVisualIdle()
        node("provider-model-option:model-29").assertIsDisplayed().performClick().assertIsOn()
        node("provider-model-count").assertTextEquals("1/30")
        node("dialog-confirm").assertIsDisplayed().assertIsEnabled()
        node("dialog-cancel").assertIsDisplayed().assertIsEnabled().performClick()
        ui.runOnIdle { assertTrue(confirmations.isEmpty()); assertEquals(1, dismissals) }
    }

    fun testEmptyCatalogCannotConfirm() {
        show(models = emptyList())
        node("provider-model-empty").assertTextEquals("远端模型目录为空")
        node("dialog-confirm").assertIsNotEnabled()
        node("provider-model-select-all").assertIsNotEnabled()
        node("provider-model-invert").assertIsNotEnabled()
    }

    fun testHostFailureKeepsSelectionOpenForCancelWithoutSecondConfirm() {
        show(failConfirmation = true)
        node("provider-model-option:alpha").performClick()
        node("dialog-confirm").assertIsEnabled().assertIsDisplayed().performClick()
        ui.runOnIdle {
            assertEquals("one real confirmation callback", 1, confirmations.size)
            assertEquals(setOf("alpha"), confirmations.single().selectedIds)
            assertEquals(0, dismissals)
        }
        ui.onNodeWithText("导入失败，原目录保留").assertExists().assertIsDisplayed()
        node("provider-model-option:alpha").assertIsOn()
        node("dialog-cancel").assertIsEnabled().assertIsDisplayed().performClick()
        node("provider-model-selection").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, confirmations.size); assertEquals(1, dismissals) }
    }

    fun testSavingDismissRequestsDoNotCloseAfterSavingEnds() {
        show()
        node("provider-model-option:alpha").performClick().assertIsOn()
        mainThread { savingState = true }
        node("dialog-cancel").assertIsNotEnabled()
        awaitNodeWindowFocus(node("dialog-confirm"))
        val viewport = node("preview-dialog-viewport").fetchSemanticsNode()
        val sheet = node("chat-selection-sheet").fetchSemanticsNode()
        ui.runOnIdle {
            assertSame(viewport.root, sheet.root)
            assertTrue("mask tap is outside the sheet", viewport.boundsInRoot.top + 1f < sheet.boundsInRoot.top)
        }
        ui.mainClock.autoAdvance = false
        try {
            node("preview-dialog-scrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(1f, 1f)) }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            mainThread { savingState = false }
            ui.mainClock.advanceTimeBy(2_000)
        } finally {
            ui.mainClock.autoAdvance = true
        }
        awaitVisualIdle()
        node("provider-model-selection").assertIsDisplayed()
        node("provider-model-option:alpha").assertIsOn()
        node("provider-model-count").assertTextEquals("1/3")
        ui.runOnIdle { assertTrue(confirmations.isEmpty()); assertEquals(0, dismissals) }
        node("dialog-cancel").assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(1, dismissals) }
    }

    fun testSavingAndStaleHostDisableSelectionAndConfirmation() {
        show()
        node("provider-model-option:alpha").performClick().assertIsOn()
        node("dialog-confirm").assertIsEnabled()
        mainThread { savingState = true }
        listOf("provider-model-search", "provider-model-select-all", "provider-model-invert",
            "dialog-confirm", "dialog-cancel").forEach { node(it).assertIsDisplayed().assertIsNotEnabled() }
        node("provider-model-option:alpha").assertIsNotEnabled().performTouchInput { click() }
        listOf("provider-model-select-all", "provider-model-invert", "dialog-confirm", "dialog-cancel")
            .forEach { node(it).performTouchInput { click() } }
        node("provider-model-count").assertTextEquals("1/3")
        ui.runOnIdle { assertTrue(confirmations.isEmpty()); assertEquals(0, dismissals) }
        mainThread { savingState = false }
        node("provider-model-option:alpha").assertIsOn().assertIsEnabled()
        node("dialog-confirm").assertIsDisplayed().assertIsEnabled()
        node("dialog-cancel").assertIsDisplayed().assertIsEnabled()
        show(enabled = false)
        node("provider-model-option:alpha").assertIsNotEnabled()
        node("provider-model-search").assertIsNotEnabled()
        node("provider-model-select-all").assertIsNotEnabled()
        node("provider-model-invert").assertIsNotEnabled()
        node("dialog-confirm").assertIsNotEnabled()
        node("dialog-cancel").assertIsEnabled()
    }
}
