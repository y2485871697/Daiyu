package app.daiyu.android

import android.database.sqlite.SQLiteDatabase

/** Downgrade fixtures must remove v10-only schema before assigning a v1..9 user_version. */
internal fun removeAssistantSchemaForLegacyFixture(db: SQLiteDatabase) {
    val foreignKeys = db.rawQuery("PRAGMA foreign_keys", null).use {
        it.moveToFirst()
        it.getInt(0) != 0
    }
    db.setForeignKeyConstraintsEnabled(false)
    try {
        removePostV9ConversationSchemaForFixture(db)
        // Do not rename the referenced table: SQLite would retarget its foreign keys.
        db.execSQL("CREATE TABLE conversations_v10_fixture(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL,folder TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO conversations_v10_fixture SELECT id,title,draft,updated,folder,pinned FROM conversations")
        db.execSQL("DROP TABLE conversations")
        db.execSQL("ALTER TABLE conversations_v10_fixture RENAME TO conversations")
        db.execSQL("DROP TABLE assistants")
        db.execSQL("DROP TABLE assistant_selection")
    } finally {
        db.setForeignKeyConstraintsEnabled(foreignKeys)
    }
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
