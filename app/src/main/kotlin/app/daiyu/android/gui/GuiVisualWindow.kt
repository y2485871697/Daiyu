package app.daiyu.android.gui

import app.daiyu.android.runningmode.AndroidRunningModeAuthorization
import app.daiyu.android.runningmode.AndroidRunningModeStore
import app.daiyu.android.runningmode.RunningMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A window-manager witness, not an accessibility window or a synthetic node tree. */
internal data class GuiVisualWindow(val identity: String, val packageName: String, val uid: Int,
    val bounds: GuiBounds, val displayBounds: GuiBounds, val rotation: Int,
    val above: List<GuiVisualOccluder>) {
    fun admits(gesture: GuiGesture): Boolean {
        fun inside(x: Int, y: Int) = x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom
        if (!inside(gesture.x, gesture.y) || !inside(gesture.endX, gesture.endY)) return false
        val path = GuiBounds(minOf(gesture.x, gesture.endX), minOf(gesture.y, gesture.endY),
            maxOf(gesture.x, gesture.endX) + 1, maxOf(gesture.y, gesture.endY) + 1)
        return above.none { it.bounds.left < path.right && path.left < it.bounds.right &&
            it.bounds.top < path.bottom && path.top < it.bounds.bottom }
    }
}
internal data class GuiVisualOccluder(val identity: String, val bounds: GuiBounds)

/** Parses only structural WM fields. Titles, intent data and node text are never retained. */
internal object GuiVisualWindowParser {
    private val header = Regex("(?m)^  Window #\\d+ Window\\{([^\\s]+) u(\\d+) [^\\n]*\\}:")
    private val focus = Regex("(?m)^\\s*mCurrentFocus=Window\\{([^\\s]+) u(\\d+) [^\\n]*\\}")
    private val rectangle = "\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]"
    private fun rect(match: MatchResult): GuiBounds = GuiBounds(match.groupValues[1].toInt(),
        match.groupValues[2].toInt(), match.groupValues[3].toInt(), match.groupValues[4].toInt())
    fun parse(text: String): GuiVisualWindow? { return try {
        if (text.length !in 1..1_048_576 || "DUMP TIMEOUT" in text || "Permission Denial" in text) return null
        val focused = focus.findAll(text).toList().singleOrNull() ?: return null
        if (focused.groupValues[2] != "0") return null
        val headers = header.findAll(text).toList()
        if (headers.size !in 1..256 || headers.map { it.groupValues[1] }.distinct().size != headers.size) return null
        val selected = headers.indexOfFirst { it.groupValues[1] == focused.groupValues[1] && it.groupValues[2] == "0" }
        if (selected < 0) return null
        val above = mutableListOf<GuiVisualOccluder>()
        var result: GuiVisualWindow? = null
        for ((index, h) in headers.withIndex()) {
            val block = text.substring(h.range.last + 1, headers.getOrNull(index + 1)?.range?.first ?: text.length)
            val display = Regex("mDisplayId=(\\d+)").find(block)?.groupValues?.get(1)?.toInt() ?: return null
            if (display != 0) { if (index == selected) return null; continue }
            val visible = Regex("(?m)^\\s*isVisible=(true|false)\\s*$").find(block)?.groupValues?.get(1) ?: return null
            if (visible == "false") { if (index == selected) return null; continue }
            if (!Regex("mHasSurface=true\\b").containsMatchIn(block) ||
                !Regex("(?m)^\\s*isOnScreen=true\\s*$").containsMatchIn(block)) return null
            val attrsStart = block.indexOf("mAttrs=")
            val attrsEnd = block.indexOf("Requested w=", attrsStart.coerceAtLeast(0))
            if (attrsStart < 0 || attrsEnd < attrsStart) return null
            val attrs = block.substring(attrsStart, attrsEnd)
            // The platform capture also rejects secure content; never downgrade that failure.
            if (Regex("\\bSECURE\\b").containsMatchIn(attrs)) return null
            val flags = Regex("(?m)^      fl=([^\\n]+)").find(attrs)?.groupValues?.get(1) ?: return null
            if (flags.startsWith("0x") || flags.all { it.isDigit() }) return null
            val frame = rect(Regex("\\bframe=$rectangle").find(block) ?: return null)
            if (index < selected && frame.left < frame.right && frame.top < frame.bottom)
                above += GuiVisualOccluder(h.groupValues[1], frame)
            if (index != selected) continue
            val type = Regex("\\bty=([^\\s}]+)").find(attrs)?.groupValues?.get(1) ?: return null
            if (type !in setOf("BASE_APPLICATION", "APPLICATION", "1", "2")) return null
            val pkg = Regex("mOwnerUid=(\\d+) [^\\n]*?\\bpackage=([A-Za-z0-9_.]+)").find(block) ?: return null
            val uid = pkg.groupValues[1].toInt()
            if (uid !in 10_000..99_999 || !GuiUserActionRequest.validTargetPackage(pkg.groupValues[2])) return null
            val displayBounds = rect(Regex("\\bdisplay=$rectangle").find(block) ?: return null)
            val rotation = Regex("(?m)^\\s*mFullConfiguration=.*?mDisplayRotation=ROTATION_(0|90|180|270)\\b")
                .find(block)?.groupValues?.get(1)?.toInt() ?: return null
            if (frame.left >= frame.right || frame.top >= frame.bottom || displayBounds.left != 0 || displayBounds.top != 0 ||
                displayBounds.right !in 1..2048 || displayBounds.bottom !in 1..4096) return null
            result = GuiVisualWindow(h.groupValues[1], pkg.groupValues[2], uid, frame, displayBounds, rotation, above.toList())
        }
        result
    } catch (_: Exception) { null }
    }
}

/** Fixed, read-only host command. Requires the user's already-selected Root mode. */
internal object AndroidGuiVisualWindows {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "daiyu-visual-window").apply { isDaemon = true } }
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    fun read(service: GuiAccessibilityService, completed: (GuiVisualWindow?) -> Unit) {
        if (!busy.compareAndSet(false, true)) { completed(null); return }
        val submitted = System.nanoTime()
        worker.execute {
            val result = try { readSafely { readBlocking(service) } } finally { busy.set(false) }
            GuiRuntime.main.post { completed(if (System.nanoTime() - submitted <= TimeUnit.SECONDS.toNanos(5)) result else null) }
        }
    }
    internal fun readSafely(read: () -> GuiVisualWindow?): GuiVisualWindow? = try { read() }
        catch (_: Exception) { null }
        catch (_: LinkageError) { null } // Includes parser initialization and a previously failed initializer.

    private fun readBlocking(service: GuiAccessibilityService): GuiVisualWindow? {
        val mode = AndroidRunningModeStore.get(service)
        if (mode.mode.value != RunningMode.ROOT) return null
        val su = AndroidRunningModeAuthorization.rootExecutable() ?: return null
        val process = ProcessBuilder(su, "-c", "[ \"\$(/system/bin/id -u)\" = 0 ] || exit 77; exec /system/bin/timeout 3 /system/bin/dumpsys window -a")
            .redirectErrorStream(true).start()
        process.outputStream.close()
        val output = ByteArrayOutputStream()
        var valid = true
        val reader = Thread {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = process.inputStream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count <= 1_048_576) output.write(buffer, 0, count) else valid = false
                }
            } catch (_: Exception) { valid = false }
        }.apply { isDaemon = true; start() }
        return try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) return null
            reader.join(500)
            if (reader.isAlive || !valid || process.exitValue() != 0 || mode.mode.value != RunningMode.ROOT) null
            else GuiVisualWindowParser.parse(output.toString(Charsets.UTF_8.name()))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close()
            reader.join(500)
        }
    }
}
