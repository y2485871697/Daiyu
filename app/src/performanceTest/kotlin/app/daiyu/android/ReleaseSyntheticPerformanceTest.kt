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
import app.daiyu.android.storage.ModelSettings
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

    private fun awaitState(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!main(predicate)) {
            check(System.nanoTime() < deadline) { label }; Thread.sleep(10)
        }
    }

    private fun collectControlledWorkloads(activity: MainActivity, controller: ChatController,
        database: ConversationDatabase, model: ControlledPerformanceModel,
        captured: SyntheticFrameCollector, records: MutableList<JSONObject>, verifyRefresh: () -> Unit) {
        fun start(label: String): Pair<ControlledPerformanceModel.Session, Message> {
            val session = model.enqueue()
            ui.onNodeWithTag("chat-draft").performTextReplacement(label)
            ui.onNodeWithTag("chat-send").performClick()
            check(session.entered.await(5, TimeUnit.SECONDS)) { "synthetic_session_not_entered" }
            awaitState("synthetic_running_owner_missing") { controller.active?.status == RunStatus.RUNNING }
            ui.onNodeWithTag("chat-control").assertIsDisplayed()
            return session to main { checkNotNull(controller.active) }
        }
        fun stop(session: ControlledPerformanceModel.Session, owner: Message) {
            main { check(controller.stop(owner)) { "synthetic_stop_owner_rejected" } }
            check(session.drained.await(5, TimeUnit.SECONDS)) { "synthetic_session_drain_missing" }
            awaitState("synthetic_stop_not_idle") { controller.active == null }
            check(database.messages(owner.conversationId).last().second.let {
                it.id == owner.id && it.runId == owner.runId && it.status == RunStatus.STOPPED
            }) { "synthetic_stop_not_persisted" }
        }
        val (stream, streamOwner) = start("synthetic streaming")
        verifyRefresh()
        captured.scenario("synthetic_streaming")
        val streamStart = System.nanoTime()
        val initialEvents = stream.emitted.get()
        var streamCompleted = false
        try {
            val deadline = streamStart + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                check(stream.drained.count != 0L) { "synthetic_stream_ended_early" }
                ui.waitForIdle()
                Thread.sleep(20)
            }
            check(stream.emitted.get() > initialEvents) { "synthetic_stream_did_not_grow" }
            check(main { controller.active?.id == streamOwner.id })
            streamCompleted = true
        } finally {
            records.add(JSONObject().put("type", "scenario_window").put("scenario", "synthetic_streaming")
                .put("start_ns", streamStart).put("end_ns", System.nanoTime())
                .put("completed", streamCompleted).put("emitted_deltas", stream.emitted.get() - initialEvents)
                .put("delta_interval_ms", ControlledPerformanceModel.INTERVAL_MS)
                .put("transport_kind", "controlled_synthetic_model_session").put("real_provider_transport", false))
            stop(stream, streamOwner)
            verifyRefresh()
        }

        ui.onNodeWithTag("chat-draft").performTextReplacement("")
        ui.onNodeWithTag("chat-draft").performClick()
        ui.onNodeWithTag("chat-draft").assertIsFocused()
        val connection = main {
            val editor = checkNotNull(activity.window.decorView.findFocus())
            check(editor.onCheckIsTextEditor()) { "synthetic_focused_view_not_editor" }
            checkNotNull(editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())) {
                "synthetic_editor_connection_missing"
            }
        }
        verifyRefresh()
        captured.scenario("synthetic_input_connection")
        val inputStart = System.nanoTime()
        var commits = 0; var inputCompleted = false
        try {
            val deadline = inputStart + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                main { check(connection.commitText("字", 1)) { "synthetic_commit_rejected" } }
                commits++; ui.waitForIdle(); Thread.sleep(50)
            }
            val expected = "字".repeat(commits)
            ui.waitUntil(5_000) { ui.onNodeWithTag("chat-draft").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.EditableText)?.text == expected }
            inputCompleted = true
        } finally {
            records.add(JSONObject().put("type", "scenario_window").put("scenario", "synthetic_input_connection")
                .put("start_ns", inputStart).put("end_ns", System.nanoTime()).put("commits", commits)
                .put("commit_interval_ms", 50).put("completed", inputCompleted)
                .put("method", "direct_editor_input_connection_commitText").put("semantics_injection", false)
                .put("hardware_input_latency", false).put("ime_service_round_trip_measured", false))
            main { connection.closeConnection() }
            verifyRefresh()
        }
        ui.onNodeWithTag("chat-draft").performTextReplacement("")
        verifyRefresh()
        captured.scenario("synthetic_stop_upper_bound")
        repeat(20) { round ->
            val (session, owner) = start("synthetic stop $round")
            var requestNs = 0L
            main {
                check(controller.active?.id == owner.id)
                requestNs = System.nanoTime()
                check(controller.stop(owner)) { "synthetic_stop_owner_rejected" }
            }
            check(session.drained.await(5, TimeUnit.SECONDS)) { "synthetic_session_drain_missing" }
            awaitState("synthetic_stop_not_idle") { controller.active == null }
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-control").fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithTag("chat-send").assertIsDisplayed()
            ui.onNodeWithTag("chat-draft").assertIsEnabled()
            val verifiedNs = System.nanoTime()
            val drawNs = nextDrawNs(activity)
            val saved = database.messages(owner.conversationId).last().second
            check(saved.id == owner.id && saved.runId == owner.runId && saved.status == RunStatus.STOPPED)
            records.add(JSONObject().put("type", "stop_sample").put("round", round)
                .put("owner_message_id", owner.id.value).put("owner_run_id", owner.runId.value)
                .put("method", "controller_stop_request_to_verified_idle_next_draw")
                .put("latency_kind", "instrumented_upper_bound").put("start_ns", requestNs)
                .put("semantics_verified_ns", verifiedNs).put("next_draw_ns", drawNs)
                .put("upper_bound_ns", drawNs - requestNs).put("present_latency", false)
                .put("first_visible_feedback_measured", false).put("long_press_gesture_measured", false))
        }
        check(records.count { it.optString("type") == "stop_sample" } == 20)
    }

    private fun collectLongConversationEntries(activity: MainActivity, database: ConversationDatabase,
        records: MutableList<JSONObject>) {
        val conversations = database.conversations()
        val short = conversations.single { it.id == ConversationId("p-c-1") }
        val long = conversations.single { it.id == ConversationId("p-c-0") }
        val tail = "chat-turn:p-a-1003"
        repeat(20) { round ->
            main { activity.select(short) }
            ui.waitForIdle()
            ui.onNodeWithTag(tail).assertDoesNotExist()
            val start = System.nanoTime()
            main { activity.select(long) }
            val copy = ui.onNode(hasContentDescription("复制回答") and hasAnyAncestor(hasTestTag(tail)))
            ui.waitUntil(5_000) {
                try {
                    ui.onNodeWithTag(tail).assertIsDisplayed()
                    copy.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
                    ui.onNodeWithTag("chat-draft").assertIsDisplayed().assertIsEnabled().assert(hasSetTextAction())
                    true
                } catch (_: AssertionError) { false }
            }
            val verified = System.nanoTime()
            val draw = nextDrawNs(activity)
            records.add(JSONObject().put("type", "long_entry_sample").put("round", round)
                .put("entry_kind", "warm_reentry").put("prewarmed_long_selection_count", 1)
                .put("conversation_id", long.id.value).put("tail_message_id", "p-a-1003")
                .put("method", "select_to_visible_tail_action_and_enabled_editor_next_draw")
                .put("latency_kind", "instrumented_upper_bound").put("start_ns", start)
                .put("tail_operable_verified_ns", verified).put("next_draw_ns", draw)
                .put("tail_action_execution_verified", false).put("navigation_gesture_measured", false)
                .put("upper_bound_ns", draw - start).put("present_latency", false)
                .put("first_visible_feedback_measured", false))
        }
    }

    private fun collectSystemImeWindow(activity: MainActivity, expectedIme: String,
        captured: SyntheticFrameCollector, records: MutableList<JSONObject>) {
        val target = instrumentation.targetContext
        fun currentIme() = android.provider.Settings.Secure.getString(target.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        check(currentIme() == expectedIme) { "system_ime_identity_mismatch" }
        ui.onNodeWithTag("chat-draft").performTextReplacement("")
        ui.onNodeWithTag("chat-draft").performClick()
        ui.onNodeWithTag("chat-draft").assertIsFocused()
        main {
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        val shownDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!main { androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }) {
            check(System.nanoTime() < shownDeadline) { "system_ime_not_visible" }; Thread.sleep(50)
        }
        captured.scenario("system_ime_user_input")
        val start = System.nanoTime()
        var changes = 0; var previous = ""; var previousSelection: String? = null
        val observations = org.json.JSONArray()
        var completed = false
        var identityChanged = false
        var observedIme: String? = expectedIme
        var primaryFailure: Throwable? = null
        try {
            // Input must come through the selected keyboard, never a semantics or direct-connection fallback.
            android.util.Log.i("DaiyuPerformance", "IME workload manual-nihao-v1: repeat typing nihao and selecting 你好 for 35 seconds; do not send.")
            while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(35)) {
                observedIme = currentIme()
                identityChanged = identityChanged || observedIme != expectedIme
                check(!identityChanged) { "system_ime_changed_during_collection" }
                check(main { androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }) { "system_ime_hidden" }
                val node = ui.onNodeWithTag("chat-draft").fetchSemanticsNode()
                val text = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
                val selection = node.config.getOrNull(SemanticsProperties.TextSelectionRange)?.toString()
                check(text.length <= 4096) { "system_ime_text_limit" }
                if (text != previous || selection != previousSelection) {
                    if (text != previous) changes++
                    observations.put(JSONObject().put("observed_ns", System.nanoTime())
                        .put("text_utf16_length", text.length).put("selection", selection ?: JSONObject.NULL)
                        .put("text_changed", text != previous))
                    previous = text; previousSelection = selection
                }
                Thread.sleep(50)
            }
            check(changes >= 20 && previous.isNotBlank()) { "system_ime_manual_input_missing" }
            observedIme = currentIme()
            identityChanged = identityChanged || observedIme != expectedIme
            check(!identityChanged) { "system_ime_changed_during_collection" }
            completed = true
        } catch (failure: Throwable) {
            primaryFailure = failure; throw failure
        } finally {
            android.util.Log.i("DaiyuPerformance", "IME manual-nihao-v1 window ended; stop typing.")
            records.add(JSONObject().put("type", "scenario_window").put("scenario", "system_ime_user_input")
                .put("start_ns", start).put("end_ns", System.nanoTime()).put("completed", completed)
                .put("method", "selected_system_ime_user_input_to_observed_editor_changes")
                .put("input_source", "user_via_selected_system_ime").put("ime_id", expectedIme)
                .put("last_observed_ime_id", observedIme ?: JSONObject.NULL).put("workload_id", "manual-nihao-v1")
                .put("text_changes", changes).put("observations", observations)
                .put("poll_interval_ms", 50).put("polling_overhead_included", true)
                .put("target_window_only", true).put("hardware_input_latency", false)
                .put("ime_commit_latency", false).put("first_visible_feedback_measured", false)
                .put("present_latency", false).put("system_ime_change_observed", identityChanged))
            val cleanup = runCatching {
                main {
                    androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                        .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                ui.onNodeWithTag("chat-draft").performTextReplacement("")
            }.exceptionOrNull()
            if (cleanup != null) primaryFailure?.addSuppressed(cleanup) ?: throw cleanup
        }
        val hiddenDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (main { androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }) {
            check(System.nanoTime() < hiddenDeadline) { "system_ime_cleanup_not_hidden" }; Thread.sleep(50)
        }
    }

    private fun collect(fullScale: Boolean) {
        val target = instrumentation.targetContext
        check(target.packageName == "app.daiyu.android.synthetic") { "non_synthetic_target_refused" }
        check(instrumentation.context.packageName == "${target.packageName}.test")
        check(Build.VERSION.SDK_INT >= 29)
        check(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        check(target.applicationInfo.isProfileableByShell)
        val systemImeOption = InstrumentationRegistry.getArguments().getString("daiyuSystemIme") ?: "false"
        require(systemImeOption in setOf("false", "true")) { "invalid_system_ime_opt_in" }
        val systemImeEnabled = systemImeOption == "true"
        require(!systemImeEnabled || fullScale) { "system_ime_requires_full_scale" }
        val expectedIme = if (systemImeEnabled) {
            checkNotNull(InstrumentationRegistry.getArguments().getString("daiyuExpectedIme"))
                .also { require(it.isNotBlank() && it.length <= 256 && it.contains('/')) { "expected_system_ime_required" } }
        } else null
        val diagnosticsMode = InstrumentationRegistry.getArguments().getString("daiyuDiagnostics") ?: "off"
        require(diagnosticsMode in setOf("off", "on")) { "invalid_diagnostics_mode" }
        val requestedRefreshRate = when (InstrumentationRegistry.getArguments().getString("daiyuRefreshRate")) {
            null -> null
            "60" -> 60
            "120" -> 120
            else -> error("invalid_synthetic_refresh_rate")
        }
        val application = target.applicationContext as ChatApplication
        val original = main { application.controller }
        val id = UUID.randomUUID().toString()
        val output = File(checkNotNull(target.getExternalFilesDir("synthetic-performance")), "raw-$id.jsonl")
        var fixture: PerformanceFixture? = null
        var activity: MainActivity? = null; var collector: SyntheticFrameCollector? = null
        var setupActivity: MainActivity? = null
        var refreshOverride: SyntheticRefreshRateOverride? = null
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
        var diagnosticsStateVerified = false
        var memoryCollector: SyntheticMemoryCollector? = null
        var memoryReport: File? = null
        var migrationReport: File? = null
        var migrationPackageFile: File? = null
        var scrollStart = 0L; var scrollEnd = 0L; var refreshRate = 0f; var preferredRate = 0f
        val inputs = ArrayList<JSONObject>()
        val controlledModel = if (fullScale) ControlledPerformanceModel() else null
        var dataset: FullPerformanceDataset.Snapshot? = null
        var migration: FullPerformanceDataset.Package? = null
        try {
            // Keep an actual resumed Activity throughout unmeasured dataset setup. A background
            // instrumentation process may otherwise be frozen before it can launch the measured UI.
            setupActivity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            ui.waitForIdle()
            awaitReady(original)
            val activeFixture = PerformanceFixture(target, id, diagnosticsEnabled = diagnosticsMode == "on", controlledModel = controlledModel, onConstructionCleanupFailure = { cleanupFailed = true }).also { fixture = it }
            stage = "dataset_setup"
            awaitReady(activeFixture.controller)
            val diagnosticState = checkNotNull(activeFixture.controller.diagnostics).load()
            check(diagnosticState.loaded && !diagnosticState.unavailable && diagnosticState.enabled == (diagnosticsMode == "on"))
            diagnosticsStateVerified = true
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
            requestedRefreshRate?.let { rate ->
                val override = SyntheticRefreshRateOverride(instrumentation, launched.window, rate)
                refreshOverride = override
                override.apply(); ui.waitForIdle(); override.verify()
            }
            main { refreshRate = launched.window.decorView.display.refreshRate; preferredRate = launched.window.attributes.preferredRefreshRate }
            val captured = SyntheticFrameCollector(instrumentation, launched.window); collector = captured
            if (fullScale) {
                stage = "long_conversation_entry"
                captured.scenario("synthetic_long_entry")
                collectLongConversationEntries(launched, activeFixture.database, inputs)
            }
            refreshOverride?.verify()
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
            refreshOverride?.verify()
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
            if (fullScale) {
                refreshOverride?.verify()
                stage = "streaming_input_connection_stop"
                collectControlledWorkloads(launched, activeFixture.controller, activeFixture.database,
                    checkNotNull(controlledModel), captured, inputs, verifyRefresh = { refreshOverride?.verify() })
            }
            if (systemImeEnabled) {
                stage = "system_ime_user_input"
                refreshOverride?.verify()
                collectSystemImeWindow(launched, checkNotNull(expectedIme), captured, inputs)
                refreshOverride?.verify()
            }
            captured.close()
            refreshOverride?.verify()
            refreshOverride?.close()
            inputs.filter { it.optString("type") == "scenario_window" }.forEach { window ->
                val count = captured.framesBetween(window.getLong("start_ns"), window.getLong("end_ns"))
                window.put("frame_count_by_intended_vsync", count)
                check(count > 0) { "synthetic_scenario_frames_missing" }
            }
            assertTrue("raw frames missing", captured.frameCount > 0)
            assertEquals(0L, captured.overflow); assertEquals(0L, captured.droppedReports); assertEquals(20, inputs.count { it.optString("type") == "input_sample" })
            if (fullScale) {
                stage = "conversation_switch_memory"
                val candidates = activeFixture.database.conversations().take(100)
                check(candidates.size == 100)
                val memory = SyntheticMemoryCollector().also { memoryCollector = it }
                memory.sample("before_switches", 0)
                repeat(100) { index ->
                    val selected = candidates[index]
                    val start = System.nanoTime()
                    main { launched.select(selected) }
                    ui.waitForIdle()
                    check(main { activeFixture.controller.preferences.getString("selected", null) } == selected.id.value)
                    memory.sample("switch_completed", index + 1, start)
                }
                // Take ten spaced post-switch observations; requesting GC does not prove stable recovery.
                System.gc(); memory.markGcRequested(); System.runFinalization()
                repeat(10) { second -> Thread.sleep(1_000); memory.sample("post_gc_observation", second + 1) }
                File(output.parentFile, "memory-$id.json").also { report ->
                    memory.write(report, id, diagnosticsMode); memoryReport = report
                }
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
            cleanup { fixture?.stopController() }
            cleanup { controlledModel?.close() }
            cleanup { collector?.close() }
            cleanup { refreshOverride?.close() }
            cleanup {
                if (memoryReport == null) memoryCollector?.let { memory ->
                    File(output.parentFile, "memory-$id.json").also { report ->
                        memory.write(report, id, diagnosticsMode, completed = false, failureStage = stage)
                        memoryReport = report
                    }
                }
            }
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
                        .put("refresh_control", if (requestedRefreshRate == null) "production_default" else "test_window_override")
                        .put("requested_refresh_rate_hz", requestedRefreshRate ?: JSONObject.NULL)
                        .put("refresh_rate_verified", refreshOverride?.verified() == true)
                        .put("refresh_rate_observations", refreshOverride?.observations() ?: org.json.JSONArray())
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
                        .put("diagnostics_mode", diagnosticsMode)
                        .put("diagnostics_state_verified", diagnosticsStateVerified)
                        .put("memory_report", memoryReport?.name ?: JSONObject.NULL)
                        .put("backend_migration_report", migrationReport?.name ?: JSONObject.NULL)
                        .put("failure_class", primaryFailure?.javaClass?.name ?: JSONObject.NULL), inputs)
                    frameReportWritten = true
                }
            }
            cleanup {
                val status = File(output.parentFile, "status-$id.json")
                status.writeText(JSONObject().put("schema", "daiyu.synthetic.performance.status.v1")
                    .put("collection_id", id).put("fixture_mode", if (fullScale) "full_scale" else "small_smoke")
                    .put("diagnostics_mode", diagnosticsMode)
                    .put("memory_report", memoryReport?.name ?: JSONObject.NULL)
                    .put("diagnostics_state_verified", diagnosticsStateVerified)
                    .put("memory_switch_samples", memoryCollector?.switchSamples ?: 0)
                    .put("memory_post_gc_samples", memoryCollector?.postGcSamples ?: 0)
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
    diagnosticsEnabled: Boolean = false,
    controlledModel: ControlledPerformanceModel? = null,
    private val onConstructionCleanupFailure: () -> Unit = {}) : AutoCloseable {
    private val prefix = "performance-$id-"
    private val keyAlias = "app.daiyu.android.synthetic.performance.$id"
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
                val modelSettings = ModelSettingsStore(application, keyAlias)
                if (controlledModel != null) modelSettings.save(
                    ModelSettings("https://example.invalid", "performance", "synthetic-key", false))
                val uiPreferences = application.getSharedPreferences("conversation-ui", 0)
                val diagnostics = app.daiyu.android.diagnostics.DiagnosticLogStore(
                    File(application.noBackupFilesDir, "diagnostics-v1/state.json"))
                diagnostics.setEnabled(diagnosticsEnabled)
                controllerConstructionStarted = true
                controller = ChatController(application, database, modelSettings, uiPreferences,
                    { controlledModel?.port ?: error("external_model_request_forbidden") }, taskSurfaceStore = surfaces, diagnostics = diagnostics)
            }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) {
                onConstructionCleanupFailure()
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun stopController() { if (::controller.isInitialized) controller.stop() }

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
        attempt {
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it == keyAlias || it.startsWith("$keyAlias.") }
                .forEach { keys.deleteEntry(it) }
        }
        attempt { check(!root.exists() || root.deleteRecursively()) { "synthetic_cache_cleanup_failed" } }
        failure?.let { throw it }
    }
}
