package app.daiyu.android

import android.app.AlertDialog
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import android.test.mock.MockContentResolver
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettingsStore
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Incremental regressions for failed selection commits and callbacks outliving their Activity.
 * Existing BackupActivityTest owns the four SAF routes, VIEW validation and configuration restore.
 * No network, exported provider, manifest change or external DocumentsUI is used here.
 */
@Suppress("DEPRECATION")
class ActivityLifecycleTest:InstrumentationTestCase() {
    private class Documents(private val document:File):ContentProvider() {
        val opens=AtomicInteger()
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/json"
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            opens.incrementAndGet()
            return ParcelFileDescriptor.open(document,ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri?=error("unused")
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=error("unused")
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=error("unused")
    }
    private class FixtureApplication(base:Context,private val resolver:MockContentResolver):Application() {
        init {attachBaseContext(base)}
        override fun getContentResolver()=resolver
    }
    private class SelectionPreferences(private val raw:SharedPreferences):SharedPreferences by raw {
        var failNextCommit=false
        override fun edit():SharedPreferences.Editor {
            val editor=raw.edit()
            return object:SharedPreferences.Editor by editor {
                override fun commit():Boolean {
                    if(failNextCommit) {failNextCommit=false;return false}
                    return editor.commit()
                }
            }
        }
    }
    private lateinit var application:ChatApplication
    private lateinit var fixture:FixtureApplication
    private lateinit var controller:ChatController
    private lateinit var preferences:SelectionPreferences
    private lateinit var provider:Documents
    private lateinit var directory:File
    private lateinit var aliasPrefix:String
    private var original:ChatController?=null
    private var activity:MainActivity?=null
    private val modelCalls=AtomicInteger()
    private val uri=Uri.parse("content://activity-lifecycle/import.json")

    private fun main(work:()->Unit) {
        val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {try {work()} catch(t:Throwable) {failure.set(t)}}
        failure.get()?.let {throw it}
    }
    private fun awaitState(predicate:()->Boolean) {
        val ready=CountDownLatch(1)
        val listener:()->Unit={if(predicate()) ready.countDown()}
        main {controller.observe(listener);listener()}
        try {
            assertTrue("controller callback timeout",ready.await(10,TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        } finally {main {controller.unobserve(listener)}}
    }
    override fun setUp() {
        super.setUp()
        val id=UUID.randomUUID().toString()
        aliasPrefix="app.daiyu.android.test.lifecycle.$id"
        directory=File(instrumentation.targetContext.cacheDir,"activity-lifecycle-$id").apply {check(mkdir())}
        val isolated=testContext(instrumentation.targetContext,"activity-lifecycle-$id-")
        val context=object:ContextWrapper(isolated) {override fun getFilesDir()=directory}
        provider=Documents(File(directory,"import.json")).apply {
            attachInfo(instrumentation.targetContext,android.content.pm.ProviderInfo().apply {
                authority="activity-lifecycle";exported=false;grantUriPermissions=false
            })
        }
        val resolver=MockContentResolver(instrumentation.targetContext).apply {addProvider("activity-lifecycle",provider)}
        fixture=FixtureApplication(context,resolver)
        preferences=SelectionPreferences(fixture.getSharedPreferences("conversation-ui",0))
        controller=ChatController(fixture,ConversationDatabase(fixture),ModelSettingsStore(fixture,aliasPrefix),preferences,
            {modelCalls.incrementAndGet();error("no model request is permitted")})
        awaitState {controller.ready}
        assertNull(controller.problem)
        // A valid tiny text backup: if a stale confirmation runs, the provider and revision expose it.
        val historical=controller.database.create()
        controller.database.rename(historical.id,"historical fixture")
        File(directory,"import.json").writeText(controller.database.exportBackup())
        controller.database.delete(historical.id)
        application=instrumentation.targetContext.applicationContext as ChatApplication
        main {original=application.controller;application.controller=controller}
    }
    override fun tearDown() {
        try {
            main {activity?.finish()}
            instrumentation.waitForIdleSync()
            main {original?.let {application.controller=it}}
            controller.close()
            fixture.deleteDatabase("conversations.db")
            fixture.getSharedPreferences("conversation-ui",0).edit().clear().commit()
            fixture.getSharedPreferences("model-settings",0).edit().clear().commit()
            val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            java.util.Collections.list(keys.aliases()).filter {it==aliasPrefix || it.startsWith("$aliasPrefix.")}.forEach {keys.deleteEntry(it)}
            directory.deleteRecursively()
        } finally {super.tearDown()}
    }
    private fun start(open:Boolean=false):MainActivity {
        val intent=Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(open) intent.setAction(Intent.ACTION_VIEW).setDataAndType(uri,"application/json").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val current=instrumentation.startActivitySync(intent) as MainActivity
        activity=current
        instrumentation.waitForIdleSync()
        return current
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun input(current:MainActivity)=views(current.window.decorView).filterIsInstance<EditText>().single()
    private fun dialog(current:MainActivity,name:String="backupDialog")=
        MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(current) as? AlertDialog
    private fun recreate(current:MainActivity):MainActivity {
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        try {
            main {current.recreate()}
            val replacement=instrumentation.waitForMonitorWithTimeout(monitor,5000) as? MainActivity
            assertNotNull("framework recreation must resume a new Activity",replacement)
            activity=replacement
            instrumentation.waitForIdleSync()
            main {assertTrue(current.isDestroyed);assertNotSame(current,replacement)}
            return replacement!!
        } finally {instrumentation.removeMonitor(monitor)}
    }
    private fun importConfirmation(current:MainActivity):AlertDialog {
        main {
            val list=dialog(current)!!.listView
            assertTrue(list.isShown)
            list.performItemClick(list.getChildAt(1) ?: View(current),1,list.getItemIdAtPosition(1))
        }
        instrumentation.waitForIdleSync()
        var confirmation:AlertDialog?=null
        main {confirmation=dialog(current);assertNotNull(confirmation)}
        return confirmation!!
    }
    private fun assertNoImport(before:String) {
        // Flush AlertDialog's queued listener, then wait for any erroneously launched dataTask.
        instrumentation.waitForIdleSync()
        awaitState {!controller.dataBusy}
        assertEquals(0,provider.opens.get())
        assertEquals(0L,controller.dataRevision)
        assertEquals(before,controller.database.exportBackup())
        assertEquals(0,modelCalls.get())
    }

    fun testFailedSelectionPreferenceCommitKeepsInputAndDraftOwner() {
        val current=start()
        val a=controller.database.create()
        val b=controller.database.create()
        controller.database.draft(b.id,"B protected draft")
        main {
            current.select(a)
            input(current).setText("A draft");input(current).setSelection(1,3)
            preferences.failNextCommit=true
            try {current.select(b);fail("selection commit must reject")} catch(_:IllegalStateException) {}
            assertEquals("A draft",input(current).text.toString())
            assertEquals(1,input(current).selectionStart);assertEquals(3,input(current).selectionEnd)
            assertEquals(a.id.value,preferences.getString("selected",null))
            input(current).setText("A amended draft");input(current).setSelection(2,4)
            assertEquals("A amended draft",controller.database.conversations().single {it.id==a.id}.draft)
            assertEquals("B protected draft",controller.database.conversations().single {it.id==b.id}.draft)
            current.select(b)
            assertEquals("B protected draft",input(current).text.toString())
            current.select(a)
            assertEquals("A amended draft",input(current).text.toString())
            assertEquals(2,input(current).selectionStart);assertEquals(4,input(current).selectionEnd)
            // The same failure must not redirect an existing conversation's input into the new-chat draft.
            preferences.failNextCommit=true
            try {current.select(null);fail("new-chat selection commit must reject")} catch(_:IllegalStateException) {}
            input(current).setText("A still owned")
            assertEquals("",controller.database.draft())
            assertEquals("A still owned",controller.database.conversations().single {it.id==a.id}.draft)
        }
        assertEquals(0,modelCalls.get())
    }

    fun testQueuedImportConfirmationRejectsFinishBeforeListenerDelivery() {
        val current=start(open=true)
        val before=controller.database.exportBackup()
        val confirmation=importConfirmation(current)
        main {
            // AlertDialog posts its Builder listener to the main queue. Finish in the same turn,
            // before that listener runs: no artificial sleep or manually invoked lifecycle method.
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            current.finish()
            assertTrue(current.isFinishing)
        }
        assertNoImport(before)
    }

    fun testDestroyedImportDialogButtonCannotImportAfterFrameworkRecreation() {
        val current=start(open=true)
        val before=controller.database.exportBackup()
        val confirmation=importConfirmation(current)
        val positive=confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        val replacement=recreate(current)
        main {
            assertFalse(confirmation.isShowing)
            assertNull(dialog(replacement))
            // Exercise the retained result callback even though destruction dismissed the dialog.
            positive.performClick()
        }
        assertNoImport(before)
    }

    fun testCancelledImportConfirmationCannotAuthorizeRetainedPositiveCallback() {
        val current=start(open=true)
        val before=controller.database.exportBackup()
        val confirmation=importConfirmation(current)
        val positive=confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        main {confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()}
        instrumentation.waitForIdleSync()
        main {
            assertFalse(confirmation.isShowing)
            assertNull(dialog(current))
            positive.performClick()
        }
        assertNoImport(before)
    }

    fun testDestroyedModelSettingsSaveCannotWriteAfterFrameworkRecreation() {
        val current=start()
        var settingsDialog:AlertDialog?=null
        var save:Button?=null
        main {
            views(current.window.decorView).filterIsInstance<Button>().single {it.text.toString()=="模型设置"}.performClick()
            settingsDialog=dialog(current,"settingsDialog")
            val fields=views(settingsDialog!!.window!!.decorView).filterIsInstance<EditText>()
            fields.single {it.hint.toString().startsWith("HTTPS")}.setText("https://fixture.invalid")
            fields.single {it.hint.toString()=="模型标识"}.setText("fixture-model")
            fields.single {it.hint.toString().startsWith("API密钥")}.setText("synthetic-lifecycle-key")
            save=settingsDialog!!.getButton(AlertDialog.BUTTON_POSITIVE)
        }
        val before=HashMap(fixture.getSharedPreferences("model-settings",0).all)
        val replacement=recreate(current)
        main {
            assertFalse(settingsDialog!!.isShowing)
            assertNull(dialog(replacement,"settingsDialog"))
            save!!.performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(before,fixture.getSharedPreferences("model-settings",0).all)
        assertEquals(0,modelCalls.get())
    }
}
