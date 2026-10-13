package app.daiyu.android

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import app.daiyu.android.domain.*
import app.daiyu.android.storage.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class ActivityTest:InstrumentationTestCase() {
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun input(activity:MainActivity)=views(activity.window.decorView).filterIsInstance<EditText>().single()
    private fun button(activity:MainActivity,text:String)=views(activity.window.decorView).filterIsInstance<Button>().single {it.text.toString()==text}
    private fun start()=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun testDraftSlotsRunningSwitchAndActivityRecreation() {
        val application=instrumentation.targetContext.applicationContext as ChatApplication
        val original=application.controller
        val context=testContext(instrumentation.targetContext)
        context.deleteDatabase("conversations.db");context.getSharedPreferences("conversation-ui",0).edit().clear().commit()
        val db=ConversationDatabase(context);val settings=ModelSettingsStore(context)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val terminal=CountDownLatch(1)
        val model=ModelPort {object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                entered.countDown();check(release.await(10,TimeUnit.SECONDS));sink(ModelEvent.TextDelta("m","A回复"))
            }
            override fun cancel() {release.countDown()}
        }}
        val controller=ChatController(application,db,settings,context.getSharedPreferences("conversation-ui",0),{model})
        var activity:MainActivity?=null
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!controller.ready && System.nanoTime()<deadline) Thread.sleep(10)
            assertTrue(controller.ready)
            instrumentation.runOnMainSync {application.controller=controller}
            val first=start();activity=first
            instrumentation.runOnMainSync {
                assertTrue(db.conversations().isEmpty())
                input(first).setText("未创建草稿");input(first).setSelection(2,4)
                button(first,"发送").performClick()
                assertEquals("未创建草稿",input(first).text.toString());assertTrue(db.conversations().isEmpty())
            }
            val a=db.create();val b=db.create()
            instrumentation.runOnMainSync {
                first.select(a);input(first).setText("A草稿");input(first).setSelection(1,2)
                first.select(b);input(first).setText("B草稿");input(first).setSelection(0,1)
                button(first,"新建").performClick();assertEquals("未创建草稿",input(first).text.toString())
                assertEquals(2,input(first).selectionStart);assertEquals(4,input(first).selectionEnd)
                first.select(a);assertEquals("A草稿",input(first).text.toString());assertEquals(1,input(first).selectionStart)
                settings.save(ModelSettings("https://example.invalid/v1/responses","fixture","synthetic-key",false))
                button(first,"发送").performClick()
                input(first).setText("A下一条")
                first.select(b);assertEquals("B草稿",input(first).text.toString());assertFalse(button(first,"发送").isEnabled)
                input(first).setText("B新稿");input(first).setSelection(1,2)
                controller.observe {if(controller.active==null) terminal.countDown()}
            }
            assertTrue(entered.await(5,TimeUnit.SECONDS));release.countDown();assertTrue(terminal.await(5,TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals("B新稿",input(first).text.toString());assertEquals(1,input(first).selectionStart)
                assertFalse(views(first.window.decorView).filterIsInstance<TextView>().any {it.text.toString()=="A回复"})
                assertTrue(db.messages(b.id).isEmpty())
                first.finish()
            }
            instrumentation.waitForIdleSync()
            val second=start();activity=second
            instrumentation.runOnMainSync {
                assertEquals("B新稿",input(second).text.toString());assertEquals(1,input(second).selectionStart)
                second.select(a);assertEquals("A下一条",input(second).text.toString())
                assertTrue(views(second.window.decorView).filterIsInstance<TextView>().any {it.text.toString()=="A回复"})
                button(second,"新建").performClick();assertEquals("未创建草稿",input(second).text.toString())
                assertEquals(2,db.conversations().size)
            }
        } finally {
            release.countDown()
            instrumentation.runOnMainSync {activity?.finish()}
            instrumentation.waitForIdleSync()
            controller.close()
            instrumentation.runOnMainSync {application.controller=original}
        }
    }
}
