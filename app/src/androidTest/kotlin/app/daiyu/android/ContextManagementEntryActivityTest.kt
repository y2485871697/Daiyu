package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.context.CompressionSettings
import app.daiyu.android.context.ContextManagementControllerAdapter
import app.daiyu.android.context.ContextManagementState
import app.daiyu.android.context.SummaryModelBinding
import app.daiyu.android.context.SummaryProfileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import app.daiyu.android.domain.*

/** Real Main chat action/route and real read-only production worker, with isolated fixture DB.
 * No real provider, credentials, paid request, or manual-compression transport is exercised here. */
@Suppress("DEPRECATION")
class ContextManagementEntryActivityTest : WiredActivityTestCase() {
    private fun seedAndSelect(): Conversation {
        val conversation = fixture.database.create("default")
        val user = Message(MessageId("context-user"), conversation.id, RunId("context-history"), "user",
            listOf(MessageBlock.Text("body", "context entry literal evidence")), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("context-assistant"), role = "assistant", status = RunStatus.RUNNING)
        fixture.database.send(user, assistant)
        fixture.database.save(assistant.copy(status = RunStatus.COMPLETED))
        fixture.database.draft(conversation.id, "retained unsent draft")
        mainThread { activity.select(conversation) }
        return conversation
    }
    private fun adapter(): ContextManagementControllerAdapter {
        val getter = MainActivity::class.java.declaredMethods.single { it.name == "getContextManagementVisit" }
        val visit = checkNotNull(getter.apply { isAccessible = true }.invoke(activity))
        return visit.javaClass.getDeclaredMethod("getAdapter").apply { isAccessible = true }
            .invoke(visit) as ContextManagementControllerAdapter
    }
    private fun page(value: String) {
        MainActivity::class.java.declaredMethods.single { it.name == "setRetainedPage" }
            .apply { isAccessible = true }.invoke(activity, value)
    }
    private fun editorGeneration(): Long = MainActivity::class.java.getDeclaredField("editorGeneration")
        .apply { isAccessible = true }.getLong(activity)
    private fun assertNoVisit() {
        val getter = MainActivity::class.java.declaredMethods.single { it.name == "getContextManagementVisit" }
        assertNull(getter.apply { isAccessible = true }.invoke(activity))
    }
    private fun assertChatRoute() {
        mainThread {
            val getter = MainActivity::class.java.declaredMethods.single { it.name == "getRetainedPage" }
            assertEquals("chat", getter.apply { isAccessible = true }.invoke(activity))
            assertNoVisit()
        }
        node("context-management").assertDoesNotExist()
        ui.onNodeWithText("上下文页面已失效；请返回原会话重新打开").assertDoesNotExist()
    }
    private fun stopped(block: () -> Unit) {
        val original = activity
        val stopped = java.util.concurrent.CountDownLatch(1)
        var assertion: Throwable? = null
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(target: android.app.Activity) {
                if (target !== original) return
                try { block() } catch (failure: Throwable) { assertion = failure }
                finally { stopped.countDown() }
            }
            override fun onActivityCreated(target: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(target: android.app.Activity) = Unit
            override fun onActivityResumed(target: android.app.Activity) = Unit
            override fun onActivityPaused(target: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(target: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(target: android.app.Activity) = Unit
        }
        original.application.registerActivityLifecycleCallbacks(callbacks)
        try {
            mainThread { assertTrue(original.moveTaskToBack(true)) }
            assertTrue("original Activity stopped", stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            original.application.unregisterActivityLifecycleCallbacks(callbacks)
            instrumentation.targetContext.startActivity(
                android.content.Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        ui.waitUntil(5_000) {
            var resumed = false
            instrumentation.runOnMainSync {
                resumed = !original.isDestroyed && original.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
            }
            resumed
        }
        awaitHostWindowFocus(original)
        ui.waitForIdle()
        assertion?.let { throw it }
    }
    @Suppress("UNCHECKED_CAST")
    private fun offerConfirmationMetadata(adapter: ContextManagementControllerAdapter) {
        // Test-only presentation probe: retain the REAL production read/owner/adapter, but supply
        // non-secret, unsaved target metadata for the compact dialog and lifecycle revocation probe.
        // No profile/settings/credentials are read or saved here. This is NOT a summary I/O test.
        val mutable = adapter.javaClass.getDeclaredField("mutable").apply { isAccessible = true }
            .get(adapter) as MutableStateFlow<ContextManagementState>
        val binding = SummaryModelBinding("entry-stop-fixture", "entry-stop-model")
        val state = mutable.value
        val configuration = CompressionSettings(automatic = false, contextWindowTokens = 10000,
            reserveTokens = 1000, keepTurns = 1, summaryModel = binding)
        mutable.value = state.copy(snapshot = checkNotNull(state.snapshot).copy(
            configuration = configuration, savedConfiguration = configuration,
            summaryTarget = SummaryProfileDescriptor(binding, "entry-stop-revision",
                "https://entry-stop.invalid/v1/responses", "entry-stop-api-model")))
    }
    private fun awaitLocalReaders() {
        awaitControl("chat local readers idle") { !fixture.controller.dataBusy }
    }
    private fun retainedCompressionClick(): () -> Unit {
        val captured = retainedControls(activity).onMenuAction
        return { captured(ChatMenuAction.COMPRESS_CONTEXT) }
    }
    private fun openAndAwait(): ContextManagementControllerAdapter {
        awaitLocalReaders()
        dismissIme()
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        ui.onNodeWithContentDescription("更多").assertIsDisplayed().performClick()
        awaitUniqueTag("chat-overflow-menu")
        awaitUniqueTag("chat-menu:COMPRESS_CONTEXT")
        node("chat-menu:COMPRESS_CONTEXT").scrollToVisible().assertIsDisplayed().assertIsEnabled().performClick()
        awaitUniqueTag("context-management")
        lateinit var result: ContextManagementControllerAdapter
        mainThread { result = adapter() }
        awaitControl("context route production read") {
            result.state.value.operation == null && result.state.value.snapshot != null
        }
        return result
    }
    fun testRealClickReadsOriginalConversationWithoutSendingOrChangingDraftAndBackFencesOldClick() {
        val conversation = seedAndSelect()
        val before = fixture.database.exportBackup()
        lateinit var oldClick: () -> Unit
        mainThread { oldClick = retainedCompressionClick() }
        val old = openAndAwait()
        assertEquals(conversation.id, old.state.value.owner.conversationId)
        assertEquals(2, old.state.value.snapshot!!.originals.size)
        assertEquals(before, fixture.database.exportBackup())
        backToChat(activity)
        node("context-management").assertDoesNotExist()
        assertDraft("retained unsent draft")
        mainThread { oldClick() }
        node("context-management").assertDoesNotExist()
        assertNull(old.state.value.snapshot)
        val next = openAndAwait()
        assertNotSame(old, next)
        assertTrue(next.state.value.owner.generation > old.state.value.owner.generation)
        assertEquals(conversation.id, next.state.value.owner.conversationId)
        assertEquals(0, fixture.opens.get())
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testCompressionChoicesStayLocalUntilConfirmationAndPickerBackKeepsConversation() {
        seedAndSelect()
        val before = fixture.database.exportBackup()
        val configuration = fixture.settings.loadForEditing().settings
        val preferences = instrumentation.targetContext.getSharedPreferences("settings-model-selection", 0)
        val savedSelection = preferences.all.toMap()
        val adapter = openAndAwait()
        mainThread { offerConfirmationMetadata(adapter) }
        ui.mainClock.advanceTimeByFrame()
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("compression-dialog-custom") and isOn()).fetchSemanticsNodes().size == 1
        }
        node("compression-dialog-custom").assertIsOn()
        node("context-refresh").assertDoesNotExist()
        node("context-advanced").assertDoesNotExist()
        val protocol = app.daiyu.android.model.ProviderProtocol.RESPONSES
        node("compression-dialog-api:${protocol.wireName}").performClick()
        node("compression-dialog-custom").performClick().assertIsOff()
        assertFalse(adapter.state.value.confirmingCompression)
        assertNull(adapter.state.value.operation)
        assertTrue(adapter.state.value.snapshot != null)
        node("compression-dialog-custom").performClick().assertIsOn()
        node("compression-dialog-model").performClick()
        awaitUniqueTag("settings-model-picker")
        val pickerView = (node("settings-model-picker").fetchSemanticsNode().root
            as androidx.compose.ui.platform.ViewRootForTest).view
        ui.waitUntil(5_000) {
            var focused = false
            instrumentation.runOnMainSync {
                focused = pickerView.isAttachedToWindow && pickerView.hasWindowFocus()
            }
            focused
        }
        dismissIme(pickerView)
        awaitNodeWindowFocus(node("settings-model-picker"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitUniqueTag("context-management")
        node("compression-dialog-api:${protocol.wireName}").assertIsSelected()
        node("context-close").performClick()
        node("context-management").assertDoesNotExist()
        assertDraft("retained unsent draft")
        assertNull(adapter.state.value.snapshot)
        assertEquals(savedSelection, preferences.all)
        assertEquals(configuration, fixture.settings.loadForEditing().settings)
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testUnavailableSelectionOffersOnlyCompactNoticeAndExplicitRefresh() {
        seedAndSelect()
        val before = fixture.database.exportBackup()
        val adapter = openAndAwait()
        mainThread {
            // Force a deterministic unavailable configuration without using any installed profile.
            val mutable = adapter.javaClass.getDeclaredField("mutable").apply { isAccessible = true }
                .get(adapter) as MutableStateFlow<ContextManagementState>
            val snapshot = requireNotNull(mutable.value.snapshot)
            mutable.value = mutable.value.copy(snapshot = snapshot.copy(configuration = null, savedConfiguration = null))
        }
        node("context-view").assertDoesNotExist()
        node("context-refresh").assertDoesNotExist()
        node("context-advanced").assertDoesNotExist()
        node("context-compress").performClick()
        awaitControl("unavailable selection requires refresh") {
            adapter.state.value.operation == null && adapter.state.value.requiresRefresh
        }
        awaitUniqueTag("compression-dialog-refresh")
        node("context-compress").assertIsNotEnabled()
        node("context-compression-confirmation").assertDoesNotExist()
        node("context-notice").assertExists()
        node("compression-dialog-refresh").scrollToVisible().performClick()
        awaitControl("explicit refresh returns to compact choices") {
            adapter.state.value.operation == null && !adapter.state.value.requiresRefresh
        }
        node("compression-dialog-custom").assertExists()
        node("context-compress").assertIsEnabled()
        assertFalse(adapter.state.value.confirmingCompression)
        assertEquals(before, fixture.database.exportBackup())
        assertDraft("retained unsent draft")
        assertEquals(0, fixture.opens.get())
    }

    fun testPauseWithoutStopRevokesOldCompressionConfirmationAndClosesRoute() {
        seedAndSelect()
        val before = fixture.database.exportBackup()
        val old = openAndAwait()
        mainThread {
            offerConfirmationMetadata(old)
            old.requestCompression()
            assertTrue(old.state.value.confirmingCompression)
        }
        realPauseResume(activity, atPause = {
            assertNoVisit()
            old.confirmCompression()
            assertNull(old.state.value.snapshot)
            assertFalse(old.state.value.confirmingCompression)
        })
        awaitUniqueTag("chat-draft")
        assertChatRoute()
        assertDraft("retained unsent draft")
        val next = openAndAwait()
        mainThread {
            old.refresh(); old.confirmCompression()
            assertSame(next, adapter())
            assertNotNull(next.state.value.snapshot)
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testSelectionAndPageChangesRevokeBeforeOldCommandsAndSameIdGetsNewVisit() {
        val conversation = seedAndSelect()
        val old = openAndAwait()
        mainThread {
            activity.select(null)
            old.refresh(); old.requestCompression(); old.confirmCompression()
        }
        assertNull(old.state.value.snapshot)
        assertChatRoute()
        mainThread { activity.select(conversation) }
        val next = openAndAwait()
        assertTrue(next.state.value.owner.generation > old.state.value.owner.generation)
        // Re-enter ordinary settings through the real page setter, not Compose disposal alone.
        mainThread {
            val setter = MainActivity::class.java.declaredMethods.single { it.name == "setRetainedPage" }
            setter.apply { isAccessible = true }.invoke(activity, "settings")
            next.refresh(); next.confirmCompression()
        }
        assertNull(next.state.value.snapshot)
        node("context-management").assertDoesNotExist()
        awaitUniqueTag("page:设置")
        assertEquals("retained unsent draft", fixture.database.conversations().single().draft)
        assertEquals(0, fixture.opens.get())
    }
    fun testActivityRecreationDoesNotRestoreContextVisitOrOldClickAuthority() {
        val conversation = seedAndSelect()
        lateinit var oldClick: () -> Unit
        mainThread { oldClick = retainedCompressionClick() }
        val old = openAndAwait()
        activity = recreateActivity(activity)
        node("context-management").assertDoesNotExist()
        mainThread { oldClick(); old.refresh(); old.confirmCompression() }
        assertNull(old.state.value.snapshot)
        node("context-management").assertDoesNotExist()
        val next = openAndAwait()
        assertNotSame(old, next)
        assertEquals(conversation.id, next.state.value.owner.conversationId)
        assertEquals(0, fixture.opens.get())
    }
    fun testSidebarRevokesEntryWithoutVisitAndClosingDoesNotReviveOldClicks() {
        seedAndSelect()
        awaitLocalReaders()
        val before = fixture.database.exportBackup()
        lateinit var oldClick: () -> Unit
        lateinit var behindSidebarClick: () -> Unit
        var generation = -1L
        mainThread {
            generation = editorGeneration()
            oldClick = retainedCompressionClick()
            retainedControls(activity).onOpenSidebar()
            oldClick()
            behindSidebarClick = retainedCompressionClick()
            behindSidebarClick()
            assertNoVisit()
        }
        node("context-management").assertDoesNotExist()
        backToChat(activity) // Dismiss the actual sidebar, without selecting the conversation again.
        assertDraft("retained unsent draft")
        awaitLocalReaders()
        mainThread {
            oldClick(); behindSidebarClick()
            assertNoVisit()
            assertEquals(generation, editorGeneration())
        }
        node("context-management").assertDoesNotExist()
        lateinit var sidebarClick: () -> Unit
        mainThread { sidebarClick = retainedControls(activity).onOpenSidebar }
        val visit = openAndAwait() // Only the newly rendered chat entry can open a visit.
        mainThread {
            // A retained sidebar action also synchronously revokes an already-open context visit.
            sidebarClick()
            assertNoVisit()
            assertNull(visit.state.value.snapshot)
            visit.refresh(); visit.confirmCompression()
            assertNull(visit.state.value.operation)
        }
        assertChatRoute()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }
    fun testSettingsRoundTripWithoutReselectionCannotRestoreOldEntry() {
        seedAndSelect()
        awaitLocalReaders()
        val before = fixture.database.exportBackup()
        lateinit var oldClick: () -> Unit
        lateinit var settingsClick: () -> Unit
        var generation = -1L
        mainThread {
            generation = editorGeneration()
            oldClick = retainedCompressionClick()
            // Isolate the real page setter from sidebar and selection-generation fences.
            page("settings")
            oldClick()
            settingsClick = retainedCompressionClick()
            settingsClick()
            assertNoVisit()
        }
        awaitUniqueTag("page:设置")
        backToChat(activity)
        awaitLocalReaders()
        mainThread {
            oldClick(); settingsClick()
            assertNoVisit()
            assertEquals(generation, editorGeneration())
        }
        node("context-management").assertDoesNotExist()
        assertDraft("retained unsent draft")
        openAndAwait()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }
    fun testStopRevokesUnopenedEntryAndOpenAdapterConsentAndResumeNeedsFreshClick() {
        val conversation = seedAndSelect()
        awaitLocalReaders()
        val before = fixture.database.exportBackup()
        lateinit var oldClick: () -> Unit
        lateinit var backgroundClick: () -> Unit
        var generation = -1L
        mainThread {
            generation = editorGeneration()
            oldClick = retainedCompressionClick()
        }
        stopped {
            oldClick()
            backgroundClick = retainedCompressionClick()
            backgroundClick()
            assertNoVisit() // onStop must revoke even when no visit existed.
        }
        awaitLocalReaders()
        mainThread {
            oldClick(); backgroundClick()
            assertNoVisit()
            assertEquals(generation, editorGeneration())
        }
        node("context-management").assertDoesNotExist()
        val old = openAndAwait()
        mainThread { offerConfirmationMetadata(old); old.requestCompression() }
        awaitUniqueTag("context-confirm-compress")
        mainThread { assertTrue(old.state.value.confirmingCompression) }
        // This is the same adapter method retained by the actual dialog's confirmation button.
        val oldConfirm: () -> Unit = old::confirmCompression
        stopped {
            assertNoVisit()
            assertNull(old.state.value.snapshot)
            assertFalse(old.state.value.confirmingCompression)
            old.refresh(); old.requestCompression(); oldConfirm(); oldClick()
            assertNull(old.state.value.operation)
            assertNoVisit()
        }
        mainThread {
            old.refresh(); old.requestCompression(); oldConfirm(); oldClick(); backgroundClick()
            assertNull(old.state.value.snapshot)
            assertFalse(old.state.value.confirmingCompression)
            assertNoVisit()
        }
        assertChatRoute() // Resume closes the revoked modal without a manual Back workaround.
        assertDraft("retained unsent draft")
        mainThread { oldClick(); backgroundClick(); oldConfirm(); assertNoVisit() }
        val next = openAndAwait()
        assertNotSame(old, next)
        assertEquals(conversation.id, next.state.value.owner.conversationId)
        mainThread {
            old.refresh(); oldConfirm(); oldClick(); backgroundClick()
            assertSame(next, adapter())
            assertNotNull(next.state.value.snapshot)
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }
    fun testSameConversationReentryClosesRevokedRouteWithoutChangingHistoryOrDraft() {
        val conversation = seedAndSelect()
        val before = fixture.database.exportBackup()
        val old = openAndAwait()
        mainThread {
            activity.select(conversation)
            old.refresh(); old.requestCompression(); old.confirmCompression()
            assertNull(old.state.value.snapshot)
            assertNull(old.state.value.operation)
        }
        assertChatRoute()
        assertDraft("retained unsent draft")
        val next = openAndAwait()
        assertNotSame(old, next)
        assertEquals(conversation.id, next.state.value.owner.conversationId)
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }
    fun testStaleChatClickCannotOpenContextFromAnotherPageOrNewEditor() {
        val conversation = seedAndSelect()
        lateinit var oldClick: () -> Unit
        mainThread { oldClick = retainedCompressionClick() }
        openSettings()
        mainThread { oldClick() }
        node("context-management").assertDoesNotExist()
        backToChat(activity)
        mainThread { activity.select(conversation); oldClick() }
        node("context-management").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
}
