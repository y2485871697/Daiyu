package app.daiyu.android.vd.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Local durable receipts only. No display, IPC, operation execution, authentication or recovery
 * inference. Root must be a pre-existing private directory selected by the trusted host, never
 * a model/request path. Existing RESERVED/STARTED/UNKNOWN records NEVER grant execution.
 * One live instance exclusively owns a root until explicit close; even lookup needs ownership.
 * Close invalidates all instance claims, but the host must quiesce actual work before closing.
 *
 * <p>Requires a POSIX filesystem supporting file fsync, atomic same-directory rename and
 * directory fsync. Unsupported durability is an error, not a non-atomic fallback. The private
 * root and its ancestors must not be concurrently replaceable by untrusted writers; this is not
 * a sandbox against another compromised process with the same UID/root privileges.
 */
public final class OwnerReceiptStore implements AutoCloseable {
    public static final int MAX_FILE_BYTES = 96 * 1024;
    private static final int MAGIC = 0x4f525331; // ORS1, binary envelope version 1
    private static final Set<PosixFilePermission> DIR_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");

    public enum State {
        RESERVED, STARTED, SUCCEEDED, FAILED, UNKNOWN;
        public boolean isFinal() { return this == SUCCEEDED || this == FAILED || this == UNKNOWN; }
    }

    public enum Error { CONFLICT, UNSAFE_PATH, CORRUPT, INDETERMINATE_WRITE, POISONED, INVALID_CLAIM, BUSY, CLOSED }

    public static final class StoreException extends IOException {
        public final Error error;
        private StoreException(Error error) {
            super("owner receipt store: " + error.name());
            this.error = error;
        }
    }

    public static final class Receipt {
        public final boolean generationReceipt;
        public final String generation;
        public final String operationId;
        public final String operation;
        public final String digest;
        public final String bindingDigest;
        public final State state;
        /** Exact canonical JSON result, null before final. Caller must omit tokens/secrets. */
        public final String resultJson;

        private Receipt(boolean generationReceipt, String generation, String operationId,
                        String operation, String digest, String bindingDigest, State state,
                        String resultJson) {
            this.generationReceipt = generationReceipt;
            this.generation = generation;
            this.operationId = operationId;
            this.operation = operation;
            this.digest = digest;
            this.bindingDigest = bindingDigest;
            this.state = state;
            this.resultJson = resultJson;
        }

        private Receipt withState(State state, String result) {
            return new Receipt(generationReceipt, generation, operationId, operation, digest,
                    bindingDigest, state, result);
        }
    }

    /** An opaque, instance-local capability. It cannot be recreated by deserializing a receipt. */
    public static final class Claim {
        private final OwnerReceiptStore owner;
        private final Receipt reserved;
        private boolean startConsumed;
        private boolean started;
        private Claim(OwnerReceiptStore owner, Receipt reserved) {
            this.owner = owner;
            this.reserved = reserved;
        }
    }

    public static final class Reservation {
        public final Receipt receipt;
        /** Non-null ONLY after a new reservation has durably committed in this store instance. */
        public final Claim newClaim;
        private Reservation(Receipt receipt, Claim claim) {
            this.receipt = receipt;
            this.newClaim = claim;
        }
    }

    public static final class Start {
        public final Receipt receipt;
        /** True only on the one successful fresh RESERVED -> STARTED transition. */
        public final boolean mayExecute;
        private Start(Receipt receipt, boolean mayExecute) {
            this.receipt = receipt;
            this.mayExecute = mayExecute;
        }
    }

    // Test-only fault points; a failure at ANY stage is indeterminate, including rename errors.
    enum WritePoint { BEFORE_WRITE, FILE_SYNCED, BEFORE_RENAME, RENAMED, DIRECTORY_SYNCED }
    interface WriteFault { void at(WritePoint point) throws IOException; }
    // Avoid even opening a second channel in this JVM: on some systems closing any channel
    // for a file can release the process's locks. Entries survive uncertain close failures.
    private static final Set<Path> LIVE_ROOTS = new HashSet<Path>();
    enum LifecyclePoint { LOCKED, CHANNEL_CLOSED }
    interface LifecycleFault { void at(LifecyclePoint point) throws IOException; }
    interface PlatformPrimitives {
        Set<PosixFilePermission> permissions(Path path) throws IOException;
        void createPrivateDirectory(Path path) throws IOException;
        FileChannel createPrivateFile(Path path) throws IOException;
        boolean isSymbolicLink(Path path);
        void syncDirectory(Path directory) throws IOException;
    }
    static final PlatformPrimitives STRICT = new PlatformPrimitives() {
        public Set<PosixFilePermission> permissions(Path path) throws IOException {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        }
        public void createPrivateDirectory(Path path) throws IOException {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIR_MODE));
        }
        public FileChannel createPrivateFile(Path path) throws IOException {
            return FileChannel.open(path, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(FILE_MODE));
        }
        public boolean isSymbolicLink(Path path) { return Files.isSymbolicLink(path); }
        public void syncDirectory(Path directory) throws IOException {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
        }
    };
    private final PlatformPrimitives platform;
    private final Path root;
    private final UserPrincipal owner;
    private final WriteFault fault;
    private final LifecycleFault lifecycleFault;
    private final FileChannel lockChannel;
    private final FileLock ownerLock;
    private boolean poisoned;
    private boolean closed;
    private boolean released;
    private IOException closeFailure;

    public OwnerReceiptStore(Path trustedPrivateRoot) throws IOException {
        this(trustedPrivateRoot, point -> { });
    }

    OwnerReceiptStore(Path trustedPrivateRoot, WriteFault fault) throws IOException {
        this(trustedPrivateRoot, fault, point -> { });
    }

    // Test-only lifecycle fault injection; production always uses the no-op callback.
    OwnerReceiptStore(Path trustedPrivateRoot, WriteFault fault, LifecycleFault lifecycleFault)
            throws IOException {
        this(trustedPrivateRoot, fault, lifecycleFault, STRICT);
    }

    OwnerReceiptStore(Path trustedPrivateRoot, WriteFault fault, LifecycleFault lifecycleFault,
            PlatformPrimitives platform) throws IOException {
        if (platform == null || trustedPrivateRoot == null || fault == null || lifecycleFault == null
                || !trustedPrivateRoot.isAbsolute()
                || !trustedPrivateRoot.equals(trustedPrivateRoot.normalize())) {
            throw failure(Error.UNSAFE_PATH);
        }
        this.platform = platform;
        this.root = trustedPrivateRoot;
        this.fault = fault;
        this.lifecycleFault = lifecycleFault;
        checkAncestors(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !platform.permissions(root).equals(DIR_MODE)) {
            throw failure(Error.UNSAFE_PATH);
        }
        owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        synchronized (LIVE_ROOTS) {
            if (!LIVE_ROOTS.add(root)) throw failure(Error.BUSY);
        }
        FileChannel channel = null;
        try {
            Path lock = root.resolve(".receipt.lock");
            if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    channel = platform.createPrivateFile(lock);
                } catch (java.nio.file.FileAlreadyExistsException concurrentConstructor) {
                    // Another trusted process created it; validate before opening.
                }
            }
            checkPrivate(lock, false);
            if (channel == null) {
                channel = FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            }
            FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                throw failure(Error.BUSY);
            }
            if (acquired == null) throw failure(Error.BUSY);
            lifecycleFault.at(LifecyclePoint.LOCKED);
            channel.force(true);
            syncDirectory(root);
            lockChannel = channel;
            ownerLock = acquired;
        } catch (IOException | RuntimeException | java.lang.Error error) {
            boolean safelyClosed = channel == null;
            if (channel != null) {
                try {
                    channel.close(); // Also releases any acquired lock.
                    safelyClosed = true;
                } catch (IOException | RuntimeException | java.lang.Error cleanupFailure) {
                    error.addSuppressed(cleanupFailure);
                }
            }
            if (safelyClosed) releaseRoot();
            throw error;
        }
    }

    /**
     * Ends this instance and all its claims. The host must first quiesce actual owner work;
     * closing receipts does not stop a process, revoke an already returned permit or end a lease.
     * A failed close is terminal and never advertised as safe release, including on retry.
     */
    @Override public synchronized void close() throws IOException {
        if (released) return;
        if (closed) throw new IOException("owner receipt lock release unconfirmed", closeFailure);
        closed = true;
        try {
            lockChannel.close(); // Closing the lifetime channel releases its lock.
            lifecycleFault.at(LifecyclePoint.CHANNEL_CLOSED);
            if (lockChannel.isOpen() || ownerLock.isValid()) {
                throw new IOException("owner receipt lock release unconfirmed");
            }
            releaseRoot();
            released = true;
        } catch (IOException error) {
            closeFailure = error;
            throw error;
        } catch (RuntimeException error) {
            closeFailure = new IOException("owner receipt lock close failed", error);
            throw closeFailure;
        }
    }

    private void releaseRoot() {
        synchronized (LIVE_ROOTS) { LIVE_ROOTS.remove(root); }
    }

    private void requireOpen() throws StoreException {
        if (closed || !lockChannel.isOpen() || !ownerLock.isValid()) throw failure(Error.CLOSED);
    }

    /** Durable owner generation claim, BEFORE any display/owner resource creation. */
    public synchronized Reservation reserveGeneration(String generation,
            OwnerOperationIdentity.RunBinding trustedBinding) throws IOException {
        requireOpen();
        OwnerOperationIdentity.pathId(generation);
        Receipt wanted = new Receipt(true, generation, "", "",
                OwnerOperationIdentity.generationDigest(generation, trustedBinding),
                OwnerOperationIdentity.bindingDigest(trustedBinding), State.RESERVED, null);
        return locked(true, () -> {
            Path directory = generationDirectory(generation);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                checkPrivate(directory, true);
                Receipt existing = readRequired(path(wanted));
                match(existing, wanted);
                return new Reservation(existing, null);
            }
            platform.createPrivateDirectory(directory);
            syncDirectory(root);
            persist(wanted);
            return new Reservation(wanted, new Claim(this, wanted));
        });
    }

    /**
     * Only a live, freshly started generation may reserve operations. An old persisted
     * generation is not authority to recreate resources or continue execution after restart.
     */
    public synchronized Reservation reserveOperation(Claim liveGeneration,
            OwnerOperationIdentity identity) throws IOException {
        requireClaim(liveGeneration);
        if (identity == null) throw failure(Error.INVALID_CLAIM);
        Receipt wanted = fromIdentity(identity);
        return locked(true, () -> {
            Receipt generation = readRequired(path(liveGeneration.reserved));
            match(generation, liveGeneration.reserved);
            if (!generation.generationReceipt || !liveGeneration.started
                    || generation.state != State.STARTED) {
                throw failure(Error.INVALID_CLAIM);
            }
            if (!generation.generation.equals(identity.generation)
                    || !generation.bindingDigest.equals(identity.bindingDigest)) {
                throw failure(Error.CONFLICT);
            }
            Receipt old = readOptional(path(wanted));
            if (old != null) {
                match(old, wanted);
                return new Reservation(old, null);
            }
            persist(wanted);
            return new Reservation(wanted, new Claim(this, wanted));
        });
    }

    /** Persist STARTED before returning the one execution permit; errors never grant a permit. */
    public synchronized Start start(Claim claim) throws IOException {
        requireClaim(claim);
        return locked(true, () -> {
            Receipt old = readRequired(path(claim.reserved));
            match(old, claim.reserved);
            if (claim.startConsumed) return new Start(old, false);
            claim.startConsumed = true; // Consumed even if persistence throws.
            if (old.state != State.RESERVED) return new Start(old, false);
            if (!old.generationReceipt) {
                Receipt generation = readRequired(generationDirectory(old.generation)
                        .resolve("generation.receipt"));
                if (!generation.generationReceipt || generation.state != State.STARTED
                        || !generation.generation.equals(old.generation)
                        || !generation.bindingDigest.equals(old.bindingDigest)) {
                    throw failure(Error.INVALID_CLAIM);
                }
            }
            Receipt started = old.withState(State.STARTED, null);
            persist(started);
            claim.started = true;
            return new Start(started, true);
        });
    }

    /**
     * Final is immutable, including UNKNOWN. Duplicate completion returns the OLD result,
     * never overwrites it. Result construction must omit secrets; no raw business args are stored.
     */
    public synchronized Receipt complete(Claim claim, State finalState,
            Map<String, ?> result) throws IOException {
        requireClaim(claim);
        if (finalState == null || !finalState.isFinal()) throw failure(Error.INVALID_CLAIM);
        return locked(true, () -> {
            Receipt old = readRequired(path(claim.reserved));
            match(old, claim.reserved);
            if (old.state.isFinal()) return old;
            if (!claim.started || old.state != State.STARTED || result == null) {
                throw failure(Error.INVALID_CLAIM);
            }
            String canonicalResult = OwnerOperationIdentity.canonicalJson(result);
            Receipt completed = old.withState(finalState, canonicalResult);
            persist(completed);
            return completed;
        });
    }

    /** Read-only authenticated-host lookup building block; no directory/display/op creation. */
    public synchronized Receipt lookup(OwnerOperationIdentity identity) throws IOException {
        requireOpen();
        if (identity == null) throw failure(Error.INVALID_CLAIM);
        Receipt wanted = fromIdentity(identity);
        return locked(false, () -> {
            Path directory = generationDirectory(identity.generation);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
            checkPrivate(directory, true);
            Receipt generation = readRequired(directory.resolve("generation.receipt"));
            if (!generation.generationReceipt || !generation.generation.equals(identity.generation)
                    || !generation.bindingDigest.equals(identity.bindingDigest)) {
                throw failure(Error.CONFLICT);
            }
            Receipt old = readOptional(path(wanted));
            if (old != null) match(old, wanted);
            return old;
        });
    }

    public synchronized Receipt lookupGeneration(String generation,
            OwnerOperationIdentity.RunBinding binding) throws IOException {
        requireOpen();
        Receipt wanted = new Receipt(true, OwnerOperationIdentity.pathId(generation), "", "",
                OwnerOperationIdentity.generationDigest(generation, binding),
                OwnerOperationIdentity.bindingDigest(binding), State.RESERVED, null);
        return locked(false, () -> {
            Path directory = generationDirectory(generation);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
            checkPrivate(directory, true);
            Receipt old = readRequired(path(wanted));
            match(old, wanted);
            return old;
        });
    }

    private Receipt fromIdentity(OwnerOperationIdentity identity) {
        return new Receipt(false, identity.generation, identity.operationId, identity.operation,
                identity.digest, identity.bindingDigest, State.RESERVED, null);
    }

    private void requireClaim(Claim claim) throws StoreException {
        requireOpen();
        if (claim == null || claim.owner != this) throw failure(Error.INVALID_CLAIM);
    }

    private interface IoCall<T> { T run() throws IOException; }

    // Public methods are synchronized; the file lock is held for the entire instance lifetime.
    private <T> T locked(boolean mutation, IoCall<T> call) throws IOException {
        requireOpen();
        if (mutation && poisoned) throw failure(Error.POISONED);
        checkPrivate(root, true);
        checkPrivate(root.resolve(".receipt.lock"), false);
        try {
            return call.run();
        } catch (IOException error) {
            // Conflict/validation is definite; an arbitrary I/O failure is NOT proof of no write.
            if (mutation && !(error instanceof StoreException)) {
                poisoned = true;
                throw failure(Error.INDETERMINATE_WRITE);
            }
            throw error;
        } catch (UnsupportedOperationException | SecurityException error) {
            if (mutation) poisoned = true;
            throw failure(mutation ? Error.INDETERMINATE_WRITE : Error.UNSAFE_PATH);
        }
    }

    private Path generationDirectory(String generation) {
        return root.resolve("g-" + OwnerOperationIdentity.pathId(generation));
    }

    private Path path(Receipt receipt) {
        return generationDirectory(receipt.generation).resolve(receipt.generationReceipt
                ? "generation.receipt" : "op-" + OwnerOperationIdentity.pathId(receipt.operationId) + ".receipt");
    }

    private void match(Receipt old, Receipt wanted) throws StoreException {
        if (old.generationReceipt != wanted.generationReceipt
                || !old.generation.equals(wanted.generation)
                || !old.operationId.equals(wanted.operationId)
                || !old.operation.equals(wanted.operation)
                || !old.digest.equals(wanted.digest)
                || !old.bindingDigest.equals(wanted.bindingDigest)) throw failure(Error.CONFLICT);
    }

    private Receipt readRequired(Path target) throws IOException {
        Receipt receipt = readOptional(target);
        if (receipt == null) throw failure(Error.CORRUPT); // Incomplete generation/vanished claim.
        return receipt;
    }

    private Receipt readOptional(Path target) throws IOException {
        checkPrivate(target.getParent(), true);
        Path pending = target.resolveSibling(target.getFileName().toString() + ".pending");
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            // Never delete/replay an abandoned temporary file or guess whether rename happened.
            throw failure(Error.INDETERMINATE_WRITE);
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
        checkPrivate(target, false);
        byte[] bytes;
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 68 || size > MAX_FILE_BYTES) throw failure(Error.CORRUPT);
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw failure(Error.CORRUPT);
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) throw failure(Error.CORRUPT);
            bytes = buffer.array();
        }
        return decode(bytes);
    }

    private void persist(Receipt receipt) throws IOException {
        Path target = path(receipt);
        checkPrivate(target.getParent(), true);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) checkPrivate(target, false);
        Path pending = target.resolveSibling(target.getFileName().toString() + ".pending");
        try {
            if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) throw failure(Error.INDETERMINATE_WRITE);
            byte[] bytes = encode(receipt);
            fault.at(WritePoint.BEFORE_WRITE);
            try (FileChannel channel = platform.createPrivateFile(pending)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            fault.at(WritePoint.FILE_SYNCED);
            // Persist the pending marker too: if rename fails, reopening cannot treat it as absent.
            syncDirectory(target.getParent());
            fault.at(WritePoint.BEFORE_RENAME);
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fault.at(WritePoint.RENAMED);
            syncDirectory(target.getParent());
            fault.at(WritePoint.DIRECTORY_SYNCED);
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            // Includes ordinary atomicMove errors: it may have committed before reporting failure.
            // Leave pending evidence; never fall back, delete, retry or grant an execution claim.
            poisoned = true;
            throw failure(Error.INDETERMINATE_WRITE);
        }
    }

    private void checkPrivate(Path path, boolean directory) throws IOException {
        checkAncestors(path);
        if ((directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                       : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)
                || !platform.permissions(path)
                        .equals(directory ? DIR_MODE : FILE_MODE)) throw failure(Error.UNSAFE_PATH);
    }

    private void checkAncestors(Path path) throws IOException {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (platform.isSymbolicLink(cursor)) throw failure(Error.UNSAFE_PATH);
        }
    }

    private void syncDirectory(Path directory) throws IOException { platform.syncDirectory(directory); }

    private static byte[] encode(Receipt receipt) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeInt(MAGIC);
        out.writeBoolean(receipt.generationReceipt);
        writeString(out, receipt.generation);
        writeString(out, receipt.operationId);
        writeString(out, receipt.operation);
        writeString(out, receipt.digest);
        writeString(out, receipt.bindingDigest);
        writeString(out, receipt.state.name());
        writeString(out, receipt.resultJson == null ? "" : receipt.resultJson);
        out.flush();
        byte[] content = body.toByteArray();
        body.write(OwnerOperationIdentity.sha256(content).getBytes(StandardCharsets.US_ASCII));
        byte[] bytes = body.toByteArray();
        if (bytes.length > MAX_FILE_BYTES) throw failure(Error.CORRUPT);
        return bytes;
    }

    private static Receipt decode(byte[] bytes) throws IOException {
        try {
            int bodyLength = bytes.length - 64;
            byte[] body = Arrays.copyOf(bytes, bodyLength);
            String checksum = new String(bytes, bodyLength, 64, StandardCharsets.US_ASCII);
            if (!OwnerOperationIdentity.sha256(body).equals(checksum)) throw failure(Error.CORRUPT);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            if (in.readInt() != MAGIC) throw failure(Error.CORRUPT);
            int kind = in.readUnsignedByte();
            if (kind > 1) throw failure(Error.CORRUPT);
            boolean generationReceipt = kind == 1;
            String generation = OwnerOperationIdentity.pathId(readString(in));
            String operationId = readString(in);
            String operation = readString(in);
            if (generationReceipt) {
                if (!operationId.isEmpty() || !operation.isEmpty()) throw failure(Error.CORRUPT);
            } else {
                OwnerOperationIdentity.pathId(operationId);
                OwnerOperationIdentity.pathId(operation);
            }
            String digest = readString(in);
            String binding = readString(in);
            if (!digest.matches("[0-9a-f]{64}") || !binding.matches("[0-9a-f]{64}")) throw failure(Error.CORRUPT);
            State state = State.valueOf(readString(in));
            String result = readString(in);
            if (in.available() != 0 || (state.isFinal() ? result.isEmpty() : !result.isEmpty())) {
                throw failure(Error.CORRUPT);
            }
            if (result.getBytes(StandardCharsets.UTF_8).length > OwnerOperationIdentity.MAX_CANONICAL_BYTES) {
                throw failure(Error.CORRUPT);
            }
            return new Receipt(generationReceipt, generation, operationId, operation,
                    digest, binding, state, state.isFinal() ? result : null);
        } catch (IOException | IllegalArgumentException error) {
            throw failure(Error.CORRUPT);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > OwnerOperationIdentity.MAX_CANONICAL_BYTES || size > in.available()) {
            throw failure(Error.CORRUPT);
        }
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw failure(Error.CORRUPT);
        }
    }

    private static StoreException failure(Error error) { return new StoreException(error); }
}
