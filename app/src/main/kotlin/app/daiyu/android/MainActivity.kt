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
    private var selected:Conversation?=null
    private var editing=false
    private var selectionReady=false
    private var shown=mutableListOf<Pair<Long,Message>>()
    private val rows=mutableMapOf<MessageId,LinearLayout>()
    private val expandedTools=mutableSetOf<ToolCallId>()
    private val selections get()=controller.selections
    private val listener:()->Unit={refresh()}
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
        timeline=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        scroll=ScrollView(this).apply {addView(timeline)}
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
                shown=controller.database.messages(saved.id).toMutableList();renderAll();scroll.post {scroll.fullScroll(View.FOCUS_DOWN)}
                if(!selectionSaved) toast("消息已保存；最后选中会话未保存")
            }
        }
        stop=button("停止") {controller.stop();status.text="正在停止；不代表远端已取消"}
        actions.addView(send);actions.addView(stop);actions.addView(button("回到底部") {scroll.fullScroll(View.FOCUS_DOWN)})
        root.addView(actions);setContentView(root);root.requestApplyInsets()
        controller.observe(listener);refresh()
    }
    override fun onPause() {
        if(selectionReady) selections[selected?.id]=input.selectionStart to input.selectionEnd
        super.onPause()
    }
    override fun onDestroy() {controller.unobserve(listener);super.onDestroy()}
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
        scroll.post {scroll.fullScroll(View.FOCUS_DOWN)};refresh()
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
                shown=controller.database.messages(selected!!.id).toMutableList();renderAll()
            }
            status.text=controller.problem ?: "${selected?.title ?: "未创建会话"} · ${if(active!=null) if(active.conversationId==selected?.id) "运行中" else "其他会话运行中" else "已就绪"}"
        }
    }
    private fun renderAll() {timeline.removeAllViews();rows.clear();shown.forEach {updateRow(it.second)} }
    private fun updateRow(message:Message) {
        val follow=scroll.scrollY+scroll.height>=timeline.height-80
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
        if(follow) scroll.post {scroll.fullScroll(View.FOCUS_DOWN)}
    }
    private fun loadEarlier() {
        val id=selected?.id ?: return
        val earlier=controller.database.messages(id,shown.firstOrNull()?.first ?: Long.MAX_VALUE)
        if(earlier.isEmpty()) {toast("没有更早消息");return}
        val old=timeline.height;val y=scroll.scrollY;shown.addAll(0,earlier);renderAll();scroll.post {scroll.scrollTo(0,y+timeline.height-old)}
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
        val saved=controller.settings.load()
        val fields=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val endpoint=EditText(this).apply {hint="HTTPS Responses完整地址";setText(saved.endpoint);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI}
        val model=EditText(this).apply {hint="模型标识";setText(saved.model)}
        val key=EditText(this).apply {hint="API密钥（加密保存）";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setText(saved.apiKey)}
        val battery=CheckBox(this).apply {text="允许模型查询只读电量";isChecked=saved.batteryEnabled}
        fields.addView(TextView(this).apply {setText(R.string.model_protocol_note)})
        fields.addView(endpoint);fields.addView(model);fields.addView(key);fields.addView(battery)
        val dialog=AlertDialog.Builder(this).setTitle("模型设置").setView(fields).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        key.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {controller.settings.save(ModelSettings(endpoint.text.toString().trim(),model.text.toString().trim(),key.text.toString(),battery.isChecked));dialog.dismiss();toast("模型设置已保存")}
            catch (_:Exception) {toast("设置未保存；请检查HTTPS地址、模型与密钥")}
        }}
        dialog.show()
    }
}
