package app.daiyu.android.vd.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Real store, offline coordinator: no root, socket, display, or process is started. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36}, manifest = Config.NONE)
public class OwnerUninitializedShutdownTest {
    private final OwnerReceiptTestPlatform platform = new OwnerReceiptTestPlatform();
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path root() throws Exception {
        Path path = temporary.newFolder().toPath();
        platform.setPermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }

    private static OwnerOperationIdentity.RunBinding binding() {
        return OwnerOperationIdentity.bindingFromTrustedHost("s", "t", "c", "r", "m", "o");
    }

    private static JSONObject envelope(String op) throws Exception {
        return new JSONObject().put("v", 2).put("op", op).put("token", "test-private-token");
    }

    private static OwnerProtocol.Request request(String op, String generation) throws Exception {
        JSONObject wire = envelope(op);
        if (generation != null) wire.put("generation", generation).put("binding", new JSONObject()
                .put("session", "s").put("task", "t").put("conversation", "c")
                .put("run", "r").put("assistantMessage", "m").put("runOwner", "o"));
        return OwnerProtocol.parseWireRequest(wire.toString());
    }

    private static OwnerProtocol.Request shutdown() throws Exception {
        return request("shutdown_uninitialized", null);
    }

    private static Map<String, String> bytes(Path root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile).sorted()::iterator) {
                result.put(root.relativize(path).toString(),
                        java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    @Test public void strictHostOnlyEnvelopeAndBothAuthenticationGates() throws Exception {
        OwnerProtocol.Request r = shutdown();
        assertFalse(OwnerProtocol.isBusinessOperation(r.op));
        assertTrue(OwnerIpcServer.isAuthenticatedRequest(10000, 10000, "test-private-token", r));
        assertFalse(OwnerIpcServer.isAuthenticatedRequest(10000, 10001, "test-private-token", r));
        assertFalse(OwnerIpcServer.isAuthenticatedRequest(10000, 10000, "wrong", r));
        for (String field : new String[]{"generation", "binding", "parameters", "operationId", "timeoutMs"}) {
            JSONObject wire = envelope("shutdown_uninitialized").put(field, "forbidden");
            assertThrows(OwnerProtocolException.class, () -> OwnerProtocol.parseWireRequest(wire.toString()));
        }
        assertThrows(OwnerProtocolException.class,
                () -> OwnerProtocol.businessParameters("shutdown_uninitialized", new JSONObject()));
    }

    @Test public void lookupOnlyShutdownClosesLockWithoutCreatingOrWritingAndFencesInitialize() throws Exception {
        Path root = root();
        AtomicInteger creates = new AtomicInteger(), writes = new AtomicInteger();
        OwnerReceiptStore store = platform.open(root, point -> writes.incrementAndGet());
        OwnerReceiptCoordinator c = new OwnerReceiptCoordinator(store, () -> {
            creates.incrementAndGet(); throw new AssertionError("must not create");
        });
        assertFalse(c.dispatch(request("receipt", "missing")).getBoolean("found"));
        assertFalse(c.shouldStop());
        assertThrows(IOException.class, c::closeAfterStop);
        JSONObject reply = c.dispatch(shutdown());
        assertEquals(5, reply.length());
        assertTrue(reply.getBoolean("ok"));
        assertFalse(reply.getBoolean("initialized"));
        assertTrue(reply.getBoolean("receiptsClosed"));
        assertFalse(reply.has("released"));
        assertFalse(reply.has("receipt"));
        assertTrue(c.shouldStop());
        assertEquals("VD_OWNER_STOPPED initialized=false receiptsClosed=true", c.stoppedMarker());
        c.closeAfterStop();
        assertEquals("INITIALIZATION_CLOSED", c.dispatch(request("initialize", "fresh")).getString("error"));
        assertFalse(c.dispatch(shutdown()).getBoolean("ok"));
        assertEquals(0, creates.get());
        assertEquals(0, writes.get());
        try (OwnerReceiptStore reopened = platform.open(root)) {
            assertNull(reopened.lookupGeneration("fresh", binding()));
        }
    }

    @Test public void oldReceiptsIncludingUnknownRemainByteIdentical() throws Exception {
        Path root = root();
        try (OwnerReceiptStore seed = platform.open(root)) {
            for (OwnerReceiptStore.State state : new OwnerReceiptStore.State[]{
                    OwnerReceiptStore.State.RESERVED, OwnerReceiptStore.State.STARTED,
                    OwnerReceiptStore.State.UNKNOWN, OwnerReceiptStore.State.SUCCEEDED}) {
                OwnerReceiptStore.Claim claim = seed.reserveGeneration(state.name(), binding()).newClaim;
                if (state != OwnerReceiptStore.State.RESERVED) {
                    seed.start(claim);
                    Map<String, Object> parameters = new LinkedHashMap<>();
                    parameters.put("kind", "key");
                    parameters.put("keyCode", 4);
                    parameters.put("displayId", null);
                    OwnerReceiptStore.Claim operation = seed.reserveOperation(claim,
                            new OwnerOperationIdentity(state.name(), "old_input", "input", binding(), parameters)).newClaim;
                    seed.start(operation);
                    Map<String, Object> unknown = new LinkedHashMap<>();
                    unknown.put("ok", false);
                    unknown.put("released", false);
                    seed.complete(operation, OwnerReceiptStore.State.UNKNOWN, unknown);
                }
                if (state.isFinal()) {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("ok", state == OwnerReceiptStore.State.SUCCEEDED);
                    summary.put("released", state == OwnerReceiptStore.State.SUCCEEDED);
                    seed.complete(claim, state, summary);
                }
            }
        }
        Map<String, String> before = bytes(root);
        OwnerReceiptStore store = platform.open(root);
        OwnerReceiptCoordinator c = new OwnerReceiptCoordinator(store, () -> { throw new AssertionError(); });
        for (String g : new String[]{"RESERVED", "STARTED", "UNKNOWN", "SUCCEEDED"}) {
            assertTrue(c.dispatch(request("receipt", g)).getBoolean("found"));
        }
        // An initialize that ONLY finds an old receipt never crosses fresh-generation admission.
        assertTrue(c.dispatch(request("initialize", "STARTED")).getBoolean("receiptOnly"));
        assertTrue(c.dispatch(shutdown()).getBoolean("ok"));
        assertEquals(before, bytes(root));
        try (OwnerReceiptStore reopened = platform.open(root)) {
            assertEquals(OwnerReceiptStore.State.UNKNOWN, reopened.lookupGeneration("UNKNOWN", binding()).state);
        }
    }

    @Test public void admittedInitializationEvenWithoutBackendCannotShutdown() throws Exception {
        for (boolean failWrite : new boolean[]{false, true}) {
            Path root = root();
            try (OwnerReceiptStore store = platform.open(root, point -> {
                if (failWrite) throw new IOException("injected admission write failure");
            })) {
                AtomicInteger creates = new AtomicInteger();
                OwnerReceiptCoordinator c = new OwnerReceiptCoordinator(store, () -> {
                    creates.incrementAndGet(); throw new AssertionError("creation uncertain");
                });
                assertFalse(c.dispatch(request("initialize", "fresh")).getBoolean("ok"));
                assertFalse(c.dispatch(shutdown()).getBoolean("ok"));
                assertFalse(c.shouldStop());
                assertThrows(IOException.class, c::closeAfterStop);
                assertThrows(IOException.class, c::stoppedMarker);
                assertEquals(failWrite ? 0 : 1, creates.get());
            }
        }
    }

    @Test public void initializedOwnerRetainsOriginalReleaseFinalsAndMarker() throws Exception {
        Path root = root();
        try (OwnerReceiptStore store = platform.open(root)) {
            boolean[] released = {false};
            OwnerReceiptCoordinator c = new OwnerReceiptCoordinator(store, () -> new OwnerReceiptCoordinator.Backend() {
                public JSONObject dispatch(OwnerProtocol.Request r) {
                    released[0] = true;
                    return OwnerProtocol.ok(r.op, null);
                }
                public boolean shouldStop() { return released[0]; }
                public int displayId() { return 7; }
                public String uniqueId() { return "synthetic"; }
            });
            assertTrue(c.dispatch(request("initialize", "fresh")).getBoolean("ok"));
            assertFalse(c.dispatch(shutdown()).getBoolean("ok"));
            assertFalse(c.shouldStop());
            JSONObject release = envelope("release").put("generation", "fresh")
                    .put("operationId", "release_1").put("parameters", new JSONObject());
            assertTrue(c.dispatch(OwnerProtocol.parseWireRequest(release.toString())).getBoolean("ok"));
            assertTrue(c.shouldStop());
            assertEquals(OwnerReceiptStore.State.SUCCEEDED, store.lookupGeneration("fresh", binding()).state);
            c.closeAfterStop();
            assertEquals("VD_OWNER_STOPPED released=true receiptsFinal=true", c.stoppedMarker());
        }
    }

    @Test public void uncertainCloseNeverStopsNeverRetriesNeverReopensInitialization() throws Exception {
        Path root = root();
        AtomicInteger closes = new AtomicInteger();
        OwnerReceiptStore store = platform.open(root, point -> {}, point -> {
            if (point == OwnerReceiptStore.LifecyclePoint.CHANNEL_CLOSED) {
                closes.incrementAndGet(); throw new IOException("injected close failure");
            }
        });
        OwnerReceiptCoordinator c = new OwnerReceiptCoordinator(store, () -> { throw new AssertionError(); });
        assertEquals("SHUTDOWN_UNINITIALIZED_UNKNOWN", c.dispatch(shutdown()).getString("error"));
        assertFalse(c.shouldStop());
        assertThrows(IOException.class, c::stoppedMarker);
        assertThrows(IOException.class, c::closeAfterStop);
        assertFalse(c.dispatch(shutdown()).getBoolean("ok"));
        assertEquals("INITIALIZATION_CLOSED", c.dispatch(request("initialize", "fresh")).getString("error"));
        assertEquals(1, closes.get());
        assertThrows(OwnerReceiptStore.StoreException.class, () -> platform.open(root));
        // The store intentionally retains this root's uncertain-close registration. No retry/kill.
    }
}
