package app.daiyu.android

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase

internal fun testContext(context:Context,prefix:String="platform-test-"):Context=object:ContextWrapper(context) {
        private fun testName(name:String)="$prefix$name"
        override fun getDatabasePath(name:String)=baseContext.getDatabasePath(testName(name))
        override fun deleteDatabase(name:String)=baseContext.deleteDatabase(testName(name))
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=
            baseContext.openOrCreateDatabase(testName(name),mode,factory)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=
            baseContext.openOrCreateDatabase(testName(name),mode,factory,handler)
        override fun getSharedPreferences(name:String,mode:Int)=baseContext.getSharedPreferences(testName(name),mode)
    }
