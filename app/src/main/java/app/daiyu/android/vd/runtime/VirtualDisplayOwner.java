package app.daiyu.android.vd.runtime;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * The live owner of exactly one created virtual display.
 *
 * <p>A single owner instance binds a single display: the display id comes from the created
 * {@code VirtualDisplay}, the unique id is generated at creation, and every operation refuses to
 * act on any other display. Operations run on the owner's looper thread, which is how commands are
 * serialized; the IPC layer only ever posts to that handler.
 *
 * <p>{@code release} refuses unless the source display is currently observed empty and every
 * observed task binder is stable. Handoff migrates selected fresh tasks and removes only verified
 * session intermediates. It is not a framework-level external-launch fence; no user process is killed.
 */
public final class VirtualDisplayOwner {
    // One retained static image plus two slots needed by acquireLatestImage to discard old frames.
    private static final int MAX_IMAGES = 3;
    private static final int MAX_SNAPSHOT_BYTES = 6 * 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 2000;
    private static final int MAX_COORDINATE = 100_000;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final VirtualDisplayFactory.Created created;
    private final OwnerFrameStore frames;
    private final OwnerTaskRegistry registry = new OwnerTaskRegistry();
    private final Handler handler;
    private final InputAccess inputAccess;

    /** Package-only host-test seam; production always uses the framework/shell implementations. */
    static class InputAccess {
        void verifyDisplay(int displayId, String uniqueId) throws Exception {
            OwnerHandoff.verifyDisplay(displayId, uniqueId);
        }

        int[] geometry(int displayId, String uniqueId) throws Exception {
            Class<?> c = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object manager = c.getMethod("getInstance").invoke(null);
            Object info = c.getMethod("getDisplayInfo", int.class).invoke(manager, displayId);
            if (info == null || !uniqueId.equals(OwnerHandoff.field(info, "uniqueId")))
                throw new IllegalStateException("display identity");
            int width = OwnerHandoff.number(info, "logicalWidth");
            int height = OwnerHandoff.number(info, "logicalHeight");
            if (width <= 0 || height <= 0) throw new IllegalStateException("geometry");
            return new int[]{width, height};
        }

        List<OwnerTaskInventory.Seen> sourceTasks(int displayId) throws OwnerException {
            return OwnerTaskInventory.readOnDisplay(displayId);
        }

        java.util.Map<Integer,Object> statusRoots() throws Exception {
            return OwnerHandoff.roots();
        }

        OwnerShell.Result run(String[] argv) throws OwnerException {
            return OwnerShell.run(argv, OwnerShell.DEFAULT_TIMEOUT_MS,
                    OwnerShell.DEFAULT_MAX_OUTPUT_BYTES);
        }

        JSONObject response() throws JSONException {
            return new JSONObject();
        }
    }
    private volatile boolean released;
    private boolean finishing;
    private boolean handoffComplete;
    private boolean releaseAttempted;
    private boolean mutationUncertain;
    /** Set with handoffComplete: true when the completed handoff delivered nothing (cleanup only). */
    private boolean handoffCleanupOnly;
    private final java.util.Map<Integer,OwnerHandoff.Task> owned = new java.util.LinkedHashMap<Integer,OwnerHandoff.Task>();

    private VirtualDisplayOwner(VirtualDisplayFactory.Created created, OwnerFrameStore frames,
            Handler handler) {
        this(created, frames, handler, new InputAccess());
    }

    // Not a public factory or authorization bypass: the IPC owner is still created only by create().
    VirtualDisplayOwner(VirtualDisplayFactory.Created created, OwnerFrameStore frames,
            Handler handler, InputAccess inputAccess) {
        this.created = created;
        this.frames = frames;
        this.handler = handler;
        this.inputAccess = inputAccess;
    }

    /** Creates the display and binds the retained ImageReader owner. Called on the owner looper. */
    public static VirtualDisplayOwner create(String name, Integer width, Integer height,
            Integer densityDpi, Handler handler) throws OwnerException {
        if (handler == null) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "handler");
        }
        if (Looper.myLooper() != handler.getLooper()) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "create thread");
        }
        String displayName = name == null || name.isEmpty() ? "daiyu-vd" : name;
        if (!OwnerProtocol.isSafeText(displayName, 128)) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "name");
        }
        DeviceDisplayInfo.Geometry geometry = DeviceDisplayInfo.defaultGeometry();
        int w = width == null ? geometry.width : width.intValue();
        int h = height == null ? geometry.height : height.intValue();
        int d = densityDpi == null ? geometry.densityDpi : densityDpi.intValue();
        if (w <= 0 || h <= 0 || d <= 0) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "geometry");
        }
        String uniqueId = newUniqueId();
        VirtualDisplayFactory.Created created = VirtualDisplayFactory.create(displayName, w, h, d,
                uniqueId, VirtualDisplayFactory.DEFAULT_FLAGS, MAX_IMAGES);
        OwnerFrameStore frames = new OwnerFrameStore();
        created.reader.setOnImageAvailableListener(frames, handler);
        return new VirtualDisplayOwner(created, frames, handler);
    }

    public boolean isReleased() {
        return released;
    }

    public int displayId() {
        return created.displayId;
    }

    public String uniqueId() {
        return created.uniqueId;
    }

    public JSONObject status() throws OwnerException {
        requireLive();
        int[] geometry = currentGeometry();
        SourceProbe source = probeSource();
        JSONObject out = new JSONObject();
        try {
            out.put("ready", true);
            out.put("displayId", created.displayId);
            out.put("uniqueId", created.uniqueId);
            out.put("name", created.name);
            out.put("width", geometry[0]);
            out.put("height", geometry[1]);
            out.put("densityDpi", created.densityDpi);
            out.put("flags", created.flags);
            out.put("ownerPackage", VirtualDisplayFactory.OWNER_PACKAGE);
            out.put("frameCount", frames.frameCount());
            out.put("hasFrame", frames.hasFrame());
            out.put("frameTimestampNs", frames.latestTimestampNs());
            out.put("session", released ? "released" : "active");
            out.put("sourceState", source.state);
            if (source.known()) {
                out.put("sourceEmpty", source.taskCount == 0);
                out.put("sourceTaskCount", source.taskCount);
            } else {
                out.put("sourceError", source.errorCode);
            }
            JSONArray packages=new JSONArray();
            try {
                java.util.Set<String> visiblePackages=new java.util.LinkedHashSet<String>();
                for(Object task:inputAccess.statusRoots().values())if(OwnerHandoff.number(task,"displayId")==created.displayId) {
                    android.content.Intent bi=(android.content.Intent)OwnerHandoff.field(task,"baseIntent");
                    if(bi!=null && bi.getComponent()!=null)visiblePackages.add(bi.getComponent().getPackageName());
                    Object top=OwnerHandoff.field(task,"topActivity");
                    if(top instanceof android.content.ComponentName)visiblePackages.add(((android.content.ComponentName)top).getPackageName());
                }
                for(String pkg:visiblePackages)packages.put(pkg);
                out.put("sourcePackages",packages);
            }catch(Exception e){out.put("sourcePackagesKnown",false);}
            out.put("retainedTaskIds",new JSONArray(owned.keySet()));
            // Read-only: owned is never pruned here. Absent fields mean "unknown" to the client.
            try {
                OwnedTaskStates states=OwnedTaskStates.read(created.displayId,inputAccess.statusRoots(),owned);
                out.put("liveTaskIds",new JSONArray(states.live));
                out.put("goneTaskIds",new JSONArray(states.gone));
            } catch(Exception ignored) { }
            out.put("finishing",finishing);
            out.put("handoffComplete",handoffComplete);
            // Lets a later release-only finish report handedOff=false after a cleanup-only handoff.
            if(handoffComplete) out.put("handoffCleanupOnly",handoffCleanupOnly);
            out.put("releaseAttempted",releaseAttempted);
            // A side effect may have been applied without a verified outcome: the recovery policy
            // treats this as "never replay" evidence.
            out.put("mutationUncertain",mutationUncertain);
            out.put("supported", stringArray(OwnerProtocol.SUPPORTED_OPS));
            out.put("missing", stringArray(OwnerProtocol.MISSING_OPS));
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "status");
        }
        return out;
    }

    public JSONObject launch(JSONObject request) throws OwnerException {
        requireLive();
        if(finishing || mutationUncertain) throw new OwnerException("SESSION_FINISHING");
        OwnerProtocol.Request parsed = wrap(request);
        int displayId = optionalDisplay(parsed);
        String packageName = parsed.optionalString("package");
        String component = parsed.optionalString("component");
        String action = parsed.optionalString("action");
        int callerFlags = parsed.optionalInt("flags", 0);
        List<String> categories = categories(parsed);
        if (packageName != null && !OwnerProtocol.isSafeIdentifier(packageName)) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "package");
        }
        if (component != null
                && (!OwnerProtocol.isSafeIdentifier(component) || component.indexOf('/') <= 0)) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "component");
        }
        if (action != null && !OwnerProtocol.isSafeIdentifier(action)) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "action");
        }
        if (packageName == null && component == null && action == null) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "launch target");
        }
        if(component==null)throw new OwnerException("EXPLICIT_COMPONENT_REQUIRED");
        String targetPackage=component.substring(0,component.indexOf('/'));
        // Admission and post-launch absence checks use the SAME complete root/child snapshot.
        OwnerHandoff.LaunchPreflight preflight;
        try { preflight=OwnerHandoff.launchPreflight(targetPackage,displayId,owned); }
        catch(OwnerException ex) { throw ex; }
        catch(Exception ex) { throw new OwnerException(LaunchTargetOccupancy.UNKNOWN,
                ex.getClass().getSimpleName()); }
        // A caller may only restate bits the owner already forces. NEW_DOCUMENT or any other bit is
        // rejected here, never silently ignored.
        try { LaunchPolicy.resolveLaunchFlags(callerFlags); }
        catch(IllegalArgumentException ex) { throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "flags"); }
        // Only a live identity may reorder. An ended recent always takes a new marked launch.
        if(preflight.reuseTaskId>0) return reuseOwned(preflight.reuseTaskId, displayId);
        String marker=LaunchPolicy.newMarker();
        String[] argv=LaunchPolicy.startArgv(displayId, packageName, component, action,categories,marker);
        try { OwnerHandoff.verifyDisplay(displayId,created.uniqueId); }
        catch(Exception ex) { throw new OwnerException("DISPLAY_REBOUND"); }
        // Arm the no-replay latch BEFORE the first attempted mutation, including timeout/failure.
        // Clear it only after the actual new task and the response have both been verified.
        finishing=true; mutationUncertain=true;
        OwnerShell.Result result;
        try {
            result=OwnerShell.run(argv,OwnerShell.DEFAULT_TIMEOUT_MS,OwnerShell.DEFAULT_MAX_OUTPUT_BYTES);
            if(!result.success() || containsError(result.stdout) || containsError(result.stderr))
                throw new IllegalStateException("launch outcome unverified");
            OwnerHandoff.Task fresh=null;
            java.util.Map<Integer,Object> after=OwnerHandoff.roots();
            for(Object task:after.values()) if(OwnerHandoff.number(task,"displayId")==displayId) {
                int id=OwnerHandoff.number(task,"taskId");
                if(preflight.presentTaskIds.contains(id)) {
                    OwnerHandoff.Task existing=owned.get(id);
                    if(existing==null) throw new IllegalStateException("pre-existing task moved");
                    existing.check(task,displayId);
                } else {
                    android.content.Intent base=(android.content.Intent)OwnerHandoff.field(task,"baseIntent");
                    String baseData=base==null?null:base.getDataString();
                    android.content.ComponentName baseComponent=base==null?null:base.getComponent();
                    String basePackage=baseComponent==null?null:baseComponent.getPackageName();
                    if(!LaunchPolicy.provenanceMatches(marker,targetPackage,baseData,basePackage))throw new IllegalStateException("launch provenance");
                    if(fresh!=null) throw new IllegalStateException("multiple fresh tasks");
                    fresh=new OwnerHandoff.Task(task);
                    fresh.check(task,displayId);
                }
            }
            if(fresh==null)throw new IllegalStateException("fresh launch task not observed");
            java.util.Map<Integer,OwnerHandoff.Task> updated=EndedOwnedRecent.register(owned,fresh.id,
                    fresh,preflight.presentTaskIds,preflight.goneTaskIds);
            // Re-read actual binder, marker, component, shape and display before committing.
            java.util.Map<Integer,Object> verified=OwnerHandoff.roots();
            fresh.check(verified.get(fresh.id),displayId);
            java.util.List<LaunchTargetOccupancy.Root> descriptions=new java.util.ArrayList<LaunchTargetOccupancy.Root>();
            for(Object task:verified.values()) {
                descriptions.add(OwnerHandoff.describeRoot(task));
                if(OwnerHandoff.number(task,"displayId")==displayId) {
                    OwnerHandoff.Task identity=updated.get(OwnerHandoff.number(task,"taskId"));
                    if(identity==null) throw new IllegalStateException("unexpected source task");
                    identity.check(task,displayId);
                }
            }
            if(EndedOwnedRecent.presentIds(descriptions)==null
                    || !OwnedTaskStates.read(displayId,verified,updated).escaped.isEmpty())
                throw new IllegalStateException("post-launch inventory unproven");
            OwnerHandoff.verifyDisplay(displayId,created.uniqueId);
            OwnerHandoff.Task previous=owned.get(fresh.id);
            if(previous!=null) registry.replaceGone(fresh.id,previous.binder,fresh.binder,
                    preflight.presentTaskIds,preflight.goneTaskIds);
            owned.put(fresh.id,fresh);
        } catch(Exception e) { throw new OwnerException("LAUNCH_IDENTITY_UNCERTAIN"); }
        JSONObject out = new JSONObject();
        try {
            out.put("taskIds",new JSONArray(owned.keySet()));
            out.put("launched", true);
            out.put("displayId", displayId);
            out.put("exitCode", result.exitCode);
            out.put("output", clip(result.stdout));
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "launch");
        }
        finishing=false; mutationUncertain=false;
        return out;
    }

    /** Switch back to a task this session launched; never starts an activity or touches display 0. */
    private JSONObject reuseOwned(int taskId, int displayId) throws OwnerException {
        OwnerHandoff.Task identity=owned.get(taskId);
        if(identity==null) throw new OwnerException(LaunchTargetOccupancy.UNKNOWN,"reuse identity");
        boolean[] applied={false};
        try { OwnerHandoff.bringOwnedToFront(displayId,created.uniqueId,identity,applied); }
        catch(Exception e) {
            // Checks before the transaction are read-only: refuse cleanly and keep the session usable.
            if(!applied[0]) throw new OwnerException("REUSE_PREFLIGHT_FAILED", e.getClass().getSimpleName());
            finishing=true; mutationUncertain=true; throw new OwnerException("LAUNCH_IDENTITY_UNCERTAIN");
        }
        JSONObject out = new JSONObject();
        try {
            out.put("taskIds",new JSONArray(owned.keySet()));
            out.put("launched", true);
            out.put("reused", true);
            out.put("reusedTaskId", taskId);
            out.put("displayId", displayId);
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "launch");
        }
        return out;
    }

    public JSONObject input(JSONObject request) throws OwnerException {
        requireLive();
        if(finishing || mutationUncertain) throw new OwnerException("SESSION_FINISHING");
        OwnerProtocol.Request parsed = wrap(request);
        int displayId = optionalDisplay(parsed);
        String kind = parsed.requireString("kind");
        if ("tap".equals(kind) || "swipe".equals(kind)) {
            int[] geometry = currentGeometry();
            if (geometry[0] != created.width || geometry[1] != created.height) {
                throw new OwnerException("VIRTUAL_FRAME_CHANGED", "input geometry differs from captured surface");
            }
        }

        String[] argv;
        if ("tap".equals(kind)) {
            int x = coordinate(parsed, "x");
            int y = coordinate(parsed, "y");
            argv = ShellCommands.inputTapArgv(displayId, x, y);
        } else if ("swipe".equals(kind)) {
            int x1 = coordinate(parsed, "x1");
            int y1 = coordinate(parsed, "y1");
            int x2 = coordinate(parsed, "x2");
            int y2 = coordinate(parsed, "y2");
            int duration = parsed.optionalInt("durationMs", 300);
            argv = ShellCommands.inputSwipeArgv(displayId, x1, y1, x2, y2, duration);
        } else if ("key".equals(kind)) {
            int keyCode = parsed.requireInt("keyCode");
            if (keyCode < 0 || keyCode > 1000) {
                throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "keyCode");
            }
            argv = ShellCommands.inputKeyArgv(displayId, keyCode);
        } else if ("text".equals(kind)) {
            String text = parsed.requireString("text");
            argv = ShellCommands.inputTextArgv(displayId, text);
        } else {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "kind");
        }
        return runInput(kind, displayId, argv);
    }

    /** Only input() reaches execution, after its live/thread, binding and argument checks. */
    private JSONObject runInput(String kind, int displayId, String[] argv) throws OwnerException {
        if (finishing || mutationUncertain) throw new OwnerException("SESSION_FINISHING");
        // A nonzero exit, timeout or exception cannot prove that no events were injected. Arm
        // before starting the child and retain the latch on EVERY error, including reply creation.
        // There is deliberately no finally-reset: a later status must forbid automatic replay.
        finishing = true;
        mutationUncertain = true;
        OwnerShell.Result result = inputAccess.run(argv);
        if (!result.success()) {
            throw new OwnerException(OwnerProtocol.ERROR_INPUT_FAILED, result.summary());
        }
        JSONObject out;
        try {
            out = inputAccess.response();
            out.put("executed", true);
            out.put("kind", kind);
            out.put("displayId", displayId);
            out.put("exitCode", result.exitCode);
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "input");
        }
        finishing = false;
        mutationUncertain = false;
        return out;
    }

    public JSONObject snapshot(JSONObject request) throws OwnerException {
        requireLive(); optionalDisplay(wrap(request));
        OwnerProtocol.Request parsed = wrap(request);
        boolean include = parsed.optionalBoolean("include", true);
        int maxBytes = parsed.optionalInt("maxBytes", MAX_SNAPSHOT_BYTES);
        if (maxBytes <= 0) {
            maxBytes = MAX_SNAPSHOT_BYTES;
        }
        maxBytes = Math.min(maxBytes, MAX_SNAPSHOT_BYTES);
        // Drain a pending frame now, not after this command returns to the listener's looper.
        // Metadata-only requests acquire images but never copy or encode their pixels.
        frames.refresh(created.reader);
        if (!frames.hasFrame()) {
            throw new OwnerException(OwnerProtocol.ERROR_NO_FRAME, "no frame");
        }
        JSONObject out = new JSONObject();
        try {
            out.put("displayId", created.displayId);
            out.put("frameCount", frames.frameCount());
            out.put("timestampNs", frames.latestTimestampNs());
            out.put("format", "png");
            if (include) {
                OwnerFrameStore.Snapshot snapshot = frames.encodePng(maxBytes);
                out.put("frameCount", snapshot.frameCount);
                out.put("timestampNs", snapshot.timestampNs);
                out.put("width", snapshot.width);
                out.put("height", snapshot.height);
                out.put("bytes", snapshot.png.length);
                out.put("data", android.util.Base64.encodeToString(snapshot.png,
                        android.util.Base64.NO_WRAP));
            } else {
                out.put("width", frames.frameWidth());
                out.put("height", frames.frameHeight());
            }
        } catch (OwnerException ex) {
            throw ex;
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "snapshot");
        }
        return out;
    }

    public JSONObject handoff(JSONObject request) throws OwnerException {
        requireLive(); optionalDisplay(wrap(request));
        if(finishing)throw new OwnerException("HANDOFF_ALREADY_ATTEMPTED");
        try {
            JSONObject out=OwnerHandoff.move(created.displayId,created.uniqueId,owned,request.optJSONArray("taskIds"));
            finishing=true; handoffComplete=true;
            handoffCleanupOnly=out.optBoolean("cleanupOnly",false);
            return out;
        } catch(OwnerHandoff.HandoffFailure ex) {
            if(ex.sideEffectsAttempted()) {
                // The anchor launch (the first side effect) may already be applied: this session is
                // now uncertain and is never replayed automatically.
                finishing=true; mutationUncertain=true;
            }
            // A preflight-only failure leaves finishing false so a deliberate retry is still possible.
            throw ex;
        } catch(OwnerException ex) {
            finishing=true; mutationUncertain=true;
            throw ex;
        } catch(Throwable ex) {
            finishing=true; mutationUncertain=true;
            throw new OwnerException("HANDOFF_UNCERTAIN", "owner:"+ex.getClass().getSimpleName());
        }
    }

    public JSONObject release(JSONObject request) throws OwnerException {
        requireLive(); optionalDisplay(wrap(request));
        if(releaseAttempted)throw new OwnerException("RELEASE_ALREADY_ATTEMPTED");
        if(!owned.isEmpty()&&!handoffComplete)throw new OwnerException("HANDOFF_REQUIRED");
        SourceProbe source = probeSource();
        if (!source.known()) {
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_STATE_UNKNOWN, source.errorCode);
        }
        if (source.taskCount != 0) {
            throw new OwnerException(OwnerProtocol.ERROR_SOURCE_NOT_EMPTY,
                    "tasks on source=" + source.taskCount);
        }
        releaseAttempted=true;
        try {
            created.display.getClass().getMethod("release").invoke(created.display);
            Class<?> c=Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object g=c.getMethod("getInstance").invoke(null);
            if(c.getMethod("getDisplayInfo",int.class).invoke(g,created.displayId)!=null)throw new IllegalStateException("display remains");
        } catch(Exception e) {throw new OwnerException("RELEASE_UNCERTAIN",e.getClass().getSimpleName());}
        // Verification, not reader cleanup or reply delivery, is the terminal authority.
        // Preserve it even if teardown throws: the dispatcher/IPC must still end this owner.
        released = true;
        try {
            frames.clear();
        } finally {
            try {
                created.reader.setOnImageAvailableListener(null, null);
            } finally {
                created.reader.close();
            }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("released", true);
            out.put("displayId", created.displayId);
            out.put("uniqueId", created.uniqueId);
        } catch (JSONException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "release");
        }
        return out;
    }

    /** Read the bound display's current logical input geometry, never creation metadata. */
    private int[] currentGeometry() throws OwnerException {
        try {
            return inputAccess.geometry(created.displayId, created.uniqueId);
        } catch (Exception ex) {
            throw new OwnerException("VIRTUAL_FRAME_UNKNOWN", "logical geometry unavailable");
        }
    }

    private void requireLive() throws OwnerException {
        try { inputAccess.verifyDisplay(created.displayId,created.uniqueId); }
        catch(Exception e) {throw new OwnerException("DISPLAY_REBOUND");}
        if (released) {
            throw new OwnerException(OwnerProtocol.ERROR_ALREADY_RELEASED, "released");
        }
        if (handler.getLooper() != Looper.myLooper()) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "owner thread");
        }
    }

    private int optionalDisplay(OwnerProtocol.Request parsed) throws OwnerException {
        int displayId = parsed.optionalInt("displayId", created.displayId);
        if (displayId != created.displayId) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_REBOUND,
                    "display id is bound to this owner");
        }
        return displayId;
    }

    private static int coordinate(OwnerProtocol.Request parsed, String key) throws OwnerException {
        int value = parsed.requireInt(key);
        if (value < 0 || value > MAX_COORDINATE) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "coordinate " + key);
        }
        return value;
    }

    private static List<String> categories(OwnerProtocol.Request parsed) throws OwnerException {
        List<String> out = new java.util.ArrayList<String>();
        Object raw = parsed.payload.opt("categories");
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof JSONArray)) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "categories");
        }
        JSONArray array = (JSONArray) raw;
        if (array.length() > 8) {
            throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "categories");
        }
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (!(value instanceof String) || !OwnerProtocol.isSafeIdentifier((String) value)) {
                throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "category");
            }
            out.add((String) value);
        }
        return out;
    }

    private OwnerProtocol.Request wrap(JSONObject request) throws OwnerException {
        if (request == null) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "request");
        }
        try {
            return OwnerProtocol.parseRequest(request.toString());
        } catch (OwnerProtocolException ex) {
            throw new OwnerException(ex.code, ex.getMessage());
        }
    }

    private SourceProbe probeSource() {
        try {
            List<OwnerTaskInventory.Seen> onDisplay =
                    inputAccess.sourceTasks(created.displayId);
            registry.retain(onDisplay);
            return SourceProbe.known(onDisplay.size());
        } catch (OwnerException ex) {
            return SourceProbe.unknown(ex.code);
        }
    }

    private static boolean containsError(String text) {
        if (text == null) {
            return false;
        }
        return text.indexOf("Error:") >= 0 || text.indexOf("Error ") >= 0
                || text.indexOf("Exception") >= 0 || text.indexOf("Permission Denial") >= 0;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() > MAX_OUTPUT_CHARS) {
            return flat.substring(0, MAX_OUTPUT_CHARS);
        }
        return flat;
    }

    private static JSONArray stringArray(String[] values) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < values.length; i++) {
            array.put(values[i]);
        }
        return array;
    }

    private static JSONArray intArray(List<Integer> values) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < values.size(); i++) {
            array.put(values.get(i).intValue());
        }
        return array;
    }

    private static String newUniqueId() {
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        StringBuilder sb = new StringBuilder("vd-owner-");
        sb.append(Process.myPid()).append('-');
        for (int i = 0; i < random.length; i++) {
            sb.append(Character.forDigit((random[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(random[i] & 0xF, 16));
        }
        return sb.toString();
    }

    private static final class SourceProbe {
        final String state;
        final int taskCount;
        final String errorCode;

        private SourceProbe(String state, int taskCount, String errorCode) {
            this.state = state;
            this.taskCount = taskCount;
            this.errorCode = errorCode;
        }

        static SourceProbe known(int taskCount) {
            return new SourceProbe(taskCount == 0 ? "empty" : "occupied", taskCount, null);
        }

        static SourceProbe unknown(String errorCode) {
            return new SourceProbe("unknown", -1, errorCode);
        }

        boolean known() {
            return errorCode == null;
        }
    }
}
