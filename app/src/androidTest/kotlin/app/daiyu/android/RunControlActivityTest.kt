package app.daiyu.android

import android.os.SystemClock
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi
import app.daiyu.android.ui.screens.chat.conversation.ComposerPhase
import app.daiyu.android.ui.screens.chat.conversation.projectMarkdown
import app.daiyu.android.ui.screens.chat.conversation.defaultMarkdownParser
import app.daiyu.android.ui.screens.chat.conversation.markdownProjectionCache
import kotlinx.coroutines.runBlocking

class RunControlActivityTest : WiredActivityTestCase() {
    override val requiresWindowDraw: Boolean
        get() = name in setOf(
            "testCompletedPresentationCanSendANewDraftWithoutAppendingToTheFinishedRun",
            "testCompletedRunKeepsRealPresentationPauseAndResumeUntilTheLastCharacter",
            "testGestureOwnerCannotRetargetNextRunBeforeRelease",
            "testPresentationGestureCannotRetargetNextRunBeforeRelease",
            "testPresentationLongPressCannotFinishTheNextReply",
        )
    private object SnapshotWriteTraceProbe {
        fun <T> around(action: () -> T): T {
            val lastSample = AtomicLong(Long.MIN_VALUE)
            val samples = java.util.ArrayDeque<String>()
            val probeClass = javaClass.name
            val observer = Snapshot.registerGlobalWriteObserver { state ->
                try {
                    val now = SystemClock.elapsedRealtime()
                    val previous = lastSample.get()
                    if ((previous == Long.MIN_VALUE || now - previous >= 750L) &&
                        lastSample.compareAndSet(previous, now)) {
                        val stack = Thread.currentThread().stackTrace.asSequence()
                            .filter { it.className.startsWith("app.daiyu.android.") && !it.className.startsWith(probeClass) }
                            .take(6).joinToString("\n") { "    at $it" }
                        val sample = "t=${now}ms ${state.javaClass.name}@" +
                            Integer.toHexString(System.identityHashCode(state)) + "\n" + stack
                        synchronized(samples) {
                            if (samples.size == 16) samples.removeFirst()
                            samples.addLast(sample)
                        }
                    }
                } catch (_: Throwable) { /* A diagnostic must not fail the state writer. */ }
            }
            var original: Throwable? = null
            try {
                return action()
            } catch (failure: Throwable) {
                original = failure
                runCatching {
                    failure.addSuppressed(AssertionError("Global snapshot write samples (750ms, last 16; not exhaustive):\n" +
                        synchronized(samples) { samples.joinToString("\n\n") }))
                }
                throw failure
            } finally {
                val failure = original
                if (failure == null) observer.dispose()
                else try { observer.dispose() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
        }
    }
    private class ParserGate : AutoCloseable {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val worker = kotlin.concurrent.thread(name = "presentation-parser-gate") {
            try {
                runBlocking {
                    projectMarkdown(listOf("parser gate ${java.util.UUID.randomUUID()}"), parser = { source ->
                        entered.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "Presentation parser gate was not released" }
                        MarkdownParser.parse(source)
                    })
                }
            } catch (problem: Throwable) { failure.set(problem) }
        }
        override fun close() {
            release.countDown()
            worker.join(5_000)
            check(!worker.isAlive) { "Presentation parser gate did not finish" }
            failure.get()?.let { throw AssertionError("Presentation parser gate failed", it) }
        }
    }
    private val parserGates = mutableListOf<ParserGate>()
    private fun holdParser(): ParserGate = ParserGate().also { gate ->
        parserGates += gate
        assertTrue("Production interactive parser lane must be held", gate.entered.await(5, TimeUnit.SECONDS))
    }
    override fun cleanupFixture() {
        var failure: Throwable? = null
        fun remember(problem: Throwable) {
            val first = failure
            if (first == null) failure = problem else first.addSuppressed(problem)
        }
        parserGates.forEach { it.release.countDown() }
        parserGates.forEach { gate -> try { gate.close() } catch (problem: Throwable) { remember(problem) } }
        parserGates.clear()
        try { super.cleanupFixture() } catch (problem: Throwable) { remember(problem) }
        failure?.let { throw it }
    }
    private fun receivedBaseline(session: RunControlFixture.Session, character: Char = '甲') {
        checkNotNull(session.sink.get())(ModelEvent.TextDelta("body",
            "received ${java.util.UUID.randomUUID()} " + character.toString().repeat(80)))
        ui.waitUntil(10_000) {
            ui.mainClock.advanceTimeBy(32)
            visibleTailCharacters(character) == 80
        }
        if (requiresWindowDraw) captureScreenNode(node("chat-messages"), advanceAnimationClock = false)
    }
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
            val second = fixture.session("乙".repeat(320), holdSeconds = 120); val conversation = fixture.database.conversations().single()
            assertNotNull(fixture.controller.send(conversation, "next user"))
            val next = checkNotNull(fixture.controller.active); assertTrue(second.entered.await(5, TimeUnit.SECONDS))
            receivedBaseline(second, '乙')
            holdParser()
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
            awaitCompletedPresentation(next, '乙')
            node("chat-control").assertContentDescriptionEquals("暂停")
            assertEquals(RunStatus.COMPLETED, fixture.database.messages(old.conversationId).last().second.status)
            assertEquals(2, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = true }
    }

    private fun visibleTailCharacters(character: Char = '甲'): Int = ui.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }
        .sumOf { text -> text.text.count { it == character } }

    private fun awaitCompletedPresentation(owner: Message, character: Char = '甲') {
        ui.waitUntil(10_000) {
            ui.mainClock.advanceTimeBy(32)
            var terminalPublished = false
            controlMain {
                val state = invokeHost(activity, "getChatState") as ChatScreenState
                terminalPublished = state.composer.phase == ComposerPhase.IDLE &&
                    state.turns.filterIsInstance<ChatTurnUi.Assistant>().any {
                        it.ownerMessageId == owner.id.value && it.status == RunStatus.COMPLETED
                    }
            }
            terminalPublished && visibleTailCharacters(character) == 80 &&
                ui.onAllNodes(hasTestTag("chat-control") and hasContentDescription("暂停") and isEnabled())
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
    }

    private fun completedTail(): Message {
        val session = fixture.session("甲".repeat(320), holdSeconds = 120)
        val owner = send()
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        ui.mainClock.autoAdvance = false
        receivedBaseline(session)
        holdParser()
        session.release.countDown()
        awaitControl("backend is completed before the presentation") { fixture.controller.active == null }
        awaitCompletedPresentation(owner)
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
            val nextSession = fixture.session("乙".repeat(320), holdSeconds = 120)
            val conversation = fixture.database.conversations().single()
            assertNotNull(fixture.controller.send(conversation, "next user"))
            val next = checkNotNull(fixture.controller.active)
            assertTrue(nextSession.entered.await(5, TimeUnit.SECONDS))
            parserGates.last().release.countDown()
            receivedBaseline(nextSession, '乙')
            holdParser()
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
            awaitCompletedPresentation(next, '乙')
            node("chat-control").assertContentDescriptionEquals("暂停")
            assertEquals(2, fixture.opens.get())
            assertEquals(RunStatus.COMPLETED,
                fixture.database.messages(old.conversationId).first { it.second.id == old.id }.second.status)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testCompletedRunKeepsRealPresentationPauseAndResumeUntilTheLastCharacter() {
        try {
            val owner = completedTail()
            captureScreenNode(node("chat-messages"), advanceAnimationClock = false)
            val beforePause = visibleTailCharacters()
            assertEquals("The drawn baseline must precede the late parsed suffix", 80, beforePause)
            val pending = fixture.database.messages(owner.conversationId).single { it.second.id == owner.id }
                .second.blocks.filterIsInstance<MessageBlock.Text>().single().text
            assertEquals(400, pending.count { it == '甲' })
            assertNull(markdownProjectionCache.getBlocks(pending, defaultMarkdownParser))
            node("chat-control").performClick()
            ui.mainClock.advanceTimeBy(32)
            node("chat-control").assertContentDescriptionEquals("继续")
            val paused = visibleTailCharacters()
            assertEquals("Pause must retain the received drawn baseline", beforePause, paused)
            parserGates.last().release.countDown()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                assertEquals("Late parsing must not cross the paused paint frontier", paused, visibleTailCharacters())
                markdownProjectionCache.getBlocks(pending, defaultMarkdownParser) != null
            }
            controlMain { }
            ui.waitForIdle()
            repeat(12) {
                ui.mainClock.advanceTimeByFrame()
                assertEquals("Late parsing must not cross the paused paint frontier", paused, visibleTailCharacters())
            }
            ui.mainClock.advanceTimeBy(1200)
            captureScreenNode(node("chat-messages"), advanceAnimationClock = false)
            assertEquals("Pause must freeze the visible prefix", paused, visibleTailCharacters())
            assertNull(fixture.controller.active)
            assertEquals(1, fixture.opens.get())
            node("chat-control").performClick()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeBy(16)
                visibleTailCharacters() == 400
            }
            captureScreenNode(node("chat-messages"), advanceAnimationClock = false)
            repeat(12) {
                ui.mainClock.advanceTimeByFrame()
                assertEquals("Without new input the received frontier must not grow", 400, visibleTailCharacters())
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
            SnapshotWriteTraceProbe.around {
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
            }
        } finally { ui.mainClock.autoAdvance = true }
    }

}
