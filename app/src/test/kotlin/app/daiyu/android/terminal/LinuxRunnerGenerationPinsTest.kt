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
        val first = LinuxRunnerGenerationPins(root) { }
        val second = LinuxRunnerGenerationPins(root) { }
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
        val pins = LinuxRunnerGenerationPins(root) { throw IOException("directory sync") }
        try { pins.claim(generation, listOf("installation")) { }; fail("claim must fail") } catch (_: IOException) { }
        rejects { LinuxRunnerGenerationPins(root) { }.admitted { } }
        assertEquals(1, root.listFiles()!!.count { it.name.startsWith(".runner-pin-") })
    }

    @Test fun failedSettlementRetainsFenceUntilOriginalSyncRetry() = fixture { root ->
        var failSync = false
        val pins = LinuxRunnerGenerationPins(root) { if (failSync) throw IOException("settlement sync") }
        val original = pins.claim(generation, listOf("installation")) { }
        failSync = true
        try { original.settleAfterTermination(); fail("sync must fail") } catch (_: IOException) { }
        rejects { pins.admitted { } }
        failSync = false
        original.settleAfterTermination()
        pins.admitted(requireEmpty = true) { }
    }

    @Test fun changedSelectionBeforeClaimNeverWritesAPin() = fixture { root ->
        val pins = LinuxRunnerGenerationPins(root) { }
        rejects { pins.claim(generation, listOf("installation")) { error("selection changed") } }
        pins.admitted(requireEmpty = true) { }
    }

    @Test fun sameBytesAtAReplacementPinInodeAreNeverAdopted() = fixture { root ->
        val pins = LinuxRunnerGenerationPins(root) { }
        val original = pins.claim(generation, listOf("installation")) { }
        val file = root.listFiles()!!.single { it.name.startsWith(".runner-pin-") }
        val replacement = File(root, "replacement").apply { writeBytes(file.readBytes()) }
        Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        rejects { pins.admitted { } }
        rejects { original.settleAfterTermination() }
    }

    @Test fun replacingTheLockOrRootCannotCreateANewAdmissionDomain() = fixture { root ->
        val directory = File(root, "pins").apply { mkdirs() }
        val pins = LinuxRunnerGenerationPins(directory) { }
        pins.admitted { }
        val lock = File(directory, ".runner-pins.lock")
        assertTrue(lock.renameTo(File(directory, "old-lock"))); lock.writeText("")
        rejects { pins.admitted { } }
        assertTrue(directory.renameTo(File(root, "old-root"))); assertTrue(directory.mkdir())
        rejects { pins.admitted { } }
    }

    @Test fun actualNewJvmCannotTreatDeadOwnerOrNewScopeAsDrain() = fixture { root ->
        assertEquals("CLAIMED", runLinuxPinChild(root, "claim"))
        assertEquals("DENIED", runLinuxPinChild(root, "admit"))
        rejects { LinuxRunnerGenerationPins(root) { }.admitted { } }
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
        val pins = LinuxRunnerGenerationPins(root) { }
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
