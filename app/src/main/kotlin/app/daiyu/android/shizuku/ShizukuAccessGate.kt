package app.daiyu.android.shizuku

import java.util.UUID

enum class ShizukuAvailability { UNAVAILABLE, UNSUPPORTED, PERMISSION_REQUIRED, AUTHORIZED, CLOSED }
data class ShizukuAuthorizationState(val availability: ShizukuAvailability, val uid: Int? = null, val connected: Boolean = false)

/** OS authorization is distinct from a task's exact write-effect authorization. */
internal class ShizukuAccessGate {
    internal class Grant(val server: Any, val generation: Long, val uid: Int, val permissionGeneration: String = UUID.randomUUID().toString())
    private var server: Any? = null
    private var uid: Int? = null
    private var version = 0
    private var granted = false
    private var generation = 0L
    private var closed = false
    @Synchronized fun update(server: Any?, version: Int, granted: Boolean, uid: Int?) {
        if (closed) return
        if (this.server !== server || this.version != version || this.granted != granted || this.uid != uid) generation++
        this.server = server; this.version = version; this.granted = granted; this.uid = uid
    }
    @Synchronized fun state(): ShizukuAuthorizationState = when {
        closed -> ShizukuAuthorizationState(ShizukuAvailability.CLOSED)
        server == null -> ShizukuAuthorizationState(ShizukuAvailability.UNAVAILABLE)
        version < 13 || uid !in setOf(0, 2000) && granted -> ShizukuAuthorizationState(ShizukuAvailability.UNSUPPORTED)
        !granted -> ShizukuAuthorizationState(ShizukuAvailability.PERMISSION_REQUIRED)
        else -> ShizukuAuthorizationState(ShizukuAvailability.AUTHORIZED, uid)
    }
    @Synchronized fun acquire(expectedUid: Int): Grant {
        require(expectedUid in setOf(0, 2000))
        check(state().availability == ShizukuAvailability.AUTHORIZED && uid == expectedUid) { "shizuku_authorization_or_identity_mismatch" }
        return Grant(checkNotNull(server), generation, expectedUid)
    }
    @Synchronized fun current(grant: Grant): Boolean = !closed && granted && version >= 13 &&
        server === grant.server && generation == grant.generation && uid == grant.uid
    @Synchronized fun rotate() { generation++ }
    @Synchronized fun invalidate() { generation++; server = null; granted = false; uid = null }
    @Synchronized fun close() { closed = true; invalidate() }
}
