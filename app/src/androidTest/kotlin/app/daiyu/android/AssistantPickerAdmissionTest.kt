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
                    AssistantPickerDialog(true, store, assets, {}, {}, {}, canMutate = { admitted })
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
}
