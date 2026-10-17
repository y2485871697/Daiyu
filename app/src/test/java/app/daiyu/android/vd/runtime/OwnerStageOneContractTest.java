package app.daiyu.android.vd.runtime;

import android.content.Intent;
import android.net.Uri;

import app.daiyu.android.vd.VirtualDisplayAnchorActivity;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;

import static org.junit.Assert.*;

/** Wiring checks only: no root operation, owner process, input binary or display creation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36}, manifest = Config.NONE)
public class OwnerStageOneContractTest {
    @Test public void shellUidAndAppUidAreNotRoot() {
        for (int uid : new int[]{2000, 10000}) {
            ShadowProcess.setUid(uid);
            assertEquals(3, VirtualDisplayOwnerMain.run(new String[]{
                    "--socket", "host-contract", "--allow-uid", "10000",
                    "--receipt-root", java.nio.file.Paths.get("private", "owner-receipts").toAbsolutePath().normalize().toString()}));
        }
    }

    @Test public void systemContextPackageDoesNotFollowJavaNamespace() {
        assertEquals("android", VirtualDisplayFactory.OWNER_PACKAGE);
    }

    @Test public void helpNamesTheActualEntrypointWithoutStartingOwner() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try (PrintStream capture = new PrintStream(bytes, true, "UTF-8")) {
            System.setOut(capture);
            assertEquals(0, VirtualDisplayOwnerMain.run(new String[]{"--help"}));
        } finally {
            System.setOut(previous);
        }
        String help = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(help.contains("app_process /system/bin "
                + VirtualDisplayOwnerMain.class.getName() + " "));
    }

    @Test public void launchAndCoverContractsUseTheHostNamespace() {
        assertEquals(app.daiyu.android.BuildConfig.APPLICATION_ID + "/" + VirtualDisplayAnchorActivity.class.getName(),
                OwnerHandoff.ANCHOR_COMPONENT);
        assertEquals(VirtualDisplayAnchorActivity.LAUNCH_SCHEME + "://handoff/",
                OwnerHandoff.ANCHOR_MARKER_PREFIX);
        assertEquals("app.daiyu.android.vd.anchor", VirtualDisplayAnchorActivity.TASK_AFFINITY);
        assertNotEquals("app.daiyu.android", VirtualDisplayAnchorActivity.TASK_AFFINITY);
        assertEquals("daiyu-vd://session/", LaunchPolicy.MARKER_PREFIX);
        String marker = LaunchPolicy.newMarker();
        assertTrue(marker.startsWith(LaunchPolicy.MARKER_PREFIX));
        assertTrue(LaunchPolicy.provenanceMatches(marker, "com.example.target", marker,
                "com.example.target"));
        assertFalse(LaunchPolicy.provenanceMatches(marker, "com.example.target", marker + "x",
                "com.example.target"));
    }

    @Test public void anchorRefusesTheMainDisplayIncludingOnApi26() {
        Intent intent = new Intent(Intent.ACTION_VIEW).setData(Uri.parse(
                OwnerHandoff.ANCHOR_MARKER_PREFIX + "11111111-1111-4111-8111-111111111111"));
        try (org.robolectric.android.controller.ActivityController<VirtualDisplayAnchorActivity>
                     controller = Robolectric.buildActivity(VirtualDisplayAnchorActivity.class, intent)) {
            controller.create();
            assertTrue(controller.get().isFinishing());
        }
    }
}
