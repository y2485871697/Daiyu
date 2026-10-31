package app.daiyu.android.storage

import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.testContext
import java.util.UUID

@Suppress("DEPRECATION")
class ConversationModelBindingDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private val a = ConversationModelBinding("primary", UUID.randomUUID().toString())
    private val b = ConversationModelBinding("primary", UUID.randomUUID().toString())
    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "conversation-model-${UUID.randomUUID()}")
        database = ConversationDatabase(context)
        database.initializeModelBindings(a)
    }
    override fun tearDown() {
        database.close(); context.deleteDatabase("conversations.db"); super.tearDown()
    }
    fun testSameProviderModelsAreIndependentAndChildCopiesTheSourceOnce() {
        val first = database.create(database.selectedAssistantId())
        val second = database.create(database.selectedAssistantId())
        assertTrue(database.bindModel(first.id, a, b))
        assertEquals(a, database.modelBinding(second.id))
        val child = database.create(database.selectedAssistantId(), modelSource = first.id)
        assertEquals(b, database.modelBinding(child.id))
        assertTrue(database.bindModel(child.id, b, a))
        assertEquals(b, database.modelBinding(first.id))
        assertFalse(database.bindModel(first.id, a, a))
        database.close(); database = ConversationDatabase(context)
        database.initializeModelBindings(ConversationModelBinding("other", UUID.randomUUID().toString()))
        assertEquals(b, database.modelBinding(first.id))
        assertEquals(a, database.modelBinding(second.id))
    }
    fun testExplicitNullDoesNotBecomeANewGlobalFallbackOnReopen() {
        database.writableDatabase.execSQL("UPDATE conversation_model_selections SET provider_slot=NULL,local_model_id=NULL WHERE owner_id=''")
        database.initializeModelBindings(b)
        assertNull(database.modelBinding(null))
        assertNull(database.modelBinding(database.create(database.selectedAssistantId()).id))
    }
    fun testCreateRollbackRetainsSourceAndDoesNotPublishAModelOrphan() {
        val source = database.create(database.selectedAssistantId())
        database.writableDatabase.execSQL("CREATE TEMP TRIGGER ignore_model BEFORE INSERT ON conversation_model_selections BEGIN SELECT RAISE(IGNORE); END")
        try {
            try { database.create(database.selectedAssistantId(), modelSource = source.id); fail("must reject") }
            catch (_: IllegalStateException) { }
        } finally { database.writableDatabase.execSQL("DROP TRIGGER ignore_model") }
        assertEquals(listOf(source.id), database.conversations().map { it.id })
        assertEquals(a, database.modelBinding(source.id))
    }
    fun testNullWriteDeletedByTriggerFailsReceiptAndDoesNotBecomeSuccessfulSeed() {
        database.writableDatabase.execSQL("UPDATE conversation_model_selections SET provider_slot=NULL,local_model_id=NULL WHERE owner_id=''")
        database.writableDatabase.execSQL("CREATE TEMP TRIGGER remove_selection AFTER INSERT ON conversation_model_selections BEGIN DELETE FROM conversation_model_selections WHERE owner_id=NEW.owner_id; END")
        try {
            try {
                database.inheritModelBinding(null, app.daiyu.android.domain.ConversationId("missing-null-owner"))
                fail("deleted write must reject")
            } catch (_: IllegalStateException) { }
        } finally { database.writableDatabase.execSQL("DROP TRIGGER remove_selection") }
    }
    fun testVersion29UpgradeAddsSelectionsWithoutChangingExistingAssistantOrDraft() {
        val source = database.create(database.selectedAssistantId())
        database.draft(source.id, "retained")
        database.writableDatabase.execSQL("DROP TABLE conversation_model_selections")
        database.writableDatabase.version = 29
        database.close(); database = ConversationDatabase(context)
        assertEquals(31, database.readableDatabase.version)
        database.initializeModelBindings(a)
        assertEquals(a, database.modelBinding(source.id))
        assertEquals(source.assistantId, database.assistantId(source.id))
        assertEquals("retained", database.conversations().single().draft)
    }
}
