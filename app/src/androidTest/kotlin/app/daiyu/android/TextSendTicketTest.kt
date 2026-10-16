package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.MessageBlock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Requires the retained TextSendState / acknowledgeTextSend API from f63a5f9. */
@Suppress("DEPRECATION")
class TextSendTicketTest : InstrumentationTestCase() {
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    private val gates = listOf(Gate(), Gate())
    private val preparations = AtomicInteger()
    private lateinit var fixture: RunControlFixture

    override fun setUp() {
        super.setUp()
        fixture = RunControlFixture(instrumentation.targetContext) {
            val gate = gates[preparations.getAndIncrement()]
            gate.entered.countDown()
            check(gate.release.await(30, TimeUnit.SECONDS))
        }
        awaitControl("controller ready") { fixture.controller.ready }
    }

    override fun tearDown() {
        try {
            gates.forEach { it.release.countDown() }
            fixture.close()
        } finally { super.tearDown() }
    }

    fun testSameTicketCancellationAfterCommittedReceiptKeepsAcceptedUntilAcknowledgement() {
        val controller = fixture.controller
        val ticket = ChatController.TextSendTicket()
        val owner = ChatController.TextSendOwner(null, 7, 11)
        val text = "  committed before cancellation\nexact trailing bytes  "
        val result = AtomicReference<ChatController.TextSendResult>()
        val completed = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val session = fixture.session(holdSeconds = 120)
        fixture.database.draft(text)
        lateinit var delivery: TextSendDeliveryGate
        controlMain {
            delivery = TextSendDeliveryGate(controller)
            delivery.hold()
        }
        try {
            controlMain {
                assertTrue(controller.sendText(null, owner.editorGeneration, owner.draftRevision, text, ticket) {
                    callbacks.incrementAndGet(); result.set(it); completed.countDown()
                })
            }
            assertTrue(gates[0].entered.await(5, TimeUnit.SECONDS))
            gates[0].release.countDown()
            // startSlice runs on the same executor AFTER result publication and callback post.
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            lateinit var accepted: ChatController.TextSendResult.Accepted
            controlMain {
                val committed = checkNotNull(controller.textSendState())
                assertSame(ticket, committed.ticket)
                assertEquals(owner, committed.owner)
                assertEquals(text, committed.text)
                accepted = committed.result as ChatController.TextSendResult.Accepted
                assertEquals(owner, accepted.owner)
                assertEquals(0, callbacks.get())
                assertEquals(1L, completed.count)
                assertNull(result.get())
                // Preparation has ended. A late cancel of this EXACT ticket is a no-op,
                // not a rejection or permission to replace the unacknowledged receipt.
                assertFalse(controller.cancelTextSend(ticket))
                val afterCancel = checkNotNull(controller.textSendState())
                assertSame(ticket, afterCancel.ticket)
                assertSame(accepted, afterCancel.result)
            }
            val rows = fixture.database.messages(accepted.conversation.id)
            assertEquals(2, rows.size)
            val user = rows.single { it.second.role == "user" }.second
            assertEquals(listOf(MessageBlock.Text("user", text)), user.blocks)
            assertEquals(user.runId, rows.single { it.second.role == "assistant" }.second.runId)
            assertEquals("", fixture.database.draft())
            assertEquals("", fixture.database.conversations().single().draft)
            assertEquals(1, fixture.opens.get()); assertEquals(1, preparations.get())

            var held = 0
            controlMain { held = delivery.release() }
            assertTrue("real callback must have been deferred", held > 0)
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals(held, delivery.deliveredCount)
            assertEquals(1, callbacks.get())
            assertSame(accepted, result.get())
            controlMain {
                assertSame(accepted, checkNotNull(controller.textSendState()).result)
                assertFalse(controller.cancelTextSend(ticket))
                controller.acknowledgeTextSend(ticket)
                assertNull(controller.textSendState())
            }
            session.release.countDown()
            awaitControl("committed run completed") { controller.active == null }
            assertEquals(1, fixture.opens.get())
        } finally {
            controlMain { delivery.close() }
        }
    }

    fun testCancelledOldTicketCannotCancelNewPreparationOrReleaseItsReservation() {
        val controller = fixture.controller
        val oldTicket = ChatController.TextSendTicket()
        val newTicket = ChatController.TextSendTicket()
        val foreignTicket = ChatController.TextSendTicket()
        val oldResult = AtomicReference<ChatController.TextSendResult>()
        val newResult = AtomicReference<ChatController.TextSendResult>()
        val oldDone = CountDownLatch(1)
        val newDone = CountDownLatch(1)
        val refusedCallbacks = AtomicInteger()
        fixture.database.draft("first cancelled draft")
        controlMain {
            assertTrue(controller.sendText(null, 1, 1, "first cancelled draft", ticket = oldTicket) {
                oldResult.set(it); oldDone.countDown()
            })
        }
        assertTrue(gates[0].entered.await(5, TimeUnit.SECONDS))
        controlMain {
            assertFalse("unrelated identity cannot cancel", controller.cancelTextSend(foreignTicket))
            assertTrue(controller.cancelTextSend(oldTicket))
            assertFalse("cancel does not release an in-flight preparation reservation",
                controller.sendText(null, 2, 2, "must not queue", ticket = newTicket) { refusedCallbacks.incrementAndGet() })
        }
        assertEquals(1, preparations.get())
        gates[0].release.countDown()
        assertTrue(oldDone.await(5, TimeUnit.SECONDS))
        assertTrue(oldResult.get() is ChatController.TextSendResult.Rejected)
        assertEquals(ChatController.TextSendOwner(null, 1, 1), oldResult.get().owner)
        assertEquals("first cancelled draft", fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, fixture.opens.get())
        controlMain {
            val state = checkNotNull(controller.textSendState())
            assertSame(oldTicket, state.ticket)
            assertEquals(oldResult.get().owner, state.owner)
            assertEquals("first cancelled draft", state.text)
            assertEquals(oldResult.get(), state.result)
            assertFalse("a retained result still needs UI acknowledgement",
                controller.sendText(null, 2, 2, "must not queue", ticket = newTicket) { refusedCallbacks.incrementAndGet() })
            controller.acknowledgeTextSend(oldTicket)
            assertNull(controller.textSendState())
        }

        val text = "  second exact draft\n  "
        fixture.database.draft(text)
        val session = fixture.session(holdSeconds = 120)
        controlMain {
            assertTrue(controller.sendText(null, 2, 2, text, ticket = newTicket) {
                newResult.set(it); newDone.countDown()
            })
        }
        assertTrue(gates[1].entered.await(5, TimeUnit.SECONDS))
        controlMain {
            assertFalse("late cancellation must not retarget the new owner", controller.cancelTextSend(oldTicket))
            assertFalse(controller.cancelTextSend(foreignTicket))
            controller.acknowledgeTextSend(oldTicket)
            val state = checkNotNull(controller.textSendState())
            assertSame("stale acknowledgement must not drop the new state", newTicket, state.ticket)
            assertEquals(ChatController.TextSendOwner(null, 2, 2), state.owner)
            assertEquals(text, state.text)
            assertNull("new preparation has no result yet", state.result)
        }
        gates[1].release.countDown()
        assertTrue(newDone.await(5, TimeUnit.SECONDS))
        assertTrue(newResult.get() is ChatController.TextSendResult.Accepted)
        assertEquals(ChatController.TextSendOwner(null, 2, 2), newResult.get().owner)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val conversation = (newResult.get() as ChatController.TextSendResult.Accepted).conversation
        val rows = fixture.database.messages(conversation.id)
        assertEquals(2, rows.size)
        assertEquals(listOf(MessageBlock.Text("user", text)), rows.single { it.second.role == "user" }.second.blocks)
        assertEquals("", fixture.database.draft())
        assertEquals(1, fixture.opens.get()); assertEquals(2, preparations.get())
        assertEquals(0, refusedCallbacks.get())
        controlMain {
            assertSame(newTicket, checkNotNull(controller.textSendState()).ticket)
            controller.acknowledgeTextSend(oldTicket)
            assertSame(newTicket, checkNotNull(controller.textSendState()).ticket)
            controller.acknowledgeTextSend(newTicket)
            assertNull(controller.textSendState())
        }
        session.release.countDown()
        awaitControl("new ticket's run completed") { controller.active == null }
        assertEquals(1, fixture.opens.get())
    }
}
