package app.daiyu.android.vd

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Explicit, blocking worker entrypoint; construction never invokes su/app_process. No lifecycle
 * method kills or implicitly releases an owner. Retain this host in the parent journal/session
 * even after UNKNOWN. READY authenticates transport only: initialize is a separate deliberate call.
 */
class AndroidVirtualDisplayOwnerHost private constructor(private val config: () -> OwnerLaunchConfig) {
    constructor(context: Context) : this({ OwnerLaunchConfig.installed(context.applicationContext) })

    enum class Start { READY, UNKNOWN }
    enum class Lifecycle { NOT_STARTED, UNKNOWN, DRAINED }
    data class Observation(val lifecycle: Lifecycle, val stopped: Boolean, val launcherExit: Int?,
                           val ownerPid: Int?, val ownerExitObserved: Boolean)

    @Volatile private var attempted = false
    @Volatile private var process: Process? = null
    @Volatile private var ready: OwnerReady? = null
    @Volatile private var stopped = false
    @Volatile private var launcherExit: Int? = null
    private var generation: String? = null
    private var binding: OwnerRunBinding? = null
    @Volatile var client: VirtualDisplayOwnerClient? = null
        private set

    /** One launch attempt only. No automatic initialize, retries, fresh IDs or fallback dex. */
    @Synchronized fun start(generation: String, binding: OwnerRunBinding): Start {
        VirtualDisplayOwnerClient.checkWorker()
        if (attempted) throw OwnerClientException()
        safeInput { require(VirtualDisplayOwnerClient.ID.matches(generation)) }
        attempted = true
        this.generation = generation
        this.binding = binding
        val cfg = try { config() } catch (_: Exception) { return Start.UNKNOWN }
        val p = try {
            ProcessBuilder("su", "-c", cfg.script()).redirectErrorStream(false).start()
        } catch (_: Exception) { return Start.UNKNOWN }
        // Store BEFORE any handshake/pipe task can fail. It is deliberately never nulled.
        process = p
        val bootstrap = CountDownLatch(1)
        daemon("vd-owner-private-stdout") {
            try {
                while (true) {
                    val line = privateLine(p.inputStream, 1024) ?: break
                    if (line.startsWith("VD_OWNER_READY")) {
                        if (ready == null) ready = OwnerReady.parse(line, cfg.socket, cfg.uid)
                        bootstrap.countDown()
                    } else if (isStoppedMarker(line)) {
                        stopped = true
                    } else if (line.startsWith("VD_OWNER_ERROR")) bootstrap.countDown()
                    // Never publish stdout, stderr, parse errors, or the READY line anywhere.
                }
            } catch (_: Exception) { /* pipe EOF/failure is not STOPPED */ }
            finally { bootstrap.countDown() }
        }
        daemon("vd-owner-private-stderr") {
            try { val buffer = ByteArray(4096); while (p.errorStream.read(buffer) >= 0) Unit }
            catch (_: Exception) { /* discard; no credential-bearing diagnostics */ }
        }
        daemon("vd-owner-exit") {
            try { launcherExit = p.waitFor() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        try { bootstrap.await(15, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); return Start.UNKNOWN }
        return connectOnce()
    }

    /** Explicit reconnect only. Does not launch, initialize, execute, lookup or change identity. */
    @Synchronized fun reconnect(): Start {
        VirtualDisplayOwnerClient.checkWorker()
        client?.disconnectTransport()
        return connectOnce()
    }

    private fun connectOnce(): Start {
        val r = ready ?: return Start.UNKNOWN
        val g = generation ?: return Start.UNKNOWN
        val b = binding ?: return Start.UNKNOWN
        return try {
            val transport = LocalOwnerTransport.connect(r.socket, r.pid)
            // Preserve process shutdown admission, including a lost response, across reconnect.
            client = client?.reconnectTransport(transport)
                ?: VirtualDisplayOwnerClient(g, b, r.credential, transport)
            Start.READY
        } catch (_: Exception) { Start.UNKNOWN }
    }

    /** A closed socket, release reply, su exit, or an uninitialized process alone is NOT drained. */
    fun observe(): Observation {
        val pid = ready?.pid
        val gone = pid != null && try {
            // Signal zero observes existence/permission; it never sends a terminating signal.
            Os.kill(pid, 0)
            false
        } catch (e: ErrnoException) { e.errno == OsConstants.ESRCH }
        catch (_: Exception) { false }
        val drained = isDrained(stopped, launcherExit, gone)
        return Observation(if (!attempted) Lifecycle.NOT_STARTED else if (drained) Lifecycle.DRAINED else Lifecycle.UNKNOWN,
            stopped, launcherExit, pid, gone)
    }

    override fun toString() = "AndroidVirtualDisplayOwnerHost(host-only)"

    internal companion object {
        fun isStoppedMarker(line: String): Boolean =
            line == "VD_OWNER_STOPPED released=true receiptsFinal=true" ||
                line == "VD_OWNER_STOPPED initialized=false receiptsClosed=true"

        fun isDrained(stopped: Boolean, launcherExit: Int?, ownerGone: Boolean): Boolean =
            stopped && launcherExit == 0 && ownerGone
    }
}

private class OwnerReady(val socket: String, val pid: Int, val credential: OwnerCredential) {
    override fun toString() = "OwnerReady(REDACTED)"
    companion object {
        fun parse(line: String, socket: String, uid: Int): OwnerReady? {
            val match = Regex("VD_OWNER_READY v=2 socket=([A-Za-z0-9_-]+) token=([a-f0-9]{32}) pid=([0-9]+) uid=0 allowUid=([0-9]+) initialized=false")
                .matchEntire(line) ?: return null
            val pid = match.groupValues[3].toIntOrNull() ?: return null
            if (match.groupValues[1] != socket || match.groupValues[4].toIntOrNull() != uid || pid <= 0) return null
            return OwnerReady(socket, pid, OwnerCredential(match.groupValues[2]))
        }
    }
}

/** Fixed installed-code launch plan. No API accepts a receipt path, dex path or shell fragment. */
private class OwnerLaunchConfig(val uid: Int, val socket: String, val receiptRoot: String,
                                val ancestors: List<Pair<String, String>>, val apks: List<String>) {
    private val installedFiles = apks.map { path ->
        val s = Os.lstat(path)
        require(OsConstants.S_ISREG(s.st_mode) && s.st_uid in setOf(0, 1000))
        require(s.st_mode and 18 == 0) // installed code must not be group/world writable
        path to "${s.st_dev}:${s.st_ino}:${s.st_uid}:${s.st_gid}:${Integer.toOctalString(s.st_mode and 4095)}"
    }
    fun script(): String = buildString {
        append("set -eu\nPATH=/system/bin:/system/xbin\nexport PATH\numask 077\n[ \"$(id -u)\" = 0 ] || exit 71\n")
        for ((path, identity) in ancestors) {
            append("[ ! -L ${q(path)} ] && [ -d ${q(path)} ] || exit 72\n")
            append("[ \"$(stat -c '%d:%i:%u:%g:%a' ${q(path)})\" = ${q(identity)} ] || exit 72\n")
        }
        append("[ \"$(readlink -f ${q(File(receiptRoot).parent!!)})\" = ${q(File(receiptRoot).parent!!)} ] || exit 72\n")
        append("r=${q(receiptRoot)}\n")
        append("if [ ! -e \"\$r\" ] && [ ! -L \"\$r\" ]; then mkdir -m 700 \"\$r\"; chown 0:0 \"\$r\"; fi\n")
        // Existing directories are never chowned/chmodded (and no recursive ownership changes).
        append("[ ! -L \"\$r\" ] && [ -d \"\$r\" ] || exit 73\n")
        append("[ \"$(stat -c '%u:%g:%a' \"\$r\")\" = '0:0:700' ] || exit 73\n")
        append("[ \"$(readlink -f \"\$r\")\" = \"\$r\" ] || exit 73\n")
        append("leaf=$(stat -c '%d:%i' \"\$r\")\n")
        for ((apk, identity) in installedFiles) {
            append("[ -f ${q(apk)} ] && [ ! -L ${q(apk)} ] || exit 74\n")
            append("[ \"$(stat -c '%d:%i:%u:%g:%a' ${q(apk)})\" = ${q(identity)} ] || exit 74\n")
        }
        for ((path, identity) in ancestors) append("[ \"$(stat -c '%d:%i:%u:%g:%a' ${q(path)})\" = ${q(identity)} ] || exit 72\n")
        append("[ \"$(stat -c '%d:%i' \"\$r\")\" = \"\$leaf\" ] || exit 73\n")
        append("exec env CLASSPATH=${q(apks.joinToString(":"))} /system/bin/app_process /system/bin ")
        append("app.daiyu.android.vd.runtime.VirtualDisplayOwnerMain --socket ${q(socket)} --allow-uid $uid --receipt-root \"\$r\"\n")
    }
    override fun toString() = "OwnerLaunchConfig(host-only)"
    companion object {
        fun installed(context: Context): OwnerLaunchConfig {
            val uid = android.os.Process.myUid()
            require(uid % 100000 in 10000..19999)
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(context.packageName, 0)
            require(info.uid == uid)
            val apks = (listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())).map {
                val file = File(it)
                require(file.isAbsolute && file.isFile && file.canonicalPath == file.absolutePath && ':' !in it)
                it
            }
            // noBackup is obtained from the actual app Context, canonicalized, never supplied by a model.
            val base = context.noBackupFilesDir.canonicalFile
            val paths = generateSequence(base) { it.parentFile }.toList().reversed()
            val ancestors = paths.map { file ->
                val s = Os.lstat(file.path)
                require(OsConstants.S_ISDIR(s.st_mode) && !OsConstants.S_ISLNK(s.st_mode))
                require(s.st_uid in setOf(0, 1000, uid))
                require(s.st_mode and 2 == 0) // no world-writable ancestor
                if (s.st_mode and 16 != 0) require(s.st_gid in setOf(0, 1000, uid))
                file.path to "${s.st_dev}:${s.st_ino}:${s.st_uid}:${s.st_gid}:${Integer.toOctalString(s.st_mode and 4095)}"
            }
            return OwnerLaunchConfig(uid, "daiyu_vd_" + UUID.randomUUID().toString().replace("-", ""),
                File(base, "vd-owner-receipts-v2").path, ancestors, apks)
        }
        private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}

/** Production credentials come from the kernel, not from READY assertions alone. */
private class LocalOwnerTransport private constructor(private val socket: LocalSocket) : OwnerTransport {
    private val reader = InputStreamReader(socket.inputStream,
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    override fun exchange(line: String): String = boundedSocket(socket) {
        socket.outputStream.write(line.toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
        val result = StringBuilder()
        while (true) {
            val c = reader.read()
            require(c >= 0)
            if (c == 10) break
            require(c != 13 && result.length < 9 * 1024 * 1024)
            result.append(c.toChar())
        }
        result.toString()
    }
    override fun disconnect() { socket.close() }
    override fun toString() = "LocalOwnerTransport(host-only)"
    companion object {
        fun connect(name: String, pid: Int): LocalOwnerTransport {
            val socket = LocalSocket()
            try {
                return boundedSocket(socket) {
                    socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                    socket.soTimeout = 20_000
                    val peer = socket.peerCredentials
                    require(peer.uid == 0 && peer.pid == pid)
                    LocalOwnerTransport(socket)
                }
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) { }
                throw OwnerClientException()
            }
        }
    }
}

private val socketDeadline = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "vd-owner-socket-deadline").apply { isDaemon = true }
}
private fun <T> boundedSocket(socket: LocalSocket, block: () -> T): T {
    val deadline = socketDeadline.schedule({ try { socket.close() } catch (_: Exception) { } }, 20, TimeUnit.SECONDS)
    try { return block() } finally { deadline.cancel(false) }
}
private fun daemon(name: String, block: () -> Unit) { Thread(block, name).apply { isDaemon = true }.start() }
private fun privateLine(stream: InputStream, max: Int): String? {
    val out = StringBuilder()
    var invalid = false
    while (true) {
        val b = stream.read()
        if (b < 0) return null // unterminated bootstrap/STOPPED is never accepted
        if (b == 10) return if (invalid) "" else out.toString()
        if (b !in 32..126 || out.length >= max) invalid = true
        if (!invalid) out.append(b.toChar())
    }
}
