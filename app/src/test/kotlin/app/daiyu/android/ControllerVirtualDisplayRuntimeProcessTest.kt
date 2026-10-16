package app.daiyu.android

import app.daiyu.android.ControllerVirtualDisplayRuntime.LeaseStore
import app.daiyu.android.ControllerVirtualDisplayRuntime.Mutation
import app.daiyu.android.ControllerVirtualDisplayRuntime.WritePoint
import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.integration.tools.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.lang.ref.Reference
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Host filesystem evidence, not an Android filesystem/power-loss test. Each contender is a JVM
 * with its own semaphore and OS lock table. No copied directories stand in for process death. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class ControllerVirtualDisplayRuntimeProcessTest {
    @Test fun liveLeaseExcludesAnotherProcessEvenAfterSameProcessGateRejection() = withDirectory { base ->
        val root = base.resolve("leases")
        val gate = Semaphore(1)
        val store = LeaseStore({ root }, gate)
        val lease = store.reserve(::processPlan)
        assertEquals(23, RuntimeChildJvm.probe(base, root.resolve("lease.lock")))
        assertThrows(IOException::class.java) { LeaseStore({ root }, gate).reserve(::processPlan) }
        // Independent OS probe, not lease.durable / semaphore state, detects dropped POSIX locks.
        assertEquals(23, RuntimeChildJvm.probe(base, root.resolve("lease.lock")))
        lease.releaseAfterDrain()
        assertEquals(0, RuntimeChildJvm.probe(base, root.resolve("lease.lock")))
    }

    @Test fun actualProcessExitReopensOriginalHistoryWithoutRepairOrNewGeneration() {
        for (mode in listOf("active", "JOURNAL_CREATED", "PENDING_CREATED", "PUBLISHED", "released")) {
            withDirectory { base ->
                val root = base.resolve("leases")
                RuntimeChildJvm.history(base, root, mode).use { child ->
                    child.awaitReady()
                    if (mode != "released") {
                        // The writer is still alive. Even a failed reserve retains its OS lock here.
                        FileChannel.open(root.resolve("lease.lock"), WRITE).use { channel ->
                            assertNull(channel.tryLock())
                        }
                        val blockedGate = Semaphore(1)
                        var prepared = false
                        val before = snapshot(root)
                        assertThrows(IOException::class.java) {
                            LeaseStore({ root }, blockedGate).reserve { prepared = true; processPlan(it) }
                        }
                        assertFalse(prepared) // a different process gate cannot bypass the OS lock
                        assertEquals(0, blockedGate.availablePermits())
                        assertEquals(before, snapshot(root))
                    }
                    child.killAndWait() // no release callback, finally repair, or image copying
                }
                assertEquals(0, RuntimeChildJvm.probe(base, root.resolve("lease.lock")))
                val before = snapshot(root)
                val gate = Semaphore(1) // independent of the exited writer's in-memory gate
                val reopened = LeaseStore({ root }, gate)
                if (mode == "released") {
                    val next = reopened.reserve(::processPlan)
                    assertFalse(before.keys.any { it.startsWith(next.directory.fileName.toString() + "/") })
                    for ((path, bytes) in before) assertEquals(bytes, snapshot(root)[path])
                    next.releaseAfterDrain()
                } else {
                    var prepared = false
                    val error = assertThrows(IOException::class.java) {
                        reopened.reserve { prepared = true; processPlan(it) }
                    }
                    assertEquals("virtual_display_runtime_unknown", error.message)
                    assertFalse(prepared)
                    assertEquals(before, snapshot(root))
                    assertThrows(IOException::class.java) { reopened.reserve(::processPlan) }
                    assertEquals(0, gate.availablePermits())
                }
            }
        }
    }

    /** Invoked only by RuntimeChildJvmMain through JUnit/Robolectric in a fresh JVM. */
    @Test fun subprocessHistoryFixture() {
        val directory = System.getProperty("r08.child.root") ?: return
        val root = Path.of(directory)
        val mode = System.getProperty("r08.child.mode")
        val store = LeaseStore({ root }, Semaphore(1)) { mutation, point ->
            if (mutation == Mutation.RESERVE && point.name == mode) throw IOException("injected")
        }
        val lease = if (mode in setOf("active", "released")) store.reserve(::processPlan) else {
            assertThrows(IOException::class.java) { store.reserve(::processPlan) }
            null
        }
        if (mode == "released") checkNotNull(lease).releaseAfterDrain()
        Files.writeString(root.parent.resolve("child.ready"), mode)
        System.`in`.read() // parent kills this JVM while references and lock are still live
        Reference.reachabilityFence(lease)
        Reference.reachabilityFence(store)
    }

    private fun snapshot(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            root.relativize(it).toString() to Files.readAllBytes(it).toList()
        }
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val base = Files.createTempDirectory("r08-child-").toRealPath()
        try { block(base) } finally {
            Files.walk(base).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}

private fun processPlan(generation: String): R08VirtualDisplayPlan {
    val owner = runnerOwner(Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
    return R08VirtualDisplayPlan.prepare(owner,
        SessionCapabilities("session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY)), generation,
        listOf(R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start)))
}

/** ProcessBuilder launches only the local JVM fixture, never a shell, owner, or launcher. */
internal object RuntimeChildJvm {
    fun probe(base: Path, lock: Path): Int {
        val log = base.resolve("probe.log")
        val process = start(log, listOf("probe", lock.toString()))
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "lock probe timed out" }
            val code = process.exitValue()
            check(code == 0 || code == 23) { "lock probe failed: ${Files.readString(log)}" }
            return code
        } finally {
            process.destroyForcibly()
            check(process.waitFor(30, TimeUnit.SECONDS))
        }
    }

    fun history(base: Path, root: Path, mode: String): Child {
        val log = base.resolve("child.log")
        return Child(start(log, listOf("history"), listOf(
            "-Dr08.child.root=$root", "-Dr08.child.mode=$mode")), base.resolve("child.ready"), log)
    }

    class Child(private val process: Process, private val ready: Path, private val log: Path) : AutoCloseable {
        fun awaitReady() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            while (!Files.exists(ready) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            check(Files.exists(ready) && process.isAlive) { "history child not ready: ${Files.readString(log)}" }
        }
        fun killAndWait() {
            process.destroyForcibly()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "history child did not exit" }
        }
        override fun close() = killAndWait()
    }

    private fun start(log: Path, args: List<String>, properties: List<String> = emptyList()): Process {
        // Gradle's worker classpath alone can omit test/runtime jars. Include the actual loader URLs.
        val paths = linkedSetOf<String>()
        paths += System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotEmpty() }
        var loader: ClassLoader? = RuntimeChildJvmMain::class.java.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.getURLs().filter { it.protocol == "file" }.forEach {
                paths += File(it.toURI()).path
            }
            loader = loader.parent
        }
        listOf(RuntimeChildJvmMain::class.java, JUnitCore::class.java, RobolectricTestRunner::class.java,
            kotlin.Unit::class.java).forEach { type ->
            type.protectionDomain.codeSource?.location?.let { paths += File(it.toURI()).path }
        }
        val inherited = System.getProperties().stringPropertyNames().filter { it.startsWith("robolectric.") }
            .map { "-D$it=${System.getProperty(it)}" }
        return ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString()) +
            inherited + properties + listOf("-cp", paths.joinToString(File.pathSeparator),
            RuntimeChildJvmMain::class.java.name) + args)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
    }
}

object RuntimeChildJvmMain {
    @JvmStatic fun main(args: Array<String>) {
        if (args[0] == "probe") {
            FileChannel.open(Path.of(args[1]), WRITE).use { channel ->
                val lock = channel.tryLock() ?: exitProcess(23)
                lock.release()
            }
            exitProcess(0)
        }
        val result = JUnitCore().run(Request.method(ControllerVirtualDisplayRuntimeProcessTest::class.java,
            "subprocessHistoryFixture"))
        result.failures.forEach { System.err.println(it.trace) }
        exitProcess(if (result.wasSuccessful()) 0 else 1)
    }
}
