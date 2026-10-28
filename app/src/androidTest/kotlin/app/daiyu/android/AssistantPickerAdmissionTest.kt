package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.assistants.*
import app.daiyu.android.ui.screens.assistants.AssistantPickerDialog
import kotlinx.coroutines.flow.MutableStateFlow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class AssistantPickerAdmissionTest : WiredActivityTestCase() {
    private val writes = AtomicInteger()
    private val assetsWrites = AtomicInteger()
    private var admitted = true
    private var interactionsAllowed = true
    private val blockedInteractions = AtomicInteger()
    private val selections = AtomicInteger()
    private val dismissals = AtomicInteger()
    private lateinit var store: AssistantStore

    override fun setUp() {
        super.setUp()
        val profiles = MutableStateFlow(listOf(
            AssistantProfile("picker-a", "Picker A", "", createdAt = 1L),
            AssistantProfile("picker-b", "Picker B", "", createdAt = 2L)))
        store = object : AssistantStore {
            override val assistants = profiles
            override val selectedId = MutableStateFlow("picker-a")
            override fun save(profile: AssistantProfile): AssistantProfile {
                writes.incrementAndGet()
                profiles.value = profiles.value.filterNot { it.id == profile.id } + profile
                return profile
            }
            override fun delete(id: String) {
                writes.incrementAndGet()
                profiles.value = profiles.value.filterNot { it.id == id }
            }
            override fun select(id: String) { selectedId.value = id }
        }
        val assets = object : AssistantAssets by FileAssistantAssets(instrumentation.targetContext) {
            override fun copyMemory(fromId: String, toId: String) { assetsWrites.incrementAndGet() }
            override fun deleteMemory(id: String) { assetsWrites.incrementAndGet() }
            override fun deleteAvatar(fileName: String?) { assetsWrites.incrementAndGet() }
        }
        mainThread {
            activity.setContent {
                MiuixTheme {
                    AssistantPickerDialog(true, store, assets, { selections.incrementAndGet() }, {},
                        { dismissals.incrementAndGet() }, canMutate = { admitted }, canInteract = {
                            interactionsAllowed.also { if (!it) blockedInteractions.incrementAndGet() }
                        })
                }
            }
        }
    }

    fun testStaleCopyDoesNotReachStoreOrAssets() {
        ui.onNodeWithText("Picker A").performTouchInput { longClick() }
        mainThread { admitted = false }
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.ui_copy_assistant)).performClick()
        ui.waitForIdle()
        assertEquals(0, writes.get())
        assertEquals(0, assetsWrites.get())
        assertEquals(2, store.list().size)
    }

    fun testStaleDeleteConfirmationDoesNotReachStoreOrAssets() {
        ui.onNodeWithText("Picker A").performTouchInput { longClick() }
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.ui_delete_assistant)).performClick()
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.assistant_delete_title)).assertIsDisplayed()
        mainThread { admitted = false }
        ui.onNode(hasText(instrumentation.targetContext.getString(R.string.action_delete)) and hasClickAction())
            .assertIsDisplayed().assertIsEnabled().performClick()
        ui.waitForIdle()
        assertEquals(0, writes.get())
        assertEquals(0, assetsWrites.get())
        assertEquals(2, store.list().size)
    }

    fun testNewAssistantPlaceholderDoesNotMutateProfilesOrSelection() {
        ui.onNodeWithTag("assistant-new").assertIsDisplayed().performClick()
        ui.onNodeWithText("Picker A").assertIsDisplayed()
        ui.onNodeWithText("Picker B").assertIsDisplayed()
        assertEquals(0, writes.get())
        assertEquals(0, assetsWrites.get())
        assertEquals(2, store.list().size)
        assertEquals("picker-a", store.selectedId.value)
    }

    fun testCurrentCopyStillCopiesProfileAndMemory() {
        ui.onNodeWithText("Picker A").performTouchInput { longClick() }
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.ui_copy_assistant)).performClick()
        ui.waitUntil(5_000) { assetsWrites.get() == 1 }
        assertEquals(1, writes.get())
        assertEquals(3, store.list().size)
    }
    fun testRunningGateRejectsSelectionAndLongPressWithoutClosingThenUnlocks() {
        mainThread { interactionsAllowed = false }
        ui.onNodeWithTag("assistant-item-picker-b").performClick()
        ui.onNodeWithTag("assistant-item-picker-a").performTouchInput { longClick() }
        ui.waitForIdle()
        assertEquals(0, selections.get())
        assertEquals(0, dismissals.get())
        assertEquals(2, blockedInteractions.get())
        ui.onNodeWithTag("assistant-action-edit").assertDoesNotExist()
        ui.onNodeWithTag("assistant-item-picker-b").assertIsDisplayed()
        assertEquals("picker-a", store.selectedId.value)
        assertEquals(0, writes.get())
        mainThread { interactionsAllowed = true }
        ui.onNodeWithTag("assistant-item-picker-b").performClick()
        ui.waitUntil(5_000) { selections.get() == 1 && dismissals.get() == 1 }
    }

    fun testTaskStartedAfterDeleteConfirmationLeavesProfileAndAssetsUntouched() {
        ui.onNodeWithTag("assistant-item-picker-a").performTouchInput { longClick() }
        ui.onNodeWithTag("assistant-action-delete").performClick()
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.assistant_delete_title)).assertIsDisplayed()
        mainThread { interactionsAllowed = false }
        ui.onNode(hasText(instrumentation.targetContext.getString(R.string.action_delete)) and hasClickAction())
            .performClick()
        ui.waitForIdle()
        assertEquals(1, blockedInteractions.get())
        assertEquals(0, writes.get())
        assertEquals(0, assetsWrites.get())
        assertEquals(2, store.list().size)
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.assistant_delete_title)).assertIsDisplayed()
    }

}
