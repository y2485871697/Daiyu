package app.daiyu.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.runtime.State
import app.daiyu.android.ui.model.ConversationPaneUiState
import app.daiyu.android.domain.Conversation
import app.daiyu.android.ui.actions.ConversationExportSelection
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class ConversationExportActivityTest : WiredActivityTestCase() {
    private fun pending() = MainActivity::class.java.getDeclaredField("conversationExport")
        .apply { isAccessible = true }.get(activity) as ConversationExportSelection

    private fun owner() = MainActivity::class.java.getDeclaredField("selected")
        .apply { isAccessible = true }.get(activity) as Conversation?

    private fun request() = MainActivity::class.java.getDeclaredField("pendingBackupRequest")
        .apply { isAccessible = true }.get(activity) as Int?

    private fun awaitHistory(ids: Set<String>) {
        awaitControl("drawer snapshot contains export targets") {
            val state = (MainActivity::class.java.getDeclaredField("paneState\$delegate")
                .apply { isAccessible = true }.get(activity) as State<*>).value as ConversationPaneUiState
            state.conversationsLoaded && state.historyConversations.map { it.id }.containsAll(ids)
        }
    }

    private fun canceledPicker(ids: Set<String>, action: () -> Unit) {
        val calls = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals("application/json", intent.type)
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertEquals(105, request())
                assertEquals(ids, pending().pendingConversationIds?.toSet())
                assertEquals(if (ids.size == 1) "daiyu-conversation.json" else "daiyu-conversations.json", intent.getStringExtra(Intent.EXTRA_TITLE))
                calls.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent())
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            action()
            awaitControl("one picker launch and cancellation consumed") { calls.get() == 1 && request() == null }
            assertEquals(1, calls.get())
            mainThread { assertNull(pending().pendingConversationIds) }
            node("conversation-export-choose").assertDoesNotExist()
        } finally { instrumentation.removeMonitor(monitor) }
    }

    fun testLongPressExportImmediatelyOpensSavePickerForThatConversationWithoutSwitchingOwner() {
        lateinit var current: Conversation
        lateinit var target: Conversation
        mainThread {
            current = fixture.database.create(); target = fixture.database.create()
            activity.select(current)
        }
        setDraft("keep current draft")
        val before = fixture.database.exportBackup()
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        val tag = "drawer-conversation:${target.id.value}"
        awaitHistory(setOf(target.id.value))
        node("drawer-list").performScrollToKey(target.id.value)
        awaitUniqueTag(tag)
        node(tag).performTouchInput { longClick() }
        canceledPicker(setOf(target.id.value)) {
            ui.onNodeWithText(instrumentation.targetContext.getString(R.string.conversation_export)).performClick()
        }
        mainThread { assertEquals(current.id, owner()?.id) }
        assertEquals(before, fixture.database.exportBackup()); assertEquals(0, fixture.opens.get())
    }

    fun testManagementExportsAllSelectedOwnersWithOnePickerAndCancelKeepsSelection() {
        lateinit var first: Conversation
        lateinit var last: Conversation
        mainThread {
            first = fixture.database.create(); last = fixture.database.create(); activity.select(first)
            fixture.database.rename(first.id, "batch-first"); fixture.database.rename(last.id, "batch-last")
        }
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        node("drawer-manage").performClick()
        node("drawer-export-selected").assertDoesNotExist()
        awaitHistory(setOf(first.id.value, last.id.value))
        for (conversation in listOf(first, last)) {
            node("drawer-list").performScrollToKey(conversation.id.value)
            awaitUniqueTag("drawer-conversation:${conversation.id.value}")
            node("drawer-conversation:${conversation.id.value}").performClick()
        }
        node("drawer-selection-count").assertTextEquals("已选择 2 项")
        node("drawer-search").performClick()
        node("drawer-search-input").performTextReplacement("batch-last")
        dismissIme()
        node("drawer-selection-count").assertTextEquals("已选择 2 项")
        val lastTag = "drawer-conversation:${last.id.value}"
        awaitUniqueTag(lastTag)
        node(lastTag).performTouchInput { longClick() }
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.conversation_export)).assertDoesNotExist()
        canceledPicker(setOf(first.id.value, last.id.value)) {
            ui.onNodeWithText(instrumentation.targetContext.getString(R.string.conversation_export_batch)).assertIsEnabled().performClick()
        }
        node("drawer-selection-count").assertTextEquals("已选择 2 项")
        node("drawer-export-selected").assertDoesNotExist()
        mainThread { assertEquals(first.id, owner()?.id) }
    }

    fun testManagementMenuTracksSelectionWithoutAddingTheLongPressedUnselectedRow() {
        lateinit var first: Conversation
        lateinit var second: Conversation
        lateinit var unselected: Conversation
        mainThread {
            first = fixture.database.create(); second = fixture.database.create(); unselected = fixture.database.create()
            activity.select(first)
        }
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        node("drawer-manage").performClick()
        awaitHistory(setOf(first.id.value, second.id.value, unselected.id.value))
        fun row(conversation: Conversation): SemanticsNodeInteraction {
            node("drawer-list").performScrollToKey(conversation.id.value)
            val tag = "drawer-conversation:${conversation.id.value}"
            awaitUniqueTag(tag)
            return node(tag)
        }
        row(first).performClick(); row(second).performClick()
        row(unselected).performTouchInput { longClick() }
        canceledPicker(setOf(first.id.value, second.id.value)) {
            ui.onNodeWithText(instrumentation.targetContext.getString(R.string.conversation_export_batch)).performClick()
        }
        node("drawer-selection-count").assertTextEquals("已选择 2 项")
        row(second).performClick()
        row(first).performTouchInput { longClick() }
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.conversation_export_batch)).assertDoesNotExist()
        canceledPicker(setOf(first.id.value)) {
            ui.onNodeWithText(instrumentation.targetContext.getString(R.string.conversation_export)).performClick()
        }
        row(first).performClick()
        node("drawer-selection-count").assertTextEquals("已选择 0 项")
        row(unselected).performTouchInput { longClick() }
        canceledPicker(setOf(unselected.id.value)) {
            ui.onNodeWithText(instrumentation.targetContext.getString(R.string.conversation_export)).performClick()
        }
        node("drawer-export-selected").assertDoesNotExist()
        mainThread { assertEquals(first.id, owner()?.id) }
        assertEquals(0, fixture.opens.get())
    }

    fun testPendingBatchSurvivesRecreationWithoutOpeningAnotherPickerAndCancelsOnlyOnce() {
        val ids = listOf("frozen-A", "frozen-C")
        mainThread {
            pending().begin(ids)
            MainActivity::class.java.getDeclaredField("pendingBackupRequest").apply { isAccessible = true }.set(activity, 105)
        }
        val calls = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CREATE_DOCUMENT) calls.incrementAndGet()
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            activity = recreateActivity(activity)
            mainThread {
                assertEquals(ids, pending().pendingConversationIds)
                assertEquals(105, request())
                val result = MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                result.invoke(activity, 105, Activity.RESULT_CANCELED, Intent())
                result.invoke(activity, 105, Activity.RESULT_CANCELED, Intent())
                assertNull(pending().pendingConversationIds); assertNull(request())
            }
            assertEquals(0, calls.get()); node("conversation-export-choose").assertDoesNotExist()
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
