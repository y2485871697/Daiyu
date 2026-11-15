"""Stable sidebar date and scroll owner wiring; behavioral coverage is in Kotlin/device tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class ConversationDateContractTest(unittest.TestCase):
    def test_migration_is_atomic_and_precedes_modern_upgrade_return(self):
        schema = (MAIN / 'storage/ConversationDatabase.kt').read_text()
        self.assertIn('"conversations.db", null, 33)', schema)
        self.assertIn('oldVersion in 1..32 && newVersion == 33', schema)
        upgrade = schema.split('override fun onUpgrade(', 1)[1]
        self.assertLess(upgrade.index('UsageStatisticsStore.install(db)'), upgrade.index('ConversationSidebarDates.install(db)'))
        self.assertLess(upgrade.index('ConversationSidebarDates.install(db)'), upgrade.index('if (oldVersion >= 13) return'))
        dates = (MAIN / 'storage/ConversationSidebarDates.kt').read_text()
        self.assertIn('check(db.inTransaction())', dates)
        self.assertIn('WHERE sidebar_date IS NULL', dates)
        self.assertIn('AFTER INSERT ON conversations WHEN NEW.sidebar_date IS NULL', dates)
        self.assertNotIn('AFTER UPDATE', dates)
        self.assertNotIn('UPDATE usage_statistics_conversations', dates)

    def test_ui_uses_stable_date_and_preserves_real_update_time(self):
        source = (MAIN / 'ui/RetainedConversationData.kt').read_text()
        self.assertIn('Instant.ofEpochMilli(conversation.sidebarDateMillis)', source)
        self.assertIn('isToday = groupDate == today', source)
        self.assertIn('dateGroupMillis = conversation.sidebarDateMillis', source)
        self.assertIn('updatedAtMillis = conversation.updatedAt', source)
        groups = (MAIN / 'ui/components/ConversationDrawerGroups.kt').read_text()
        self.assertIn('conversation.dateGroupMillis', groups)
        self.assertNotIn('conversation.updatedAtMillis', groups)
        self.assertIn('thenByDescending', groups)

    def test_scroll_state_is_owned_outside_conversation_preview_subtree(self):
        activity = (MAIN / 'MainActivity.kt').read_text()
        owner = activity.index('val conversationDrawerListState = androidx.compose.foundation.lazy.rememberLazyListState()')
        self.assertLess(owner, activity.index('ConversationSidePaneScaffold('))
        self.assertIn('listState = conversationDrawerListState', activity)
        source = (MAIN / 'ui/components/ConversationSidePaneScaffold.kt').read_text()
        self.assertIn('listState: LazyListState = rememberLazyListState()', source)
        self.assertIn('listState = listState', source)
        self.assertIn('LazyColumn(\n                state = listState', source)

    def test_backup_v4_keeps_stable_date_and_legacy_compatibility(self):
        source = (MAIN / 'storage/ConversationDatabase.kt').read_text()
        self.assertIn('version in 1L..4L', source)
        self.assertIn('if (version >= 4L) integer(c,"sidebar_date") else integer(c,"updated")', source)
        self.assertIn('put("sidebar_date",c.sidebarDateMillis)', source)
        self.assertIn('AND sidebar_date=?', source)
        self.assertIn('legacy.getInt("version") in 1..4', (MAIN / 'backup/NeutralBackupService.kt').read_text())
        self.assertIn('.put("version", 4)', (MAIN / 'backup/BackupConversationMerge.kt').read_text())

    def test_cloud_scope_runs_exact_business_tests(self):
        workflow = (ROOT / '.github/workflows/chat-workflow-checks.yml').read_text()
        self.assertIn('conversation-date', workflow)
        for name in ('ConversationDrawerGroupsTest', 'ConversationPreviewTest', 'BackupConversationMergeTest', 'MessageDisplayStoreTest'):
            self.assertIn(name, workflow)
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/storage/ConversationDateGroupingTest.kt').read_text()
        for name in ('testMissingColumnMigrationUsesKnownCreationAndFreezesUnknownDateOnlyOnce',
                     'testSchema32UpgradeThenContinuationRenameAndPinKeepTheOriginalDay',
                     'testEditingRegeneratingAndDeletingTailKeepTheStableDayButBranchGetsToday',
                     'testSelectingAnOldConversationKeepsTheOpenDrawerAndItsScrollPosition',
                     'testDrawerConditionsSurviveRealConversationSelectionAndReopen'):
            self.assertIn('fun ' + name + '()', device)

    def test_drawer_controls_use_compact_upward_menus_and_highlight_without_check_circles(self):
        source = (MAIN / 'ui/components/ConversationDrawerControls.kt').read_text()
        for label in ('筛选', '排序', '正在运行', '已完成', '重置', '按日期', '按最后输出', '正序', '倒序'):
            self.assertIn('"' + label + '"', source)
        self.assertIn('preferAbove = true', source)
        self.assertIn('colors.selectionContainer', source)
        self.assertIn('colors.selectionContent', source)
        self.assertIn('colors.danger', source)
        self.assertEqual(2, source.count('DrawerControlDivider()') - 1)
        self.assertNotIn('DaiyuIcons.Check', source)
        self.assertNotIn('CircleShape', source)
        self.assertIn('ChatAnchoredMenu(expanded, onDismiss, preferAbove = true', source)
        self.assertIn('minWidth = 0.dp', source)
        self.assertNotIn('DaiyuDropdownMenu(', source)
        self.assertIn('Modifier.width(IntrinsicSize.Max)', source)
        self.assertIn('TextAlign.Center', source)
        self.assertIn('.padding(horizontal = 2.dp, vertical = 2.dp)\n        .clip', source)
        layer = (MAIN / 'ui/screens/chat/conversation/ChatMenuLayer.kt').read_text()
        self.assertIn('preferAbove: Boolean = false', layer)
        self.assertIn('minWidth: Dp = 156.dp', layer)
        self.assertIn('menu.minWidth.roundToPx()', layer)
        self.assertIn('preferAbove = menu.preferAbove', layer)
        pane = (MAIN / 'ui/components/ConversationSidePaneScaffold.kt').read_text()
        for name in ('filterRunning', 'filterCompleted', 'sortByLastOutput', 'reverse'):
            self.assertIn('val ' + name + ' = controlsState.' + name, pane)
            self.assertNotIn('var ' + name + ' by rememberSaveable', pane)
        activity = (MAIN / 'MainActivity.kt').read_text()
        owner = activity.index('val conversationDrawerControlsState = rememberConversationDrawerControlsState()')
        self.assertLess(owner, activity.index('val rootChatContent:'))
        self.assertIn('controlsState = conversationDrawerControlsState,', activity)
        self.assertIn('controlsState = controlsState,', pane)
        self.assertIn('rememberSaveable(saver = ConversationDrawerControlsState.Saver)', source)
        self.assertIn('state.searchResults().filterDrawerStatus(filterRunning, filterCompleted)', pane)
        self.assertIn('drawer-assistant-button', pane)
        self.assertIn('val showDateHeadings = !sortByLastOutput && !filterRunning && !filterCompleted', pane)
        self.assertIn('group.section == ConversationDrawerSection.Pinned || showDateHeadings', pane)

    def test_last_output_projection_reads_only_tail_metadata_and_excludes_running(self):
        source = (MAIN / 'storage/ConversationOutputTimes.kt').read_text()
        self.assertIn('row.status != "RUNNING"', source)
        self.assertIn('ORDER BY seq DESC LIMIT 1', source)
        self.assertIn('created_at', source)
        self.assertNotIn('MAX(', source)
        self.assertNotIn('created_at IS NOT NULL', source)
        self.assertIn('reader.skipValue()', source)
        self.assertNotIn('MessageHistoryCodec.decode', source)
        for event in ('INSERT', 'UPDATE', 'DELETE'):
            self.assertIn('AFTER ' + event + ' ON messages', source)
