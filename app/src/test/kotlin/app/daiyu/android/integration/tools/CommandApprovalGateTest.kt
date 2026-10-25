package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class CommandApprovalGateTest {
    private fun owner() = app.daiyu.android.domain.runtime.RunExecutionOwner.issue(Message(
        MessageId("m"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
    private class Ui : CommandApprovalUi {
        val shown = CountDownLatch(1)
        lateinit var reply: (Boolean) -> Unit
        override fun show(request: CommandApprovalRequest, answer: (Boolean) -> Unit) { reply = answer; shown.countDown() }
        override fun dismiss(request: CommandApprovalRequest) {}
    }
    @Test fun requiresAttachedHostAndOriginalReplyIsOneShot() {
        val owner = owner()
        val gate = CommandApprovalGate({ it() }, { it === owner })
        val request = CommandApprovalRequest(owner, "call", WriteEffect("shell", "exec", "target", "ls"), "ls")
        assertFalse(gate.await(request) { false })
        val ui = Ui(); gate.attach(ui)
        val waiting = FutureTask { gate.await(request) { false } }
        Thread(waiting).start(); assertTrue(ui.shown.await(2, TimeUnit.SECONDS))
        ui.reply(true); ui.reply(false)
        assertTrue(waiting.get(2, TimeUnit.SECONDS))
        gate.detach(ui)
        assertFalse(gate.await(request) { false })
    }
    @Test fun detachWakesWaiterAndLateTrueCannotApproveTheNextRequest() {
        val owner = owner(); val gate = CommandApprovalGate({ it() }, { it === owner })
        val request = CommandApprovalRequest(owner, "call", WriteEffect("shell", "exec", "target", "ls"), "ls")
        val old = Ui(); gate.attach(old)
        val first = FutureTask { gate.await(request) { false } }
        Thread(first).start(); assertTrue(old.shown.await(2, TimeUnit.SECONDS))
        gate.detach(old); old.reply(true)
        assertFalse(first.get(2, TimeUnit.SECONDS))
        val current = Ui(); gate.attach(current)
        val second = FutureTask { gate.await(request) { false } }
        Thread(second).start(); assertTrue(current.shown.await(2, TimeUnit.SECONDS))
        old.reply(true); assertFalse(second.isDone)
        gate.revoke(); current.reply(true)
        assertFalse(second.get(2, TimeUnit.SECONDS))
    }
    @Test fun queuedShowAfterRevocationNeverPresentsAndCannotApprove() {
        val owner = owner(); val posted = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
        val gate = CommandApprovalGate({ posted.add(it) }, { it === owner })
        val ui = Ui(); gate.attach(ui)
        val request = CommandApprovalRequest(owner, "call", WriteEffect("shell", "exec", "target", "ls"), "ls")
        val waiting = FutureTask { gate.await(request) { false } }
        Thread(waiting).start()
        val show = checkNotNull(posted.poll(2, TimeUnit.SECONDS))
        gate.detach(ui); show()
        assertFalse(waiting.get(2, TimeUnit.SECONDS)); assertEquals(1L, ui.shown.count)
    }
}
