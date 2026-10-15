# 15号宿主交接合同 v1（PROPOSED，未冻结）

## 0．范围、证据与合流门槛
- 本件仅补宿主合同；助手、侧栏、正文搜索、会话搜索、模型弹窗五旧任务结果未知，不重放、不实现、不据此判失败。
- 本件不声明公共入口、数据库、运行或UI业务接线完成；不修改代码、清单、skill，也不改变其他编号归属。
- 已读 `docs/并行分工.md` 公共规则及01/03/04/05/11/15归属；已核 `docs/重构清单.md:64,69-70,90-91` 的P01/P06/P07/P27/P28。
- 下文源码路径均相对仓库根；只表示本工作树实读结果，不代表候选已合流或最新main状态。
- 必读外部记录因工具隔离均返回 `WORKSPACE_PATH_DENIED`，未能核实：
  - `/workspace/daiyu-coordination/task-01/interfaces-v1.md`
  - `/workspace/daiyu-coordination/task-03/interfaces-proposed.md`
  - `/workspace/daiyu-coordination/task-04/handoff-latest.md`
  - `/workspace/daiyu-coordination/task-05/integration-requests-v2.md`
  - 01新发布 `/workspace/代鱼/docs/integration/接口契约-v1.md`。
- **以下为父提供，未本任务独立核**：01 v1保留ModelPort/ConversationStore，历史MessageHistorySegment目标已批准；助手/搜索公共入口尚未冻结。
- **父提供，未本任务独立核**：03正式 `444bfd09` 为历史codecs成品但无assistantId，助手绑定API尚未提供；不据此否定其历史成品。
- **父提供，未本任务独立核**：05请求显式assistantId的AtomicFile memory service；profile/persona/memoryEnabled/skills绑定归15，不可另存memory。
- **父提供，未本任务独立核**：04最终 `2c213187`、project=`/workspace/daiyu-task04-fix-f-correction`，宿主JUnit22 passed，仅后端未UI接线；本件不猜其方法名，也不转授为UI验收。
- 本件所有新增数据/操作均为 **PROPOSED语义**，不是可调用签名或已冻结接口；已批准的历史目标不等于本件宿主提案获批。
- 01须先对照上述正式记录和对应候选源码复核差异，签认版本后再接线；未知证据不能用本提案顶替。

## 1．现有API与明确缺口（精确实读）
路径缩写：`A=app/src/main/kotlin/app/daiyu/android`，`D=core-domain/src/main/kotlin/app/daiyu/android/domain`。

| 现有入口 / 来源 | 已核行为与缺口 |
|---|---|
| `D/Conversation.kt:4`：`Conversation(id: ConversationId, title: String, draft: String, updatedAt: Long, folder: String = "", pinned: Boolean = false)` | 无assistant归属、profile、preview、持久revision。 |
| `A/storage/ConversationDatabase.kt:37`：`fun conversations(query:String="",folder:String?=null): List<Conversation>` | 同步；folder精确过滤；pinned降序、updated降序、id升序；query最长1000、folder最长200。 |
| 同文件:45-55 | 非空query字面、大小写敏感地匹配title/draft、Text.text、Tool.arguments/result；不是title-preview专用搜索，无分页/取消/快照token。 |
| 同文件:57-69：`organize(id:ConversationId,folder:String,pinned:Boolean)`、`create():Conversation`、`rename(id: ConversationId, title: String)`、`delete(id: ConversationId)` | 已有组织/创建/改名/删除；无助手绑定、独立文件夹实体/组状态接口。 |
| 同文件:77：`fun send(user:Message,assistant:Message,newConversation:Boolean=false):Conversation` | user与assistant同conversation/run，事务写双消息并清草稿；assistant是消息角色，不是助手实体归属。 |
| 同文件:254：`fun messages(id: ConversationId, before: Long = Long.MAX_VALUE, limit: Int = 50): List<Pair<Long,Message>>` | seq严格小于before，倒查后反转为升序，limit=1..200；没有命中检索、固定快照、按message加载窗口。 |
| `D/Conversation.kt:5-11`：`MessageBlock.Text(itemId,text)`、`MessageBlock.Tool(invocation,result)`、`Message(...,role,blocks,status,error,protocolItems)` | 当前工具记录内嵌assistant.blocks；不能臆造独立tool消息或候选segment结构。 |
| `A/ChatController.kt:37-40`：`observe(listener:()->Unit)`、`unobserve(listener:()->Unit)`、`send(conversation:Conversation?,text:String):Conversation?` | 通知post主线程；send同步读配置/历史/保存后单executor跑Runner；无助手/skills/memory冻结参数。 |
| 同文件:41-68 | 仅冻结本次加载的ModelSettings及enabledTools；active为运行Message，不是全局active助手；失败null配problem，不可当新合同错误类型。 |
| `A/MainActivity.kt:317`：`internal fun jumpToMessage(id:MessageId)` | 明确check已加载且role=user；不加载更早页，不能用来定位assistant/tool命中。 |
| `A/storage/ModelSettings.kt:20,130,151,158` | `ModelSettings(endpoint,model,apiKey,batteryEnabled,responsesPath,deviceStatusEnabled)`；`loadForEditing():EditingSettings`、`load():ModelSettings`、`save(settings:ModelSettings):Unit`；当前是单配置存储，未见远端目录/批量新增入口。 |
| `A/context/ContextService.kt:24-29` | `constructor(context: Context, store: ConversationDatabase)`；`prepareSend(id: ConversationId, policy: CompressionPolicy = CompressionPolicy(), summarizer: ContextSummarizer? = null): PreparedContext`；当前无助手记忆开关参数。 |
| 同文件:27,96 | `displayHistory(id: ConversationId): List<Message>`；`archivePage(archiveId: String, offset: Int = 0, limit: Int = 50, expectedOwner: ConversationId? = null): ArchivePage`；归档分页不是正文搜索API。 |

兼容红线：保留旧`conversations(query,folder)`正文命中语义和测试，不静默改成title-preview过滤。
证据：`app/src/androidTest/kotlin/app/daiyu/android/PlatformTest.kt:233-252` 的 `testConversationOrganizationSearchAndVersionTwoMigration` 明确断言正文词、引号换行命中及非内容字段不命中。
定位红线：`ActivityTest.kt:190` 明确拒绝assistant；保留原方法/测试，为搜索新增通用加载定位接缝。

## 2．PROPOSED：跨01/03/05/11的助手归属与运行冻结
- 03持有会话持久归属接缝；15负责助手profile/persona/memoryEnabled/skills绑定数据及消费，11仍持有skill索引/版本快照，01负责公共发送入口。严禁从当前全局选中助手推断旧会话归属。
- 最小持久数据：conversationId、assistantId（可表示未绑定）、bindingRevision；助手实体另有assistantRevision。
- 老数据迁移须保留“未绑定”，显式选择/迁移后才绑定；助手不存在返回MissingAssistant，不静默替换全局active。
- 新会话首次发送的归属提交须与会话/消息创建协调为一个可确认提交；失败不发网络、保草稿，不留下只在内存生效的归属。
- 修改归属须带expectedBindingRevision；03事务校验会话存在/版本/运行状态，返回提交后的归属与revision，冲突不得覆盖。
- 默认运行中拒绝改绑；助手编辑/删除策略由03/15确认，已有run仍使用冻结值，后续发送遇删除须显式处理。
- 01在run入口一次解析并冻结：conversationId/runId、assistantId及revision、profile（所选模型/配置引用及revision）、persona正文、memoryEnabled、skills快照引用。
- 此profile是提案数据概念，不是本树已存在类型；profile与persona不可在模型续请求、重连、暂停继续时重新读取可变全局状态。
- 11提供run级不可变skills快照（skill身份、固定版本/commit或内容摘要、资源与权限快照）；缺失/失效显式失败，不自动安装、升级或执行脚本。
- 05唯一负责记忆读取/存储/上下文拼装；消费其显式assistantId的AtomicFile服务（父提供，方法待核），15不建重复记忆表/文件，不把persona或skill内容当第二套记忆持久化。
- memoryEnabled=false的run不得读取/注入或写回助手记忆；true也只在05授权scope内访问，绑定ID不是权限凭证。
- 05接收宿主冻结的身份/开关/scope并提供上下文准备回执；具体方法及记忆写回时点待05正式API，不向现有prepareSend猜塞参数。
- 所有续请求继承同一冻结配置和授权；停止/重启不自动重放未知模型请求、记忆写入或工具副作用。

## 3．PROPOSED：P28 title-preview与P27正文/工具搜索分离
- 新增专用语义入口，不改旧conversations：SidebarFilter只看title/preview；HistorySearch只查可展示正文及工具参数/结果。
- SidebarFilter输入：query、folder范围、可选assistant归属、requestGeneration、pageSize、cursor；空folder是未分类，null是全部。
- preview由03/05确认单一派生来源与revision，不用draft冒充，不扫描全量正文冒充preview；截断长度/规范化规则须冻结。
- title与preview同一文字比较规则（提案为大小写敏感字面子串，非正则/SQL通配），与旧入口分别回归。
- 删短/清空query产生新generation、重置cursor/快照并恢复当前范围的完整分页列表，不能残留旧结果。
- HistorySearch输入：当前conversation或全记录的显式scope、query、pageSize、cursor、requestGeneration；可叠加文件夹/助手过滤。
- 命中输出：conversationId、messageId、runId、可选segment/attempt定位、block身份/位置、kind(Text/ToolArguments/ToolResult)、摘要及文本范围。
- segment/attempt仅在03正式历史模型核实后映射，不杜撰当前Message已有字段；摘要不泄漏协议元数据、密钥或越权会话。
- 03定义稳定排序键与唯一命中键；同message多个block可分别命中，跨页无重复/遗漏，不能仅用updatedAt当cursor。
- 首页返回opaque snapshotToken、nextCursor、hasMore及dataRevision；后续cursor绑定scope/query/sort/snapshot，参数不符拒绝。
- 快照固定内容与顺序；编辑/删除/导入/归档修订导致无法保持时返回SnapshotExpired要求刷新，不能混入新历史假装一致。
- 无命中返回成功空页；归档是否纳入必须有明确scope与权限，不能把压缩后摘要当原文命中或漏掉原文而宣称全记录。
- 每次输入/范围变化先取消旧请求并推进generation；底层支持协作取消，UI仍按generation+snapshot二次丢弃迟到结果。
- 搜索不自动切会话或触发模型/工具；点击命中才发定位请求，保留当前草稿/选择，不改变全局助手。
- 新通用LocateHit输入命中身份、snapshot/revision、navigationGeneration；03加载包含目标的有界窗口并核真实归属。
- 01让宿主选择目标会话、合并窗口去重，待布局后定位Text或Tool子块；不得调用现有user-only方法假装已定位。
- 定位输出Located或NotFound/ContentChanged/Forbidden/Cancelled；目标删改、归档缺失不得跳到相邻消息冒称成功。
- 用户触摸滚动、切会话、新定位、页面销毁须取消待加载/布局定位；迟到回调不得滚动新页面。

## 4．PROPOSED：跨01/04的远端模型目录与P06提交
- 04负责后端拉取与本地目录提交，15仅消费目录与勾选状态；实际候选签名须由01读正式候选后填写，禁止用ModelSettingsStore.save模拟批量目录导入。
- 拉取输入须冻结providerId、accountGeneration、配置revision及requestGeneration；凭据仅由04解析，不传给UI、日志或持久cursor。
- 结果包含同一账户代际、远端稳定modelId及可展示能力元数据；拉取本身零本地目录写入，失败保留旧列表/本地配置并明确状态。
- 勾选以远端稳定ID集合为准；搜索只是显示过滤，不偷偷改变隐藏项选择；全选/反选作用域须明确为当前过滤结果。
- 确认禁用条件：加载/提交中、选中为空、账户或目录快照失效；计数区分已选/可见/已存在，已存在项不当新增。
- 提交输入：providerId、accountGeneration、目录快照、selectedIds、期望本地revision及幂等operationId。
- 仅新增所选且尚不存在的模型；未选不加、已有不覆盖、不删除，不修改自定义名称、能力覆写、请求体、档位及其他本地字段。
- 04在实际写入点复查账户代际/配置与所选ID属于该快照；切账户/重绑/删除供应商后旧结果一律StaleAccount，不能串账户落库。
- 返回addedIds/skippedExistingIds与提交revision；失败不得报成功数量。提案要求原子提交，若04仅支持部分结果须先显式修订合同。
- 关闭弹窗/重建取消拉取并使generation失效；提交已持久化则以回执或operationId查询确认，不盲重试未知提交。

## 5．PROPOSED：公共线程、错误、生命周期与责任确认
- 01持有UI状态和生命周期；03执行DB事务/加载，04执行网络/目录提交，05执行Context/记忆，11提供skills冻结快照，15不跨界直写其存储。
- 阻塞DB/网络/快照准备在所属worker执行，主线程仅发布不可变view-state；不得持UI锁或Runner控制锁等待I/O。
- 每项异步操作携带owner身份、generation及必要revision；回调在主线程核owner仍有效再发布，取消不等于已回滚持久提交。
- 统一最小错误语义：InvalidInput、NotFound、Forbidden、Busy、Conflict、SnapshotExpired、MissingAssistant、MissingSkill、StaleAccount、CredentialUnavailable、Network、Persistence、Cancelled。
- 这些是提案分类非已有异常类型；边界适配已有异常/null+problem，保留可恢复性/是否已提交/是否可安全重试，不展示秘密或原始响应体。
- 页面关闭注销observer、取消搜索/拉取/定位并释放快照；配置变化/导入/删除使旧revision失效；run冻结值生命周期独立于页面。
- 03待交缺口：归属迁移/读写API、两搜索snapshot/cursor及加载窗口；04待核并对接既有候选目录API/账户代际回执，不重做后端；05/11待核并对接记忆开关与run快照消费API。
- 01待审：与已发布v1契约对照、公共入口调用顺序、兼容回归与生命周期测试；签认前以上均保持PROPOSED。
- 交接验收建议：重开归属不串助手、编辑不影响既有run、关记忆不访问存储、搜索取消/跨页/删改/通用定位、旧正文兼容、模型仅新增所选及切账户迟到隔离。
- 本次只做有界源码/文档核查和文档产物；未执行构建、测试、GUI、CI、网络、APK或Git操作。由父独立审查后安排runtime合流，不据此更新功能完成状态。
