package app.daiyu.android

import android.app.Application
import android.os.Handler
import android.os.Looper
import app.daiyu.android.domain.*
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.platform.AndroidBatteryTool
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.Executors

class ChatApplication:Application() {
    lateinit var controller:ChatController
    override fun onCreate() {super.onCreate();controller=ChatController(this)}
}
class ChatController(private val application:Application) {
    val database=ConversationDatabase(application)
    val settings=ModelSettingsStore(application)
    val preferences=application.getSharedPreferences("conversation-ui",0)
    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val listeners=mutableSetOf<()->Unit>()
    @Volatile var active:Message?=null;private set
    @Volatile var problem:String?=null;private set
    @Volatile private var runner:ChatRunner?=null
    @Volatile var ready=false;private set
    init {executor.execute {try {database.recover()} catch (_:Exception) {problem="会话恢复失败，原数据已保留"};ready=true;notifyChanged()} }
    fun observe(listener:()->Unit) {listeners.add(listener)}
    fun unobserve(listener:()->Unit) {listeners.remove(listener)}
    private fun notifyChanged() {handler.post {listeners.toList().forEach {it()} } }
    fun send(conversation:Conversation,text:String):Boolean {
        if(!ready || active!=null || text.isBlank()) return false
        val config=try {settings.load().also {it.validate()}} catch (_:Exception) {problem="请在模型设置里配置Responses地址、模型与密钥";notifyChanged();return false}
        val run=RunId(UUID.randomUUID().toString())
        val user=Message(MessageId(UUID.randomUUID().toString()),conversation.id,run,"user",listOf(MessageBlock.Text("user",text)),RunStatus.COMPLETED)
        val assistant=Message(MessageId(UUID.randomUUID().toString()),conversation.id,run,"assistant",emptyList(),RunStatus.RUNNING)
        val history=try {
            val prior=database.messages(conversation.id,limit=50).map {it.second}
            database.send(user,assistant);prior+user
        } catch (_:Exception) {problem="发送内容保存失败；草稿保留，未发起网络请求";notifyChanged();return false}
        problem=null;active=assistant
        val owned=ChatRunner(ResponsesModel(config),database,AndroidBatteryTool(application,config.batteryEnabled)) {
            active=it;notifyChanged()
        }
        runner=owned
        executor.execute {
            val result=owned.run(assistant,history)
            runner=null
            if (result.error?.startsWith("保存失败")==true) { active=result;problem=result.error }
            else active=null
            notifyChanged()
        }
        notifyChanged();return true
    }
    fun stop() {runner?.stop()}
}
