package app.daiyu.android.vd.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public class OwnedTaskStatesTest {
    private static Set<Integer> set(Integer... ids) { return new LinkedHashSet<Integer>(Arrays.asList(ids)); }

    @Test public void removedTasksAreGoneNotEscaped() {
        // Real-device case: tasks 190/191/192 were launched, then the system removed all three.
        OwnedTaskStates s = OwnedTaskStates.classify(set(190, 191, 192), set(1, 2, 184), set());
        assertEquals(set(190, 191, 192), s.gone);
        assertTrue(s.live.isEmpty());
        assertTrue(s.escaped.isEmpty());
    }
    @Test public void liveGoneAndEscapedAreDisjoint() {
        OwnedTaskStates s = OwnedTaskStates.classify(set(190, 191, 192), set(191, 192), set(191));
        assertEquals(set(191), s.live);
        assertEquals(set(190), s.gone);
        assertEquals(set(192), s.escaped);
    }
    @Test public void nestedChildIsPresentSoNeverGone() {
        // Present only as some root's child id: not provably ours on the source display.
        OwnedTaskStates s = OwnedTaskStates.classify(set(191), set(3, 191), Collections.<Integer>emptySet());
        assertEquals(set(191), s.escaped);
        assertTrue(s.gone.isEmpty());
    }
    @Test(expected = IllegalArgumentException.class) public void unknownInventoryThrows() {
        OwnedTaskStates.classify(set(191), null, new HashSet<Integer>());
    }
    @Test(expected = IllegalArgumentException.class) public void invalidOwnedIdThrows() {
        OwnedTaskStates.classify(set(0), set(), set());
    }
    @Test public void unreadableChildrenNeverProveGone() {
        OwnedTaskStates s = OwnedTaskStates.classify(set(190, 191), set(1, 191), set(191), false);
        assertEquals(set(191), s.live);
        assertTrue(s.gone.isEmpty());
        assertEquals(set(190), s.escaped);
    }
}
