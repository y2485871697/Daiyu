package app.daiyu.android.vd.runtime;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Authenticated owner-looper boundary. Only OwnerIpcServer may dispatch wire requests here,
 * AFTER kernel UID and token checks. A binding on initialize/receipt is a statement by that
 * trusted application UID, not model input. The host must never expose these envelopes to tools.
 * Owns the receipt store for the whole process generation, not a socket/connection lifetime.
 */
final class OwnerReceiptCoordinator implements OwnerIpcServer.Dispatcher {
    interface Backend {
        JSONObject dispatch(OwnerProtocol.Request request);
        boolean shouldStop(); // original owner has verified release (not transport completion)
        int displayId();
        String uniqueId();
    }

    interface Factory { Backend create() throws OwnerException; }

    private final OwnerReceiptStore store;
    private final Factory factory;
    private Backend backend;
    private OwnerOperationIdentity.RunBinding binding;
    private String generation;
    private OwnerReceiptStore.Claim generationClaim;
    private boolean initializationAttempted;
    private boolean uninitializedShutdownAttempted;
    private boolean uninitializedStopped;
    // Persistence/uncaught-boundary uncertainty is terminal, including for cleanup.
    private boolean executionBlocked;
    // Business failure fences original mutations, not a separately receipted first cleanup.
    private boolean mutationBlocked;
    private boolean handoffStarted;
    private boolean releaseStarted;
    private volatile boolean stop;

    OwnerReceiptCoordinator(OwnerReceiptStore store, Factory factory) {
        if (store == null || factory == null) throw new IllegalArgumentException("owner dependencies");
        this.store = store;
        this.factory = factory;
    }

    @Override public JSONObject dispatch(OwnerProtocol.Request request) {
        try {
            if (OwnerProtocol.OP_SHUTDOWN_UNINITIALIZED.equals(request.op)) return shutdownUninitialized(request);
            if (OwnerProtocol.OP_RECEIPT.equals(request.op)) return lookup(request);
            if (OwnerProtocol.OP_INITIALIZE.equals(request.op)) return initialize(request);
            return operate(request);
        } catch (OwnerProtocolException | IllegalArgumentException invalid) {
            return OwnerProtocol.fail(request.op, OwnerProtocol.ERROR_PROTOCOL, "invalid owner request");
        } catch (IOException unavailable) {
            // A write might already be durable. Never retry it here or switch generations.
            if (!OwnerProtocol.OP_RECEIPT.equals(request.op)) executionBlocked = true;
            if (OwnerProtocol.OP_SHUTDOWN_UNINITIALIZED.equals(request.op)) {
                return OwnerProtocol.fail(request.op, "SHUTDOWN_UNINITIALIZED_UNKNOWN",
                        "store close outcome unconfirmed; observe process only");
            }
            return OwnerProtocol.fail(request.op, "RECEIPT_UNAVAILABLE", "receipt outcome unconfirmed; lookup only");
        } catch (Throwable unexpected) {
            if (!OwnerProtocol.OP_RECEIPT.equals(request.op)) executionBlocked = true;
            if (OwnerProtocol.OP_SHUTDOWN_UNINITIALIZED.equals(request.op)) {
                return OwnerProtocol.fail(request.op, "SHUTDOWN_UNINITIALIZED_UNKNOWN",
                        "shutdown outcome unconfirmed; observe process only");
            }
            return OwnerProtocol.fail(request.op, OwnerProtocol.ERROR_INTERNAL, "owner outcome unconfirmed; lookup only");
        }
    }

    /** Runs only on the same owner looper as initialize. No lookup, claim or receipt writes. */
    private JSONObject shutdownUninitialized(OwnerProtocol.Request request) throws IOException, JSONException {
        if (initializationAttempted || executionBlocked || backend != null || generationClaim != null
                || uninitializedShutdownAttempted || stop) {
            return OwnerProtocol.fail(request.op, "SHUTDOWN_UNINITIALIZED_DENIED",
                    "initialization or shutdown admission already closed");
        }
        // Fence BEFORE close, including a throwing/uncertain close. Never reopen or retry.
        uninitializedShutdownAttempted = true;
        initializationAttempted = true;
        JSONObject out = OwnerProtocol.ok(request.op, null);
        out.put("initialized", false);
        out.put("receiptsClosed", true);
        store.close();
        uninitializedStopped = true;
        stop = true;
        return out;
    }

    private JSONObject initialize(OwnerProtocol.Request request) throws IOException, OwnerProtocolException, JSONException {
        if (uninitializedShutdownAttempted) {
            return OwnerProtocol.fail(request.op, "INITIALIZATION_CLOSED", "process shutdown admission closed");
        }
        String wanted = OwnerProtocol.pathId(request.requireString("generation"));
        OwnerOperationIdentity.RunBinding trusted = binding(request);
        // Lookup first is deliberate: an existing record NEVER recreates an owner, even STARTED.
        OwnerReceiptStore.Receipt old = store.lookupGeneration(wanted, trusted);
        if (old != null) return receiptResponse(request.op, old);
        if (initializationAttempted || executionBlocked || backend != null) {
            return OwnerProtocol.fail(request.op, "INITIALIZATION_CLOSED", "one generation per owner process");
        }
        initializationAttempted = true;
        OwnerReceiptStore.Reservation reservation = store.reserveGeneration(wanted, trusted);
        if (reservation.newClaim == null) return receiptResponse(request.op, reservation.receipt);
        OwnerReceiptStore.Start started = store.start(reservation.newClaim);
        if (!started.mayExecute) return receiptResponse(request.op, started.receipt);
        generation = wanted;
        binding = trusted;
        generationClaim = reservation.newClaim;
        try {
            // The only production factory invokes VirtualDisplayOwner.create, on this looper,
            // after both generation writes have durably succeeded.
            backend = factory.create();
        } catch (Throwable failedCreation) {
            executionBlocked = true;
            store.complete(generationClaim, OwnerReceiptStore.State.UNKNOWN, summary(false, false));
            return OwnerProtocol.fail(request.op, "INITIALIZATION_UNKNOWN", "creation outcome unconfirmed");
        }
        JSONObject out = OwnerProtocol.ok(request.op, null);
        out.put("generation", generation);
        out.put("displayId", backend.displayId());
        out.put("uniqueId", backend.uniqueId());
        out.put("initialized", true);
        out.put("receipt", receiptJson(started.receipt)); // STARTED until the entire lease ends
        return out;
    }

    private JSONObject lookup(OwnerProtocol.Request request) throws IOException, OwnerProtocolException, JSONException {
        String wanted = OwnerProtocol.pathId(request.requireString("generation"));
        OwnerOperationIdentity.RunBinding trusted = binding(request);
        OwnerReceiptStore.Receipt receipt;
        if (!request.has("operation")) {
            receipt = store.lookupGeneration(wanted, trusted);
        } else {
            String operation = request.requireString("operation");
            if (!OwnerProtocol.isBusinessOperation(operation)) throw new IllegalArgumentException("operation");
            JSONObject parameters = OwnerProtocol.businessParameters(operation,
                    OwnerProtocol.object(request.payload, "parameters"));
            receipt = store.lookup(identity(wanted, request.requireString("operationId"), operation, trusted, parameters));
        }
        // No factory, owner method, generation reservation, write, retry or cleanup on this path.
        return receiptResponse(request.op, receipt);
    }

    private JSONObject operate(OwnerProtocol.Request request) throws IOException, OwnerProtocolException, JSONException {
        if (!OwnerProtocol.isBusinessOperation(request.op)) throw new IllegalArgumentException("operation");
        if (backend == null || generationClaim == null) {
            return OwnerProtocol.fail(request.op, "INITIALIZE_REQUIRED", "authenticated initialization required");
        }
        String requestedGeneration = OwnerProtocol.pathId(request.requireString("generation"));
        if (!generation.equals(requestedGeneration)) {
            return OwnerProtocol.fail(request.op, "GENERATION_MISMATCH", "wrong generation");
        }
        JSONObject parameters = OwnerProtocol.businessParameters(request.op,
                OwnerProtocol.object(request.payload, "parameters"));
        OwnerOperationIdentity identity = identity(generation, request.requireString("operationId"),
                request.op, binding, parameters);
        OwnerReceiptStore.Receipt old = store.lookup(identity);
        if (old != null) return receiptResponse(request.op, old);
        boolean readOnly = OwnerProtocol.OP_STATUS.equals(request.op) || OwnerProtocol.OP_SNAPSHOT.equals(request.op);
        boolean handoff = OwnerProtocol.OP_HANDOFF.equals(request.op);
        boolean release = OwnerProtocol.OP_RELEASE.equals(request.op);
        boolean cleanup = handoff || release;
        // A new cleanup ID is not a retry capability. Same-ID requests returned the receipt above.
        // Each cleanup kind has its own one-shot permit, so handoff may still be followed by release.
        if (stop || backend.shouldStop() || (executionBlocked && !readOnly)
                || (mutationBlocked && !readOnly && !cleanup)
                || (handoff && handoffStarted) || (release && releaseStarted)) {
            return OwnerProtocol.fail(request.op, "SESSION_UNCERTAIN", "execution disabled; lookup only");
        }
        OwnerReceiptStore.Reservation reservation = store.reserveOperation(generationClaim, identity);
        if (reservation.newClaim == null) return receiptResponse(request.op, reservation.receipt);
        OwnerReceiptStore.Start started = store.start(reservation.newClaim);
        if (!started.mayExecute) return receiptResponse(request.op, started.receipt);
        // Arm before entering the backend, including throws/UNKNOWN and preflight failures.
        if (handoff) handoffStarted = true;
        if (release) releaseStarted = true;
        JSONObject response;
        try {
            // Never pass the authentication token, binding, operationId or wire envelope.
            // The legacy owner wrapper gets only a public, non-credential parser marker.
            response = backend.dispatch(new OwnerProtocol.Request(request.op, "", parameters));
            if (response == null) throw new IllegalStateException("no response");
        } catch (Throwable unexpected) {
            response = OwnerProtocol.fail(request.op, OwnerProtocol.ERROR_INTERNAL, "owner outcome unconfirmed");
        }
        boolean succeeded = response.optBoolean("ok", false);
        if (!succeeded && !readOnly) mutationBlocked = true;
        // Conservative UNKNOWN on any business failure: an error never proves NotExecuted.
        boolean released = backend.shouldStop();
        OwnerReceiptStore.Receipt completed = store.complete(reservation.newClaim,
                succeeded ? OwnerReceiptStore.State.SUCCEEDED : OwnerReceiptStore.State.UNKNOWN,
                summary(succeeded, released));
        if (released) {
            // Both final records must commit BEFORE IPC is allowed to terminate this process.
            store.complete(generationClaim, OwnerReceiptStore.State.SUCCEEDED, summary(true, true));
            stop = true;
        }
        response.put("generation", generation);
        response.put("receipt", receiptJson(completed));
        response.put("receiptOnly", false);
        return response;
    }

    private static OwnerOperationIdentity.RunBinding binding(OwnerProtocol.Request request) throws OwnerProtocolException {
        JSONObject object = OwnerProtocol.trustedBindingObject(request.payload);
        OwnerProtocol.Request fields = new OwnerProtocol.Request("", "", object);
        return OwnerOperationIdentity.bindingFromTrustedHost(fields.requireString("session"),
                fields.requireString("task"), fields.requireString("conversation"), fields.requireString("run"),
                fields.requireString("assistantMessage"), fields.requireString("runOwner"));
    }

    private static OwnerOperationIdentity identity(String generation, String id, String op,
            OwnerOperationIdentity.RunBinding binding, JSONObject parameters) throws JSONException {
        Map<String, Object> effective = jsonMap(parameters);
        // Omitted displayId denotes this generation's owned display, not a mutable global default.
        if (!OwnerProtocol.OP_STATUS.equals(op) && !effective.containsKey("displayId")) effective.put("displayId", null);
        if (OwnerProtocol.OP_LAUNCH.equals(op)) {
            for (String key : new String[]{"package", "component", "action"}) {
                if (!effective.containsKey(key)) effective.put(key, null);
            }
        }
        if (OwnerProtocol.OP_HANDOFF.equals(op) && !effective.containsKey("taskIds")) effective.put("taskIds", null);
        return new OwnerOperationIdentity(generation, id, op, binding, effective);
    }

    private static Map<String, Object> summary(boolean ok, boolean released) {
        // Intentionally NOT raw response/message/PNG/parameters: bounded and secret-free by construction.
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", ok);
        result.put("released", released);
        return result;
    }

    private static JSONObject receiptResponse(String op, OwnerReceiptStore.Receipt receipt) throws JSONException {
        JSONObject out = OwnerProtocol.ok(op, null);
        out.put("receiptOnly", true);
        out.put("executed", false);
        out.put("found", receipt != null);
        if (receipt != null) out.put("receipt", receiptJson(receipt));
        return out;
    }

    private static JSONObject receiptJson(OwnerReceiptStore.Receipt receipt) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("generation", receipt.generation);
        out.put("operationId", receipt.operationId);
        out.put("operation", receipt.operation);
        out.put("generationReceipt", receipt.generationReceipt);
        out.put("digest", receipt.digest);
        out.put("bindingDigest", receipt.bindingDigest);
        out.put("state", receipt.state.name());
        if (receipt.resultJson != null) out.put("result", new JSONObject(receipt.resultJson));
        return out;
    }

    private static Map<String, Object> jsonMap(JSONObject value) throws JSONException {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Iterator<String> keys = value.keys(); keys.hasNext();) {
            String key = keys.next();
            result.put(key, jsonValue(value.get(key)));
        }
        return result;
    }

    private static Object jsonValue(Object value) throws JSONException {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return jsonMap((JSONObject) value);
        if (value instanceof JSONArray) {
            ArrayList<Object> result = new ArrayList<Object>();
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) result.add(jsonValue(array.get(i)));
            return result;
        }
        if (value instanceof Float || value instanceof Double) return new BigDecimal(value.toString());
        return value;
    }

    @Override public boolean shouldStop() { return stop; }

    /** Only main's verified terminal callback may relinquish the lifetime lock. */
    void closeAfterStop() throws IOException {
        if (uninitializedStopped && stop) return; // store already closed before shutdown success
        if (!stop || backend == null || !backend.shouldStop()) throw new IOException("owner not terminal");
        store.close();
    }

    String stoppedMarker() throws IOException {
        if (!stop) throw new IOException("owner not terminal");
        return uninitializedStopped
                ? "VD_OWNER_STOPPED initialized=false receiptsClosed=true"
                : "VD_OWNER_STOPPED released=true receiptsFinal=true";
    }
}
