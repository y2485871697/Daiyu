package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.getOrNull
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.defaultMarkdownParser
import app.daiyu.android.ui.screens.chat.conversation.markdownProjectionCache
import java.util.UUID

/** Original Activity content and recovery worker: never manually warms or replaces the renderer. */
class RestoredMarkdownActivityTest : WiredActivityTestCase() {
    private lateinit var conversation: Conversation
    private lateinit var original: List<Pair<Long, Message>>
    private val marker = UUID.randomUUID().toString()
    private val source get() = "Restored **$marker**.\n\nSecond paragraph."
    private val rendered get() = "Restored $marker."

    override fun createFixture(): RunControlFixture {
        assertNull(markdownProjectionCache.getPrepared(listOf(source), defaultMarkdownParser))
        val result = RunControlFixture(instrumentation.targetContext, beforeController = { database, preferences ->
            conversation = database.create("default")
            val user = Message(MessageId("restored-user"), conversation.id, RunId("restored-run"), Message.USER_ROLE,
                listOf(MessageBlock.Text("question", "previous question")), RunStatus.COMPLETED)
            val answer = user.copy(id = MessageId("restored-answer"), role = Message.ASSISTANT_ROLE,
                blocks = listOf(MessageBlock.Text("first", "Restored **$marker**."),
                    MessageBlock.Text("second", "Second paragraph.")), status = RunStatus.RUNNING)
            database.send(user, answer)
            database.save(answer.copy(status = RunStatus.COMPLETED))
            check(preferences.edit().putString("selected", conversation.id.value).commit())
            original = database.messages(conversation.id)
        })
        try {
            awaitControl("history recovery ready before Activity mounts") { result.controller.ready }
            assertNotNull("Ready must already include the exact historical AST",
                markdownProjectionCache.getPrepared(listOf(source), defaultMarkdownParser))
            return result
        } catch (failure: Throwable) {
            result.close()
            throw failure
        }
    }

    fun testRestoredBodyIsPreparedBeforeActivityAndRemainsFormattedAfterReentry() {
        val body = ui.onNodeWithText(rendered, useUnmergedTree = true)
        ui.waitUntil(5_000) { ui.onAllNodesWithText(rendered, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        body.assertIsDisplayed()
        body.assert(SemanticsMatcher("restored bold span") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty()
                .any { text -> text.spanStyles.any { it.item.fontWeight == FontWeight.Bold } }
        })
        ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
        mainThread { activity.select(null) }
        ui.waitForIdle()
        ui.onNodeWithText(rendered, useUnmergedTree = true).assertDoesNotExist()
        mainThread { activity.select(conversation) }
        ui.waitForIdle()
        body.assertIsDisplayed()
        ui.onNodeWithText("Second paragraph.", useUnmergedTree = true).assertIsDisplayed()
        ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(original, fixture.database.messages(conversation.id))
        assertEquals(0, fixture.opens.get())
    }
}
