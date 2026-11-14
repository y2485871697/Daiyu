package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import app.daiyu.android.model.catalog.CatalogLocalEditResult
import app.daiyu.android.model.catalog.selectionFor
import app.daiyu.android.ui.components.DialogActionLease
import app.daiyu.android.ui.components.RetainedWorkspaceAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real host retains presentation, not an executable grant or a replayed user action. */
class OrdinaryDialogLifecycleActivityTest : WiredActivityTestCase(object : androidx.compose.ui.MotionDurationScale {
    override val scaleFactor = 1f
}) {
    private val confirmedGptSpeedField = MainActivity::class.java.getDeclaredField("confirmedGptSpeed").apply { isAccessible = true }
    private fun confirmedGptSpeed(): ChatGptSpeedSelection? = confirmedGptSpeedField.get(activity) as ChatGptSpeedSelection?
    private fun chatModelPicker(): app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerState? =
        invokeHost(activity, "getChatModelPicker") as app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerState?
    private fun lease(): DialogActionLease? = invokeHost(activity, "getOrdinaryDialogLease") as DialogActionLease?
    private fun activeLease(): DialogActionLease {
        lateinit var current: DialogActionLease
        mainThread { current = checkNotNull(lease()); assertTrue(current.isActive) }
        return current
    }
    private fun assertRenewed(previous: DialogActionLease) {
        val current = activeLease()
        assertFalse(previous.isActive)
        assertNotSame(previous, current)
    }
    private fun pauseAndReturn() {
        val previous = activeLease()
        realPauseResume(activity, atPause = { assertFalse(previous.isActive) })
        assertRenewed(previous)
    }
    private fun homeAndReturn() {
        val previous = activeLease()
        val original = activity
        val stopped = CountDownLatch(1)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopped.countDown()
        }
        mainThread { original.lifecycle.addObserver(observer) }
        try {
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            assertTrue("Original host reached STOP", stopped.await(5, TimeUnit.SECONDS))
            assertFalse(previous.isActive)
        } finally {
            mainThread { original.lifecycle.removeObserver(observer) }
            restoreOriginalHost(original)
        }
        awaitHostWindowFocus(original)
        assertRenewed(previous)
    }
    private fun openWorkspace() {
        awaitControl("chat local readers idle") { !fixture.controller.dataBusy }
        awaitHostWindowFocus(activity)
        dismissIme()
        val distance = 80f * activity.resources.displayMetrics.density
        node("chat-workspace-gesture-area").performTouchInput { swipe(center, center + Offset(0f, -distance)) }
        awaitUniqueTag("conversation-workspace-sheet")
    }
    private fun loadedDirectory(path: String) {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            val current = ui.onAllNodes(hasTestTag("workspace-current-path") and hasText(path)).fetchSemanticsNodes()
            current.size == 1 && ui.onAllNodes(hasTestTag("workspace-save") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        node("workspace-current-path").assertTextEquals(path)
        node("workspace-error").assertDoesNotExist()
    }
    fun testWorkspaceManagementSurvivesPauseAndStopButOldClickNeverRevives() {
        setDraft("后台返回必须保留的草稿")
        openWorkspace()
        val originalBackup = fixture.database.exportBackup()
        val oldAdd = node("workspace-add").fetchSemanticsNode().config[RetainedWorkspaceAction]
        val oldLease = activeLease()
        repeat(2) {
            pauseAndReturn()
            awaitNodeWindowFocus(node("workspace-add"))
            node("conversation-workspace-sheet").assertIsDisplayed()
            homeAndReturn()
            awaitNodeWindowFocus(node("workspace-add"))
            mainThread { oldAdd() }
            node("workspace-current-path").assertDoesNotExist()
            assertEquals(originalBackup, fixture.database.exportBackup())
            assertRenewed(oldLease)
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitHostWindowFocus(activity)
        assertDraft("后台返回必须保留的草稿")
        pauseAndReturn()
        node("conversation-workspace-sheet").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
    fun testUnsavedWorkspaceDirectorySurvivesPauseAndHomeWithoutSaving() {
        val root = java.io.File(fixture.application.filesDir, "workspace").apply { mkdirs() }
        val child = java.io.File(root, "retained-choice").apply { mkdirs() }
        val marker = java.io.File(child, "marker").apply { writeText("unchanged") }
        openWorkspace()
        node("workspace-add").performClick()
        loadedDirectory("/workspace")
        node("workspace-folder:retained-choice").performClick()
        loadedDirectory("/workspace/retained-choice")
        val oldSave = node("workspace-save").fetchSemanticsNode().config[RetainedWorkspaceAction]
        val before = fixture.database.workspaceBinding(null)
        repeat(2) {
            pauseAndReturn()
            loadedDirectory("/workspace/retained-choice")
            homeAndReturn()
            loadedDirectory("/workspace/retained-choice")
            mainThread {
                val beforeVisit = invokeHost(activity, "getWorkspaceVisit")
                oldSave()
                // Admission replaces the visit synchronously, before launching the save worker.
                assertSame(beforeVisit, invokeHost(activity, "getWorkspaceVisit"))
            }
            assertEquals(before, fixture.database.workspaceBinding(null))
            assertEquals("unchanged", marker.readText())
        }
        node("workspace-save").performClick()
        awaitControl("fresh workspace selection saved") { fixture.database.workspaceBinding(null) != before }
        assertTrue(checkNotNull(fixture.database.workspaceBinding(null)).contains("retained-choice"))
        assertEquals("unchanged", marker.readText())
        assertEquals(0, fixture.opens.get())
    }
    fun testOpeningChatModelsDuringLongHistoryFlingKeepsBackgroundScroll() {
        val db = fixture.database
        val conversation = db.create("default")
        repeat(70) { i ->
            val user = app.daiyu.android.domain.Message(
                app.daiyu.android.domain.MessageId("dialog-fling-user-$i"), conversation.id,
                app.daiyu.android.domain.RunId("dialog-fling-run-$i"), app.daiyu.android.domain.Message.USER_ROLE,
                listOf(app.daiyu.android.domain.MessageBlock.Text("body", "历史问题 $i")),
                app.daiyu.android.domain.RunStatus.COMPLETED)
            val answer = user.copy(id = app.daiyu.android.domain.MessageId("dialog-fling-answer-$i"),
                role = app.daiyu.android.domain.Message.ASSISTANT_ROLE,
                blocks = listOf(app.daiyu.android.domain.MessageBlock.Text("body", "历史正文 $i\n".repeat(12))),
                status = app.daiyu.android.domain.RunStatus.RUNNING)
            db.send(user, answer); db.save(answer.copy(status = app.daiyu.android.domain.RunStatus.COMPLETED))
        }
        val expectedHistory = db.messages(conversation.id).map { it.second.id }
        assertEquals(50, expectedHistory.size)
        val shownField = MainActivity::class.java.getDeclaredField("shown").apply { isAccessible = true }
        val historyLoadingField = MainActivity::class.java.getDeclaredField("refreshHistoryLoading").apply { isAccessible = true }
        mainThread { activity.select(conversation) }
        awaitControl("history readers idle") { !fixture.controller.dataBusy }
        ui.waitUntil(10_000) { ui.runOnIdle {
            @Suppress("UNCHECKED_CAST")
            val loaded = shownField.get(activity) as List<Pair<Long, app.daiyu.android.domain.Message>>
            invokeHost(activity, "getDisplayWindow") == null && !historyLoadingField.getBoolean(activity) &&
                loaded.map { it.second.id } == expectedHistory
        } }
        awaitUniqueTag("chat-turn:dialog-fling-answer-69")
        dismissIme(); ui.waitForIdle()
        val originalListId = node("chat-messages").fetchSemanticsNode().id
        fun scrollValue(): Float {
            val current = node("chat-messages").fetchSemanticsNode()
            assertEquals("Opening a picker must not remount chat", originalListId, current.id)
            val range = current.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            var value = Float.NaN
            ui.runOnUiThread { value = range.value() }
            assertTrue(value.isFinite())
            return value
        }
        fun frame(): Float { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle(); return scrollValue() }
        val tail = scrollValue()
        assertTrue(tail > 0f)
        node("chat-messages").performTouchInput { click(center) }
        node("chat-messages").performScrollToIndex(8)
        val reading = frame()
        assertTrue("History fixture must enter an interior reading position: reading=$reading tail=$tail", reading > 0f && reading < tail)
        val listBounds = node("chat-messages").fetchSemanticsNode().boundsInRoot
        val composerBounds = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
        val readingHeight = minOf(listBounds.bottom, composerBounds.top) - listBounds.top
        assertTrue("Gesture must stay above composer: list=$listBounds composer=$composerBounds", readingHeight > 0f)
        val oldAutoAdvance = ui.mainClock.autoAdvance
        ui.mainClock.autoAdvance = false
        try {
            node("chat-messages").performTouchInput {
                swipeWithVelocity(Offset(center.x, readingHeight * .8f), Offset(center.x, readingHeight * .2f), endVelocity = 2000f, durationMillis = 96L)
            }
            val first = frame(); val second = frame()
            assertTrue("Fixture must still move after UP: reading=$reading first=$first second=$second tail=$tail", second > first)
            assertTrue(second < tail)
            ui.runOnUiThread { invokeHost(activity, "openChatModels") }
            var afterOpen = second
            repeat(4) {
                afterOpen = frame()
                assertTrue("Picker entry must not reset history", afterOpen >= second - .001f)
                assertTrue("Picker entry must not jump to tail", afterOpen < tail)
            }
            assertTrue("Background continues moving across picker entry", afterOpen > second)
            repeat(24) {
                val value = frame()
                assertTrue(value >= second - .001f); assertTrue(value < tail)
            }
            node("chat-model-picker").assertIsDisplayed()
            node("chat-model-search").assertIsDisplayed()
        } finally { ui.mainClock.autoAdvance = oldAutoAdvance }
        ui.waitForIdle()
        awaitNodeWindowFocus(node("chat-model-search"))
        val beforeClose = scrollValue()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitHostWindowFocus(activity)
        node("chat-model-picker").assertDoesNotExist()
        assertEquals(beforeClose, scrollValue(), .001f)
        val beforeNextScroll = scrollValue()
        node("chat-messages").performTouchInput {
            swipe(Offset(center.x, height * .8f), Offset(center.x, height * .2f), durationMillis = 240L)
        }
        ui.waitForIdle()
        assertTrue(scrollValue() > beforeNextScroll)
        assertEquals(0, fixture.opens.get())
    }

    fun testConfirmedContextRevisionStillChangesForRealConfigurationChanges() {
        awaitControl("chat local readers idle") { !fixture.controller.dataBusy }
        ui.waitUntil(5_000) { ui.runOnIdle { confirmedGptSpeed() != null } }
        val revision = ui.runOnIdle { invokeHost(activity, "getContextModelRevision") as Long }
        val before = fixture.controller.conversationModelEditing(null).second.settings
        val catalog = checkNotNull(fixture.settings.localModelCatalogSnapshot())
        val ticket = checkNotNull(catalog.selectionFor(before.model))
        val capacity = if (before.contextWindowOverride == 32768) 65536 else 32768
        assertEquals(CatalogLocalEditResult.APPLIED, fixture.settings.modelCatalog.replaceSelection(
            ticket, ticket.record.copy(settings = ticket.record.settings.copy(contextWindowOverride = capacity)),
            { false }))
        mainThread { invokeHost(activity, "refreshConfirmedModelLabel") }
        ui.waitUntil(5_000) { ui.runOnIdle {
            val confirmed = confirmedGptSpeed() as? ChatGptSpeedSelection
            confirmed?.editing?.settings?.contextWindowOverride == capacity &&
                (invokeHost(activity, "getContextModelRevision") as Long) > revision
        } }
        val confirmedRevision = ui.runOnIdle { invokeHost(activity, "getContextModelRevision") as Long }
        mainThread { invokeHost(activity, "openChatModels") }
        awaitUniqueTag("chat-model-search")
        ui.waitUntil(5_000) { ui.runOnIdle {
            chatModelPicker()?.loading == false
        } }
        mainThread { assertEquals(confirmedRevision, invokeHost(activity, "getContextModelRevision")) }
        assertEquals(capacity, fixture.controller.conversationModelEditing(null).second.settings.contextWindowOverride)
        assertEquals(0, fixture.opens.get())
    }

    fun testChatModelLoadingKeepsTheWindowSearchAndContextRevision() {
        awaitControl("chat local readers idle") { !fixture.controller.dataBusy }
        ui.waitUntil(5_000) { ui.runOnIdle { confirmedGptSpeed() != null } }
        setDraft("打开目录不改变聊天")
        dismissIme()
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.load()
        val revision = ui.runOnIdle { invokeHost(activity, "getContextModelRevision") as Long }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val holder = Thread {
            try { fixture.settings.modelCatalog.configurationChange {
                entered.countDown(); check(release.await(60, TimeUnit.SECONDS))
            } } catch (problem: Throwable) { failure.set(problem) }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            mainThread { invokeHost(activity, "openChatModels") }
            awaitUniqueTag("chat-model-search")
            awaitNodeWindowFocus(node("chat-model-search"))
            mainThread { assertTrue(checkNotNull(chatModelPicker()).loading) }
            val root = focusedWindowRoot()
            node("chat-model-search").performTextReplacement("unfinished model search")
            dismissIme()
            release.countDown(); holder.join(5_000)
            assertFalse(holder.isAlive)
            failure.get()?.let { throw it }
            ui.waitUntil(5_000) { ui.runOnIdle {
                chatModelPicker()?.loading == false
            } }
            awaitNodeWindowFocus(node("chat-model-search"))
            assertSame(root, focusedWindowRoot())
            node("chat-model-search").assertEditableText("unfinished model search")
            ui.onAllNodesWithTag("chat-model-picker").assertCountEquals(1)
            mainThread { assertEquals(revision, invokeHost(activity, "getContextModelRevision")) }
            assertEquals(settings, fixture.settings.load())
            assertEquals(before, fixture.database.exportBackup())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitHostWindowFocus(activity)
            assertDraft("打开目录不改变聊天")
            mainThread { invokeHost(activity, "openChatModels") }
            awaitUniqueTag("chat-model-search")
            ui.waitUntil(5_000) { ui.runOnIdle {
                chatModelPicker()?.loading == false
            } }
            node("chat-model-search").assertEditableText("")
            mainThread { assertEquals(revision, invokeHost(activity, "getContextModelRevision")) }
            assertEquals(0, fixture.opens.get())
        } finally { release.countDown(); holder.join(10_000) }
    }

    fun testChatModelSearchSurvivesPauseAndStopWithoutAnySelection() {
        awaitControl("chat local readers idle") { !fixture.controller.dataBusy }
        mainThread { invokeHost(activity, "openChatModels") }
        awaitUniqueTag("chat-model-search")
        node("chat-model-search").performTextReplacement("unfinished model search")
        dismissIme()
        val before = fixture.database.exportBackup()
        repeat(2) {
            pauseAndReturn()
            node("chat-model-search").assertEditableText("unfinished model search")
            homeAndReturn()
            node("chat-model-search").assertEditableText("unfinished model search")
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        awaitNodeWindowFocus(node("chat-model-search"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitHostWindowFocus(activity)
        pauseAndReturn()
        node("chat-model-picker").assertDoesNotExist()
    }
}
