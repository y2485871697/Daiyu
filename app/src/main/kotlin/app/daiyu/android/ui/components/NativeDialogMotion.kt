package app.daiyu.android.ui.components

import android.app.Dialog
import android.view.Window

internal fun Window.usePlatformDialogFade() {
    val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.windowAnimationStyle))
    val animation = try { attributes.getResourceId(0, 0) } finally { attributes.recycle() }
    setWindowAnimations(animation)
}

internal fun Dialog.prepareDaiyuCenterMotion() {
    window?.usePlatformDialogFade()
}
