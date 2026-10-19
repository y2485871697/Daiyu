package app.daiyu.android.terminal

import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.test.platform.app.InstrumentationRegistry
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit disposable-root-device coverage; default smoke does not enter root. */
@Suppress("DEPRECATION")
class LinuxChrootProductionDeviceTest : InstrumentationTestCase() {
    private fun rootObservation(command: String): String {
        val process = ProcessBuilder("/system/bin/su", "-c", command).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        var readFailure: Throwable? = null
        val reader = Thread {
            try {
                process.inputStream.use { stream ->
                    val bytes = ByteArray(4096)
                    while (true) {
                        val count = stream.read(bytes)
                        if (count < 0) break
                        check(output.size() + count <= 32768) { "root_observation_output_limit" }
                        output.write(bytes, 0, count)
                    }
                }
            } catch (failure: Throwable) { readFailure = failure }
        }.apply { start() }
        try {
            check(process.waitFor(8, TimeUnit.SECONDS)) { "root_observation_timeout" }
            reader.join(2000)
            check(!reader.isAlive) { "root_observation_reader_not_drained" }
            readFailure?.let { throw it }
            check(process.exitValue() == 0) { "root_observation_failed" }
            return output.toString("UTF-8")
        } finally {
            process.outputStream.close()
            if (process.isAlive) process.destroy()
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            process.inputStream.close()
            reader.join(2000)
            check(!process.isAlive && !reader.isAlive) { "retain_root_observation_process" }
        }
    }

    private fun leased(store: LinuxEnvironmentStore) {
        try { store.clearSelection(); fail("close must retain the selected generation until join") }
        catch (failure: IllegalStateException) { assertEquals("linux_environment_in_use", failure.message) }
    }

    fun testExplicitRootChrootSelectionAndDetachedDescendantDrain() {
        verifyExplicitRootChrootSelectionAndDetachedDescendantDrain()
    }

    private fun verifyExplicitRootChrootSelectionAndDetachedDescendantDrain() {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("daiyuChrootOptIn") != "true") return
        val holdSeconds = args.getString("daiyuChrootHoldSeconds")?.let { raw ->
            requireNotNull(raw.toIntOrNull()) { "invalid_chroot_hold_seconds" }
                .also { require(it in 60..600) { "invalid_chroot_hold_seconds" } }
        } ?: 0
        val context = instrumentation.targetContext
        check(context.packageName == "app.daiyu.android.synthetic") { "isolated_synthetic_required" }
        val source = File(requireNotNull(args.getString("daiyuChrootBusyboxSource")))
        check(source.isFile && source.length() in 1..16_777_216)
        val bytes = source.readBytes()
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
            "4d60ab3f5a59ebb2ca863f2f514e6924401b581e9b64f602665c008177626651")
        val token = UUID.randomUUID().toString().replace("-", "")
        val policy = File(context.filesDir, "linux-chroot-installations").canonicalFile
        check(policy.mkdirs() || policy.isDirectory)
        val environment = "device_$token"
        val installation = File(policy, environment)
        check(installation.mkdir())
        val rootfs = File(installation, "g1/rootfs")
        check(File(rootfs, "bin").mkdirs())
        for (name in listOf("root", "tmp", "dev")) check(File(rootfs, name).mkdir())
        File(rootfs, "empty").writeBytes(byteArrayOf())
        File(rootfs, "probe-token").writeText(token)
        File(rootfs, "bin/busybox").apply { writeBytes(bytes); check(setExecutable(true, true)) }
        Files.createSymbolicLink(File(rootfs, "bin/sh").toPath(), Paths.get("busybox"))
        val work = File(context.cacheDir, "chroot-device-$token").apply { check(mkdir()) }
        val selected = File(work, "selection")
        var session: LinuxPtySession? = null
        var startup: LinuxPtyStartupCleanup? = null
        var primary: Throwable? = null
        var scope: String? = null
        var parentPid: Int? = null
        var childPid: Int? = null
        var store: LinuxEnvironmentStore? = null
        var joined = false
        val report = JSONObject().put("token", token).put("production_delivery", false)
        try {
            val initial = LinuxEnvironmentStore(selected, policy)
            initial.select(LinuxInstallation("busybox", environment, "g1", rootfs.canonicalFile,
                executionMode = LinuxExecutionMode.CHROOT))
            val persisted = LinuxEnvironmentStore(selected, policy); store = persisted
            val status = persisted.status()
            assertEquals("FILES_READY", status.getString("state"))
            assertEquals("chroot", status.getString("execution_mode"))
            assertTrue(status.getBoolean("host_root_required"))
            assertFalse(status.getBoolean("host_root_verified"))
            val auth = TaskAuthorization("chroot-device-$token", token)
            val owner = PtyRunOwner(ConversationId("chroot-device-$token"), auth, environment, "g1")
            val active = AndroidPtyHost.openLinux(context, owner, persisted,
                LinuxGenerationRef(environment, "g1"), work, work, File(work, "journal"), token, 31, 97)
            session = active
            leased(persisted)
            val command = """
                /bin/busybox stty -echo
                printf '\nPARENT_$token UID=%s PID=%s\n' "${'$'}(/bin/busybox id -u)" "${'$'}${'$'}"
                printf '\nTOKEN:%s\n' "${'$'}(/bin/busybox cat /probe-token)"
                /bin/busybox cat /proc/self/status >/tmp/proc-status && /bin/busybox grep -q '^Name:' /tmp/proc-status && printf '\nPROC_$token FS=%s\n' "${'$'}(/bin/busybox stat -f -c %t /proc)"
                /bin/busybox setsid /bin/sh -c 'printf "\nCHILD_$token UID=%s PID=%s\n" "${'$'}(/bin/busybox id -u)" "${'$'}${'$'}"; exec /bin/busybox sleep ${holdSeconds + 300}' </empty &

            """.trimIndent() + "\n"
            val delivery = active.write(auth, "probe", 1, command.toByteArray())
            assertTrue(delivery.getBoolean("helper_input_written"))
            assertEquals("unknown", delivery.getString("pty_delivery"))
            assertEquals("unknown", delivery.getString("command_completion"))
            val parentPattern = Regex("(?m)^PARENT_$token UID=0 PID=([0-9]+)\\r?$")
            val childPattern = Regex("(?m)^CHILD_$token UID=0 PID=([0-9]+)\\r?$")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (System.nanoTime() < deadline) {
                val state = active.read(auth)
                val output = state.getString("stdout")
                report.put("last_state", state)
                parentPid = parentPattern.find(output)?.groupValues?.get(1)?.toInt()
                childPid = childPattern.find(output)?.groupValues?.get(1)?.toInt()
                if (parentPid != null && childPid != null && output.contains("\nTOKEN:$token\r\n") &&
                    output.contains("\nPROC_$token FS=9fa0\r\n")) {
                    scope = state.getString("root_scope"); break
                }
                Thread.sleep(20)
            }
            val parent = checkNotNull(parentPid); val child = checkNotNull(childPid)
            check(parent > 1 && child > 1 && parent != child)
            val originalScope = checkNotNull(scope)
            check(originalScope.matches(Regex("daiyu-[0-9a-f]{32}")))
            val before = rootObservation("set -eu; test \"$(/system/bin/id -u)\" = 0; " +
                "for p in $parent $child; do printf 'PID:%s\\n' \"\$p\"; " +
                "cat /proc/\$p/status /proc/\$p/stat /proc/\$p/cgroup; readlink /proc/\$p/root; done; " +
                "cat /sys/fs/cgroup/uid_0/$originalScope/cgroup.events")
            val mounts = rootObservation("set -eu; cat /proc/$parent/mountinfo")
            val procMount = mounts.lineSequence().single { line ->
                line.split(" ").getOrNull(4) == "/proc" && line.contains(" - proc ")
            }
            assertTrue(procMount.split(" ")[5].split(",").containsAll(listOf("nosuid", "nodev", "noexec")))
            val hostMounts = rootObservation("cat /proc/self/mountinfo")
            assertFalse(hostMounts.contains(" ${rootfs.canonicalPath}/proc "))
            val rebuilt = LinuxEnvironmentStore(selected, policy)
            leased(rebuilt)
            report.put("guest_mountinfo", mounts).put("host_mountinfo", hostMounts)
                .put("live_store_rebuild_lease_verified", true)
            report.put("before", before).put("scope", originalScope).put("parent_pid", parent).put("child_pid", child)
            assertEquals(2, Regex("(?m)^Uid:\\s+0\\s+0\\s+0\\s+0$").findAll(before).count())
            assertEquals(2, Regex("(?m)^0::/uid_0/$originalScope$").findAll(before).count())
            assertEquals(2, before.lineSequence().count { it == rootfs.canonicalPath })
            val childStat = before.lineSequence().single { it.startsWith("$child (") }
            val fields = childStat.substringAfterLast(") ").split(" ")
            assertEquals(child.toString(), fields[3])
            assertTrue(before.lineSequence().any { it == "populated 1" })
            // An opt-in dwell proves continued ownership, not merely a sleep command being spawned.
            val holdStart = SystemClock.elapsedRealtime()
            val holdSamples = org.json.JSONArray()
            report.put("hold_requested_seconds", holdSeconds).put("hold_samples", holdSamples)
            val parentStat = before.lineSequence().single { it.startsWith("$parent (") }
            fun startTime(stat: String) = stat.substringAfterLast(") ").split(" ")[19]
            val parentStartTime = startTime(parentStat)
            val childStartTime = startTime(childStat)
            var lastVerifiedAt = holdStart
            var maxGapMs = 0L
            report.put("hold_max_allowed_gap_ms", 5000L)
            try {
                while (SystemClock.elapsedRealtime() - holdStart < holdSeconds * 1000L) {
                    Thread.sleep(minOf(1000L, holdSeconds * 1000L - (SystemClock.elapsedRealtime() - holdStart)).coerceAtLeast(1L))
                    val sample = JSONObject().put("started_ms", SystemClock.elapsedRealtime() - holdStart)
                        .put("verified", false)
                    holdSamples.put(sample)
                    val observed = rootObservation("set -eu; for p in $parent $child; do " +
                        "cat /proc/\$p/stat /proc/\$p/cgroup; readlink /proc/\$p/root; done; " +
                        "cat /sys/fs/cgroup/uid_0/$originalScope/cgroup.events")
                    val observedAt = SystemClock.elapsedRealtime()
                    val gapMs = observedAt - lastVerifiedAt
                    maxGapMs = maxOf(maxGapMs, gapMs)
                    sample.put("elapsed_ms", observedAt - holdStart).put("gap_ms", gapMs)
                    check(gapMs <= 5000L) { "chroot_hold_observation_gap" }
                    assertEquals(parentStartTime, startTime(observed.lineSequence().single { it.startsWith("$parent (") }))
                    assertEquals(childStartTime, startTime(observed.lineSequence().single { it.startsWith("$child (") }))
                    assertEquals(2, observed.lineSequence().count { it == "0::/uid_0/$originalScope" })
                    assertEquals(2, observed.lineSequence().count { it == rootfs.canonicalPath })
                    assertTrue(observed.lineSequence().any { it == "populated 1" })
                    leased(persisted)
                    sample.put("parent_start_ticks", parentStartTime).put("child_start_ticks", childStartTime)
                        .put("scope", originalScope).put("roots_verified", true).put("verified", true)
                    lastVerifiedAt = observedAt
                }
                if (holdSeconds > 0) assertTrue(holdSamples.length() > 0)
            } finally {
                report.put("hold_elapsed_ms", SystemClock.elapsedRealtime() - holdStart)
                    .put("hold_max_gap_ms", maxGapMs)
            }
            active.close(); leased(persisted)
            joined = active.awaitTermination(30_000)
            assertTrue("must observe D receipt, reader exit and launcher exit", joined)
            val after = rootObservation("set -eu; test \"$(/system/bin/id -u)\" = 0; " +
                "test ! -e /proc/$parent; test ! -e /proc/$child; " +
                "test ! -e /sys/fs/cgroup/uid_0/$originalScope; printf 'original_scope_and_pids_gone\\n'")
            assertEquals("original_scope_and_pids_gone", after.trim())
            val afterMounts = rootObservation("cat /proc/self/mountinfo")
            assertFalse(afterMounts.contains(" ${rootfs.canonicalPath}/proc "))
            report.put("after", after).put("host_mountinfo_after", afterMounts).put("passed", true)
        } catch (failure: LinuxPtyStartupFailure) {
            report.put("startup_diagnostics", (failure.cause as? PtyStartupFailure)?.diagnostics ?: JSONObject.NULL)
            startup = failure.cleanup; primary = failure; throw failure
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try {
                val active = session; val cleanup = startup
                if (active != null) { try { active.close() } finally { joined = active.awaitTermination(30_000) } }
                else if (cleanup != null) { try { cleanup.requestClose() } finally { joined = cleanup.awaitTermination(30_000) } }
                else joined = true
                check(joined) { "retain_chroot_device_resources:$work:$installation" }
                store?.clearSelection()
                check(work.deleteRecursively())
                check(installation.deleteRecursively())
            } catch (failure: Throwable) {
                if (primary != null) primary.addSuppressed(failure) else { primary = failure; throw failure }
            } finally {
                report.put("joined", joined).put("failed", primary != null)
                if (primary != null) report.put("passed", false)
                try {
                    val evidence = File(checkNotNull(context.getExternalFilesDir("chroot-device")), "$token.json")
                    java.io.FileOutputStream(evidence).use { output ->
                        output.write(report.toString(2).toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                } catch (evidenceFailure: Exception) {
                    if (primary != null) primary.addSuppressed(evidenceFailure) else throw evidenceFailure
                }
            }
        }
    }
}
