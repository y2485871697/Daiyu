package app.daiyu.android.vd.runtime;

import java.util.List;
import java.util.UUID;

/**
 * 固定使用 NEW_TASK | MULTIPLE_TASK 创建独立任务，避免复用 affinity 匹配的用户任务。
 * MULTIPLE_TASK 的行为见 developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_MULTIPLE_TASK。
 * 会话不使用文档任务模式；调用方传入 NEW_DOCUMENT 或其他超出 {@link #LAUNCH_FLAGS} 的位时拒绝启动。
 */
final class LaunchPolicy {
    static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    static final int FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000;
    /** Never set by this owner; named only so a caller that asks for it can be rejected. */
    static final int FLAG_ACTIVITY_NEW_DOCUMENT = 0x00080000;
    /** 0x18000000. */
    static final int LAUNCH_FLAGS = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK;
    static final String MARKER_PREFIX = "daiyu-vd://session/";

    private LaunchPolicy() {
    }

    /**
     * Resolves the caller's optional flags. Only bits already inside {@link #LAUNCH_FLAGS} are
     * tolerated; anything else (including {@link #FLAG_ACTIVITY_NEW_DOCUMENT}) fails closed. The
     * returned value is always {@link #LAUNCH_FLAGS}, so a caller can never weaken the
     * independent-task guarantee.
     */
    static int resolveLaunchFlags(int callerFlags) {
        if ((callerFlags & ~LAUNCH_FLAGS) != 0) {
            throw new IllegalArgumentException("unsupported launch flags");
        }
        return LAUNCH_FLAGS;
    }

    /** A fresh, unguessable per-launch marker carried as the intent data URI. */
    static String newMarker() {
        return MARKER_PREFIX + UUID.randomUUID().toString();
    }

    /**
     * argv for {@code am start --display <id> -n <component> -f 0x18000000 -d <marker>}.
     *
     * <p>The marker is appended after the option block as a single literal {@code -d} argument. A
     * blank marker is refused rather than launching without provenance.
     */
    static String[] startArgv(int displayId, String packageName, String component, String action,
            List<String> categories, String marker) {
        if (marker == null || marker.isEmpty()) {
            throw new IllegalArgumentException("marker");
        }
        String[] base = ShellCommands.amStartArgv(displayId, packageName, component, action,
                categories, LAUNCH_FLAGS);
        String[] argv = new String[base.length + 2];
        System.arraycopy(base, 0, argv, 0, base.length);
        argv[base.length] = "-d";
        argv[base.length + 1] = marker;
        return argv;
    }

    /**
     * Post-launch provenance: the task's base intent must carry exactly this launch's marker as its
     * data URI and must belong to the target package. A missing marker (null/empty), a different
     * data URI, or a different/absent component package all reject.
     */
    static boolean provenanceMatches(String marker, String targetPackage, String baseDataString,
            String baseComponentPackage) {
        return marker != null && !marker.isEmpty() && marker.equals(baseDataString)
                && targetPackage != null && targetPackage.equals(baseComponentPackage);
    }
}
