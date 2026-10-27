package app.daiyu.android.integration.tools

/** App-local consent only. Never grants Android/Root/Shizuku authority or selects a backend.
 * No saved preference is required, so existing installations get the same default immediately.
 */
internal object CommandPermissionDefaults {
    const val REQUIRE_INTERACTIVE_APPROVAL = false
}
