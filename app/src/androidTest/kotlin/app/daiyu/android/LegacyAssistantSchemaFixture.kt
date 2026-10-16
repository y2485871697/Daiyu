package app.daiyu.android

import android.database.sqlite.SQLiteDatabase

/** Downgrade fixtures must remove v10-only schema before assigning a v1..9 user_version. */
internal fun removeAssistantSchemaForLegacyFixture(db: SQLiteDatabase) {
    db.execSQL("ALTER TABLE conversations RENAME TO conversations_v10_fixture")
    db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL,folder TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0)")
    db.execSQL("INSERT INTO conversations SELECT id,title,draft,updated,folder,pinned FROM conversations_v10_fixture")
    db.execSQL("DROP TABLE conversations_v10_fixture")
    db.execSQL("DROP TABLE assistants")
    db.execSQL("DROP TABLE assistant_selection")
}
