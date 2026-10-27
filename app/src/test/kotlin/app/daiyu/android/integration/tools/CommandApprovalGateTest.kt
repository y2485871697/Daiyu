package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class CommandApprovalGateTest {
    private fun owner() = runnerOwner(Message(
        MessageId("m"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
    private class Ui : CommandApprovalUi {
        val shown = CountDownLatch(1)
        lateinit var reply: (Boolean) -> Unit
        override fun show(request: CommandApprovalRequest, answer: (Boolean) -> Unit) { reply = answer; shown.countDown() }
        override fun dismiss(request: CommandApprovalRequest) {}
    }
    @Test fun defaultAllowsMultipleDifferentRequestsWithoutHostOrUiPost() {
        val owner = owner()
        val gate = CommandApprovalGate({ fail("default consent must not post UI") }, { it === owner })
        val ui = object : CommandApprovalUi {
            override fun show(request: CommandApprovalRequest, answer: (Boolean) -> Unit) = fail("must not show")
            override fun dismiss(request: CommandApprovalRequest) = fail("must not dismiss")
        }
        repeat(4) { index ->
            if (index == 1) gate.attach(ui)
            if (index == 2) gate.detach(ui)
            val request = CommandApprovalRequest(owner, "call-$index",
                WriteEffect("terminal.command", "exec", "backend-$index", "echo $index"), "echo $index")
            assertTrue(gate.await(request) { false })
        }
    }

    @Test fun defaultRejectsStoppedOrExpiredOwnerWithoutUi() {
        val owner = owner()
        var live = true
        var stopped = false
        val gate = CommandApprovalGate({ fail("no UI") }, { live && it === owner })
        fun request(who: app.daiyu.android.domain.runtime.RunExecutionOwner = owner) =
            CommandApprovalRequest(who, "call", WriteEffect("terminal.command", "exec", "target", "bytes"), "bytes")
        assertFalse(gate.await(request(this.owner())) { false })
        stopped = true
        assertFalse(gate.await(request()) { stopped })
        stopped = false; live = false
        assertFalse(gate.await(request()) { stopped })
        live = true
        var checks = 0
        assertFalse(gate.await(request()) { ++checks == 2 })
    }

    @Test fun defaultRechecksOwnerAndHonorsRevocationDuringAdmission() {
        val owner = owner()
        val other = owner()
        val request = CommandApprovalRequest(owner, "call", WriteEffect("terminal.command", "exec", "target", "bytes"), "bytes")
        var checks = 0
        lateinit var gate: CommandApprovalGate
        gate = CommandApprovalGate({ fail("no UI") }, {
            checks++
            if (checks == 2) gate.revoke(owner)
            it === owner
        })
        assertFalse(gate.await(request) { false })
        // Revocation is request/owner scoped, not a permanent preference blocking later live runs.
        gate = CommandApprovalGate({ fail("no UI") }, {
            gate.revoke(other)
            it === owner
        })
        assertTrue(gate.await(request) { false })
        checks = 0
        gate = CommandApprovalGate({ fail("no UI") }, { ++checks == 1 })
        assertFalse(gate.await(request) { false })
    }

    @Test fun requiresAttachedHostAndOriginalReplyIsOneShot() {
        val owner = owner()
        val gate = CommandApprovalGate({ it() }, { it === owner }, requireInteractiveApproval = true)
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
        val owner = owner(); val gate = CommandApprovalGate({ it() }, { it === owner }, requireInteractiveApproval = true)
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
        val gate = CommandApprovalGate({ posted.add(it) }, { it === owner }, requireInteractiveApproval = true)
        val ui = Ui(); gate.attach(ui)
        val request = CommandApprovalRequest(owner, "call", WriteEffect("shell", "exec", "target", "ls"), "ls")
        val waiting = FutureTask { gate.await(request) { false } }
        Thread(waiting).start()
        val show = checkNotNull(posted.poll(2, TimeUnit.SECONDS))
        gate.detach(ui); show()
        assertFalse(waiting.get(2, TimeUnit.SECONDS)); assertEquals(1L, ui.shown.count)
    }
}
