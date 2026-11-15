package app.daiyu.android.pet

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.domain.RequestReasoningEffort
import app.daiyu.android.domain.pet.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.IndependentModelControls
import app.daiyu.android.ui.screens.settings.PetSettingsContent

class PetThinkingPickerActivityTest : WiredActivityTestCase() {
    private var pet: PetHost? = null
    private var modelChoices = 0
    private var modelRequests = 0
    private fun mount() {
        mainThread {
            pet = PetHost(fixture.application, PetModelPort { _, _ -> modelRequests++; PetCancel {} }, onOpenSettings = {})
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), false) {
                    PetSettingsContent(checkNotNull(pet), IndependentModelControls(true, false, "国模 / deepseek-v4.1-flash",
                        null, {}, { modelChoices++ }), Modifier, {}, {}, {})
                }
            }
        }
        awaitUniqueTag("pet-options-toggle")
        node("pet-options-toggle").performClick()
        awaitUniqueTag("pet-thinking-button")
    }
    private fun openPicker() {
        node("pet-thinking-button").performClick()
        awaitUniqueTag("chat-thinking-title")
        awaitDialogEnter(node("chat-thinking-title"))
        awaitNodeWindowFocus(node("chat-thinking-title"))
    }
    private fun closePicker() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("chat-thinking-title", useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
    }
    override fun cleanupFixture() {
        mainThread { pet?.close(); pet = null }
        super.cleanupFixture()
    }
    fun testChatThinkingButtonIsBeforeModelChevronAndOpensOnlyTheSharedPicker() {
        mount()
        val button = node("pet-thinking-button").fetchSemanticsNode().boundsInRoot
        val arrow = node("pet-model-chevron").fetchSemanticsNode().boundsInRoot
        assertTrue(button.right <= arrow.left + 1f)
        assertTrue(kotlin.math.abs(button.center.y - arrow.center.y) <= 1f)
        openPicker()
        node("chat-thinking-value").assertTextEquals("关闭")
        ui.onNodeWithText("调整思考深度").assertIsDisplayed()
        for (label in listOf("上下文窗口", "上下文压缩", "记忆", "下一次主请求生效")) {
            ui.onNodeWithText(label, substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
        assertEquals(0, modelChoices); assertEquals(0, modelRequests)
        closePicker()
        node("pet-model-configure").performClick()
        assertEquals(1, modelChoices)
    }
    fun testSevenPetOnlyStopsPersistWithoutSendingOrChangingChatEffort() {
        fixture.controller.reasoningPreference.set(null, ReasoningEffort.LOW)
        mount(); openPicker()
        RequestReasoningEffort.entries.forEachIndexed { index, effort ->
            node("thinking-depth-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
            node("chat-thinking-value").assertTextEquals(effort.displayName)
            mainThread { assertEquals(effort, checkNotNull(pet).snapshot().pet.state.settings.reasoningEffort) }
            assertEquals(ReasoningEffort.LOW, fixture.controller.reasoningPreference.snapshot(null))
        }
        assertEquals(0, modelRequests); assertEquals(0, modelChoices)
        closePicker(); openPicker()
        node("chat-thinking-value").assertTextEquals("极高")
        closePicker()
        mainThread {
            assertEquals(RequestReasoningEffort.MAX, AndroidPetStore(fixture.application).load().settings.reasoningEffort)
            checkNotNull(pet).close()
            pet = PetHost(fixture.application, PetModelPort { _, _ -> modelRequests++; PetCancel {} }, onOpenSettings = {})
            assertEquals(RequestReasoningEffort.MAX, checkNotNull(pet).snapshot().pet.state.settings.reasoningEffort)
        }
        assertEquals(ReasoningEffort.LOW, fixture.controller.reasoningPreference.snapshot(null))
        assertEquals(0, modelRequests)
    }
}
