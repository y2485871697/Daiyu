package app.daiyu.android.vd.runtime;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Wire/auth helper tests, not socket or kernel-credential integration tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36}, manifest = Config.NONE)
public class OwnerProtocolV2Test {
    private JSONObject operation() throws Exception {
        return new JSONObject().put("v", 2).put("op", "input").put("token", "private-token")
                .put("generation", "g1").put("operationId", "op1")
                .put("parameters", new JSONObject().put("kind", "key").put("keyCode", 4));
    }

    private void rejected(JSONObject request) {
        assertThrows(OwnerProtocolException.class, () -> OwnerProtocol.parseWireRequest(request.toString()));
    }

    @Test public void versionIsExplicitAndLegacyFlatRequestsCannotBypassReceipts() throws Exception {
        assertEquals(2, OwnerProtocol.VERSION);
        assertEquals("input", OwnerProtocol.parseWireRequest(operation().toString()).op);
        rejected(operation().put("v", 1));
        rejected(operation().put("v", 3));
        rejected(operation().put("v", "2"));
        JSONObject noId = operation(); noId.remove("operationId"); rejected(noId);
        JSONObject flat = operation(); flat.remove("parameters"); flat.put("kind", "key"); rejected(flat);
        rejected(operation().put("generation", "../g"));
        rejected(operation().put("operationId", ""));
    }

    @Test public void everyUnknownEnvelopeAndBusinessFieldIsRejectedRatherThanIgnored() throws Exception {
        for (String key : new String[]{"receiptRoot", "runBinding", "binding", "timeoutMs", "extra"}) {
            rejected(operation().put(key, "untrusted"));
        }
        for (String key : new String[]{"token", "binding", "generation", "operationId", "extra", "durationMs"}) {
            JSONObject request = operation();
            request.getJSONObject("parameters").put(key, "untrusted");
            rejected(request);
        }
        JSONObject request = operation();
        request.getJSONObject("parameters").put("keyCode", "4");
        rejected(request);
    }

    @Test public void internalOwnerCompatibilityDecoderCannotBypassTheWireEnvelope() throws Exception {
        JSONObject flat = OwnerProtocol.ownerBusinessPayload(new OwnerProtocol.Request("input", "",
                new JSONObject().put("kind", "key").put("keyCode", 4)));
        assertEquals(4, OwnerProtocol.parseRequest(flat.toString()).requireInt("keyCode"));
        assertFalse(flat.toString().contains("private-token"));
        rejected(flat); // the real socket uses parseWireRequest, not parseRequest
        assertFalse(java.lang.reflect.Modifier.isPublic(OwnerProtocol.class
                .getDeclaredMethod("parseRequest", String.class).getModifiers()));
    }

    @Test public void uidAndTokenAreBothRequiredByActualConnectionGate() throws Exception {
        OwnerProtocol.Request request = OwnerProtocol.parseWireRequest(operation().toString());
        assertTrue(OwnerIpcServer.isAuthenticatedRequest(10000, 10000, "private-token", request));
        assertFalse(OwnerIpcServer.isAuthenticatedRequest(10000, 2000, "private-token", request));
        assertFalse(OwnerIpcServer.isAuthenticatedRequest(10000, 0, "private-token", request));
        assertFalse(OwnerIpcServer.isAuthenticatedRequest(10000, 10001, "private-token", request));
        assertFalse(OwnerIpcServer.isAuthenticatedRequest(10000, 10000, "wrong", request));
        assertFalse(OwnerIpcServer.isAuthenticatedRequest(10000, 10000, "private-token", null));
    }

    @Test public void defaultCompleteParametersAreDetachedAndMatchOriginalLimits() throws Exception {
        JSONObject supplied = new JSONObject().put("kind", "swipe").put("x1", 1).put("y1", 2)
                .put("x2", 3).put("y2", 4);
        JSONObject normalized = OwnerProtocol.businessParameters("input", supplied);
        assertEquals(300, normalized.getInt("durationMs"));
        supplied.put("x1", 999);
        assertEquals(1, normalized.getInt("x1"));
        JSONObject snapshot = OwnerProtocol.businessParameters("snapshot", new JSONObject());
        assertTrue(snapshot.getBoolean("include"));
        assertEquals(6 * 1024 * 1024, snapshot.getInt("maxBytes"));
        assertEquals(6 * 1024 * 1024, OwnerProtocol.businessParameters("snapshot",
                new JSONObject().put("maxBytes", 0)).getInt("maxBytes"));
        JSONObject launch = OwnerProtocol.businessParameters("launch", new JSONObject().put("component", "pkg/.Main"));
        assertEquals(0, launch.getInt("flags"));
        assertEquals(0, launch.getJSONArray("categories").length());
    }

    @Test public void bindingOnlyAllowedOnHostInitializeOrLookupAndRequiresExactlySixDimensions() throws Exception {
        JSONObject binding = new JSONObject().put("session", "s").put("task", "t").put("conversation", "c")
                .put("run", "r").put("assistantMessage", "m").put("runOwner", "o");
        JSONObject initialize = new JSONObject().put("v", 2).put("op", "initialize")
                .put("token", "private-token").put("generation", "g1").put("binding", binding);
        assertEquals("initialize", OwnerProtocol.parseWireRequest(initialize.toString()).op);
        rejected(initialize.put("receiptRoot", "/untrusted"));
        initialize.remove("receiptRoot");
        binding.put("extra", "untrusted"); rejected(initialize);
        binding.remove("extra"); binding.remove("runOwner"); rejected(initialize);
        rejected(operation().put("binding", binding));
    }

    @Test public void trustedStartupRequiresFixedRootAndAnApplicationUid() {
        for (String uid : new String[]{"0", "2000", "1000", "99000"}) {
            assertThrows(IllegalArgumentException.class, () -> VirtualDisplayOwnerMain.Args.parse(
                    new String[]{"--socket", "test", "--allow-uid", uid, "--receipt-root", "/private/receipts"}));
        }
        assertThrows(IllegalArgumentException.class, () -> VirtualDisplayOwnerMain.Args.parse(
                new String[]{"--socket", "test", "--allow-uid", "10000"}));
        assertThrows(IllegalArgumentException.class, () -> VirtualDisplayOwnerMain.Args.parse(
                new String[]{"--socket", "test", "--allow-uid", "10000", "--receipt-root", "relative"}));
        VirtualDisplayOwnerMain.Args parsed = VirtualDisplayOwnerMain.Args.parse(
                new String[]{"--socket", "test", "--allow-uid", "110000", "--receipt-root", "/private/receipts"});
        assertEquals(110000, parsed.allowUid);
        assertEquals("/private/receipts", parsed.receiptRoot.toString());
    }
}
