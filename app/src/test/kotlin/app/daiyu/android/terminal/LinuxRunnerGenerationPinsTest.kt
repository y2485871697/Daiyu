package app.daiyu.android.terminal

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class LinuxRunnerGenerationPinsTest {
    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("linux-runner-pins-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private val generation = LinuxGenerationRef("environment", "generation")
    private fun rejects(action: () -> Unit) {
        try { action(); fail("unknown execution must remain fenced") } catch (_: IllegalStateException) { }
    }

    @Test fun knownRunsCanCoexistButOnlyOriginalProofSettlesEachPin() = fixture { root ->
        val first = LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey) { }
        val second = LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey) { }
        val one = first.claim(generation, listOf("installation")) { }
        val two = second.claim(generation, listOf("installation")) { }
        first.admitted { }
        rejects { second.admitted(requireEmpty = true) { } }
        one.settleAfterTermination(); one.settleAfterTermination()
        rejects { second.admitted(requireEmpty = true) { } }
        two.settleAfterTermination()
        first.admitted(requireEmpty = true) { }
    }

    @Test fun failedClaimAfterRenameBlocksEveryNewAdmission() = fixture { root ->
        val pins = LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey) { throw IOException("directory sync") }
        try { pins.claim(generation, listOf("installation")) { }; fail("claim must fail") } catch (_: IOException) { }
        rejects { LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey) { }.admitted { } }
        assertEquals(1, root.listFiles()!!.count { it.name.startsWith(".runner-pin-") })
    }

    @Test fun failedSettlementRetainsFenceUntilOriginalSyncRetry() = fixture { root ->
        var failSync = false
        val pins = LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey) { if (failSync) throw IOException("settlement sync") }
        val original = pins.claim(generation, listOf("installation")) { }
        failSync = true
        try { original.settleAfterTermination(); fail("sync must fail") } catch (_: IOException) { }
        rejects { pins.admitted { } }
        failSync = false
        original.settleAfterTermination()
        pins.admitted(requireEmpty = true) { }
    }

    @Test fun changedSelectionBeforeClaimNeverWritesAPin() = fixture { root ->
        val pins = LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey) { }
        rejects { pins.claim(generation, listOf("installation")) { error("selection changed") } }
        pins.admitted(requireEmpty = true) { }
    }

    @Test fun sameBytesAtAReplacementPinInodeAreNeverAdopted() = fixture { root ->
        val pins = LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey) { }
        val original = pins.claim(generation, listOf("installation")) { }
        val file = root.listFiles()!!.single { it.name.startsWith(".runner-pin-") }
        val replacement = File(root, "replacement").apply { writeBytes(file.readBytes()) }
        Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        HostLinuxIdentity.replaced(file.toPath())
        rejects { pins.admitted { } }
        rejects { original.settleAfterTermination() }
    }

    @Test fun replacingTheLockOrRootCannotCreateANewAdmissionDomain() = fixture { root ->
        val directory = File(root, "pins").apply { mkdirs() }
        val pins = LinuxRunnerGenerationPins(directory, fileKey = ::hostLinuxFileKey) { }
        pins.admitted { }
        val lock = File(directory, ".runner-pins.lock")
        assertTrue(lock.renameTo(File(directory, "old-lock"))); lock.writeText("")
        HostLinuxIdentity.replaced(lock.toPath())
        rejects { pins.admitted { } }
        assertTrue(directory.renameTo(File(root, "old-root"))); assertTrue(directory.mkdir())
        HostLinuxIdentity.replaced(directory.toPath())
        rejects { pins.admitted { } }
    }

    private fun legacyPin(root: File): File = File(root, ".runner-pin-" + "a".repeat(32)).apply {
        writeText("1\nenvironment\ngeneration\n${"b".repeat(64)}\n$name\n")
    }
    private fun recovering(root: File, boot: String?, sync: (File) -> Unit = {}) =
        LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey, bootIdentity = { boot }, directorySync = sync)

    @Test fun legacyUnknownGetsWitnessButSameBootNeverBecomesDrain() = fixture { root ->
        val pin = legacyPin(root); val bytes = pin.readBytes(); val key = hostLinuxFileKey(pin.toPath())
        rejects { recovering(root, "android:10").admitted { } }
        assertEquals(1, root.listFiles()!!.count { it.name.startsWith(".runner-boot-") })
        rejects { recovering(root, "android:10").admitted { } }
        assertArrayEquals(bytes, pin.readBytes()); assertEquals(key, hostLinuxFileKey(pin.toPath()))
        recovering(root, "android:11").admitted(requireEmpty = true) { }
        assertFalse(pin.exists())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".runner-boot-") })
    }

    @Test fun missingBootCounterRollbackAndSourceChangeNeverRecoverLegacyPin() = fixture { root ->
        val pin = legacyPin(root)
        rejects { recovering(root, null).admitted { } }
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".runner-boot-") })
        rejects { recovering(root, "android:10").admitted { } }
        for (boot in listOf(null, "android:9", "kernel:00000000-0000-0000-0000-000000000001"))
            rejects { recovering(root, boot).admitted { } }
        assertTrue(pin.exists())
    }

    @Test fun changedLegacyPinCannotUseAnEarlierBootWitness() = fixture { root ->
        val pin = legacyPin(root)
        rejects { recovering(root, "android:10").admitted { } }
        val replacement = File(root, "replacement").apply { writeBytes(pin.readBytes()) }
        Files.move(replacement.toPath(), pin.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        HostLinuxIdentity.replaced(pin.toPath())
        rejects { recovering(root, "android:11").admitted { } }
        assertTrue(pin.exists())
    }

    @Test fun interruptedRebootRecoveryRetriesDirectorySyncBeforeAdmission() = fixture { root ->
        val pin = legacyPin(root)
        rejects { recovering(root, "android:10").admitted { } }
        var fail = true
        val pins = recovering(root, "android:11") { if (fail) throw IOException("recovery sync") }
        assertThrows(IOException::class.java) { pins.admitted { } }
        assertFalse(pin.exists())
        assertTrue(root.listFiles()!!.any { it.name.startsWith(".runner-boot-") })
        assertThrows(IOException::class.java) { pins.admitted { } }
        fail = false
        pins.admitted(requireEmpty = true) { }
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".runner-boot-") })
    }

    @Test fun realJvmOwnerWritesBootWitnessButAnotherJvmInSameBootIsStillDenied() = fixture { root ->
        assertEquals("CLAIMED", runLinuxPinChild(root, "claim"))
        assertEquals(1, root.listFiles()!!.count { it.name.startsWith(".runner-boot-") })
        assertEquals("DENIED", runLinuxPinChild(root, "admit"))
        recovering(root, "android:11").admitted(requireEmpty = true) { }
    }

    @Test fun interruptedWitnessStagingIsNotRebootProofOrAPermanentFence() = fixture { root ->
        val pin = legacyPin(root)
        File(root, ".runner-boot-" + "a".repeat(32) + ".tmp").writeText("incomplete")
        rejects { recovering(root, "android:10").admitted { } }
        assertTrue(pin.exists())
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
        rejects { recovering(root, "android:10").admitted { } }
        recovering(root, "android:11").admitted(requireEmpty = true) { }
    }

    @Test fun sameProcessOwnerCannotBeRetiredByAChangedBootProvider() = fixture { root ->
        var boot = "android:10"
        val pins = LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey, bootIdentity = { boot }) { }
        val owner = pins.claim(generation, listOf("installation")) { }
        boot = "android:11"
        rejects { pins.admitted(requireEmpty = true) { } }
        assertTrue(root.listFiles()!!.any { it.name.startsWith(".runner-pin-") })
        owner.settleAfterTermination()
        pins.admitted(requireEmpty = true) { }
    }

    @Test fun actualNewJvmCannotTreatDeadOwnerOrNewScopeAsDrain() = fixture { root ->
        assertEquals("CLAIMED", runLinuxPinChild(root, "claim"))
        assertEquals("DENIED", runLinuxPinChild(root, "admit"))
        rejects { LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey) { }.admitted { } }
    }

    @Test fun twoActualJvmsCannotBothClaimTheSameInstallationDomain() = fixture { root ->
        val one = startLinuxPinChild(root, "race")
        val two = startLinuxPinChild(root, "race")
        try {
            File(root, "go").writeText("go")
            assertEquals(listOf("CLAIMED", "DENIED"), listOf(finishLinuxPinChild(one), finishLinuxPinChild(two)).sorted())
        } finally { one.destroyForcibly(); two.destroyForcibly() }
    }
}

internal fun startLinuxPinChild(directory: File, operation: String): Process {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val java = File(System.getProperty("java.home"), "bin/" + if (windows) "java.exe" else "java")
    val classes = listOf(LinuxRunnerPinProcess::class.java, LinuxGenerationRef::class.java,
        TerminalDurability::class.java, Unit::class.java).map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct()
    return ProcessBuilder(java.path, "-cp", classes.joinToString(File.pathSeparator),
        LinuxRunnerPinProcess::class.java.name, directory.path, operation).redirectErrorStream(true).start()
}
internal fun finishLinuxPinChild(process: Process): String {
    try {
        check(process.waitFor(15, TimeUnit.SECONDS)) { "pin_child_timeout" }
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.exitValue() == 0) { "pin_child_failed: $output" }
        return output
    } finally { if (process.isAlive) process.destroyForcibly() }
}
internal fun runLinuxPinChild(directory: File, operation: String) = finishLinuxPinChild(startLinuxPinChild(directory, operation))

/** Separate JVM intentionally abandons its original claim; no static-map reset simulates a restart. */
internal object LinuxRunnerPinProcess {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]); val operation = args[1]
        val pins = LinuxRunnerGenerationPins(root, fileKey = ::hostLinuxFileKey, bootIdentity = { "android:10" }) { }
        if (operation == "race") {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!File(root, "go").exists()) {
                check(System.nanoTime() < deadline); Thread.sleep(5)
            }
        }
        try {
            if (operation == "admit") { pins.admitted { }; println("ADMITTED") }
            else { pins.claim(LinuxGenerationRef("environment", "generation"), listOf("installation")) { }; println("CLAIMED") }
        } catch (_: IllegalStateException) { println("DENIED") }
    }
}
