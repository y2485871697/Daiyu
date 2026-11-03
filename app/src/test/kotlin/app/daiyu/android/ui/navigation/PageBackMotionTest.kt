package app.daiyu.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class PageBackMotionTest {
    @Test fun preservesStationaryRelease() = assertEquals(0f, pageBackInitialVelocity(.6f, 0f), .000001f)
    @Test fun preservesMeasuredForwardVelocity() = assertEquals(2f, pageBackInitialVelocity(.6f, 2f), .000001f)
    @Test fun preservesReverseVelocity() = assertEquals(-.8f, pageBackInitialVelocity(.6f, -.8f), .000001f)
    @Test fun capsFastFlingAtCriticalDampingBoundary() = assertEquals(3.0207615f, pageBackInitialVelocity(.75f, 30f), .000001f)
    @Test fun remainingDistanceDefinesVelocityCap() = assertEquals(.12083046f, pageBackInitialVelocity(.99f, 2f), .000001f)
    @Test fun finishedTransitionCannotOvershoot() = assertEquals(0f, pageBackInitialVelocity(1f, 2f), .000001f)
}
