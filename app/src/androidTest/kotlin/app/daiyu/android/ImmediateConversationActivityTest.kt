package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.domain.Conversation
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.ui.model.ConversationReasoningPreference

/** Explicit New chat persists an empty owner without opening a model session. */
class ImmediateConversationActivityTest : WiredActivityTestCase() {
    private fun selectedConversation() = MainActivity::class.java.getDeclaredField("selected")
        .apply { isAccessible = true }.get(activity) as Conversation?

    private fun hostDraft() = MainActivity::class.java.getDeclaredMethod("getDraft")
        .apply { isAccessible = true }.invoke(activity) as String

    private fun assertEmptySelected(): Conversation {
        lateinit var saved: Conversation
        mainThread {
            saved = checkNotNull(selectedConversation())
            assertEquals(saved.id.value, fixture.controller.preferences.getString("selected", null))
            assertEquals("", hostDraft())
            assertEquals("", fixture.database.conversations().single { it.id == saved.id }.draft)
            assertTrue(fixture.database.messages(saved.id).isEmpty())
            assertEquals(fixture.database.selectedAssistantId(), saved.assistantId)
        }
        assertEquals(0, fixture.opens.get())
        return saved
    }

    fun testMenuAndSidebarImmediatelyPersistSeparateEmptyConversations() {
        assertTrue(fixture.database.conversations().isEmpty())
        ui.onNodeWithContentDescription("更多").performClick()
        node("chat-menu:NEW_CHAT").performClick()
        val first = assertEmptySelected()
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        node("drawer-new").performClick()
        val second = assertEmptySelected()
        assertTrue(first.id != second.id)
        assertEquals(setOf(first.id, second.id), fixture.database.conversations().map { it.id }.toSet())
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        awaitUniqueTag("drawer-conversation:${second.id.value}")
        node("drawer-conversation:${second.id.value}").assertIsDisplayed().assertIsSelected()
    }

    fun testNewOwnerPreservesSourceDraftAndCopiesReasoningOnce() {
        val reasoning = ConversationReasoningPreference(fixture.controller.preferences)
        lateinit var source: Conversation
        mainThread {
            source = fixture.database.create(fixture.database.selectedAssistantId())
            fixture.database.draft(source.id, "原会话草稿")
            reasoning.set(source.id.value, ReasoningEffort.HIGH)
            activity.select(source)
            activity.createNewConversation()
        }
        val created = assertEmptySelected()
        assertEquals("原会话草稿", fixture.database.conversations().single { it.id == source.id }.draft)
        assertEquals(ReasoningEffort.HIGH, reasoning.snapshot(created.id.value))
        reasoning.set(created.id.value, ReasoningEffort.LOW)
        assertEquals(ReasoningEffort.HIGH, reasoning.snapshot(source.id.value))
    }

    fun testIndependentComposerIsNotMovedIntoExplicitNewConversation() {
        setDraft("独立草稿")
        mainThread { activity.createNewConversation() }
        val created = assertEmptySelected()
        assertEquals("独立草稿", fixture.database.draft())
        assertNull(fixture.database.workspaceBinding(created.id))
        mainThread { activity.select(null) }
        mainThread { assertEquals("独立草稿", hostDraft()) }
        assertEquals(1, fixture.database.conversations().size)
    }

    fun testEmptyConversationSurvivesDatabaseReopenAndActivityRecreation() {
        mainThread { activity.createNewConversation() }
        val created = assertEmptySelected()
        ConversationDatabase(fixture.application).use { reopened ->
            assertEquals(created.id, reopened.conversations().single().id)
            assertTrue(reopened.messages(created.id).isEmpty())
        }
        activity = recreateActivity(activity)
        awaitControl("empty owner restored") { selectedConversation()?.id == created.id }
        assertEquals(created.id, assertEmptySelected().id)
    }

    fun testIgnoredCreateDoesNotPublishOwnerOrLoseOriginalDraft() {
        lateinit var source: Conversation
        mainThread {
            source = fixture.database.create(fixture.database.selectedAssistantId())
            fixture.database.draft(source.id, "保存的草稿")
            activity.select(source)
            fixture.database.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER ignore_new_conversation BEFORE INSERT ON conversations BEGIN SELECT RAISE(IGNORE); END")
            try {
                try {
                    activity.createNewConversation()
                    fail("ignored insert must not publish a new owner")
                } catch (_: IllegalStateException) { }
            } finally { fixture.database.writableDatabase.execSQL("DROP TRIGGER ignore_new_conversation") }
            assertEquals(source.id, selectedConversation()?.id)
            assertEquals("保存的草稿", hostDraft())
        }
        assertEquals(source.id, fixture.database.conversations().single().id)
        assertEquals(0, fixture.opens.get())
    }

    fun testFallbackSelectionDoesNotCreateAConversation() {
        mainThread { repeat(2) { activity.select(null) } }
        assertTrue(fixture.database.conversations().isEmpty())
        assertNull(fixture.controller.preferences.getString("selected", null))
        assertEquals(0, fixture.opens.get())
    }
}
