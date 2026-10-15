package com.openminis.app.ui.browser

import android.content.Context
import android.net.Uri
import app.daiyu.android.browser.openminis.OpenMinisNavigationPolicy

/** Manager-only compatibility facade. true means BLOCKED/consumed, never external launch. */
object BrowserExternalSchemeHandler {
    enum class Origin { AGENT_BACKGROUND }

    @Suppress("UNUSED_PARAMETER")
    fun handle(context: Context, uri: Uri?, origin: Origin): Boolean =
        !OpenMinisNavigationPolicy.permits(uri?.toString())
}
