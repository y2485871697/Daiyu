package app.daiyu.android.gui.keepalive

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import app.daiyu.android.gui.GuiRuntime

/** System-only connection bit. Never reads a window, node, screenshot or task. */
class AccessibilityHealthProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val status = if (Binder.getCallingUid() != Process.SYSTEM_UID ||
            method != AccessibilityProtectionProtocol.HEALTH_METHOD ||
            extras?.getInt(AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION, -1) != AccessibilityProtectionProtocol.VERSION
        ) AccessibilityProtectionProtocol.HEALTH_STATUS_REJECTED
        else if (GuiRuntime.hasServiceConnection()) AccessibilityProtectionProtocol.HEALTH_STATUS_CONNECTED
        else AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED
        return AccessibilityProtectionProtocol.healthResult(status)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
