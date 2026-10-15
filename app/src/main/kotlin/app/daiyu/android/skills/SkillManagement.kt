package app.daiyu.android.skills

import android.os.Looper

/** Trusted settings surface only. This API is deliberately absent from the model tool catalog. */
class SkillManagement private constructor(private val registry: SkillToolRegistry) {
    companion object {
        fun create(registryFactory: () -> SkillToolRegistry): SkillManagement {
            check(Looper.myLooper() != Looper.getMainLooper()) { "background_thread_required" }
            return SkillManagement(registryFactory())
        }
    }
    data class Entry(val id: String, val skill: InstalledSkill, val builtin: Boolean)
    data class State(val enabled: Boolean, val entries: List<Entry>, val entriesUnavailable: Boolean = false)

    fun snapshot(): State {
        workerOnly()
        return synchronized(this) { registry.managementState() }
    }

    fun setGlobalEnabled(enabled: Boolean): State {
        workerOnly()
        return synchronized(this) {
            registry.enabled = enabled
            registry.managementState()
        }
    }

    fun setEnabled(id: String, enabled: Boolean, revision: String): State {
        workerOnly()
        return synchronized(this) {
            registry.setEnabled(id, enabled, revision)
            registry.managementState()
        }
    }

    private fun workerOnly() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "background_thread_required" }
    }
}
