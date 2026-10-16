package app.daiyu.android.vd.runtime;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class EndedOwnedRecentTest {
    private static final String TARGET = "com.example.target";
    private static final String COMPONENT = TARGET + "/com.example.target.MainActivity";
    private static final String MARKER = "daiyu-vd://session/11111111-1111-4111-8111-111111111111";
    private static final String OTHER_MARKER = "daiyu-vd://session/22222222-2222-4222-8222-222222222222";

    private static Set<Integer> ids(Integer... values) {
        return new LinkedHashSet<Integer>(Arrays.asList(values));
    }
    private static final class Evidence {
        Integer task = 38, persistent = 38, display = -1, user = 0, parent = -1, count = 0;
        String component = COMPONENT, marker = MARKER;
        boolean known = true, absent = true, packages = true;
        EndedOwnedRecent.Recent recent() {
            return new EndedOwnedRecent.Recent(task, persistent, display, user, parent, count,
                    component, marker, known, absent, packages);
        }
    }
    private static boolean proves(Evidence e) {
        return EndedOwnedRecent.proves(TARGET, 7, 38, COMPONENT, MARKER, ids(38), ids(),
                ids(1, 2), e.recent());
    }
    private static LaunchTargetOccupancy.Root root(int id, boolean known, int... children) {
        String[] names = children == null ? null : new String[children.length];
        if (names != null) Arrays.fill(names, "home.app");
        return new LaunchTargetOccupancy.Root(id, "home.app", null, null, null, null, true, 1,
                known, children, true, names, false, false);
    }

    @Test public void exactEndedOwnedTask38IsProven() {
        assertTrue(proves(new Evidence()));
    }

    @Test public void inactiveTaskIdUsesOnlyExactPersistentIdentity() {
        Evidence e = new Evidence();
        e.task = -1;
        assertTrue(proves(e));
        assertEquals(38, e.recent().identityId());
        e.persistent = 39;
        assertFalse(proves(e));
    }

    @Test public void conflictingMissingAndInvalidIdsNeverFallBack() {
        Integer[][] invalid = { {38, 39}, {39, 38}, {null, 38}, {38, null}, {-2, 38},
                {0, 38}, {38, -1}, {38, 0}, {-1, null}, {-1, -1} };
        for (Integer[] pair : invalid) {
            Evidence e = new Evidence();
            e.task = pair[0]; e.persistent = pair[1];
            assertFalse(Arrays.toString(pair), proves(e));
            assertEquals(-1, e.recent().identityId());
        }
    }

    @Test public void packageOrSessionPrefixAloneNeverProvesOwnership() {
        Evidence e = new Evidence();
        e.marker = OTHER_MARKER; assertFalse(proves(e));
        e.marker = null; assertFalse(proves(e));
        e.marker = "daiyu-vd://session/"; assertFalse(proves(e));
        e.marker = MARKER; e.component = TARGET + "/OtherActivity"; assertFalse(proves(e));
        e.component = "foreign.app/Main"; assertFalse(proves(e));
        e.component = null; assertFalse(proves(e));
    }

    @Test public void unregisteredAndMalformedOwnedIdentitiesRefuse() {
        Evidence e = new Evidence();
        assertFalse(EndedOwnedRecent.proves(TARGET, 7, 39, COMPONENT, MARKER, ids(39), ids(),
                ids(1), e.recent()));
        assertFalse(EndedOwnedRecent.proves(TARGET, 7, 38, COMPONENT, MARKER, ids(), ids(),
                ids(1), e.recent()));
        e.marker = "daiyu-vd://session/not-a-session-marker";
        assertFalse(EndedOwnedRecent.proves(TARGET, 7, 38, COMPONENT, e.marker, ids(38), ids(),
                ids(1), e.recent()));
        assertFalse(EndedOwnedRecent.proves(TARGET, 7, 38, "foreign.app/Main", MARKER, ids(38),
                ids(), ids(1), new Evidence().recent()));
    }

    @Test public void mainForeignAndUnknownDisplayRefuse() {
        for (Integer display : new Integer[]{0, 7, 8, -2, null}) {
            Evidence e = new Evidence(); e.display = display;
            assertFalse(proves(e));
        }
        for (int source : new int[]{-1, 0})
            assertFalse(EndedOwnedRecent.proves(TARGET, source, 38, COMPONENT, MARKER,
                    ids(38), ids(), ids(1), new Evidence().recent()));
    }

    @Test public void activeUnknownOrForeignTaskShapeRefuses() {
        Evidence e = new Evidence();
        e.count = 1; assertFalse(proves(e));
        e.count = null; assertFalse(proves(e));
        e = new Evidence(); e.user = 10; assertFalse(proves(e));
        e.user = null; assertFalse(proves(e));
        e = new Evidence(); e.parent = 1; assertFalse(proves(e));
        e.parent = null; assertFalse(proves(e));
        e = new Evidence(); e.known = false; assertFalse(proves(e));
        e = new Evidence(); e.absent = false; assertFalse(proves(e));
        e = new Evidence(); e.packages = false; assertFalse(proves(e));
    }

    @Test public void anyActiveRootOrChildPresenceDefeatsGoneEvidence() {
        for (Set<Integer> present : Arrays.asList(
                EndedOwnedRecent.presentIds(Arrays.asList(root(1, true, 2), root(38, true))),
                EndedOwnedRecent.presentIds(Arrays.asList(root(1, true, 2, 38))))) {
            assertTrue(present.contains(38));
            assertFalse(EndedOwnedRecent.proves(TARGET, 7, 38, COMPONENT, MARKER, ids(38),
                    ids(), present, new Evidence().recent()));
        }
    }

    @Test public void incompleteOrMalformedEnumerationCannotProveAbsence() {
        assertNull(EndedOwnedRecent.presentIds(null));
        assertNull(EndedOwnedRecent.presentIds(Collections.<LaunchTargetOccupancy.Root>emptyList()));
        assertNull(EndedOwnedRecent.presentIds(Arrays.asList(root(1, false, 2))));
        assertNull(EndedOwnedRecent.presentIds(Arrays.asList(root(1, true, (int[]) null))));
        assertNull(EndedOwnedRecent.presentIds(Arrays.asList(root(1, true, 0))));
        assertNull(EndedOwnedRecent.presentIds(Arrays.asList(root(1, true, -2))));
        assertNull(EndedOwnedRecent.presentIds(Arrays.asList(root(1, true, 2, 2))));
        assertNull(EndedOwnedRecent.presentIds(Arrays.asList(root(1, true), root(1, true))));
        assertFalse(EndedOwnedRecent.proves(TARGET, 7, 38, COMPONENT, MARKER, ids(38),
                ids(), null, new Evidence().recent()));
    }

    @Test public void escapedIdentityBlocksReopenEvenWhenAnotherTaskIsGone() {
        assertFalse(EndedOwnedRecent.proves(TARGET, 7, 38, COMPONENT, MARKER, ids(38),
                ids(99), ids(1), new Evidence().recent()));
        assertFalse(EndedOwnedRecent.proves(TARGET, 7, 38, COMPONENT, MARKER, ids(38),
                null, ids(1), new Evidence().recent()));
    }

    @Test public void nonTaskMarkersDoNotCreateFalseActivePresence() {
        assertEquals(ids(1), EndedOwnedRecent.presentIds(Arrays.asList(root(1, true, 1, -1))));
    }

    @Test public void unprovenRecentAndForeignActiveTaskStillRefuse() {
        LaunchTargetOccupancy.Recent recent = new LaunchTargetOccupancy.Recent(38, TARGET,
                null, null, TARGET, null, true);
        assertEquals(LaunchTargetOccupancy.RECENT, LaunchTargetOccupancy.decide(TARGET,
                Arrays.asList(root(1, true)), Arrays.asList(recent)).code);
        LaunchTargetOccupancy.Root target = new LaunchTargetOccupancy.Root(38, TARGET,
                TARGET, TARGET, TARGET, null, true, 1, true, new int[0], true, new String[0],
                false, false);
        assertEquals(LaunchTargetOccupancy.ACTIVE, LaunchTargetOccupancy.decide(TARGET,
                Arrays.asList(root(1, true), target), Arrays.asList(recent)).code);
        assertTrue(LaunchTargetOccupancy.decide(TARGET, Arrays.asList(root(1, true), target),
                Arrays.asList(recent), ids(38)).reuses());
    }

    @Test public void sameIdRegistrationReplacesIdentityWithoutDuplicateCounts() {
        Map<Integer, String> owned = new LinkedHashMap<Integer, String>();
        owned.put(38, "old-binder-and-marker");
        Map<Integer, String> updated = EndedOwnedRecent.register(owned, 38, "new-binder-and-marker",
                ids(1), ids(38));
        assertEquals("old-binder-and-marker", owned.get(38));
        assertEquals("new-binder-and-marker", updated.get(38));
        assertEquals(1, updated.size());
        OwnedTaskStates states = OwnedTaskStates.classify(updated.keySet(), ids(1, 38), ids(38));
        assertEquals(ids(38), states.live);
        assertTrue(states.gone.isEmpty());
        assertTrue(states.escaped.isEmpty());
    }

    @Test public void differentIdRegistrationRetainsOldGoneIdentityForItsRecent() {
        Map<Integer, String> owned = new LinkedHashMap<Integer, String>();
        owned.put(38, "old-identity");
        Map<Integer, String> updated = EndedOwnedRecent.register(owned, 41, "new-identity",
                ids(1), ids(38));
        assertEquals(1, owned.size());
        assertEquals(2, updated.size());
        assertEquals("old-identity", updated.get(38));
        OwnedTaskStates states = OwnedTaskStates.classify(updated.keySet(), ids(1, 41), ids(41));
        assertEquals(ids(38), states.gone);
        assertEquals(ids(41), states.live);
        assertEquals(updated.size(), states.gone.size() + states.live.size());
        assertTrue(states.escaped.isEmpty());
    }

    @Test(expected = IllegalStateException.class) public void activeChildIdCannotRegisterAsFresh() {
        EndedOwnedRecent.register(Collections.<Integer, String>emptyMap(), 38, "new",
                ids(1, 38), ids());
    }

    @Test(expected = IllegalStateException.class) public void escapedOwnedIdCannotBeOverwritten() {
        EndedOwnedRecent.register(Collections.singletonMap(38, "old"), 38, "new", ids(1), ids());
    }
}
