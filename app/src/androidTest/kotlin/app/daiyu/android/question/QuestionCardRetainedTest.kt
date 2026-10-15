package app.daiyu.android.question

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.*
import app.daiyu.android.MainActivity
import app.daiyu.android.domain.question.*
import java.util.ArrayDeque
import java.util.concurrent.Executor

@Suppress("DEPRECATION")
class QuestionCardRetainedTest:InstrumentationTestCase() {
    private val owner=QuestionIdentity("conversation","run","owner","call")
    private class Queue:Executor {
        private val work=ArrayDeque<Runnable>()
        var enqueued=0;private set
        override fun execute(command:Runnable) {synchronized(work) {enqueued++;work.addLast(command)}}
        fun drain() {while(true) {(synchronized(work) {work.pollFirst()} ?: return).run()}}
    }
    private class Store:QuestionStore {
        val rows=linkedMapOf<String,QuestionRecord>()
        var failure:Exception?=null
        var attempts=0;var commits=0
        override fun get(callId:String)=rows[callId]
        override fun records()=rows.values.toList()
        override fun create(record:QuestionRecord):Boolean {if(record.callId in rows) return false;rows[record.callId]=record;return true}
        override fun answer(callId:String,answer:QuestionAnswer):Boolean {
            attempts++;failure?.let {throw it}
            val old=rows[callId] ?: return false
            if(old.answer!=null || old.interrupted) return false
            rows[callId]=old.copy(answer=answer);commits++;return true
        }
        override fun interruptPending()=interruptPending("")
        override fun interruptPending(ownerPrefix:String) {rows.replaceAll {key,row->if(key.startsWith(ownerPrefix) && row.answer==null) row.copy(interrupted=true) else row}}
    }
    private inner class Fixture {
        val store=Store();val writer=Queue();val registry=QuestionRegistry(store,owner)
        val pending=registry.barrier.register(owner.storageKey,UserQuestion("Title","Choose",listOf(QuestionOption("one","First"),QuestionOption("two","Second")),"one",true,true,true))
        val binding=QuestionCardBinding(registry,owner,writer) {true}
    }
    private fun main(work:()->Unit) {
        var failure:Throwable?=null
        instrumentation.runOnMainSync {try {work()} catch(t:Throwable) {failure=t}}
        failure?.let {throw it}
    }
    private fun fixture(work:(Fixture)->Unit) {
        val f=Fixture()
        try {work(f)} finally {try {f.writer.drain();instrumentation.waitForIdleSync()} finally {f.registry.close()}}
    }
    private fun children(c:QuestionCardView)=(0 until c.childCount).map(c::getChildAt)
    private fun group(c:QuestionCardView)=children(c).filterIsInstance<RadioGroup>().single()
    private fun send(c:QuestionCardView)=children(c).filterIsInstance<Button>().single()
    private fun field(c:QuestionCardView,hint:String)=children(c).filterIsInstance<EditText>().single {it.hint.toString()==hint}
    private fun text(c:QuestionCardView)=children(c).filterIsInstance<TextView>().joinToString("\n") {it.text.toString()}
    private fun choose(c:QuestionCardView,index:Int) {val g=group(c);g.check(g.getChildAt(index).id)}
    private fun chosen(c:QuestionCardView,index:Int) {assertEquals(group(c).getChildAt(index).id,group(c).checkedRadioButtonId)}
    private fun card(f:Fixture)=QuestionCardView(instrumentation.targetContext).apply {bind(owner,f.pending,true,f.binding)}
    private fun saved(c:QuestionCardView,summary:String) {assertTrue(children(c).none {it is Button});assertTrue(text(c).contains(summary))}
    fun testSameBindingNewViewsRetainOptionOtherAndNote()=fixture {f ->
        lateinit var replacement:QuestionCardView
        main {
            val first=card(f);assertEquals(-1,group(first).checkedRadioButtonId)
            choose(first,2);field(first,"其他答案").setText("保留的其他答案");field(first,"补充说明（可选）").setText("保留说明");choose(first,1)
            val second=card(f);chosen(second,1);assertEquals("保留的其他答案",field(second,"其他答案").text.toString());assertEquals(View.GONE,field(second,"其他答案").visibility)
            choose(second,2);replacement=card(f);chosen(replacement,2)
            assertEquals("保留说明",field(replacement,"补充说明（可选）").text.toString());assertEquals("保留的其他答案",field(replacement,"其他答案").text.toString())
        }
        assertEquals(0,f.writer.enqueued)
        main {send(replacement).performClick()};f.writer.drain();instrumentation.waitForIdleSync()
        assertEquals(QuestionAnswer.Other("保留的其他答案","保留说明"),f.store.get(owner.storageKey)!!.answer)
        assertEquals(1,f.store.attempts);assertEquals(1,f.store.commits);main {saved(replacement,"其他：保留的其他答案")}
    }
    fun testNewViewWhileSavingEnqueuesAndWritesOnlyOnce()=fixture {f ->
        lateinit var replacement:QuestionCardView
        main {
            val first=card(f);choose(first,1);field(first,"补充说明（可选）").setText("一次")
            send(first).performClick();send(first).performClick();replacement=card(f)
            chosen(replacement,1);assertFalse(send(replacement).isEnabled);assertTrue(text(replacement).contains("正在保存答案"));send(replacement).performClick()
        }
        assertEquals(1,f.writer.enqueued);assertEquals(0,f.store.attempts)
        f.writer.drain();instrumentation.waitForIdleSync()
        assertEquals(1,f.store.attempts);assertEquals(1,f.store.commits)
        assertEquals(QuestionAnswer.Selected("two","一次"),f.store.get(owner.storageKey)!!.answer)
        main {saved(replacement,"已选择：Second");replacement.bind(owner,f.pending,true,f.binding);saved(replacement,"已选择：Second");saved(card(f),"已选择：Second")}
        assertEquals(1,f.writer.enqueued)
    }
    fun testCompletionAfterRealOldViewDetachUpdatesReplacement()=fixture {f ->
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var old:QuestionCardView;lateinit var replacement:QuestionCardView
        try {
            main {
                val host=FrameLayout(activity);activity.findViewById<ViewGroup>(android.R.id.content).addView(host)
                old=QuestionCardView(activity);host.addView(old);old.bind(owner,f.pending,true,f.binding)
                assertTrue(old.isAttachedToWindow);choose(old,0);send(old).performClick();host.removeView(old);assertFalse(old.isAttachedToWindow)
                replacement=QuestionCardView(activity);host.addView(replacement);replacement.bind(owner,f.pending,true,f.binding)
                assertTrue(replacement.isAttachedToWindow);assertFalse(send(replacement).isEnabled)
            }
            f.writer.drain();instrumentation.waitForIdleSync()
            main {saved(replacement,"已选择：First");assertFalse(old.isAttachedToWindow);assertFalse(send(old).isEnabled);assertTrue(text(old).contains("正在保存答案"))}
            assertEquals(1,f.writer.enqueued);assertEquals(1,f.store.attempts);assertEquals(1,f.store.commits)
        } finally {main {activity.finish()}}
    }
    fun testTypedRejectedCanRetryAfterNewView()=fixture {f ->
        lateinit var first:QuestionCardView;lateinit var replacement:QuestionCardView
        f.store.failure=QuestionException(QuestionFailure.INVALID_ANSWER)
        main {first=card(f);choose(first,0);field(first,"补充说明（可选）").setText("保留说明");send(first).performClick()}
        f.writer.drain();instrumentation.waitForIdleSync();assertEquals(0,f.store.commits)
        main {assertTrue(send(first).isEnabled);replacement=card(f);chosen(replacement,0);assertTrue(send(replacement).isEnabled);assertTrue(text(replacement).contains("答案未被接受"));assertEquals("保留说明",field(replacement,"补充说明（可选）").text.toString())}
        assertEquals(1,f.writer.enqueued);f.store.failure=null
        main {choose(replacement,1);send(replacement).performClick();send(replacement).performClick()}
        f.writer.drain();instrumentation.waitForIdleSync();assertEquals(2,f.store.attempts);assertEquals(1,f.store.commits);assertEquals(2,f.writer.enqueued)
        main {saved(replacement,"已选择：Second")}
    }
    fun testUnconfirmedRemainsBlockedAcrossNewViewsAndDirectBindingSubmit()=fixture {f ->
        lateinit var first:QuestionCardView
        f.store.failure=IllegalStateException("result unavailable")
        main {first=card(f);choose(first,1);send(first).performClick()};f.writer.drain();instrumentation.waitForIdleSync()
        assertEquals(1,f.store.attempts);assertEquals(0,f.store.commits)
        f.store.failure=null
        main {
            repeat(2) {val replacement=card(f);chosen(replacement,1);assertFalse(send(replacement).isEnabled);assertTrue(text(replacement).contains("提交结果未确认"));send(replacement).performClick()}
            f.binding.submit(owner,QuestionAnswer.Selected("two")) {assertEquals(QuestionCardView.Reply.UNCONFIRMED,it)}
        }
        f.writer.drain();instrumentation.waitForIdleSync();assertEquals(1,f.writer.enqueued);assertEquals(1,f.store.attempts);assertNull(f.store.get(owner.storageKey)!!.answer)
    }
}
