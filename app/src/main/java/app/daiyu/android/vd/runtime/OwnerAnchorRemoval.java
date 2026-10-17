package app.daiyu.android.vd.runtime;

/** A single removal followed only by bounded identity-aware observations. */
final class OwnerAnchorRemoval {
    interface Access {
        boolean remove() throws Exception;
        OwnedTaskStates observe() throws Exception;
        void pause() throws Exception;
    }

    static void remove(int id, Access access) throws Exception {
        access.remove();
        for (int attempt = 0; attempt <= 20; attempt++) {
            OwnedTaskStates state = access.observe();
            if (state.gone.contains(id)) return;
            if (!state.live.contains(id) || !state.escaped.isEmpty())
                throw new IllegalStateException("anchor identity unverified");
            if (attempt == 20) throw new IllegalStateException("anchor removal uncertain");
            access.pause();
        }
    }

    private OwnerAnchorRemoval() { }
}
