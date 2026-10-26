package app.daiyu.android.terminal

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** A command owns the existing DPTY2 scope, not the su launcher PID. */
internal class RootScopeCommandProcess internal constructor(private val session: PtySession) : Process() {
    @Volatile private var terminated = false
    @Volatile var outputTruncated = false
        private set
    private val input = object : InputStream() {
        private var pending = ByteArray(0)
        private var offset = 0
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, start: Int, count: Int): Int {
            if (start < 0 || count < 0 || start > bytes.size - count) throw IndexOutOfBoundsException()
            if (count == 0) return 0
            while (offset == pending.size) {
                val ended = waitFor(1, TimeUnit.MILLISECONDS)
                val result = session.readAvailable()
                outputTruncated = outputTruncated || result.optBoolean("log_truncated")
                pending = java.util.Base64.getDecoder().decode(result.getString("stdout_base64"))
                offset = 0
                if (pending.isNotEmpty()) break
                if (!result.isNull("error")) throw IOException(result.getString("error"))
                if (ended) return -1
                Thread.sleep(5)
            }
            val size = minOf(count, pending.size - offset)
            pending.copyInto(bytes, start, offset, offset + size); offset += size
            return size
        }
    }
    override fun getInputStream(): InputStream = input
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) { throw IOException("root_command_stdin_not_supported") }
    }
    override fun destroy() = session.close()
    override fun destroyForcibly(): Process { destroy(); return this }
    override fun isAlive(): Boolean = !waitFor(1, TimeUnit.MILLISECONDS)
    override fun waitFor(): Int {
        while (!waitFor(30_000, TimeUnit.MILLISECONDS)) { /* An absent D receipt retains ownership. */ }
        return exitValue()
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        if (terminated) return true
        if (timeout <= 0) return false
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        do {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            if (session.awaitTermination(TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, 30_000))) {
                terminated = true
                session.close()
                return true
            }
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left > 0) Thread.sleep(minOf(left, 10))
        } while (System.nanoTime() < deadline)
        return false
    }
    override fun exitValue(): Int {
        if (!waitFor(1, TimeUnit.MILLISECONDS)) throw IllegalThreadStateException("root_scope_not_drained")
        return exitCodeOrNull() ?: throw IllegalThreadStateException("root_command_exit_unknown")
    }
    internal fun exitCodeOrNull(): Int? {
        if (isAlive) return null
        val status = session.read().optInt("wait_status", -1)
        if (status < 0) return null
        return if (status and 127 == 0) (status ushr 8) and 255 else 128 + (status and 127)
    }
    companion object {
        fun open(context: Context, backend: SelectedBackend, command: String, scope: String): RootScopeCommandProcess {
            val root = checkNotNull(backend.rootScope).copy(command = command)
            val session = PtySession.openBound(AndroidPtyHost.helper(context), root.guestArgv(), backend.environment,
                backend.cwd, File(context.filesDir, "terminal-command-pty"), scope, 24, 80,
                JSONObject().put("backend", backend.name).put("environment_id", backend.environmentId), rootScope = root)
            return RootScopeCommandProcess(session)
        }
    }
}
