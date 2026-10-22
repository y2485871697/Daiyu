package app.daiyu.android

import android.view.ViewGroup
import app.daiyu.android.browser.openminis.BrowserVisibleAction
import app.daiyu.android.browser.openminis.BrowserVisibleConfiguration
import app.daiyu.android.browser.openminis.BrowserVisibleDownload
import app.daiyu.android.browser.session.BrowserTabLease
import app.daiyu.android.domain.ConversationId

/** The same UI commands target either the original task tab or an explicit human-only session. */
internal interface BrowserVisualVisit : AutoCloseable {
    val conversationId: ConversationId?
    val memory: app.daiyu.android.browser.ui.BrowserUiMemory
    val taskOwned: Boolean
    fun snapshot(): ControllerBrowserVisual.State
    fun takeover(lease: BrowserTabLease)
    fun mount(parent: ViewGroup)
    fun unmount(parent: ViewGroup)
    fun navigate(url: String)
    fun newTab()
    fun selectTab(lease: BrowserTabLease)
    fun closeTab()
    fun control(action: BrowserVisibleAction)
    fun configure(configuration: BrowserVisibleConfiguration)
    fun downloads(listener: BrowserVisibleDownload?)
    fun restore()
    fun stop()
}
