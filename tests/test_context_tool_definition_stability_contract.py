"""Context-tool metadata stays stable; executable behavior is covered by JVM SQLite tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class ContextToolDefinitionStabilityContractTest(unittest.TestCase):
    def test_dynamic_targets_are_bound_after_metadata_not_in_the_hint(self):
        source = (SRC / 'integration/tools/ScopedContextTools.kt').read_text()
        hint = source.split('val hint = when (name)', 1)[1].split('check(source.getString("sideEffect")', 1)[0]
        for dynamic in ('conversationId.value', 'runId.value', 'JSONObject()', '.put("archive_id", preparedArchiveId)'):
            self.assertNotIn(dynamic, hint)
        prepare = source.split('override fun prepare(arguments: ToolArguments)', 1)[1]
        self.assertIn('JSONObject(arguments.canonicalJson())', prepare)
        self.assertIn('if (!resolved.has("branch_id"))', prepare)
        self.assertIn('if (!resolved.has("run_id")) resolved.put("run_id", runId.value)', prepare)
        self.assertIn('if (!resolved.has("archive_id"))', prepare)
        self.assertIn('adapter.execute(name, JSONObject(canonical))', prepare)

    def test_defaults_keep_the_existing_scoped_backend_and_archive_gate(self):
        source = (SRC / 'integration/tools/ScopedContextTools.kt').read_text()
        self.assertIn('registry.forModel(scope)', source)
        self.assertNotIn('executeLocal(', source)
        self.assertIn('preparedArchiveId == null || !scope.allowsArchive(conversationId)', source)
        self.assertIn('required = name in required && name != defaultedField', source)
        registry = (SRC / 'context/ContextRegistry.kt').read_text()
        for check in ('scope.allowsUsage(ConversationId(branch), run)', 'service.isUsageOwner(branch, run)',
                      'scope.allowsArchive(persistedOwner)', 'service.archiveOwner(id)'):
            self.assertIn(check, registry)

    def test_behavior_regression_is_inside_the_existing_cloud_context_scope(self):
        test = ROOT / 'app/src/test/kotlin/app/daiyu/android/context/ContextToolDefinitionStabilityTest.kt'
        source = test.read_text()
        for behavior in ('completeDefinitionsStayEqualAcrossRunsConversationsAndArchiveLocators',
                         'everyProtocolSendsStableToolsAndSessionGatewayKeepsTheDurableConversation',
                         'omittedAndExplicitUsageTargetsKeepTheirOriginalMeanings',
                         'identicalToolCallsAcrossRunsNeverReplayAnotherRunsResultOrWidenItsScope',
                         'defaultsStillUseRunGrantsAndRejectForeignOrUnknownHostBindings',
                         'archiveDefaultsPreserveExplicitHistoricalIdsAndPersistedOwnerChecks',
                         'optionalIdsDoNotAcceptNullWrongTypesEmptyIdsOrForgedAuthority'):
            self.assertIn('@Test fun ' + behavior, source)
        self.assertIn('--tests app.daiyu.android.context.*', (ROOT / 'scripts/cooperation/evidence.py').read_text())
        self.assertIn("'test_context*.py'", (ROOT / '.github/workflows/build.yml').read_text())
