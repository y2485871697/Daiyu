package app.daiyu.android.ui.screens.settings

import android.content.SharedPreferences
import app.daiyu.android.terminal.LinuxExecutionMode

internal data class LinuxInstallChoice(val distribution: String, val executionMode: LinuxExecutionMode)

/** Install form preference only; never grants access to or selects a runtime generation. */
internal class LinuxInstallChoicePreferences(private val preferences: SharedPreferences) {
    fun read(): LinuxInstallChoice? = runCatching {
        val parts = preferences.getString(KEY, null)?.split(':') ?: return null
        if (parts.size != 2 || parts[0] !in DISTRIBUTIONS) return null
        LinuxInstallChoice(parts[0], LinuxExecutionMode.valueOf(parts[1]))
    }.getOrNull()

    fun save(choice: LinuxInstallChoice): Boolean {
        if (choice.distribution !in DISTRIBUTIONS) return false
        val previous = runCatching { preferences.getString(KEY, null) }.getOrElse { return false }
        val saved = runCatching {
            preferences.edit().putString(KEY, "${choice.distribution}:${choice.executionMode.name}").commit()
        }.getOrDefault(false)
        // A failed commit can still replace SharedPreferences' in-process value.
        if (!saved) runCatching { preferences.edit().putString(KEY, previous).commit() }
        return saved
    }

    private companion object {
        const val KEY = "install_choice"
        val DISTRIBUTIONS = setOf("alpine", "debian")
    }
}
