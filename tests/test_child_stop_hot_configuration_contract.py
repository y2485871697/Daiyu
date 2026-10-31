"""Source wiring checks only; concurrency and UI methods await the approved runtime batch."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
DOMAIN = ROOT / "core-domain/src/main/kotlin/app/daiyu/android/domain/delegation"
class ChildStopHotConfigurationContractTest(unittest.TestCase):
    def test_three_choices_reuse_model_sheet_and_dismiss_preserves_pause(self):
        text = (APP / "MainActivity.kt").read_text().split("@Composable private fun ChildStopChoiceSurface()",1)[1].split("@Composable private fun UnknownBranchSurface()",1)[0]
        self.assertIn("OptionSelectionSheet(",text)
        self.assertNotIn("DaiyuAlertDialog(",text)
        self.assertLess(text.index("继续子代理"), text.index("暂停子代理"))
        self.assertLess(text.index("暂停子代理"), text.index("停止子代理"))
        self.assertIn("onDismiss = { finish(app.daiyu.android.domain.delegation.ChildTaskStopDisposition.PAUSE) }",text)
    def test_stop_choice_never_relooks_up_active_group(self):
        text = (APP / "ChatController.kt").read_text().split("internal fun finishChildStopChoice(",1)[1].split("private fun capturePausedChildren",1)[0]
        self.assertIn("childStopChoices.remove(choice)",text)
        for body in ["capture.tasks.resumeGroup(capture.group)","capture.tasks.finishGroup(capture.group, false)","capture.tasks.finishGroup(capture.group, true)","plan.finishPause(epoch, disposition)"]:
            self.assertIn(body,text)
    def test_fresh_dispatch_and_original_task_definitions_are_separate(self):
        text = (DOMAIN / "RetainedChildTasks.kt").read_text()
        self.assertIn("fun configure(binding: ChildTaskBinding",text)
        self.assertIn("val definition = task.definition",text)
        self.assertIn("definition = resources.models.getValue(inputTask.profileId)",text)
        self.assertIn("resources.poolKeys",text)
        tool = (APP / "integration/tools/RunChildTasks.kt").read_text()
        self.assertIn("supervisor.configure(binding, it())",tool)
        self.assertIn("currentProfiles?.invoke() ?: profiles",tool)
    def test_hot_request_claim_rechecks_selection_and_shares_actual_pool(self):
        text = (DOMAIN / "ChildModelBatch.kt").read_text()
        self.assertIn("selectedModel = awaitSelection()",text)
        self.assertIn("val selected = definition.current()",text)
        self.assertIn("sharedPool?.invoke(selected)",text)
        self.assertIn("selectedModel.sameSelection(definition.current())",text)
        selected = text.split("store?.selected(record, selectedModel.display)",1)[1]
        self.assertLess(selected.index("selectedModel.sameSelection(definition.current())"), selected.index("val transport = port.open"))
        self.assertIn("else if (acquired) checkNotNull(permit).release()",text)
        self.assertIn("acquired = false; lease = null; permit = null",text)
        self.assertIn("if (!workerFinished || !cleanupFinished || !toolsDrained || itemReleased) false",text)
        self.assertIn("if (assembly != null) awaitRequestBoundary()",text)
        self.assertIn("(transport as ResumableModelSession).resume(attemptSink)",text)
    def test_non_destructive_edits_hot_apply_and_destructive_edits_revoke(self):
        text = (APP / "delegation/SubAgentHotUpdatePolicy.kt").read_text()
        self.assertIn("profiles.all",text); self.assertIn("it.id == previous.id",text)
        self.assertIn("?.role == previous.role",text)
        binding = (APP / "delegation/SavedSubAgentRuntimeBindings.kt").read_text()
        self.assertIn("frozen.parallelLimit(binding.profile.id, model)",binding)
        self.assertIn("frozen.poolKey(owner, binding.profile.id)",binding)
        self.assertIn("profileCurrent(binding.profile.id)",binding)
        self.assertIn("val selected = latestModel(binding.profile.id)",binding)
        self.assertIn('check(latestConfig() == next) { "child_model_configuration_changed" }',binding)
    def test_configuration_events_keep_destructive_epoch_and_independent_control_lane(self):
        prefs = (APP / "delegation/ConversationSubAgentPreferences.kt").read_text()
        transaction = prefs.split("private fun transaction(", 1)[1].split("fun isConfigurationResetComplete", 1)[0]
        self.assertLess(transaction.index("check(edit.commit())"), transaction.index("state.lifetimes[identity]"))
        self.assertIn("?.role != oldProfile.role", transaction)
        controller = (APP / "ChatController.kt").read_text()
        observer = controller.split("private val childConfigurationObserver", 1)[1].split("private fun conversationExists", 1)[0]
        self.assertIn("childConfigurationWorker.execute", observer)
        self.assertIn("supervisor.reconcile(owner.value)", observer)
        self.assertIn("batch.configurationChanged", observer)
        self.assertIn("profileCurrent = { id -> childPreferences.profileLifetime", controller)
        retained = (DOMAIN / "RetainedChildTasks.kt").read_text()
        reconcile = retained.split("fun reconcile(", 1)[1].split("fun command(", 1)[0]
        self.assertIn("bindings.values.filter", reconcile)
        self.assertIn("it.definition.isValid()", reconcile)
        self.assertIn("stop(it.binding, it.initial.owner)", reconcile)

    def test_history_access_is_registered_independently_of_new_configuration(self):
        text = (APP / "integration/tools/RunChildTaskAccess.kt").read_text()
        for name in ["inspect_child_tasks", "get_child_task", "control_child_task"]: self.assertIn(name,text)
        self.assertIn("context.owner.conversationId.value != conversationId",text)
        self.assertIn("row.revision != revision",text)
        self.assertIn('put("new_request", false)',text)
        self.assertIn("next_text_offset",text)
        self.assertNotIn("enqueue(",text)
        catalog = (APP / "integration/tools/RunCatalogTools.kt").read_text()
        self.assertIn("childTaskAccess?.tools()?.forEach { registry.register(it) }",catalog)
    def test_runtime_store_forwards_actual_selection_through_owner_guard(self):
        text = (APP / "storage/SQLiteRuntimeStore.kt").read_text()
        wrapper = text.split("internal val childTasks: ChildTaskStore = object : ChildTaskStore", 1)[1].split("@Synchronized private fun <T> childAccess", 1)[0]
        self.assertIn("override fun selected(task: ChildTaskRecord, display: ChildTaskDisplay?)", wrapper)
        self.assertIn("childAccess { childStore.selected(task, display) }", wrapper)

    def test_browser_pause_never_cancels_or_replays_tools(self):
        text = (DOMAIN / "ToolEnabledChildRun.kt").read_text()
        pause = text.split("fun pause(): ChildTaskControlReceipt",1)[1].split("fun resume()",1)[0]
        self.assertIn("pauseControl.pause()",pause); self.assertNotIn("runner?.pause()",pause)
        self.assertIn("check(pauseControl.awaitBoundary() && !stopped() && !cancelled())",text)
        self.assertIn("pauseControl.terminate()",text)
        stop = (APP / "ChatController.kt").read_text().split("internal fun stopMain(",1)[1].split("// True acknowledges",1)[0]
        self.assertIn("plan.capturePause()",stop); self.assertNotIn("it.close()",stop)

    def test_single_profile_revision_check_does_not_read_added_profiles(self):
        text = (APP / "ChatController.kt").read_text().split("private fun freezeSavedChildModels",1)[1].split("internal fun savedSubAgentProviders",1)[0]
        self.assertLess(text.index("val selection ="), text.index("val catalogRevisions ="))
        self.assertIn("val catalogRevisions = selection.profiles",text)
    def test_transferred_browser_preparation_cannot_stop_owned_child(self):
        text = (APP / "ControllerBrowserPreparation.kt").read_text().split("fun cancel()",1)[1].split("fun retryCleanup",1)[0]
        self.assertLess(text.index("phase == State.TRANSFERRED"), text.index("child?.close()"))

    def test_new_dispatch_and_continue_share_hot_update_without_stale_fallback(self):
        policy = (APP / "delegation/SubAgentHotUpdatePolicy.kt").read_text()
        self.assertIn("?.role == previous.role", policy)
        bindings = (APP / "delegation/SavedSubAgentRuntimeBindings.kt").read_text()
        self.assertIn("fun forNewRequests()", bindings)
        self.assertIn("val selected = latestModels()", bindings)
        self.assertNotIn("else prepared", bindings)
        self.assertIn('check(latestConfig() == next)', bindings)
        controller = (APP / "ChatController.kt").read_text()
        callbacks = controller.split("val childPreparation = if (!withChildren)", 1)[1].split("val interactiveSnapshot =", 1)[0]
        self.assertIn("childPreparation?.forNewRequests() ?: emptyMap()", callbacks)
        self.assertIn("currentChildProfiles: () -> Set<String> = { currentChildModels().keys }", callbacks)
        choice = controller.split("internal fun finishChildStopChoice(", 1)[1].split("private fun capturePausedChildren", 1)[0]
        self.assertIn("capture.tasks.resumeGroup(capture.group)", choice)
        self.assertNotIn("configure(", choice)
        self.assertNotIn("hotUpdates = false", choice)
