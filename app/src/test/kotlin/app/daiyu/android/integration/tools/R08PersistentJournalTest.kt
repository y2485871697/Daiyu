package app.daiyu.android.integration.tools

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real local filesystem tests. No owner process, Android device, network, shell or tool dispatch. */
class R08PersistentJournalTest {
    private val execute = R08VirtualDisplayPermit.EXECUTE_ONCE
    private val lookup = R08VirtualDisplayPermit.LOOKUP_ONLY
    private val unknown = R08VirtualDisplayPermit.UNKNOWN

    private fun fixture(test: (Path, Path) -> Unit) {
        assumeTrue(File("/").toPath().fileSystem.supportedFileAttributeViews().contains("posix"))
        val base = Files.createTempDirectory("r08-journal-test-").toRealPath()
        val root = Files.createDirectory(base.resolve("journal"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        try { test(root, base) } finally {
            Files.walk(base).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test fun firstClaimIsDurableAndRepeatAndReopenOnlyLookup() = fixture { root, _ ->
        val intent = intent()
        val stages = mutableListOf<R08PersistentJournal.WritePoint>()
        val journal = R08PersistentJournal(root) { stages.add(it) }
        assertEquals(execute, journal.claim(intent))
        assertEquals(R08PersistentJournal.WritePoint.entries.toList(), stages)
        assertEquals(lookup, journal.claim(intent))
        assertEquals(lookup, R08PersistentJournal(root).claim(intent))
        assertEquals(1, events(root).size)
        assertFalse(Files.exists(root.resolve("00000000000000000000.pending")))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(events(root).single()))
        val persisted = decode(events(root).single())
        assertEquals(1, persisted.kind)
        assertEquals(listOf(intent.bindingKey, intent.generation, intent.invocationId,
            intent.operationId, intent.operation, intent.canonicalParameters), persisted.fields)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(root.resolve(".r08.lock")))
    }

    @Test fun independentInvocationsAndAllPreparedWriteKindsRemainDistinct() = fixture { root, _ ->
        val journal = R08PersistentJournal(root)
        val intents = listOf("start", "initialize", "launch", "input", "release").map { operation ->
            intent(invocation = "invocation-$operation").copy(operation = operation,
                canonicalParameters = when (operation) {
                    "launch" -> "{\"package\":\"com.example.app\"}"
                    "input" -> "{\"kind\":\"tap\",\"x\":1,\"y\":2}"
                    else -> "{}"
                })
        }
        intents.forEach { assertEquals(execute, journal.claim(it)) }
        assertEquals(5, events(root).size)
        val reopened = R08PersistentJournal(root)
        intents.forEach { assertEquals(lookup, reopened.claim(it)) }
        assertEquals(intents.map { it.operation }, events(root).map { decode(it).fields[4] })
    }

    @Test fun manyThreadsAndInstancesProduceExactlyOnePermission() = fixture { root, _ ->
        val pool = Executors.newFixedThreadPool(12)
        val ready = CountDownLatch(12)
        val start = CountDownLatch(1)
        val journals = List(12) { R08PersistentJournal(root) }
        try {
            val results = journals.map { journal -> pool.submit<R08VirtualDisplayPermit> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                journal.claim(intent())
            } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val permissions = results.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, permissions.count { it == execute })
            assertEquals(11, permissions.count { it == lookup })
            assertEquals(1, events(root).size)
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun independentJvmProcessesSerializeAndRestartDoesNotReexecute() = fixture { root, base ->
        val gate = base.resolve("go")
        val processes = ArrayList<Process>()
        try {
            repeat(4) { index -> processes += child(root, gate, base.resolve("ready-$index")) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while ((0..3).any { !Files.exists(base.resolve("ready-$it")) } && System.nanoTime() < deadline) {
                if (processes.any { !it.isAlive }) break
                Thread.sleep(10)
            }
            assertTrue("child JVMs reached the barrier", (0..3).all { Files.exists(base.resolve("ready-$it")) })
            Files.createFile(gate)
            val results = processes.map { result(it) }
            assertEquals(1, results.count { it == execute.name })
            assertEquals(3, results.count { it == lookup.name })
            val restarted = child(root, gate, base.resolve("ready-restart"))
            processes += restarted
            assertEquals(lookup.name, result(restarted))
            assertEquals(lookup, R08PersistentJournal(root).claim(intent()))
        } finally {
            processes.forEach { if (it.isAlive) { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) } }
        }
    }

    @Test fun changedBindingGenerationOperationAndParametersFailClosed() = fixture { root, _ ->
        val journal = R08PersistentJournal(root)
        val original = intent()
        assertEquals(execute, journal.claim(original))
        val changes = listOf(
            intent(binding = "b".repeat(64)),
            intent(generation = "generation_2"),
            original.copy(operation = "launch", canonicalParameters = "{\"package\":\"com.example.other\"}"),
            original.copy(canonicalParameters = "{\"kind\":\"text\",\"text\":\"changed-secret\"}"),
            original.copy(operationId = "f".repeat(64))
        )
        for (changed in changes) {
            assertEquals(unknown, journal.claim(changed))
            failsRedacted { journal.complete(changed, R08VirtualDisplayEvidence.CONFIRMED) }
        }
        failsRedacted { journal.finalOutcome("b".repeat(64), original.generation, original.invocationId,
            R08VirtualDisplayEvidence.UNKNOWN) }
        assertEquals(1, events(root).size)
        assertEquals(lookup, R08PersistentJournal(root).claim(original))
    }

    @Test fun everyWriteInterruptionRetainsEvidenceAndNeverGrantsAgain() {
        for (point in R08PersistentJournal.WritePoint.entries) fixture { root, _ ->
            val original = intent()
            val journal = R08PersistentJournal(root) { if (it == point) throw IOException("test private value") }
            assertEquals("failure at $point", unknown, journal.claim(original))
            val before = records(root).associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
            assertEquals(1, before.size)
            val expected = if (point == R08PersistentJournal.WritePoint.PENDING_CREATED ||
                point == R08PersistentJournal.WritePoint.BEFORE_WRITE) unknown else lookup
            assertEquals("same instance at $point", expected, journal.claim(original))
            assertEquals("restart at $point", expected, R08PersistentJournal(root).claim(original))
            assertEquals(before, records(root).associate { it.fileName.toString() to Files.readAllBytes(it).toList() })
            if (records(root).any { it.fileName.toString().endsWith(".pending") }) {
                // Do not skip a pending tail to grant a different invocation either.
                assertEquals(unknown, R08PersistentJournal(root).claim(intent(invocation = "other")))
            }
        }
    }

    @Test fun crashAfterClaimBeforeExecutionIsLookupOnly() = fixture { root, _ ->
        assertEquals(execute, R08PersistentJournal(root).claim(intent()))
        // No complete/final callback: the next process must not infer NOT_EXECUTED.
        assertEquals(lookup, R08PersistentJournal(root).claim(intent()))
        assertEquals(1, events(root).size)
    }

    @Test fun corruptAndTruncatedAndOversizedRecordsAreNotEmptyJournals() {
        for (mutation in listOf<(ByteArray) -> ByteArray>(
            { bytes -> bytes.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() } },
            { bytes -> bytes.copyOf(8) },
            { _ -> ByteArray(100 * 1024) }
        )) fixture { root, _ ->
            assertEquals(execute, R08PersistentJournal(root).claim(intent()))
            val path = events(root).single()
            Files.write(path, mutation(Files.readAllBytes(path)))
            val evidence = Files.readAllBytes(path).toList()
            val journal = R08PersistentJournal(root)
            assertEquals(unknown, journal.claim(intent()))
            assertEquals(unknown, journal.claim(intent(invocation = "different")))
            failsRedacted { journal.finalOutcome(intent().bindingKey, "generation_1", "semantic", R08VirtualDisplayEvidence.UNKNOWN) }
            assertEquals(evidence, Files.readAllBytes(path).toList())
            assertEquals(1, events(root).size)
        }
    }

    @Test fun observationsAppendAndFinalUnknownCannotRestorePermissionOrEraseHistory() = fixture { root, _ ->
        val journal = R08PersistentJournal(root)
        val original = intent()
        assertEquals(execute, journal.claim(original))
        val claimBytes = Files.readAllBytes(events(root).single()).toList()
        journal.complete(original, R08VirtualDisplayEvidence.CONFIRMED)
        journal.finalOutcome(original.bindingKey, original.generation, original.invocationId, R08VirtualDisplayEvidence.UNKNOWN)
        journal.complete(original, R08VirtualDisplayEvidence.NOT_EXECUTED)
        journal.finalOutcome(original.bindingKey, original.generation, original.invocationId, R08VirtualDisplayEvidence.CONFIRMED)
        val persisted = events(root).map(::decode)
        assertEquals(listOf(1, 2, 3, 2, 3), persisted.map { it.kind })
        assertEquals(listOf(null, 0, 2, 1, 0), persisted.map { it.evidence })
        assertEquals(claimBytes, Files.readAllBytes(events(root).first()).toList())
        assertEquals(lookup, R08PersistentJournal(root).claim(original))
        assertEquals(5, events(root).size)
    }

    @Test fun centralSemanticReplayWithoutClaimCreatesOnlyFinalObservation() = fixture { root, _ ->
        val journal = R08PersistentJournal(root)
        val original = intent()
        journal.finalOutcome(original.bindingKey, original.generation, original.invocationId, R08VirtualDisplayEvidence.UNKNOWN)
        journal.finalOutcome(original.bindingKey, original.generation, original.invocationId, R08VirtualDisplayEvidence.CONFIRMED)
        assertEquals(listOf(3, 3), events(root).map { decode(it).kind })
        assertEquals(listOf(original.bindingKey, original.generation, original.invocationId), decode(events(root).first()).fields)
        assertEquals(lookup, journal.claim(original))
        assertEquals(lookup, R08PersistentJournal(root).claim(original))
        failsRedacted { journal.complete(original, R08VirtualDisplayEvidence.CONFIRMED) }
        assertEquals(2, events(root).size)
    }

    @Test fun observationWriteFailuresAreNotSilentlySuccessfulAndKeepClaim() = fixture { root, _ ->
        val original = intent()
        assertEquals(execute, R08PersistentJournal(root).claim(original))
        val failing = R08PersistentJournal(root) {
            if (it == R08PersistentJournal.WritePoint.BEFORE_DIRECTORY_SYNC) throw IOException("fsync failure")
        }
        failsRedacted { failing.complete(original, R08VirtualDisplayEvidence.NOT_EXECUTED) }
        assertEquals(lookup, R08PersistentJournal(root).claim(original))
        failsRedacted { failing.finalOutcome(original.bindingKey, original.generation, original.invocationId, R08VirtualDisplayEvidence.UNKNOWN) }
        val observations = events(root).map(::decode)
        assertEquals(listOf(1, 2, 3), observations.map { it.kind })
        assertEquals(2, observations.last().evidence)
        assertEquals(lookup, R08PersistentJournal(root).claim(original))
    }

    @Test fun completeWithoutClaimCannotInventAnExecution() = fixture { root, _ ->
        val journal = R08PersistentJournal(root)
        failsRedacted { journal.complete(intent(), R08VirtualDisplayEvidence.NOT_EXECUTED) }
        assertTrue(events(root).isEmpty())
        assertEquals(execute, journal.claim(intent()))
    }

    @Test fun inputBoundsRejectBeforeWritingAndDiagnosticsAreRedacted() = fixture { root, _ ->
        val journal = R08PersistentJournal(root)
        for (invalid in listOf(
            intent().copy(canonicalParameters = "private-secret".repeat(4000)),
            intent().copy(canonicalParameters = "\uD800"),
            intent(invocation = "x".repeat(1025)),
            intent().copy(invocationId = "private\nsecret"),
            intent().copy(bindingKey = "not-a-digest"),
            intent().copy(generation = "../../secret"),
            intent().copy(operation = "shutdown")
        )) assertEquals(unknown, journal.claim(invalid))
        assertTrue(events(root).isEmpty())
        assertFalse(journal.toString().contains(root.toString()))
        assertEquals("R08PersistentJournal(private)", journal.toString())
        assertEquals(execute, journal.claim(intent()))
    }

    @Test fun rejectsSymlinkRootsFilesAndAncestorsAndNonPrivatePermissions() = fixture { root, base ->
        val alias = Files.createSymbolicLink(base.resolve("alias"), root)
        failsRedacted { R08PersistentJournal(alias) }
        val nested = Files.createDirectory(root.resolve("nested"), PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")))
        failsRedacted { R08PersistentJournal(alias.resolve("nested")) }
        Files.delete(nested)
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"))
        failsRedacted { R08PersistentJournal(root) }
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        val sentinel = Files.write(base.resolve("sentinel"), byteArrayOf(1, 2, 3))
        Files.createSymbolicLink(root.resolve(".r08.lock"), sentinel)
        assertEquals(unknown, R08PersistentJournal(root).claim(intent()))
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(sentinel))
        Files.delete(root.resolve(".r08.lock"))
        assertEquals(execute, R08PersistentJournal(root).claim(intent()))
        Files.delete(events(root).single())
        Files.createSymbolicLink(root.resolve("00000000000000000000.event"), sentinel)
        assertEquals(unknown, R08PersistentJournal(root).claim(intent()))
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(sentinel))
    }

    @Test fun unexpectedFilesAndSequenceGapsFailClosed() {
        fixture { root, _ ->
            Files.write(root.resolve("unknown-artifact"), byteArrayOf(1))
            assertEquals(unknown, R08PersistentJournal(root).claim(intent()))
        }
        fixture { root, _ ->
            val journal = R08PersistentJournal(root)
            assertEquals(execute, journal.claim(intent()))
            Files.move(events(root).single(), root.resolve("00000000000000000001.event"))
            assertEquals(unknown, journal.claim(intent()))
        }
    }

    private fun child(root: Path, gate: Path, ready: Path): Process {
        val classpath = listOf(R08PersistentJournalProcessProbe::class.java, R08PersistentJournal::class.java,
            kotlin.Unit::class.java).map { Paths.get(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(File.pathSeparator)
        return ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, R08PersistentJournalProcessProbe::class.java.name,
            root.toString(), gate.toString(), ready.toString()).redirectErrorStream(true).start()
    }

    private fun result(process: Process): String {
        assertTrue("child JVM completed", process.waitFor(20, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals("child JVM successful: $output", 0, process.exitValue())
        return output
    }

    private fun records(root: Path): List<Path> = Files.newDirectoryStream(root).use { paths ->
        paths.filter { it.fileName.toString() != ".r08.lock" }.sortedBy { it.fileName.toString() }
    }
    private fun events(root: Path) = records(root).filter { it.fileName.toString().endsWith(".event") }

    private class Decoded(val kind: Int, val fields: List<String>, val evidence: Int?)
    private fun decode(path: Path): Decoded {
        val bytes = Files.readAllBytes(path)
        val body = bytes.copyOfRange(0, bytes.size - 32)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(bytes.size - 32, bytes.size))
        val input = DataInputStream(ByteArrayInputStream(body))
        assertEquals(0x52303831, input.readInt())
        val kind = input.readUnsignedByte()
        val fields = List(if (kind == 3) 3 else 6) {
            ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)
        }
        val evidence = if (kind == 1) null else input.readUnsignedByte()
        assertEquals(0, input.available())
        return Decoded(kind, fields, evidence)
    }

    private fun failsRedacted(block: () -> Unit) {
        try { block(); fail("Expected fail-closed IOException") } catch (error: IOException) {
            assertEquals("R08 journal unavailable or conflicting", error.message)
            assertNull(error.cause)
        }
    }

    companion object {
        internal fun intent(binding: String = "a".repeat(64), generation: String = "generation_1",
                            invocation: String = "real-invocation-1"): R08VirtualDisplayIntent {
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in listOf(binding, generation, invocation)) {
                val bytes = part.toByteArray(Charsets.UTF_8)
                digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
                digest.update(bytes)
            }
            return R08VirtualDisplayIntent(binding, generation, invocation,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, "input",
                "{\"kind\":\"text\",\"text\":\"private-text-秘密\"}")
        }
    }
}

/** Fork target tests OS locks and persisted reopen, not Android owner startup. */
object R08PersistentJournalProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = Paths.get(args[0])
        val gate = Paths.get(args[1])
        Files.createFile(Paths.get(args[2]))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!Files.exists(gate)) {
            check(System.nanoTime() < deadline)
            Thread.sleep(5)
        }
        // Keep fork target independent of JUnit class initialization/classpath.
        val binding = "a".repeat(64)
        val generation = "generation_1"
        val invocation = "real-invocation-1"
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf(binding, generation, invocation)) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        val intent = R08VirtualDisplayIntent(binding, generation, invocation,
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, "input",
            "{\"kind\":\"text\",\"text\":\"private-text-秘密\"}")
        println(R08PersistentJournal(root).claim(intent).name)
    }
}
