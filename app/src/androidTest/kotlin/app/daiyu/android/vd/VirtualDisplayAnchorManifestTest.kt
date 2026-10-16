package app.daiyu.android.vd

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.test.InstrumentationTestCase

/** Inspects the installed component only; never creates a display or launches the root owner. */
@Suppress("DEPRECATION")
class VirtualDisplayAnchorManifestTest : InstrumentationTestCase() {
    fun testAnchorUsesIndependentStandardOpaqueTask() {
        val context = instrumentation.targetContext
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, VirtualDisplayAnchorActivity::class.java), 0)
        assertTrue(info.exported)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertEquals(0, info.flags and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING)
        assertEquals(0, info.flags and ActivityInfo.FLAG_NO_HISTORY)
        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, info.launchMode)
        assertEquals(VirtualDisplayAnchorActivity.TASK_AFFINITY, info.taskAffinity)
        assertTrue(info.taskAffinity != context.packageName)
        assertEquals(android.R.style.Theme_Material_NoActionBar, info.theme)
    }
}
