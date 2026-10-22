"""Source-only checks; Android resume geometry remains in the approved device batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


def source(path):
    return (SRC / path).read_text()


class SubAgentResumeDisplayContractTest(unittest.TestCase):
    def test_pause_still_revokes_lookup_and_resume_reloads(self):
        activity = source("MainActivity.kt")
        pause = activity.split("override fun onPause()", 1)[1].split("override fun onStop()", 1)[0]
        self.assertIn("subAgentModelSource.clear()", pause)
        resume = activity.split("override fun onResume()", 1)[1].split("private fun applyPreferredRefreshRate", 1)[0]
        self.assertIn("subAgentSourceRevision++", resume)

    def test_display_is_retained_only_while_lookup_is_revoked(self):
        text = source("delegation/SavedCatalogSubAgentModelSource.kt")
        clear = text.split("fun clear()", 1)[1].split("suspend fun refreshAll", 1)[0]
        self.assertIn("generation++", clear)
        self.assertIn("refreshingState.value = true", clear)
        self.assertIn("state.value = emptyList()", clear)
        self.assertNotIn("displayed.value", clear)
        publish = text.split("private fun publish(", 1)[1].split("/** Called", 1)[0]
        self.assertLess(publish.index("if (request != generation) return"), publish.index("displayed.value = loaded"))
        self.assertIn("state.value = loaded", publish)
        self.assertIn("refreshingState.value = false", publish)
        self.assertEqual(2, text.count("publish(request, loaded)"))

    def test_only_presentation_consumers_read_retained_providers(self):
        for path in ["ui/components/ConversationCollaborationDialog.kt", "ui/SubAgentSettingsScreen.kt"]:
            self.assertIn("modelSource.providers.collectAsState()", source(path))
            self.assertNotIn("modelSource.displayProviders.collectAsState()", source(path))
        editor = source("ui/components/ConversationSubAgentEditor.kt")
        self.assertIn("modelSource.providers.value.singleOrNull", editor)
        self.assertIn("this.modelSource = modelSource", editor)
        self.assertIn("it.modelSource = modelSource", editor)

    def test_draft_is_not_cleared_or_confirmed_while_refreshing(self):
        text = source("ui/components/SubAgentProfileConfigDialog.kt")
        self.assertIn("!editor.modelRefreshing.value", text)
        self.assertIn("session.editor.modelRefreshing.collectAsState()", text)
        self.assertIn("val usable = !modelRefreshing &&", text)
        self.assertIn("if (!modelRefreshing) session.bindParallel(boundConfig)", text)
        self.assertIn("val ready = usable && (!hasModelIdentity || boundConfig != null)", text)
        self.assertIn("session.editor.modelDisplayProviders?.collectAsState()?.value ?: providers", text)
        self.assertIn("session.canCommit(latestEditor, latestEnabled)", text)

    def test_pure_model_projection_has_no_null_first_composition(self):
        text = source("ui/components/SubAgentProfileConfigDialog.kt")
        self.assertIn("val boundConfig = remember(draft.providerId, draft.modelId, draft.role, presentationProviders)", text)
        self.assertIn("resolveSubAgentProfileConfig(draft, presentationProviders)", text)
        self.assertNotIn("produceState<SubAgentResolvedDraftBinding", text)
        self.assertNotIn("SubAgentResolvedDraftBinding", text)

    def test_android_regression_covers_pending_reload_and_real_background(self):
        text = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/SubAgentResumeGeometryActivityTest.kt").read_text()
        for assertion in ["activity.moveTaskToBack(true)", "Lifecycle.State.CREATED", "Lifecycle.State.RESUMED", "ViewTreeObserver.OnPreDrawListener",
                          "choreographer.removeFrameCallback(callback)", "return-frame height:",
                          "assertFalse(session.canCommit(editor, true))", "assertGeometry(before)",
                          "assertSame(root,", "source.displayProviders.value.isEmpty()", "WriteResult.Rejected"]:
            self.assertIn(assertion, text)

    def test_final_model_failure_does_not_clear_unconfirmed_parallel_input(self):
        text = source("ui/components/SubAgentProfileConfigDialog.kt")
        binding = text.split("fun bindParallel(config:", 1)[1].split("fun editParallel", 1)[0]
        self.assertIn("} ?: return", binding)
        self.assertNotIn('parallelValue = if (next == null) ""', binding)
        self.assertIn("bindingChanged = true; resetParallel()", text)
        regression = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/SubAgentResumeGeometryActivityTest.kt").read_text()
        failed = regression.split("fun testARealRemovedModelIsNotKeptAsAValidBinding()", 1)[1]
        self.assertEqual(2, failed.count('assertEquals("7", session.parallelValue)'))

    def test_no_resize_or_animation_workaround_was_added(self):
        text = source("ui/components/SubAgentProfileConfigDialog.kt")
        for workaround in ["delay(", "setSoftInputMode", "requestApplyInsets", "animateContentSize", "requiredHeight"]:
            self.assertNotIn(workaround, text)
        motion = source("ui/components/DialogMotion.kt")
        self.assertIn("LaunchedEffect(Unit) { progress.animateTo(1f, dialogEnterSpec(largeScreen)) }", motion)


if __name__ == "__main__":
    unittest.main()
