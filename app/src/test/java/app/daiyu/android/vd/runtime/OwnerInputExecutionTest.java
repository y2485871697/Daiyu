package app.daiyu.android.vd.runtime;

import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Public-entry regression; only synthetic display reads/commands, no root or real resources. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36}, manifest = Config.NONE)
public class OwnerInputExecutionTest {
    private interface Attempt {
        OwnerShell.Result run() throws OwnerException;
    }

    private static OwnerShell.Result success() {
        return new OwnerShell.Result(0, "", "", false, false, true);
    }

    private static final class Access extends VirtualDisplayOwner.InputAccess {
        Attempt attempt = OwnerInputExecutionTest::success;
        int commands;
        int responses;
        int liveChecks;
        String[] argv;
        boolean failResponseCreation;
        boolean failResponsePut;
        boolean failDisplay;
        int width = 640;

        @Override void verifyDisplay(int displayId, String uniqueId) throws OwnerException {
            assertEquals(7, displayId);
            assertEquals("host-display", uniqueId);
            liveChecks++;
            if (failDisplay) throw new OwnerException("DISPLAY_REBOUND");
        }

        @Override int[] geometry(int displayId, String uniqueId) {
            assertEquals(7, displayId);
            assertEquals("host-display", uniqueId);
            return new int[]{width, 480};
        }

        @Override List<OwnerTaskInventory.Seen> sourceTasks(int displayId) {
            assertEquals(7, displayId);
            return Collections.emptyList();
        }

        @Override Map<Integer,Object> statusRoots() {
            return Collections.emptyMap();
        }

        @Override OwnerShell.Result run(String[] argv) throws OwnerException {
            commands++;
            this.argv = argv.clone();
            return attempt.run();
        }

        @Override JSONObject response() throws JSONException {
            responses++;
            if (failResponseCreation) throw new JSONException("synthetic response allocation failure");
            if (failResponsePut) {
                return new JSONObject() {
                    @Override public JSONObject put(String key, int value) throws JSONException {
                        // executed and kind were written, but this is not a complete success body.
                        assertTrue(getBoolean("executed"));
                        assertEquals("key", getString("kind"));
                        throw new JSONException("synthetic response field failure");
                    }
                };
            }
            return new JSONObject();
        }
    }

    private static final class Fixture {
        final Access access = new Access();
        final VirtualDisplayOwner owner = new VirtualDisplayOwner(
                new VirtualDisplayFactory.Created(null, null, null, 7, "host-display",
                        "host-only", 640, 480, 160, VirtualDisplayFactory.DEFAULT_FLAGS),
                new OwnerFrameStore(), new Handler(Looper.getMainLooper()), access);
    }

    private static JSONObject request(String op) throws JSONException {
        return new JSONObject().put("v", OwnerProtocol.VERSION).put("op", op)
                .put("token", "host-test").put("displayId", 7);
    }

    private static JSONObject key() throws JSONException {
        return request(OwnerProtocol.OP_INPUT).put("kind", "key").put("keyCode", 4);
    }

    private static void flags(VirtualDisplayOwner owner, boolean expected) throws Exception {
        JSONObject status = owner.status();
        assertEquals(expected, status.getBoolean("finishing"));
        assertEquals(expected, status.getBoolean("mutationUncertain"));
        assertEquals(7, status.getInt("displayId"));
        assertEquals("android", status.getString("ownerPackage"));
    }

    private static void refusesReplay(Fixture fixture) throws Exception {
        flags(fixture.owner, true);
        int commands = fixture.access.commands;
        int responses = fixture.access.responses;
        int liveChecks = fixture.access.liveChecks;
        OwnerException input = assertThrows(OwnerException.class, () -> fixture.owner.input(key()));
        assertEquals("SESSION_FINISHING", input.code);
        OwnerException launch = assertThrows(OwnerException.class, () -> fixture.owner.launch(
                request(OwnerProtocol.OP_LAUNCH).put("component", "com.example.target/.Main")));
        assertEquals("SESSION_FINISHING", launch.code);
        // Both public calls passed through requireLive(), not just the private execution guard.
        assertEquals(liveChecks + 2, fixture.access.liveChecks);
        assertEquals(commands, fixture.access.commands);
        assertEquals(responses, fixture.access.responses);
        flags(fixture.owner, true);
    }

    @Test public void nonzeroExitAfterPartialInputIsUncertainAndNeverReplayed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.access.attempt = () -> new OwnerShell.Result(1, "", "input failed", false, false, true);
        OwnerException failure = assertThrows(OwnerException.class, () -> fixture.owner.input(
                request(OwnerProtocol.OP_INPUT).put("kind", "text").put("text", "partial input")));
        assertEquals(OwnerProtocol.ERROR_INPUT_FAILED, failure.code);
        assertEquals(1, fixture.access.commands);
        assertEquals(0, fixture.access.responses);
        JSONObject reply = OwnerCommandDispatcher.failureResponse(OwnerProtocol.OP_INPUT, failure);
        assertFalse(reply.getBoolean("ok"));
        assertFalse(reply.has("executed")); // failure is not evidence of NotExecuted
        refusesReplay(fixture);
    }

    @Test public void timeoutAfterPartialSwipeIsUncertainAndNeverReplayed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.access.attempt = () -> new OwnerShell.Result(-1, "", "", true, false, false);
        OwnerException failure = assertThrows(OwnerException.class, () -> fixture.owner.input(
                request(OwnerProtocol.OP_INPUT).put("kind", "swipe").put("x1", 1).put("y1", 2)
                        .put("x2", 3).put("y2", 4).put("durationMs", 250)));
        assertEquals(OwnerProtocol.ERROR_INPUT_FAILED, failure.code);
        assertArrayEquals(ShellCommands.inputSwipeArgv(7, 1, 2, 3, 4, 250), fixture.access.argv);
        refusesReplay(fixture);
    }

    @Test public void unobservedExitAlsoKeepsTheLatch() throws Exception {
        Fixture fixture = new Fixture();
        fixture.access.attempt = () -> new OwnerShell.Result(-1, "", "", false, false, false);
        OwnerException failure = assertThrows(OwnerException.class, () -> fixture.owner.input(
                request(OwnerProtocol.OP_INPUT).put("kind", "tap").put("x", 1).put("y", 2)));
        assertEquals(OwnerProtocol.ERROR_INPUT_FAILED, failure.code);
        refusesReplay(fixture);
    }

    @Test public void checkedAndUncheckedErrorsAfterAttemptKeepTheLatch() throws Exception {
        Fixture checked = new Fixture();
        checked.access.attempt = () -> { throw new OwnerException(OwnerProtocol.ERROR_INTERNAL); };
        assertThrows(OwnerException.class, () -> checked.owner.input(key()));
        refusesReplay(checked);

        Fixture unchecked = new Fixture();
        unchecked.access.attempt = () -> { throw new IllegalStateException("post-start failure"); };
        assertThrows(IllegalStateException.class, () -> unchecked.owner.input(key()));
        refusesReplay(unchecked);
    }

    @Test public void successfulCommandButResponseCreationOrPutFailsKeepsBothFlags() throws Exception {
        for (boolean creation : new boolean[]{true, false}) {
            Fixture fixture = new Fixture();
            fixture.access.failResponseCreation = creation;
            fixture.access.failResponsePut = !creation;
            OwnerException failure = assertThrows(OwnerException.class, () -> fixture.owner.input(key()));
            assertEquals(OwnerProtocol.ERROR_INTERNAL, failure.code);
            assertEquals(1, fixture.access.commands); // command returned success before JSON failed
            assertEquals(1, fixture.access.responses);
            assertArrayEquals(ShellCommands.inputKeyArgv(7, 4), fixture.access.argv);
            JSONObject reply = OwnerCommandDispatcher.failureResponse(OwnerProtocol.OP_INPUT, failure);
            assertFalse(reply.getBoolean("ok"));
            assertFalse(reply.has("executed"));
            refusesReplay(fixture);
        }
    }

    @Test public void latchPrecedesChildAndClearsOnlyAfterSuccessfulReply() throws Exception {
        Fixture fixture = new Fixture();
        flags(fixture.owner, false);
        fixture.access.attempt = () -> {
            try {
                flags(fixture.owner, true);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
            return success();
        };
        JSONObject reply = fixture.owner.input(key());
        assertTrue(reply.getBoolean("executed"));
        assertEquals("key", reply.getString("kind"));
        assertEquals(7, reply.getInt("displayId"));
        assertEquals(0, reply.getInt("exitCode"));
        flags(fixture.owner, false);
        assertTrue(fixture.owner.input(key()).getBoolean("executed"));
        assertEquals(2, fixture.access.commands);
        assertEquals(2, fixture.access.responses);
        flags(fixture.owner, false);
    }

    @Test public void publicValidationStillRunsBeforeAnyMutation() throws Exception {
        Fixture fixture = new Fixture();
        assertEquals(OwnerProtocol.ERROR_DISPLAY_REBOUND, assertThrows(OwnerException.class,
                () -> fixture.owner.input(key().put("displayId", 8))).code);
        assertEquals(OwnerProtocol.ERROR_PROTOCOL, assertThrows(OwnerException.class,
                () -> fixture.owner.input(key().put("keyCode", -1))).code);
        assertEquals(OwnerProtocol.ERROR_PROTOCOL, assertThrows(OwnerException.class,
                () -> fixture.owner.input(key().put("kind", "unknown"))).code);
        fixture.access.width = 800;
        assertEquals("VIRTUAL_FRAME_CHANGED", assertThrows(OwnerException.class,
                () -> fixture.owner.input(request(OwnerProtocol.OP_INPUT).put("kind", "tap")
                        .put("x", 1).put("y", 2))).code);
        fixture.access.failDisplay = true;
        assertEquals("DISPLAY_REBOUND", assertThrows(OwnerException.class,
                () -> fixture.owner.input(key())).code);
        fixture.access.failDisplay = false;
        assertEquals(0, fixture.access.commands);
        assertEquals(0, fixture.access.responses);
        flags(fixture.owner, false);
    }
}
