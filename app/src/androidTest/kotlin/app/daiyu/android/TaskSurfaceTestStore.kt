package app.daiyu.android

import app.daiyu.android.tasksurface.*

/** Memory-only production store seam. No Android singleton/preferences or user data is touched. */
internal fun isolatedTaskSurfaceStore(preference: TaskSurfacePreference): TaskSurfaceStore {
    val store = TaskSurfaceStore(object : TaskSurfacePersistence {
        private var record: String? = null
        @Synchronized override fun read(): String? = record
        @Synchronized override fun write(record: String) { this.record = record }
    })
    check(store.save(preference))
    return store
}
