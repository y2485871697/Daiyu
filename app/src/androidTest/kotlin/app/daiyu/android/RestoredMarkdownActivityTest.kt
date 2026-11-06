package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.getOrNull
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.defaultMarkdownParser
import app.daiyu.android.ui.screens.chat.conversation.markdownProjectionCache
import java.util.UUID
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Original Activity content and asynchronous history load; no manual AST preparation. */
class RestoredMarkdownActivityTest : WiredActivityTestCase() {
    private lateinit var conversation: Conversation
    private lateinit var original: List<Pair<Long, Message>>
    private val marker = UUID.randomUUID().toString()
    private val sources get() = listOf("Restored **$marker**.", "Second paragraph.")
    private val rendered get() = "Restored $marker."

    override fun createFixture(): RunControlFixture {
        assertNull(markdownProjectionCache.getPrepared(sources, defaultMarkdownParser))
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
            assertNull("Ready must not wait for historical formatting",
                markdownProjectionCache.getPrepared(sources, defaultMarkdownParser))
            return result
        } catch (failure: Throwable) {
            result.close()
            throw failure
        }
    }

    fun testRestoredBodyIsFormattedAndRemainsFormattedAfterReentry() {
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
    fun testSlowInitialHistoryReadDoesNotBlockMainOrRestartOnRefresh() {
        ui.waitUntil(5_000) { ui.onAllNodesWithText(rendered, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val readOnMain = AtomicBoolean()
        val reader = activity.paneConversationReader
        try {
            mainThread {
                activity.paneConversationReader = { id ->
                    reads.incrementAndGet()
                    readOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                    reader(id)
                }
                MainActivity::class.java.getDeclaredField("selectionReady").apply { isAccessible = true }
                    .setBoolean(activity, false)
                MainActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(activity)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.controller.ready)
            mainThread {
                repeat(3) {
                    MainActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(activity)
                }
                assertEquals(conversation.id.value, fixture.controller.preferences.getString("selected", null))
            }
            assertEquals(1, reads.get())
            assertFalse(readOnMain.get())
            release.countDown()
            awaitControl("initial history selection completed") {
                MainActivity::class.java.getDeclaredField("selectionReady").apply { isAccessible = true }.getBoolean(activity)
            }
            ui.onNodeWithText(rendered, useUnmergedTree = true).assertIsDisplayed()
            assertEquals(original, fixture.database.messages(conversation.id))
            assertEquals(0, fixture.opens.get())
        } finally {
            release.countDown()
            mainThread { activity.paneConversationReader = reader }
        }
    }


    fun testPausedInitialReadRestartsOnResumeWithoutAnotherControllerEvent() {
        ui.waitUntil(5_000) { ui.onAllNodesWithText(rendered, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val reads = AtomicInteger()
        val reader = activity.paneConversationReader
        val ready = MainActivity::class.java.getDeclaredField("selectionReady").apply { isAccessible = true }
        val refresh = MainActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }
        try {
            mainThread {
                activity.paneConversationReader = { id ->
                    if (reads.incrementAndGet() == 1) {
                        entered.countDown()
                        try { check(release.await(30, TimeUnit.SECONDS)) } finally { drained.countDown() }
                    }
                    reader(id)
                }
                ready.setBoolean(activity, false)
                refresh.invoke(activity)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val request = MainActivity::class.java.getDeclaredField("paneSelectionRequest").apply { isAccessible = true }
            val job = MainActivity::class.java.getDeclaredField("paneSelectionJob").apply { isAccessible = true }
                .get(activity) as kotlinx.coroutines.Job
            realPauseResume(activity, atPause = {
                assertTrue(job.isCancelled)
                repeat(3) { refresh.invoke(activity) }
                assertFalse(ready.getBoolean(activity))
                assertNull("Background notifications must not restart cancelled initial reads", request.get(activity))
                assertEquals(1, reads.get())
                release.countDown()
            })
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            awaitControl("resume restarts and publishes initial selection") { ready.getBoolean(activity) }
            assertEquals(2, reads.get())
            ui.onNodeWithText(rendered, useUnmergedTree = true).assertIsDisplayed()
            assertEquals(original, fixture.database.messages(conversation.id))
            assertEquals(0, fixture.opens.get())
        } finally {
            release.countDown()
            mainThread { activity.paneConversationReader = reader }
        }
    }

    fun testFreshActivityRestoresStoppedLargeScreenHistoryWithoutReplay() {
        finishExistingMainActivities()
        val large = fixture.database.create("default")
        val user = Message(MessageId("large-user"), large.id, RunId("large-run"), Message.USER_ROLE,
            listOf(MessageBlock.Text("question", "synthetic screen history")), RunStatus.COMPLETED)
        val answer = user.copy(id = MessageId("large-answer"), role = Message.ASSISTANT_ROLE,
            blocks = emptyList(), status = RunStatus.RUNNING)
        fixture.database.send(user, answer)
        val output = largeScreenOutput()
        val blocks = List(7) { index ->
            val screen = index == 4 || index == 6
            MessageBlock.Tool(ToolInvocation(ToolCallId("synthetic-$index"), if (screen) "screen" else "synthetic_tool",
                if (screen) """{"operation":"observe"}""" else "{}"), if (index == 4) output else """{"ok":true}""")
        }
        val protocol = buildList {
            blocks.forEachIndexed { index, block ->
                if (index < 6) add(org.json.JSONObject().put("type", "reasoning").put("id", "reason-$index")
                    .put("summary", org.json.JSONArray()).toString())
                add(org.json.JSONObject().put("type", "function_call").put("call_id", block.invocation.id.value)
                    .put("name", block.invocation.name).put("arguments", block.invocation.arguments).toString())
                if (index < 6) add(org.json.JSONObject().put("type", "function_call_output")
                    .put("call_id", block.invocation.id.value).put("output", block.result).toString())
            }
        }
        val stopped = answer.copy(blocks = blocks, protocolItems = protocol, status = RunStatus.STOPPED,
            historySegments = listOf(MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, blocks, protocol),
                MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, emptyList(), emptyList())))
        fixture.database.save(stopped)
        assertEquals(19, protocol.size)
        assertTrue(stopped.blocks.all { it is MessageBlock.Tool })
        fixture.database.readableDatabase.rawQuery(
            "SELECT length(blocks),length(protocol),length(history_segments) FROM messages WHERE id=?",
            arrayOf(answer.id.value)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getLong(0) > 1_800_000)
            assertTrue(cursor.getLong(1) > 1_800_000)
            assertTrue(cursor.getLong(2) > 3_600_000)
        }
        check(fixture.controller.preferences.edit().putString("selected", large.id.value).commit())
        fun rawFields() = listOf("blocks", "protocol", "history_segments").map { column ->
            app.daiyu.android.storage.SQLiteText.read(fixture.database.readableDatabase, "messages", column, "id", answer.id.value)
        }
        val before = rawFields()
        activity = startMain()
        val ready = MainActivity::class.java.getDeclaredField("selectionReady").apply { isAccessible = true }
        ui.waitUntil(20_000) { ready.getBoolean(activity) }
        ui.onAllNodesWithText("加载会话中", useUnmergedTree = true).assertCountEquals(0)
        ui.onAllNodesWithText("恢复会话中", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(large.id.value, fixture.controller.preferences.getString("selected", null))
        ui.onNodeWithTag("work-process-header", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(before, rawFields())
        val restored = fixture.database.messages(large.id).last().second
        assertEquals(RunStatus.STOPPED, restored.status)
        assertEquals(blocks, restored.blocks)
        assertEquals(protocol, restored.protocolItems)
        assertEquals(stopped.historySegments, restored.historySegments)
        assertEquals(0, fixture.opens.get())
    }

    fun testDisplayPublishesRealBodyAndDraftWhileAuthorityReadIsBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readerField = MainActivity::class.java.getDeclaredField("loadedWindowReader").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val reader = readerField.get(activity) as (ConversationId, Long, Int) -> List<Pair<Long, Message>>
        val ready = MainActivity::class.java.getDeclaredField("selectionReady").apply { isAccessible = true }
        val shown = MainActivity::class.java.getDeclaredField("shown").apply { isAccessible = true }
        val display = MainActivity::class.java.getDeclaredField("displayWindow\$delegate").apply { isAccessible = true }
        val stateField = MainActivity::class.java.getDeclaredField("chatState\$delegate").apply { isAccessible = true }
        fun state() = (stateField.get(activity) as androidx.compose.runtime.MutableState<*>).value as
            app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
        mainThread {
            val blocked: (ConversationId, Long, Int) -> List<Pair<Long, Message>> = { id, before, limit ->
                entered.countDown()
                check(release.await(60, TimeUnit.SECONDS))
                reader(id, before, limit)
            }
            readerField.set(activity, blocked)
            ready.setBoolean(activity, false)
            MainActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(activity)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ui.waitUntil(5_000) { ui.onAllNodesWithText(rendered, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(rendered, useUnmergedTree = true).assertIsDisplayed()
            ui.onAllNodesWithText("加载会话中", useUnmergedTree = true).assertCountEquals(0)
            lateinit var keys: List<String>
            var scroll = 0L
            mainThread {
                assertTrue(ready.getBoolean(activity))
                assertNotNull((display.get(activity) as androidx.compose.runtime.MutableState<*>).value)
                assertTrue((shown.get(activity) as List<*>).isEmpty())
                keys = state().turns.map { it.listKey }
                scroll = state().scrollRequest!!.serial
            }
            node("chat-draft").performTextReplacement("edited while history is loading")
            release.countDown()
            awaitControl("authoritative window takes over") { (display.get(activity) as androidx.compose.runtime.MutableState<*>).value == null }
            mainThread {
                assertEquals(keys, state().turns.map { it.listKey })
                assertEquals(scroll, state().scrollRequest!!.serial)
                assertEquals("edited while history is loading", state().composer.text)
            }
            assertEquals(original, fixture.database.messages(conversation.id))
            assertEquals(0, fixture.opens.get())
        } finally {
            release.countDown()
            mainThread { readerField.set(activity, reader) }
        }
    }

    private fun largeScreenOutput(): String {
        val bitmap = android.graphics.Bitmap.createBitmap(1216, 2640, android.graphics.Bitmap.Config.ARGB_8888)
        val image = java.io.ByteArrayOutputStream().use { stream ->
            try {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            } finally { bitmap.recycle() }
        }
        // A valid ancillary PNG chunk makes a deterministic large fixture without real pixels.
        val padding = ByteArray(1_361_220 - image.size - 12)
        val type = "raNd".toByteArray(Charsets.US_ASCII)
        val crc = java.util.zip.CRC32().apply { update(type); update(padding) }.value.toInt()
        val chunk = java.io.ByteArrayOutputStream().apply {
            write(java.nio.ByteBuffer.allocate(4).putInt(padding.size).array()); write(type); write(padding)
            write(java.nio.ByteBuffer.allocate(4).putInt(crc).array())
        }.toByteArray()
        val png = image.copyOfRange(0, image.size - 12) + chunk + image.copyOfRange(image.size - 12, image.size)
        val encoded = java.util.Base64.getEncoder().encodeToString(png)
        assertEquals(1_814_960, encoded.length)
        return org.json.JSONObject().put("capture_id", "synthetic-cold-start").put("mime_type", "image/png")
            .put("width", 1216).put("height", 2640).put("data_base64", encoded)
            .put("screen_read_mode", "visual_fallback").put("node_tree_available", false)
            .put("node_failure_code", "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
            .put("visual_actions_available", true).toString()
    }

}
