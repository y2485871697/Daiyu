package app.daiyu.android.vd.runtime;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Wire contract between the app-side caller and the root {@code app_process} VirtualDisplay owner.
 *
 * <p>Framing: one UTF-8 JSON object per line in each direction, terminated by {@code '\n'}.
 * The owner never writes a bare newline inside a response because {@link #encodeLine} relies on
 * {@link JSONObject#toString()} escaping control characters.
 *
 * <p>Every wire request carries {@code v=2}, {@code op} and {@code token}; the bootstrap token
 * belongs only on the launcher's private stdout pipe, never logs or model data. Business requests
 * also carry generation/operationId/parameters. {@link #parseWireRequest} is the ONLY IPC parser;
 * the package-only flat decoder exists solely for the unchanged owner's internal wrap method.
 * UID and token checks precede dispatch, initialization, receipt reads and display work.
 *
 * <p>Operations whose behaviour is not implemented are listed in {@link #MISSING_OPS} and answer
 * with {@code ok=false}. There is no catch-all "success" reply: an unknown op, a bad token or a
 * failed side effect always produces an {@code error} code.
 */
public final class OwnerProtocol {
    public static final int VERSION = 2;

    public static final String OP_INITIALIZE = "initialize";
    public static final String OP_RECEIPT = "receipt";

    public static final String OP_STATUS = "status";
    public static final String OP_LAUNCH = "launch";
    public static final String OP_INPUT = "input";
    public static final String OP_SNAPSHOT = "snapshot";
    public static final String OP_HANDOFF = "handoff";
    public static final String OP_RELEASE = "release";

    /** Operations this build answers successfully. */
    public static final String[] SUPPORTED_OPS = {
            OP_INITIALIZE, OP_RECEIPT,
            OP_STATUS, OP_LAUNCH, OP_INPUT, OP_SNAPSHOT, OP_HANDOFF, OP_RELEASE,
    };

    /** Operations that are named in the protocol but deliberately not implemented yet. */
    public static final String[] MISSING_OPS = {};

    public static final String FIELD_VERSION = "v";
    public static final String FIELD_OP = "op";
    public static final String FIELD_TOKEN = "token";
    public static final String FIELD_OK = "ok";
    public static final String FIELD_ERROR = "error";
    public static final String FIELD_MESSAGE = "message";

    public static final String ERROR_PROTOCOL = "PROTOCOL";
    public static final String ERROR_REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE";
    public static final String ERROR_AUTH_REQUIRED = "AUTH_REQUIRED";
    public static final String ERROR_AUTH_REJECTED = "AUTH_REJECTED";
    public static final String ERROR_PEER_REJECTED = "PEER_REJECTED";
    public static final String ERROR_SESSION_BUSY = "SESSION_BUSY";
    public static final String ERROR_UNKNOWN_OP = "UNKNOWN_OP";
    public static final String ERROR_NOT_IMPLEMENTED_HANDOFF = "NOT_IMPLEMENTED_HANDOFF";
    public static final String ERROR_DISPLAY_NOT_READY = "DISPLAY_NOT_READY";
    public static final String ERROR_DISPLAY_REBOUND = "DISPLAY_REBOUND";
    public static final String ERROR_SOURCE_NOT_EMPTY = "SOURCE_NOT_EMPTY";
    public static final String ERROR_SOURCE_STATE_UNKNOWN = "SOURCE_STATE_UNKNOWN";
    public static final String ERROR_LAUNCH_FAILED = "LAUNCH_FAILED";
    public static final String ERROR_INPUT_FAILED = "INPUT_FAILED";
    public static final String ERROR_SNAPSHOT_FAILED = "SNAPSHOT_FAILED";
    public static final String ERROR_IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE";
    public static final String ERROR_NO_FRAME = "NO_FRAME";
    public static final String ERROR_ALREADY_RELEASED = "ALREADY_RELEASED";
    public static final String ERROR_INTERNAL = "INTERNAL";

    /** A single request line above this many characters is rejected before JSON parsing. */
    public static final int MAX_REQUEST_CHARS = 64 * 1024;

    private static final int MAX_TEXT_CHARS = 4096;
    private static final int MAX_TOKEN_CHARS = 256;

    private static final Set<String> OPS = new HashSet<String>(Arrays.asList(
            OP_INITIALIZE, OP_RECEIPT,
            OP_STATUS, OP_LAUNCH, OP_INPUT, OP_SNAPSHOT, OP_HANDOFF, OP_RELEASE));

    private OwnerProtocol() {
    }

    /** Parsed envelope. v2 rejects every unknown field; payload is never logged or persisted. */
    public static final class Request {
        public final String op;
        public final String token;
        public final JSONObject payload;

        Request(String op, String token, JSONObject payload) {
            this.op = op;
            this.token = token;
            this.payload = payload;
        }

        public boolean has(String key) {
            return payload.has(key);
        }

        /** Required non-empty string with no control characters. */
        public String requireString(String key) throws OwnerProtocolException {
            Object raw = payload.opt(key);
            if (!(raw instanceof String)) {
                throw new OwnerProtocolException(OwnerProtocol.ERROR_PROTOCOL, "field " + key);
            }
            String value = (String) raw;
            if (value.isEmpty() || !OwnerProtocol.isSafeText(value, MAX_TEXT_CHARS)) {
                throw new OwnerProtocolException(OwnerProtocol.ERROR_PROTOCOL, "field " + key);
            }
            return value;
        }

        /** Optional string; {@code null} when absent. */
        public String optionalString(String key) throws OwnerProtocolException {
            if (!payload.has(key)) {
                return null;
            }
            return requireString(key);
        }

        /** Required int without string coercion. */
        public int requireInt(String key) throws OwnerProtocolException {
            Integer value = OwnerProtocol.asInt(payload.opt(key));
            if (value == null) {
                throw new OwnerProtocolException(OwnerProtocol.ERROR_PROTOCOL, "field " + key);
            }
            return value.intValue();
        }

        /** Optional int without string coercion. */
        public int optionalInt(String key, int fallback) throws OwnerProtocolException {
            if (!payload.has(key)) {
                return fallback;
            }
            return requireInt(key);
        }

        /** Optional boolean; {@code fallback} when absent, rejected when not a real boolean. */
        public boolean optionalBoolean(String key, boolean fallback) throws OwnerProtocolException {
            if (!payload.has(key)) {
                return fallback;
            }
            Object raw = payload.opt(key);
            if (!(raw instanceof Boolean)) {
                throw new OwnerProtocolException(OwnerProtocol.ERROR_PROTOCOL, "field " + key);
            }
            return ((Boolean) raw).booleanValue();
        }
    }

    /** Strict public IPC entry. The socket MUST use this, never the internal owner decoder. */
    public static Request parseWireRequest(String line) throws OwnerProtocolException {
        if (line == null || line.isEmpty()) {
            throw new OwnerProtocolException(ERROR_PROTOCOL, "empty line");
        }
        if (line.length() > MAX_REQUEST_CHARS) {
            throw new OwnerProtocolException(ERROR_REQUEST_TOO_LARGE, "line too long");
        }
        JSONObject obj;
        try {
            obj = new JSONObject(line);
        } catch (JSONException ex) {
            throw new OwnerProtocolException(ERROR_PROTOCOL, "not json");
        }
        if (!isVersion(obj.opt(FIELD_VERSION))) {
            throw new OwnerProtocolException(ERROR_PROTOCOL, "version");
        }
        Object opRaw = obj.opt(FIELD_OP);
        if (!(opRaw instanceof String)) {
            throw new OwnerProtocolException(ERROR_PROTOCOL, "op");
        }
        String op = (String) opRaw;
        if (!OPS.contains(op)) {
            throw new OwnerProtocolException(ERROR_UNKNOWN_OP, "unknown operation");
        }
        Object tokenRaw = obj.opt(FIELD_TOKEN);
        if (!(tokenRaw instanceof String)) {
            throw new OwnerProtocolException(ERROR_AUTH_REQUIRED, "token");
        }
        String token = (String) tokenRaw;
        if (token.isEmpty() || token.length() > MAX_TOKEN_CHARS) {
            throw new OwnerProtocolException(ERROR_AUTH_REQUIRED, "token");
        }
        Request request = new Request(op, token, obj);
        if (OP_INITIALIZE.equals(op)) {
            onlyFields(obj, "v", "op", "token", "generation", "binding");
            pathId(request.requireString("generation"));
            trustedBindingObject(obj);
        } else if (OP_RECEIPT.equals(op)) {
            onlyFields(obj, "v", "op", "token", "generation", "binding", "operation",
                    "operationId", "parameters");
            pathId(request.requireString("generation"));
            trustedBindingObject(obj);
            if (obj.has("operation")) {
                String target = request.requireString("operation");
                if (!isBusinessOperation(target)) throw protocol("receipt operation");
                pathId(request.requireString("operationId"));
                businessParameters(target, object(obj, "parameters"));
            } else if (obj.has("operationId") || obj.has("parameters")) {
                throw protocol("generation lookup fields");
            }
        } else {
            onlyFields(obj, "v", "op", "token", "generation", "operationId", "parameters");
            pathId(request.requireString("generation"));
            pathId(request.requireString("operationId"));
            businessParameters(op, object(obj, "parameters"));
        }
        return request;
    }

    /**
     * Package-only compatibility seam for VirtualDisplayOwner.wrap, NOT an IPC decoder.
     * Original owner entrypoints consume flat parameters and reparse them. The dispatcher adds
     * only v/op/a public non-credential marker; authentication has already happened in IPC.
     * Kept separate so no flat request can bypass the strict wire receipt envelope.
     */
    static Request parseRequest(String line) throws OwnerProtocolException {
        if (line == null || line.length() > MAX_REQUEST_CHARS) throw protocol("owner parameters");
        try {
            JSONObject object = new JSONObject(line);
            if (!isVersion(object.opt("v"))) throw protocol("version");
            Request header = new Request("", "", object);
            String op = header.requireString("op");
            if (!isBusinessOperation(op)) throw protocol("owner operation");
            header.requireString("token"); // legacy parser field, never the authentication token
            object.remove("v"); object.remove("op"); object.remove("token");
            return new Request(op, "", businessParameters(op, object));
        } catch (JSONException invalid) { throw protocol("owner parameters"); }
    }

    static JSONObject ownerBusinessPayload(Request request) {
        try {
            JSONObject parameters = new JSONObject(request.payload.toString());
            parameters.put("v", VERSION);
            parameters.put("op", request.op);
            parameters.put("token", "internal-parameters-not-a-credential");
            return parameters;
        } catch (JSONException invalid) { throw new IllegalArgumentException("owner parameters"); }
    }

    static boolean isBusinessOperation(String op) {
        return OPS.contains(op) && !OP_INITIALIZE.equals(op) && !OP_RECEIPT.equals(op);
    }

    static String pathId(String value) throws OwnerProtocolException {
        try { return OwnerOperationIdentity.pathId(value); }
        catch (IllegalArgumentException invalid) { throw protocol("identity"); }
    }

    static JSONObject object(JSONObject parent, String key) throws OwnerProtocolException {
        Object raw = parent.opt(key);
        if (!(raw instanceof JSONObject)) throw protocol("object field");
        return (JSONObject) raw;
    }

    // Only authenticated host traffic may be converted into a RunBinding, in the coordinator.
    static JSONObject trustedBindingObject(JSONObject envelope) throws OwnerProtocolException {
        JSONObject binding = object(envelope, "binding");
        onlyFields(binding, "session", "task", "conversation", "run", "assistantMessage", "runOwner");
        Request fields = new Request("", "", binding);
        for (String key : new String[]{"session", "task", "conversation", "run", "assistantMessage", "runOwner"}) {
            String value = fields.requireString(key);
            if (value.length() > OwnerOperationIdentity.MAX_BINDING_CHARS) throw protocol("binding");
        }
        return binding;
    }

    static void onlyFields(JSONObject object, String... allowed) throws OwnerProtocolException {
        Set<String> keys = new HashSet<String>(Arrays.asList(allowed));
        for (Iterator<String> it = object.keys(); it.hasNext();) {
            if (!keys.contains(it.next())) throw protocol("unsupported field");
        }
    }

    /** One detached, default-complete snapshot used for BOTH identity and original execution.
     * Null displayId means the display owned by this generation, not the current default display.
     */
    static JSONObject businessParameters(String op, JSONObject supplied) throws OwnerProtocolException {
        JSONObject out;
        try { out = new JSONObject(supplied.toString()); }
        catch (JSONException invalid) { throw protocol("parameters"); }
        Request p = new Request(op, "", out);
        try {
            if (OP_STATUS.equals(op)) {
                onlyFields(out); // status() has no business arguments
            } else if (OP_LAUNCH.equals(op)) {
                onlyFields(out, "displayId", "package", "component", "action", "flags", "categories");
                for (String key : new String[]{"package", "component", "action"}) {
                    if (out.has(key)) p.requireString(key);
                }
                out.put("flags", p.optionalInt("flags", 0));
                if (!out.has("categories")) out.put("categories", new org.json.JSONArray());
                Object categories = out.opt("categories");
                if (!(categories instanceof org.json.JSONArray)) throw protocol("categories");
                org.json.JSONArray array = (org.json.JSONArray) categories;
                for (int i = 0; i < array.length(); i++) {
                    if (!(array.opt(i) instanceof String)) throw protocol("categories");
                }
            } else if (OP_INPUT.equals(op)) {
                String kind = p.requireString("kind");
                if ("tap".equals(kind)) {
                    onlyFields(out, "displayId", "kind", "x", "y");
                    p.requireInt("x"); p.requireInt("y");
                } else if ("swipe".equals(kind)) {
                    onlyFields(out, "displayId", "kind", "x1", "y1", "x2", "y2", "durationMs");
                    p.requireInt("x1"); p.requireInt("y1"); p.requireInt("x2"); p.requireInt("y2");
                    out.put("durationMs", p.optionalInt("durationMs", 300));
                } else if ("key".equals(kind)) {
                    onlyFields(out, "displayId", "kind", "keyCode"); p.requireInt("keyCode");
                } else if ("text".equals(kind)) {
                    onlyFields(out, "displayId", "kind", "text"); p.requireString("text");
                } else throw protocol("input kind");
            } else if (OP_SNAPSHOT.equals(op)) {
                onlyFields(out, "displayId", "include", "maxBytes");
                out.put("include", p.optionalBoolean("include", true));
                int max = p.optionalInt("maxBytes", 6 * 1024 * 1024);
                out.put("maxBytes", max <= 0 ? 6 * 1024 * 1024 : Math.min(max, 6 * 1024 * 1024));
            } else if (OP_HANDOFF.equals(op)) {
                onlyFields(out, "displayId", "taskIds");
                if (out.has("taskIds")) {
                    Object ids = out.opt("taskIds");
                    if (!(ids instanceof org.json.JSONArray)) throw protocol("taskIds");
                    org.json.JSONArray array = (org.json.JSONArray) ids;
                    for (int i = 0; i < array.length(); i++) {
                        if (asInt(array.opt(i)) == null) throw protocol("taskIds");
                    }
                }
            } else if (OP_RELEASE.equals(op)) {
                onlyFields(out, "displayId");
            } else throw protocol("operation");
            if (out.has("displayId")) p.requireInt("displayId");
        } catch (JSONException invalid) { throw protocol("parameters"); }
        return out;
    }

    private static OwnerProtocolException protocol(String message) {
        return new OwnerProtocolException(ERROR_PROTOCOL, message);
    }

    public static JSONObject ok(String op, JSONObject fields) {
        JSONObject out = base(op, true);
        if (fields != null) {
            try {
                for (Iterator<String> it = fields.keys(); it.hasNext(); ) {
                    String key = it.next();
                    out.put(key, fields.get(key));
                }
            } catch (JSONException ex) {
                throw new IllegalStateException("owner response merge", ex);
            }
        }
        return out;
    }

    public static JSONObject fail(String op, String code, String message) {
        JSONObject out = base(op, false);
        try {
            out.put(FIELD_ERROR, code == null ? ERROR_INTERNAL : code);
            out.put(FIELD_MESSAGE, clip(message == null ? "" : message));
        } catch (JSONException ex) {
            throw new IllegalStateException("owner failure", ex);
        }
        return out;
    }

    /** Compact single line, {@code '\n'} terminated. */
    public static String encodeLine(JSONObject obj) {
        return obj.toString() + "\n";
    }

    /** Constant-time compare so a wrong token cannot be recovered from response timing. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        int diff = a.length() ^ b.length();
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** Printable text: no NUL, newline, CR or other control characters. */
    public static boolean isSafeText(String value, int maxChars) {
        if (value == null || value.isEmpty() || value.length() > maxChars) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1F || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** Package / component / key identifier: no whitespace, no shell metacharacters. */
    public static boolean isSafeIdentifier(String value) {
        if (value == null || value.isEmpty() || value.length() > 255) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '/' || c == ':' || c == '@';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static JSONObject base(String op, boolean ok) {
        JSONObject out = new JSONObject();
        try {
            out.put(FIELD_VERSION, VERSION);
            out.put(FIELD_OK, ok);
            if (op != null) {
                out.put(FIELD_OP, op);
            }
        } catch (JSONException ex) {
            throw new IllegalStateException("owner response", ex);
        }
        return out;
    }

    private static boolean isVersion(Object raw) {
        Integer value = asInt(raw);
        return value != null && value.intValue() == VERSION;
    }

    /** Integer only when the JSON value is a real integral number; never parses strings. */
    private static Integer asInt(Object raw) {
        if (raw instanceof Integer) {
            return (Integer) raw;
        }
        if (raw instanceof Long) {
            long v = ((Long) raw).longValue();
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) v);
            }
            return null;
        }
        if (raw instanceof Double) {
            double d = ((Double) raw).doubleValue();
            if (d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) d);
            }
        }
        return null;
    }

    private static String clip(String message) {
        String flat = message.replace('\n', ' ').replace('\r', ' ').replace('\0', ' ');
        if (flat.length() > 200) {
            return flat.substring(0, 200);
        }
        return flat;
    }
}
