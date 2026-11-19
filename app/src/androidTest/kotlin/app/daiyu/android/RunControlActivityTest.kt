package app.daiyu.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import java.util.concurrent.TimeUnit

class RunControlActivityTest : WiredActivityTestCase() {
    private fun send(): Message {
        setDraft("initial user"); node("chat-send").performClick()
        awaitTextSendResult(activity)
        assertDraft("")
        return checkNotNull(fixture.controller.active)
    }
    private fun awaitAppendEnabled() {
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("chat-send") and hasContentDescription("追加消息") and isEnabled())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
    }
    fun testShortClickPausesRecreationRetainsDraftAndContinueCompletesSameOwner() {
        val first = fixture.session("cancelled"); val second = fixture.session("answer")
        val owner = send(); assertTrue(first.entered.await(5, TimeUnit.SECONDS))
        node("chat-control").performClick()
        setDraft("unsent draft")
        awaitControl("worker paused") { fixture.controller.runState(owner)?.phase == ExecutionPhase.PAUSED }
        ui.waitForIdle(); assertDraft("unsent draft")
        assertEquals(RunStatus.RUNNING, fixture.controller.active!!.status)
        node("chat-control").assertDoesNotExist()
        awaitAppendEnabled()
        node("chat-send").assertIsEnabled().assertContentDescriptionEquals("追加消息")
        assertEquals(1, fixture.opens.get())
        activity = recreateActivity(activity)
        assertDraft("unsent draft")
        node("chat-control").assertDoesNotExist()
        awaitAppendEnabled()
        node("chat-send").assertIsEnabled().assertContentDescriptionEquals("追加消息")
        assertEquals(owner.id, fixture.controller.active!!.id); assertEquals(1, fixture.opens.get())
        setDraft("")
        node("chat-control").assertIsEnabled().assertContentDescriptionEquals("继续").performClick()
        assertTrue(second.entered.await(5, TimeUnit.SECONDS))
        setDraft("unsent draft")
        second.release.countDown()
        awaitControl("same run completes") { fixture.controller.active == null }
        ui.waitForIdle(); assertDraft("unsent draft"); node("chat-control").assertDoesNotExist()
        val saved = fixture.database.messages(owner.conversationId).last().second
        assertEquals(owner.id, saved.id); assertEquals(owner.runId, saved.runId); assertEquals(RunStatus.COMPLETED, saved.status)
        assertEquals(2, fixture.opens.get()); assertEquals(2, fixture.database.messages(owner.conversationId).size)
    }
    private fun streamingAppendAcrossRecreation(editAfterRecreate: Boolean) {
        val first = fixture.session("cancelled", releaseOnCancel = false)
        val second = fixture.session("after append")
        val owner = send(); assertTrue(first.entered.await(5, TimeUnit.SECONDS))
        setDraft("additional instruction")
        awaitAppendEnabled(); node("chat-send").performClick()
        assertTrue(first.cancelled.await(5, TimeUnit.SECONDS))
        val pendingId = checkNotNull(fixture.controller.supplementDraft.pending).messageId
        assertDraft("additional instruction")
        activity = recreateActivity(activity)
        assertDraft("additional instruction")
        assertEquals(pendingId, fixture.controller.supplementDraft.pending?.messageId)
        if (editAfterRecreate) setDraft("newer draft")
        first.release.countDown()
        assertTrue(second.entered.await(5, TimeUnit.SECONDS))
        awaitControl("retained supplement receipt consumed") { fixture.controller.supplementDraft.pending == null }
        ui.waitForIdle(); assertDraft(if (editAfterRecreate) "newer draft" else "")
        assertEquals(1, fixture.database.messages(owner.conversationId).count { it.second.id == pendingId })
        assertEquals(owner.runId, fixture.controller.active!!.runId)
        second.release.countDown()
        awaitControl("appended owner completes") { fixture.controller.active == null }
        assertEquals(2, fixture.opens.get())
    }
    fun testStreamingAppendReceiptSurvivesRecreationAndClearsOriginalDraftOnce() =
        streamingAppendAcrossRecreation(editAfterRecreate = false)
    fun testStreamingAppendReceiptAfterRecreationPreservesNewEdits() =
        streamingAppendAcrossRecreation(editAfterRecreate = true)

    fun testOtherConversationCannotControlOwnerAndLongClickStopsWithoutPause() {
        val session = fixture.session(holdSeconds = 120); val owner = send(); assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val ownedConversation = fixture.database.conversations().single(); val other = fixture.database.create()
        val staleTap = retainedClick(activity, "chat-control")
        val staleLong = retainedControls(activity).onPrimaryLongPress
        mainThread { activity.select(other) }; setDraft("other draft"); node("chat-control").assertDoesNotExist()
        mainThread { staleTap(); staleLong() }
        assertFalse(fixture.controller.runState(owner)!!.pauseRequested); assertEquals(1L, session.cancelled.count)
        mainThread { activity.select(ownedConversation) }; setDraft("")
        mainThread {
            staleTap(); staleLong()
            val active = checkNotNull(fixture.controller.active)
            assertEquals(owner.id, active.id); assertEquals(owner.runId, active.runId)
            assertEquals(owner.conversationId, active.conversationId)
            assertEquals(RunStatus.RUNNING, active.status)
            assertFalse(checkNotNull(fixture.controller.runState(owner)).pauseRequested)
            assertEquals(1L, session.cancelled.count)
        }
        val root = ui.onNode(isRoot() and hasAnyDescendant(hasTestTag("chat-draft")))
        node("chat-control").assertIsDisplayed().assertIsEnabled().performTouchInput { down(center) }
        try {
            ui.mainClock.advanceTimeBy(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 200L)
            ui.waitForIdle()
            assertTrue("long press cancels owner session while still held", session.cancelled.await(5, TimeUnit.SECONDS))
        } finally {
            root.performTouchInput { up() }
        }
        awaitControl("long gesture stops without short pause") { fixture.controller.active == null }
        val saved = fixture.database.messages(owner.conversationId).last().second
        assertEquals(RunStatus.STOPPED, saved.status); assertEquals(1, fixture.opens.get())
        assertEquals(0L, session.cancelled.count)
        assertDraft(""); node("chat-control").assertDoesNotExist()
        assertEquals("other draft", fixture.database.conversations().single { it.id == other.id }.draft)
    }
    fun testGestureOwnerCannotRetargetNextRunBeforeRelease() {
        val first = fixture.session(holdSeconds = 120); val old = send(); assertTrue(first.entered.await(5, TimeUnit.SECONDS))
        val target = node("chat-control")
        val oldLong = retainedControls(activity).onPrimaryLongPress
        ui.mainClock.autoAdvance = false
        try {
            target.performTouchInput { down(center) }
            first.release.countDown(); awaitControl("old owner completed") { fixture.controller.active == null }
            val second = fixture.session("乙".repeat(400), holdSeconds = 120); val conversation = fixture.database.conversations().single()
            assertNotNull(fixture.controller.send(conversation, "next user"))
            val next = checkNotNull(fixture.controller.active); assertTrue(second.entered.await(5, TimeUnit.SECONDS))
            ui.mainClock.advanceTimeBy(32)
            controlMain { oldLong() }
            ui.mainClock.advanceTimeBy(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 200L)
            ui.waitForIdle()
            controlMain {
                assertFalse(checkNotNull(fixture.controller.runState(next)) { "next run disappeared before release" }.pauseRequested)
                assertEquals(1L, second.cancelled.count)
            }
            node("chat-control").performTouchInput { up() }
            ui.mainClock.advanceTimeBy(32)
            controlMain {
                assertFalse(checkNotNull(fixture.controller.runState(next)) { "next run disappeared after old gesture release" }.pauseRequested)
                assertEquals(1L, second.cancelled.count)
            }
            second.release.countDown(); awaitControl("next run unaffected") { fixture.controller.active == null }
            ui.waitUntil(10_000) {
                ui.mainClock.advanceTimeBy(32)
                visibleTailCharacters('乙') in 1 until 400
            }
            node("chat-control").assertContentDescriptionEquals("暂停")
            assertEquals(RunStatus.COMPLETED, fixture.database.messages(old.conversationId).last().second.status)
            assertEquals(2, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = true }
    }

    private fun visibleTailCharacters(character: Char = '甲'): Int = ui.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }
        .sumOf { text -> text.text.count { it == character } }

    private fun completedTail(): Message {
        val session = fixture.session("终态输出" + "甲".repeat(400))
        val owner = send()
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        ui.mainClock.autoAdvance = false
        session.release.countDown()
        awaitControl("backend is completed before the presentation") { fixture.controller.active == null }
        ui.waitUntil(10_000) {
            ui.mainClock.advanceTimeBy(32)
            visibleTailCharacters() in 1 until 400
        }
        node("chat-control").assertIsEnabled().assertContentDescriptionEquals("暂停")
        assertEquals(RunStatus.COMPLETED, fixture.database.messages(owner.conversationId).last().second.status)
        return owner
    }

    fun testPresentationGestureCannotRetargetNextRunBeforeRelease() = presentationGestureCannotRetargetNextRun(false)

    fun testPresentationLongPressCannotFinishTheNextReply() = presentationGestureCannotRetargetNextRun(true)

    private fun presentationGestureCannotRetargetNextRun(longPress: Boolean) {
        try {
            val old = completedTail()
            val root = ui.onNode(isRoot() and hasAnyDescendant(hasTestTag("chat-draft")))
            node("chat-control").performTouchInput { down(center) }
            val nextSession = fixture.session("乙".repeat(400), holdSeconds = 120)
            val conversation = fixture.database.conversations().single()
            assertNotNull(fixture.controller.send(conversation, "next user"))
            val next = checkNotNull(fixture.controller.active)
            assertTrue(nextSession.entered.await(5, TimeUnit.SECONDS))
            ui.mainClock.advanceTimeBy(32)
            if (longPress) {
                ui.mainClock.advanceTimeBy(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 200L)
                ui.waitForIdle()
            }
            controlMain {
                assertFalse(checkNotNull(fixture.controller.runState(next)).pauseRequested)
            }
            root.performTouchInput { up() }
            ui.mainClock.advanceTimeBy(32)
            controlMain {
                assertFalse(checkNotNull(fixture.controller.runState(next)).pauseRequested)
                assertEquals(1L, nextSession.cancelled.count)
            }
            nextSession.release.countDown()
            awaitControl("next run survives the presentation gesture") { fixture.controller.active == null }
            ui.waitUntil(10_000) {
                ui.mainClock.advanceTimeBy(32)
                visibleTailCharacters('乙') in 1 until 400
            }
            node("chat-control").assertContentDescriptionEquals("暂停")
            assertEquals(2, fixture.opens.get())
            assertEquals(RunStatus.COMPLETED,
                fixture.database.messages(old.conversationId).first { it.second.id == old.id }.second.status)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testCompletedRunKeepsRealPresentationPauseAndResumeUntilTheLastCharacter() {
        try {
            val owner = completedTail()
            node("chat-control").performClick()
            ui.mainClock.advanceTimeBy(32)
            node("chat-control").assertContentDescriptionEquals("继续")
            val paused = visibleTailCharacters()
            assertTrue(paused in 1 until 400)
            ui.mainClock.advanceTimeBy(1200)
            assertEquals("Pause must freeze the visible prefix", paused, visibleTailCharacters())
            assertNull(fixture.controller.active)
            assertEquals(1, fixture.opens.get())
            node("chat-control").performClick()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeBy(16)
                visibleTailCharacters() > paused
            }
            assertTrue("Resume must not jump to the end", visibleTailCharacters() < 400)
            node("chat-control").assertContentDescriptionEquals("暂停")
            ui.waitUntil(15_000) {
                ui.mainClock.advanceTimeBy(64)
                visibleTailCharacters() == 400
            }
            ui.mainClock.advanceTimeBy(64)
            node("chat-control").assertDoesNotExist()
            node("chat-send").assertContentDescriptionEquals("发送").assertIsNotEnabled()
            assertEquals(1, fixture.opens.get())
            assertEquals(RunStatus.COMPLETED, fixture.database.messages(owner.conversationId).last().second.status)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testCompletedPresentationCanSendANewDraftWithoutAppendingToTheFinishedRun() {
        try {
            val old = completedTail()
            node("chat-control").performClick()
            ui.mainClock.advanceTimeBy(32)
            val next = fixture.session("next reply", holdSeconds = 120)
            setDraft("next user")
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodes(hasTestTag("chat-send") and hasContentDescription("发送") and isEnabled())
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
            }
            assertDraft("next user")
            node("chat-send").assertContentDescriptionEquals("发送").assertIsEnabled().performClick()
            ui.mainClock.autoAdvance = true
            awaitTextSendResult(activity)
            assertTrue(next.entered.await(5, TimeUnit.SECONDS))
            assertEquals(2, fixture.opens.get())
            val active = checkNotNull(fixture.controller.active)
            assertFalse(old.runId == active.runId)
            assertEquals(RunStatus.COMPLETED,
                fixture.database.messages(old.conversationId).first { it.second.id == old.id }.second.status)
            next.release.countDown()
            awaitControl("new run completes independently") { fixture.controller.active == null }
        } finally { ui.mainClock.autoAdvance = true }
    }

}
