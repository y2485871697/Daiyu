package app.daiyu.android

import app.daiyu.android.media.ImageDimensions
import app.daiyu.android.media.ImageInspector
import app.daiyu.android.model.generation.GenerationInput
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.model.generation.GenerationRequestPlan
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The chat draft and the generation form deliberately have different attachment namespaces. */
internal object GenerationFormInputs {
    fun supported(protocol: GenerationProtocol, references: List<GenerationInput>, mask: GenerationInput?): Boolean =
        when (protocol) {
            GenerationProtocol.OPENAI_IMAGES_JSON -> references.isEmpty() && mask == null
            GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> references.size == 1 &&
                references.single().mimeType == "image/png" && (mask == null || mask.mimeType == "image/png")
            GenerationProtocol.OPENAI_VIDEO_MULTIPART -> references.size <= 1 && mask == null
        }

    fun ids(references: List<GenerationInput>, mask: GenerationInput?): List<String> =
        references.mapIndexed { index, input -> id("reference:$index", input) } +
            listOfNotNull(mask?.let { id("mask", it) })

    private fun id(role: String, input: GenerationInput): String = "$role:${input.mimeType}:" +
        MessageDigest.getInstance("SHA-256").digest(input.bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal enum class GenerationInputTarget { REFERENCE, MASK }

/** No URI, grant, owner, or network authority is saved with the resulting immutable input. */
internal object GenerationInputImport {
    fun read(source: String, open: () -> InputStream, inspector: ImageInspector,
        protocol: GenerationProtocol, target: GenerationInputTarget, reference: GenerationInput?,
        cancelled: () -> Boolean): GenerationInput {
        val uri = URI(source)
        require(uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank())
        require(protocol != GenerationProtocol.OPENAI_IMAGES_JSON)
        require(target != GenerationInputTarget.MASK ||
            protocol == GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART && reference != null)
        fun checkCurrent() { check(!cancelled()) { "generation_input_cancelled" } }
        checkCurrent()
        val bytes = open().use { stream ->
            ByteArrayOutputStream().use { out ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkCurrent()
                    // At most limit + one sentinel byte is read, regardless of provider metadata.
                    val count = stream.read(buffer, 0,
                        minOf(buffer.size, GenerationRequestPlan.MAX_INPUT_BYTES - out.size() + 1))
                    if (count < 0) break
                    require(count > 0) { "generation_input_empty_read" }
                    require(out.size() + count <= GenerationRequestPlan.MAX_INPUT_BYTES)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        }
        checkCurrent()
        require(bytes.isNotEmpty())
        val actual = inspector.inspect(bytes)
        validate(actual)
        if (protocol == GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART) require(actual.mime == "image/png")
        if (target == GenerationInputTarget.MASK) {
            val original = inspector.inspect(checkNotNull(reference).bytes)
            validate(original)
            require(original.mime == "image/png" && actual.mime == "image/png" &&
                actual.width == original.width && actual.height == original.height)
        }
        checkCurrent()
        // Never resize, rotate, transcode, or recompress original input bytes.
        return GenerationInput(bytes, actual.mime)
    }

    private fun validate(info: ImageDimensions) {
        require(info.mime in setOf("image/png", "image/jpeg", "image/webp"))
        require(info.width > 0 && info.height > 0 && info.width.toLong() * info.height <= 40_000_000)
    }
}

internal data class GenerationInputIdentity(
    val entry: Any, val visit: Any, val conversation: String?, val editorGeneration: Long,
    val draftRevision: Long, val text: String, val formRevision: Long, val profileId: String,
    val inputIds: List<String>,
) {
    override fun toString() = "GenerationInputIdentity(<redacted>)"
}

/** Process-owned capacity, not Activity-owned executors. A provider that ignores cancellation
 * occupies the single slot until BOTH the reader and its cancellation calls physically return.
 * No UI wait, shutdownNow, timeout, or Future cancellation is treated as evidence of drain.
 * The injected executors are for deterministic tests; production always uses [shared].
 */
internal class GenerationInputWorkers(
    private val execute: (() -> Unit) -> Unit = { Runtime.reader.execute(it) },
    internal val cancel: (() -> Unit) -> Unit = { Runtime.canceller.execute(it) },
) {
    private var occupied: GenerationInputHost.Request? = null
    val pending get() = synchronized(this) { occupied != null }

    fun submit(request: GenerationInputHost.Request, work: () -> GenerationInput,
        complete: (Result<GenerationInput>) -> Unit): Boolean {
        synchronized(this) {
            if (occupied != null) return false
            occupied = request
        }
        var result: Result<GenerationInput>? = null
        request.trackDrain {
            synchronized(this) { check(occupied === request); occupied = null }
            complete(checkNotNull(result))
        }
        try {
            execute {
                try {
                    result = runCatching {
                        check(!request.cancelled.get()) { "generation_input_cancelled" }
                        work()
                    }
                } finally {
                    // Only this finally proves the read/open stack has unwound.
                    request.workerExited()
                }
            }
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            result = Result.failure(failure)
            request.workerExited()
        }
        return true
    }

    companion object { val shared = GenerationInputWorkers() }
    private object Runtime {
        val reader = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "generation-input").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
        // Signal.cancel() itself can block in a provider. Stream.close() must still get a lane.
        val canceller = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
            { task -> Thread(task, "generation-input-cancel").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
}

/** UI-thread state machine. A picker suspension retains only an unconfirmed selection, not an
 * approval. The external result slot remains a tombstone until its own late callback. Logical
 * cancellation immediately revokes authority; a reading request remains pending until drain.
 */
internal class GenerationInputHost(
    private val current: () -> GenerationInputIdentity?,
    private val read: (String, Request) -> GenerationInput,
    private val dispatch: (() -> Unit) -> Unit,
    private val changed: (Boolean) -> Unit,
    private val finished: (Request, Result<GenerationInput>) -> Unit,
    private val workers: GenerationInputWorkers = GenerationInputWorkers.shared,
) : AutoCloseable {
    internal class Request(val identity: GenerationInputIdentity, val protocol: GenerationProtocol,
        val target: GenerationInputTarget, val reference: GenerationInput?,
        private val executeCancel: (() -> Unit) -> Unit) {
        val cancelled = AtomicBoolean(false)
        internal var paused = false
        internal var delivered = false
        internal var source: String? = null
        internal var reading = false
        private val lock = Any()
        private val cancellation = mutableListOf<() -> Unit>()
        private var registrations = 0
        private var cancelling = 0
        private var exited = false
        private var onDrain: (() -> Unit)? = null
        val drained get() = synchronized(lock) { exited && cancelling == 0 }

        /** At most the per-open signal and the opened stream. Never invoked on the UI thread. */
        fun onCancel(action: () -> Unit) {
            val run = synchronized(lock) {
                check(!exited && ++registrations <= 2)
                if (cancelled.get()) { cancelling++; true }
                else { cancellation += action; false }
            }
            if (run) scheduleCancel(action)
        }

        fun clearCancellation() { synchronized(lock) { cancellation.clear() } }

        internal fun cancel() {
            val actions = synchronized(lock) {
                cancelled.set(true)
                cancellation.toList().also { cancelling += it.size; cancellation.clear() }
            }
            actions.forEach(::scheduleCancel)
        }

        private fun scheduleCancel(action: () -> Unit) {
            executeCancel {
                try { runCatching(action) }
                finally {
                    val notify = synchronized(lock) { cancelling--; takeDrain() }
                    notify?.invoke()
                }
            }
        }

        internal fun trackDrain(notify: () -> Unit) { synchronized(lock) { onDrain = notify } }
        internal fun workerExited() {
            val notify = synchronized(lock) { cancellation.clear(); exited = true; takeDrain() }
            notify?.invoke()
        }
        private fun takeDrain(): (() -> Unit)? =
            if (exited && cancelling == 0) onDrain.also { onDrain = null } else null
    }
    private var active: Request? = null
    private var external: Request? = null
    private var resumed = false
    @Volatile private var closed = false
    val pending get() = active?.let { !closed || it.reading && !it.drained } == true

    fun begin(identity: GenerationInputIdentity, protocol: GenerationProtocol,
        target: GenerationInputTarget, reference: GenerationInput?): Request? {
        if (closed || !resumed || active != null || external != null || current() != identity ||
            protocol == GenerationProtocol.OPENAI_IMAGES_JSON ||
            target == GenerationInputTarget.MASK &&
                (protocol != GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART || reference == null)) return null
        return Request(identity, protocol, target, reference, workers.cancel).also {
            active = it; external = it; changed(true)
        }
    }

    /** Only the first external-picker hop is exempt from normal editor revocation. */
    fun pause(): Boolean {
        resumed = false
        val request = active
        if (request != null && external === request && !request.paused &&
            !request.cancelled.get() && current() == request.identity) {
            request.paused = true
            return true
        }
        invalidate()
        return false
    }

    fun resume() {
        if (closed) return
        resumed = true
        val request = active ?: return
        if (current() != request.identity || request.cancelled.get()) { invalidate(); return }
        startRead(request)
    }

    /** Called once by the fixed ActivityResult launcher, including null on cancellation. */
    fun deliver(source: String?) {
        val request = external ?: return
        external = null
        if (active !== request || request.cancelled.get() || closed) return
        request.delivered = true
        request.source = source
        if (source == null) { invalidate(); return }
        if (resumed) startRead(request)
    }

    fun launchFailed(request: Request) {
        if (external === request) external = null
        if (active === request) invalidate()
    }

    private fun startRead(request: Request) {
        if (!request.delivered || request.reading || !resumed || active !== request) return
        if (current() != request.identity) { invalidate(); return }
        val source = request.source ?: return
        request.reading = true
        // Neither the stuck work nor its drain callback may retain this host / Activity.
        // Main supplies a reader capturing only the application ContentResolver.
        val reader = read
        val owner = WeakReference(this)
        val accepted = workers.submit(request, { reader(source, request) }) { result ->
            owner.get()?.workerDrained(request, result)
        }
        if (!accepted) {
            request.reading = false
            active = null; request.cancel(); changed(false)
            finished(request, Result.failure(IllegalStateException("generation_input_worker_busy")))
        }
    }

    private fun workerDrained(request: Request, result: Result<GenerationInput>) {
        if (closed) return
        dispatch {
            if (active === request) {
                val valid = !closed && resumed && !request.cancelled.get() && current() == request.identity
                active = null; request.source = null; changed(false)
                request.cancel()
                if (valid) finished(request, result)
            }
        }
    }

    fun invalidate() {
        val request = active
        request?.cancel()
        request?.source = null
        // Revocation is NOT drain. Reject new imports until the physical worker/cancel finally.
        if (request?.reading != true) active = null
        changed(pending)
        // Do not clear external until its own callback (or a failed launch).
    }

    override fun close() {
        closed = true; resumed = false; invalidate()
        // The stable process manager owns any undrained work; never spawn a replacement executor.
        // Its only host reference is weak, and no completion is sent to a destroyed Activity.
    }
}
