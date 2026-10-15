package com.openminis.app.browser

import android.content.Context
import app.daiyu.android.browser.openminis.OpenMinisNavigationPolicy

/**
 * Manager-only deny implementation. Select this file instead of upstream GoogleAuthRouter.kt;
 * there is deliberately no Custom Tab, Intent, fallback, AppLogger, or UI interaction.
 */
object GoogleAuthRouter {
    fun shouldRouteExternally(url: String?): Boolean = OpenMinisNavigationPolicy.isGoogleAuth(url)

    @Suppress("UNUSED_PARAMETER")
    fun openInCustomTab(context: Context, url: String): Nothing =
        error("BROWSER_OPENMINIS_GOOGLE_AUTH_UNSUPPORTED")
}
