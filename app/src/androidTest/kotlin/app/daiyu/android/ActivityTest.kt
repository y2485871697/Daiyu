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
    private val keyAliasPrefix="app.daiyu.android.test.credentials.${java.util.UUID.randomUUID()}"
    private fun settingsStore(context:android.content.Context)=ModelSettingsStore(context,keyAliasPrefix)
    override fun tearDown() {
        try {
            val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            java.util.Collections.list(keys.aliases()).filter {it==keyAliasPrefix || it.startsWith("$keyAliasPrefix.")}.forEach {keys.deleteEntry(it)}
        } finally {super.tearDown()}
    }

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
    private fun onMain(work:()->Unit) {
        var failure:Throwable?=null
        instrumentation.runOnMainSync {try {work()} catch (problem:Throwable) {failure=problem}}
        failure?.let {throw it}
    }
    private fun awaitScrollLayout(activity:MainActivity,scroll:android.widget.ScrollView,phase:String="scroll") {
        val pending=MainActivity::class.java.getDeclaredField("pendingScrollLayout").apply {isAccessible=true}
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        var ready=false
        var lastState="not sampled"
        while(!ready && System.nanoTime()<deadline) {
            awaitFrames()
            onMain {
                ready=scroll.height>0 && pending.get(activity)==null && !scroll.isLayoutRequested &&
                    !scroll.getChildAt(0).isLayoutRequested && !activity.window.decorView.isLayoutRequested
                lastState="height=${scroll.height}, pending=${pending.get(activity)!=null}, " +
                    "scrollLayout=${scroll.isLayoutRequested}, childLayout=${scroll.getChildAt(0).isLayoutRequested}, " +
                    "decorLayout=${activity.window.decorView.isLayoutRequested}"
            }
        }
        assertTrue("scroll layout and pending anchor restoration must finish: $phase, $lastState",ready)
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
        val controller=ChatController(application,db,settingsStore(context),context.getSharedPreferences("conversation-ui",0),{error("no model request")})
        val field=ChatApplication::class.java.getDeclaredField("controller").apply {isAccessible=true}
        var original:ChatController?=null
        var activity:MainActivity?=null
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!controller.ready && System.nanoTime()<deadline) Thread.sleep(10)
            assertTrue(controller.ready)
            onMain {original=field.get(application) as? ChatController;application.controller=controller}
            val current=start();activity=current
            onMain {current.select(conversation)}
            instrumentation.waitForIdleSync()
            val scroll=views(current.window.decorView).filterIsInstance<android.widget.ScrollView>().single()
            val following=MainActivity::class.java.getDeclaredField("followsBottom").apply {isAccessible=true}
            val scheduled=MainActivity::class.java.getDeclaredField("followScheduled").apply {isAccessible=true}
            val update=MainActivity::class.java.getDeclaredMethod("updateRow",Message::class.java).apply {isAccessible=true}
            val tail=db.messages(conversation.id).last().second
            onMain {
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
                val cancel=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_CANCEL,10f,10f,0)
                scroll.dispatchTouchEvent(cancel);cancel.recycle()
            }
            awaitScrollLayout(current,scroll,"after touch and 40-line update")
            onMain {
                assertEquals(0,scroll.scrollY)
                button(current,"回到底部").performClick()
            }
            awaitScrollLayout(current,scroll,"after return to bottom")
            onMain {
                assertTrue(following.getBoolean(current));assertTrue(scroll.scrollY>0)
                update.invoke(current,tail.copy(blocks=listOf(MessageBlock.Text("a","更多内容\n".repeat(80)))))
            }
            instrumentation.waitForIdleSync()
            awaitScrollLayout(current,scroll,"after 80-line update")
            var anchor=MessageId("not-selected")
            var anchorOffset=0
            val rowField=MainActivity::class.java.getDeclaredField("rows").apply {isAccessible=true}
            onMain {
                @Suppress("UNCHECKED_CAST") val rows=rowField.get(current) as Map<MessageId,android.widget.LinearLayout>
                val stableRow=rows.values.toList()[8]
                scroll.scrollTo(0,stableRow.top+10)
                val visible=rows.entries.first {it.value.bottom>scroll.scrollY}
                anchor=visible.key;anchorOffset=visible.value.top-scroll.scrollY
                button(current,"加载更早消息").performClick()
                assertFalse(following.getBoolean(current));assertFalse(scheduled.getBoolean(current))
                assertTrue(views(current.window.decorView).filterIsInstance<TextView>().any {it.text.toString().contains("历史用户 45")})

            }
            awaitScrollLayout(current,scroll,"after prepend")
            onMain {
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
            onMain {firstUser=shownUserId(current);startY=scroll.scrollY;current.jumpToMessage(firstUser)}
            val jump=MainActivity::class.java.getDeclaredField("jumpTarget").apply {isAccessible=true}
            val movingDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            var moved=false
            var cancelledY=0
            while(!moved && System.nanoTime()<movingDeadline) {
                onMain {
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
            onMain {assertEquals(cancelledY,scroll.scrollY);current.jumpToMessage(firstUser)}
            var distance=0
            var viewport=1
            onMain {distance=scroll.scrollY;viewport=scroll.height.coerceAtLeast(1)}
            val timeoutSeconds=(5L+distance.toLong()/(viewport*5L)).coerceAtMost(30L)
            val deadlineJump=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds)
            var finished=false
            while(!finished && System.nanoTime()<deadlineJump) {
                onMain {finished=jump.get(current)==null}
                if(!finished) Thread.sleep(10)
            }
            assertTrue("user message navigation must reach measured target",finished)
            onMain {
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
            onMain {assertEquals("cancelled prepend must not affect next conversation",0,scroll.scrollY)}
            onMain {
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
            onMain {activity?.finish()}
            instrumentation.waitForIdleSync()
            controller.close()
            onMain {field.set(application,original)}
        }
    }
    private fun shownUserId(activity:MainActivity):MessageId {
        val field=MainActivity::class.java.getDeclaredField("shown").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST") val shown=field.get(activity) as List<Pair<Long,Message>>
        return shown.filter {it.second.role=="user"}[1].second.id
    }
    fun testCredentialFailureCanBeReenteredFromSettings() {
        val application=instrumentation.targetContext.applicationContext as ChatApplication
        val context=testContext(instrumentation.targetContext,"credential-ui-${java.util.UUID.randomUUID()}-")
        val store=settingsStore(context)
        val config=ModelSettings("https://example.invalid/gateway","fixture","synthetic-key",false,"/responses",true)
        store.save(config)
        val prefs=context.getSharedPreferences("model-settings",0);prefs.edit().remove("iv").commit()
        val db=ConversationDatabase(context)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val controller=ChatController(application,db,store,context.getSharedPreferences("conversation-ui",0),{calls.incrementAndGet();error("no network")})
        var original:ChatController?=null
        var activity:MainActivity?=null
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!controller.ready && System.nanoTime()<deadline) Thread.sleep(10)
            assertTrue(controller.ready)
            instrumentation.runOnMainSync {original=application.controller;application.controller=controller}
            val current=start();activity=current
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                input(current).setText("保留草稿");button(current,"发送").performClick()
                assertEquals("保留草稿",input(current).text.toString());assertTrue(db.conversations().isEmpty());assertEquals(0,calls.get())
                button(current,"模型设置").performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialogField=MainActivity::class.java.getDeclaredField("settingsDialog").apply {isAccessible=true}
                val dialog=dialogField.get(current) as android.app.AlertDialog
                assertTrue(dialog.isShowing)
                val edits=views(dialog.window!!.decorView).filterIsInstance<EditText>()
                assertEquals(config.endpoint,edits[0].text.toString());assertEquals(config.responsesPath,edits[1].text.toString())
                assertEquals(config.model,edits[2].text.toString());assertEquals("",edits[3].text.toString())
                assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any {it.text.toString().contains("已保存密钥不可用")})
                edits[3].setText("replacement-key");dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(dialog.isShowing);assertEquals(config.copy(apiKey="replacement-key"),settingsStore(context).load())
                assertEquals("保留草稿",input(current).text.toString());assertEquals("保留草稿",db.draft());assertEquals(0,calls.get())
            }
        } finally {
            try {
                instrumentation.runOnMainSync {activity?.finish()}
                instrumentation.waitForIdleSync();controller.close()
            } finally {
                instrumentation.runOnMainSync {original?.let {application.controller=it}}
                prefs.edit().clear().commit();context.getSharedPreferences("conversation-ui",0).edit().clear().commit()
                context.deleteDatabase("conversations.db")
            }
        }
    }
    fun testDraftSlotsRunningSwitchAndActivityRecreation() {
        val application=instrumentation.targetContext.applicationContext as ChatApplication
        val controllerField=ChatApplication::class.java.getDeclaredField("controller").apply {isAccessible=true}
        var original:ChatController?=null
        var installed=false
        val context=testContext(instrumentation.targetContext)
        context.getSharedPreferences("model-settings",0).edit().clear().commit()
        context.deleteDatabase("conversations.db");context.getSharedPreferences("conversation-ui",0).edit().clear().commit()
        val db=ConversationDatabase(context);val settings=settingsStore(context)
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
    fun testImportDraftSurvivesBusyActivityReplacementAndSelection() {
        val application=instrumentation.targetContext.applicationContext as ChatApplication
        val context=testContext(instrumentation.targetContext,"backup-ui-${java.util.UUID.randomUUID()}-")
        val source=testContext(instrumentation.targetContext,"backup-ui-source-${java.util.UUID.randomUUID()}-")
        val db=ConversationDatabase(context);val from=ConversationDatabase(source)
        from.draft("导入的未创建草稿");val backup=from.exportBackup()
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val controller=ChatController(application,db,settingsStore(context),context.getSharedPreferences("conversation-ui",0),{calls.incrementAndGet();error("no network")})
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        var original:ChatController?=null;var activity:MainActivity?=null
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!controller.ready && System.nanoTime()<deadline) Thread.sleep(10)
            assertTrue(controller.ready)
            instrumentation.runOnMainSync {original=application.controller;application.controller=controller}
            val first=start();activity=first
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("",input(first).text.toString())
                controller.observe {if(!controller.dataBusy && controller.dataRevision>0) done.countDown()}
                controller.dataTask(importing=true,work={entered.countDown();check(release.await(10,TimeUnit.SECONDS));db.importBackup(backup);"合成导入完成"})
            }
            assertTrue(entered.await(5,TimeUnit.SECONDS));instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse(input(first).isEnabled)
                assertNull(controller.send(null,"busy attempt"))
                try {first.select(null);fail("busy selection must reject")} catch(_:IllegalStateException) {}
                first.finish()
            }
            instrumentation.waitForIdleSync()
            val replacement=start();activity=replacement
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse(input(replacement).isEnabled)
                controller.observe {if(!controller.dataBusy && controller.dataRevision>0) {
                    assertEquals("导入的未创建草稿",input(replacement).text.toString())
                    replacement.select(null)
                    assertEquals("导入的未创建草稿",db.draft())
                }}
            }
            release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(input(replacement).isEnabled)
                assertEquals("导入的未创建草稿",input(replacement).text.toString())
                button(replacement,"新建").performClick()
                assertEquals("导入的未创建草稿",input(replacement).text.toString())
                val conversation=db.create();db.organize(conversation.id,"测试文件夹",true)
                replacement.select(conversation);replacement.select(null)
                assertEquals("导入的未创建草稿",input(replacement).text.toString())
                assertTrue(db.conversations().single().pinned);assertEquals("测试文件夹",db.conversations().single().folder)
                assertEquals(0,calls.get())
            }
            val failed=CountDownLatch(1)
            instrumentation.runOnMainSync {
                controller.observe {if(!controller.dataBusy) failed.countDown()}
                controller.dataTask(work={error("synthetic failure")})
            }
            assertTrue(failed.await(5,TimeUnit.SECONDS));assertFalse(controller.dataBusy)
        } finally {
            release.countDown()
            instrumentation.runOnMainSync {activity?.finish()};instrumentation.waitForIdleSync()
            controller.close();from.close()
            instrumentation.runOnMainSync {application.controller=original!!}
            context.deleteDatabase("conversations.db");source.deleteDatabase("conversations.db")
        }
    }

    fun testProtocolSelectionClearsCredentialRequiresRebindAndSurvivesReopen() {
        val application=instrumentation.targetContext.applicationContext as ChatApplication
        val context=testContext(instrumentation.targetContext,"protocol-ui-${java.util.UUID.randomUUID()}-")
        val store=settingsStore(context)
        val config=ModelSettings("https://example.invalid","fixture","synthetic-responses-key",false,"/responses")
        store.save(config)
        val db=ConversationDatabase(context)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val controller=ChatController(application,db,store,context.getSharedPreferences("conversation-ui",0),{calls.incrementAndGet();error("no network")})
        var original:ChatController?=null
        var activity:MainActivity?=null
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!controller.ready && System.nanoTime()<deadline) Thread.sleep(10)
            assertTrue(controller.ready)
            instrumentation.runOnMainSync {original=application.controller;application.controller=controller}
            val current=start();activity=current
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                input(current).setText("协议切换保留草稿")
                button(current,"模型设置").performClick()
            }
            instrumentation.waitForIdleSync()
            val dialogField=MainActivity::class.java.getDeclaredField("settingsDialog").apply {isAccessible=true}
            instrumentation.runOnMainSync {
                val dialog=dialogField.get(current) as android.app.AlertDialog
                val nodes=views(dialog.window!!.decorView)
                val edits=nodes.filterIsInstance<EditText>()
                assertEquals(5,edits.size)
                assertEquals(config.apiKey,edits[3].text.toString())
                nodes.filterIsInstance<android.widget.RadioButton>().single {it.text.toString()=="Anthropic Messages"}.performClick()
                assertEquals("",edits[3].text.toString());assertEquals("",edits[1].text.toString())
                assertFalse(edits[1].isEnabled)
                edits[3].setText("synthetic-messages-key")
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing);assertEquals(config,store.load())
                nodes.filterIsInstance<android.widget.CheckBox>().single {it.text.toString().contains("确认将其绑定")}.isChecked=true
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(dialog.isShowing)
                val messages=store.load()
                assertEquals(app.daiyu.android.model.ProviderProtocol.MESSAGES,messages.protocol)
                assertEquals("synthetic-messages-key",messages.apiKey);assertEquals("",messages.responsesPath)
                assertTrue(app.daiyu.android.model.ModelFactory.create(messages) is app.daiyu.android.model.MessagesModel)
                assertEquals(messages,settingsStore(context).load())
                button(current,"模型设置").performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialog=dialogField.get(current) as android.app.AlertDialog
                val nodes=views(dialog.window!!.decorView)
                val edits=nodes.filterIsInstance<EditText>()
                assertTrue(nodes.filterIsInstance<android.widget.RadioButton>().single {it.text.toString()=="Anthropic Messages"}.isChecked)
                assertFalse(edits[1].isEnabled)
                nodes.filterIsInstance<android.widget.RadioButton>().single {it.text.toString()=="OpenAI Responses"}.performClick()
                assertEquals("",edits[3].text.toString());assertTrue(edits[1].isEnabled)
                edits[1].setText("/responses");edits[3].setText("explicit-responses-key")
                nodes.filterIsInstance<android.widget.CheckBox>().single {it.text.toString().contains("确认将其绑定")}.isChecked=true
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(dialog.isShowing)
                assertEquals(config.copy(apiKey="explicit-responses-key"),store.load())
                assertFalse(context.getSharedPreferences("model-settings",0).contains("protocol"))
                assertEquals("协议切换保留草稿",input(current).text.toString())
                assertTrue(db.conversations().isEmpty());assertEquals(0,calls.get())
            }
        } finally {
            try {
                instrumentation.runOnMainSync {activity?.finish()}
                instrumentation.waitForIdleSync();controller.close()
            } finally {
                instrumentation.runOnMainSync {original?.let {application.controller=it}}
                context.getSharedPreferences("model-settings",0).edit().clear().commit()
                context.getSharedPreferences("conversation-ui",0).edit().clear().commit()
                context.deleteDatabase("conversations.db")
            }
        }
    }

}
