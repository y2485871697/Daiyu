"""Quota-sync presentation contracts; no credentials, network or cloud mutations."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


def body(text, marker):
    start = text.index("{", text.index(marker))
    depth = 0
    for index in range(start, len(text)):
        depth += (text[index] == "{") - (text[index] == "}")
        if depth == 0:
            return text[start + 1:index]
    raise AssertionError(f"Unclosed block: {marker}")


class VoiceSyncLayoutContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (SRC / "VoiceAccountPanel.kt").read_text()
        cls.sync = body(cls.panel, 'if (page == "SYNC")')

    def test_sync_uses_small_shared_cards_as_independent_lazy_items(self):
        cards = ["credentials", "project", "read", "storage", "console"]
        positions = []
        for card in cards:
            marker = f'item {{ SettingsCard(Modifier.testTag("voice-sync-{card}-card"))'
            self.assertEqual(1, self.sync.count(marker))
            positions.append(self.sync.index(marker))
        self.assertEqual(sorted(positions), positions)
        self.assertNotIn('VoiceSettingsCard', self.sync)
        self.assertNotIn('.background(', self.sync)
        self.assertNotIn('.clip(', self.sync)
        self.assertNotIn('TextButton(', self.sync)

    def test_credentials_use_compact_trailing_eyes_and_clear_stale_secret(self):
        credentials = body(self.sync, 'SettingsCard(Modifier.testTag("voice-sync-credentials-card"))')
        for key in ('AK', 'SK'):
            self.assertIn(f'SettingsTextField("{key}",', credentials)
        self.assertEqual(2, credentials.count('password = true, enabled = !busy, compact = true'))
        self.assertIn('ak = it; sk = ""; project = "default"; projects = emptyList(); projectPicker = false; confirmation = null', credentials)
        self.assertIn('sk = it; project = "default"; projects = emptyList(); projectPicker = false; confirmation = null', credentials)
        self.assertNotIn('resources.', credentials)
        self.assertNotIn('execute', credentials)

    def test_current_project_fetches_before_opening_without_cached_list_gate(self):
        project = body(self.sync, 'SettingsCard(Modifier.testTag("voice-sync-project-card"))')
        self.assertEqual(1, project.count('SettingsRow('))
        self.assertIn('SettingsRow("当前项目", summary = project', project)
        self.assertIn('if (!enabled || ak.isBlank() || sk.isBlank()) null', project)
        self.assertNotIn('projects.isEmpty()', project)
        self.assertNotIn('ask(', project)
        self.assertLess(project.index('refreshProjects(a, s, p)'), project.index('projectPicker = true'))
        for removed in ('获取项目列表', '刷新项目列表', '手动填写项目（备用）', '清除已记住的信息', 'projectManual'):
            self.assertNotIn(removed, self.panel)

    def test_shared_refresh_rechecks_owner_and_resolves_only_fetched_projects(self):
        refresh = body(self.panel, 'suspend fun refreshProjects(')
        self.assertIn('projects = emptyList(); projectPicker = false', refresh)
        markers = ['resources.projects.list(a, s)', 'currentCoroutineContext().ensureActive()',
                   'check(current() && page == "SYNC" && ak == a && sk == s)',
                   'VoiceProjectCatalog.selectProject(fetched, preferred)',
                   'projects = fetched; project = selected.name', 'return selected.name']
        self.assertEqual(sorted(refresh.index(marker) for marker in markers),
                         [refresh.index(marker) for marker in markers])
        self.assertNotIn('catch', refresh)
        self.assertEqual(1, self.panel.count('resources.projects.list('))

    def test_save_fetches_every_time_before_encrypted_write_and_success(self):
        storage = body(self.sync, 'SettingsCard(Modifier.testTag("voice-sync-storage-card"))')
        self.assertEqual(1, storage.count('SettingsRow('))
        self.assertIn('SettingsRow("保存账户信息"', storage)
        self.assertIn('if (!enabled || ak.isBlank() || sk.isBlank()) null', storage)
        self.assertNotIn('project.isBlank()', storage)
        self.assertNotIn('projects.isEmpty()', storage)
        markers = ['val p = refreshProjects(a, s, preferred)', 'withContext(Dispatchers.IO)',
                   'currentCoroutineContext().ensureActive()',
                   'resources.preferences.save(account!!.fingerprint, a, s, p)',
                   'notice = "账户信息已加密保存在本机"']
        self.assertEqual(sorted(storage.index(marker) for marker in markers),
                         [storage.index(marker) for marker in markers])
        self.assertNotIn('resources.preferences.clear(', self.panel)
        self.assertIn('账户保存未确认', storage)
        self.assertIn('catch (cancelled: CancellationException) { throw cancelled }', self.panel)
        self.assertIn('else failureNotice ?:', self.panel)
        self.assertIn('execute(block = pending.second)', self.panel)

    def test_query_and_console_keep_existing_functionality(self):
        read = body(self.sync, 'SettingsCard(Modifier.testTag("voice-sync-read-card"))')
        self.assertIn('if (!enabled || ak.isBlank() || sk.isBlank() || project.isBlank()) null', read)
        self.assertIn('ask("查询项目 $p 的槽位', read)
        self.assertIn('resources.voices.importPurchased(speech!!, a, s, p)', read)
        console = body(self.sync, 'SettingsCard(Modifier.testTag("voice-sync-console-card"))')
        self.assertIn('SettingsRow("打开火山访问密钥管理"', console)
        self.assertIn('if (current()) uriHandler.openUri("https://console.volcengine.com/iam/keymanage/")', console)
        for unexpected in ('resources.voices.create(', 'resources.voices.accept(', '.synthesize(', 'LaunchedEffect'):
            self.assertNotIn(unexpected, self.sync)

    def test_static_descriptions_are_removed_but_results_errors_and_confirmation_remain(self):
        self.assertNotIn('SettingsNote(', self.sync)
        self.assertIn('notice = if (count == 0)', self.sync)
        self.assertIn('notice = "账户信息已加密保存在本机"', self.sync)
        self.assertIn('if (notice.isNotBlank()) item { SettingsNote(notice) }', self.panel)
        self.assertIn('previewState.error?.let', self.panel)
        self.assertIn('confirmation === pending && enabled', self.panel)
        self.assertIn('Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP', self.panel)
        self.assertIn('confirmation = null; operation?.cancel(); preview.stop()', self.panel)


if __name__ == '__main__':
    unittest.main()
