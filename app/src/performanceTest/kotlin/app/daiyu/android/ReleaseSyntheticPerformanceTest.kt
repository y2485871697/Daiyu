package app.daiyu.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.test.InstrumentationTestCase
import android.view.ViewTreeObserver
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.tasksurface.*
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Run only on a fresh disposable synthetic installation. This tests collection, not AC acceptance. */
@Suppress("DEPRECATION")
class ReleaseSyntheticPerformanceTest : InstrumentationTestCase() {
    private val ui = createEmptyComposeRule()
    private fun legacyRunBare() = super.runBare()
    override fun runBare() {
        ui.apply(object : Statement() { override fun evaluate() = legacyRunBare() },
            Description.createTestDescription(javaClass, name)).evaluate()
    }
    private fun <T> main(action: () -> T): T = syntheticMain(instrumentation, action)
    private fun awaitReady(controller: ChatController) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!main { controller.ready }) {
            check(System.nanoTime() < deadline) { "synthetic_controller_not_ready" }; Thread.sleep(10)
        }
        check(main { controller.problem == null }) { "synthetic_controller_recovery_failed" }
    }
    private fun nextDrawNs(activity: MainActivity): Long {
        val latch = CountDownLatch(1); val timestamp = AtomicLong()
        val view = activity.window.decorView
        val listener = ViewTreeObserver.OnDrawListener {
            if (timestamp.compareAndSet(0, System.nanoTime())) latch.countDown()
        }
        main { check(view.isAttachedToWindow); view.viewTreeObserver.addOnDrawListener(listener); view.postInvalidateOnAnimation() }
        try { check(latch.await(5, TimeUnit.SECONDS)) { "synthetic_draw_missing" }; return timestamp.get() }
        finally { main { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) } }
    }
    fun testCollectRawFramesAndSyntheticInputUpperBounds() = collect(fullScale = false)

    fun testCollectFullScaleScrollInputAndBackendMigration() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("daiyuFullPerformance") == "true") { "explicit_full_performance_opt_in_required" }
        collect(fullScale = true)
    }

    private fun collect(fullScale: Boolean) {
        val target = instrumentation.targetContext
        check(target.packageName == "app.daiyu.android.synthetic") { "non_synthetic_target_refused" }
        check(instrumentation.context.packageName == "${target.packageName}.test")
        check(Build.VERSION.SDK_INT >= 29)
        check(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        check(target.applicationInfo.isProfileableByShell)
        val application = target.applicationContext as ChatApplication
        val original = main { application.controller }
        val id = UUID.randomUUID().toString()
        val output = File(checkNotNull(target.getExternalFilesDir("synthetic-performance")), "raw-$id.jsonl")
        var fixture: PerformanceFixture? = null
        var activity: MainActivity? = null; var collector: SyntheticFrameCollector? = null
        var setupActivity: MainActivity? = null
        fun finishActivity(closing: MainActivity) {
            main { closing.finish() }; instrumentation.waitForIdleSync()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!main { closing.isDestroyed }) {
                check(System.nanoTime() < deadline) { "synthetic_activity_not_destroyed" }; Thread.sleep(10)
            }
        }
        var injected = false; var complete = false; var primaryFailure: Throwable? = null
        var cleanupFailed = false
        var frameReportWritten = false
        var stage = "initialization"
        var migrationReport: File? = null
        var migrationPackageFile: File? = null
        var scrollStart = 0L; var scrollEnd = 0L; var refreshRate = 0f; var preferredRate = 0f
        val inputs = ArrayList<JSONObject>()
        var dataset: FullPerformanceDataset.Snapshot? = null
        var migration: FullPerformanceDataset.Package? = null
        try {
            // Keep an actual resumed Activity throughout unmeasured dataset setup. A background
            // instrumentation process may otherwise be frozen before it can launch the measured UI.
            setupActivity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            ui.waitForIdle()
            awaitReady(original)
            val activeFixture = PerformanceFixture(target, id, onConstructionCleanupFailure = { cleanupFailed = true }).also { fixture = it }
            stage = "dataset_setup"
            awaitReady(activeFixture.controller)
            val conversation = if (fullScale) {
                val backup = app.daiyu.android.backup.NeutralBackupService(activeFixture.context, activeFixture.database)
                val selected = FullPerformanceDataset.seed(activeFixture.database, backup)
                val packageFile = File(output.parentFile, "migration-$id.package.json")
                migrationPackageFile = packageFile
                migration = FullPerformanceDataset.buildPackage(activeFixture.database, backup, packageFile)
                dataset = FullPerformanceDataset.snapshot(activeFixture.database, backup)
                File(output.parentFile, "dataset-$id.json").writeText(checkNotNull(dataset).manifest)
                selected
            } else activeFixture.database.create("default")
            if (!fullScale) repeat(80) { index ->
                val run = RunId("synthetic-run-$index")
                activeFixture.database.send(
                    Message(MessageId("synthetic-user-$index"), conversation.id, run, Message.USER_ROLE,
                        listOf(MessageBlock.Text("u", "Synthetic prompt $index")), RunStatus.COMPLETED),
                    Message(MessageId("synthetic-answer-$index"), conversation.id, run, Message.ASSISTANT_ROLE,
                        listOf(MessageBlock.Text("a", "Synthetic response $index\n".repeat(8))), RunStatus.RUNNING))
                activeFixture.database.messages(conversation.id, limit = 1).last().second.let { answer ->
                    activeFixture.database.save(answer.copy(status = RunStatus.COMPLETED))
                }
            }
            stage = "activity_setup"
            finishActivity(checkNotNull(setupActivity))
            setupActivity = null
            main { application.controller = activeFixture.controller; injected = true }
            val launched = instrumentation.startActivitySync(Intent(target, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = launched; ui.waitForIdle(); main { launched.select(conversation) }; ui.waitForIdle()
            ui.onNodeWithTag("chat-draft").assertIsDisplayed().assertIsEnabled()
            ui.onNodeWithTag("chat-messages").assertIsDisplayed()
            main { refreshRate = launched.window.decorView.display.refreshRate; preferredRate = launched.window.attributes.preferredRefreshRate }
            val captured = SyntheticFrameCollector(instrumentation, launched.window); collector = captured
            stage = "scroll"
            captured.scenario("synthetic_scroll"); scrollStart = System.nanoTime()
            val deadline = scrollStart + TimeUnit.SECONDS.toNanos(30)
            var direction = false
            while (System.nanoTime() < deadline) {
                ui.onNodeWithTag("chat-messages").performTouchInput {
                    if (direction) swipeUp(durationMillis = 300) else swipeDown(durationMillis = 300)
                }
                direction = !direction
            }
            ui.waitForIdle(); scrollEnd = System.nanoTime(); captured.scenario("synthetic_semantics_input")
            stage = "semantics_input"
            repeat(20) { round ->
                val text = "SYNTHETIC-$round"; val start = System.nanoTime()
                ui.onNodeWithTag("chat-draft").performTextReplacement(text)
                ui.waitUntil(5_000) { ui.onNodeWithTag("chat-draft").fetchSemanticsNode()
                    .config.getOrNull(SemanticsProperties.EditableText)?.text == text }
                val verified = System.nanoTime(); val draw = nextDrawNs(launched)
                inputs.add(JSONObject().put("type", "input_sample").put("round", round)
                    .put("method", "semantics_text_replacement_to_verified_text_next_draw")
                    .put("latency_kind", "instrumented_upper_bound").put("hardware_input_latency", false)
                    .put("ime_commit_latency", false).put("present_latency", false)
                    .put("first_visible_feedback_measured", false)
                    .put("start_ns", start).put("semantics_verified_ns", verified).put("next_draw_ns", draw)
                    .put("upper_bound_ns", draw - start))
            }
            captured.close()
            assertTrue("raw frames missing", captured.frameCount > 0)
            assertEquals(0L, captured.overflow); assertEquals(0L, captured.droppedReports); assertEquals(20, inputs.size)
            if (fullScale) {
                stage = "backend_migration"
                val targetFixture = PerformanceFixture(target, "$id-migration", createController = false,
                    onConstructionCleanupFailure = { cleanupFailed = true })
                AutoCloseable {
                    try { targetFixture.close() }
                    catch (failure: Throwable) { cleanupFailed = true; throw failure }
                }.use {
                    val sample = FullPerformanceDataset.migrate(targetFixture.context, targetFixture.database,
                        checkNotNull(migration), checkNotNull(dataset)).put("collection_id", id)
                    File(output.parentFile, "migration-$id.json").also { report ->
                        report.writeText(sample.toString()); migrationReport = report
                    }
                }
            }
            stage = "completed"
            complete = true
        } catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally {
            val bodyFailed = primaryFailure != null
            fun cleanup(action: () -> Unit) {
                try { action() }
                catch (failure: Throwable) {
                    cleanupFailed = true
                    val first = primaryFailure
                    if (first == null) primaryFailure = failure else if (first !== failure) first.addSuppressed(failure)
                }
            }
            cleanup { collector?.close() }
            var activitiesDestroyed = false
            var controllerRestored = !injected
            var fixtureCleanupAttempted = false
            cleanup {
                activity?.let(::finishActivity)
                setupActivity?.let(::finishActivity)
                activitiesDestroyed = true
            }
            if (activitiesDestroyed) {
                cleanup {
                    if (injected) main { application.controller = original }
                    controllerRestored = true
                }
                if (controllerRestored) cleanup {
                    fixtureCleanupAttempted = fixture != null
                    fixture?.close()
                }
            }
            cleanup {
                collector?.let { captured ->
                    captured.write(output, JSONObject().put("collection_id", id)
                        .put("fixture_mode", if (fullScale) "full_scale" else "small_smoke")
                        .put("application_id", target.packageName)
                        .put("activity", MainActivity::class.java.name).put("debuggable", false).put("profileable", true)
                        .put("sdk", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                        .put("refresh_rate_hz_at_start", refreshRate).put("preferred_refresh_rate_hz_at_start", preferredRate)
                        .put("expected_fixture_messages", if (fullScale) 10000 else 160)
                        .put("expected_fixture_conversations", if (fullScale) 1000 else 1)
                        .put("fixture_matches_approved_dataset", false)
                        .put("full_scale_fixture_verified", dataset != null)
                        .put("dataset_sha256", dataset?.sha256 ?: JSONObject.NULL)
                        .put("migration_package_sha256", migration?.sha256 ?: JSONObject.NULL)
                        .put("migration_package_bytes", if (migration != null) FullPerformanceDataset.PACKAGE_BYTES else 0L)
                        .put("mb_definition", "decimal_1000000_bytes")
                        .put("scroll_start_ns", scrollStart).put("scroll_end_ns", scrollEnd)
                        .put("scroll_window_includes_driver_idle_time", true).put("collection_completed", complete)
                        .put("cleanup_succeeded", !cleanupFailed)
                        .put("activity_destruction_confirmed", activitiesDestroyed)
                        .put("controller_restoration_confirmed", controllerRestored)
                        .put("fixture_cleanup_attempted", fixtureCleanupAttempted)
                        .put("fixture_resources_retained", fixture != null && (!activitiesDestroyed || !controllerRestored))
                        .put("cleanup_scope", "fixture_controller_databases_preferences_cache")
                        .put("backend_migration_report", migrationReport?.name ?: JSONObject.NULL)
                        .put("failure_class", primaryFailure?.javaClass?.name ?: JSONObject.NULL), inputs)
                    frameReportWritten = true
                }
            }
            cleanup {
                val status = File(output.parentFile, "status-$id.json")
                status.writeText(JSONObject().put("schema", "daiyu.synthetic.performance.status.v1")
                    .put("collection_id", id).put("fixture_mode", if (fullScale) "full_scale" else "small_smoke")
                    .put("collection_completed", complete).put("failure_stage", if (primaryFailure == null) JSONObject.NULL else stage)
                    .put("failure_class", primaryFailure?.javaClass?.name ?: JSONObject.NULL)
                    .put("cleanup_succeeded", !cleanupFailed)
                        .put("activity_destruction_confirmed", activitiesDestroyed)
                        .put("controller_restoration_confirmed", controllerRestored)
                        .put("fixture_cleanup_attempted", fixtureCleanupAttempted)
                        .put("fixture_resources_retained", fixture != null && (!activitiesDestroyed || !controllerRestored))
                        .put("cleanup_scope", "fixture_controller_databases_preferences_cache").put("frame_report_written", frameReportWritten)
                    .put("frame_report", if (frameReportWritten) output.name else JSONObject.NULL)
                    .put("backend_migration_report", migrationReport?.name ?: JSONObject.NULL)
                    .put("dataset_verified", dataset != null).put("synthetic_data", true)
                    .put("retained_migration_package", migrationPackageFile?.name ?: JSONObject.NULL)
                    .put("retained_migration_package_bytes", migrationPackageFile?.length() ?: 0L)
                    .put("retained_migration_package_integrity_verified", migration != null)
                    .put("production_delivery", false).put("business_acceptance", false)
                    .put("collection_authenticated", false).toString())
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("synthetic_performance_status", status.absolutePath)
                    if (frameReportWritten) putString("synthetic_performance_report", output.absolutePath)
                    putString("business_acceptance", "false")
                })
            }
            if (!bodyFailed) primaryFailure?.let { throw it }
        }
    }
}

private class PerformanceFixture(private val base: Context, id: String, createController: Boolean = true,
    private val onConstructionCleanupFailure: () -> Unit = {}) : AutoCloseable {
    private val prefix = "performance-$id-"
    private var controllerConstructionStarted = false
    private val root = File(base.cacheDir, "performance-fixture-$id")
    private val databases = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private val preferences = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private val isolated = object : ContextWrapper(base) {
        private fun databaseName(value: String) = "$prefix$value".also { databases.add(it) }
        private fun preferenceName(value: String) = "$prefix$value".also { preferences.add(it) }
        override fun getFilesDir() = File(root, "files").apply { check(isDirectory || mkdirs()) }
        override fun getCacheDir() = File(root, "cache").apply { check(isDirectory || mkdirs()) }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { check(isDirectory || mkdirs()) }
        override fun getDatabasePath(name: String) = baseContext.getDatabasePath(databaseName(name))
        override fun deleteDatabase(name: String) = baseContext.deleteDatabase(databaseName(name))
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(databaseName(name), mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(databaseName(name), mode, factory, handler)
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(preferenceName(name), mode)
    }
    private class FixtureApplication(base: Context) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
    }
    lateinit var context: Context
        private set
    lateinit var database: ConversationDatabase
        private set
    lateinit var controller: ChatController
        private set

    init {
        try {
            check(root.mkdirs())
            val application = FixtureApplication(isolated)
            context = application
            database = ConversationDatabase(application)
            if (createController) {
                val surfaces = TaskSurfaceStore(object : TaskSurfacePersistence {
                    private var record: String? = null
                    @Synchronized override fun read() = record
                    @Synchronized override fun write(record: String) { this.record = record }
                }).apply { check(save(TaskSurfacePreference.FOREGROUND)) }
                val modelSettings = ModelSettingsStore(application, "app.daiyu.android.synthetic.performance.$id")
                val uiPreferences = application.getSharedPreferences("conversation-ui", 0)
                controllerConstructionStarted = true
                controller = ChatController(application, database, modelSettings, uiPreferences,
                    { error("external_model_request_forbidden") }, taskSurfaceStore = surfaces)
            }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) {
                onConstructionCleanupFailure()
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun close() {
        if (::controller.isInitialized) {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                "synthetic_fixture_close_requires_worker"
            }
            controller.close()
        } else {
            check(!controllerConstructionStarted) { "synthetic_controller_construction_drain_unconfirmed" }
        }
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (caught: Throwable) {
                val first = failure
                if (first == null) failure = caught else if (first !== caught) first.addSuppressed(caught)
            }
        }
        attempt { if (::database.isInitialized) database.close() }
        synchronized(databases) { databases.toList() }.forEach { name ->
            attempt {
                check(!base.getDatabasePath(name).exists() || base.deleteDatabase(name)) {
                    "synthetic_database_cleanup_failed"
                }
            }
        }
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            attempt { check(base.deleteSharedPreferences(name)) { "synthetic_preferences_cleanup_failed" } }
        }
        attempt { check(!root.exists() || root.deleteRecursively()) { "synthetic_cache_cleanup_failed" } }
        failure?.let { throw it }
    }
}
