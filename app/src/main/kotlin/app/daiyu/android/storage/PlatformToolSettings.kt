package app.daiyu.android.storage

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.platform.DeviceResultPolicy
import app.daiyu.android.platform.PlatformReadTool
import java.util.Collections

/** Independent of provider credentials and the legacy battery/status switches. No migration grants. */
internal class PlatformToolSettings(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("platform-read-settings", Context.MODE_PRIVATE))

    @Synchronized fun freeze(): FrozenPlatformToolSettings = FrozenPlatformToolSettings(
        read(SELECTION), read(MODEL), read(HISTORY))

    /** One explicit choice per action: selecting a capability never implies either outbound consent. */
    @Synchronized fun select(tool: PlatformReadTool, enabled: Boolean) = change(SELECTION, tool, enabled)
    @Synchronized fun consentModel(tool: PlatformReadTool, enabled: Boolean) = change(MODEL, tool, enabled)
    @Synchronized fun consentHistory(tool: PlatformReadTool, enabled: Boolean) = change(HISTORY, tool, enabled)

    private fun read(key: String): Set<PlatformReadTool> {
        val names = try { preferences.getStringSet(key, emptySet())?.toSet().orEmpty() }
            catch (_: ClassCastException) { emptySet<String>() }
        return PlatformReadTool.entries.filterTo(linkedSetOf()) { it.toolName in names &&
            (key == SELECTION || it.toolName in DeviceResultPolicy.PRIVATE_TOOLS) }
    }

    private fun change(key: String, tool: PlatformReadTool, enabled: Boolean) {
        require(key == SELECTION || tool.toolName in DeviceResultPolicy.PRIVATE_TOOLS)
        val next = read(key).toMutableSet().apply { if (enabled) add(tool) else remove(tool) }
        check(preferences.edit().putStringSet(key, next.mapTo(linkedSetOf()) { it.toolName }).commit()) {
            "platform_settings_not_saved"
        }
    }

    private companion object {
        const val SELECTION = "selected-v1"
        const val MODEL = "private-model-v1"
        const val HISTORY = "private-history-v1"
    }
}

/** Captured once before preparation I/O; subsequent settings edits affect only the next run. */
internal class FrozenPlatformToolSettings(selected: Set<PlatformReadTool>, model: Set<PlatformReadTool>,
    history: Set<PlatformReadTool>) {
    val platformAllowed: Set<PlatformReadTool> = Collections.unmodifiableSet(selected.toSet())
    private val modelAllowed = model.toSet()
    private val historyAllowed = history.toSet()
    val resultPolicy = DeviceResultPolicy(modelAllowed.mapTo(linkedSetOf()) { it.toolName },
        historyAllowed.mapTo(linkedSetOf()) { it.toolName })
    fun modelConsent(tool: PlatformReadTool) = tool in modelAllowed
    fun historyConsent(tool: PlatformReadTool) = tool in historyAllowed
}
