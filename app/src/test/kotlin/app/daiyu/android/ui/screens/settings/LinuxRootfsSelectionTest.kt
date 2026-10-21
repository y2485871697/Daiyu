package app.daiyu.android.ui.screens.settings

import app.daiyu.android.terminal.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LinuxRootfsSelectionTest {
    private val proot = LinuxInstallation("debian", "environment", "one", File("/approved/rootfs"))
    private val selected = LinuxGenerationRef(proot.environmentId, proot.generation)
    private val confirmed = LinuxSettingsState(entries = listOf(proot), listConfirmed = true)

    @Test fun onlyConfirmedCurrentCatalogCanExposeRootfs() {
        assertEquals(proot, linuxBrowsableRootfs(confirmed, "Debian", LinuxExecutionMode.PROOT, null))
        for (state in listOf(confirmed.copy(listConfirmed = false), confirmed.copy(revoked = true),
            confirmed.copy(busy = true), confirmed.copy(pendingRemoval = selected))) {
            assertNull(linuxBrowsableRootfs(state, "debian", LinuxExecutionMode.PROOT, selected))
        }
        assertNull(linuxBrowsableRootfs(confirmed, "alpine", LinuxExecutionMode.PROOT, selected))
        assertNull(linuxBrowsableRootfs(confirmed, "debian", LinuxExecutionMode.CHROOT, selected))
    }

    @Test fun explicitSelectionNeverFallsBackToAnotherGenerationEvenIfOnlyOneRemains() {
        assertEquals(proot, linuxBrowsableRootfs(confirmed, "debian", LinuxExecutionMode.PROOT, selected))
        assertNull(linuxBrowsableRootfs(confirmed, "debian", LinuxExecutionMode.PROOT, LinuxGenerationRef("environment", "old")))
        assertNull(linuxBrowsableRootfs(confirmed, "debian", LinuxExecutionMode.PROOT, LinuxGenerationRef("other", "one")))
    }

    @Test fun multipleGenerationsRequireAnExactSelectionAndNeverPickFirst() {
        val second = proot.copy(generation = "two")
        val state = confirmed.copy(entries = listOf(proot, second))
        assertNull(linuxBrowsableRootfs(state, "debian", LinuxExecutionMode.PROOT, null))
        assertNull(linuxBrowsableRootfs(state, "debian", LinuxExecutionMode.PROOT, LinuxGenerationRef("other", "one")))
        assertEquals(second, linuxBrowsableRootfs(state, "debian", LinuxExecutionMode.PROOT, LinuxGenerationRef("environment", "two")))
    }
}
