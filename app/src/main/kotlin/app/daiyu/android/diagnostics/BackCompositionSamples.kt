package app.daiyu.android.diagnostics

/** Main-thread compiler callbacks; elapsed self time excludes nested compiler callbacks, not CPU waits. */
internal class BackCompositionSamples(
    private val clock: () -> Long = System::nanoTime,
    private val observed: (BackPerformanceRecorder.Token) -> Unit,
    private val limited: (BackPerformanceRecorder.Token) -> Unit,
    private val completed: (BackPerformanceRecorder.Token, String, Long, Long, Long) -> Unit,
    private val rootName: ((String) -> Boolean)? = null,
) {
    private var token: BackPerformanceRecorder.Token? = null
    private var depth = 0
    private var calls = 0
    private var stopped = false
    private val names = arrayOfNulls<String>(MAX_DEPTH)
    private val starts = LongArray(MAX_DEPTH)
    private val children = LongArray(MAX_DEPTH)

    fun enabled(source: BackPerformanceRecorder.Token?): Boolean {
        if (source != token) {
            names.fill(null)
            depth = 0
            calls = 0
            stopped = false
            token = source
        }
        return source != null && !stopped
    }

    fun start(source: BackPerformanceRecorder.Token?, name: String) {
        if (!enabled(source)) return
        val owner = source ?: return
        if (depth == 0 && rootName?.invoke(name) == false) return
        if (calls == MAX_CALLS || depth == MAX_DEPTH) {
            names.fill(null)
            depth = 0
            stopped = true
            limited(owner)
            return
        }
        if (calls++ == 0) observed(owner)
        names[depth] = name
        starts[depth] = clock()
        children[depth] = 0
        depth++
    }

    fun end(source: BackPerformanceRecorder.Token?) {
        if (!enabled(source) || depth == 0) return
        val owner = source ?: return
        val slot = --depth
        val name = names[slot] ?: return
        names[slot] = null
        val elapsed = (clock() - starts[slot]).coerceAtLeast(0)
        val self = (elapsed - children[slot]).coerceAtLeast(0)
        if (depth > 0) children[depth - 1] += elapsed
        if (self >= MIN_SELF_NS) completed(owner, name, starts[slot], elapsed, self)
    }

    companion object {
        const val MAX_DEPTH = 128
        const val MAX_CALLS = 4096
        const val MIN_SELF_NS = 1_000_000L
    }
}

/** Keeps native section ownership while tracing is toggled or sampling stops mid-composition. */
internal class CompositionTraceEvents {
    private val native = BooleanArray(BackCompositionSamples.MAX_DEPTH)
    private var depth = 0
    private var overflow = 0
    val pending: Boolean get() = depth != 0 || overflow != 0

    fun start(systemEnabled: Boolean): Boolean {
        if (overflow != 0 || depth == native.size) {
            overflow++
            return false
        }
        native[depth++] = systemEnabled
        return systemEnabled
    }

    fun end(): Boolean {
        if (overflow != 0) { overflow--; return false }
        return if (depth == 0) false else native[--depth]
    }
}
