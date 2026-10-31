package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Hold real model preparation before SQLite acceptance; UI feedback must not wait for it. */
class ChatSubmissionActivityTest : WiredActivityTestCase() {
    private val preparing = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val reject = AtomicBoolean()
    internal override fun createFixture() = RunControlFixture(instrumentation.targetContext) {
        preparing.countDown()
        check(release.await(30, TimeUnit.SECONDS))
        check(!reject.get()) { "test preparation rejection" }
    }
    override fun tearDown() { release.countDown(); super.tearDown() }
    private fun state() = MainActivity::class.java.declaredMethods
        .single { it.name == "getChatState" && it.parameterCount == 0 }
        .apply { isAccessible = true }.invoke(activity) as ChatScreenState
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun regenerateBeforeReceipt(): Pair<Conversation, List<Pair<Long, Message>>> {
        val conversation = fixture.database.create("default")
        val user = Message(MessageId("preview-user"), conversation.id, RunId("preview-run"), Message.USER_ROLE,
            listOf(MessageBlock.Text("body", "original question")), RunStatus.COMPLETED)
        val answer = user.copy(id = MessageId("preview-answer"), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("answer", "original answer")), status = RunStatus.RUNNING)
        fixture.database.send(user, answer)
        fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
        mainThread { activity.select(conversation) }
        awaitControl("message snapshot") { field("messageActionSnapshot") != null && !fixture.controller.dataBusy }
        ui.onNodeWithTag("chat-turn:preview-answer").assertExists()
        val original = fixture.database.messages(conversation.id)
        mainThread { retainedControls(activity).onRegenerate("preview-answer") }
        assertTrue(preparing.await(5, TimeUnit.SECONDS))
        awaitControl("preview published during preparation") { state().submissionPreview?.replaceFromKey != null }
        ui.onNodeWithTag("chat-turn:preview-answer").assertDoesNotExist()
        ui.onNodeWithTag("chat-turn:preview-user").assertExists()
        assertEquals(original, fixture.database.messages(conversation.id))
        assertNull(fixture.controller.active)
        assertEquals(0, fixture.opens.get())
        mainThread { assertTrue(state().statusText.orEmpty().contains("重新生成")) }
        return conversation to original
    }

    fun testRegenerationHidesReplyWhilePreparingAndRestoresItOnRejection() {
        reject.set(true)
        val (conversation, original) = regenerateBeforeReceipt()
        release.countDown()
        awaitControl("preparation rejection") { !fixture.controller.dataBusy && fixture.controller.regenerationPreview() == null }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-turn:preview-answer").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(original, fixture.database.messages(conversation.id))
        assertEquals(0, fixture.opens.get())
    }

    fun testAcceptedRegenerationReplacesPreviewOnlyAfterExactNewReplyIsPublished() {
        val session = fixture.session(holdSeconds = 120)
        val (conversation, _) = regenerateBeforeReceipt()
        release.countDown()
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(fixture.controller.active)
        awaitControl("exact regenerated owner displayed") { state().turns.any { it.id == owner.id.value } }
        ui.onNodeWithTag("chat-turn:preview-answer").assertDoesNotExist()
        ui.onNodeWithTag("chat-turn:${owner.id.value}").assertExists()
        assertFalse(fixture.database.messages(conversation.id).any { it.second.id.value == "preview-answer" })
        assertEquals(1, fixture.opens.get())
        session.release.countDown()
        awaitControl("regenerated run finished") { fixture.controller.active == null }
    }
}
