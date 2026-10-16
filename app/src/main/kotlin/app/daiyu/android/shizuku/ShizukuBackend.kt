package app.daiyu.android.shizuku

/** Observed through one authorized UserService, never inferred from configuration. */
@ConsistentCopyVisibility
data class ShizukuIdentity internal constructor(val uid: Int, val serviceGeneration: String, val permissionGeneration: String) {
    init { require(uid in setOf(0, 2000)); require(serviceGeneration.isNotBlank() && permissionGeneration.isNotBlank()) }
    val kind: String get() = if (uid == 0) "root" else "adb_shell"
}

data class ShizukuCommand(val requestId: String, val script: String, val timeoutMillis: Long = 30_000) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(script.isNotBlank() && '\u0000' !in script && script.toByteArray(Charsets.UTF_8).size <= 131_072)
        require(timeoutMillis in 1..120_000)
    }
}

@ConsistentCopyVisibility
data class ShizukuCommandHandle internal constructor(val requestId: String, val identity: ShizukuIdentity)
sealed interface ShizukuDispatch {
    data class Dispatched(val handle: ShizukuCommandHandle) : ShizukuDispatch
    data class NotExecuted(val reason: String) : ShizukuDispatch
    data class Unknown(val requestId: String, val reason: String) : ShizukuDispatch
}
sealed interface ShizukuOutcome {
    data object Running : ShizukuOutcome
    data class Completed(val exitCode: Int) : ShizukuOutcome
    data class Unknown(val reason: String) : ShizukuOutcome
}
data class ShizukuCommandPage(val outcome: ShizukuOutcome, val bytes: ByteArray,
    val offset: Int, val nextOffset: Int, val retainedBytes: Int, val truncated: Boolean, val outputComplete: Boolean)

sealed interface ShizukuCloseResult {
    /** The owned shell, engine workers and local RPCs have exited; not detached descendants. */
    data object Drained : ShizukuCloseResult
    data class Unknown(val reason: String) : ShizukuCloseResult
}

/** Worker-only execution/poll/cancel. close fences immediately and only requests remote cleanup. */
interface ShizukuBackend : AutoCloseable {
    val identity: ShizukuIdentity
    fun execute(command: ShizukuCommand): ShizukuDispatch
    fun poll(handle: ShizukuCommandHandle, offset: Int = 0, limit: Int = 16_384): ShizukuCommandPage
    /** Cancellation never promises side effects were rolled back or detached descendants stopped. */
    fun cancel(handle: ShizukuCommandHandle): ShizukuOutcome.Unknown
    fun closeWithResult(onResult: (ShizukuCloseResult) -> Unit)
    override fun close()
}
