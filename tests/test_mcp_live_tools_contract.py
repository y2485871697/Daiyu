from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
DOMAIN = ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain'

def text(path):
    return path.read_text() if path.exists() else ''

class McpLiveToolsContractTest(unittest.TestCase):
    def test_ordinary_run_mounts_live_scope_even_with_an_empty_initial_catalog(self):
        source = text(APP / 'ChatController.kt')
        self.assertIn('McpLiveScope(conversationId, run, store::currentSnapshot, store::observeChanges, mcpTransport)', source)
        self.assertIn('liveMcp=liveMcp', source)
        self.assertIn('setOf(app.daiyu.android.mcp.McpLiveScope.CAPABILITY)', source)
        self.assertIn('preparingLiveMcp?.close()', source)

    def test_store_has_persistent_cross_instance_changes_and_an_authorized_memory_snapshot(self):
        source = text(APP / 'mcp/McpSettingsStore.kt')
        self.assertIn('snapshots[path]?.takeIf(::isCurrent)', source)
        self.assertIn('callbacks.addAll(changeObservers[path].orEmpty())', source)
        self.assertIn('snapshots[path] = value', source)

    def test_live_adapter_rechecks_owner_and_epoch_and_uses_central_exact_write_approval(self):
        source = text(APP / 'mcp/McpLiveScope.kt')
        self.assertIn('PreparedTool.approvedWrite(effect', source)
        self.assertIn('epoch === binding', source)
        self.assertIn('latest.stamp === binding.settings.stamp', source)
        self.assertIn('mcp_live_${binding.number}_${index}_call', source)
        self.assertIn('ExecutionReceipt.Unknown("mcp_result_owner_revoked")', source)
        self.assertIn('handle.observeDrained', source)
        self.assertNotIn('ToolDispatcher(', source)
        self.assertNotIn('ToolLedger(', source)

    def test_catalog_advertising_and_dispatch_share_a_live_source_and_the_original_ledger(self):
        source = text(APP / 'integration/tools/CatalogModelBridge.kt')
        self.assertIn('ToolDispatcher(currentCatalog(), session, ledger)', source)
        self.assertIn('val catalog = currentCatalog()', source)
        self.assertIn('catalog.advertisements(session)', source)
        self.assertIn('snapshot.withAdditional(it.registeredTools())', text(APP / 'integration/tools/RunCatalogTools.kt'))

    def test_only_new_requests_refresh_and_all_production_wrappers_forward(self):
        source = text(DOMAIN / 'ChatRunner.kt')
        self.assertIn('if (attempt == 0 && tool is CatalogTool) {\n                                    active.updateToolDefinitions(requestDefinitions)', source)
        self.assertIn('val local = runtime.localTokens(effectiveHistory(), requestDefinitions)', source)
        self.assertIn('active.updateToolDefinitions(requestDefinitions)', source)
        self.assertIn('val sideEffect = requestDefinitions.firstOrNull', source)
        for name in ['ResponsesModel', 'MessagesModel', 'NativeCompletionModel']:
            protocol = text(APP / f'model/{name}.kt')
            self.assertIn('tools = definitions.toList()', protocol)
            self.assertIn('tools = savedTools', protocol)
        for path in ['model/usage/UsageRecordingModel.kt', 'model/reconnect/FrozenReconnectAdapter.kt', 'ControllerAuxiliaryVision.kt']:
            self.assertIn('override fun updateToolDefinitions', text(APP / path))
        self.assertEqual(4, text(APP / 'model/reconnect/GuardModelSession.kt').count('override fun updateToolDefinitions'))

if __name__ == '__main__':
    unittest.main()
