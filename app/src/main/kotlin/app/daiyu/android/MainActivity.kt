package app.daiyu.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
    private var backupDialog:AlertDialog?=null
    private var configurationRestoreDialog:AlertDialog?=null
    private var configurationRestorePreview:ChatController.ConfigurationRestorePreview?=null
    private var lastOpenedBackupUri:String?=null
    private var pendingBackupRequest:Int?=null
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
    private var dataRevision=-1L
    private var shown=mutableListOf<Pair<Long,Message>>()
    private val rows=mutableMapOf<MessageId,LinearLayout>()
    private val expandedTools=mutableSetOf<ToolCallId>()
    private val selections get()=controller.selections
    private val listener:()->Unit={refresh()}
    private var refreshRateWindowResumed=false
    private var previousPreferredRefreshRate:Float?=null
    private var requestedPreferredRefreshRate:Float?=null
    /** Delayed controller/UI callbacks must not mutate a destroyed Activity. */
    private var activityCallbacksAllowed=false
    private val refreshRateRequest=Runnable {applyPreferredRefreshRate()}
    private fun activityUsable():Boolean = activityCallbacksAllowed && !isFinishing && !isDestroyed
    private fun requireActivityUsable() {check(activityUsable()) {"页面已关闭；未执行操作"}}
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
        fun button(label:String,action:()->Unit)=Button(this).apply {text=label;setOnClickListener {guard {check(!controller.dataBusy);action()}}}
        toolbar.addView(button("会话") {chooseConversation()});toolbar.addView(button("新建") {select(null)})
        toolbar.addView(button("模型设置") {modelSettings()});root.addView(toolbar)
        root.addView(button("备份与导入") {backupMenu()})
        root.addView(button("搜索会话") {
            val query=EditText(this).apply {hint="标题、草稿或消息关键词"}
            AlertDialog.Builder(this).setTitle("搜索会话").setView(query).setPositiveButton("搜索") {_,_->guard {chooseConversation(query.text.toString())}}.setNegativeButton("取消",null).show()
        })
        root.addView(button("按文件夹浏览") {
            val folders=controller.database.conversations().map {it.folder}.distinct().sorted()
            AlertDialog.Builder(this).setTitle("文件夹").setItems(folders.map {if(it.isEmpty()) "未分类" else it}.toTypedArray()) {_,index->guard {chooseConversation(folder=folders[index])}}.show()
        })
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
                if(!editing && !controller.dataBusy) guard {val id=selected?.id;if(id==null) controller.database.draft(s.toString()) else controller.database.draft(id,s.toString())}
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
        activityCallbacksAllowed=true
        controller.observe(listener);refresh()
        pendingBackupRequest=state?.getInt("pending-backup-request",0)?.takeIf {it!=0}
        // A restored Activity never replays a VIEW prompt or an import confirmation.
        if(state==null) offerOpenedBackup(intent)
    }
    override fun onSaveInstanceState(state:Bundle) {
        state.putInt("pending-backup-request",pendingBackupRequest ?: 0)
        super.onSaveInstanceState(state)
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerOpenedBackup(intent)
    }
    private fun checkBackupAvailable() {
        requireActivityUsable()
        check(controller.ready && controller.active==null && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败")!=true) {
            "会话尚未就绪、运行中、备份任务进行中或恢复失败；未打开备份，请稍后重新选择文件"
        }
    }
    private fun backupGuard(action:()->Unit) {
        try {checkBackupAvailable();action()}
        catch(failure:Exception) {
            toast(if(failure is IllegalStateException || failure is IllegalArgumentException)
                failure.message ?: "备份操作失败；原数据保留"
            else "备份操作失败；原数据保留，请重新选择文件")
        }
    }
    private fun showBackupDialog(dialog:AlertDialog) {
        backupDialog?.dismiss()
        backupDialog=dialog
        dialog.setOnDismissListener {if(backupDialog===dialog) backupDialog=null}
        dialog.show()
    }
    private fun offerOpenedBackup(open:Intent?) {
        if(open?.action!=Intent.ACTION_VIEW) return
        backupGuard {
            val uri=open.data
            require(uri!=null && uri.scheme=="content" && open.type=="application/json") {"请选择content类型的JSON备份文件"}
            require(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0) {"备份文件未授予读取权限；请重新选择文件"}
            checkBackupAvailable()
            // Suppress duplicate delivery only while its choice/confirmation is visible.
            // A later deliberate open can retry; recreation itself never replays the intent.
            if(lastOpenedBackupUri==uri.toString() && backupDialog?.isShowing==true) return@backupGuard
            lastOpenedBackupUri=uri.toString()
            showBackupDialog(AlertDialog.Builder(this).setTitle("打开备份：选择格式后确认导入")
                .setItems(arrayOf("完整中立备份（推荐）","纯文本兼容备份")) {_,index->backupGuard {
                    confirmBackupImport(uri,index==0)
                }}.setNegativeButton("取消",null).create())
        }
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
        activityCallbacksAllowed=false
        refreshRateWindowResumed=false
        scroll.removeCallbacks(initialBottom);cancelBottomFollow();cancelTurnJump()
        window.decorView.removeCallbacks(refreshRateRequest)
        configurationRestorePreview?.let {controller.cancelConfigurationRestore(it)}
        configurationRestorePreview=null
        settingsDialog?.dismiss();backupDialog?.dismiss();configurationRestoreDialog?.dismiss()
        controller.unobserve(listener);super.onDestroy()
    }
    private fun toast(text:String) {Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    private fun guard(action:()->Unit) {try {requireActivityUsable();check(!controller.dataBusy);action()} catch (_:Exception) {toast("操作或保存失败；原数据保留")} }
    internal fun select(conversation:Conversation?) {
        requireActivityUsable()
        check(controller.ready && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败")!=true)
        val list=controller.database.conversations()
        var draft=if(conversation==null) controller.database.draft() else list.first {it.id==conversation.id}.draft
        if(selectionReady) {
            val id=selected?.id
            if(id==null) controller.database.draft(input.text.toString())
            else if(list.any {it.id==id}) controller.database.draft(id,input.text.toString())
            selections[id]=input.selectionStart to input.selectionEnd
        }
        if(selectionReady && selected?.id==conversation?.id) draft=input.text.toString()
        // Do not change the draft owner until the selection preference has been accepted.
        // Otherwise a failed commit leaves the previous input writing into the target conversation.
        check(controller.preferences.edit().apply {if(conversation==null) remove("selected") else putString("selected",conversation.id.value)}.commit())
        selected=conversation;selectionReady=true
        val range=selections[conversation?.id]
        editing=true
        try {input.setText(draft);input.setSelection((range?.first ?: input.length()).coerceIn(0,input.length()),(range?.second ?: input.length()).coerceIn(0,input.length()))} finally {editing=false}
        shown=if(conversation==null) mutableListOf() else controller.database.messages(conversation.id).toMutableList();renderAll()
        jumpToBottom();refresh()
    }
    private fun refresh() {
        if(!activityUsable()) return
        input.isEnabled=controller.ready && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败")!=true
        send.isEnabled=controller.ready && !controller.dataBusy && controller.active==null && controller.problem?.startsWith("会话恢复失败")!=true
        stop.isEnabled=controller.active?.status==RunStatus.RUNNING
        if(controller.dataBusy) {status.text="处理备份中";return}
        if(!controller.ready) {status.text="恢复会话中";return}
        if(controller.problem?.startsWith("会话恢复失败")==true) {status.text=controller.problem;return}
        guard {
            if(dataRevision!=controller.dataRevision) {
                dataRevision=controller.dataRevision
                if(selectionReady && selected==null) {
                    editing=true
                    try {input.setText(controller.database.draft());input.setSelection(input.length())} finally {editing=false}
                }
            }
            if(!isFinishing && !isDestroyed) controller.consumeDataNotice()?.let {toast(it)}
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
    private fun backupMenu() {
        checkBackupAvailable()
        lastOpenedBackupUri=null
        showBackupDialog(AlertDialog.Builder(this).setTitle("备份与导入")
            .setItems(arrayOf("完整中立备份（推荐）","纯文本兼容备份","人工恢复已导入的模型配置")) {_,index->backupGuard {
                if(index==2) previewConfigurationRestore() else backupFormatMenu(index==0)
            }}
            .setNegativeButton("取消",null).create())
    }
    private fun backupFormatMenu(complete:Boolean) {
        checkBackupAvailable()
        val description=if(complete) "包含会话、草稿、工具结果、协议历史、分支、压缩原文归档、用量、附件及非秘密配置。"+
            "不含API密钥、Cookie、授权令牌或系统授权。配置导入后仅保存为备份元数据，不替换当前模型设置或启用授权。"+
            "完整格式v1，包上限112MiB。含聊天隐私，请选择可信位置。"
        else "文本格式v1，包含会话、文本、工具结果、协议历史及草稿。"+
            "不含附件、压缩原文归档、分支、用量或模型配置，不能替代完整备份。文件上限16MiB。含聊天隐私，请选择可信位置。"
        showBackupDialog(AlertDialog.Builder(this).setTitle(if(complete) "完整中立备份" else "纯文本兼容备份").setMessage(description)
            .setPositiveButton("导出") {_,_->backupGuard {pickBackup(true,complete)}}
            .setNeutralButton("导入") {_,_->backupGuard {pickBackup(false,complete)}}
            .setNegativeButton("取消",null).create())
    }
    @Suppress("DEPRECATION")
    private fun pickBackup(exporting:Boolean,complete:Boolean) {
        checkBackupAvailable()
        check(pendingBackupRequest==null) {"文件选择尚未返回；请先完成或取消选择"}
        val code=if(complete) {if(exporting) 103 else 104} else {if(exporting) 101 else 102}
        val action=if(exporting) android.content.Intent.ACTION_CREATE_DOCUMENT else android.content.Intent.ACTION_OPEN_DOCUMENT
        pendingBackupRequest=code
        try {
            startActivityForResult(android.content.Intent(action).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE);type="application/json"
                if(exporting) putExtra(android.content.Intent.EXTRA_TITLE,if(complete) "daiyu-complete-v1.json" else "daiyu-text-v1.json")
            },code)
        } catch(failure:Exception) {pendingBackupRequest=null;throw failure}
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode !in setOf(101,102,103,104) || pendingBackupRequest!=requestCode) return
        pendingBackupRequest=null
        if(resultCode!=RESULT_OK) return
        val uri=data?.data ?: run {toast("文件选择未返回URI；原数据保留，请重新选择");return}
        val complete=requestCode==103 || requestCode==104
        backupGuard {
            require(uri.scheme=="content") {"文件选择未返回content URI；原数据保留，请重新选择"}
            if(requestCode==101 || requestCode==103) controller.exportUserBackup(uri,complete)
            else confirmBackupImport(uri,complete)
        }
    }
    private fun confirmBackupImport(uri:Uri,complete:Boolean) {
        checkBackupAvailable()
        val explanation=if(complete) "仅追加完整格式v1。会话、消息、分支、附件身份、独立草稿或备份配置冲突将整包回滚，"+
            "不覆盖已有会话；重复整包不会再次追加。非秘密配置只保存为备份元数据，不替换当前模型设置、密钥或系统授权。不会自动执行历史模型请求或工具。"
        else "仅追加文本格式v1。会话ID、消息ID或独立草稿冲突将整包回滚，不覆盖已有数据，不自动执行历史模型请求或工具。"+
            "此入口不接受完整备份；完整包请选择完整中立备份入口。"
        lateinit var confirmation:AlertDialog
        confirmation=AlertDialog.Builder(this).setTitle(if(complete) "确认导入完整中立备份" else "确认导入文本备份").setMessage(explanation)
            .setPositiveButton("导入") {_,_->backupGuard {
                check(backupDialog===confirmation && confirmation.isShowing) {"导入确认已失效；请重新选择文件"}
                // Consume before dispatch: a replaced, cancelled or repeated callback has no authority.
                backupDialog=null
                controller.importUserBackup(uri,complete)
            }}
            .setNegativeButton("取消",null).create()
        showBackupDialog(confirmation)
    }
    private fun chooseConversation(query:String="",folder:String?=null) {
        val list=controller.database.conversations(query,folder)
        AlertDialog.Builder(this).setTitle("会话").setItems(list.map {"${if(it.pinned) "★ " else ""}${if(it.folder.isNotEmpty()) "[${it.folder}] " else ""}${it.title}"}.toTypedArray()) {_,which ->
            if(controller.dataBusy) {toast("备份任务进行中");return@setItems}
            val conversation=list[which]
            AlertDialog.Builder(this).setTitle(conversation.title).setItems(arrayOf("打开","重命名","删除",if(conversation.pinned) "取消置顶" else "置顶","移动到文件夹")) {_,action -> guard {when(action) {
                0->select(conversation)
                1->{val edit=EditText(this).apply {setText(conversation.title)};AlertDialog.Builder(this).setTitle("重命名").setView(edit).setPositiveButton("保存") {_,_->guard {controller.database.rename(conversation.id,edit.text.toString());if(selected?.id==conversation.id) {selected=conversation.copy(title=edit.text.toString());refresh()}}}.setNegativeButton("取消",null).show()}
                3->{controller.database.organize(conversation.id,conversation.folder,!conversation.pinned);chooseConversation(query,folder)}
                4->{val edit=EditText(this).apply {hint="文件夹名称；留空移出";setText(conversation.folder)};AlertDialog.Builder(this).setTitle("移动到文件夹").setView(edit).setPositiveButton("保存") {_,_->guard {controller.database.organize(conversation.id,edit.text.toString().trim(),conversation.pinned);chooseConversation(query,folder)}}.setNegativeButton("取消",null).show()}
                2->if(controller.active?.conversationId==conversation.id) toast("先停止此会话的运行再删除") else AlertDialog.Builder(this).setMessage("确认删除此会话及其消息？").setPositiveButton("删除") {_,_->guard {check(controller.active?.conversationId!=conversation.id) {"会话运行中，不能删除"};controller.database.delete(conversation.id);if(selected?.id==conversation.id) {select(null)}}}.setNegativeButton("取消",null).show()
            }} }.show()
        }.show()
    }
    private fun previewConfigurationRestore() {
        checkBackupAvailable()
        configurationRestoreDialog?.dismiss()
        val preview=controller.previewImportedConfiguration()
        val plan=preview.plan
        fun value(values:Map<String,Any?>,name:String):String = when {
            !values.containsKey(name) -> "（未设置）"
            values[name]=="" -> "（空）"
            values[name] is Boolean -> if(values[name]==true) "允许" else "关闭"
            else -> values[name]?.toString() ?: "（空）"
        }
        val labels=listOf("endpoint" to "服务地址","responses-path" to "Responses 路径","model" to "模型",
            "battery" to "只读电量工具","device-status" to "只读设备状态工具")
        val message=if(plan.isNoOp) "没有已导入的非秘密配置；不会修改当前设置。" else
            labels.joinToString("\n\n") {(name,label)->"$label：\n当前：${value(plan.before,name)}\n恢复后：${value(plan.after,name)}"}+
                "\n\n仅恢复已导入完整备份的非秘密配置，不执行历史请求，不启用工具或系统授权。"+
                (if(plan.credentialRebindRequired) "\n服务地址、路径或模型发生变化：当前加密密钥保留但不会读取或用于新配置。恢复后必须在模型设置中重新输入并保存密钥，才可发送。"
                else "\n当前密钥不读取、不展示、不修改；已有密钥重绑要求不会解除。")+
                "\n取消或页面重建不恢复、不重放。恢复失败时原配置保留；若存储补偿也失败，将阻止发送并要求重新保存。"
        val dialog=AlertDialog.Builder(this).setTitle("预览人工恢复模型配置").setMessage(message)
            .setNegativeButton("取消") {_,_->controller.cancelConfigurationRestore(preview)}.apply {
                if(!plan.isNoOp) setPositiveButton("确认恢复",null)
            }.create()
        configurationRestoreDialog=dialog
        configurationRestorePreview=preview
        dialog.setOnDismissListener {
            controller.cancelConfigurationRestore(preview)
            if(configurationRestoreDialog===dialog) {
                configurationRestoreDialog=null
                configurationRestorePreview=null
            }
        }
        dialog.setOnCancelListener {controller.cancelConfigurationRestore(preview)}
        dialog.setOnShowListener {
            if(!plan.isNoOp) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    // The Controller rechecks ready/active/dataBusy/recovery failure immediately before commit.
                    requireActivityUsable()
                    controller.restoreImportedConfiguration(preview,authorized=true)
                    dialog.dismiss()
                    toast(if(plan.credentialRebindRequired) "配置已恢复；请重新输入并保存密钥，工具保持关闭" else "非秘密配置已恢复；工具保持关闭")
                } catch (_:Exception) {
                    dialog.dismiss()
                    toast("配置未恢复；原配置保留，若存储补偿失败须重新保存。请等待操作结束并重新预览")
                }
            }
        }
        dialog.show()
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
            try {requireActivityUsable();check(settingsDialog===dialog && dialog.isShowing);check(controller.active==null && !controller.dataBusy);controller.settings.save(ModelSettings(endpoint.text.toString().trim(),model.text.toString().trim(),key.text.toString(),battery.isChecked,path.text.toString().trim(),deviceStatus.isChecked));dialog.dismiss();toast("模型设置已保存")}
            catch (_:Exception) {toast("设置未保存；请检查HTTPS地址、模型与密钥")}
        }}
        settingsDialog=dialog
        dialog.setOnDismissListener {if(settingsDialog===dialog) settingsDialog=null}
        dialog.show()
    }
}
