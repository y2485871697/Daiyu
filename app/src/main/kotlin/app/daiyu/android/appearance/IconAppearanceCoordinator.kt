package app.daiyu.android.appearance

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import app.daiyu.android.domain.appearance.IconAppearance
import app.daiyu.android.domain.appearance.IconNotificationPort
import app.daiyu.android.domain.appearance.IconSyncFailure
import app.daiyu.android.domain.appearance.IconSyncResult
import app.daiyu.android.domain.appearance.LauncherComponentPort
import app.daiyu.android.domain.appearance.LauncherIconSwitcher

/**
 * Main-thread-only mutation boundary; workers fail before storage, PM or notification access.
 * No service/activity/global application wiring is installed here.
 */
class IconAppearanceCoordinator internal constructor(
    context: Context,
    notifications: IconNotificationPort,
    components: LauncherComponentPort,
) {
    constructor(context: Context, notifications: IconNotificationPort) :
        this(context, notifications, PackageManagerIconAdapter(context.applicationContext))

    private val context = context.applicationContext
    val store = IconAppearanceStore(this.context)
    private val switcher = LauncherIconSwitcher(this.context.packageName,
        components, notifications, store::recordCurrent)

    /** Explicit user choice; desired preference survives a PM failure, receipt is authoritative. */
    fun saveAndApply(settings: IconAppearance): IconSyncResult {
        checkMainThread()
        val normalized = settings.normalized()
        try { store.save(normalized) } catch (error: Exception) {
            return IconSyncResult(false, normalized.selectedIcon(systemNight()), listOf(
                IconSyncFailure("persist-preferences", null, error.message ?: error.javaClass.simpleName)))
        }
        return switcher.apply(normalized.selectedIcon(systemNight()), userInitiated = true)
    }

    /** Startup/uiMode sync on main; never re-enable a user-disabled target automatically. */
    fun applyCurrent(): IconSyncResult {
        checkMainThread()
        return switcher.apply(store.load().selectedIcon(systemNight()), userInitiated = false)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Icon appearance changes must run on the main thread" }
    }

    private fun systemNight(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
