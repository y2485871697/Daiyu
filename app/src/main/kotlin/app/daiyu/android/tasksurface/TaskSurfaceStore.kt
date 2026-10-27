package app.daiyu.android.tasksurface

/** null means a genuinely absent record, never a failed read. */
internal interface TaskSurfacePersistence {
    fun read(): String?
    fun write(record: String)
}

/** No cache of a permissive default. Corruption/I/O failure returns Unavailable, not FOREGROUND.
 * The settings page may report saved only after durable write AND exact readback succeed. */
internal class TaskSurfaceStore(private val persistence: TaskSurfacePersistence) {
    private var writeUnconfirmed = false
    @Synchronized fun load(): TaskSurfaceSettings = if (writeUnconfirmed) TaskSurfaceSettings.Unavailable else try {
        val raw = persistence.read()
        if (raw == null) TaskSurfaceSettings.Available(TaskSurfacePreference.FOREGROUND)
        else parse(raw)?.let { TaskSurfaceSettings.Available(it) } ?: TaskSurfaceSettings.Unavailable
    } catch (_: Exception) { TaskSurfaceSettings.Unavailable }

    @Synchronized fun save(value: TaskSurfacePreference): Boolean {
        writeUnconfirmed = true
        return try {
            val record = "task-surface-v1\n${value.name}\n"
            persistence.write(record)
            (persistence.read() == record).also { confirmed -> writeUnconfirmed = !confirmed }
        } catch (_: Exception) { false }
    }

    fun freeze(owner: Any, ownPackage: String) = TaskSurfaceRunDecision(owner, load(), ownPackage)

    private fun parse(record: String): TaskSurfacePreference? = TaskSurfacePreference.entries.singleOrNull {
        record == "task-surface-v1\n${it.name}\n"
    }
}

/** Presentation state also tested without Compose: failure never changes the saved selection. */
internal class TaskSurfaceSettingsEditor(private val store: TaskSurfaceStore) {
    var saved: TaskSurfaceSettings = store.load()
        private set
    var draft: TaskSurfacePreference? = (saved as? TaskSurfaceSettings.Available)?.preference
        private set
    var saveFailed = false
        private set
    fun choose(value: TaskSurfacePreference) { draft = value; saveFailed = false }
    fun save(): Boolean {
        val value = draft ?: return false
        if (!store.save(value)) { saveFailed = true; return false }
        saved = TaskSurfaceSettings.Available(value)
        saveFailed = false
        return true
    }
}
