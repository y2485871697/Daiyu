package app.daiyu.android.vd.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Non-POSIX metadata model only. Bytes, atomic replacement and advisory locks remain real. */
final class OwnerReceiptTestPlatform implements OwnerReceiptStore.PlatformPrimitives {
    private final Map<Path, Set<PosixFilePermission>> modes = new HashMap<>();
    private final Set<Path> links = new HashSet<>();
    private Path key(Path path) { return path.toAbsolutePath().normalize(); }
    private boolean posix(Path path) throws IOException {
        Path existing = path.toAbsolutePath();
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        boolean supported = Files.getFileAttributeView(existing, java.nio.file.attribute.PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) != null;
        if (!supported && java.io.File.separatorChar != '\\') throw new IOException("POSIX filesystem required");
        return supported;
    }
    void setPermissions(Path path, Set<PosixFilePermission> mode) throws IOException {
        if (posix(path)) Files.setPosixFilePermissions(path, mode);
        else modes.put(key(path), new HashSet<>(mode));
    }
    public Set<PosixFilePermission> permissions(Path path) throws IOException {
        if (posix(path)) return OwnerReceiptStore.STRICT.permissions(path);
        Set<PosixFilePermission> result = modes.get(key(path));
        if (result == null) throw new IOException("missing host permission metadata");
        return new HashSet<>(result);
    }
    public void createPrivateDirectory(Path path) throws IOException {
        if (posix(path)) OwnerReceiptStore.STRICT.createPrivateDirectory(path);
        else { Files.createDirectory(path); setPermissions(path, PosixFilePermissions.fromString("rwx------")); }
    }
    public FileChannel createPrivateFile(Path path) throws IOException {
        if (posix(path)) return OwnerReceiptStore.STRICT.createPrivateFile(path);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        setPermissions(path, PosixFilePermissions.fromString("rw-------"));
        String name = path.getFileName().toString();
        if (name.endsWith(".pending")) setPermissions(path.resolveSibling(name.substring(0, name.length() - 8)),
                PosixFilePermissions.fromString("rw-------"));
        return channel;
    }
    public boolean isSymbolicLink(Path path) { return Files.isSymbolicLink(path) || links.contains(key(path)); }
    public void syncDirectory(Path path) throws IOException {
        if (posix(path)) OwnerReceiptStore.STRICT.syncDirectory(path);
        else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || isSymbolicLink(path))
            throw new IOException("invalid host directory barrier");
    }
    void createSymbolicLink(Path path, Path target) throws IOException {
        if (posix(path)) Files.createSymbolicLink(path, target);
        else { Files.createFile(path); links.add(key(path)); }
    }
    void delete(Path path) throws IOException { Files.delete(path); links.remove(key(path)); modes.remove(key(path)); }
    OwnerReceiptStore open(Path path) throws IOException { return open(path, point -> {}); }
    OwnerReceiptStore open(Path path, OwnerReceiptStore.WriteFault fault) throws IOException {
        return open(path, fault, point -> {});
    }
    OwnerReceiptStore open(Path path, OwnerReceiptStore.WriteFault fault, OwnerReceiptStore.LifecycleFault fault2)
            throws IOException { return new OwnerReceiptStore(path, fault, fault2, this); }
}
