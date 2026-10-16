package app.daiyu.android.vd.runtime;

import org.junit.Test;

import static org.junit.Assert.*;

/** Tests the same transport fence used by Connection, without opening a local socket. */
public class OwnerIpcServerFenceTest {
    private static void assertCleanupOnly(OwnerIpcServer.MutationFence fence) {
        assertTrue(fence.isUncertain());
        for (String op : new String[]{"status", "snapshot", "receipt", "handoff", "release"}) {
            assertTrue(op, fence.allows(op));
        }
        for (String op : new String[]{"initialize", "shutdown_uninitialized", "launch", "input", "unknown"}) {
            assertFalse(op, fence.allows(op));
        }
        assertFalse(fence.allows(null));
    }

    @Test public void failedMutationDeliveryAllowsCleanupButNeverOriginalMutation() {
        for (String op : new String[]{"initialize", "shutdown_uninitialized", "launch", "input", "handoff", "release"}) {
            OwnerIpcServer.MutationFence fence = new OwnerIpcServer.MutationFence();
            assertTrue(fence.allows(op));
            fence.responseLost(op);
            assertCleanupOnly(fence);
        }
    }

    @Test public void abandonedStartedMutationHasSameFenceAsLostReply() {
        for (String op : new String[]{"initialize", "shutdown_uninitialized", "launch", "input", "handoff", "release"}) {
            OwnerIpcServer.MutationFence fence = new OwnerIpcServer.MutationFence();
            fence.waitAbandoned(op, false);
            assertFalse(fence.isUncertain());
            fence.waitAbandoned(op, true);
            assertCleanupOnly(fence);
        }
    }

    @Test public void observationLossDoesNotFenceAndObservationCannotClearExistingFence() {
        OwnerIpcServer.MutationFence fence = new OwnerIpcServer.MutationFence();
        for (String op : new String[]{"status", "snapshot", "receipt"}) {
            fence.responseLost(op);
            fence.waitAbandoned(op, true);
            assertFalse(fence.isUncertain());
        }
        fence.responseLost("input");
        for (String op : new String[]{"status", "snapshot", "receipt"}) {
            fence.responseLost(op);
            fence.waitAbandoned(op, false);
            assertCleanupOnly(fence);
        }
    }
}
