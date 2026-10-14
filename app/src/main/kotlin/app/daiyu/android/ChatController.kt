package app.daiyu.android

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import app.daiyu.android.domain.*
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.platform.AndroidReadOnlyTools
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.Executors

class ChatApplication:Application() {
    lateinit var controller:ChatController
    override fun onCreate() {super.onCreate();controller=ChatController(this)}
}
class ChatController internal constructor(private val application:Application,
    val database:ConversationDatabase, val settings:ModelSettingsStore, val preferences:SharedPreferences,
    private val model:(ModelSettings)->ModelPort) {
    constructor(application:Application):this(application,ConversationDatabase(application),ModelSettingsStore(application),application.getSharedPreferences("conversation-ui",0),{ResponsesModel(it)})
    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val listeners=mutableSetOf<()->Unit>()
    internal val selections=mutableMapOf<ConversationId?,Pair<Int,Int>>()
    @Volatile var active:Message?=null;private set
    @Volatile var problem:String?=null;private set
    @Volatile private var runner:ChatRunner?=null
    @Volatile var ready=false;private set
    init {executor.execute {try {database.recover()} catch (_:Exception) {problem="会话恢复失败，原数据已保留"};ready=true;notifyChanged()} }
    fun observe(listener:()->Unit) {listeners.add(listener)}
    fun unobserve(listener:()->Unit) {listeners.remove(listener)}
    private fun notifyChanged() {handler.post {listeners.toList().forEach {it()} } }
    fun send(conversation:Conversation?,text:String):Conversation? {
        if(!ready || active!=null || problem?.startsWith("会话恢复失败")==true || text.isBlank()) return null
        val config=try {settings.load().also {it.validate()}} catch (_:Exception) {problem="请在模型设置里配置Responses地址、模型与密钥";notifyChanged();return null}
        val conversationId=conversation?.id ?: ConversationId(UUID.randomUUID().toString())
        val run=RunId(UUID.randomUUID().toString())
        val user=Message(MessageId(UUID.randomUUID().toString()),conversationId,run,"user",listOf(MessageBlock.Text("user",text)),RunStatus.COMPLETED)
        val assistant=Message(MessageId(UUID.randomUUID().toString()),conversationId,run,"assistant",emptyList(),RunStatus.RUNNING)
        val prior=try {if(conversation==null) emptyList() else database.messages(conversationId,limit=50).map {it.second}} catch(_:Exception) {problem="读取历史失败；草稿保留，未发起网络请求";notifyChanged();return null}
        val saved=try {
            database.send(user,assistant,conversation==null)
        } catch (_:Exception) {problem="发送内容保存失败；草稿保留，未发起网络请求";notifyChanged();return null}
        problem=null;active=assistant
        val tools=config.enabledTools()
        val owned=ChatRunner(ModelPort { history -> model(config).open(history,tools) },database,AndroidReadOnlyTools(application,tools)) {
            if(active?.id==assistant.id && active?.runId==run && it.id==assistant.id && it.runId==run && it.conversationId==conversationId) {active=it;notifyChanged()}
        }
        runner=owned
        executor.execute {
            val result=owned.run(assistant,prior+user)
            if(runner===owned && active?.id==assistant.id && active?.runId==run) {
                runner=null
                if (result.error?.startsWith("保存失败")==true) { active=result;problem=result.error }
                else active=null
                notifyChanged()
            }
        }
        notifyChanged();return saved
    }
    fun stop() {runner?.stop()}
    internal fun close() {stop();executor.shutdown();check(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));database.close()}
}
