package app.daiyu.android.vd.runtime;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Offline gates + real store, including real dispatcher/input/release algorithms.
 * Uses synthetic display reads/command results and a Robolectric virtual display for release.
 * No socket, external process, physical-device display or shell is started.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36}, manifest = Config.NONE)
public class OwnerReceiptCoordinatorTest {
    private final OwnerReceiptTestPlatform platform = new OwnerReceiptTestPlatform();
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final List<OwnerReceiptStore> stores = new ArrayList<OwnerReceiptStore>();
    private static final String SECRET = "private-bootstrap-secret";

    @After public void closeStores() throws Exception {
        // Test backends are synchronous and quiescent; production closes only after verified stop.
        for (OwnerReceiptStore store : stores) store.close();
    }

    private OwnerReceiptStore store(OwnerReceiptStore.WriteFault fault) throws Exception {
        Path root = temporary.newFolder().toPath();
        platform.setPermissions(root, PosixFilePermissions.fromString("rwx------"));
        OwnerReceiptStore store = platform.open(root, fault);
        stores.add(store);
        return store;
    }

    private static JSONObject bindingJson() throws Exception {
        return new JSONObject().put("session", "s").put("task", "t").put("conversation", "c")
                .put("run", "r").put("assistantMessage", "m").put("runOwner", "o");
    }

    private static OwnerOperationIdentity.RunBinding binding() {
        return OwnerOperationIdentity.bindingFromTrustedHost("s", "t", "c", "r", "m", "o");
    }

    private static JSONObject envelope(String op) throws Exception {
        return new JSONObject().put("v", OwnerProtocol.VERSION).put("op", op)
                .put("token", SECRET).put("generation", "generation_1");
    }

    private static OwnerProtocol.Request initialize() throws Exception {
        return parse(envelope("initialize").put("binding", bindingJson()));
    }

    private static JSONObject keyParameters() throws Exception {
        return new JSONObject().put("kind", "key").put("keyCode", 4);
    }

    private static OwnerProtocol.Request operation(String op, String id, JSONObject parameters) throws Exception {
        return parse(envelope(op).put("operationId", id).put("parameters", parameters));
    }

    private static OwnerProtocol.Request receipt(String op, String id, JSONObject parameters) throws Exception {
        JSONObject object = envelope("receipt").put("binding", bindingJson());
        if (op != null) object.put("operation", op).put("operationId", id).put("parameters", parameters);
        return parse(object);
    }

    private static OwnerProtocol.Request parse(JSONObject object) throws Exception {
        return OwnerProtocol.parseWireRequest(object.toString());
    }

    private static OwnerOperationIdentity keyIdentity(String id) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("displayId", null);
        values.put("kind", "key");
        values.put("keyCode", 4);
        return new OwnerOperationIdentity("generation_1", id, "input", binding(), values);
    }

    private static final class Backend implements OwnerReceiptCoordinator.Backend {
        final OwnerReceiptStore store;
        int calls;
        boolean fail;
        boolean released;
        Backend(OwnerReceiptStore store) { this.store = store; }
        @Override public JSONObject dispatch(OwnerProtocol.Request request) {
            calls++;
            assertEquals("", request.token);
            assertFalse(request.payload.has("token"));
            assertFalse(request.payload.has("binding"));
            try {
                if (request.op.equals("input")) {
                    assertEquals(OwnerReceiptStore.State.STARTED, store.lookup(keyIdentity("input_1")).state);
                }
                if (request.op.equals("release")) released = true;
                return fail ? OwnerProtocol.fail(request.op, "INPUT_FAILED", SECRET)
                        : OwnerProtocol.ok(request.op, new JSONObject().put("message", SECRET));
            } catch (Exception error) { throw new AssertionError(error); }
        }
        @Override public boolean shouldStop() { return released; }
        @Override public int displayId() { return 7; }
        @Override public String uniqueId() { return "synthetic-display"; }
    }

    private OwnerReceiptCoordinator coordinator(OwnerReceiptStore store, Backend backend, int[] creates) {
        return new OwnerReceiptCoordinator(store, () -> {
            creates[0]++;
            try {
                assertEquals(OwnerReceiptStore.State.STARTED,
                        store.lookupGeneration("generation_1", binding()).state);
            } catch (IOException error) { throw new AssertionError(error); }
            return backend;
        });
    }

    @Test public void bootstrapAndReceiptMissDoNotCreateOrWriteGeneration() throws Exception {
        final int[] writes = {0};
        OwnerReceiptStore store = store(point -> { if (point == OwnerReceiptStore.WritePoint.BEFORE_WRITE) writes[0]++; });
        Backend backend = new Backend(store);
        int[] creates = {0};
        OwnerReceiptCoordinator coordinator = coordinator(store, backend, creates);
        assertEquals(0, creates[0]);
        JSONObject miss = coordinator.dispatch(receipt(null, null, null));
        assertTrue(miss.getBoolean("receiptOnly"));
        assertFalse(miss.getBoolean("found"));
        assertEquals(0, writes[0]);
        assertEquals(0, creates[0]);
        assertEquals("INITIALIZE_REQUIRED", coordinator.dispatch(operation("input", "input_1", keyParameters())).getString("error"));
        assertEquals(0, backend.calls);
        assertEquals(0, writes[0]);
    }

    @Test public void freshStartedPrecedesCreateAndEachOriginalCallAndDuplicatesDoNotReplay() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        Backend backend = new Backend(store);
        int[] creates = {0};
        OwnerReceiptCoordinator coordinator = coordinator(store, backend, creates);
        assertTrue(coordinator.dispatch(initialize()).getBoolean("initialized"));
        assertEquals(1, creates[0]);
        assertTrue(coordinator.dispatch(initialize()).getBoolean("receiptOnly"));
        assertEquals(1, creates[0]);
        JSONObject first = coordinator.dispatch(operation("input", "input_1", keyParameters()));
        assertEquals("SUCCEEDED", first.getJSONObject("receipt").getString("state"));
        JSONObject duplicate = coordinator.dispatch(operation("input", "input_1", keyParameters()));
        assertTrue(duplicate.getBoolean("receiptOnly"));
        assertFalse(duplicate.getBoolean("executed"));
        assertEquals(1, backend.calls);
        JSONObject read = coordinator.dispatch(receipt("input", "input_1", keyParameters()));
        assertEquals(first.getJSONObject("receipt").toString(), read.getJSONObject("receipt").toString());
        assertEquals(1, backend.calls);
        assertFalse(store.lookup(keyIdentity("input_1")).resultJson.contains(SECRET));
    }

    @Test public void changedParameterOrBindingConflictsWithoutExecution() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        Backend backend = new Backend(store);
        OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
        coordinator.dispatch(initialize());
        coordinator.dispatch(operation("input", "input_1", keyParameters()));
        JSONObject conflict = coordinator.dispatch(operation("input", "input_1", keyParameters().put("keyCode", 5)));
        assertFalse(conflict.getBoolean("ok"));
        JSONObject lookup = envelope("receipt").put("binding", bindingJson().put("runOwner", "other"));
        assertFalse(coordinator.dispatch(parse(lookup)).getBoolean("ok"));
        assertEquals(1, backend.calls);
    }

    @Test public void unknownFailureIsImmutableAndNewIdsCannotBypassFailureFence() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        Backend backend = new Backend(store);
        backend.fail = true;
        OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
        coordinator.dispatch(initialize());
        assertFalse(coordinator.dispatch(operation("input", "input_1", keyParameters())).getBoolean("ok"));
        backend.fail = false;
        assertEquals("UNKNOWN", coordinator.dispatch(operation("input", "input_1", keyParameters()))
                .getJSONObject("receipt").getString("state"));
        assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation("input", "new_id", keyParameters())).getString("error"));
        assertEquals(1, backend.calls);
        assertFalse(store.lookup(keyIdentity("input_1")).resultJson.contains(SECRET));
    }

    @Test public void businessFailureAllowsOneIndependentHandoffThenReleaseWithoutOpeningMutationFence() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        Backend backend = new Backend(store);
        OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
        coordinator.dispatch(initialize());
        backend.fail = true;
        coordinator.dispatch(operation("input", "input_1", keyParameters()));
        backend.fail = false;
        assertTrue(coordinator.dispatch(operation("handoff", "handoff_1", new JSONObject())).getBoolean("ok"));
        assertTrue(coordinator.dispatch(operation("handoff", "handoff_1", new JSONObject())).getBoolean("receiptOnly"));
        assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation("handoff", "handoff_2", new JSONObject())).getString("error"));
        assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation("input", "input_2", keyParameters())).getString("error"));
        assertTrue(coordinator.dispatch(operation("release", "release_1", new JSONObject())).getBoolean("ok"));
        assertTrue(coordinator.dispatch(operation("release", "release_1", new JSONObject())).getBoolean("receiptOnly"));
        assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation("release", "release_2", new JSONObject())).getString("error"));
        assertEquals(3, backend.calls);
        assertTrue(coordinator.shouldStop());
    }

    @Test public void startedAndUnknownCleanupCannotBeRetriedWithNewId() throws Exception {
        for (String op : new String[]{"handoff", "release"}) {
            OwnerReceiptStore store = store(point -> {});
            final OwnerReceiptCoordinator[] coordinator = {null};
            final int[] calls = {0};
            final Throwable[] callbackFailure = {null};
            OwnerReceiptCoordinator.Backend backend = new OwnerReceiptCoordinator.Backend() {
                @Override public JSONObject dispatch(OwnerProtocol.Request request) {
                    calls[0]++;
                    try {
                        // Reentrant observation models an outstanding STARTED cleanup, before final.
                        assertEquals("STARTED", coordinator[0].dispatch(receipt(op, "cleanup_1", new JSONObject()))
                                .getJSONObject("receipt").getString("state"));
                        assertTrue(coordinator[0].dispatch(operation(op, "cleanup_1", new JSONObject())).getBoolean("receiptOnly"));
                        assertEquals("SESSION_UNCERTAIN", coordinator[0].dispatch(operation(op, "cleanup_2", new JSONObject())).getString("error"));
                    } catch (Throwable error) { callbackFailure[0] = error; }
                    return OwnerProtocol.fail(request.op, "CLEANUP_UNCERTAIN", "unknown");
                }
                @Override public boolean shouldStop() { return false; }
                @Override public int displayId() { return 7; }
                @Override public String uniqueId() { return "synthetic-display"; }
            };
            coordinator[0] = new OwnerReceiptCoordinator(store, () -> backend);
            coordinator[0].dispatch(initialize());
            assertFalse(coordinator[0].dispatch(operation(op, "cleanup_1", new JSONObject())).getBoolean("ok"));
            assertNull(callbackFailure[0]);
            assertEquals("UNKNOWN", coordinator[0].dispatch(operation(op, "cleanup_1", new JSONObject()))
                    .getJSONObject("receipt").getString("state"));
            assertEquals("SESSION_UNCERTAIN", coordinator[0].dispatch(operation(op, "cleanup_2", new JSONObject())).getString("error"));
            assertEquals("SESSION_UNCERTAIN", coordinator[0].dispatch(operation("input", "input_2", keyParameters())).getString("error"));
            assertFalse(coordinator[0].dispatch(operation(op, "cleanup_1", new JSONObject().put("displayId", 7))).getBoolean("ok"));
            assertEquals(1, calls[0]);
            assertFalse(coordinator[0].shouldStop());
        }
    }

    @Test public void allPersistedGenerationStatesAreReceiptOnlyNeverNewCreate() throws Exception {
        for (OwnerReceiptStore.State state : OwnerReceiptStore.State.values()) {
            OwnerReceiptStore store = store(point -> {});
            OwnerReceiptStore.Reservation reservation = store.reserveGeneration("generation_1", binding());
            if (state != OwnerReceiptStore.State.RESERVED) store.start(reservation.newClaim);
            if (state.isFinal()) store.complete(reservation.newClaim, state, new LinkedHashMap<String, Object>());
            // New coordinator has no imported live claim, even with the same still-open store.
            int[] creates = {0};
            OwnerReceiptCoordinator coordinator = coordinator(store, new Backend(store), creates);
            JSONObject response = coordinator.dispatch(initialize());
            assertTrue(response.getBoolean("receiptOnly"));
            assertEquals(state.name(), response.getJSONObject("receipt").getString("state"));
            assertEquals(0, creates[0]);
        }
    }

    @Test public void generationReserveAndStartFailuresNeverReachCreate() throws Exception {
        for (int failAt : new int[]{0, 1}) {
            final int[] writes = {0};
            OwnerReceiptStore store = store(point -> {
                if (point == OwnerReceiptStore.WritePoint.BEFORE_WRITE && writes[0]++ == failAt) throw new IOException("fault");
            });
            int[] creates = {0};
            OwnerReceiptCoordinator coordinator = coordinator(store, new Backend(store), creates);
            assertEquals("RECEIPT_UNAVAILABLE", coordinator.dispatch(initialize()).getString("error"));
            assertEquals(0, creates[0]);
            assertFalse(coordinator.shouldStop());
        }
    }

    @Test public void operationReserveStartAndFinalFailuresCannotExecuteAgain() throws Exception {
        for (int failAt : new int[]{0, 1, 2}) {
            final int[] remaining = {-1};
            OwnerReceiptStore store = store(point -> {
                if (point == OwnerReceiptStore.WritePoint.BEFORE_WRITE && remaining[0] >= 0 && remaining[0]-- == 0) {
                    throw new IOException("fault");
                }
            });
            Backend backend = new Backend(store);
            OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
            coordinator.dispatch(initialize());
            remaining[0] = failAt;
            JSONObject first = coordinator.dispatch(operation("input", "input_1", keyParameters()));
            assertEquals("RECEIPT_UNAVAILABLE", first.getString("error"));
            assertEquals(failAt == 2 ? 1 : 0, backend.calls);
            coordinator.dispatch(operation("input", "input_1", keyParameters()));
            assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation("handoff", "handoff_1", new JSONObject())).getString("error"));
            assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation("release", "release_1", new JSONObject())).getString("error"));
            assertEquals(failAt == 2 ? 1 : 0, backend.calls);
            assertFalse(coordinator.shouldStop());
        }
    }

    @Test public void cleanupReserveStartOrFinalFailureNeverAllowsAnotherCleanupId() throws Exception {
        for (String op : new String[]{"handoff", "release"}) {
            for (int failAt : new int[]{0, 1, 2}) {
                final int[] remaining = {-1};
                OwnerReceiptStore store = store(point -> {
                    if (point == OwnerReceiptStore.WritePoint.BEFORE_WRITE && remaining[0] >= 0 && remaining[0]-- == 0)
                        throw new IOException("fault");
                });
                Backend backend = new Backend(store);
                OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
                coordinator.dispatch(initialize());
                remaining[0] = failAt;
                assertEquals("RECEIPT_UNAVAILABLE", coordinator.dispatch(operation(op, "cleanup_1", new JSONObject())).getString("error"));
                coordinator.dispatch(operation(op, "cleanup_1", new JSONObject()));
                assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation(op, "cleanup_2", new JSONObject())).getString("error"));
                assertEquals(failAt == 2 ? 1 : 0, backend.calls);
                assertFalse(coordinator.shouldStop());
            }
        }
    }

    @Test public void poisonedStoreCannotBeBypassedByFirstCleanupEvenWithoutCoordinatorFailure() throws Exception {
        final boolean[] fail = {false};
        OwnerReceiptStore store = store(point -> {
            if (fail[0] && point == OwnerReceiptStore.WritePoint.BEFORE_WRITE) throw new IOException("fault");
        });
        Backend backend = new Backend(store);
        OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
        coordinator.dispatch(initialize());
        // Poison through another store call, not dispatch: the coordinator's terminal fence is not set yet.
        fail[0] = true;
        assertThrows(IOException.class, () -> store.reserveGeneration("other", binding()));
        fail[0] = false;
        assertEquals("RECEIPT_UNAVAILABLE", coordinator.dispatch(operation("release", "release_1", new JSONObject())).getString("error"));
        assertEquals(0, backend.calls);
        assertFalse(coordinator.shouldStop());
    }

    @Test public void releaseFinalsPrecedeStopAndStoreClose() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        Backend backend = new Backend(store);
        OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
        assertThrows(IOException.class, coordinator::closeAfterStop);
        coordinator.dispatch(initialize());
        coordinator.dispatch(operation("release", "release_1", new JSONObject()));
        assertTrue(coordinator.shouldStop());
        JSONObject lookup = coordinator.dispatch(receipt("release", "release_1", new JSONObject()));
        assertEquals("SUCCEEDED", lookup.getJSONObject("receipt").getString("state"));
        assertEquals(OwnerReceiptStore.State.SUCCEEDED, store.lookupGeneration("generation_1", binding()).state);
        coordinator.closeAfterStop();
        assertThrows(OwnerReceiptStore.StoreException.class, () -> store.lookupGeneration("generation_1", binding()));
    }

    @Test public void releaseOperationOrGenerationFinalLossNeverAuthorizesExitOrReplay() throws Exception {
        for (int finalIndex : new int[]{2, 3}) {
            final int[] remaining = {-1};
            OwnerReceiptStore store = store(point -> {
                if (point == OwnerReceiptStore.WritePoint.BEFORE_WRITE && remaining[0] >= 0 && remaining[0]-- == 0) throw new IOException("fault");
            });
            Backend backend = new Backend(store);
            OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
            coordinator.dispatch(initialize());
            remaining[0] = finalIndex;
            assertEquals("RECEIPT_UNAVAILABLE", coordinator.dispatch(operation("release", "release_1", new JSONObject())).getString("error"));
            assertTrue(backend.released);
            assertFalse(coordinator.shouldStop());
            assertThrows(IOException.class, coordinator::closeAfterStop);
            coordinator.dispatch(operation("release", "release_1", new JSONObject()));
            assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation("release", "release_2", new JSONObject())).getString("error"));
            assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation("handoff", "handoff_1", new JSONObject())).getString("error"));
            assertEquals(1, backend.calls);
        }
    }

    @Test public void failedLookupDoesNotFenceAHealthyLiveGeneration() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        Backend backend = new Backend(store);
        OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
        coordinator.dispatch(initialize());
        JSONObject wrong = envelope("receipt").put("binding", bindingJson().put("task", "wrong"));
        assertFalse(coordinator.dispatch(parse(wrong)).getBoolean("ok"));
        assertTrue(coordinator.dispatch(operation("input", "input_1", keyParameters())).getBoolean("ok"));
        assertEquals(1, backend.calls);
    }

    @Test public void reopenedStoreSupportsOnlyHistoricalReceiptLookupNotOwnerRecreation() throws Exception {
        Path root = temporary.newFolder().toPath();
        platform.setPermissions(root, PosixFilePermissions.fromString("rwx------"));
        OwnerReceiptStore previous = platform.open(root);
        stores.add(previous);
        OwnerReceiptStore.Reservation generation = previous.reserveGeneration("generation_1", binding());
        previous.start(generation.newClaim);
        OwnerReceiptStore.Reservation operation = previous.reserveOperation(generation.newClaim, keyIdentity("input_1"));
        previous.start(operation.newClaim); // crash-equivalent unresolved STARTED, not NotExecuted
        previous.close();
        final int[] writes = {0};
        OwnerReceiptStore reopened = platform.open(root, point -> {
            if (point == OwnerReceiptStore.WritePoint.BEFORE_WRITE) writes[0]++;
        });
        stores.add(reopened);
        int[] creates = {0};
        Backend backend = new Backend(reopened);
        OwnerReceiptCoordinator coordinator = coordinator(reopened, backend, creates);
        JSONObject found = coordinator.dispatch(receipt("input", "input_1", keyParameters()));
        assertEquals("STARTED", found.getJSONObject("receipt").getString("state"));
        assertTrue(coordinator.dispatch(initialize()).getBoolean("receiptOnly"));
        assertEquals("INITIALIZE_REQUIRED", coordinator.dispatch(operation("input", "input_1", keyParameters())).getString("error"));
        assertEquals("INITIALIZE_REQUIRED", coordinator.dispatch(operation("release", "release_1", new JSONObject())).getString("error"));
        assertEquals(0, writes[0]);
        assertEquals(0, creates[0]);
        assertEquals(0, backend.calls);
    }

    @Test public void creationFailureBecomesUnknownWithoutRetryOrExit() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        int[] creates = {0};
        OwnerReceiptCoordinator coordinator = new OwnerReceiptCoordinator(store, () -> {
            creates[0]++;
            throw new OwnerException("DISPLAY_NOT_READY", "synthetic failure");
        });
        assertEquals("INITIALIZATION_UNKNOWN", coordinator.dispatch(initialize()).getString("error"));
        assertEquals("UNKNOWN", coordinator.dispatch(initialize()).getJSONObject("receipt").getString("state"));
        assertEquals(1, creates[0]);
        assertFalse(coordinator.shouldStop());
    }

    @Test public void everyBusinessOperationGetsAReceiptAndVerifiedReleaseSurvivesBodyFailure() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        Backend backend = new Backend(store);
        OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
        coordinator.dispatch(initialize());
        String[] operations = {"status", "launch", "input", "snapshot", "handoff", "release"};
        JSONObject[] parameters = {new JSONObject(), new JSONObject().put("component", "pkg/.Main"),
                keyParameters(), new JSONObject().put("include", false),
                new JSONObject().put("taskIds", new org.json.JSONArray()), new JSONObject()};
        for (int i = 0; i < operations.length; i++) {
            if (operations[i].equals("release")) backend.fail = true; // released but response cleanup failed
            String id = operations[i] + "_1";
            JSONObject first = coordinator.dispatch(operation(operations[i], id, parameters[i]));
            assertEquals(operations[i].equals("release") ? "UNKNOWN" : "SUCCEEDED",
                    first.getJSONObject("receipt").getString("state"));
            coordinator.dispatch(operation(operations[i], id, parameters[i]));
            assertEquals(i + 1, backend.calls);
        }
        assertTrue(coordinator.shouldStop());
        assertEquals(OwnerReceiptStore.State.SUCCEEDED, store.lookupGeneration("generation_1", binding()).state);
    }

    /** Real dispatcher/owner/release with Robolectric display resources; no device or shell. */
    private static final class EmptyDisplay implements AutoCloseable {
        final android.media.ImageReader reader;
        final android.hardware.display.VirtualDisplay display;
        final VirtualDisplayOwner owner;
        int releaseProbes;

        EmptyDisplay(OwnerReceiptStore store) throws Exception {
            reader = android.media.ImageReader.newInstance(640, 480, android.graphics.PixelFormat.RGBA_8888, 3);
            android.hardware.display.DisplayManager manager = (android.hardware.display.DisplayManager)
                    org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.Context.DISPLAY_SERVICE);
            display = manager.createVirtualDisplay("receipt-test", 640, 480, 160, reader.getSurface(),
                    VirtualDisplayFactory.DEFAULT_FLAGS);
            assertNotNull(display);
            int id = display.getDisplay().getDisplayId();
            VirtualDisplayOwner.InputAccess access = new VirtualDisplayOwner.InputAccess() {
                @Override void verifyDisplay(int actualId, String uniqueId) { assertEquals(id, actualId); }
                @Override int[] geometry(int actualId, String uniqueId) { return new int[]{640, 480}; }
                @Override List<OwnerTaskInventory.Seen> sourceTasks(int actualId) {
                    releaseProbes++;
                    Map<String, Object> parameters = new LinkedHashMap<String, Object>();
                    parameters.put("displayId", null);
                    try {
                        assertEquals(OwnerReceiptStore.State.STARTED, store.lookup(new OwnerOperationIdentity(
                                "generation_1", "release_1", "release", binding(), parameters)).state);
                    } catch (IOException error) { throw new AssertionError(error); }
                    return new ArrayList<OwnerTaskInventory.Seen>();
                }
                @Override java.util.Map<Integer, Object> statusRoots() { return new LinkedHashMap<Integer, Object>(); }
                @Override OwnerShell.Result run(String[] argv) { throw new AssertionError("no shell command expected"); }
            };
            owner = new VirtualDisplayOwner(new VirtualDisplayFactory.Created(display, reader, reader.getSurface(),
                    id, "synthetic-display", "test", 640, 480, 160, VirtualDisplayFactory.DEFAULT_FLAGS),
                    new OwnerFrameStore(), new Handler(Looper.getMainLooper()), access);
        }

        @Override public void close() { display.release(); reader.close(); }
    }

    @Test public void realEmptyDisplayMissingLaunchTargetStillAllowsFirstReceiptedRelease() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        try (EmptyDisplay empty = new EmptyDisplay(store)) {
            OwnerReceiptCoordinator coordinator = new OwnerReceiptCoordinator(store, () -> new OwnerCommandDispatcher(empty.owner));
            coordinator.dispatch(initialize());
            JSONObject failed = coordinator.dispatch(operation("launch", "launch_1", new JSONObject()));
            assertFalse(failed.getBoolean("ok"));
            assertEquals(OwnerProtocol.ERROR_PROTOCOL, failed.getString("error"));
            assertEquals("UNKNOWN", failed.getJSONObject("receipt").getString("state"));
            assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation("launch", "launch_2", new JSONObject())).getString("error"));
            assertEquals("GENERATION_MISMATCH", coordinator.dispatch(parse(envelope("release")
                    .put("generation", "other").put("operationId", "release_1").put("parameters", new JSONObject()))).getString("error"));
            JSONObject released = coordinator.dispatch(operation("release", "release_1", new JSONObject()));
            assertTrue(released.toString(), released.getBoolean("ok"));
            assertEquals("SUCCEEDED", released.getJSONObject("receipt").getString("state"));
            assertEquals(1, empty.releaseProbes);
            assertTrue(empty.owner.isReleased());
            assertTrue(coordinator.shouldStop());
            assertTrue(coordinator.dispatch(operation("release", "release_1", new JSONObject())).getBoolean("receiptOnly"));
            assertEquals("SESSION_UNCERTAIN", coordinator.dispatch(operation("release", "release_2", new JSONObject())).getString("error"));
            assertEquals(1, empty.releaseProbes);
            assertEquals(OwnerReceiptStore.State.SUCCEEDED, store.lookupGeneration("generation_1", binding()).state);
        }
    }

    @Test public void successfulReplyLossThenAuthenticatedReceiptAndControlledCleanupNeverReplaysMutation() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        Backend backend = new Backend(store);
        OwnerReceiptCoordinator coordinator = coordinator(store, backend, new int[]{0});
        OwnerIpcServer.MutationFence fence = new OwnerIpcServer.MutationFence();
        coordinator.dispatch(initialize());
        OwnerProtocol.Request input = operation("input", "input_1", keyParameters());
        assertTrue(authenticatedDispatch(coordinator, fence, input, 10000).getBoolean("ok"));
        // Same event used by Connection's failed write/flush path; no real socket is opened here.
        fence.responseLost(input.op);
        assertTrue(fence.isUncertain());
        assertEquals("SESSION_UNCERTAIN", authenticatedDispatch(coordinator, fence, input, 10000).getString("error"));
        assertEquals("SESSION_UNCERTAIN", authenticatedDispatch(coordinator, fence,
                operation("input", "input_2", keyParameters()), 10000).getString("error"));
        JSONObject lookup = authenticatedDispatch(coordinator, fence, receipt("input", "input_1", keyParameters()), 10000);
        assertEquals("SUCCEEDED", lookup.getJSONObject("receipt").getString("state"));
        OwnerProtocol.Request handoff = operation("handoff", "handoff_1", new JSONObject());
        assertEquals(OwnerProtocol.ERROR_AUTH_REJECTED, authenticatedDispatch(coordinator, fence, handoff, 2000).getString("error"));
        assertEquals(OwnerProtocol.ERROR_AUTH_REJECTED, authenticatedDispatch(coordinator, fence,
                parse(envelope("release").put("token", "wrong").put("operationId", "release_1")
                        .put("parameters", new JSONObject())), 10000).getString("error"));
        assertEquals(1, backend.calls);
        assertTrue(authenticatedDispatch(coordinator, fence, handoff, 10000).getBoolean("ok"));
        fence.responseLost("handoff");
        assertTrue(authenticatedDispatch(coordinator, fence, handoff, 10000).getBoolean("receiptOnly"));
        assertEquals("SESSION_UNCERTAIN", authenticatedDispatch(coordinator, fence,
                operation("handoff", "handoff_2", new JSONObject()), 10000).getString("error"));
        assertTrue(authenticatedDispatch(coordinator, fence,
                operation("release", "release_1", new JSONObject()), 10000).getBoolean("ok"));
        assertEquals(3, backend.calls);
        assertTrue(coordinator.shouldStop());
        assertTrue(fence.isUncertain()); // cleanup never clears the original transport fence
    }

    private static JSONObject authenticatedDispatch(OwnerReceiptCoordinator coordinator,
            OwnerIpcServer.MutationFence fence, OwnerProtocol.Request request, int peerUid) {
        if (!OwnerIpcServer.isAuthenticatedRequest(10000, peerUid, SECRET, request))
            return OwnerProtocol.fail(request.op, OwnerProtocol.ERROR_AUTH_REJECTED, "auth");
        if (!fence.allows(request.op)) return OwnerProtocol.fail(request.op, "SESSION_UNCERTAIN", "unknown");
        return coordinator.dispatch(request);
    }

    @Test public void realDispatcherEntersExistingPublicInputOnlyAfterStartedAndNeverReplays() throws Exception {
        OwnerReceiptStore store = store(point -> {});
        final int[] commands = {0};
        VirtualDisplayOwner.InputAccess access = new VirtualDisplayOwner.InputAccess() {
            @Override void verifyDisplay(int id, String uniqueId) { assertEquals(7, id); }
            @Override OwnerShell.Result run(String[] argv) throws OwnerException {
                try { assertEquals(OwnerReceiptStore.State.STARTED, store.lookup(keyIdentity("input_1")).state); }
                catch (IOException failure) { throw new AssertionError(failure); }
                commands[0]++;
                assertArrayEquals(ShellCommands.inputKeyArgv(7, 4), argv);
                return new OwnerShell.Result(0, "", "", false, false, true);
            }
        };
        VirtualDisplayOwner owner = new VirtualDisplayOwner(
                new VirtualDisplayFactory.Created(null, null, null, 7, "synthetic-display", "test",
                        640, 480, 160, VirtualDisplayFactory.DEFAULT_FLAGS),
                new OwnerFrameStore(), new Handler(Looper.getMainLooper()), access);
        OwnerReceiptCoordinator coordinator = new OwnerReceiptCoordinator(store, () -> new OwnerCommandDispatcher(owner));
        coordinator.dispatch(initialize());
        assertTrue(coordinator.dispatch(operation("input", "input_1", keyParameters())).getBoolean("ok"));
        coordinator.dispatch(operation("input", "input_1", keyParameters()));
        coordinator.dispatch(receipt("input", "input_1", keyParameters()));
        assertEquals(1, commands[0]);
    }
}
