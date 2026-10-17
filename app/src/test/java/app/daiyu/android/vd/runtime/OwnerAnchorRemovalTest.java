package app.daiyu.android.vd.runtime;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnerAnchorRemovalTest {
    private static final int ID=557;
    private static OwnedTaskStates state(boolean present,boolean live,boolean complete) {
        return OwnedTaskStates.classify(Collections.singleton(ID),
                present?Collections.singleton(ID):Collections.emptySet(),
                live?Collections.singleton(ID):Collections.emptySet(),complete);
    }
    private static final class Fake implements OwnerAnchorRemoval.Access {
        boolean result; int calls,reads,pauses; int goneAt=1; boolean escaped,incomplete,readFailure,invokeFailure;
        Fake(boolean result) { this.result=result; }
        public boolean remove() { calls++; if(invokeFailure) throw new IllegalStateException("invoke failed"); return result; }
        public OwnedTaskStates observe() {
            reads++;
            if(readFailure) throw new IllegalStateException("inventory failed");
            return state(reads<goneAt,!escaped && reads<goneAt,!incomplete);
        }
        public void pause() { pauses++; }
    }
    @Test public void trueAndFalseBothRequireObservedAbsenceWithExactlyOneRemoval() throws Exception {
        for(boolean result:new boolean[]{true,false}) {
            Fake f=new Fake(result);f.goneAt=3;
            OwnerAnchorRemoval.remove(ID,f);
            assertEquals(1,f.calls);assertEquals(3,f.reads);assertEquals(2,f.pauses);
        }
    }
    @Test public void liveAfterBudgetFailsWithoutRepeatingMutation() {
        for(boolean result:new boolean[]{true,false}) {
            Fake f=new Fake(result);f.goneAt=Integer.MAX_VALUE;
            assertThrows(IllegalStateException.class,()->OwnerAnchorRemoval.remove(ID,f));
            assertEquals(1,f.calls);assertEquals(21,f.reads);assertEquals(20,f.pauses);
        }
    }
    @Test public void changedOrEscapedIdentityFailsWithoutRetry() {
        Fake f=new Fake(true);f.goneAt=2;f.escaped=true;
        assertThrows(IllegalStateException.class,()->OwnerAnchorRemoval.remove(ID,f));
        assertEquals(1,f.calls);assertEquals(1,f.reads);assertEquals(0,f.pauses);
    }
    @Test public void incompleteInventoryNeverProvesGone() {
        Fake f=new Fake(false);f.incomplete=true;
        assertThrows(IllegalStateException.class,()->OwnerAnchorRemoval.remove(ID,f));
        assertEquals(1,f.calls);assertEquals(1,f.reads);
    }
    @Test public void unreadableInventoryFailsWithoutRetry() {
        Fake f=new Fake(true);f.readFailure=true;
        assertThrows(IllegalStateException.class,()->OwnerAnchorRemoval.remove(ID,f));
        assertEquals(1,f.calls);assertEquals(1,f.reads);
    }
    @Test public void unknownInvocationIsNotRetriedOrAssumedAbsent() {
        Fake f=new Fake(true);f.invokeFailure=true;
        assertThrows(IllegalStateException.class,()->OwnerAnchorRemoval.remove(ID,f));
        assertEquals(1,f.calls);assertEquals(0,f.reads);
    }
}
