package app.daiyu.android.shizuku

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import java.util.UUID

/** Shizuku constructs this Binder in its own process, not as an Android Service. */
class ShizukuUserService : IShizukuExecutor.Stub() {
    private val lock = Any()
    private val uid = Process.myUid()
    private val generation = UUID.randomUUID().toString()
    private val engine = ShizukuCommandEngine()
    private var owner: IBinder? = null
    private var callerUid: Int? = null
    private var permission: String? = null
    private var closed = false
    private val ownerDeath = IBinder.DeathRecipient { shutdown() }

    override fun attach(client: IBinder, permissionGeneration: String): Bundle = synchronized(lock) {
        check(!closed && uid in setOf(0, 2000)) { "unsupported_service_identity" }
        require(permissionGeneration.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        if (owner == null) {
            client.linkToDeath(ownerDeath, 0)
            owner = client; callerUid = Binder.getCallingUid(); permission = permissionGeneration
        } else check(owner == client && callerUid == Binder.getCallingUid() && permission == permissionGeneration) { "service_already_owned" }
        Bundle().apply { putInt("uid", uid); putString("service_generation", generation); putString("permission_generation", permission) }
    }
    private fun verify(service: String, grant: String) = synchronized(lock) {
        check(!closed && owner?.isBinderAlive == true && callerUid == Binder.getCallingUid() &&
            service == generation && grant == permission) { "service_scope_revoked" }
    }
    override fun execute(service: String, grant: String, expectedUid: Int, requestId: String, script: ByteArray, timeoutMillis: Long): Bundle {
        verify(service, grant); require(expectedUid == uid)
        return when (val admission = engine.submit(requestId, script, timeoutMillis)) {
            ShizukuCommandEngine.Admission.Accepted -> Bundle().apply { putString("status", "dispatched"); putString("request_id", requestId) }
            is ShizukuCommandEngine.Admission.Rejected -> Bundle().apply { putString("status", "not_executed"); putString("reason", admission.reason) }
        }
    }
    override fun poll(service: String, grant: String, requestId: String, offset: Int, limit: Int): Bundle {
        verify(service, grant)
        val page = engine.page(requestId, offset, limit)
        return Bundle().apply {
            when (val state = page.outcome) {
                ShizukuOutcome.Running -> putString("state", "running")
                is ShizukuOutcome.Completed -> { putString("state", "completed"); putInt("exit_code", state.exitCode) }
                is ShizukuOutcome.Unknown -> { putString("state", "unknown"); putString("reason", state.reason) }
            }
            putByteArray("bytes", page.bytes); putInt("offset", page.offset); putInt("next_offset", page.nextOffset)
            putInt("retained_bytes", page.retainedBytes); putBoolean("truncated", page.truncated); putBoolean("output_complete", page.outputComplete)
        }
    }
    override fun cancel(service: String, grant: String, requestId: String): Bundle {
        verify(service, grant)
        return Bundle().apply { putBoolean("requested", engine.cancel(requestId)); putString("reason", "cancel_requested_owned_shell_only") }
    }
    override fun closeExecution(service: String, grant: String): Bundle {
        verify(service, grant)
        engine.close()
        return Bundle().apply { putBoolean("drained", engine.isDrained()) }
    }
    override fun destroy() {
        val caller = Binder.getCallingUid()
        synchronized(lock) { check(caller == uid || caller == callerUid) { "foreign_caller" } }
        shutdown()
    }
    private fun shutdown() {
        val client = synchronized(lock) { if (closed) return; closed = true; owner }
        client?.let { runCatching { it.unlinkToDeath(ownerDeath, 0) } }
        engine.close()
        if (uid in setOf(0, 2000)) Process.killProcess(Process.myPid())
    }
}
