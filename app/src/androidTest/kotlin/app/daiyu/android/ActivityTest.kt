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
    private fun awaitFrames(count:Int=2) {
        val ready=CountDownLatch(1)
        instrumentation.runOnMainSync {
            var remaining=count
            val callback=object:android.view.Choreographer.FrameCallback {
                override fun doFrame(time:Long) {
                    if(--remaining==0) ready.countDown() else android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(callback)
        }
        assertTrue("frame callbacks must run",ready.await(5,TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }
    private fun start()=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun testStreamingFollowStopsOnTouchAndEarlierHistory() {
        val application=instrumentation.targetContext.applicationContext as ChatApplication
        val context=testContext(instrumentation.targetContext)
        context.deleteDatabase("conversations.db")
        context.getSharedPreferences("conversation-ui",0).edit().clear().commit()
        val db=ConversationDatabase(context)
        val conversation=db.create()
        repeat(70) {i ->
            val run=RunId("scroll-$i")
            val user=Message(MessageId("su-$i"),conversation.id,run,"user",listOf(MessageBlock.Text("u","历史用户 $i")),RunStatus.COMPLETED)
            val assistant=Message(MessageId("sa-$i"),conversation.id,run,"assistant",listOf(MessageBlock.Text("a",("历史正文 $i\n").repeat(12))),RunStatus.RUNNING)
            db.send(user,assistant);db.save(assistant.copy(status=RunStatus.COMPLETED))
        }
        val controller=ChatController(application,db,ModelSettingsStore(context),context.getSharedPreferences("conversation-ui",0),{error("no model request")})
        val field=ChatApplication::class.java.getDeclaredField("controller").apply {isAccessible=true}
        var original:ChatController?=null
        var activity:MainActivity?=null
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!controller.ready && System.nanoTime()<deadline) Thread.sleep(10)
            assertTrue(controller.ready)
            instrumentation.runOnMainSync {original=field.get(application) as? ChatController;application.controller=controller}
            val current=start();activity=current
            instrumentation.runOnMainSync {current.select(conversation)}
            instrumentation.waitForIdleSync()
            val scroll=views(current.window.decorView).filterIsInstance<android.widget.ScrollView>().single()
            val following=MainActivity::class.java.getDeclaredField("followsBottom").apply {isAccessible=true}
            val scheduled=MainActivity::class.java.getDeclaredField("followScheduled").apply {isAccessible=true}
            val update=MainActivity::class.java.getDeclaredMethod("updateRow",Message::class.java).apply {isAccessible=true}
            val tail=db.messages(conversation.id).last().second
            instrumentation.runOnMainSync {
                assertTrue(scroll.scrollY>0)
                val firstUser=db.messages(conversation.id).map {it.second}.first {it.role=="user"}
                current.jumpToMessage(firstUser.id)
                assertFalse(following.getBoolean(current))
                val now=android.os.SystemClock.uptimeMillis()
                val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,10f,10f,0)
                scroll.dispatchTouchEvent(down);down.recycle()
                scroll.scrollTo(0,0)
                update.invoke(current,tail.copy(blocks=listOf(MessageBlock.Text("a","新内容\n".repeat(40)))))
                assertFalse(following.getBoolean(current));assertFalse(scheduled.getBoolean(current));assertEquals(0,scroll.scrollY)
                val jump=MainActivity::class.java.getDeclaredField("jumpTarget").apply {isAccessible=true}
                assertNull(jump.get(current))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(0,scroll.scrollY)
                button(current,"回到底部").performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(following.getBoolean(current));assertTrue(scroll.scrollY>0)
                update.invoke(current,tail.copy(blocks=listOf(MessageBlock.Text("a","更多内容\n".repeat(80)))))
            }
            instrumentation.waitForIdleSync()
            var anchor=MessageId("not-selected")
            var anchorOffset=0
            val rowField=MainActivity::class.java.getDeclaredField("rows").apply {isAccessible=true}
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST") val rows=rowField.get(current) as Map<MessageId,android.widget.LinearLayout>
                val visible=rows.entries.first {it.value.bottom>scroll.scrollY}
                anchor=visible.key;anchorOffset=visible.value.top-scroll.scrollY
                button(current,"加载更早消息").performClick()
                assertFalse(following.getBoolean(current));assertFalse(scheduled.getBoolean(current))
                assertTrue(views(current.window.decorView).filterIsInstance<TextView>().any {it.text.toString().contains("历史用户 45")})

            }
            awaitFrames()
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST") val rows=rowField.get(current) as Map<MessageId,android.widget.LinearLayout>
                assertEquals("prepend must preserve visible message offset",anchorOffset,rows.getValue(anchor).top-scroll.scrollY)
                val shownField=MainActivity::class.java.getDeclaredField("shown").apply {isAccessible=true}
                @Suppress("UNCHECKED_CAST")
                val shown=shownField.get(current) as MutableList<Pair<Long,Message>>
                val count=shown.size
                assertTrue(count>50)
                val index=shown.indexOfFirst {it.second.id==tail.id}
                shown[index]=shown[index].first to shown[index].second.copy(status=RunStatus.RUNNING)
                val before=scroll.scrollY
                MainActivity::class.java.getDeclaredMethod("refresh").apply {isAccessible=true}.invoke(current)
                @Suppress("UNCHECKED_CAST")
                val refreshed=shownField.get(current) as MutableList<Pair<Long,Message>>
                assertEquals(count,refreshed.size)
                assertEquals(RunStatus.COMPLETED,refreshed[index].second.status)
                assertEquals(before,scroll.scrollY)
                assertFalse(following.getBoolean(current))
                val copy=views(scroll).filterIsInstance<Button>().first {it.text.toString()=="复制"}
                assertTrue(copy.width>0 && copy.height>0)
                scroll.scrollTo(0,copy.top)
                val location=IntArray(2);copy.getLocationOnScreen(location)
                val scrollLocation=IntArray(2);scroll.getLocationOnScreen(scrollLocation)
                following.setBoolean(current,true)
                val now=android.os.SystemClock.uptimeMillis()
                val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,
                    (location[0]-scrollLocation[0]+copy.width/2).toFloat(),(location[1]-scrollLocation[1]+copy.height/2).toFloat(),0)
                scroll.dispatchTouchEvent(down);down.recycle()
                assertFalse(following.getBoolean(current));assertFalse(scheduled.getBoolean(current))
                val cancel=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_CANCEL,10f,10f,0)
                scroll.dispatchTouchEvent(cancel);cancel.recycle()
                scroll.scrollTo(0,Int.MAX_VALUE)
                assertFalse(following.getBoolean(current))
                button(current,"回到底部").performClick()
                assertTrue(following.getBoolean(current))
            }
            var firstUser=MessageId("not-selected")
            var startY=0
            instrumentation.runOnMainSync {firstUser=shownUserId(current);startY=scroll.scrollY;current.jumpToMessage(firstUser)}
            val jump=MainActivity::class.java.getDeclaredField("jumpTarget").apply {isAccessible=true}
            val movingDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            var moved=false
            var cancelledY=0
            while(!moved && System.nanoTime()<movingDeadline) {
                instrumentation.runOnMainSync {
                    moved=scroll.scrollY!=startY && jump.get(current)!=null
                    if(moved) {
                        val now=android.os.SystemClock.uptimeMillis()
                        val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,10f,10f,0)
                        scroll.dispatchTouchEvent(down);down.recycle()
                        val cancel=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_CANCEL,10f,10f,0)
                        scroll.dispatchTouchEvent(cancel);cancel.recycle()
                        cancelledY=scroll.scrollY;assertNull(jump.get(current))
                    }
                }
                if(!moved) Thread.sleep(10)
            }
            assertTrue("navigation must move before cancellation",moved)
            awaitFrames(3)
            instrumentation.runOnMainSync {assertEquals(cancelledY,scroll.scrollY);current.jumpToMessage(firstUser)}
            var distance=0
            var viewport=1
            instrumentation.runOnMainSync {distance=scroll.scrollY;viewport=scroll.height.coerceAtLeast(1)}
            val timeoutSeconds=(5L+distance.toLong()/(viewport*5L)).coerceAtMost(30L)
            val deadlineJump=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds)
            var finished=false
            while(!finished && System.nanoTime()<deadlineJump) {
                instrumentation.runOnMainSync {finished=jump.get(current)==null}
                if(!finished) Thread.sleep(10)
            }
            assertTrue("user message navigation must reach measured target",finished)
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST") val rows=rowField.get(current) as Map<MessageId,android.widget.LinearLayout>
                assertTrue(rows.getValue(firstUser).top>0)
                assertEquals(rows.getValue(firstUser).top,scroll.scrollY)
                assertFalse(following.getBoolean(current))
                try {current.jumpToMessage(tail.id);fail("assistant cannot be navigation target")} catch (_:IllegalStateException) {}
                button(current,"加载更早消息").performClick()
                current.select(null)
                val pending=MainActivity::class.java.getDeclaredField("pendingScrollLayout").apply {isAccessible=true}
                assertNull(pending.get(current))
            }
            awaitFrames(3)
            instrumentation.runOnMainSync {assertEquals("cancelled prepend must not affect next conversation",0,scroll.scrollY)}
            instrumentation.runOnMainSync {
                current.select(conversation)
                button(current,"加载更早消息").performClick()
                current.jumpToMessage(shownUserId(current))
                val pending=MainActivity::class.java.getDeclaredField("pendingScrollLayout").apply {isAccessible=true}
                assertNull(pending.get(current))
                val pause=MainActivity::class.java.getDeclaredMethod("onPause").apply {isAccessible=true}
                pause.invoke(current)
                assertNull(jump.get(current))
                assertNull(pending.get(current))
            }
        } finally {
            instrumentation.runOnMainSync {activity?.finish()}
            instrumentation.waitForIdleSync()
            controller.close()
            instrumentation.runOnMainSync {field.set(application,original)}
        }
    }
    private fun shownUserId(activity:MainActivity):MessageId {
        val field=MainActivity::class.java.getDeclaredField("shown").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST") val shown=field.get(activity) as List<Pair<Long,Message>>
        return shown.filter {it.second.role=="user"}[1].second.id
    }
    fun testDraftSlotsRunningSwitchAndActivityRecreation() {
        val application=instrumentation.targetContext.applicationContext as ChatApplication
        val controllerField=ChatApplication::class.java.getDeclaredField("controller").apply {isAccessible=true}
        var original:ChatController?=null
        var installed=false
        val context=testContext(instrumentation.targetContext)
        context.getSharedPreferences("model-settings",0).edit().clear().commit()
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
            instrumentation.runOnMainSync {
                original=controllerField.get(application) as? ChatController
                application.controller=controller
                installed=true
            }
            val first=start();activity=first
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val expected=highestSupportedRefreshRate(first.window.decorView.display?.supportedModes?.map {it.refreshRate}.orEmpty())
                assertTrue("The attached display must report a usable refresh rate",expected>0f)
                assertEquals(expected,first.window.attributes.preferredRefreshRate,0f)
                val resume=MainActivity::class.java.getDeclaredMethod("onResume").apply {isAccessible=true}
                val pause=MainActivity::class.java.getDeclaredMethod("onPause").apply {isAccessible=true}
                pause.invoke(first)
                val zero=first.window.attributes
                zero.preferredRefreshRate=0f
                first.window.attributes=zero
                resume.invoke(first)
                pause.invoke(first)
                assertEquals(0f,first.window.attributes.preferredRefreshRate,0f)
                val attributes=first.window.attributes
                attributes.preferredRefreshRate=13f
                first.window.attributes=attributes
                resume.invoke(first)
                assertEquals(expected,first.window.attributes.preferredRefreshRate,0f)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val pause=MainActivity::class.java.getDeclaredMethod("onPause").apply {isAccessible=true}
                val resume=MainActivity::class.java.getDeclaredMethod("onResume").apply {isAccessible=true}
                val preferredMode=first.window.attributes.preferredDisplayModeId
                pause.invoke(first)
                assertEquals(13f,first.window.attributes.preferredRefreshRate,0f)
                assertEquals(preferredMode,first.window.attributes.preferredDisplayModeId)
                resume.invoke(first)
                val external=first.window.attributes
                external.preferredRefreshRate=17f
                first.window.attributes=external
                pause.invoke(first)
                assertEquals(17f,first.window.attributes.preferredRefreshRate,0f)
                resume.invoke(first)
                pause.invoke(first)
                assertEquals(17f,first.window.attributes.preferredRefreshRate,0f)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(17f,first.window.attributes.preferredRefreshRate,0f)
                MainActivity::class.java.getDeclaredMethod("onResume").apply {isAccessible=true}.invoke(first)
            }
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
            try {
                try {
                    instrumentation.runOnMainSync {activity?.finish()}
                    instrumentation.waitForIdleSync()
                } finally {controller.close()}
            } finally {
                instrumentation.runOnMainSync {if(installed) controllerField.set(application,original)}
                context.getSharedPreferences("model-settings",0).edit().clear().commit()
            }
        }
    }
}
