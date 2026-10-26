package app.daiyu.android.gui.keepalive

import android.net.Uri
import android.os.Bundle
import app.daiyu.android.BuildConfig

internal object AccessibilityProtectionProtocol {
    const val VERSION = 1
    const val APP_PACKAGE = BuildConfig.APPLICATION_ID
    const val SERVICE_CLASS = "app.daiyu.android.gui.GuiAccessibilityService"
    const val SETTING_NAME = "${APP_PACKAGE}.accessibility_keepalive_authorized_uid"
    const val HEALTH_METHOD = "accessibility_health"
    const val EXTRA_PROTOCOL_VERSION = "protocol_version"
    const val HEALTH_STATUS = "status"
    const val HEALTH_STATUS_CONNECTED = "connected"
    const val HEALTH_STATUS_DISCONNECTED = "disconnected"
    const val HEALTH_STATUS_REJECTED = "rejected"
    val HEALTH_URI: Uri = Uri.parse("content://$APP_PACKAGE.accessibility.health")
    fun request(): Bundle = Bundle().apply { putInt(EXTRA_PROTOCOL_VERSION, VERSION) }
    fun healthResult(status: String): Bundle = request().apply { putString(HEALTH_STATUS, status) }
}
