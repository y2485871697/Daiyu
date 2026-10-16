package app.daiyu.android.domain.appearance

/** Mirrors PM's explicit state, including DEFAULT. Restore it, not just a boolean. */
enum class IconComponentState { DEFAULT, ENABLED, DISABLED, DISABLED_USER, DISABLED_UNTIL_USED }
data class IconComponentSnapshot(val state: IconComponentState, val enabled: Boolean)

interface LauncherComponentPort {
    /** Must reject absent components, non-alias components and aliases without MAIN/LAUNCHER. */
    fun validate(componentName: String)
    fun snapshot(componentName: String): IconComponentSnapshot
    /** Must use DONT_KILL_APP. */
    fun setState(componentName: String, state: IconComponentState)
}

/** Refresh active notifications, not just future builders. No service restart is implied. */
fun interface IconNotificationPort {
    /**
     * Refresh inline or throw; implementation owns IDs/channels and may require a calling thread.
     * Android's MainThreadIconNotifications requires main-thread calls and rejects workers;
     * this port does not promise to marshal or synchronously wait for another thread.
     */
    fun refresh(entry: Task17IconCatalog.Entry)
}

/** Receipts distinguish launcher success from notification/persistence failure. */
data class IconSyncResult(
    val launcherApplied: Boolean,
    val selected: Task17IconCatalog.Entry,
    val failures: List<IconSyncFailure> = emptyList(),
    val retainedComponents: List<String> = emptyList(),
) {
    val complete: Boolean get() = launcherApplied && failures.isEmpty()
}
data class IconSyncFailure(val stage: String, val component: String?, val detail: String)

class LauncherIconSwitcher(
    private val packageName: String,
    private val components: LauncherComponentPort,
    private val notifications: IconNotificationPort,
    /** Persist current resource only after launcher success, before refreshing notifications. */
    private val recordSelection: (Task17IconCatalog.Entry) -> Unit,
) {
    /** Only an explicit user choice may re-enable a DISABLED_USER target. */
    @Synchronized
    fun apply(selected: Task17IconCatalog.Entry, userInitiated: Boolean = false): IconSyncResult {
        require(selected in Task17IconCatalog.entries)
        val names = Task17IconCatalog.entries.map { it.componentName(packageName) }
        val target = selected.componentName(packageName)
        val before = linkedMapOf<String, IconComponentSnapshot>()
        val failures = mutableListOf<IconSyncFailure>()
        fun failure(stage: String, component: String?, error: Exception) {
            failures += IconSyncFailure(stage, component, error.message ?: error.javaClass.simpleName)
        }
        try {
            // Fail closed before mutation: 64 real aliases must exist, not just predictable names.
            names.forEach { components.validate(it); before[it] = components.snapshot(it) }
        } catch (error: Exception) {
            failure("validate", null, error)
            return IconSyncResult(false, selected, failures, before.filterValues { it.enabled }.keys.toList())
        }
        if (!userInitiated && before.getValue(target).state == IconComponentState.DISABLED_USER) {
            failures += IconSyncFailure("user-disabled", target, "Only an explicit user choice may re-enable this launcher")
            return IconSyncResult(false, selected, failures, before.filterValues { it.enabled }.keys.toList())
        }
        var changing: String? = target
        try {
            // Even DEFAULT may be enabled; setting explicitly first avoids relying on defaults.
            components.setState(target, IconComponentState.ENABLED)
            check(components.snapshot(target).enabled) { "New launcher did not become enabled" }
            names.filter { it != target }.forEach { name ->
                changing = name
                if (components.snapshot(name).enabled) components.setState(name, IconComponentState.DISABLED)
            }
            check(components.snapshot(target).enabled) { "New launcher lost its enabled state" }
            check(names.filter { it != target }.none { components.snapshot(it).enabled }) { "Old launcher is still enabled" }
        } catch (error: Exception) {
            failure("switch", changing, error)
            // Restore all formerly enabled entrances FIRST. If recovery fails, keep new entrance.
            before.filterValues { it.enabled }.forEach { (name, _) ->
                try { components.setState(name, IconComponentState.ENABLED) }
                catch (recovery: Exception) { failure("restore-enable", name, recovery) }
            }
            val restored = before.filterValues { it.enabled }.keys.any { name ->
                try { components.snapshot(name).enabled } catch (_: Exception) { false }
            }
            before.forEach { (name, snapshot) ->
                // Never remove the newly enabled entrance unless a former one is verified alive.
                if (name == target && !snapshot.enabled && !restored) return@forEach
                try { components.setState(name, snapshot.state) }
                catch (recovery: Exception) { failure("restore-state", name, recovery) }
            }
            var retained = names.filter { name ->
                try { components.snapshot(name).enabled } catch (_: Exception) { false }
            }
            if (retained.isEmpty()) {
                try { components.setState(target, IconComponentState.ENABLED) }
                catch (recovery: Exception) { failure("emergency-enable", target, recovery) }
                retained = names.filter { name ->
                    try { components.snapshot(name).enabled } catch (_: Exception) { false }
                }
            }
            if (retained.isEmpty()) failures += IconSyncFailure("no-verified-entry", null, "PackageManager rejected recovery; host must surface this failure")
            return IconSyncResult(false, selected, failures, retained)
        }
        try { recordSelection(selected) } catch (error: Exception) { failure("persist-current", target, error) }
        // Also refresh when already selected: process restart / theme change can stale notifications.
        try { notifications.refresh(selected) } catch (error: Exception) { failure("notification", target, error) }
        return IconSyncResult(true, selected, failures, listOf(target))
    }
}
