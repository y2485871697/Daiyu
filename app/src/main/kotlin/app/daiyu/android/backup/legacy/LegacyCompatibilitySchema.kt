package app.daiyu.android.backup.legacy

import android.database.sqlite.SQLiteDatabase

/** Narrow additive installer for the existing helper only. No schema version assumption or bump.
 * Commit installs lazily inside its own transaction. A parent choosing eager installation may call
 * install(db) from that SAME helper's onCreate/onUpgrade transaction (including DB20/P37 merge). */
object LegacyCompatibilitySchema {
    fun install(db: SQLiteDatabase) {
        check(db.inTransaction()) { "Compatibility schema requires the existing helper transaction" }
        db.execSQL("CREATE TABLE IF NOT EXISTS legacy_r12_imports(id TEXT PRIMARY KEY NOT NULL,source_hash TEXT NOT NULL,format TEXT NOT NULL,version INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS legacy_r12_histories(id TEXT PRIMARY KEY NOT NULL,import_id TEXT NOT NULL REFERENCES legacy_r12_imports(id),source_id TEXT NOT NULL,title TEXT NOT NULL,source_created INTEGER,source_updated INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS legacy_r12_assets(import_id TEXT NOT NULL REFERENCES legacy_r12_imports(id),entry TEXT NOT NULL,sha256 TEXT NOT NULL,byte_count INTEGER NOT NULL,PRIMARY KEY(import_id,entry))")
        db.execSQL("CREATE TABLE IF NOT EXISTS legacy_r12_chunks(owner TEXT NOT NULL,name TEXT NOT NULL,part INTEGER NOT NULL,data BLOB NOT NULL,PRIMARY KEY(owner,name,part))")
    }
}
