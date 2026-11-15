package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import app.daiyu.android.*
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.ui.RetainedConversationData
import app.daiyu.android.ui.components.ConversationDrawerSection
import app.daiyu.android.ui.components.groupForDrawer
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

@Suppress("DEPRECATION")
class ConversationDateGroupingTest : WiredActivityTestCase() {
    private val zone get() = ZoneId.systemDefault()
    private val oldDate get() = LocalDate.now(zone).minusDays(8)
    private fun oldMillis() = oldDate.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun pair(id: ConversationId): Pair<Message, Message> {
        val suffix = UUID.randomUUID().toString()
        val user = Message(MessageId("user-$suffix"), id, RunId("run-$suffix"), Message.USER_ROLE,
            listOf(MessageBlock.Text("body", "question")), RunStatus.COMPLETED)
        return user to user.copy(id = MessageId("reply-$suffix"), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("answer", "answer")), status = RunStatus.RUNNING)
    }
    private fun target(id: ConversationId, role: String): MessageActionTarget {
        val done = CountDownLatch(1)
        val result = AtomicReference<MessageActionSnapshot?>()
        mainThread { assertTrue(fixture.controller.loadMessageActionSnapshot(id) { result.set(it); done.countDown() }) }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        return checkNotNull(result.get()).targets.values.last { it.role == role }
    }
    private fun action(kind: MessageAction, target: MessageActionTarget, text: String? = null): MessageActionResult {
        val done = CountDownLatch(1)
        val result = AtomicReference<MessageActionResult?>()
        mainThread { assertTrue(fixture.controller.messageAction(kind, target, replacementText = text) {
            result.set(it); done.countDown()
        }) }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        return checkNotNull(result.get())
    }
    private fun retained() = RetainedConversationData(fixture.database,
        ContextService(fixture.database, PrivateBlobs(fixture.application)))
    private fun assertOld(id: ConversationId, expected: Long) {
        val row = retained().summaries(null).single { it.id == id.value }
        assertEquals(expected, row.dateGroupMillis)
        assertFalse(row.isToday)
        assertEquals(ConversationDrawerSection.Dated(oldDate), listOf(row).groupForDrawer(zone).single().section)
        val reopened = ConversationDatabase(fixture.application)
        try { assertEquals(expected, reopened.conversations().single { it.id == id }.sidebarDateMillis) }
        finally { reopened.close() }
    }

    fun testMissingColumnMigrationUsesKnownCreationAndFreezesUnknownDateOnlyOnce() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,updated INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE usage_statistics_conversations(conversation_id TEXT PRIMARY KEY,created_at INTEGER)")
            db.execSQL("INSERT INTO conversations VALUES('known',300),('unknown',400)")
            db.execSQL("INSERT INTO usage_statistics_conversations VALUES('known',100),('unknown',NULL)")
            fun install() {
                db.beginTransaction()
                try { ConversationSidebarDates.install(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
            }
            install()
            db.execSQL("UPDATE conversations SET updated=900")
            install()
            db.rawQuery("SELECT id,sidebar_date FROM conversations ORDER BY id", null).use {
                assertTrue(it.moveToFirst()); assertEquals("known", it.getString(0)); assertEquals(100L, it.getLong(1))
                assertTrue(it.moveToNext()); assertEquals("unknown", it.getString(0)); assertEquals(400L, it.getLong(1))
            }
            assertTrue(db.rawQuery("SELECT created_at IS NULL FROM usage_statistics_conversations WHERE conversation_id='unknown'", null)
                .use { it.moveToFirst() && it.getInt(0) == 1 })
            db.execSQL("INSERT INTO conversations(id,updated) VALUES('new',1000)")
            db.execSQL("UPDATE conversations SET updated=2000 WHERE id='new'")
            assertEquals(1000L, db.rawQuery("SELECT sidebar_date FROM conversations WHERE id='new'", null)
                .use { check(it.moveToFirst()); it.getLong(0) })
        } finally { db.close() }
    }

    fun testSchema32UpgradeThenContinuationRenameAndPinKeepTheOriginalDay() {
        val scoped = testContext(instrumentation.targetContext, "sidebar-upgrade-${UUID.randomUUID()}-")
        var db = ConversationDatabase(scoped)
        try {
            val owner = db.create("default")
            val old = oldMillis()
            db.writableDatabase.execSQL("UPDATE usage_statistics_conversations SET created_at=? WHERE conversation_id=?",
                arrayOf<Any>(old, owner.id.value))
            db.writableDatabase.execSQL("UPDATE conversations SET sidebar_date=NULL,updated=? WHERE id=?",
                arrayOf<Any>(old + 1000, owner.id.value))
            db.writableDatabase.execSQL("DROP TRIGGER conversation_sidebar_date_insert")
            db.writableDatabase.version = 32
            db.close(); db = ConversationDatabase(scoped)
            assertEquals(33, db.readableDatabase.version)
            assertEquals(old, db.conversations().single().sidebarDateMillis)
            val (user, reply) = pair(owner.id)
            val accepted = db.send(user, reply)
            db.save(reply.copy(status = RunStatus.COMPLETED))
            assertEquals(old, accepted.sidebarDateMillis)
            assertTrue(accepted.updatedAt > old)
            db.rename(owner.id, "renamed"); db.organize(owner.id, "folder", true)
            val row = RetainedConversationData(db, ContextService(db, PrivateBlobs(scoped))).summaries(null).single()
            assertEquals(old, row.dateGroupMillis); assertFalse(row.isToday)
            assertEquals(ConversationDrawerSection.Pinned, listOf(row).groupForDrawer(zone).single().section)
            db.organize(owner.id, "folder", false)
            db.close(); db = ConversationDatabase(scoped)
            assertEquals(old, db.conversations().single().sidebarDateMillis)
        } finally { db.close(); scoped.deleteDatabase("conversations.db") }
    }

    fun testExplicitAndImplicitNewConversationsUseTheirOwnCreationDay() {
        val db = fixture.database
        val before = System.currentTimeMillis()
        val explicit = db.create("default")
        assertTrue(explicit.sidebarDateMillis in before..System.currentTimeMillis())
        val (user, reply) = pair(ConversationId(UUID.randomUUID().toString()))
        val implicit = db.send(user, reply, newConversation = true)
        db.save(reply.copy(status = RunStatus.COMPLETED))
        assertTrue(implicit.sidebarDateMillis in before..System.currentTimeMillis())
        assertEquals(setOf(explicit.id, implicit.id), db.conversations().map { it.id }.toSet())
        assertTrue(retained().summaries(null).all { it.isToday })
    }

    fun testEditingRegeneratingAndDeletingTailKeepTheStableDayButBranchGetsToday() {
        val db = fixture.database
        val owner = db.create("default")
        val old = oldMillis()
        db.writableDatabase.execSQL("UPDATE conversations SET sidebar_date=?,updated=? WHERE id=?",
            arrayOf<Any>(old, old, owner.id.value))
        val (user, reply) = pair(owner.id)
        db.send(user, reply); db.save(reply.copy(status = RunStatus.COMPLETED))
        assertOld(owner.id, old)
        for (kind in listOf(MessageAction.SAVE_EDIT, MessageAction.REGENERATE)) {
            val session = fixture.session("completed")
            val result = action(kind, target(owner.id, if (kind == MessageAction.SAVE_EDIT) Message.USER_ROLE else Message.ASSISTANT_ROLE),
                if (kind == MessageAction.SAVE_EDIT) "edited question" else null)
            assertTrue(result is MessageActionResult.Regenerated)
            assertEquals(RegenerationSendOutcome.ACCEPTED, (result as MessageActionResult.Regenerated).outcome)
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            session.release.countDown()
            awaitControl("date revision completed") { fixture.controller.active == null }
            assertOld(owner.id, old)
        }
        val before = System.currentTimeMillis()
        val branched = action(MessageAction.BRANCH, target(owner.id, Message.ASSISTANT_ROLE)) as MessageActionResult.Branch
        assertTrue(branched.conversation.sidebarDateMillis in before..System.currentTimeMillis())
        assertTrue(retained().summaries(null).single { it.id == branched.conversation.id.value }.isToday)
        assertOld(owner.id, old)
        assertTrue(action(MessageAction.DELETE_TURN, target(owner.id, Message.ASSISTANT_ROLE)) is MessageActionResult.Deleted)
        assertOld(owner.id, old)
    }

    fun testTextBackupPreservesStableDateAndOldTextUsesItsStoredDateNotImportTime() {
        val db = fixture.database
        val owner = db.create("default")
        val old = oldMillis()
        val updated = old + 86400000L
        db.writableDatabase.execSQL("UPDATE conversations SET sidebar_date=?,updated=? WHERE id=?",
            arrayOf<Any>(old, updated, owner.id.value))
        val text = db.exportConversationBackup(owner.id)
        assertEquals(4, JSONObject(text).getInt("version"))
        db.delete(owner.id); db.importBackup(text)
        assertEquals(old, db.conversations().single().sidebarDateMillis)
        val legacy = JSONObject(text)
        val payload = JSONObject(legacy.getString("payload"))
        payload.getJSONArray("conversations").getJSONObject(0).remove("sidebar_date")
        val raw = payload.toString()
        legacy.put("version", 3).put("payload", raw).put("sha256", MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
        db.delete(owner.id); db.importBackup(legacy.toString())
        assertEquals(updated, db.conversations().single().sidebarDateMillis)
    }

    fun testNeutralSingleAndMultiConversationPackagesPreserveTheStableDate() {
        val source = fixture.database
        val first = source.create("default")
        val second = source.create("default")
        val old = oldMillis()
        val dates = mapOf(first.id to old, second.id to old + 86400000L)
        for ((id, date) in dates) {
            source.writableDatabase.execSQL("UPDATE conversations SET sidebar_date=?,updated=? WHERE id=?",
                arrayOf<Any>(date, date + 86400000L, id.value))
        }
        val exporter = app.daiyu.android.backup.NeutralBackupService(fixture.application, source)
        val packages = listOf(exporter.exportPackage() to dates,
            exporter.exportConversation(first.id) to mapOf(first.id to old),
            exporter.exportConversations(listOf(first.id, second.id)) to dates)
        for ((text, expectedDates) in packages) {
            val scoped = testContext(instrumentation.targetContext, "sidebar-backup-${UUID.randomUUID()}-")
            val target = ConversationDatabase(scoped)
            try {
                val importer = app.daiyu.android.backup.NeutralBackupService(scoped, target)
                assertFalse(importer.importPackage(text).alreadyImported)
                val restored = target.conversations()
                assertEquals(expectedDates, restored.associate { it.id to it.sidebarDateMillis })
                assertTrue(restored.all { it.updatedAt == expectedDates.getValue(it.id) + 86400000L })
                assertTrue(importer.importPackage(text).alreadyImported)
                target.writableDatabase.execSQL("UPDATE conversations SET sidebar_date=sidebar_date+1")
                var rejected = false
                try { importer.importPackage(text) } catch (_: Exception) { rejected = true }
                assertTrue("Repeated import must detect a changed stable date", rejected)
            } finally { target.close(); scoped.deleteDatabase("conversations.db") }
        }
    }

    fun testSelectingAnOldConversationKeepsTheOpenDrawerAndItsScrollPosition() {
        val db = fixture.database
        val owners = (0 until 70).map { index -> db.create("default").also { db.rename(it.id, "history-$index") } }
        val historyDate = oldMillis()
        owners.forEachIndexed { index, owner ->
            db.writableDatabase.execSQL("UPDATE conversations SET sidebar_date=?,updated=? WHERE id=?",
                arrayOf<Any>(historyDate, historyDate + index, owner.id.value))
        }
        val wanted = owners.first()
        val firstVisible = owners.last()
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        awaitControl("sidebar snapshot contains seeded conversations") {
            val field = MainActivity::class.java.getDeclaredField("paneState\$delegate").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val pane = field.get(activity) as androidx.compose.runtime.MutableState<app.daiyu.android.ui.model.ConversationPaneUiState>
            pane.value.historyConversations.size == owners.size
        }
        node("drawer-conversation:${firstVisible.id.value}").assertIsDisplayed()
        node("drawer-list").scrollToDescendant(hasTestTag("drawer-conversation:${wanted.id.value}"))
        node("drawer-conversation:${firstVisible.id.value}").assertIsNotDisplayed()
        val top = node("drawer-conversation:${wanted.id.value}").assertIsDisplayed().getUnclippedBoundsInRoot().top.value
        node("drawer-conversation:${wanted.id.value}").performClick()
        awaitControl("selected historical owner published") {
            val selected = MainActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(activity) as? Conversation
            selected?.id == wanted.id
        }
        ui.waitForIdle()
        val restored = node("drawer-conversation:${wanted.id.value}").assertIsDisplayed().getUnclippedBoundsInRoot().top.value
        assertEquals(top, restored, 1f)
        mainThread { MainActivity::class.java.getDeclaredMethod("dismissSidebar").apply { isAccessible = true }.invoke(activity) }
        ui.waitForIdle()
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        assertEquals(top, node("drawer-conversation:${wanted.id.value}").assertIsDisplayed().getUnclippedBoundsInRoot().top.value, 1f)
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun screenBounds(tag: String): androidx.compose.ui.geometry.Rect {
        val target = node(tag)
        val semantics = target.fetchSemanticsNode()
        val view = (semantics.root as androidx.compose.ui.platform.ViewRootForTest).view
        return awaitStableWindowFrame(view) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            semantics.boundsInRoot.translate(location[0].toFloat(), location[1].toFloat())
        }
    }

    fun testFilterAndSortMenusSitAboveTheirOwnButtonsAndOnlyHighlightSelection() {
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        val filter = screenBounds("drawer-filter-button")
        val sort = screenBounds("drawer-sort-button")
        val assistant = screenBounds("drawer-assistant-button")
        assertTrue(filter.right <= sort.left + 1f)
        assertTrue(sort.right <= assistant.left + 1f)
        node("drawer-filter-button").performClick()
        node("drawer-choice-running").assertIsNotSelected()
        node("drawer-choice-completed").assertIsNotSelected()
        assertTrue(screenBounds("drawer-filter-menu").bottom <= filter.top)
        node("drawer-choice-running").performClick()
        node("drawer-choice-running").assertIsSelected()
        node("drawer-choice-completed").performClick()
        node("drawer-choice-running").assertIsSelected()
        node("drawer-choice-completed").assertIsSelected()
        node("drawer-filter-reset").performClick()
        node("drawer-choice-running").assertIsNotSelected()
        node("drawer-choice-completed").assertIsNotSelected()
        androidx.test.espresso.Espresso.pressBack()
        node("drawer-sort-button").performClick()
        node("drawer-choice-date").assertIsSelected()
        node("drawer-choice-forward").assertIsSelected()
        assertTrue(screenBounds("drawer-sort-menu").bottom <= sort.top)
        node("drawer-choice-last-output").performClick()
        node("drawer-choice-reverse").performClick()
        node("drawer-choice-last-output").assertIsSelected()
        node("drawer-choice-date").assertIsNotSelected()
        node("drawer-choice-reverse").assertIsSelected()
        node("drawer-choice-forward").assertIsNotSelected()
        androidx.test.espresso.Espresso.pressBack()
        node("drawer-sort-button").performClick()
        node("drawer-choice-last-output").assertIsSelected()
        node("drawer-choice-reverse").assertIsSelected()
    }

    private fun awaitPaneRows(ids: List<ConversationId>) {
        awaitControl("sidebar captured seeded rows and message metadata") {
            val field = MainActivity::class.java.getDeclaredField("paneState\$delegate").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = field.get(activity) as androidx.compose.runtime.MutableState<app.daiyu.android.ui.model.ConversationPaneUiState>
            ids.all { id -> state.value.historyConversations.any { it.id == id.value } }
        }
    }

    fun testCompletionFilterAndResetOperateOnFullDrawerWithoutOpeningAssistant() {
        val db = fixture.database
        val completed = db.create("default")
        val ordinary = db.create("default")
        val (user, reply) = pair(completed.id)
        db.send(user, reply); db.save(reply.copy(status = RunStatus.COMPLETED))
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        awaitPaneRows(listOf(completed.id, ordinary.id))
        node("drawer-conversation:${completed.id.value}").assertIsDisplayed()
        node("drawer-conversation:${ordinary.id.value}").assertIsDisplayed()
        node("drawer-filter-button").performClick()
        node("drawer-choice-completed").performClick()
        androidx.test.espresso.Espresso.pressBack()
        node("drawer-conversation:${completed.id.value}").assertIsDisplayed()
        node("drawer-conversation:${ordinary.id.value}").assertDoesNotExist()
        node("drawer-filter-button").performClick()
        node("drawer-filter-reset").performClick()
        androidx.test.espresso.Espresso.pressBack()
        node("drawer-conversation:${completed.id.value}").assertIsDisplayed()
        node("drawer-conversation:${ordinary.id.value}").assertIsDisplayed()
        node("drawer-filter-button").performClick()
        node("drawer-choice-running").performClick()
        androidx.test.espresso.Espresso.pressBack()
        node("drawer-conversation:${completed.id.value}").assertDoesNotExist()
        node("drawer-conversation:${ordinary.id.value}").assertDoesNotExist()
        node("drawer-assistant-button").assertIsDisplayed().performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("assistant-sheet").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        node("assistant-sheet").assertIsDisplayed()
    }

    fun testLastOutputSortingSkipsRunningReplyAndCanReverseWithoutChangingStoredDates() {
        val db = fixture.database
        fun seed(title: String, day: Long, userAt: Long, replyAt: Long, running: Boolean): Conversation {
            val owner = db.create("default")
            val (question, answer) = pair(owner.id)
            db.send(question.copy(createdAt = userAt), answer.copy(createdAt = replyAt))
            if (!running) db.save(answer.copy(createdAt = replyAt, status = RunStatus.COMPLETED))
            db.rename(owner.id, title)
            db.writableDatabase.execSQL("UPDATE conversations SET sidebar_date=? WHERE id=?", arrayOf<Any>(day, owner.id.value))
            return owner
        }
        val older = seed("older-day-new-output", oldMillis(), 1000L, 3000L, false)
        val newer = seed("newer-day-running", oldMillis() + 86400000L, 2000L, 9000L, true)
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        awaitPaneRows(listOf(older.id, newer.id))
        assertTrue(screenBounds("drawer-conversation:${newer.id.value}").top < screenBounds("drawer-conversation:${older.id.value}").top)
        node("drawer-sort-button").performClick()
        node("drawer-choice-last-output").performClick()
        androidx.test.espresso.Espresso.pressBack()
        assertTrue(screenBounds("drawer-conversation:${older.id.value}").top < screenBounds("drawer-conversation:${newer.id.value}").top)
        node("drawer-sort-button").performClick()
        node("drawer-choice-reverse").performClick()
        androidx.test.espresso.Espresso.pressBack()
        assertTrue(screenBounds("drawer-conversation:${newer.id.value}").top < screenBounds("drawer-conversation:${older.id.value}").top)
        assertEquals(oldMillis(), db.conversations().single { it.id == older.id }.sidebarDateMillis)
    }

    fun testClosingDrawerDismissesTheOpenMenuAndDoesNotReopenItOnNextVisit() {
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        node("drawer-filter-button").performClick()
        node("drawer-filter-menu").assertIsDisplayed()
        mainThread { MainActivity::class.java.getDeclaredMethod("dismissSidebar").apply { isAccessible = true }.invoke(activity) }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("drawer-filter-menu").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        node("drawer-filter-button").assertIsDisplayed()
        node("drawer-filter-menu").assertDoesNotExist()
        node("drawer-sort-menu").assertDoesNotExist()
    }

}
