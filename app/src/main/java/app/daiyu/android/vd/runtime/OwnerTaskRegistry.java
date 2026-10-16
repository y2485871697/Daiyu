package app.daiyu.android.vd.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Process-local task identity for the owner.
 *
 * <p>A task is retained by its numeric id together with the real token binder observed for it.
 * The binder object is held so it cannot be collected, and a later enumeration that reports the
 * same id under a different binder means the task instance was replaced. That is treated as
 * unknown source state rather than silently accepted, because release must not act on a stale
 * handle. Nothing here is written to disk or logged as raw binder text.
 */
final class OwnerTaskRegistry {
    private final Map<Integer, Object> binderById = new HashMap<Integer, Object>();
    private final Map<Object, Integer> idByBinder = new HashMap<Object, Integer>();

    /** Bind and validate the tasks currently seen on the source display. */
    void retain(List<OwnerTaskInventory.Seen> tasks) throws OwnerException {
        for (int i = 0; i < tasks.size(); i++) {
            OwnerTaskInventory.Seen seen = tasks.get(i);
            Integer knownId = idByBinder.get(seen.binder);
            if (knownId != null && knownId.intValue() != seen.taskId) {
                throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN,
                        "BINDER_TASK_MISMATCH");
            }
            Object knownBinder = binderById.get(Integer.valueOf(seen.taskId));
            if (knownBinder != null && !knownBinder.equals(seen.binder)) {
                throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN,
                        "TASK_BINDER_REPLACED");
            }
            idByBinder.put(seen.binder, Integer.valueOf(seen.taskId));
            binderById.put(Integer.valueOf(seen.taskId), seen.binder);
        }
    }

    /**
     * Called only after a fresh marked task has passed post-launch identity verification.
     * Ordinary inventory retains its fail-closed binder rule; only an exact gone owned identity
     * may be replaced, with all validation completed before either index is changed.
     */
    void replaceGone(int id, Object expected, Object replacement, Set<Integer> beforePresent,
            Set<Integer> beforeGone) throws OwnerException {
        Object retained = binderById.get(id);
        Integer replacementId = idByBinder.get(replacement);
        if (id <= 0 || expected == null || replacement == null || beforePresent == null
                || beforeGone == null || beforePresent.contains(id) || !beforeGone.contains(id)
                || (retained != null && !expected.equals(retained))
                || (replacementId != null && replacementId.intValue() != id))
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN,
                    "ENDED_TASK_REPLACEMENT_UNPROVEN");
        if (retained != null) idByBinder.remove(retained);
        binderById.put(id, replacement);
        idByBinder.put(replacement, id);
    }

    boolean isEmpty() {
        return binderById.isEmpty();
    }

    int size() {
        return binderById.size();
    }

    /** Sorted retained ids for diagnostics. Never returns binder text. */
    List<Integer> retainedTaskIds() {
        List<Integer> ids = new ArrayList<Integer>(binderById.keySet());
        Collections.sort(ids);
        return Collections.unmodifiableList(ids);
    }
}
