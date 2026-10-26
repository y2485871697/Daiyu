package app.daiyu.android.ui.screens.settings

/** Registration transfers credential ownership once, even if the page pauses or later work fails. */
internal class ProviderRegistrationOwnership(private val discard: () -> Unit) {
    @Volatile var registeredSlot: String? = null
        private set
    private var saving = false
    private var revoked = false
    @Synchronized fun beginSave() {
        check(!revoked && !saving && registeredSlot == null) { "创建草稿已提交或关闭" }
        saving = true
    }
    @Synchronized fun registered(slot: String) {
        check(saving && registeredSlot == null && slot.isNotBlank())
        registeredSlot = slot
    }
    @Synchronized fun finishSave() {
        saving = false
        if (revoked && registeredSlot == null) discard()
    }
    @Synchronized fun revoke() {
        if (revoked) return
        revoked = true
        if (!saving && registeredSlot == null) discard()
    }
}
