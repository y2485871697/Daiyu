package app.daiyu.android.terminal

import org.junit.Assert.*
import org.junit.Test

class LinuxRuntimePlatformTest {
    @Test fun api26Through33CannotUseManualOrPackagedRuntimeEvenWithCorrectMachine() {
        for (sdk in 26..33) for (machine in listOf("aarch64", "x86_64")) {
            try { LinuxRuntimePlatform.requireSupported(sdk, true, machine); fail("accepted API $sdk") }
            catch (failure: IllegalStateException) { assertEquals("linux_helpers_unavailable_api_below_34", failure.message) }
        }
    }
    @Test fun onlySupported64BitProcessMachinesAreAcceptedWithoutAbiFallback() {
        assertEquals("arm64-v8a", LinuxRuntimePlatform.requireSupported(34, true, "aarch64"))
        assertEquals("x86_64", LinuxRuntimePlatform.requireSupported(37, true, "x86_64"))
        for ((bit64, machine) in listOf(false to "aarch64", false to "x86_64", true to "armv7l", true to "unknown")) {
            try { LinuxRuntimePlatform.requireSupported(34, bit64, machine); fail("unsupported process") }
            catch (_: IllegalStateException) {}
        }
    }
    @Test fun chrootHasIndependentApiGateButRequiresPackaged64BitAbi() {
        for (sdk in listOf(26, 33, 34, 37)) {
            assertEquals("arm64-v8a", LinuxRuntimePlatform.requireSupported(sdk, true, "aarch64", LinuxExecutionMode.CHROOT))
            assertEquals("x86_64", LinuxRuntimePlatform.requireSupported(sdk, true, "x86_64", LinuxExecutionMode.CHROOT))
        }
        for ((sdk, bits, machine) in listOf(Triple(25, true, "aarch64"), Triple(35, false, "armv7l"), Triple(35, true, "unknown"))) {
            assertTrue(runCatching { LinuxRuntimePlatform.requireSupported(sdk, bits, machine, LinuxExecutionMode.CHROOT) }.isFailure)
        }
    }

}
