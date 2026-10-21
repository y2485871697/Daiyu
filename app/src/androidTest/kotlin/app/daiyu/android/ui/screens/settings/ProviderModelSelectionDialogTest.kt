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

    private fun show(models: List<RemoteModel> = this.models, saving: Boolean = false,
        enabled: Boolean = true, failConfirmation: Boolean = false) {
        confirmations.clear()
        dismissals = 0
        mainThread {
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
                        isSaving = saving,
                        errorText = error,
                    )
                }
            }
        }
        ui.waitForIdle()
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
        node("provider-model-search").performTextReplacement(" GAMMA ")
        node("provider-model-count").assertTextEquals("1/3")
        node("provider-model-select-all").performClick()
        node("provider-model-count").assertTextEquals("2/3")
        node("provider-model-invert").performClick()
        node("provider-model-count").assertTextEquals("1/3")
        node("provider-model-search").performTextReplacement("")
        node("provider-model-option:alpha").assertIsOn()
        node("provider-model-option:beta").assertIsOff()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
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
        node("provider-model-option:beta").performClick()
        node("provider-model-search").performTextReplacement("not present")
        node("provider-model-count").assertTextEquals("1/3")
        node("provider-model-empty").assertTextEquals("没有匹配的模型")
        node("provider-model-select-all").assertIsNotEnabled()
        node("provider-model-invert").assertIsNotEnabled()
        node("dialog-confirm").assertIsEnabled()
        node("dialog-cancel").performClick()
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

    fun testSavingAndStaleHostDisableSelectionAndConfirmation() {
        show(saving = true)
        node("provider-model-option:alpha").assertIsNotEnabled()
        node("provider-model-search").assertIsNotEnabled()
        node("dialog-confirm").assertIsNotEnabled()
        node("dialog-cancel").assertIsNotEnabled()
        show(enabled = false)
        node("provider-model-option:alpha").assertIsNotEnabled()
        node("dialog-confirm").assertIsNotEnabled()
        node("dialog-cancel").assertIsEnabled()
    }
}
