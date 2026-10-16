package app.daiyu.android.vd.runtime;

import java.security.SecureRandom;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * {@code app_process} entrypoint for the root VirtualDisplay owner.
 *
 * <p>Bootstrap creates NO display: check root, prepare looper, acquire the fixed trusted receipt
 * root's lifetime lock, bind IPC, then publish the one private bootstrap line. READY means only
 * authentication is available. The authenticated application UID must initialize a trusted run
 * binding and a fresh durable STARTED generation before the coordinator creates the display.
 *
 * <p>The token is printed once on a PRIVATE launcher stdout pipe, never a log/model channel.
 * The trusted launcher supplies the application UID and pre-existing private receipt root.
 * Neither the receipt root nor display configuration can be overridden by business JSON.
 *
 * <p>The main looper prepared by {@link Looper#prepareMainLooper()} cannot be quit, so
 * {@link Looper#loop()} never returns. The process is ended only from the IPC stop callback, and
 * only after the owner reports the display released; a session that still has tasks is never
 * exited.
 *
 * <pre>
 * app_process /system/bin app.daiyu.android.vd.runtime.VirtualDisplayOwnerMain \
 *     --socket &lt;abstract-name&gt; --allow-uid &lt;app-uid&gt; --receipt-root &lt;private-root&gt; \
 *     [--width W] [--height H] [--density DPI] [--name NAME]
 * </pre>
 */
public final class VirtualDisplayOwnerMain {
    private static final String READY_PREFIX = "VD_OWNER_READY";
    private static final String ERROR_PREFIX = "VD_OWNER_ERROR";
    private static final String STOPPED_PREFIX = "VD_OWNER_STOPPED";

    private static final SecureRandom RANDOM = new SecureRandom();

    private VirtualDisplayOwnerMain() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException ex) {
            print(ERROR_PREFIX + " code=ARGS message=" + ex.getMessage());
            printUsage();
            return 2;
        }
        if (parsed.help) {
            printUsage();
            return 0;
        }
        if (Process.myUid() != 0) {
            print(ERROR_PREFIX + " code=ROOT_REQUIRED uid=" + Process.myUid());
            return 3;
        }

        Looper.prepareMainLooper();
        Handler handler = new Handler();

        final OwnerReceiptStore store;
        try {
            store = new OwnerReceiptStore(parsed.receiptRoot);
        } catch (IOException | RuntimeException ex) {
            print(ERROR_PREFIX + " code=RECEIPT_ROOT_UNAVAILABLE");
            return 4;
        }
        final OwnerReceiptCoordinator coordinator = new OwnerReceiptCoordinator(store,
                new OwnerReceiptCoordinator.Factory() {
                    @Override public OwnerReceiptCoordinator.Backend create() throws OwnerException {
                        return new OwnerCommandDispatcher(VirtualDisplayOwner.create(parsed.name,
                                parsed.width, parsed.height, parsed.densityDpi, handler));
                    }
                });
        String token = newToken();
        OwnerIpcServer server;
        try {
            server = new OwnerIpcServer(parsed.socket, parsed.allowUid, token, handler, coordinator,
                    new Runnable() {
                        @Override public void run() {
                            // Verified release AND durable operation/generation finals precede
                            // this callback, even if the response delivery was lost.
                            if (!coordinator.shouldStop()) return;
                            try {
                                coordinator.closeAfterStop();
                            } catch (IOException ex) {
                                print(ERROR_PREFIX + " code=RECEIPT_CLOSE_UNCONFIRMED");
                                return; // never advertise successful lifecycle completion
                            }
                            print(STOPPED_PREFIX + " released=true receiptsFinal=true");
                            System.exit(0);
                        }
                    });
        } catch (OwnerException ex) {
            // No initialize could run: no owner/display exists on this startup failure path.
            try { store.close(); } catch (IOException ignored) { /* process exits, no execution */ }
            print(ERROR_PREFIX + " code=IPC_UNAVAILABLE");
            return 5;
        }

        print(READY_PREFIX + " v=" + OwnerProtocol.VERSION
                + " socket=" + server.socketName()
                + " token=" + token
                + " pid=" + Process.myPid()
                + " uid=" + Process.myUid()
                + " allowUid=" + parsed.allowUid
                + " initialized=false");
        server.start();

        // The main looper prepared above cannot be quit, so this never returns. The session ends
        // from the stop callback, which calls System.exit(0) once the release has been verified.
        Looper.loop();
        // Not reached: the stop callback exits the process.
        return 0;
    }

    private static void print(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static void printUsage() {
        print("usage: app_process /system/bin app.daiyu.android.vd.runtime.VirtualDisplayOwnerMain "
                + "--socket <abstract-name> --allow-uid <app-uid> --receipt-root <private-root> "
                + "[--width W] [--height H] [--density DPI] [--name NAME]");
    }

    private static String newToken() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < random.length; i++) {
            sb.append(Character.forDigit((random[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(random[i] & 0xF, 16));
        }
        return sb.toString();
    }

    /** Parsed command line. Values stay {@code null} when the device geometry should be used. */
    static final class Args {
        final String socket;
        final Path receiptRoot;
        final int allowUid;
        final Integer width;
        final Integer height;
        final Integer densityDpi;
        final String name;
        final boolean help;

        private Args(String socket, Path receiptRoot, int allowUid, Integer width, Integer height, Integer densityDpi,
                String name, boolean help) {
            this.socket = socket;
            this.receiptRoot = receiptRoot;
            this.allowUid = allowUid;
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.name = name;
            this.help = help;
        }

        static Args parse(String[] args) {
            String socket = null;
            Path receiptRoot = null;
            Integer allowUid = null;
            Integer width = null;
            Integer height = null;
            Integer density = null;
            String name = null;
            boolean help = false;
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    if ("--help".equals(arg) || "-h".equals(arg)) {
                        help = true;
                    } else if ("--socket".equals(arg)) {
                        socket = value(args, ++i, arg);
                    } else if ("--receipt-root".equals(arg)) {
                        try { receiptRoot = Paths.get(value(args, ++i, arg)); }
                        catch (RuntimeException invalid) { throw new IllegalArgumentException("receipt-root"); }
                    } else if ("--allow-uid".equals(arg)) {
                        allowUid = Integer.valueOf(positive(args, ++i, arg));
                    } else if ("--width".equals(arg)) {
                        width = Integer.valueOf(positive(args, ++i, arg));
                    } else if ("--height".equals(arg)) {
                        height = Integer.valueOf(positive(args, ++i, arg));
                    } else if ("--density".equals(arg)) {
                        density = Integer.valueOf(positive(args, ++i, arg));
                    } else if ("--name".equals(arg)) {
                        name = value(args, ++i, arg);
                    } else {
                        throw new IllegalArgumentException("unknown arg");
                    }
                }
            }
            if (help) {
                return new Args(null, null, 0, null, null, null, null, true);
            }
            if (socket == null || socket.isEmpty()) {
                throw new IllegalArgumentException("socket required");
            }
            if (!OwnerProtocol.isSafeIdentifier(socket)) {
                throw new IllegalArgumentException("socket");
            }
            if (allowUid == null || allowUid.intValue() % 100000 < 10000
                    || allowUid.intValue() % 100000 > 19999) {
                throw new IllegalArgumentException("application allow-uid required");
            }
            if (receiptRoot == null || !receiptRoot.isAbsolute() || !receiptRoot.equals(receiptRoot.normalize())) {
                throw new IllegalArgumentException("absolute normalized receipt-root required");
            }
            return new Args(socket, receiptRoot, allowUid.intValue(), width, height, density, name, false);
        }

        private static String value(String[] args, int index, String flag) {
            if (index >= args.length || args[index] == null || args[index].isEmpty()) {
                throw new IllegalArgumentException(flag);
            }
            return args[index];
        }

        private static int positive(String[] args, int index, String flag) {
            String raw = value(args, index, flag);
            int parsed;
            try {
                parsed = Integer.parseInt(raw);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(flag);
            }
            if (parsed <= 0) {
                throw new IllegalArgumentException(flag);
            }
            return parsed;
        }
    }
}
