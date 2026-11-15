package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase

/** A stable sidebar date is not evidence of a legacy conversation's creation time. */
internal object ConversationSidebarDates {
    fun install(db: SQLiteDatabase) {
        check(db.inTransaction())
        val hasDate = db.rawQuery("PRAGMA table_info(conversations)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == "sidebar_date") found = true
            found
        }
        if (!hasDate) db.execSQL("ALTER TABLE conversations ADD COLUMN sidebar_date INTEGER")
        // Unknown legacy creation dates retain their current bucket once, not on every read.
        db.execSQL("UPDATE conversations SET sidebar_date=COALESCE(" +
            "(SELECT created_at FROM usage_statistics_conversations WHERE conversation_id=conversations.id)," +
            "updated) WHERE sidebar_date IS NULL")
        // Covers explicit/implicit creation, both branch paths and legacy imports atomically.
        db.execSQL("CREATE TRIGGER IF NOT EXISTS conversation_sidebar_date_insert " +
            "AFTER INSERT ON conversations WHEN NEW.sidebar_date IS NULL BEGIN " +
            "UPDATE conversations SET sidebar_date=COALESCE(" +
            "(SELECT created_at FROM usage_statistics_conversations WHERE conversation_id=NEW.id)," +
            "NEW.updated) WHERE id=NEW.id; END")
    }
}
