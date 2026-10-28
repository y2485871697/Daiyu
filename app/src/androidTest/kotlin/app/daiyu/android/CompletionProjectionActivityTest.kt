package app.daiyu.android

import androidx.compose.runtime.State
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ConversationCompletionMarkers
import app.daiyu.android.ui.model.ConversationPaneUiState
import java.util.concurrent.TimeUnit

/** Real Main -> persisted runtime completion -> retained pane -> rendered-chat read acknowledgement. */
@Suppress("DEPRECATION")
class CompletionProjectionActivityTest : WiredActivityTestCase() {
    private fun call(name: String, vararg args: Any?): Any? = MainActivity::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(activity, *args)
    private fun generation() = MainActivity::class.java.getDeclaredField("editorGeneration").apply {
        isAccessible = true
    }.getLong(activity)
    private fun pane() = (MainActivity::class.java.getDeclaredField("paneState\$delegate").apply {
        isAccessible = true
    }.get(activity) as State<*>).value as ConversationPaneUiState

    fun testBackgroundPaneCompletionAndExactRenderedReadNeverClearNewerEvent() {
        val session = fixture.session("completed while pane open", holdSeconds = 120)
        setDraft("first request"); node("chat-send").performClick(); awaitTextSendResult(activity)
        val owner = checkNotNull(fixture.controller.active)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        session.release.countDown()
        awaitControl("durable terminal") { fixture.controller.active == null }
        awaitControl("completed retained pane projection") {
            pane().historyConversations.singleOrNull {
                it.id == owner.conversationId.value
            }?.hasCompletionMarker == true
        }
        ui.waitForIdle()
        val id = owner.conversationId
        lateinit var old: ConversationCompletionMarkers.Receipt
        mainThread {
            old = checkNotNull(ConversationCompletionMarkers.read(fixture.database.readableDatabase, id))
            assertTrue(pane().historyConversations.single { it.id == id.value }.hasCompletionMarker)
            // A pane/background refresh is not a chat read, even for the selected conversation.
            call("acknowledgeCompletion", old, generation(), fixture.controller.dataRevision)
            assertEquals(old, ConversationCompletionMarkers.read(fixture.database.readableDatabase, id))
        }
        backToChat(activity)
        ui.waitUntil(timeoutMillis = 5_000) {
            ConversationCompletionMarkers.read(fixture.database.readableDatabase, id) == null
        }
        mainThread {
            val run = RunId("second-completion")
            val user = Message(MessageId("second-user"), id, run, Message.USER_ROLE,
                listOf(MessageBlock.Text("user", "second request")), RunStatus.COMPLETED)
            val next = user.copy(id = MessageId("second-answer"), role = Message.ASSISTANT_ROLE,
                status = RunStatus.RUNNING, blocks = emptyList())
            fixture.database.send(user, next)
            fixture.database.save(next.copy(status = RunStatus.COMPLETED,
                blocks = listOf(MessageBlock.Text("body", "second answer"))))
            val fresh = checkNotNull(ConversationCompletionMarkers.read(fixture.database.readableDatabase, id))
            // Before the next UI frame, the old shown owner remains eligible but its token is not.
            call("acknowledgeCompletion", old, generation(), fixture.controller.dataRevision)
            assertEquals(fresh, ConversationCompletionMarkers.read(fixture.database.readableDatabase, id))
            call("acknowledgeCompletion", fresh, generation() - 1, fixture.controller.dataRevision)
            assertEquals(fresh, ConversationCompletionMarkers.read(fixture.database.readableDatabase, id))
            activity.select(fixture.database.conversations().single { it.id == id })
        }
        ui.waitUntil(timeoutMillis = 5_000) {
            ConversationCompletionMarkers.read(fixture.database.readableDatabase, id) == null
        }
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        awaitControl("acknowledged retained pane projection") {
            pane().historyConversations.singleOrNull { it.id == id.value }?.hasCompletionMarker == false
        }
        mainThread { assertFalse(pane().historyConversations.single { it.id == id.value }.hasCompletionMarker) }
        assertEquals(1, fixture.opens.get()) // Reading never sends/replays a request.
    }
}
