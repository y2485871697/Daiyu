package app.daiyu.android.vd.runtime;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static app.daiyu.android.vd.runtime.OwnerReceiptStore.Error.*;
import static app.daiyu.android.vd.runtime.OwnerReceiptStore.State.*;
import static org.junit.Assert.*;

/** Linux POSIX primitives; non-POSIX host metadata model. No display, process, network, root launcher or Android APIs. */
public class OwnerReceiptStoreTest {
    private final OwnerReceiptTestPlatform platform = new OwnerReceiptTestPlatform();
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private OwnerOperationIdentity.RunBinding binding;
    private final List<OwnerReceiptStore> stores = new ArrayList<OwnerReceiptStore>();

    private OwnerReceiptStore openStore(Path directory) throws IOException {
        return openStore(directory, point -> { });
    }

    private OwnerReceiptStore openStore(Path directory, OwnerReceiptStore.WriteFault fault)
            throws IOException {
        OwnerReceiptStore store = platform.open(directory, fault);
        stores.add(store);
        return store;
    }

    @After public void closeStores() throws IOException {
        IOException failure = null;
        for (OwnerReceiptStore store : stores) {
            try { store.close(); }
            catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    @Before public void setUp() throws Exception {
        root = privateRoot();
        binding = OwnerOperationIdentityTest.binding();
    }

    private Path privateRoot() throws IOException {
        Path path = temporary.newFolder().toPath().toAbsolutePath();
        platform.setPermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }

    private OwnerOperationIdentity identity(String id) {
        return new OwnerOperationIdentity("gen_1", id, "launch", binding, args("component", "pkg/.A"));
    }

    private static Map<String, Object> args(String key, Object value) {
        Map<String, Object> args = new HashMap<String, Object>(); args.put(key, value); return args;
    }

    private OwnerReceiptStore.Claim liveGeneration(OwnerReceiptStore store) throws Exception {
        OwnerReceiptStore.Reservation reserved = store.reserveGeneration("gen_1", binding);
        assertNotNull(reserved.newClaim);
        assertEquals(RESERVED, reserved.receipt.state);
        assertTrue(store.start(reserved.newClaim).mayExecute);
        return reserved.newClaim;
    }

    @Test public void durableReservationStartAndImmutableFinalSurviveReopen() throws Exception {
        OwnerReceiptStore store = openStore(root);
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        OwnerOperationIdentity identity = identity("one");
        OwnerReceiptStore.Reservation reservation = store.reserveOperation(generation, identity);
        assertNotNull(reservation.newClaim);
        assertEquals(RESERVED, store.lookup(identity).state);
        assertEquals("rw-------", PosixFilePermissions.toString(platform.permissions(operationFile("one"))));
        assertEquals("rwx------", PosixFilePermissions.toString(platform.permissions(root.resolve("g-gen_1"))));
        OwnerReceiptStore.Start started = store.start(reservation.newClaim);
        assertTrue(started.mayExecute);
        assertEquals(STARTED, store.lookup(identity).state);
        assertFalse(store.start(reservation.newClaim).mayExecute);
        OwnerReceiptStore.Receipt finalReceipt = store.complete(reservation.newClaim, SUCCEEDED, args("displayId", 7));
        assertEquals("{\"displayId\":7}", finalReceipt.resultJson);
        OwnerReceiptStore.Receipt duplicate = store.complete(reservation.newClaim, FAILED, args("different", true));
        assertEquals(SUCCEEDED, duplicate.state);
        assertEquals(finalReceipt.resultJson, duplicate.resultJson);
        // Even an invalid replacement payload cannot replace a final receipt.
        assertEquals(finalReceipt.resultJson, store.complete(reservation.newClaim, UNKNOWN, null).resultJson);
        assertFalse(store.start(reservation.newClaim).mayExecute);
        assertNull(store.reserveOperation(generation, identity).newClaim);
        store.close();
        OwnerReceiptStore.Receipt reopened = openStore(root).lookup(identity);
        assertEquals(SUCCEEDED, reopened.state);
        assertEquals(finalReceipt.resultJson, reopened.resultJson);
    }

    @Test public void allPersistedStatesBlockFreshExecutionIncludingUnknown() throws Exception {
        for (OwnerReceiptStore.State state : OwnerReceiptStore.State.values()) {
            Path directory = privateRoot();
            OwnerReceiptStore store = openStore(directory);
            OwnerReceiptStore.Claim generation = liveGeneration(store);
            OwnerOperationIdentity identity = identity(state.name());
            OwnerReceiptStore.Reservation reservation = store.reserveOperation(generation, identity);
            if (state != RESERVED) store.start(reservation.newClaim);
            if (state.isFinal()) store.complete(reservation.newClaim, state, args("outcome", state.name()));
            OwnerReceiptStore.Reservation duplicate = store.reserveOperation(generation, identity);
            assertNull(duplicate.newClaim);
            assertEquals(state, duplicate.receipt.state);
            store.close();
            OwnerReceiptStore reopened = openStore(directory);
            assertEquals(state, reopened.lookup(identity).state);
            assertNull(reopened.reserveGeneration("gen_1", binding).newClaim);
            // A capability from a previous instance cannot be imported to resume execution.
            expect(INVALID_CLAIM, () -> reopened.start(reservation.newClaim));
            expect(INVALID_CLAIM, () -> reopened.reserveOperation(generation, identity("new")));
        }
    }

    @Test public void generationClaimCannotBeReissuedEvenBeforeStart() throws Exception {
        OwnerReceiptStore store = openStore(root);
        OwnerReceiptStore.Reservation generation = store.reserveGeneration("gen_1", binding);
        assertNull(store.reserveGeneration("gen_1", binding).newClaim);
        expect(BUSY, () -> openStore(root));
        expect(INVALID_CLAIM, () -> store.reserveOperation(generation.newClaim, identity("one")));
        assertTrue(store.start(generation.newClaim).mayExecute);
        assertFalse(store.start(generation.newClaim).mayExecute);
        store.complete(generation.newClaim, UNKNOWN, args("reason", "creation_outcome_unknown"));
        expect(INVALID_CLAIM, () -> store.reserveOperation(generation.newClaim, identity("one")));
        store.close();
        assertNull(openStore(root).reserveGeneration("gen_1", binding).newClaim);
    }

    @Test public void parameterOperationAndBindingConflictNeverReturnOldReceiptAsSuccess() throws Exception {
        OwnerReceiptStore store = openStore(root);
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        store.reserveOperation(generation, identity("one"));
        OwnerOperationIdentity changedArgs = new OwnerOperationIdentity("gen_1", "one", "launch", binding,
                args("component", "pkg/.B"));
        OwnerOperationIdentity changedOp = new OwnerOperationIdentity("gen_1", "one", "input", binding,
                args("component", "pkg/.A"));
        for (OwnerOperationIdentity conflict : Arrays.asList(changedArgs, changedOp)) {
            expect(CONFLICT, () -> store.lookup(conflict));
            expect(CONFLICT, () -> store.reserveOperation(generation, conflict));
        }
        OwnerOperationIdentity.RunBinding other = OwnerOperationIdentity.bindingFromTrustedHost("s", "t", "c", "r", "m", "other");
        OwnerOperationIdentity changedBinding = new OwnerOperationIdentity("gen_1", "one", "launch", other, args("component", "pkg/.A"));
        expect(CONFLICT, () -> store.lookup(changedBinding));
        expect(CONFLICT, () -> store.reserveOperation(generation, changedBinding));
        expect(CONFLICT, () -> store.reserveGeneration("gen_1", other));
        expect(CONFLICT, () -> store.lookupGeneration("gen_1", other));
        // Definite conflicts do not poison legitimate receipt reads or fresh IDs.
        assertNotNull(store.reserveOperation(generation, identity("two")).newClaim);
    }

    @Test public void unknownCannotBecomeSucceededOrFailedAndReservedCannotComplete() throws Exception {
        OwnerReceiptStore store = openStore(root);
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        OwnerReceiptStore.Claim claim = store.reserveOperation(generation, identity("one")).newClaim;
        expect(INVALID_CLAIM, () -> store.complete(claim, SUCCEEDED, args("ok", true)));
        expect(INVALID_CLAIM, () -> store.complete(claim, RESERVED, args("ok", true)));
        assertTrue(store.start(claim).mayExecute);
        String result = store.complete(claim, UNKNOWN, args("reason", "response_lost")).resultJson;
        for (OwnerReceiptStore.State next : new OwnerReceiptStore.State[]{SUCCEEDED, FAILED, UNKNOWN}) {
            OwnerReceiptStore.Receipt old = store.complete(claim, next, args("not", "used"));
            assertEquals(UNKNOWN, old.state);
            assertEquals(result, old.resultJson);
        }
        assertNull(store.reserveOperation(generation, identity("one")).newClaim);
    }

    @Test public void pendingOperationCannotStartAfterGenerationEnds() throws Exception {
        OwnerReceiptStore store = openStore(root);
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        OwnerReceiptStore.Claim operation = store.reserveOperation(generation, identity("one")).newClaim;
        store.complete(generation, SUCCEEDED, args("released", true));
        expect(INVALID_CLAIM, () -> store.start(operation));
        assertEquals(RESERVED, store.lookup(identity("one")).state);
        assertFalse(store.start(operation).mayExecute);
    }

    @Test public void lookupOnlyDoesNotCreateGenerationOrOperationAndReturnsOldFinal() throws Exception {
        OwnerReceiptStore store = openStore(root);
        long before = childCount(root);
        assertNull(store.lookup(identity("absent")));
        assertNull(store.lookupGeneration("absent", binding));
        assertEquals(before, childCount(root));
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        long generationFiles = childCount(root.resolve("g-gen_1"));
        assertNull(store.lookup(identity("absent")));
        assertEquals(generationFiles, childCount(root.resolve("g-gen_1")));
        OwnerReceiptStore.Claim claim = store.reserveOperation(generation, identity("one")).newClaim;
        store.start(claim); store.complete(claim, FAILED, args("error", "business_failure"));
        long after = childCount(root.resolve("g-gen_1"));
        store.close();
        for (int i = 0; i < 3; i++) {
            try (OwnerReceiptStore reopened = openStore(root)) {
                assertEquals(FAILED, reopened.lookup(identity("one")).state);
            }
        }
        assertEquals(after, childCount(root.resolve("g-gen_1")));
    }

    @Test public void sameGenerationAndOperationNamesCannotCollideThroughHyphens() throws Exception {
        OwnerReceiptStore store = openStore(root);
        OwnerReceiptStore.Claim a = store.reserveGeneration("a-b", binding).newClaim;
        OwnerReceiptStore.Claim b = store.reserveGeneration("a", binding).newClaim;
        store.start(a); store.start(b);
        OwnerOperationIdentity first = new OwnerOperationIdentity("a-b", "c", "launch", binding, Collections.emptyMap());
        OwnerOperationIdentity second = new OwnerOperationIdentity("a", "b-c", "launch", binding, Collections.emptyMap());
        assertNotNull(store.reserveOperation(a, first).newClaim);
        assertNotNull(store.reserveOperation(b, second).newClaim);
        assertNotEquals(store.lookup(first).digest, store.lookup(second).digest);
    }

    @Test public void rejectNonPrivateNonNormalizedAndSymlinkRootOrAncestor() throws Exception {
        platform.setPermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        expect(UNSAFE_PATH, () -> openStore(root));
        platform.setPermissions(root, PosixFilePermissions.fromString("rwx------"));
        expect(UNSAFE_PATH, () -> openStore(root.resolve("../" + root.getFileName())));
        Path link = temporary.getRoot().toPath().resolve("root-link");
        platform.createSymbolicLink(link, root);
        expect(UNSAFE_PATH, () -> openStore(link));
        Path child = root.resolve("child");
        platform.createPrivateDirectory(child);
        expect(UNSAFE_PATH, () -> openStore(link.resolve("child")));
    }

    @Test public void symlinkGenerationReceiptAndLockAreRejectedWithoutTouchingTarget() throws Exception {
        OwnerReceiptStore store = openStore(root);
        Path outside = privateRoot();
        platform.createSymbolicLink(root.resolve("g-gen_1"), outside);
        expect(UNSAFE_PATH, () -> store.reserveGeneration("gen_1", binding));
        assertEquals(0, childCount(outside));
        platform.delete(root.resolve("g-gen_1"));
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        store.reserveOperation(generation, identity("one"));
        Path target = outside.resolve("outside"); Files.write(target, new byte[]{7});
        platform.delete(operationFile("one"));
        platform.createSymbolicLink(operationFile("one"), target);
        expect(UNSAFE_PATH, () -> store.lookup(identity("one")));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(target));
        store.close();
        platform.delete(root.resolve(".receipt.lock"));
        platform.createSymbolicLink(root.resolve(".receipt.lock"), target);
        expect(UNSAFE_PATH, () -> openStore(root));
    }

    @Test public void corruptionTruncationOversizeAndPermissiveFilesFailClosed() throws Exception {
        OwnerReceiptStore store = openStore(root);
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        store.reserveOperation(generation, identity("one"));
        Path file = operationFile("one");
        byte[] original = Files.readAllBytes(file);
        byte[] flipped = original.clone(); flipped[20] ^= 1;
        for (byte[] corrupt : new byte[][]{new byte[0], Arrays.copyOf(original, 15), flipped,
                new byte[OwnerReceiptStore.MAX_FILE_BYTES + 1]}) {
            Files.write(file, corrupt);
            expect(CORRUPT, () -> store.lookup(identity("one")));
            expect(CORRUPT, () -> store.reserveOperation(generation, identity("one")));
        }
        Files.write(file, original);
        platform.setPermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        expect(UNSAFE_PATH, () -> store.lookup(identity("one")));
    }

    @Test public void invalidEnvelopeEvenWithRecomputedChecksumIsRejected() throws Exception {
        OwnerReceiptStore store = openStore(root);
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        store.reserveOperation(generation, identity("one"));
        byte[] original = Files.readAllBytes(operationFile("one"));
        byte[] badVersion = original.clone(); badVersion[0] ^= 1;
        byte[] badKind = original.clone(); badKind[4] = 2;
        byte[] badLength = original.clone();
        badLength[5] = 0x7f; badLength[6] = -1; badLength[7] = -1; badLength[8] = -1;
        byte[] badUtf8 = original.clone(); badUtf8[9] = -1;
        for (byte[] corrupt : new byte[][]{badVersion, badKind, badLength, badUtf8}) {
            byte[] body = Arrays.copyOf(corrupt, corrupt.length - 64);
            byte[] checksum = OwnerOperationIdentity.sha256(body).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(checksum, 0, corrupt, body.length, checksum.length);
            Files.write(operationFile("one"), corrupt);
            expect(CORRUPT, () -> store.lookup(identity("one")));
        }
    }

    @Test public void missingGenerationRecordAndAbandonedPendingFileAreNeverRecreated() throws Exception {
        OwnerReceiptStore store = openStore(root);
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        Path pending = operationFile("one").resolveSibling("op-one.receipt.pending");
        Files.write(pending, new byte[]{1, 2, 3});
        expect(INDETERMINATE_WRITE, () -> store.lookup(identity("one")));
        expect(INDETERMINATE_WRITE, () -> store.reserveOperation(generation, identity("one")));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(pending));
        platform.delete(root.resolve("g-gen_1/generation.receipt"));
        store.close();
        expect(CORRUPT, () -> openStore(root).reserveGeneration("gen_1", binding));
    }

    @Test public void sameRootIsExclusiveForEntireLifetimeAndCanReopenOnlyAfterClose() throws Exception {
        OwnerReceiptStore first = openStore(root);
        // Even an idle/read-only instance owns the root, not just individual method calls.
        expect(BUSY, () -> openStore(root));
        assertNull(first.lookupGeneration("absent", binding));
        expect(BUSY, () -> openStore(root));
        OwnerReceiptStore.Claim claim = first.reserveGeneration("gen_1", binding).newClaim;
        expect(BUSY, () -> openStore(root));
        // A distinct private root is independent.
        assertNotNull(openStore(privateRoot()).reserveGeneration("other", binding).newClaim);
        first.close();
        first.close(); // Successful close is idempotent.
        OwnerReceiptStore second = openStore(root);
        assertNull(second.reserveGeneration("gen_1", binding).newClaim);
        assertEquals(RESERVED, second.lookupGeneration("gen_1", binding).state);
        expect(INVALID_CLAIM, () -> second.start(claim));
        expect(CLOSED, () -> first.start(claim));
        expect(BUSY, () -> openStore(root));
        // Closing the old instance again must not drop the new owner's registry entry.
        first.close();
        expect(BUSY, () -> openStore(root));
    }

    @Test public void closeRejectsEveryOperationAndOldClaimWithoutChangingReceipts() throws Exception {
        OwnerReceiptStore store = openStore(root);
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        OwnerReceiptStore.Claim operation = store.reserveOperation(generation, identity("one")).newClaim;
        assertTrue(store.start(operation).mayExecute);
        byte[] before = Files.readAllBytes(operationFile("one"));
        store.close();
        expect(CLOSED, () -> store.reserveGeneration("other", binding));
        expect(CLOSED, () -> store.reserveOperation(generation, identity("two")));
        expect(CLOSED, () -> store.start(operation));
        expect(CLOSED, () -> store.complete(operation, UNKNOWN, args("closed", true)));
        expect(CLOSED, () -> store.lookup(identity("one")));
        expect(CLOSED, () -> store.lookupGeneration("gen_1", binding));
        expect(CLOSED, () -> store.start(null));
        expect(CLOSED, () -> store.lookup(null));
        OwnerReceiptStore reopened = openStore(root);
        assertEquals(STARTED, reopened.lookup(identity("one")).state);
        assertNull(reopened.reserveGeneration("gen_1", binding).newClaim);
        expect(INVALID_CLAIM, () -> reopened.reserveOperation(generation, identity("two")));
        expect(INVALID_CLAIM, () -> reopened.complete(operation, SUCCEEDED, args("old", true)));
        assertArrayEquals(before, Files.readAllBytes(operationFile("one")));
    }

    @Test public void constructorFailureAfterAcquiringLockReleasesChannelAndRoot() throws Exception {
        IOException injected = new IOException("injected constructor failure");
        try {
            platform.open(root, point -> { }, point -> {
                if (point == OwnerReceiptStore.LifecyclePoint.LOCKED) throw injected;
            });
            fail("expected constructor failure");
        } catch (IOException expected) {
            assertSame(injected, expected);
        }
        OwnerReceiptStore reopened = openStore(root);
        assertNotNull(reopened.reserveGeneration("gen_1", binding).newClaim);
        expect(BUSY, () -> openStore(root));
    }

    @Test public void fileLockRemainsHeldAcrossCallsAndRejectedConstructorsUntilClose() throws Exception {
        OwnerReceiptStore store = openStore(root);
        // Keep the probe open until the owner closes: closing a second descriptor while owning
        // a lock can itself release process locks on some systems.
        try (FileChannel probe = FileChannel.open(root.resolve(".receipt.lock"), StandardOpenOption.WRITE)) {
            assertLocked(probe);
            assertNull(store.lookupGeneration("absent", binding));
            assertLocked(probe);
            expect(BUSY, () -> openStore(root));
            assertLocked(probe);
            assertNotNull(store.reserveGeneration("gen_1", binding).newClaim);
            assertLocked(probe);
            store.close();
            try (java.nio.channels.FileLock acquired = probe.tryLock()) {
                assertNotNull(acquired);
            }
        }
    }

    private static void assertLocked(FileChannel probe) throws IOException {
        try {
            try (java.nio.channels.FileLock unexpected = probe.tryLock()) {
                fail("owner must retain its file lock between calls");
            }
        } catch (java.nio.channels.OverlappingFileLockException expected) {
            // Same-JVM OS/JVM lock probe, independent of the store's root registry.
        }
    }

    @Test public void externallyHeldLockRejectsConstructionAndDoesNotLeakRootRegistration() throws Exception {
        openStore(root).close(); // Initialize the private lock file.
        try (FileChannel channel = FileChannel.open(root.resolve(".receipt.lock"), StandardOpenOption.WRITE);
             java.nio.channels.FileLock ignored = channel.lock()) {
            expect(BUSY, () -> openStore(root));
        }
        assertNotNull(openStore(root).reserveGeneration("gen_1", binding).newClaim);
    }

    @Test public void reportedCloseFailureNeverClaimsSafeReleaseEvenOnRetry() throws Exception {
        IOException injected = new IOException("injected close failure");
        // Report failure after actually closing the channel so this fault test leaks no OS handle.
        // The registry must nevertheless remain fail-closed: a real close error is ambiguous.
        OwnerReceiptStore store = platform.open(root, point -> { }, point -> {
            if (point == OwnerReceiptStore.LifecyclePoint.CHANNEL_CLOSED) throw injected;
        });
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        for (int i = 0; i < 2; i++) {
            try {
                store.close();
                fail("expected close failure, not a safe-release acknowledgment");
            } catch (IOException expected) {
                if (i == 0) assertSame(injected, expected);
                else assertSame(injected, expected.getCause());
            }
            expect(CLOSED, () -> store.start(generation));
            expect(CLOSED, () -> store.lookupGeneration("gen_1", binding));
            expect(BUSY, () -> openStore(root));
        }
    }

    @Test public void generationWriteFailuresNeverReturnCreationPermit() throws Exception {
        for (OwnerReceiptStore.WritePoint point : OwnerReceiptStore.WritePoint.values()) {
            Path directory = privateRoot();
            OwnerReceiptStore store = openStore(directory, observed -> {
                if (observed == point) throw new IOException("injected storage failure");
            });
            expect(INDETERMINATE_WRITE, () -> store.reserveGeneration("gen_1", binding));
            expect(POISONED, () -> store.reserveGeneration("different", binding));
            store.close();
            OwnerReceiptStore reopened = openStore(directory);
            try {
                assertNull(reopened.reserveGeneration("gen_1", binding).newClaim);
            } catch (OwnerReceiptStore.StoreException expected) {
                assertTrue(expected.error == CORRUPT || expected.error == INDETERMINATE_WRITE);
            }
        }
    }

    @Test public void everyOperationReservationFailureWithholdsClaim() throws Exception {
        for (OwnerReceiptStore.WritePoint point : OwnerReceiptStore.WritePoint.values()) {
            Path directory = privateRoot();
            AtomicReference<OwnerReceiptStore.WritePoint> armed = new AtomicReference<OwnerReceiptStore.WritePoint>();
            OwnerReceiptStore store = openStore(directory, observed -> {
                if (observed == armed.get()) throw new IOException("injected reservation failure");
            });
            OwnerReceiptStore.Claim generation = liveGeneration(store);
            armed.set(point);
            expect(INDETERMINATE_WRITE, () -> store.reserveOperation(generation, identity("one")));
            expect(POISONED, () -> store.reserveOperation(generation, identity("one")));
            store.close();
            OwnerReceiptStore reopened = openStore(directory);
            assertNull(reopened.reserveGeneration("gen_1", binding).newClaim);
            try {
                OwnerReceiptStore.Receipt receipt = reopened.lookup(identity("one"));
                // BEFORE_WRITE left no operation record; no side effect/permit was produced.
                if (receipt != null) assertEquals(RESERVED, receipt.state);
            } catch (OwnerReceiptStore.StoreException expected) {
                assertEquals(INDETERMINATE_WRITE, expected.error);
            }
        }
    }

    @Test public void everyStartFailurePointWithholdsPermitAndReopenCannotResume() throws Exception {
        for (OwnerReceiptStore.WritePoint point : OwnerReceiptStore.WritePoint.values()) {
            Path directory = privateRoot();
            AtomicReference<OwnerReceiptStore.WritePoint> armed = new AtomicReference<OwnerReceiptStore.WritePoint>();
            OwnerReceiptStore store = openStore(directory, observed -> {
                if (observed == armed.get()) throw new IOException("injected start failure");
            });
            OwnerReceiptStore.Claim generation = liveGeneration(store);
            OwnerReceiptStore.Claim operation = store.reserveOperation(generation, identity("one")).newClaim;
            armed.set(point);
            expect(INDETERMINATE_WRITE, () -> store.start(operation));
            expect(POISONED, () -> store.start(operation));
            store.close();
            OwnerReceiptStore reopened = openStore(directory);
            assertNull(reopened.reserveGeneration("gen_1", binding).newClaim);
            try {
                OwnerReceiptStore.Receipt receipt = reopened.lookup(identity("one"));
                assertTrue(receipt.state == RESERVED || receipt.state == STARTED);
            } catch (OwnerReceiptStore.StoreException expected) {
                assertEquals(INDETERMINATE_WRITE, expected.error);
            }
        }
    }

    @Test public void failedFinalWriteCannotDowngradeOrReexecute() throws Exception {
        for (OwnerReceiptStore.WritePoint point : OwnerReceiptStore.WritePoint.values()) {
            Path directory = privateRoot();
            AtomicReference<OwnerReceiptStore.WritePoint> armed = new AtomicReference<OwnerReceiptStore.WritePoint>();
            OwnerReceiptStore store = openStore(directory, observed -> {
                if (observed == armed.get()) throw new IOException("injected final failure");
            });
            OwnerReceiptStore.Claim generation = liveGeneration(store);
            OwnerReceiptStore.Claim operation = store.reserveOperation(generation, identity("one")).newClaim;
            store.start(operation);
            armed.set(point);
            expect(INDETERMINATE_WRITE, () -> store.complete(operation, SUCCEEDED, args("ok", true)));
            expect(POISONED, () -> store.start(operation));
            store.close();
            OwnerReceiptStore reopened = openStore(directory);
            assertNull(reopened.reserveGeneration("gen_1", binding).newClaim);
            try {
                OwnerReceiptStore.Receipt receipt = reopened.lookup(identity("one"));
                assertTrue(receipt.state == STARTED || receipt.state == SUCCEEDED);
                if (receipt.state == SUCCEEDED) assertEquals("{\"ok\":true}", receipt.resultJson);
            } catch (OwnerReceiptStore.StoreException expected) {
                assertEquals(INDETERMINATE_WRITE, expected.error);
            }
        }
    }

    @Test public void ordinaryAndUnsupportedAtomicMoveFailuresAreIndeterminateNotAbsent() throws Exception {
        for (boolean unsupported : new boolean[]{false, true}) {
            Path directory = privateRoot();
            AtomicReference<OwnerReceiptStore.WritePoint> armed = new AtomicReference<OwnerReceiptStore.WritePoint>();
            OwnerReceiptStore store = openStore(directory, observed -> {
                if (observed == armed.get()) {
                    if (unsupported) throw new AtomicMoveNotSupportedException("pending", "receipt", "injected");
                    throw new java.nio.file.FileSystemException("pending", "receipt", "injected move failure");
                }
            });
            OwnerReceiptStore.Claim generation = liveGeneration(store);
            armed.set(OwnerReceiptStore.WritePoint.BEFORE_RENAME);
            expect(INDETERMINATE_WRITE, () -> store.reserveOperation(generation, identity("one")));
            store.close();
            expect(INDETERMINATE_WRITE, () -> openStore(directory).lookup(identity("one")));
            assertTrue(Files.exists(directory.resolve("g-gen_1/op-one.receipt.pending")));
        }
    }

    @Test public void failureAfterRenameMustNotBeReportedAsNotWritten() throws Exception {
        AtomicReference<OwnerReceiptStore.WritePoint> armed = new AtomicReference<OwnerReceiptStore.WritePoint>();
        OwnerReceiptStore store = openStore(root, observed -> {
            if (observed == armed.get()) throw new IOException("after rename");
        });
        OwnerReceiptStore.Claim generation = liveGeneration(store);
        OwnerReceiptStore.Claim operation = store.reserveOperation(generation, identity("one")).newClaim;
        armed.set(OwnerReceiptStore.WritePoint.RENAMED);
        expect(INDETERMINATE_WRITE, () -> store.start(operation));
        expect(POISONED, () -> store.start(operation));
        store.close();
        assertEquals(STARTED, openStore(root).lookup(identity("one")).state);
    }

    private Path operationFile(String operation) { return root.resolve("g-gen_1/op-" + operation + ".receipt"); }
    private static long childCount(Path path) throws IOException {
        try (Stream<Path> files = Files.list(path)) { return files.count(); }
    }
    private interface Checked { void run() throws Exception; }
    private static void expect(OwnerReceiptStore.Error error, Checked call) throws Exception {
        try { call.run(); fail("expected " + error); }
        catch (OwnerReceiptStore.StoreException expected) { assertEquals(error, expected.error); }
    }
}
