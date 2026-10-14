package app.daiyu.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.View
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.widget.*
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings

class MainActivity:Activity() {
    private val controller get()=(application as ChatApplication).controller
    private lateinit var root:LinearLayout
    private lateinit var timeline:LinearLayout
    private lateinit var scroll:ScrollView
    private lateinit var input:EditText
    private lateinit var status:TextView
    private lateinit var send:Button
    private lateinit var stop:Button
    private var settingsDialog:AlertDialog?=null
    private var jumpTarget:MessageId?=null
    private var turnMotion:ConversationTurnMotion?=null
    private var jumpTime=0L
    private var jumpRemainder=0f
    private var pendingScrollLayout:ViewTreeObserver.OnPreDrawListener?=null
    private val turnFrame=Choreographer.FrameCallback {time -> advanceTurnJump(time)}
    private val bottomMotion=BottomFollowMotion()
    private var followsBottom=true
    private var followScheduled=false
    private var followRemainder=0f
    private val followFrame=Choreographer.FrameCallback {time ->
        followScheduled=false
        if(followsBottom && refreshRateWindowResumed) {
            val distance=(timeline.height-scroll.height+scroll.paddingTop+scroll.paddingBottom-scroll.scrollY).coerceAtLeast(0)
            if(distance>0) {
                val movement=bottomMotion.step(distance.toFloat(),time,resources.displayMetrics.density)+followRemainder
                val pixels=movement.toInt().coerceAtMost(distance)
                followRemainder=movement-pixels
                scroll.scrollBy(0,pixels)
                if(isAtBottom()) cancelBottomFollow() else scheduleBottomFollow()
            } else cancelBottomFollow()
        }
    }
    private val initialBottom=Runnable {if(followsBottom && refreshRateWindowResumed) scroll.scrollTo(0,(timeline.height-scroll.height+scroll.paddingTop+scroll.paddingBottom).coerceAtLeast(0))}
    private var selected:Conversation?=null
    private var editing=false
    private var selectionReady=false
    private var shown=mutableListOf<Pair<Long,Message>>()
    private val rows=mutableMapOf<MessageId,LinearLayout>()
    private val expandedTools=mutableSetOf<ToolCallId>()
    private val selections get()=controller.selections
    private val listener:()->Unit={refresh()}
    private var refreshRateWindowResumed=false
    private var previousPreferredRefreshRate:Float?=null
    private var requestedPreferredRefreshRate:Float?=null
    private val refreshRateRequest=Runnable {applyPreferredRefreshRate()}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val padding=(12*resources.displayMetrics.density).toInt()
        root.setPadding(padding,padding,padding,padding)
        root.setOnApplyWindowInsetsListener {v,insets ->
            @Suppress("DEPRECATION")
            v.setPadding(padding+insets.systemWindowInsetLeft,padding+insets.systemWindowInsetTop,padding+insets.systemWindowInsetRight,padding+insets.systemWindowInsetBottom);insets
        }
        root.addView(TextView(this).apply {text="代鱼 · 聊天";textSize=24f})
        val toolbar=LinearLayout(this)
        fun button(label:String,action:()->Unit)=Button(this).apply {text=label;setOnClickListener {guard(action)}}
        toolbar.addView(button("会话") {chooseConversation()});toolbar.addView(button("新建") {select(null)})
        toolbar.addView(button("模型设置") {modelSettings()});root.addView(toolbar)
        status=TextView(this);root.addView(status)
        root.addView(button("加载更早消息") {loadEarlier()})
        root.addView(button("定位用户消息") {chooseUserMessage()})
        timeline=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        scroll=object:ScrollView(this) {
            override fun dispatchTouchEvent(event:MotionEvent):Boolean {
                if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                    cancelTurnJump()
                    followsBottom=false
                    removeCallbacks(initialBottom)
                    cancelBottomFollow()
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {addView(timeline);isSmoothScrollingEnabled=false}
        scroll.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_ -> scheduleBottomFollow()}
        timeline.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_ -> scheduleBottomFollow()}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        input=EditText(this).apply {hint="输入消息";minLines=2;maxLines=5;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES}
        input.isSaveEnabled=false
        root.addView(input)
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                if(!editing) guard {val id=selected?.id;if(id==null) controller.database.draft(s.toString()) else controller.database.draft(id,s.toString())}
            }
            override fun afterTextChanged(s:Editable?) {}
        })
        val actions=LinearLayout(this)
        send=button("发送") {
            val saved=controller.send(selected,input.text.toString())
            if(saved!=null) {
                selected=saved
                val selectionSaved=controller.preferences.edit().putString("selected",saved.id.value).commit()
                editing=true;input.setText("");editing=false
                shown=controller.database.messages(saved.id).toMutableList();renderAll();jumpToBottom()
                if(!selectionSaved) toast("消息已保存；最后选中会话未保存")
            }
        }
        stop=button("停止") {controller.stop();status.text="正在停止；不代表远端已取消"}
        actions.addView(send);actions.addView(stop);actions.addView(button("回到底部") {jumpToBottom()})
        root.addView(actions);setContentView(root);root.requestApplyInsets()
        controller.observe(listener);refresh()
    }
    override fun onResume() {
        super.onResume()
        refreshRateWindowResumed=true
        applyPreferredRefreshRate()
        window.decorView.post(refreshRateRequest)
        scheduleBottomFollow()
    }
    private fun applyPreferredRefreshRate() {
        if(!refreshRateWindowResumed || isFinishing || isDestroyed) return
        val rate=highestSupportedRefreshRate(window.decorView.display?.supportedModes?.map {it.refreshRate}.orEmpty())
        if(rate<=0f) return
        val attributes=window.attributes
        if(attributes.preferredRefreshRate==rate) return
        if(previousPreferredRefreshRate==null) previousPreferredRefreshRate=attributes.preferredRefreshRate
        requestedPreferredRefreshRate=rate
        attributes.preferredRefreshRate=rate
        window.attributes=attributes
    }
    override fun onPause() {
        refreshRateWindowResumed=false
        scroll.removeCallbacks(initialBottom)
        cancelBottomFollow()
        cancelTurnJump()
        window.decorView.removeCallbacks(refreshRateRequest)
        val previous=previousPreferredRefreshRate
        if(previous!=null && window.attributes.preferredRefreshRate==requestedPreferredRefreshRate) {
            val attributes=window.attributes
            attributes.preferredRefreshRate=previous
            window.attributes=attributes
        }
        previousPreferredRefreshRate=null
        requestedPreferredRefreshRate=null
        if(selectionReady) selections[selected?.id]=input.selectionStart to input.selectionEnd
        super.onPause()
    }
    override fun onDestroy() {
        refreshRateWindowResumed=false
        scroll.removeCallbacks(initialBottom);cancelBottomFollow();cancelTurnJump()
        window.decorView.removeCallbacks(refreshRateRequest)
        settingsDialog?.dismiss()
        controller.unobserve(listener);super.onDestroy()
    }
    private fun toast(text:String) {Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    private fun guard(action:()->Unit) {try {action()} catch (_:Exception) {toast("操作或保存失败；原数据保留")} }
    internal fun select(conversation:Conversation?) {
        check(controller.ready && controller.problem?.startsWith("会话恢复失败")!=true)
        val list=controller.database.conversations()
        var draft=if(conversation==null) controller.database.draft() else list.first {it.id==conversation.id}.draft
        if(selectionReady) {
            val id=selected?.id
            if(id==null) controller.database.draft(input.text.toString())
            else if(list.any {it.id==id}) controller.database.draft(id,input.text.toString())
            selections[id]=input.selectionStart to input.selectionEnd
        }
        if(selectionReady && selected?.id==conversation?.id) draft=input.text.toString()
        selected=conversation;selectionReady=true
        check(controller.preferences.edit().apply {if(conversation==null) remove("selected") else putString("selected",conversation.id.value)}.commit())
        val range=selections[conversation?.id]
        editing=true
        try {input.setText(draft);input.setSelection((range?.first ?: input.length()).coerceIn(0,input.length()),(range?.second ?: input.length()).coerceIn(0,input.length()))} finally {editing=false}
        shown=if(conversation==null) mutableListOf() else controller.database.messages(conversation.id).toMutableList();renderAll()
        jumpToBottom();refresh()
    }
    private fun refresh() {
        input.isEnabled=controller.ready && controller.problem?.startsWith("会话恢复失败")!=true
        send.isEnabled=controller.ready && controller.active==null && controller.problem?.startsWith("会话恢复失败")!=true
        stop.isEnabled=controller.active?.status==RunStatus.RUNNING
        if(!controller.ready) {status.text="恢复会话中";return}
        if(controller.problem?.startsWith("会话恢复失败")==true) {status.text=controller.problem;return}
        guard {
            if(!selectionReady) {
                val list=controller.database.conversations()
                val id=controller.preferences.getString("selected",null)
                select(list.firstOrNull {it.id.value==id});return@guard
            }
            val active=controller.active
            if(active!=null && active.conversationId==selected?.id) {
                val at=shown.indexOfFirst {it.second.id==active.id}
                if(at>=0) shown[at]=shown[at].first to active
                updateRow(active)
            } else if(selected!=null && active==null && shown.any {it.second.status==RunStatus.RUNNING}) {
                shown=shown.map {entry ->
                    if(entry.second.status==RunStatus.RUNNING) {
                        val saved=controller.database.messages(selected!!.id,entry.first+1,1).singleOrNull()
                        if(saved?.second?.id==entry.second.id) {updateRow(saved.second);saved} else entry
                    } else entry
                }.toMutableList()
            }
            status.text=controller.problem ?: "${selected?.title ?: "未创建会话"} · ${if(active!=null) if(active.conversationId==selected?.id) "运行中" else "其他会话运行中" else "已就绪"}"
        }
    }
    private fun renderAll() {cancelTurnJump();timeline.removeAllViews();rows.clear();shown.forEach {updateRow(it.second)} }
    private fun updateRow(message:Message) {
        val row=rows.getOrPut(message.id) {LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(0,12,0,12);timeline.addView(this)}}
        row.removeAllViews()
        row.addView(TextView(this).apply {text=getString(R.string.message_header,getString(if(message.role=="user") R.string.message_user else R.string.app_name),message.status);textSize=12f})
        message.blocks.forEach {block -> when(block) {
            is MessageBlock.Text -> row.addView(TextView(this).apply {text=MarkdownText.render(block.text);textSize=17f;setTextIsSelectable(true);movementMethod=android.text.method.LinkMovementMethod.getInstance()})
            is MessageBlock.Tool -> {
                val details=TextView(this).apply {text=getString(R.string.tool_details,block.invocation.arguments,block.result ?: getString(R.string.tool_waiting));visibility=if(block.invocation.id in expandedTools) View.VISIBLE else View.GONE;setTextIsSelectable(true)}
                row.addView(Button(this).apply {text=getString(R.string.tool_header,block.invocation.name,getString(if(block.result!=null) R.string.tool_has_result else R.string.tool_pending));setOnClickListener {if(details.visibility==View.GONE) {expandedTools.add(block.invocation.id);details.visibility=View.VISIBLE} else {expandedTools.remove(block.invocation.id);details.visibility=View.GONE}}})
                row.addView(details)
            }
        } }
        message.error?.let {row.addView(TextView(this).apply {text=it})}
        row.addView(Button(this).apply {text="复制";setOnClickListener {
            val text=message.blocks.joinToString("\n") {when(it) {is MessageBlock.Text->it.text;is MessageBlock.Tool->"${it.invocation.name}: ${it.result ?: "未完成"}"}}
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("代鱼消息",text))
        }})
        scheduleBottomFollow()
    }
    private fun chooseUserMessage() {
        val messages=shown.map {it.second}.filter {it.role=="user"}
        if(messages.isEmpty()) {toast("当前已加载历史中没有用户消息");return}
        val labels=messages.map {it.blocks.filterIsInstance<MessageBlock.Text>().joinToString(" ") {block->block.text}.take(100)}
        AlertDialog.Builder(this).setTitle("定位已加载的用户消息（更早内容请先加载）")
            .setItems(labels.toTypedArray()) {_,index->guard {jumpToMessage(messages[index].id)}}.show()
    }
    internal fun jumpToMessage(id:MessageId) {
        check(shown.any {it.second.id==id && it.second.role=="user"})
        cancelTurnJump();followsBottom=false;scroll.removeCallbacks(initialBottom);cancelBottomFollow()
        if(!refreshRateWindowResumed) return
        jumpTarget=id;jumpTime=0L
        Choreographer.getInstance().postFrameCallback(turnFrame)
    }
    private fun cancelScrollLayout() {
        pendingScrollLayout?.let {if(scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnPreDrawListener(it)}
        pendingScrollLayout=null
    }
    private fun afterScrollLayout(action:()->Boolean) {
        cancelScrollLayout()
        val listener=ViewTreeObserver.OnPreDrawListener {
            if(!refreshRateWindowResumed || isFinishing || isDestroyed || action()) cancelScrollLayout()
            true
        }
        pendingScrollLayout=listener
        scroll.viewTreeObserver.addOnPreDrawListener(listener)
    }
    private fun cancelTurnJump() {
        cancelScrollLayout()
        Choreographer.getInstance().removeFrameCallback(turnFrame)
        jumpTarget=null;turnMotion=null;jumpTime=0L;jumpRemainder=0f
    }
    private fun advanceTurnJump(time:Long) {
        val id=jumpTarget ?: return
        val row=rows[id]
        if(!refreshRateWindowResumed || isFinishing || isDestroyed || row==null) {cancelTurnJump();return}
        if(row.height<=0 || scroll.height<=0 || root.isLayoutRequested || timeline.isLayoutRequested || scroll.isLayoutRequested) {
            afterScrollLayout {
                if(row.height>0 && scroll.height>0 && !root.isLayoutRequested && !timeline.isLayoutRequested && !scroll.isLayoutRequested) {
                    advanceTurnJump(System.nanoTime());true
                } else false
            }
            return
        }
        val motion=turnMotion ?: ConversationTurnMotion(scroll.height.toFloat()).also {turnMotion=it}
        val maximum=(timeline.height-scroll.height+scroll.paddingTop+scroll.paddingBottom).coerceAtLeast(0)
        val target=row.top.coerceIn(0,maximum)
        val distance=target-scroll.scrollY
        if(kotlin.math.abs(distance)<=1) {scroll.scrollTo(0,target);cancelTurnJump();return}
        val elapsed=if(jumpTime==0L) 0f else (time-jumpTime).coerceAtLeast(0)/1_000_000_000f
        jumpTime=time
        val movement=motion.advance(elapsed,kotlin.math.abs(distance).toFloat())+jumpRemainder
        val pixels=movement.toInt().coerceAtMost(kotlin.math.abs(distance))
        jumpRemainder=movement-pixels
        scroll.scrollBy(0,if(distance<0) -pixels else pixels)
        Choreographer.getInstance().postFrameCallback(turnFrame)
    }
    private fun isAtBottom()=scroll.scrollY+scroll.height-scroll.paddingTop-scroll.paddingBottom>=timeline.height-2
    private fun cancelBottomFollow() {
        Choreographer.getInstance().removeFrameCallback(followFrame)
        followScheduled=false;followRemainder=0f;bottomMotion.reset()
    }
    private fun scheduleBottomFollow() {
        if(followsBottom && refreshRateWindowResumed && !followScheduled && !isAtBottom()) {
            followScheduled=true
            Choreographer.getInstance().postFrameCallback(followFrame)
        }
    }
    private fun jumpToBottom() {
        cancelTurnJump()
        followsBottom=true
        cancelBottomFollow()
        scroll.removeCallbacks(initialBottom)
        scroll.post(initialBottom)
    }
    private fun loadEarlier() {
        val id=selected?.id ?: return
        val earlier=controller.database.messages(id,shown.firstOrNull()?.first ?: Long.MAX_VALUE)
        if(earlier.isEmpty()) {toast("没有更早消息");return}
        followsBottom=false;scroll.removeCallbacks(initialBottom);cancelBottomFollow()
        val anchor=shown.firstOrNull {rows[it.second.id]?.bottom?.let {bottom->bottom>scroll.scrollY}==true}?.second?.id
        val offset=anchor?.let {rows.getValue(it).top-scroll.scrollY} ?: 0
        shown.addAll(0,earlier);renderAll()
        if(anchor!=null) afterScrollLayout {
            val row=rows[anchor]
            if(row!=null && row.height>0 && scroll.height>0 && !root.isLayoutRequested && !timeline.isLayoutRequested && !scroll.isLayoutRequested) {
                scroll.scrollTo(0,row.top-offset);true
            } else false
        }
    }
    private fun chooseConversation() {
        val list=controller.database.conversations()
        AlertDialog.Builder(this).setTitle("会话").setItems(list.map {it.title}.toTypedArray()) {_,which ->
            val conversation=list[which]
            AlertDialog.Builder(this).setTitle(conversation.title).setItems(arrayOf("打开","重命名","删除")) {_,action -> guard {when(action) {
                0->select(conversation)
                1->{val edit=EditText(this).apply {setText(conversation.title)};AlertDialog.Builder(this).setTitle("重命名").setView(edit).setPositiveButton("保存") {_,_->guard {controller.database.rename(conversation.id,edit.text.toString());if(selected?.id==conversation.id) {selected=conversation.copy(title=edit.text.toString());refresh()}}}.setNegativeButton("取消",null).show()}
                2->if(controller.active?.conversationId==conversation.id) toast("先停止此会话的运行再删除") else AlertDialog.Builder(this).setMessage("确认删除此会话及其消息？").setPositiveButton("删除") {_,_->guard {check(controller.active?.conversationId!=conversation.id) {"会话运行中，不能删除"};controller.database.delete(conversation.id);if(selected?.id==conversation.id) {select(null)}}}.setNegativeButton("取消",null).show()
            }} }.show()
        }.show()
    }
    private fun modelSettings() {
        if(controller.active!=null) {toast("当前运行使用冻结配置，请结束后修改");return}
        val editingSettings=controller.settings.loadForEditing()
        val saved=editingSettings.settings
        val fields=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val endpoint=EditText(this).apply {hint="HTTPS 服务基础地址或完整 Responses 地址";setText(saved.endpoint);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI}
        val path=EditText(this).apply {hint="可选 Responses 路径，如 /responses";setText(saved.responsesPath);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI}
        val model=EditText(this).apply {hint="模型标识";setText(saved.model)}
        val key=EditText(this).apply {hint="API密钥（加密保存）";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setText(saved.apiKey)}
        val battery=CheckBox(this).apply {text="允许模型查询只读电量";isChecked=saved.batteryEnabled}
        val deviceStatus=CheckBox(this).apply {text="允许模型查询只读设备状态（电量、内存、应用存储所在卷、系统与开机时长；结果发送给模型服务，不含设备标识）";isChecked=saved.deviceStatusEnabled}
        fields.addView(TextView(this).apply {setText(R.string.model_protocol_note)})
        if(editingSettings.credentialUnavailable) fields.addView(TextView(this).apply {text="已保存密钥不可用；原配置已保留，请重新输入密钥"})
        fields.addView(endpoint);fields.addView(path);fields.addView(model);fields.addView(key);fields.addView(battery);fields.addView(deviceStatus)
        val dialog=AlertDialog.Builder(this).setTitle("模型设置").setView(fields).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        key.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {controller.settings.save(ModelSettings(endpoint.text.toString().trim(),model.text.toString().trim(),key.text.toString(),battery.isChecked,path.text.toString().trim(),deviceStatus.isChecked));dialog.dismiss();toast("模型设置已保存")}
            catch (_:Exception) {toast("设置未保存；请检查HTTPS地址、模型与密钥")}
        }}
        settingsDialog=dialog
        dialog.setOnDismissListener {if(settingsDialog===dialog) settingsDialog=null}
        dialog.show()
    }
}
