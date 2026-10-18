package app.daiyu.android.vd.runtime;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.test.InstrumentationTestCase;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Device LocalSocket/kernel UID + real owner-looper/store tests; no root or display creation. */
public class OwnerUninitializedShutdownSocketTest extends InstrumentationTestCase {
    private static final String TOKEN = "private-device-test-token";

    private static final String DIAGNOSTIC_TAG = "OwnerShutdownDiag";
    private volatile String phase = "not started";

    private void phase(String value) {
        phase = value;
        Log.i(DIAGNOSTIC_TAG, getName() + " phase=" + value);
    }

    @Override protected void runTest() throws Throwable {
        CountDownLatch finished = new CountDownLatch(1);
        Thread watchdog = new Thread(() -> {
            try {
                if (finished.await(60, TimeUnit.SECONDS)) return;
            } catch (InterruptedException interrupted) { return; }
            Log.e(DIAGNOSTIC_TAG, getName() + " still running phase=" + phase);
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Throwable stack = new Throwable("thread=" + entry.getKey().getName()
                        + " state=" + entry.getKey().getState());
                stack.setStackTrace(entry.getValue());
                Log.e(DIAGNOSTIC_TAG, "stalled owner test", stack);
            }
        }, "owner-shutdown-test-diagnostic");
        watchdog.setDaemon(true);
        watchdog.start();
        try { super.runTest(); }
        finally { finished.countDown(); watchdog.interrupt(); }
    }

    private final class TracedSocket extends LocalSocket {
        @Override public void close() throws IOException {
            phase("client.close.begin");
            super.close();
            phase("client.close.end");
        }
    }

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
        final CountDownLatch terminalDispatchReached = new CountDownLatch(1);
        final CountDownLatch allowTerminalDispatch = new CountDownLatch(1);

        Fixture(int allowedUid) throws Exception { this(allowedUid, false); }

        Fixture(int allowedUid, boolean gateTerminalDispatch) throws Exception {
            phase("fixture.create.begin");
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
                public boolean shouldStop() {
                    boolean stop = coordinator.shouldStop();
                    if (stop && gateTerminalDispatch && Thread.currentThread() == thread &&
                            allowTerminalDispatch.getCount() != 0) {
                        terminalDispatchReached.countDown();
                        try {
                            if (!allowTerminalDispatch.await(15, TimeUnit.SECONDS))
                                failure.set(new AssertionError("terminal dispatch gate not released"));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt(); failure.set(error);
                        }
                    }
                    return stop;
                }
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
            phase("fixture.create.end");
        }

        LocalSocket connect() throws IOException {
            phase("client.connect.begin");
            LocalSocket socket = new TracedSocket();
            socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(10000);
            phase("client.connect.end");
            return socket;
        }

        void awaitTerminal() throws Exception {
            phase("terminal.await.begin");
            assertTrue("terminal callback not delivered", terminal.await(15, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals("VD_OWNER_STOPPED initialized=false receiptsClosed=true", marker.get());
            phase("terminal.await.end");
        }

        public void close() throws Exception {
            allowTerminalDispatch.countDown();
            phase("fixture.server.stop.begin");
            server.stop();
            phase("fixture.owner.quit.begin");
            thread.quitSafely();
            phase("fixture.owner.join.begin");
            thread.join(10000);
            phase("fixture.store.close.begin");
            store.close();
            phase("fixture.close.end");
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

    private void send(LocalSocket socket, JSONObject request) throws Exception {
        phase("client.write.begin op=" + request.optString("op"));
        socket.getOutputStream().write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        phase("client.flush.begin");
        socket.getOutputStream().flush();
        phase("client.send.end");
    }

    private JSONObject receive(LocalSocket socket) throws Exception {
        phase("client.receive.begin");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int ch;
        while ((ch = socket.getInputStream().read()) >= 0 && ch != '\n') {
            if (bytes.size() >= 4096) throw new IOException("test response limit");
            bytes.write(ch);
        }
        if (ch < 0) throw new IOException("incomplete test response");
        phase("client.receive.end");
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
                phase("store.reopen.begin");
                try (OwnerReceiptStore reopened = new OwnerReceiptStore(f.root)) {
                    phase("store.reopened.lookup.begin");
                    assertNull(reopened.lookupGeneration("device_generation",
                            OwnerOperationIdentity.bindingFromTrustedHost("s", "t", "c", "r", "m", "o")));
                    phase("store.reopened.close.begin");
                }
                phase("store.reopened.close.end");
            }
        }
    }

    private LocalSocket connectAfterRejected(Fixture f) throws Exception {
        // Retry only read-only receipt admission, never shutdown or initialize.
        for (int attempt = 0; attempt < 30; attempt++) {
            LocalSocket socket = f.connect();
            boolean admitted = false;
            try {
                send(socket, bound("receipt"));
                JSONObject response = receive(socket);
                if (response.optBoolean("ok", false)) {
                    admitted = true;
                    return socket;
                }
                assertEquals("SESSION_BUSY", response.getString("error"));
            } catch (IOException refused) {
                // A rejected peer can close before our request or its busy response is consumed.
                // Only this read-only admission probe may retry; never retry a shutdown.
            } finally {
                if (!admitted) socket.close();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("rejected peer did not relinquish connection");
    }

    public void testTerminalShutdownClosesAuthenticatedClientWithoutAnotherFrame() throws Exception {
        try (Fixture f = new Fixture(Process.myUid(), true); LocalSocket socket = f.connect()) {
            send(socket, bound("receipt"));
            assertFalse(receive(socket).getBoolean("found"));
            try {
                send(socket, wire("shutdown_uninitialized"));
                assertTrue(receive(socket).getBoolean("ok"));
                assertTrue(f.terminalDispatchReached.await(5, TimeUnit.SECONDS));
                // Dispatch completion is still gated; the terminal reply must close, not read again.
                assertEquals(-1, socket.getInputStream().read());
            } finally { f.allowTerminalDispatch.countDown(); }
            f.awaitTerminal();
            assertEquals(0, f.creates.get());
        }
    }

    public void testBusyRejectionDoesNotWaitForPeerToReadBeforeClosingUnreadRequest() throws Exception {
        try (Fixture f = new Fixture(Process.myUid()); LocalSocket active = f.connect()) {
            send(active, bound("receipt"));
            assertFalse(receive(active).getBoolean("found"));
            try (LocalSocket rejected = f.connect()) {
                CountDownLatch sent = new CountDownLatch(1);
                AtomicReference<Throwable> sendFailure = new AtomicReference<>();
                Thread sender = new Thread(() -> {
                    try { send(rejected, bound("receipt")); }
                    catch (Throwable error) { sendFailure.set(error); }
                    finally { sent.countDown(); }
                }, "owner-busy-request-sender");
                sender.setDaemon(true);
                sender.start();
                try {
                    // Deliberately do not read the rejection until the request's flush returns.
                    assertTrue("request flush deadlocked with busy response", sent.await(5, TimeUnit.SECONDS));
                    Throwable sendError = sendFailure.get();
                    assertTrue("unexpected sender failure: " + sendError,
                            sendError == null || sendError instanceof IOException);
                    try {
                        assertEquals("SESSION_BUSY", receive(rejected).getString("error"));
                    } catch (java.net.SocketTimeoutException timeout) {
                        throw timeout;
                    } catch (IOException refused) {
                        // Rejection can close an unread request; transport refusal is also final.
                    }
                } finally {
                    try { rejected.shutdownInput(); } catch (IOException ignored) {}
                    try { rejected.shutdownOutput(); } catch (IOException ignored) {}
                    rejected.close();
                    sender.join(5000);
                }
                assertFalse("busy sender survived cleanup", sender.isAlive());
            }
            send(active, bound("receipt"));
            assertFalse(receive(active).getBoolean("found"));
            assertFalse(f.coordinator.shouldStop());
            assertEquals(0, f.creates.get());
        }
    }

    public void testStopWakesAuthenticatedIdleReadWithoutCreatingOrTerminatingDisplay() throws Exception {
        try (Fixture f = new Fixture(Process.myUid())) {
            Set<Thread> before = Thread.getAllStackTraces().keySet();
            try (LocalSocket socket = f.connect()) {
                send(socket, bound("receipt"));
                assertFalse(receive(socket).getBoolean("found"));
                Thread connection = awaitIdleConnection(before);
                CountDownLatch stopped = new CountDownLatch(1);
                AtomicReference<Throwable> stopFailure = new AtomicReference<>();
                Thread stop = new Thread(() -> {
                    try { f.server.stop(); }
                    catch (Throwable error) { stopFailure.set(error); }
                    finally { stopped.countDown(); }
                }, "owner-idle-stop-test");
                stop.setDaemon(true);
                phase("idle.stop.begin");
                stop.start();
                assertTrue("server stop blocked", stopped.await(5, TimeUnit.SECONDS));
                assertNull(stopFailure.get());
                connection.join(5000);
                assertFalse("authenticated read survived stop", connection.isAlive());
                assertEquals(-1, socket.getInputStream().read());
                assertFalse(f.coordinator.shouldStop());
                assertEquals(1L, f.terminal.getCount());
                assertEquals(0, f.creates.get());
                phase("idle.stop.end");
            }
        }
    }

    private Thread awaitIdleConnection(Set<Thread> before) throws Exception {
        phase("idle.read.await");
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                if (before.contains(entry.getKey()) || !"vd-owner-conn".equals(entry.getKey().getName())) continue;
                for (StackTraceElement frame : entry.getValue()) {
                    if (OwnerIpcServer.class.getName().equals(frame.getClassName())
                            && "readLine".equals(frame.getMethodName())) return entry.getKey();
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("authenticated connection did not enter idle read");
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
