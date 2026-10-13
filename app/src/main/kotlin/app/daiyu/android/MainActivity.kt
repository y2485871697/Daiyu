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
    private var shown=mutableListOf<Pair<Long,Message>>()
    private val rows=mutableMapOf<MessageId,LinearLayout>()
    private val expandedTools=mutableSetOf<ToolCallId>()
    private val selections=mutableMapOf<ConversationId,Pair<Int,Int>>()
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
        toolbar.addView(button("会话") {chooseConversation()});toolbar.addView(button("新建") {if(controller.active==null) select(controller.database.create()) else toast("先停止当前运行")})
        toolbar.addView(button("模型设置") {modelSettings()});root.addView(toolbar)
        status=TextView(this);root.addView(status)
        root.addView(button("加载更早消息") {loadEarlier()})
        timeline=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        scroll=ScrollView(this).apply {addView(timeline)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        input=EditText(this).apply {hint="输入消息";minLines=2;maxLines=5;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES}
        root.addView(input)
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                if(!editing) guard {selected?.let {controller.database.draft(it.id,s.toString())}}
            }
            override fun afterTextChanged(s:Editable?) {}
        })
        val actions=LinearLayout(this)
        send=button("发送") {
            val conversation=selected ?: return@button
            if(controller.send(conversation,input.text.toString())) {
                editing=true;input.setText("");editing=false
                shown=controller.database.messages(conversation.id).toMutableList();renderAll();scroll.post {scroll.fullScroll(View.FOCUS_DOWN)}
            }
        }
        stop=button("停止") {controller.stop();status.text="正在停止；不代表远端已取消"}
        actions.addView(send);actions.addView(stop);actions.addView(button("回到底部") {scroll.fullScroll(View.FOCUS_DOWN)})
        root.addView(actions);setContentView(root);root.requestApplyInsets()
        controller.observe(listener);refresh()
    }
    override fun onDestroy() {controller.unobserve(listener);super.onDestroy()}
    private fun toast(text:String) {Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    private fun guard(action:()->Unit) {try {action()} catch (_:Exception) {toast("操作或保存失败；原数据保留")} }
    private fun select(conversation:Conversation) {
        selected?.let {selections[it.id]=input.selectionStart to input.selectionEnd}
        selected=conversation
        controller.preferences.edit().putString("selected",conversation.id.value).apply()
        editing=true;input.setText(conversation.draft);val range=selections[conversation.id];input.setSelection((range?.first ?: input.length()).coerceIn(0,input.length()),(range?.second ?: input.length()).coerceIn(0,input.length()));editing=false
        shown=controller.database.messages(conversation.id).toMutableList();renderAll()
        scroll.post {scroll.fullScroll(View.FOCUS_DOWN)};refresh()
    }
    private fun refresh() {
        send.isEnabled=controller.ready && controller.active==null && controller.problem?.startsWith("会话恢复失败")!=true
        stop.isEnabled=controller.active?.status==RunStatus.RUNNING
        if(!controller.ready) {status.text="恢复会话中";return}
        if(controller.problem?.startsWith("会话恢复失败")==true) {status.text=controller.problem;return}
        guard {
            if(selected==null) {
                val list=controller.database.conversations()
                val id=controller.preferences.getString("selected",null)
                select(list.firstOrNull {it.id.value==id} ?: list.firstOrNull() ?: controller.database.create());return@guard
            }
            val active=controller.active
            if(active!=null && active.conversationId==selected!!.id) {
                val at=shown.indexOfFirst {it.second.id==active.id}
                if(at>=0) shown[at]=shown[at].first to active
                updateRow(active)
            } else if(active==null && shown.any {it.second.status==RunStatus.RUNNING}) {
                shown=controller.database.messages(selected!!.id).toMutableList();renderAll()
            }
            status.text=controller.problem ?: "${selected!!.title} · ${if(active!=null) "运行中" else "已就绪"}"
        }
    }
    private fun renderAll() {timeline.removeAllViews();rows.clear();shown.forEach {updateRow(it.second)} }
    private fun updateRow(message:Message) {
        val follow=scroll.scrollY+scroll.height>=timeline.height-80
        val row=rows.getOrPut(message.id) {LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(0,12,0,12);timeline.addView(this)}}
        row.removeAllViews()
        row.addView(TextView(this).apply {text="${if(message.role=="user") "你" else "代鱼"} · ${message.status}";textSize=12f})
        message.blocks.forEach {block -> when(block) {
            is MessageBlock.Text -> row.addView(TextView(this).apply {text=MarkdownText.render(block.text);textSize=17f;setTextIsSelectable(true);movementMethod=android.text.method.LinkMovementMethod.getInstance()})
            is MessageBlock.Tool -> {
                val details=TextView(this).apply {text="参数：${block.invocation.arguments}\n结果：${block.result ?: "等待确认"}";visibility=if(block.invocation.id in expandedTools) View.VISIBLE else View.GONE;setTextIsSelectable(true)}
                row.addView(Button(this).apply {text="工具 ${block.invocation.name} · ${if(block.result!=null) "已有结果" else "待处理"}";setOnClickListener {if(details.visibility==View.GONE) {expandedTools.add(block.invocation.id);details.visibility=View.VISIBLE} else {expandedTools.remove(block.invocation.id);details.visibility=View.GONE}}})
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
        if(controller.active!=null) {toast("先停止当前运行再管理会话");return}
        val list=controller.database.conversations()
        AlertDialog.Builder(this).setTitle("会话").setItems(list.map {it.title}.toTypedArray()) {_,which ->
            val conversation=list[which]
            AlertDialog.Builder(this).setTitle(conversation.title).setItems(arrayOf("打开","重命名","删除")) {_,action -> guard {when(action) {
                0->select(conversation)
                1->{val edit=EditText(this).apply {setText(conversation.title)};AlertDialog.Builder(this).setTitle("重命名").setView(edit).setPositiveButton("保存") {_,_->guard {controller.database.rename(conversation.id,edit.text.toString());if(selected?.id==conversation.id) {selected=conversation.copy(title=edit.text.toString());refresh()}}}.setNegativeButton("取消",null).show()}
                2->AlertDialog.Builder(this).setMessage("确认删除此会话及其消息？").setPositiveButton("删除") {_,_->guard {controller.database.delete(conversation.id);if(selected?.id==conversation.id) {selected=null;refresh()}}}.setNegativeButton("取消",null).show()
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
        fields.addView(TextView(this).apply {text="首个协议：OpenAI Responses。仅HTTPS；密钥不写入聊天。"})
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
