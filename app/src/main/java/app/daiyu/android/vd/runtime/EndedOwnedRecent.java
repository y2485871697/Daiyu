package app.daiyu.android.vd.runtime;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only proof for ignoring exactly one ended, session-owned recent entry. */
final class EndedOwnedRecent {
    private EndedOwnedRecent() { }

    /** Missing or malformed platform fields remain null, never an id fallback. */
    static final class Recent {
        final Integer taskId, persistentId, displayId, userId, parentTaskId, numActivities;
        final String component, marker;
        final boolean componentsKnown, activitiesAbsent, packagesMatch;

        Recent(Integer taskId, Integer persistentId, Integer displayId, Integer userId,
                Integer parentTaskId, Integer numActivities, String component, String marker,
                boolean componentsKnown, boolean activitiesAbsent, boolean packagesMatch) {
            this.taskId = taskId;
            this.persistentId = persistentId;
            this.displayId = displayId;
            this.userId = userId;
            this.parentTaskId = parentTaskId;
            this.numActivities = numActivities;
            this.component = component;
            this.marker = marker;
            this.componentsKnown = componentsKnown;
            this.activitiesAbsent = activitiesAbsent;
            this.packagesMatch = packagesMatch;
        }

        /** Only the documented inactive -1 task id may resolve through persistentId. */
        int identityId() {
            if (taskId == null || persistentId == null || persistentId <= 0) return -1;
            if (taskId == -1 || (taskId > 0 && taskId.equals(persistentId)))
                return persistentId;
            return -1;
        }
    }

    /** Null means absence cannot be proven across ALL root/child arrays. */
    static Set<Integer> presentIds(List<LaunchTargetOccupancy.Root> roots) {
        if (roots == null || roots.isEmpty()) return null;
        Set<Integer> rootIds = new HashSet<Integer>();
        Set<Integer> present = new LinkedHashSet<Integer>();
        for (LaunchTargetOccupancy.Root root : roots) {
            if (root == null || root.taskId <= 0 || !rootIds.add(root.taskId)
                    || !root.childIdsKnown || root.childTaskIds == null) return null;
            present.add(root.taskId);
            Set<Integer> children = new HashSet<Integer>();
            for (int id : root.childTaskIds) {
                if ((id != -1 && id <= 0) || !children.add(id)) return null;
                if (id > 0) present.add(id);
            }
        }
        return present;
    }

    private static boolean sessionMarker(String marker) {
        if (marker == null || !marker.startsWith(LaunchPolicy.MARKER_PREFIX)) return false;
        String suffix = marker.substring(LaunchPolicy.MARKER_PREFIX.length());
        try { return UUID.fromString(suffix).toString().equals(suffix); }
        catch (IllegalArgumentException ex) { return false; }
    }

    static boolean proves(String target, int source, int ownedId, String ownedComponent,
            String ownedMarker, Set<Integer> gone, Set<Integer> escaped,
            Set<Integer> present, Recent recent) {
        if (source <= 0 || target == null || target.isEmpty() || ownedId <= 0
                || ownedComponent == null || !ownedComponent.startsWith(target + "/")
                || !sessionMarker(ownedMarker) || gone == null || !gone.contains(ownedId)
                || escaped == null || !escaped.isEmpty() || present == null
                || present.contains(ownedId) || recent == null) return false;
        return recent.identityId() == ownedId
                && Integer.valueOf(-1).equals(recent.displayId)
                && Integer.valueOf(0).equals(recent.userId)
                && Integer.valueOf(-1).equals(recent.parentTaskId)
                && Integer.valueOf(0).equals(recent.numActivities)
                && recent.componentsKnown && recent.activitiesAbsent && recent.packagesMatch
                && ownedComponent.equals(recent.component) && ownedMarker.equals(recent.marker);
    }

    /**
     * Stage one independently verified fresh identity; never mutate retained state on failure.
     * A different id preserves the old gone record, so its remaining recent entry stays provable.
     * A recycled id replaces that exact gone identity, without increasing the retained count.
     */
    static <T> Map<Integer, T> register(Map<Integer, T> owned, int id, T fresh,
            Set<Integer> beforePresent, Set<Integer> beforeGone) {
        if (owned == null || fresh == null || id <= 0 || beforePresent == null
                || beforeGone == null || beforePresent.contains(id)
                || (owned.containsKey(id) && !beforeGone.contains(id)))
            throw new IllegalStateException("fresh task identity not proven");
        Map<Integer, T> updated = new LinkedHashMap<Integer, T>(owned);
        updated.put(id, fresh);
        return updated;
    }
}
