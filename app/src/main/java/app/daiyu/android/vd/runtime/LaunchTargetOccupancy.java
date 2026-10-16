package app.daiyu.android.vd.runtime;

import java.util.List;

/** Conservative, pure-Java preflight policy. Unknown task identity is never a clean inventory. */
final class LaunchTargetOccupancy {
    static final String ACTIVE = "TARGET_TASK_ACTIVE";
    static final String RECENT = "TARGET_TASK_RECENT";
    static final String UNKNOWN = "TARGET_INVENTORY_UNKNOWN";

    /**
     * Sanitized snapshot of one root task, extended with the identity of its child tasks.
     *
     * <p>A root task can be a container whose activities live in nested child tasks, so proving a
     * root is not the target also requires proving none of its children is the target. The raw
     * {@code childTaskIds} array and the parallel {@code childTaskNames} package per id are carried
     * here (a {@code null} element means the platform did not name that child) so the decision is a
     * pure function that can be unit tested without a device.
     *
     * <p>An organizer-created, identity-free empty container may legitimately own child tasks that the
     * platform does not name. {@code organizerEvidenceValid} records that this root satisfied the
     * preconditions to consult a read-only {@code TaskOrganizer} enumeration; {@code
     * emptyOrganizerProven} records that the enumeration matched the root's own child ids exactly and
     * every child was an identity-free empty task. Only when both hold may an unnamed foreign child be
     * treated as harmless.
     */
    static final class Root {
        final int taskId;
        final String base, baseActivity, topActivity, realActivity, origActivity;
        final boolean componentsKnown;
        final int numActivities; // -1 means unreadable
        final boolean childIdsKnown;
        final int[] childTaskIds; // raw platform ids; may contain the root's own id or -1 marker
        final boolean childNamesKnown;
        final String[] childTaskNames; // package per raw child id; a null element is unreadable
        final boolean emptyOrganizerProven;
        final boolean organizerEvidenceValid;

        Root(int taskId, String base, String baseActivity, String topActivity,
                String realActivity, String origActivity, boolean componentsKnown, int numActivities,
                boolean childIdsKnown, int[] childTaskIds, boolean childNamesKnown,
                String[] childTaskNames, boolean emptyOrganizerProven, boolean organizerEvidenceValid) {
            this.taskId = taskId;
            this.base = base;
            this.baseActivity = baseActivity;
            this.topActivity = topActivity;
            this.realActivity = realActivity;
            this.origActivity = origActivity;
            this.componentsKnown = componentsKnown;
            this.numActivities = numActivities;
            this.childIdsKnown = childIdsKnown;
            this.childTaskIds = childTaskIds;
            this.childNamesKnown = childNamesKnown;
            this.childTaskNames = childTaskNames;
            this.emptyOrganizerProven = emptyOrganizerProven;
            this.organizerEvidenceValid = organizerEvidenceValid;
        }
    }

    static final class Recent {
        final String base, baseActivity, topActivity, realActivity, origActivity;
        final boolean componentsKnown;
        final int taskId; // -1 means unreadable; never proves the recent is a reusable task

        Recent(String base, String baseActivity, String topActivity,
                String realActivity, String origActivity, boolean componentsKnown) {
            this(-1, base, baseActivity, topActivity, realActivity, origActivity, componentsKnown);
        }

        Recent(int taskId, String base, String baseActivity, String topActivity,
                String realActivity, String origActivity, boolean componentsKnown) {
            this.taskId = taskId;
            this.base = base;
            this.baseActivity = baseActivity;
            this.topActivity = topActivity;
            this.realActivity = realActivity;
            this.origActivity = origActivity;
            this.componentsKnown = componentsKnown;
        }
    }

    static final class Decision {
        final String code;
        final String detail;
        /** A live task this owner launched on its own display that may be brought back; -1 if none. */
        final int reuseTaskId;
        Decision(String code, String detail) { this(code, detail, -1); }
        Decision(String code, String detail, int reuseTaskId) {
            this.code = code; this.detail = detail; this.reuseTaskId = code == null ? reuseTaskId : -1;
        }
        boolean rejects() { return code != null; }
        boolean reuses() { return code == null && reuseTaskId > 0; }
    }

    private LaunchTargetOccupancy() { }

    private static boolean contains(String target, String... packages) {
        for (String candidate : packages) if (target.equals(candidate)) return true;
        return false;
    }

    /** -1 is the platform's non-task child marker, never another task id. */
    static boolean hasForeignChild(int selfId, int[] ids) {
        if (selfId < 0 || ids == null) return true; // unreadable identity is never an empty shell
        for (int child : ids) if (child != -1 && child != selfId) return true;
        return false;
    }

    /** A child id that denotes a different, real task; the root's own id and -1 are markers. */
    private static boolean isForeignChild(int selfId, int id) {
        return id >= 0 && id != selfId;
    }

    private static boolean hasParallelChildNames(Root root) {
        return root.childIdsKnown && root.childNamesKnown && root.childTaskIds != null
                && root.childTaskNames != null
                && root.childTaskNames.length == root.childTaskIds.length;
    }

    /** Only -1 and the root's own id are non-task markers. Everything else must be unique. */
    private static boolean validChildIds(Root root) {
        if (!root.childIdsKnown || root.childTaskIds == null || root.taskId <= 0) return false;
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (int id : root.childTaskIds) {
            if (id != -1 && id <= 0) return false;
            if (!seen.add(id)) return false;
        }
        return true;
    }

    /** True when a readable, identified foreign child is the target package. */
    private static boolean targetChild(Root root, String target) {
        if (!hasParallelChildNames(root)) return false;
        int[] ids = root.childTaskIds;
        String[] names = root.childTaskNames;
        for (int i = 0; i < ids.length; i++) {
            // Even a self/marker slot with a target name is contradictory, never a clean inventory.
            if (target.equals(names[i])) return true;
        }
        return false;
    }

    /** No package identity in any component field; an unreadable self is only clearable as empty. */
    private static boolean identityAbsent(Root root) {
        return root.base == null && root.baseActivity == null && root.topActivity == null
                && root.realActivity == null && root.origActivity == null;
    }

    /**
     * True when a name carried on the root's own marker echoes one of the root's own readable
     * identity fields. At least one identity field must be readable, and the name must equal one of
     * them.
     *
     * <p>The platform echoes a package it already reported for this root into the
     * {@code childTaskNames} slot that pairs with the root's own id. A task can legitimately host
     * more than one package (the caller's activity plus a system activity such as the photo picker
     * started for a result in the same task), so the slot only repeats one of those packages. Matching
     * any readable field is an echo of this root. A name that matches none of them, or a name on an
     * identity-free root, is a conflict and must fail closed: the marker can never invent an identity
     * the root itself did not report. A field that actually is the launch target is rejected earlier
     * by {@link #contains} and never reaches this check.
     */
    private static boolean selfMarkerNameEchoesRoot(Root root, String name) {
        String[] identities = {root.base, root.baseActivity, root.topActivity, root.realActivity,
                root.origActivity};
        for (String identity : identities) {
            if (identity != null && identity.equals(name)) return true;
        }
        return false;
    }

    /** Organizer evidence that every foreign child is an identity-free empty task. */
    private static boolean organizerProven(Root root) {
        return root.organizerEvidenceValid && root.emptyOrganizerProven
                && identityAbsent(root) && root.numActivities == 0;
    }

    /**
     * True only when the platform evidence proves this root and every child task is not the target.
     *
     * <p>A root with an unreadable self identity, unreadable child ids, missing or non-parallel child
     * names with a foreign child, or a foreign child whose identity is unknown is never clearable: the
     * caller must fail closed rather than assume the unnamed child is harmless. The single exception
     * is an organizer-created, identity-free empty container whose {@code TaskOrganizer} enumeration
     * matches its child ids exactly ({@link #organizerProven}); only then may an unnamed foreign child
     * be accepted. A self-absent root is otherwise only clearable as an empty container: it must report
     * zero activities on top of the readable, identified non-target children.
     *
     * <p>A name on the root's own marker is tolerated only when it merely echoes the root's own
     * readable identity ({@link #selfMarkerNameEchoesRoot}); a name on a {@code -1} marker, or a
     * partial/contradictory self name, is a conflict, never a clean inventory.
     */
    private static boolean provenNonTarget(Root root) {
        if (!root.componentsKnown || !validChildIds(root)) return false;
        if (identityAbsent(root) && root.numActivities != 0) return false;
        // A claimed readable name array that disagrees with the ids is inconsistent, not empty.
        if (root.childNamesKnown && !hasParallelChildNames(root)) return false;
        boolean namesKnown = hasParallelChildNames(root);
        boolean foreign = false, unnamedForeign = false, namedForeign = false;
        for (int i = 0; i < root.childTaskIds.length; i++) {
            int id = root.childTaskIds[i];
            if (!isForeignChild(root.taskId, id)) {
                // A name on the root's own marker is tolerated only when it echoes the root's own
                // readable identity. A name on a -1 marker, or a partial/contradictory self name,
                // contradicts the assertion that the slot names no other task.
                if (namesKnown && root.childTaskNames[i] != null
                        && (id != root.taskId
                                || !selfMarkerNameEchoesRoot(root, root.childTaskNames[i]))) {
                    return false;
                }
                continue;
            }
            foreign = true;
            if (!namesKnown || root.childTaskNames[i] == null) unnamedForeign = true;
            else namedForeign = true;
        }
        if (!foreign) return namesKnown; // collector normalizes an absent self-only name array
        if (!unnamedForeign) return namesKnown; // all foreign leaf tasks have known, non-target names
        // An empty-organizer proof cannot coexist with a named, non-empty foreign task.
        return !namedForeign && organizerProven(root);
    }

    static Decision decide(String target, List<Root> roots, List<Recent> recents) {
        return decide(target, roots, recents, java.util.Collections.<Integer>emptySet());
    }

    /**
     * Same policy, except a target root whose id is in {@code reusableTaskIds} is not an
     * occupancy conflict: it is this owner's own live task on its own display (the caller proves
     * that from the same inventory snapshot) and is returned as {@link Decision#reuseTaskId}.
     *
     * <p>Reuse is exclusive. Any other target root or target child, more than one reusable target
     * root, a target recent that is not that exact task id, or an unreadable recent id still
     * refuses exactly as before. Unknown inventory still wins over reuse.
     */
    static Decision decide(String target, List<Root> roots, List<Recent> recents,
            java.util.Set<Integer> reusableTaskIds) {
        if (target == null || target.isEmpty())
            return new Decision(UNKNOWN, "target package missing");
        if (reusableTaskIds == null) return new Decision(UNKNOWN, "owned inventory missing");
        String unknown = null;
        int reuse = -1;
        if (roots == null || roots.isEmpty()) unknown = "root inventory empty";
        if (recents == null) unknown = "recent inventory missing";
        for (Root root : roots == null ? java.util.Collections.<Root>emptyList() : roots) {
            if (root == null) { if (unknown == null) unknown = "null root"; continue; }
            if (contains(target, root.base, root.baseActivity, root.topActivity,
                    root.realActivity, root.origActivity)) {
                if (root.taskId > 0 && reusableTaskIds.contains(root.taskId) && reuse == -1
                        && !hasForeignChild(root.taskId, root.childTaskIds)) {
                    reuse = root.taskId;
                    continue;
                }
                return new Decision(ACTIVE, "active task id=" + root.taskId);
            }
            if (targetChild(root, target))
                return new Decision(ACTIVE, "active child task of id=" + root.taskId);
            if (!provenNonTarget(root) && unknown == null)
                unknown = "root task id=" + root.taskId + " identity unknown";
        }
        for (Recent recent : recents == null ? java.util.Collections.<Recent>emptyList() : recents) {
            if (recent == null) { if (unknown == null) unknown = "null recent"; continue; }
            if (contains(target, recent.base, recent.baseActivity,
                    recent.topActivity, recent.realActivity, recent.origActivity)) {
                if (reuse > 0 && recent.taskId == reuse) continue;
                return new Decision(RECENT, "existing recent task");
            }
            if ((!recent.componentsKnown || (recent.base == null && recent.baseActivity == null
                    && recent.topActivity == null && recent.realActivity == null
                    && recent.origActivity == null)) && unknown == null)
                unknown = "recent identity unknown";
        }
        return unknown == null ? new Decision(null, "", reuse) : new Decision(UNKNOWN, unknown);
    }
}
