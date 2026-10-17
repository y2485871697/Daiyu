package app.daiyu.android.terminal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LinuxInstallationJsonTest {
    private val chroot = LinuxInstallation("alpine", "alpine", "generation", File("/private/rootfs"),
        executionMode = LinuxExecutionMode.CHROOT)
    @Test fun chrootRoundTripHasNoProotHelperFields() {
        val value = LinuxInstallationJson.encode(chroot)
        assertEquals("chroot", value.getString("execution_mode"))
        assertFalse(value.has("proot")); assertFalse(value.has("loader"))
        assertEquals(chroot, LinuxInstallationJson.decode(value, 2))
    }
    @Test fun legacyProotReadsWithoutModeAndRetainsExactFields() {
        val original = chroot.copy(proot = File("/apk/proot"), prootSha256 = "a".repeat(64),
            loader = File("/apk/loader"), loaderSha256 = "b".repeat(64), executionMode = LinuxExecutionMode.PROOT)
        val value = LinuxInstallationJson.encode(original, 1)
        val bytes = value.toString()
        assertFalse(value.has("execution_mode"))
        assertEquals(original, LinuxInstallationJson.decode(value, 1))
        assertEquals(bytes, value.toString())
    }
    @Test fun chrootCannotDiscardInjectedHelpersOrUnknownModes() {
        for (key in listOf("proot", "loader", "proot_sha256", "loader_sha256")) {
            val value = LinuxInstallationJson.encode(chroot).put(key, "injected")
            assertTrue(runCatching { LinuxInstallationJson.decode(value, 2) }.isFailure)
        }
        assertTrue(runCatching { LinuxInstallationJson.decode(LinuxInstallationJson.encode(chroot)
            .put("execution_mode", "unknown"), 2) }.isFailure)
        assertTrue(runCatching { LinuxInstallationJson.decode(LinuxInstallationJson.encode(chroot)
            .also { it.remove("execution_mode") }, 2) }.isFailure)
    }
    @Test fun chrootDraftRejectsProotFieldsAndHostMappings() {
        val draft = LinuxSettingsDraft(distribution = "alpine", environmentId = "alpine", rootfs = "/private/rootfs",
            executionMode = LinuxExecutionMode.CHROOT)
        assertNull(draft.installation().proot)
        assertNull(draft.installation().loader)
        for (bad in listOf(draft.copy(proot = "/apk/proot"), draft.copy(loaderSha256 = "a".repeat(64)),
            draft.copy(guestWorkingDirectory = "/tmp"), draft.copy(sharedMounts = listOf(LinuxSettingsMount("/host", "/guest"))))) {
            assertTrue(runCatching { bad.installation() }.isFailure)
        }
    }
}
