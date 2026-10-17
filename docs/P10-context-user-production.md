# P10 / P22 / R11 用户侧上下文：真实组件与待接入边界

## 交付状态（不要写成生产闭环）

本增量提供可渲染的独立 `ContextManagementPanel` / `ContextManagementRoute`、
受控 `ContextManagementControllerAdapter`、真实本地读取/手动压缩 worker 和定向测试源码。
**Main 的 `chatActions.onContextClick` 仍为 `pendingFeature()`；ChatController 尚未实现
本页的 admission/lease。因此普通用户入口没有接通，不能称为生产完成。**

本次叶子修复针对任务所述已暂存 `47caced` 的授权缺口；本代理没有 shell/Git 能力，
没有独立核验该提交或 HEAD、执行 commit、
构建、仪器测试或设备操作。实际非空文件修改交由父代理审核、构建测试与提交。
没有发真实摘要请求或聊天请求。

限定范围内组件与定向测试文件如下（本次只作必要修改，不改父接线）：

- `app/src/main/kotlin/app/daiyu/android/context/ContextManagementWorker.kt`
- `app/src/main/kotlin/app/daiyu/android/context/ContextManagementControllerAdapter.kt`
- `app/src/main/kotlin/app/daiyu/android/context/SummaryProfileStore.kt`（仅必要非秘密 descriptor/revision 与同锁冻结端口）
- `app/src/androidTest/kotlin/app/daiyu/android/context/SummaryProfileStoreTest.kt`
- `app/src/main/kotlin/app/daiyu/android/ui/screens/chat/context/ContextManagementPanel.kt`
- `app/src/test/kotlin/app/daiyu/android/context/ContextManagementControllerAdapterTest.kt`
- `app/src/androidTest/kotlin/app/daiyu/android/context/ContextManagementWorkerTest.kt`
- `app/src/androidTest/kotlin/app/daiyu/android/ContextManagementPanelTest.kt`
- 本说明。

未改 MainActivity、ChatController、ContextService、既有自动压缩 send、schema、
消息分支/编辑接口、CI 或唯一清单。usage 不是本组件的实测用量来源，允许另路通过受控
adapter 展示，但不把此页 `estimatedTokens` 当作 provider receipt。

## 静态核对的真实来源

以下是工作树源码证据，不是网络资料或已运行测试的报告：

1. `MainActivity.chatActions`：上下文点击仍 pending；用户入口不能靠新组件自行生效。
2. `ChatController`：`idleAvailable`、`reservePreparation`、`Preparation.cancellation`、
   `preparationCancelled`、`finishPreparation` 和共享 `contextService` 都是内部私有实现；
   外部 extension/helper 无权安全获取同一准备槽。`sendPrepared` 已用
   `contextPreparation.freeze/prepare`，并在原发送事务内验证
   `contextService.matchesHistoryRevision`。本增量不重复接线或替换这条路径。
3. `ContextPreparationCoordinator.freeze`、`freezeContextMessages`：冻结设置、明确独立绑定、
   单次快照、工具禁用摘要适配、父取消对象；复用它们，不重造摘要 transport。
4. `ProductionCompressionSettings`、`ProductionCompressionPolicyResolver`、
   `ProviderSummaryModelResolver`、`SummaryProfileStore`：复用现有 v2 完整轮设置及保全候选。
   不拿旧消息数当轮数、不猜窗口、不降级聊天模型。
5. `ContextService.displayHistory/prepareSend/compress/archiveOwner/archivePage/readQuery`：
   完整权威消息、当前有效摘要投影、归档验证、全历史版本 CAS、发布事务内取消与原文保全。
   `ScopedContextTools` 仍是现有模型只读工具入口，本页不新增工具或绕过 registry 授权。

## 现在可实现的 UI 行为

- 确切 `ConversationId + generation`，不使用标题、父分支 ID、分页可见尾消息来确定归属。
  generation 是宿主选择/编辑器代次，不能仅用会话 ID：切走再切回来也必须是新身份。
- 同一数据库读快照内取得完整物理原文版本和有效上下文（有效摘要 + 保留尾部）。
  浏览使用无 summarizer 的 `prepareSend` 默认只读投影，即使超预算也能本地查看。
  打开/刷新另只读 SummaryProfileStore 原子非秘密 metadata，取得真实请求 endpoint（含协议路径）、
  API model 与保存 revision；不将 opaque binding UUID 当成收费目标。浏览与档案翻页均不解析
  含凭据模型、不调用 ModelSettingsStore.load/keystore、不读取摘要密钥、不联网。
  profile 不可用时原文仍可看，但不能手动压缩；旧 v1 profile 无 revision 时要求明确重存，
  不以 binding 或 provider hash 合成可重用授权。
- 原文/有效消息按存储顺序显示可选择的完整消息 JSON，保留工具参数/结果、协议和历史
  片段，不作为 Markdown/网页渲染，不执行其中指令。图片为引用证据，不冒充已观看图片。
  视频旁表元数据未投影到本页；现有 Service 的视频压缩边界保护仍保留。
- 当前有效档案 ID 来自同一个 `PreparedContext.archiveId`，不猜测、不枚举其它会话或
  历史非活动档案。翻页先在同一读事务验证完整原文版本、当前 locator、持久化 owner，
  再用 `archivePage(... expectedOwner=精确会话)` 每页 50 条读取完整性校验过的原文。
  损坏、缺失、归属不符或 locator 失效拒绝展示新结果；提示刷新，不静默显示其它档案。
- 手动压缩先显示真实独立摘要 endpoint/API model、明确窗口与完整保留轮，确认区域再次
  显示该收费目标，再单独明确确认可能
  发送原文/收费。确认不是发送新聊天消息，不修改草稿，不执行工具，不提供分支/编辑/删除。
- 压缩期间显示同槽占用与取消/等待退出。关闭页面取消同一个准备 token，丢弃迟到结果；
  不提前清空父准备槽，不将旧完成回调归属到新会话。

## 手动路径怎样复用既有准备而不重做 send

`ContextManagementWorker.compress(lease, expectedSnapshot)` 只应由受控 adapter 在父 lease
下于父工作线程执行。它不是可直接挂到 UI 点击的 Service 捷径。

1. 同一 reservation 下检查宿主身份和取消；在读事务内比较**完整权威原文** digest、
   持久化会话存在性与当前有效 archiveId，拒绝旧 UI 快照。不是只比较压缩前缀或分页。
2. 再比较当前设置与用户确认快照的设置。缺明确窗口、独立绑定、完整轮设置或非秘密
   真实目标/revision 则拒绝。浏览仍可用；设置/profile 损坏不阻挡读取完好原文。
3. 只对这次手动意图将冻结设置的 `automatic` 投影为 true，然后调用既有
   `ContextPreparationCoordinator.freeze(id, emptyList(), lease.cancellation)`。
   这不写 preferences，不开启以后的自动压缩；自动开关关闭时仍可明确手动。
   其 resolver 使用 worker-only `ContextManagementSummaryProfiles.freeze(expectedDescriptor)`：
   在 SummaryProfileStore **同一个 save lock** 内先比较整个 descriptor/revision，再加载并捕获
   唯一 credential-bearing configuration。每次成功 save 都写新随机 UUID revision，包括
   相同 binding 换 endpoint、API model、key-only、预算/选项以及完全同值重存；A→B→A 也不复用。
   旧确认遇到 revision 改变/缺失/保存中时在密钥读取与任何网络前拒绝，UI 要求只读刷新并
   重新明确确认。不能先检查 revision、松锁后再按当前 binding 查候选。
   通过校验后 ProviderSummaryModelResolver 仅封装**这一个已捕获 configuration**，不再查
   当前 profile；后续 save 不会将该 frozen 候选重定向到新 endpoint/model/key。
   UI state 仅包含 settings 和非秘密 descriptor，不包含 ModelSettings、ProviderSnapshot、
   SummaryProviderConfiguration 或 transport。缺/错/不可用候选拒绝，不回退聊天模型。
4. 单次消费这个 frozen，然后调用既有 `ContextService.compress`，只绕过自动压力阈值。
   不改变保留轮、预算、切口、视频限制或全历史 CAS；pending 是空列表，不入库、不发送。
   Service 在模型 I/O 之外取得权威快照，在模型返回后的发布事务内再次校验**包括保留尾部**
   的完整历史，校验容量/缩小/取消后才发布档案。原始消息表不被本操作删除或改写。
5. 不再调用 `coordinator.prepare` 或再次 `prepareSend` 开 summarizer，不增加失败重试、
   分块、重发或聊天模型 fallback。无安全前缀返回明确 no-op，不打开摘要 transport。
   成功 receipt 后不添加可失败的后置刷新/校验；UI 明确标记旧快照过期，用户只读刷新核对。

所有会话写入口必须遵守同一个父 reservation；仅前置比较 digest、另建 mutex、UI 布尔
`busy` 或 Service 自身“无运行消息”检查，都不能替代该准入约束。父绕过 reservation 的
写入不在组件可保证的范围；模型期间的历史变化由既有 Service 全历史 CAS 拒绝应用。

## 父需要提供的准确最小接缝（目前未实现）

没有示例假回调，没有 `true`/空实现生产兜底。源码给出强制接口，未提供可误用的默认
admission 实现。生产接入前，父代理必须在有权访问私有成员的 ChatController 内提供：

### `ContextManagementAdmission`

- `isCurrent(owner: ContextManagementOwner): Boolean`：短同步内纯内存核对当前页面/编辑器
  注册身份、精确会话和 generation、closed 状态。不得主线程查库或读密钥。
- `tryReserve(owner): ContextManagementLease?`：在现有 controller monitor 的同一临界区
  核对 owner/generation、`idleAvailable()`、`hasLiveChildTasks(owner.conversationId)` 等现有
  禁入条件，然后真实调用 `reservePreparation()`。忙、切会话、删除、关闭则返回 null。
  不能拿普通 `canSend`/Compose busy 值替代，也不能改成独立队列。

### `ContextManagementLease`

- `owner` 是准入时不可变身份；adapter 拒绝错 owner 的 lease。
- `cancellation` 必须直接引用此次 `Preparation.cancellation`，不能新建另一取消旗标。
- `checkCurrent()` 是无 I/O 的短同步检查：原 `preparation === token`、未 closed、未 revoked、
  owner/generation 仍有效；持久化 owner 存在性由 worker 读事务检查。
- `finish()` 是不抛异常、按该 token 身份调用既有 `finishPreparation(token)` 的释放。
  **仅 worker 真正退出（或 executor 尚未开始就拒绝）时执行一次**，不能 cancel/close 就释放。
  admission 到 worker 排队之间也持有 reservation；父关闭存储必须继续遵守该准备槽。
- 父 stop/close/换会话/删除/页面失效必须撤销原 token，并在 monitor 外取消这个
  cancellation，确保活动摘要 session 真正收到 cancel。新页面新 adapter，不复用旧 visit。

### 组合与宿主身份生命周期

父内部必须在既有 `executor` 的 worker 任务中延迟取得共享 `contextService`，然后才构建
`ContextManagementWorker.production(application, contextService)`；不要在 UI 打开页面、
adapter 构造或 controller monitor 内求值 lazy `contextService`。它的首次初始化会构造
ContextService/archive store，可能执行目录 `mkdirs` 等磁盘 I/O，不能搬到主线程。
可由父传入一个 worker-only 的延迟 backend 委托，在取得同一个 Preparation admission 后，
每个 backend 方法执行时才解析共享 service/worker；这不是新 Service 实例或独立准备槽。
将真实 admission、既有 `executor` 和主线程 Handler 包装的 UI Executor 传入
`ControlledContextManagementAdapter`。
`worker.execute` 若拒绝必须保证任务未开始；UI executor 在正常宿主生命周期接收完成通知。
组件使用 worker-drain 后的 UI 回调二次核对 visit 当前性，避免旧页面覆盖新选择。

### 精确接线合同（父后续实现；以下新增宿主方法当前不存在）

任务补充说明父主线为 `8058f20`；本代理不能查 Git，以下是当前可读源码的合同，不声称
该主线已实现或验收。实际现有接口/字段：Main 的 `chatActions()` 捕获
`renderedConversation = chatState.conversationKey`、`renderedEditorGeneration = editorGeneration`，
其 `editorMatches()` 校验 `activityUsable()`、`selectionReady`、无 `pendingTextSend`、
`controller.textSendState() == null`、精确 `selected?.id?.value` 与代次；它本身不检查
`retainedPage == "chat"`，新入口必须额外检查页面当前性；当前 `onContextClick = { pendingFeature() }`。Controller 现有
`reservePreparation(textTicket: TextSendTicket? = null): Preparation`、
`preparationCancelled(token: Preparation): Boolean`、`finishPreparation(token: Preparation)`、
`idleAvailable()`、`hasLiveChildTasks(conversationId: ConversationId? = null)` 及
`executor` / `handler` / lazy `contextService`，不得替换或暴露 Preparation 到 Main/UI。

父应在 Controller 内补齐以下**确切待实现签名**（不是扩展函数或假实现）：

```kotlin
internal fun openContextManagement(owner: ContextManagementOwner): ContextManagementControllerAdapter?
internal fun invalidateContextManagement(expected: ContextManagementControllerAdapter)
```

- `openContextManagement(owner)` 由主线程调用。Main 的点击先要求 `editorMatches()`、
  `retainedPage == "chat"`、
  `selected != null`、捕获会话非空且等于 `selected.id.value`、当前 generation 等于捕获值；
  用 `ContextManagementOwner(selected.id, renderedEditorGeneration)`，不能创建新会话或拿父分支。
  Controller 在短 monitor 内拒绝 closed/not-ready/dataBusy/recoveryFailed、不满足
  `idleAvailable()`、`hasLiveChildTasks(owner.conversationId)`、已有未失效 visit 的情形，返回
  null，Main 仅提示未打开/稍后再试，不保存 page、不排队重试、不发请求。持久化 owner 是否
  存在由首次 worker snapshot 的 readQuery 检查；删除/损坏在完成通知中提示失败，不自动新建。
- 成功时注册不可复用 visit（adapter 身份 + owner），返回 `ControlledContextManagementAdapter`；
  此方法不查 DB/密钥、不求值 lazy service、不先占 Preparation。Main 保存该 adapter 和 owner
  为页面实例，展示 `ContextManagementRoute(adapter, onClose)`。Route 自身仅 refresh/read。
  同 ID 离开再进入也必须是新 visit；不能仅靠 conversationId 判断当前性。
- adapter admission 的 `isCurrent(owner)` 必须在纯内存核对注册 adapter 身份、owner 全值与
  closed；`tryReserve(owner)` 另在同一个 Controller monitor 内重复该检查及
  `idleAvailable()` / `hasLiveChildTasks(owner.conversationId)`，失败返回 null；成功调用现有
  `reservePreparation()`，lease 直接引用该 token 的 cancellation。`checkCurrent()` 还要
  检查 `preparation === token`、`!token.revoked`、`!preparationCancelled(token)`。
  不持 monitor 运行后端、load、mkdir 或 cancel；`finish()` 仅按该 token 调现有
  `finishPreparation(token)`，不能直接 `preparation = null`。
- `invalidateContextManagement(expected)` 由主线程调用，按 `===` 比较当前注册 adapter；
  不匹配是幂等 no-op，绝不能撤销新 visit 或新 token。匹配时先在 monitor 内移除 visit、
  将它持有的 Preparation token 标记 revoked，再在 monitor 外调用同一 cancellation.cancel()
  和 `expected.close()`。若没有 active token，仅清注册和 close；不提前 finish/release。
- Main 的 `onClose`、系统返回、切走 retainedPage、切换 selected、创建新会话、删除当前会话、
  editor generation 变化、activity recreate/destroy，在改变选择/页面或清存储**之前**同步
  调用 invalidate(expected)，清除本地 adapter，并推进 generation；Controller 的 stop()/close()
  也必须撤销当前 visit 并取消同 token。正常 Route dispose 会再次 close，是幂等的，不能
  代替前置 invalidate 注册。UI 迟到回调不能显示旧历史/档案或释放新 token。
- 初次 refresh 失败、目标/revision 不可用、executor 拒绝和压缩失败均留在该精确页面提示；
  不跳转到新会话、不自动 confirm、不回退聊天模型。revision 改变的确认要求显式 refresh
  后重新 requestCompression + confirmCompression。close 后所有旧确认零 backend 操作。
- `executor` 必须使用现有父单线程执行器；UI executor 可包装现有 `handler.post`，其正常
  生命周期要接收 drain 通知。Controller.close 的存储/线程池关闭遵守既有准备槽 drain，
  不能因为页面 close 先关闭 database 或丢弃尚未执行、负责 finish 的已准入 worker。

以上合同需要父在 Main/Controller 有权限处实现并端到端验证，本叶子未改这两个文件。
不能在 `onContextClick` 直接构造 Service/compress，不能拿永远 true admission 或 UI busy
代替注册和同一 Preparation。当前 Controller 没有通用“已选会话 + generation”注册字段，
因此仅添加公开 compress 方法不足以接线；此项仍是生产阻塞。

## 失败、取消与 UNKNOWN 的准确保证

- 失败/取消/非缩小/预算不足/完整历史 CAS 失败发生在 Service 档案发布事务提交前，
  原文保留，原有效档案不被替换；没有自动重试。
- 不声称“取消一定撤回档案”：取消可能在发布已经提交后到达，SQLite 提交异常也可能
  无确认。adapter 在 mutation 异常后将结果视为待核对、禁用再次压缩和旧 locator 翻页，
  只允许显式本地刷新；不自动重发，不补偿删除档案。
- 成功、no-op、取消或未知完成后均需要只读刷新再做下一次明确操作。普通用户明确刷新、
  重新阅读快照并再次确认是新的用户意图，不是隐藏重试。
- 原文不被这条路径改写，不扩大为“其它并发编辑也会被撤销”；P37 的编辑/分支另路处理。

## 测试源码与未验证项

现有及补充定向测试源码均未在本代理环境执行；按任务要求不运行测试或 CI，由父最终统一验证：

- adapter JVM 测试源码：新增离页旧确认、刷新销毁旧确认、profile revision 改变提示刷新重确认；保留构造/确认零请求、同槽忙拒绝、取消到 worker drain 才释放、
  活动摘要取消期间禁入、关闭/切换迟到回调隔离、分页 offset、UNKNOWN 只读刷新、
  成功不重试、executor 拒绝仅释放一次。admission 是确定性 fixture，不是 Controller 接通证据。
- worker 仪器测试源码：新增真实 metadata/profile save 的同 binding 换 endpoint/API model/key-only/同值重存，
  旧确认零 credential read/零模型解析/零 transport，刷新后方可压缩，以及目标不可用仍可浏览；保留真实本机 DB/Service/归档/coordinator，假 tool-free transport；
  主线程误用在存储/模型读取前拒绝、精确全历史快照与预算外本地浏览、
  自动关闭且低于阈值的明确手动压缩、只读归属分页、
  历史/档案/设置/owner 变更拒绝、未知/缺/错候选无 fallback、失败/取消/输入预算保旧档案、
  保留尾部改变触发全历史 CAS、无安全前缀/预取消零请求。
- SummaryProfileStore 仪器测试源码：新增 descriptor 非秘密真实请求地址/模型、元数据浏览零密钥读、
  每次 save 唯一 revision（含 key-only 与 ABA）、旧 descriptor 同锁拒绝且不读密钥、
  捕获后的 configuration 不随保存重定向、旧 v1 仅兼容原 load 而不能凭空授权手动收费。
- UI 仪器测试源码：精确会话与不可信原文展示/档案翻页派发、收费前明确二次确认与取消、
  确认区真实 endpoint/API model 而非 opaque UUID、
  未知窗口/结果未确认禁用 mutation 而仍可刷新。Actions 为渲染 fixture，不是生产实现。

尚缺父真实 admission/visit 注册、Main 入口接线、真实同槽发送竞争与 stop/close/换会话
端到端测试、可运行构建与设备验收，以及最终非空 Git 提交回执。不能把静态测试源码
或可渲染面板当成这些证据，也没有真实网络取消/真实 provider 行为的验收结论。
