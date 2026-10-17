package app.daiyu.android.vd.runtime;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.test.InstrumentationTestCase;
import app.daiyu.android.vd.VirtualDisplayAnchorActivity;

/** Verify the component actually consumed by the owner against the installed variant. */
@SuppressWarnings("deprecation")
public final class OwnerAnchorIdentityTest extends InstrumentationTestCase {
    public void testOwnerAnchorResolvesOnlyInsideInstalledHost() throws Exception {
        android.content.Context context = getInstrumentation().getTargetContext();
        ComponentName expected = new ComponentName(context, VirtualDisplayAnchorActivity.class);
        assertEquals(expected.flattenToString(), OwnerHandoff.ANCHOR_COMPONENT);
        ComponentName actual = ComponentName.unflattenFromString(OwnerHandoff.ANCHOR_COMPONENT);
        assertNotNull(actual);
        ActivityInfo info = context.getPackageManager().getActivityInfo(actual, 0);
        assertEquals(context.getPackageName(), info.packageName);
        assertEquals(context.getApplicationInfo().uid, info.applicationInfo.uid);
    }
}
