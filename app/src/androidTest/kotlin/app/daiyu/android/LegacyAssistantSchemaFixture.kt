package app.daiyu.android

import android.database.sqlite.SQLiteDatabase

/** Downgrade fixtures must remove v10-only schema before assigning a v1..9 user_version. */
internal fun removeAssistantSchemaForLegacyFixture(db: SQLiteDatabase) {
    removePostV9ConversationSchemaForFixture(db)
    db.execSQL("ALTER TABLE conversations RENAME TO conversations_v10_fixture")
    db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL,folder TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0)")
    db.execSQL("INSERT INTO conversations SELECT id,title,draft,updated,folder,pinned FROM conversations_v10_fixture")
    db.execSQL("DROP TABLE conversations_v10_fixture")
    db.execSQL("DROP TABLE assistants")
    db.execSQL("DROP TABLE assistant_selection")
}

internal fun removePostV9ConversationSchemaForFixture(db: SQLiteDatabase) {
    listOf("conversation_title_insert", "conversation_title_update", "conversation_title_delete",
        "conversation_completion_event", "conversation_completion_message_deleted").forEach {
        db.execSQL("DROP TRIGGER IF EXISTS $it")
    }
    listOf("conversation_title_revisions", "runtime_preparations", "conversation_completion_markers").forEach {
        db.execSQL("DROP TABLE IF EXISTS $it")
    }
}
