"""Source wiring plus real SQLite schema checks; Android end-to-end runs in the approved batch."""
from pathlib import Path
import re
import sqlite3
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
def text(name): return (SRC / name).read_text()
class ConversationModelBindingContractTest(unittest.TestCase):
    def test_sqlite_pair_is_atomic_and_stores_explicit_unselected(self):
        source = text("storage/ConversationModelSelections.kt")
        schema = re.search(r'db.execSQL\("(CREATE TABLE IF NOT EXISTS conversation_model_selections[^"\n]+)"\)', source).group(1)
        db = sqlite3.connect(":memory:")
        db.execute(schema)
        db.execute("INSERT INTO conversation_model_selections VALUES(?,?,?)", ("A", "p1", "m1"))
        db.execute("INSERT INTO conversation_model_selections VALUES(?,?,?)", ("B", "p1", "m2"))
        db.execute("INSERT INTO conversation_model_selections VALUES(?,?,?)", ("", None, None))
        with self.assertRaises(sqlite3.IntegrityError):
            db.execute("INSERT INTO conversation_model_selections VALUES(?,?,?)", ("bad", "p1", None))
        db.execute("UPDATE conversation_model_selections SET provider_slot=?,local_model_id=? WHERE owner_id=?", ("p2", "m3", "A"))
        self.assertEqual(("p1", "m2"), db.execute("SELECT provider_slot,local_model_id FROM conversation_model_selections WHERE owner_id='B'").fetchone())
        self.assertEqual((None, None), db.execute("SELECT provider_slot,local_model_id FROM conversation_model_selections WHERE owner_id=''").fetchone())
    def test_binding_seed_and_create_share_existing_database_transaction(self):
        database = text("storage/ConversationDatabase.kt")
        self.assertIn("ConversationModelSelections.install(db)", database)
        self.assertIn("ConversationModelSelections.inherit(db, modelSource, value.id)", database)
        self.assertIn("ConversationModelSelections.inherit(db, null, value.id)", database)
        self.assertIn("ConversationModelSelections.delete(db, id)", database)
        store = text("storage/ConversationModelSelections.kt")
        self.assertIn("if (!exists) write", store)
        controller = text("ChatController.kt")
        self.assertLess(controller.index("database.initializeModelBindings("), controller.index("val runtime=SQLiteRuntimeStore.acquire("))
    def test_model_selection_does_not_mutate_global_active_or_current_model(self):
        controller = text("ChatController.kt").split("internal fun selectActiveProviderCatalogModel", 1)[1].split("internal fun saveConversationGptSpeed", 1)[0]
        self.assertIn("database.bindModel(conversation, expectedBinding, binding)", controller)
        self.assertIn("owner = conversation?.value", controller)
        self.assertNotIn("providers.selectActiveCatalogModel", controller)
        repository = text("storage/ProviderRepository.kt").split("internal fun selectConversationModel", 1)[1].split("private fun contextReceiptIdentity", 1)[0]
        self.assertIn("consumeSelection(ticket)", repository)
        self.assertNotIn("selectCatalogModel(", repository)
        self.assertNotIn("registry.change", repository)
        self.assertNotIn("store.save", repository)
    def test_all_main_runtime_entries_resolve_the_explicit_conversation(self):
        controller = text("ChatController.kt")
        self.assertNotIn("providers.freezeActive()", controller)
        self.assertNotIn("providers.isCurrentActive(frozenProvider)", controller)
        self.assertIn("freezePreparationProvider(token, conversation?.id)", controller)
        self.assertIn("freezeConversationProvider(pending.conversation?.id)", controller)
        self.assertIn("providers.isCurrentConversation(binding, frozenProvider)", controller)
        self.assertIn("controller.freezeConversationProvider(conversation?.id)", text("ImageChatHost.kt"))
    def test_model_label_and_picker_are_fenced_to_the_conversation_visit(self):
        host = text("MainActivity.kt")
        label = host.split("private fun refreshConfirmedModelLabel()",1)[1].split("private fun refreshAutomaticCompression",1)[0]
        self.assertIn("controller.conversationModelEditing(conversation)", label)
        self.assertIn("selected?.id == conversation && editorGeneration == generation", label)
        picker = host.split("private fun reloadChatModels",1)[1].split("private fun gatewayCurrent",1)[0]
        self.assertIn("controller.database.modelBinding(conversation)", picker)
        self.assertIn("it.settings.localIdentity == binding?.localModelIdentity", picker)
        self.assertIn("conversation = selected?.id", picker)
    def test_assistant_selection_and_new_creation_follow_the_current_conversation(self):
        host = text("MainActivity.kt")
        helper = host.split("private fun selectCurrentConversationAssistant",1)[1].split("private fun assistantOwnerMatches",1)[0]
        self.assertIn("if (!allowTaskConfigurationChange(conversation)) return", helper)
        self.assertIn("controller.bindConversationAssistant(current.id, expected, id)", helper)
        self.assertIn("selected = current.copy(assistantId = id)", helper)
        create = host.split("internal fun createNewConversation()",1)[1].split("internal fun select(",1)[0]
        self.assertIn("if (source == null) controller.database.selectedAssistantId()", create)
        self.assertIn("checkNotNull(controller.database.assistantId(source))", create)
        self.assertIn("modelSource = source", create)
    def test_context_summary_and_capacity_receive_conversation_binding(self):
        self.assertIn("database.modelBinding(owner)", text("ChatController.kt"))
        worker = text("context/ContextManagementWorker.kt")
        self.assertIn("repository.conversationContextUsage(it).windowTokens", worker)
        self.assertIn("conversationModel?.invoke()", worker)
        self.assertIn("repo.withConversationModel(binding)", text("context/ManualSummaryTargets.kt"))
