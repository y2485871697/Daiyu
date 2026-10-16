package app.daiyu.android.shizuku

import android.os.Bundle
import app.daiyu.android.terminal.OperationJournal
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class BinderShizukuBackend(
    override val identity: ShizukuIdentity,
    private val remote: IShizukuExecutor,
    private val journal: OperationJournal,
    private val live: () -> Boolean,
    private val permission: () -> Boolean,
    private val release: () -> Unit
) : ShizukuBackend {
    private val closed = AtomicBoolean(false)
    private val closeSignal = ShizukuCloseSignal()
    private val calls = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, "shizuku-rpc").apply { isDaemon = true } })
    private fun available() = !closed.get() && live()
    private fun <T> rpc(call: () -> T): T {
        check(available()) { "shizuku_scope_revoked" }
        val pending = calls.submit<T> { check(available()) { "shizuku_scope_revoked" }; call() }
        return try { pending.get(5, TimeUnit.SECONDS) } catch (failure: Exception) {
            pending.cancel(true); close(); throw failure
        }
    }
    override fun execute(command: ShizukuCommand): ShizukuDispatch {
        if (!available()) return ShizukuDispatch.NotExecuted("shizuku_scope_revoked")
        val binding = journal.verifyBinding()
        if (!binding.getBoolean("ok")) return ShizukuDispatch.NotExecuted(binding.getString("error"))
        val result = journal.once(command.requestId, "shizuku_execute",
            JSONObject().put("script", command.script).put("timeout_ms", command.timeoutMillis)) {
            if (!available()) OperationJournal.failure("shizuku_scope_revoked")
            else try {
                val response = rpc {
                    if (!permission()) Bundle().apply { putString("status", "not_executed"); putString("reason", "shizuku_permission_revoked") }
                    else remote.execute(identity.serviceGeneration, identity.permissionGeneration,
                        identity.uid, command.requestId, command.script.toByteArray(Charsets.UTF_8), command.timeoutMillis)
                }
                when (response.requireString("status")) {
                    "dispatched" -> {
                        check(response.requireString("request_id") == command.requestId)
                        JSONObject().put("ok", true).put("status", "dispatched").put("execution", "unknown")
                    }
                    "not_executed" -> {
                        val reason = response.requireString("reason")
                        if (reason == "shizuku_permission_revoked") close()
                        OperationJournal.failure(reason)
                    }
                    else -> error("invalid_dispatch_receipt")
                }
            } catch (_: Exception) {
                close(); JSONObject().put("ok", false).put("execution", "unknown").put("error", "shizuku_dispatch_unknown_not_replayed")
            }
        }
        return when {
            result.optString("status") == "dispatched" && result.optString("error").isEmpty() ->
                ShizukuDispatch.Dispatched(ShizukuCommandHandle(command.requestId, identity))
            result.optString("execution") == "unknown" -> ShizukuDispatch.Unknown(command.requestId, result.optString("error", "outcome_unknown_not_replayed"))
            else -> ShizukuDispatch.NotExecuted(result.optString("error", "shizuku_not_executed"))
        }
    }
    private fun owns(handle: ShizukuCommandHandle) = handle.identity === identity
    override fun poll(handle: ShizukuCommandHandle, offset: Int, limit: Int): ShizukuCommandPage {
        require(owns(handle)); require(offset >= 0 && limit in 1..65_536)
        return try {
            val value = rpc { check(permission()) { "shizuku_permission_revoked" }; remote.poll(identity.serviceGeneration, identity.permissionGeneration, handle.requestId, offset, limit) }
            val bytes = checkNotNull(value.getByteArray("bytes"))
            val start = value.requireInt("offset"); val end = value.requireInt("next_offset"); val retained = value.requireInt("retained_bytes")
            check(start == offset && bytes.size <= limit && end == start + bytes.size && retained in end..262_144)
            val outcome = when (value.requireString("state")) {
                "running" -> ShizukuOutcome.Running
                "completed" -> { check(value.containsKey("exit_code") && value.requireBoolean("output_complete")); ShizukuOutcome.Completed(value.requireInt("exit_code")) }
                "unknown" -> ShizukuOutcome.Unknown(value.requireString("reason"))
                else -> error("invalid_poll_receipt")
            }
            ShizukuCommandPage(outcome, bytes, start, end, retained, value.requireBoolean("truncated"), value.requireBoolean("output_complete"))
        } catch (_: Exception) {
            close(); ShizukuCommandPage(ShizukuOutcome.Unknown("shizuku_poll_unknown_not_replayed"), byteArrayOf(), offset, offset, offset, false, false)
        }
    }
    override fun cancel(handle: ShizukuCommandHandle): ShizukuOutcome.Unknown {
        require(owns(handle))
        return try {
            rpc { check(permission()) { "shizuku_permission_revoked" }; remote.cancel(identity.serviceGeneration, identity.permissionGeneration, handle.requestId) }
            ShizukuOutcome.Unknown("cancel_requested_owned_shell_only")
        } catch (_: Exception) { close(); ShizukuOutcome.Unknown("shizuku_cancel_unconfirmed") }
    }
    override fun closeWithResult(onResult: (ShizukuCloseResult) -> Unit) {
        closeSignal.observe(onResult)
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        calls.shutdownNow()
        Thread({
            val cleanupCalls = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "shizuku-close-rpc").apply { isDaemon = true }
            }
            var result: ShizukuCloseResult = ShizukuCloseResult.Unknown("shizuku_cleanup_unconfirmed")
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline) {
                    if (!permission()) break
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    val response = cleanupCalls.submit<Bundle> {
                        check(permission()) { "shizuku_permission_revoked" }
                        remote.closeExecution(identity.serviceGeneration, identity.permissionGeneration)
                    }.get(minOf(remaining, TimeUnit.SECONDS.toNanos(5)), TimeUnit.NANOSECONDS)
                    if (response.requireBoolean("drained")) {
                        val localBudget = deadline - System.nanoTime()
                        if (localBudget > 0 && calls.awaitTermination(localBudget, TimeUnit.NANOSECONDS))
                            result = ShizukuCloseResult.Drained
                        break
                    }
                    Thread.sleep(25)
                }
            } catch (_: Exception) {
                result = ShizukuCloseResult.Unknown("shizuku_cleanup_unconfirmed")
            } finally {
                cleanupCalls.shutdownNow()
                try { release() }
                finally { closeSignal.complete(result) }
            }
        }, "shizuku-close").apply { isDaemon = true; start() }
    }
}

internal fun Bundle.requireString(key: String): String = get(key) as? String ?: error("invalid_service_receipt_$key")

internal fun Bundle.requireInt(key: String): Int = get(key) as? Int ?: error("invalid_service_receipt_$key")
internal fun Bundle.requireBoolean(key: String): Boolean = get(key) as? Boolean ?: error("invalid_service_receipt_$key")
