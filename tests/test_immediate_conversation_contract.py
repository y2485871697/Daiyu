"""Source wiring guards; Android tests cover SQLite, owner and UI behavior separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class ImmediateConversationContractTest(unittest.TestCase):
    def host(self):
        return (SRC / 'MainActivity.kt').read_text()

    def test_both_explicit_entries_create_instead_of_selecting_the_composer(self):
        host = self.host()
        self.assertIn('onNewConversation = { guard { createNewConversation(); dismissSidebar() } }', host)
        self.assertIn('ChatMenuAction.NEW_CHAT -> if (currentChat()) createNewConversation()', host)

    def test_creation_has_admission_and_reasoning_before_publishing_the_owner(self):
        host = self.host()
        self.assertIn('internal fun createNewConversation()', host)
        create = host.split('internal fun createNewConversation()', 1)[1].split('internal fun select(', 1)[0]
        self.assertLess(create.index('pendingTextSend == null'), create.index('controller.database.create('))
        self.assertLess(create.index('controller.ready && !controller.dataBusy'), create.index('controller.database.create('))
        self.assertLess(create.index('reasoningPreference.snapshot(selected?.id?.value)'), create.index('controller.database.create('))
        self.assertIn('DatabaseTransactions { controller.database.writableDatabase }.run', create)
        self.assertIn('controller.database.create(controller.database.selectedAssistantId())', create)
        self.assertLess(create.index('reasoningPreference.retainAccepted(it.id.value, reasoning)'), create.index('select(created)'))
        self.assertLess(create.index('select(created)'), create.index('updatePane()'))
        self.assertNotIn('database.draft(', create)
        self.assertNotIn('workspaceBinding(null)', create)

    def test_fallback_null_selection_keeps_its_non_creating_meaning(self):
        select = self.host().split('internal fun select(conversation: Conversation?)', 1)[1].split('private fun sendDraft(', 1)[0]
        self.assertNotIn('database.create(', select)
        self.assertIn('if (conversation == null) remove("selected")', select)
        self.assertIn('if (conversation == null) controller.database.draft()', select)

    def test_first_send_keeps_existing_owner_and_creates_no_second_row(self):
        controller = (SRC / 'ChatController.kt').read_text()
        self.assertIn('database.send(user,assistant,conversation==null,assistantContext.assistantId', controller)
        self.assertIn('check(ownerExists == (conversation != null))', controller)

    def test_title_uses_first_physical_turn_after_acceptance_not_null_owner(self):
        controller = (SRC / 'ChatController.kt').read_text()
        title = controller.split('acceptedTitle.set(saved.title)', 1)[1].split('imageAttempt?.accepted(saved)', 1)[0]
        self.assertIn('if (physicalVideoHistory.rows.isEmpty() && actionRevision == null)', title)
        self.assertIn('coordinator.prepare(saved.id, run)', title)
        self.assertNotIn('if (conversation == null)', title)
        self.assertIn('ControllerVideoHistory(database).assertCurrent(db, physicalVideoHistory)', controller)

    def test_empty_conversations_remain_independent_storage_rows(self):
        database = (SRC / 'storage/ConversationDatabase.kt').read_text()
        create = database.split('fun create(', 1)[1].split('fun draft(id:', 1)[0]
        self.assertIn('UUID.randomUUID()', create)
        self.assertIn('transaction { db ->', create)
        self.assertIn('UsageStatisticsStore.created(db, value.id.value, value.updatedAt)', create)
        query = database.split('fun conversations(', 1)[1].split('if(query.isEmpty()) return list', 1)[0]
        self.assertNotIn('JOIN messages', query)
        self.assertNotIn('EXISTS', query)

    def test_android_regressions_cover_real_entries_recreation_drafts_and_failure(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ImmediateConversationActivityTest.kt').read_text()
        for fragment in ['node("chat-menu:NEW_CHAT").performClick()', 'node("drawer-new").performClick()',
                         'val reopened = ConversationDatabase(fixture.application)', 'recreateActivity(activity)', 'RAISE(IGNORE)',
                         'ReasoningEffort.HIGH', 'fixture.database.draft()', 'assertEquals(0, fixture.opens.get())',
                         'testFallbackSelectionDoesNotCreateAConversation']:
            self.assertIn(fragment, test)

    def test_database_reopen_closes_without_newer_sdk_autocloseable_cast(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ImmediateConversationActivityTest.kt').read_text()
        reopen = test.split('fun testEmptyConversationSurvivesDatabaseReopenAndActivityRecreation()', 1)[1].split('fun testIgnoredCreate', 1)[0]
        self.assertIn('val reopened = ConversationDatabase(fixture.application)', reopen)
        self.assertIn('} finally {\n            reopened.close()\n        }', reopen)
        self.assertLess(reopen.index('reopened.close()'), reopen.index('recreateActivity(activity)'))
        self.assertNotIn('.use {', reopen)

    def test_database_recovery_uses_an_independent_released_fixture(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/storage/ImmediateConversationDatabaseTest.kt').read_text()
        self.assertIn('database.close()', test)
        self.assertIn('database.recover()', test)
        self.assertIn('testEmptyOwnersSurviveRecoveryAndFirstSendReusesTheirId', test)
        self.assertIn('testFailedFirstSendKeepsPreviouslyCreatedEmptyOwnerAcrossRecovery', test)
        self.assertIn('RAISE(IGNORE)', test)

    def test_title_regression_keeps_first_send_once_and_manual_rename_protected(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ControllerTitleProductionTest.kt').read_text()
        self.assertIn('testPersistedEmptyConversationFirstTurnNamesOnceWithoutCreatingAnotherConversation', test)
        self.assertIn('testRenamedEmptyConversationAndFailedFirstTurnNeverStartTitle', test)
        self.assertIn('assertEquals(empty.id, f.db.conversations().single().id)', test)
        self.assertIn('assertEquals(1, f.calls.get())', test)


if __name__ == '__main__':
    unittest.main()
