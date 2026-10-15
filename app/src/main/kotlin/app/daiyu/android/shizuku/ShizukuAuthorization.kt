package app.daiyu.android.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Bundle
import app.daiyu.android.terminal.OperationJournal
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** No prompting, binding, command execution or fallback until the host explicitly asks. */
class ShizukuAuthorization(context: Context) : AutoCloseable {
    private val app = context.applicationContext ?: context
    private val gate = ShizukuAccessGate()
    private val cleanup = Executors.newSingleThreadExecutor { task -> Thread(task, "shizuku-cleanup").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val connectLock = Any()
    @Volatile private var current: ShizukuConnection? = null
    private val received = Shizuku.OnBinderReceivedListener { invalidate() }
    private val died = Shizuku.OnBinderDeadListener { invalidate() }
    private val permission = Shizuku.OnRequestPermissionResultListener { request, _ -> if (request == PERMISSION_REQUEST) invalidate() }
    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(died)
        Shizuku.addRequestPermissionResultListener(permission)
    }
    private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "shizuku_worker_required" } }
    val state: ShizukuAuthorizationState get() = gate.state().let { it.copy(connected = current?.isOpen == true) }
    private fun server(): IShizukuService? = Shizuku.getBinder()?.takeIf { it.isBinderAlive }?.let(IShizukuService.Stub::asInterface)
    fun refresh(): ShizukuAuthorizationState {
        worker()
        if (closed.get()) return state
        try {
            val binder = Shizuku.getBinder()?.takeIf { it.isBinderAlive }
            val service = binder?.let(IShizukuService.Stub::asInterface)
            if (service == null) gate.invalidate()
            else {
                // Version comes from the unprivileged SDK handshake; direct getVersion requires a grant.
                val version = Shizuku.getVersion()
                // Direct server query: the SDK checkSelfPermission() caches a true grant.
                val allowed = version >= 13 && service.checkSelfPermission()
                gate.update(binder, version, allowed, if (allowed) service.uid else null)
            }
        } catch (_: Exception) { gate.invalidate() }
        current?.takeIf { !it.isOpen }?.close()
        return state
    }
    fun requestPermission() {
        worker(); check(!closed.get())
        val status = refresh()
        check(status.availability in setOf(ShizukuAvailability.PERMISSION_REQUIRED, ShizukuAvailability.AUTHORIZED)) { "shizuku_service_unavailable" }
        if (status.availability != ShizukuAvailability.AUTHORIZED) Shizuku.requestPermission(PERMISSION_REQUEST)
    }
    private fun invalidate() { gate.invalidate(); current?.close() }
    /** expectedUid is an explicit host choice: default shell never silently accepts a root service. */
    fun connect(expectedUid: Int = 2000): ShizukuConnection = synchronized(connectLock) {
        worker(); refresh(); check(!closed.get())
        gate.rotate()
        val grant = gate.acquire(expectedUid)
        current?.close()
        val args = Shizuku.UserServiceArgs(ComponentName(app, ShizukuUserService::class.java))
            .tag("daiyu-${UUID.randomUUID()}").version(1).daemon(false).debuggable(false).processNameSuffix("shizuku")
        val ready = CountDownLatch(1)
        val remote = AtomicReference<IBinder?>()
        val abandoned = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        fun release() {
            if (abandoned.compareAndSet(false, true)) queueCleanup {
                try { Shizuku.unbindUserService(args, connection, true) }
                finally { Shizuku.unbindUserService(args, connection, false) }
            }
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (abandoned.get() || !gate.current(grant)) {
                    queueCleanup {
                try { Shizuku.unbindUserService(args, connection, true) }
                finally { Shizuku.unbindUserService(args, connection, false) }
            }
                    return
                }
                remote.set(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote.set(null); ready.countDown(); if (gate.current(grant)) invalidate() }
        }
        try {
            Shizuku.bindUserService(args, connection)
            check(ready.await(5, TimeUnit.SECONDS) && gate.current(grant)) { "shizuku_bind_unconfirmed" }
            val binder = checkNotNull(remote.get()) { "shizuku_service_disconnected" }
            val owner = Binder()
            val endpoint = IShizukuExecutor.Stub.asInterface(binder)
            val attach = Executors.newSingleThreadExecutor { task -> Thread(task, "shizuku-attach").apply { isDaemon = true } }
            val observation = try { attach.submit<Bundle> { endpoint.attach(owner, grant.permissionGeneration) }.get(5, TimeUnit.SECONDS) }
                finally { attach.shutdownNow() }
            val actualUid = observation.requireInt("uid")
            check(actualUid == expectedUid && observation.requireString("permission_generation") == grant.permissionGeneration && gate.current(grant)) { "shizuku_identity_mismatch" }
            val identity = ShizukuIdentity(actualUid, observation.requireString("service_generation"), grant.permissionGeneration)
            binder.linkToDeath({ if (gate.current(grant)) invalidate() }, 0)
            ShizukuConnection(identity, endpoint, owner, File(app.filesDir, "shizuku-journal"),
                { gate.current(grant) && binder.isBinderAlive && !abandoned.get() }, {
                    val observed = Shizuku.getBinder()
                    observed === grant.server && server()?.let { it.checkSelfPermission() && it.uid == expectedUid } == true
                }, ::release).also { lease ->
                    check(gate.current(grant)) { "shizuku_scope_revoked" }
                    current = lease
                }
        } catch (failure: Exception) { release(); throw failure }
    }
    private fun queueCleanup(action: () -> Unit) {
        val task = Runnable { runCatching(action) }
        try { cleanup.execute(task) }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            // A late bind callback still owns cleanup after the manager has closed.
            Thread(task, "shizuku-late-cleanup").apply { isDaemon = true; start() }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        gate.close(); current?.close()
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(died)
        Shizuku.removeRequestPermissionResultListener(permission)
        cleanup.shutdown()
    }
    companion object { private const val PERMISSION_REQUEST = 0x5348 }
}

/** A live connection is not a task permission. Only trusted host code may create an execution scope. */
class ShizukuConnection internal constructor(val identity: ShizukuIdentity, private val remote: IShizukuExecutor,
    @Suppress("unused") private val owner: IBinder, private val directory: File,
    private val live: () -> Boolean, private val permission: () -> Boolean, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var scope: String? = null
    private var backend: ShizukuBackend? = null
    val isOpen: Boolean get() = !closed.get() && live()
    @Synchronized fun openBackend(hostScope: String): ShizukuBackend {
        check(Looper.myLooper() != Looper.getMainLooper()) { "shizuku_worker_required" }
        check(isOpen)
        backend?.let { check(scope == hostScope) { "shizuku_scope_already_bound" }; return it }
        val journal = OperationJournal(directory, hostScope, binding = JSONObject().put("backend", "shizuku")
            .put("uid", identity.uid).put("service", identity.serviceGeneration).put("permission", identity.permissionGeneration))
        check(journal.verifyBinding().getBoolean("ok")) { "shizuku_scope_binding_unavailable" }
        return BinderShizukuBackend(identity, remote, journal, { isOpen }, permission, ::close).also { backend = it; scope = hostScope }
    }
    override fun close() { if (closed.compareAndSet(false, true)) { backend?.close(); release() } }
}
