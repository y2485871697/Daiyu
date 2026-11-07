package app.daiyu.android.platform

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import app.daiyu.android.diagnostics.RuntimeDiagnostics

/** Resource labels only; package visibility, enabled state and inventory are always freshly queried. */
internal class AppLabelCache(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private data class Key(val packageName: String, val source: String?, val resource: Int,
        val declared: String?, val configuration: String)
    private data class Entry(val value: String, val at: Long)
    private val entries = LinkedHashMap<Key, Entry>()

    @Synchronized fun label(info: ApplicationInfo, configuration: String, load: () -> String): String {
        val key = Key(info.packageName, info.sourceDir, info.labelRes, info.nonLocalizedLabel?.toString(), configuration)
        val now = clock()
        val hit = entries[key]
        if (hit != null && now - hit.at in 0 until 60_000L) {
            RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.APPS_LABELS, RuntimeDiagnostics.Code.CACHE_HIT)
            return hit.value
        }
        val label = load()
        if (label.length > 4096) return label
        entries.remove(key)
        while (entries.size >= 512) entries.remove(entries.keys.first())
        entries[key] = Entry(label, now)
        RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.APPS_LABELS, RuntimeDiagnostics.Code.CACHE_MISS)
        return label
    }
}
