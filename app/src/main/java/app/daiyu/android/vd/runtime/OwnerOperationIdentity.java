package app.daiyu.android.vd.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Local operation identity contract, not an IPC request parser or an authorization decision.
 * The trusted host must supply the COMPLETE effective business arguments (including defaults),
 * not a projection of model input. Neither authentication tokens nor transport fields belong here.
 */
public final class OwnerOperationIdentity {
    public static final int MAX_ID_CHARS = 64;
    public static final int MAX_BINDING_CHARS = 256;
    public static final int MAX_CANONICAL_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 8192;

    public final String generation;
    public final String operationId;
    public final String operation;
    public final String digest;
    public final String bindingDigest;

    /**
     * Not constructible from a public model/JSON API. Package host wiring is the trust boundary:
     * it MUST derive all six fields from authenticated host state, never from model arguments.
     * Java visibility alone cannot authenticate callers in this package or a compromised process.
     */
    public static final class RunBinding {
        private final Map<String, Object> fields;
        private final String digest;

        private RunBinding(String session, String task, String conversation, String run,
                           String assistantMessage, String runOwner) {
            TreeMap<String, Object> values = new TreeMap<String, Object>();
            values.put("session", bindingText(session));
            values.put("task", bindingText(task));
            values.put("conversation", bindingText(conversation));
            values.put("run", bindingText(run));
            values.put("assistantMessage", bindingText(assistantMessage));
            values.put("runOwner", bindingText(runOwner));
            fields = Collections.unmodifiableMap(values);
            digest = sha256(canonicalJson(fields).getBytes(StandardCharsets.UTF_8));
        }
    }

    // Intentionally package-private; no overload accepts JSONObject, Map, request or token.
    static RunBinding bindingFromTrustedHost(String session, String task, String conversation,
                                              String run, String assistantMessage, String runOwner) {
        return new RunBinding(session, task, conversation, run, assistantMessage, runOwner);
    }

    public OwnerOperationIdentity(String generation, String operationId, String operation,
                                  RunBinding trustedBinding, Map<String, ?> completeParameters) {
        this.generation = pathId(generation);
        this.operationId = pathId(operationId);
        this.operation = pathId(operation);
        if (trustedBinding == null || completeParameters == null) {
            throw invalid();
        }
        bindingDigest = trustedBinding.digest;
        TreeMap<String, Object> envelope = new TreeMap<String, Object>();
        envelope.put("contract", "owner-operation-v1");
        envelope.put("generation", generation);
        envelope.put("operationId", operationId);
        envelope.put("operation", operation);
        envelope.put("runBinding", trustedBinding.fields);
        envelope.put("parameters", completeParameters);
        // Snapshot now. Subsequent mutation of the caller's maps cannot alter this identity.
        digest = sha256(canonicalJson(envelope).getBytes(StandardCharsets.UTF_8));
    }

    static String generationDigest(String generation, RunBinding binding) {
        if (binding == null) throw invalid();
        TreeMap<String, Object> value = new TreeMap<String, Object>();
        value.put("contract", "owner-generation-v1");
        value.put("generation", pathId(generation));
        value.put("runBinding", binding.fields);
        return sha256(canonicalJson(value).getBytes(StandardCharsets.UTF_8));
    }

    static String bindingDigest(RunBinding binding) {
        if (binding == null) throw invalid();
        return binding.digest;
    }

    /** ASCII only, one component, no dot/slash/backslash/whitespace, fixed upper bound. */
    static String pathId(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ID_CHARS) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-')) throw invalid();
        }
        return value;
    }

    private static String bindingText(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_BINDING_CHARS) throw invalid();
        validateUnicode(value);
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) throw invalid();
        }
        return value;
    }

    /**
     * Canonical JSON v1 (not RFC 8785): string-keyed Map sorted by Java UTF-16 ordering;
     * List order retained; null/boolean/string; exact integral types and BigDecimal only.
     * Decimals use stripTrailingZeros().toPlainString(), zero is 0; Float/Double and arbitrary
     * objects are rejected. No Unicode normalization; unpaired surrogates rejected. Controls
     * use lowercase \\u00xx, quote/backslash escaped, all other scalars emitted literally in UTF-8.
     * Absent and null are different. No fields are ignored. Cycles, excess depth/size rejected.
     */
    public static String canonicalJson(Object value) {
        Canonical out = new Canonical();
        out.append(value, 0);
        String json = out.text.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_BYTES) throw invalid();
        return json;
    }

    private static final class Canonical {
        final StringBuilder text = new StringBuilder();
        final IdentityHashMap<Object, Boolean> parents = new IdentityHashMap<Object, Boolean>();
        int nodes;

        void append(Object value, int depth) {
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) throw invalid();
            if (value == null) text.append("null");
            else if (value instanceof String) quote((String) value);
            else if (value instanceof Boolean) text.append(value);
            else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value.getClass() == BigInteger.class) {
                if (value instanceof BigInteger
                        && ((BigInteger) value).bitLength() > MAX_CANONICAL_BYTES * 4) throw invalid();
                String number = value.toString();
                if (number.length() > MAX_CANONICAL_BYTES) throw invalid();
                text.append(number);
            } else if (value.getClass() == BigDecimal.class) {
                BigDecimal input = (BigDecimal) value;
                if (input.signum() == 0) {
                    text.append('0');
                    return;
                }
                if (input.unscaledValue().bitLength() > MAX_CANONICAL_BYTES * 4) throw invalid();
                BigDecimal number = input.stripTrailingZeros();
                long length = (long) number.precision() + Math.abs((long) number.scale()) + 3;
                if (length > MAX_CANONICAL_BYTES) throw invalid();
                text.append(number.toPlainString());
            } else if (value instanceof Map) {
                enter(value);
                TreeMap<String, Object> sorted = new TreeMap<String, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw invalid();
                    if (sorted.containsKey(entry.getKey())) throw invalid();
                    sorted.put((String) entry.getKey(), entry.getValue());
                    if (sorted.size() > MAX_NODES) throw invalid();
                }
                text.append('{');
                boolean comma = false;
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    if (comma) text.append(',');
                    comma = true;
                    quote(entry.getKey());
                    text.append(':');
                    append(entry.getValue(), depth + 1);
                }
                text.append('}');
                parents.remove(value);
            } else if (value instanceof List) {
                enter(value);
                // Snapshot to avoid retaining the caller's mutable structure.
                if (((List<?>) value).size() > MAX_NODES) throw invalid();
                List<?> values = new ArrayList<Object>((List<?>) value);
                text.append('[');
                for (int i = 0; i < values.size(); i++) {
                    if (i != 0) text.append(',');
                    append(values.get(i), depth + 1);
                }
                text.append(']');
                parents.remove(value);
            } else throw invalid();
            if (text.length() > MAX_CANONICAL_BYTES) throw invalid();
        }

        void enter(Object value) {
            if (parents.put(value, Boolean.TRUE) != null) throw invalid();
        }

        void quote(String value) {
            if (value.length() > MAX_CANONICAL_BYTES) throw invalid();
            validateUnicode(value);
            text.append('"');
            final char[] hex = "0123456789abcdef".toCharArray();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == '\\') text.append('\\').append(c);
                else if (c < 0x20) text.append("\\u00").append(hex[c >> 4]).append(hex[c & 15]);
                else text.append(c);
                if (text.length() > MAX_CANONICAL_BYTES) throw invalid();
            }
            text.append('"');
        }
    }

    private static void validateUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : hash) {
                out.append(Character.forDigit((b >>> 4) & 15, 16));
                out.append(Character.forDigit(b & 15, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static IllegalArgumentException invalid() {
        // Never echo caller content, binding identifiers or authentication material.
        return new IllegalArgumentException("invalid owner identity/canonical value");
    }
}
