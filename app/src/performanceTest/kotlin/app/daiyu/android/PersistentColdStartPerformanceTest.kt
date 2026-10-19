package app.daiyu.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.AtomicFile
import android.view.ViewTreeObserver
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.domain.ConversationId
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Persistent disposable synthetic data; each sample requires a new externally stopped process. */
@Suppress("DEPRECATION")
class PersistentColdStartPerformanceTest : InstrumentationTestCase() {
    private val ui = createEmptyComposeRule()
    private fun legacyRunBare() = super.runBare()
    override fun runBare() {
        ui.apply(object : Statement() { override fun evaluate() = legacyRunBare() },
            Description.createTestDescription(javaClass, name)).evaluate()
    }
    private fun <T> main(action: () -> T): T = syntheticMain(instrumentation, action)
    private fun target(): ChatApplication {
        val context = instrumentation.targetContext
        check(context.packageName == "app.daiyu.android.synthetic")
        check(instrumentation.context.packageName == "${context.packageName}.test")
        check(android.os.Build.VERSION.SDK_INT >= 29)
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        check(context.applicationInfo.isProfileableByShell)
        check(InstrumentationRegistry.getArguments().getString("daiyuColdStartOptIn") == "true")
        return context.applicationContext as ChatApplication
    }
    private fun token(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("daiyuColdStartOwner"))
        .also { check(it.matches(Regex("[0-9a-f]{32}"))) }
    private fun directory(app: ChatApplication) = File(app.noBackupFilesDir, "synthetic-cold-start-v1")
    private fun marker(app: ChatApplication) = File(directory(app), "owner.json")
    private fun readOwner(app: ChatApplication): JSONObject = AtomicFile(marker(app)).openRead()
        .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }.also {
        check(it.getString("owner_token") == token())
        check(it.getString("state") == "prepared")
        check(it.getString("application_id") == app.packageName)
        check(it.getString("database_path") == app.getDatabasePath("conversations.db").canonicalPath)
    }
    private fun saveOwner(app: ChatApplication, record: JSONObject) {
        val atomic = AtomicFile(marker(app)); val stream = atomic.startWrite()
        try { stream.write(record.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
    private fun publishSample(output: File, sample: JSONObject) {
        val pending = File(output.parentFile, ".${output.name}.${UUID.randomUUID()}.pending")
        check(pending.createNewFile())
        try {
            pending.outputStream().use { stream ->
                stream.write(sample.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            check(!output.exists()) { "existing_cold_start_sample_retained" }
            check(pending.renameTo(output)) { "cold_start_sample_publish_failed" }
        } finally { check(!pending.exists() || pending.delete()) { "cold_start_pending_retained" } }
    }
    private fun awaitReady(controller: ChatController) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!main { controller.ready }) {
            check(SystemClock.elapsedRealtime() < deadline) { "cold_start_controller_not_ready" }
            Thread.sleep(10)
        }
        check(main { controller.problem == null }) { "cold_start_recovery_failed" }
    }
    private fun verify(app: ChatApplication, owner: JSONObject) {
        awaitReady(app.controller)
        val actual = FullPerformanceDataset.snapshot(app.controller.database,
            NeutralBackupService(app, app.controller.database))
        check(actual.sha256 == owner.getString("dataset_sha256")) { "cold_start_dataset_changed" }
        check(app.controller.preferences.getString("selected", null) == "p-c-0")
    }
    fun testPreparePersistentColdStartDataset() {
        val app = target(); val owner = token(); awaitReady(app.controller)
        check(!directory(app).exists()) { "existing_cold_start_owner_retained" }
        check(app.controller.database.conversations().isEmpty()) { "existing_synthetic_data_retained" }
        check(!app.controller.preferences.contains("selected")) { "existing_synthetic_selection_retained" }
        check(directory(app).mkdir())
        val record = JSONObject().put("schema", "daiyu.synthetic.cold-start.owner.v1")
            .put("owner_token", owner).put("application_id", app.packageName)
            .put("database_path", app.getDatabasePath("conversations.db").canonicalPath)
            .put("state", "preparing").put("production_delivery", false)
        saveOwner(app, record)
        val setup = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            ui.waitForIdle()
            val backup = NeutralBackupService(app, app.controller.database)
            val selected = FullPerformanceDataset.seed(app.controller.database, backup)
            val output = File(checkNotNull(app.getExternalFilesDir("synthetic-performance")), "cold-start-$owner")
            check(output.mkdir())
            val migration = FullPerformanceDataset.buildPackage(app.controller.database, backup, File(output, "migration.package.json"))
            val snapshot = FullPerformanceDataset.snapshot(app.controller.database, backup)
            check(selected.id == ConversationId("p-c-0"))
            check(app.controller.preferences.edit().putString("selected", selected.id.value).commit())
            File(output, "dataset.json").writeText(snapshot.manifest)
            record.put("state", "prepared").put("dataset_sha256", snapshot.sha256)
                .put("migration_package_sha256", migration.sha256).put("prepared_pid", android.os.Process.myPid())
                .put("prepared_process_start_ns", android.os.Process.getStartElapsedRealtime() * 1_000_000L)
            saveOwner(app, record)
            verify(app, record)
            instrumentation.sendStatus(0, Bundle().apply { putString("cold_start_owner", marker(app).absolutePath) })
        } finally { main { setup.finish() } }
    }
    fun testVerifyPersistentColdStartDataset() {
        val app = target(); val owner = readOwner(app); verify(app, owner)
        instrumentation.sendStatus(0, Bundle().apply { putString("cold_start_dataset_retained", "true") })
    }
    private fun nextElapsedDraw(activity: MainActivity): Long {
        val latch = CountDownLatch(1); val timestamp = AtomicLong()
        val view = activity.window.decorView
        val listener = ViewTreeObserver.OnDrawListener {
            if (timestamp.compareAndSet(0, SystemClock.elapsedRealtimeNanos())) latch.countDown()
        }
        main { check(view.isAttachedToWindow); view.viewTreeObserver.addOnDrawListener(listener); view.postInvalidateOnAnimation() }
        try { check(latch.await(5, TimeUnit.SECONDS)) { "cold_start_draw_missing" }; return timestamp.get() }
        finally { main { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) } }
    }
    fun testCollectOneColdStart() {
        val testEnter = SystemClock.elapsedRealtimeNanos()
        val app = target(); val args = InstrumentationRegistry.getArguments()
        val start = checkNotNull(args.getString("daiyuColdStartElapsedNs")).toLong()
        check(start > 0 && start <= testEnter && testEnter - start < TimeUnit.SECONDS.toNanos(30))
        val round = checkNotNull(args.getString("daiyuColdStartRound")).toInt().also { check(it in 0..19) }
        val owner = readOwner(app)
        val output = File(checkNotNull(app.getExternalFilesDir("synthetic-performance")), "cold-start-${token()}/sample-$round.json")
        check(!output.exists()) { "existing_cold_start_sample_retained" }
        val previous = owner.optLong("last_sample_process_start_ns", -1)
        val processStart = android.os.Process.getStartElapsedRealtime() * 1_000_000L
        check(processStart != owner.getLong("prepared_process_start_ns")) { "prepare_process_not_cold" }
        check(processStart >= start - 10_000_000L && processStart != previous) { "fresh_process_required" }
        var activity: MainActivity? = null
        val sample = JSONObject().put("schema", "daiyu.synthetic.cold-start.sample.v1")
            .put("owner_token", token()).put("round", round).put("pid", android.os.Process.myPid())
            .put("process_start_elapsed_ns", processStart).put("start_elapsed_ns", start)
            .put("test_enter_elapsed_ns", testEnter).put("clock", "elapsed_realtime_boottime")
            .put("dataset_sha256", owner.getString("dataset_sha256"))
            .put("includes_instrumentation_startup", true).put("latency_kind", "instrumented_upper_bound")
            .put("present_latency", false).put("hardware_input_latency", false)
            .put("production_delivery", false).put("business_acceptance", false)
        var completed = false
        var primary: Throwable? = null
        try {
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            awaitReady(app.controller)
            ui.waitUntil(5_000) {
                try {
                    ui.onNodeWithTag("chat-draft").assertIsDisplayed().assertIsEnabled().assert(hasSetTextAction())
                    ui.onNodeWithTag("chat-messages").assertIsDisplayed()
                    ui.onNodeWithTag("chat-turn:p-a-1003").assertIsDisplayed()
                    true
                } catch (_: AssertionError) { false }
            }
            val inputReady = SystemClock.elapsedRealtimeNanos()
            val draw = nextElapsedDraw(checkNotNull(activity))
            sample.put("input_available_verified_ns", inputReady).put("next_draw_ns", draw)
                .put("input_ready_upper_bound_ns", inputReady - start).put("next_draw_upper_bound_ns", draw - start)
            // Full-data verification is after the timed endpoint, never a cold-start warm-up.
            verify(app, owner)
            owner.put("last_sample_process_start_ns", processStart)
            saveOwner(app, owner)
            completed = true
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            var destroyed = activity == null
            try {
                activity?.let { current ->
                    main { current.finish() }
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (!main { current.isDestroyed }) {
                        check(SystemClock.elapsedRealtime() < deadline) { "cold_start_activity_retained" }; Thread.sleep(10)
                    }
                    destroyed = true
                }
            } catch (cleanup: Throwable) {
                val first = primary
                if (first == null) { primary = cleanup; throw cleanup } else first.addSuppressed(cleanup)
            } finally {
                sample.put("collection_completed", completed && destroyed && primary == null)
                    .put("timed_endpoint_and_dataset_verified", completed).put("activity_destroyed", destroyed)
                    .put("dataset_deletion_attempted", false).put("dataset_integrity_verified", completed)
                    .put("cleanup_succeeded", destroyed)
                    .put("failure_class", primary?.javaClass?.name ?: JSONObject.NULL)
                try {
                    check(!output.exists()) { "existing_cold_start_sample_retained" }
                    publishSample(output, sample)
                    instrumentation.sendStatus(0, Bundle().apply { putString("cold_start_sample", output.absolutePath) })
                } catch (reportFailure: Throwable) {
                    val first = primary
                    if (first == null) throw reportFailure else first.addSuppressed(reportFailure)
                }
            }
        }
    }
}
