package app.daiyu.android.vd.runtime;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.test.InstrumentationTestCase;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Device LocalSocket/kernel UID + real owner-looper/store tests; no root or display creation. */
public class OwnerUninitializedShutdownSocketTest extends InstrumentationTestCase {
    private static final String TOKEN = "private-device-test-token";

    private final class Fixture implements AutoCloseable {
        final Path root;
        final HandlerThread thread = new HandlerThread("r08-shutdown-test-owner");
        final OwnerReceiptStore store;
        final OwnerReceiptCoordinator coordinator;
        final OwnerIpcServer server;
        final String name = "r08_" + UUID.randomUUID().toString().replace("-", "");
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<String> marker = new AtomicReference<>();
        final AtomicInteger creates = new AtomicInteger();

        Fixture(int allowedUid) throws Exception {
            root = Files.createTempDirectory(getInstrumentation().getTargetContext()
                    .getCacheDir().getCanonicalFile().toPath(), "r08_");
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
            store = new OwnerReceiptStore(root);
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            coordinator = new OwnerReceiptCoordinator(store, () -> {
                assertSame(thread, Thread.currentThread());
                creates.incrementAndGet();
                throw new OwnerException("TEST_CREATE_UNKNOWN", "no test display");
            });
            OwnerIpcServer.Dispatcher checked = new OwnerIpcServer.Dispatcher() {
                public JSONObject dispatch(OwnerProtocol.Request r) {
                    if (Thread.currentThread() != thread) failure.set(new AssertionError("not owner looper"));
                    return coordinator.dispatch(r);
                }
                public boolean shouldStop() { return coordinator.shouldStop(); }
            };
            server = new OwnerIpcServer(name, allowedUid, TOKEN, handler, checked, () -> {
                try {
                    assertSame(thread, Thread.currentThread());
                    coordinator.closeAfterStop();
                    marker.set(coordinator.stoppedMarker());
                } catch (Throwable error) { failure.set(error); }
                finally { terminal.countDown(); }
            });
            server.start();
        }

        LocalSocket connect() throws IOException {
            LocalSocket socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(10000);
            return socket;
        }

        void awaitTerminal() throws Exception {
            assertTrue("terminal callback not delivered", terminal.await(15, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals("VD_OWNER_STOPPED initialized=false receiptsClosed=true", marker.get());
        }

        public void close() throws Exception {
            server.stop();
            thread.quitSafely();
            thread.join(10000);
            store.close();
        }
    }

    private static JSONObject wire(String op) throws Exception {
        return new JSONObject().put("v", 2).put("op", op).put("token", TOKEN);
    }

    private static JSONObject bound(String op) throws Exception {
        return wire(op).put("generation", "device_generation").put("binding", new JSONObject()
                .put("session", "s").put("task", "t").put("conversation", "c")
                .put("run", "r").put("assistantMessage", "m").put("runOwner", "o"));
    }

    private static void send(LocalSocket socket, JSONObject request) throws Exception {
        socket.getOutputStream().write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static JSONObject receive(LocalSocket socket) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int ch; (ch = socket.getInputStream().read()) >= 0 && ch != '\n';) {
            if (bytes.size() >= 4096) throw new IOException("test response limit");
            bytes.write(ch);
        }
        return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    public void testKernelUidRejectsBeforeShutdownDispatch() throws Exception {
        try (Fixture f = new Fixture(Process.myUid() + 1); LocalSocket socket = f.connect()) {
            // UID is rejected on connect, before the server reads any token or command.
            assertEquals("PEER_REJECTED", receive(socket).getString("error"));
            assertFalse(f.coordinator.shouldStop());
            assertEquals(0, f.creates.get());
        }
    }

    public void testTokenAndSchemaGateThenLookupOnlyShutdownOnOwnerLooper() throws Exception {
        try (Fixture f = new Fixture(Process.myUid())) {
            try (LocalSocket wrong = f.connect()) {
                send(wrong, wire("shutdown_uninitialized").put("token", "wrong"));
                assertEquals("AUTH_REJECTED", receive(wrong).getString("error"));
            }
            // SESSION_BUSY is possible until the rejected connection's finally relinquishes it.
            try (LocalSocket socket = connectAfterRejected(f)) {
                send(socket, wire("shutdown_uninitialized").put("generation", "forbidden"));
                assertEquals("PROTOCOL", receive(socket).getString("error"));
                send(socket, bound("receipt"));
                assertFalse(receive(socket).getBoolean("found"));
                assertFalse(f.coordinator.shouldStop());
                send(socket, wire("shutdown_uninitialized"));
                JSONObject result = receive(socket);
                assertEquals(5, result.length());
                assertTrue(result.getBoolean("ok"));
                assertFalse(result.getBoolean("initialized"));
                assertTrue(result.getBoolean("receiptsClosed"));
                f.awaitTerminal();
                assertEquals(0, f.creates.get());
                try (OwnerReceiptStore reopened = new OwnerReceiptStore(f.root)) {
                    assertNull(reopened.lookupGeneration("device_generation",
                            OwnerOperationIdentity.bindingFromTrustedHost("s", "t", "c", "r", "m", "o")));
                }
            }
        }
    }

    private LocalSocket connectAfterRejected(Fixture f) throws Exception {
        // Retry only read-only receipt admission, never shutdown or initialize.
        for (int attempt = 0; attempt < 30; attempt++) {
            LocalSocket socket = f.connect();
            send(socket, bound("receipt"));
            JSONObject response = receive(socket);
            if (response.optBoolean("ok", false)) return socket;
            socket.close();
            assertEquals("SESSION_BUSY", response.getString("error"));
            Thread.sleep(20);
        }
        throw new AssertionError("rejected peer did not relinquish connection");
    }

    public void testLostShutdownResponseStillCompletesButNeverCreatesDisplay() throws Exception {
        try (Fixture f = new Fixture(Process.myUid())) {
            LocalSocket socket = f.connect();
            send(socket, wire("shutdown_uninitialized"));
            socket.close(); // no response read; completion must not depend on its delivery
            f.awaitTerminal();
            assertEquals(0, f.creates.get());
        }
    }

    public void testInitializationAdmissionWinsAndDeniesUninitializedShutdown() throws Exception {
        try (Fixture f = new Fixture(Process.myUid()); LocalSocket socket = f.connect()) {
            send(socket, bound("initialize"));
            assertEquals("INITIALIZATION_UNKNOWN", receive(socket).getString("error"));
            send(socket, wire("shutdown_uninitialized"));
            assertEquals("SHUTDOWN_UNINITIALIZED_DENIED", receive(socket).getString("error"));
            assertEquals(1, f.creates.get());
            assertFalse(f.coordinator.shouldStop());
            assertEquals(1L, f.terminal.getCount());
            assertNull(f.failure.get());
        }
    }
}
