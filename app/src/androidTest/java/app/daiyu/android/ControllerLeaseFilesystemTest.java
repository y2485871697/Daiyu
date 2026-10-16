package app.daiyu.android;

import android.test.InstrumentationTestCase;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Device-only filesystem capability checks. Not executed as part of the host suite, and not a
 * power-loss durability claim. Uses only a disposable noBackup child, no owner/display or shell. */
public class ControllerLeaseFilesystemTest extends InstrumentationTestCase {
    public void testNoBackupModesDirectoryForceAtomicMoveAndProcessLock() throws Exception {
        Path noBackup = getInstrumentation().getTargetContext().getNoBackupFilesDir()
                .getCanonicalFile().toPath();
        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(noBackup, LinkOption.NOFOLLOW_LINKS));
        Path root = noBackup.resolve("r08-filesystem-test-" + UUID.randomUUID());
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        try {
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS));
            Path pending = root.resolve("claim.pending");
            byte[] record = new byte[] { 1, 2, 3, 4 };
            try (FileChannel file = FileChannel.open(pending,
                    java.util.EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                assertEquals(PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(pending, LinkOption.NOFOLLOW_LINKS));
                forceDirectory(root);
                ByteBuffer bytes = ByteBuffer.wrap(record);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            Path published = root.resolve("claim.event");
            Files.move(pending, published, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(root);
            forceDirectory(noBackup);
            assertFalse(Files.exists(pending, LinkOption.NOFOLLOW_LINKS));
            assertTrue(java.util.Arrays.equals(record, Files.readAllBytes(published)));
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(published, LinkOption.NOFOLLOW_LINKS));
            Path lockPath = root.resolve("lease.lock");
            try (FileChannel channel = FileChannel.open(lockPath,
                    java.util.EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                FileLock lock = channel.tryLock();
                assertNotNull(lock);
                try { assertEquals(23, probe(root, lockPath)); }
                finally { lock.release(); }
                assertEquals(0, probe(root, lockPath));
            }
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void forceDirectory(Path directory) throws Exception {
        try (FileChannel channel = FileChannel.open(directory,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true); // no ignore-on-error / filesystem fallback
        }
    }

    private int probe(Path root, Path lock) throws Exception {
        Path log = root.resolve("probe.log");
        ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                LockProbe.class.getName(), lock.toString());
        builder.environment().put("CLASSPATH", getInstrumentation().getContext().getPackageCodePath());
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue("app_process lock probe timed out", process.waitFor(30, TimeUnit.SECONDS));
            int code = process.exitValue();
            assertTrue("app_process probe failed: " + new String(Files.readAllBytes(log),
                    java.nio.charset.StandardCharsets.UTF_8), code == 0 || code == 23);
            return code;
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        }
    }

    /** Runs under the same app UID but a distinct kernel process, with no shared Semaphore. */
    public static class LockProbe {
        public static void main(String[] args) throws Exception {
            try (FileChannel channel = FileChannel.open(java.nio.file.Paths.get(args[0]),
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock = channel.tryLock();
                if (lock == null) System.exit(23);
                lock.release();
            }
            System.exit(0);
        }
    }
}
