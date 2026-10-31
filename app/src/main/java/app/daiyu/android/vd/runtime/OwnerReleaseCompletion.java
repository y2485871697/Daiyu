package app.daiyu.android.vd.runtime;

/**
 * Per-command rendezvous between owner dispatch and its socket response attempt. Only verified
 * release (or explicit never-initialized store closure) authorizes exit. A completed flush,
 * failed flush, disconnect or timed-out waiter only finishes delivery. Either event may arrive
 * first. The boolean is the dispatcher's terminal proof.
 */
final class OwnerReleaseCompletion {
    private boolean verifiedReleased;
    private boolean responseFinished;
    private boolean stopClaimed;

    synchronized boolean dispatchFinished(boolean released) {
        verifiedReleased |= released;
        return claimStop();
    }

    synchronized boolean responseFinished() {
        responseFinished = true;
        return claimStop();
    }

    private boolean claimStop() {
        if (!verifiedReleased || !responseFinished || stopClaimed) return false;
        stopClaimed = true;
        return true;
    }
}
