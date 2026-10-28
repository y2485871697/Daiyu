package app.daiyu.android.ui.components

import java.util.concurrent.atomic.AtomicBoolean

/** A foreground action grant; revocation never destroys the dialog's in-memory presentation. */
internal class DialogActionLease {
    private val active = AtomicBoolean(true)
    val isActive: Boolean get() = active.get()
    fun revoke() { active.set(false) }
}
