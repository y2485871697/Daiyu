package app.daiyu.android.context

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase

@Suppress("DEPRECATION")
class RuntimePreparationSchemaTest : InstrumentationTestCase() {
    fun testV15SourceMigrationIsIdempotentAndDoesNotInventHistoricalUsage() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE child_request_usage(task_id TEXT,request_id TEXT,input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER)")
            db.execSQL("INSERT INTO child_request_usage VALUES('child','opaque',NULL,0,NULL)")
            RuntimePreparationSchema.install(db); RuntimePreparationSchema.install(db)
            db.rawQuery("SELECT usage_source,input_tokens,output_tokens,cached_tokens FROM child_request_usage", null).use {
                assertTrue(it.moveToFirst()); assertEquals("MAIN", it.getString(0))
                assertTrue(it.isNull(1)); assertEquals(0L, it.getLong(2)); assertTrue(it.isNull(3)); assertFalse(it.moveToNext())
            }
            db.rawQuery("SELECT COUNT(*) FROM runtime_preparations", null).use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
        }
    }
}
