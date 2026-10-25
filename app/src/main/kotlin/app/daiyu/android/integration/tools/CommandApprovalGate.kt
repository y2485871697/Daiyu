package app.daiyu.android.integration.tools

import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.WriteEffect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class CommandApprovalRequest(val owner: RunExecutionOwner, val callId: String,
    val effect: WriteEffect, val disclosure: String)

internal interface CommandApprovalUi {
    fun show(request: CommandApprovalRequest, answer: (Boolean) -> Unit)
    fun dismiss(request: CommandApprovalRequest)
}

/** One live foreground host and one original request. UI replies never execute a command. */
internal class CommandApprovalGate(private val post: (() -> Unit) -> Unit,
    private val current: (RunExecutionOwner) -> Boolean) {
    private class Pending(val request: CommandApprovalRequest, val host: CommandApprovalUi) {
        val answer = CountDownLatch(1)
        var accepted = false
        var revoked = false
        var answered = false
    }
    private val lock = Any()
    private var host: CommandApprovalUi? = null
    private var pending: Pending? = null
    fun attach(ui: CommandApprovalUi) = synchronized(lock) {
        if (host !== ui) revokeLocked()
        host = ui
    }
    fun detach(ui: CommandApprovalUi) = synchronized(lock) {
        if (host === ui) { host = null; revokeLocked() }
    }
    fun revoke(owner: RunExecutionOwner? = null) = synchronized(lock) {
        if (owner == null || pending?.request?.owner === owner) revokeLocked()
    }
    private fun revokeLocked() {
        pending?.let { it.revoked = true; it.accepted = false; it.answer.countDown() }
    }
    fun await(request: CommandApprovalRequest, cancelled: () -> Boolean): Boolean {
        if (cancelled() || !current(request.owner)) return false
        val entry = synchronized(lock) {
            if (pending != null) return false
            Pending(request, host ?: return false).also { pending = it }
        }
        try {
            post {
                val valid = current(request.owner) && !cancelled() && synchronized(lock) {
                    pending === entry && host === entry.host && !entry.revoked
                }
                if (!valid) reply(entry, false)
                else try { entry.host.show(request) { reply(entry, it) } }
                catch (_: Exception) { reply(entry, false) }
            }
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            while (!entry.answer.await(100, TimeUnit.MILLISECONDS)) {
                if (cancelled() || !current(request.owner) || System.nanoTime() >= deadline) return false
            }
            if (cancelled() || !current(request.owner)) return false
            return synchronized(lock) {
                pending === entry && host === entry.host && entry.accepted && !entry.revoked
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            synchronized(lock) { if (pending === entry) pending = null; entry.revoked = true }
            post { entry.host.dismiss(request) }
        }
    }
    private fun reply(entry: Pending, accepted: Boolean) = synchronized(lock) {
        if (pending !== entry || host !== entry.host || entry.revoked || entry.answered) return@synchronized
        entry.answered = true; entry.accepted = accepted; entry.answer.countDown()
    }
}
