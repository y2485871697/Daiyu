# P09 / R11 准备组件接缝（尚未改生产 Controller）

本增量仅新增 context 准备组件、定向测试源码及本说明。`ContextService`、
`ContextPolicy`、原切口/估算/归档实现、MainActivity、ChatController、数据库均未修改。
不能把组件存在认作生产自动压缩已接通。

## 已核对的来源与配置边界

只读参考清单是 `build/agent-input/SOURCE.json`：source_sha
`f001fb82eb9941e5673970c656f5c4d5370fbf7f`，其列出的保留
`AgentContextCompactor.kt` sha256 为
`6b2a45502a41f673ef4b0fc3dcc03aed0898a74082ad340f581a29b1f1b5df2b`。
这里只复核清单声明与源码，不声称执行了 hash 校验。

- 参考的配置默认保留4、范围0..100；非正/缺失窗口是未知窗口；只有开关打开且
  窗口明确为正才启用自动压缩。`CompressionSettings.fromLegacy` 保留这些语义。
  参考没有提供持久化键名，故不推测旧键、不自动迁移。外部读旧配置后显式投影。
- 新设置采用独立单条版本化记录；包含开关、窗口、reserve、阈值、保尾和明确的
  provider/model身份，不存凭据。默认关闭，无窗口、无摘要绑定。写入失败后共享
  preferences 的实例均拒读，直到用户明确成功保存；不谎称 SharedPreferences
  commit=false 意味着内存未改变。
- **不能静默移植的差异**：参考 `keepRecentMessages` 允许0且是消息设置；现有
  `CompressionPolicy.keepTurns` 是1..100的轮设置。参考触发按完整窗口80%，现有
  Policy 按 `(window-reserve)*triggerPercent`。`CompressionPolicyResolver` 因此必须
  外部注入，明确审核其映射；不能忠实映射时应拒绝，不能暗改0为1或把消息当轮。
  本组件没有默认策略适配器，也未宣称现有策略已完整恢复旧语义。
- 设置窗口值来自明确配置，不从模型名推测。现有 Policy 接受1024..10000000窗口且
  reserve必须小于窗口；旧的正数配置不因此被改写，不能映射的配置在策略解析时拒绝。
- 摘要绑定独立于主模型：缺绑定或解析返回null时只是不压缩，仍执行发送容量检查；
  不回退主模型。解析返回其他身份直接拒绝。禁用/未知窗口时不解析摘要模型。
- 摘要模型必须由外部注册表提供冻结的 transport、真实已知窗口、明确输入预算，
  并在 transport 内冻结输出/推理预算及超时。参考128000是规划上限，不是能力数据；
  本组件不内置128k、不猜摘要模型窗口、不复制旧分块/重试算法。单次摘要完整证据
  不适合给定预算则在打开transport前失败；外部分块策略不在本次范围。

## 父准备锁、取消、发送事务的接线要求

1. 父入口先获取现有 owner/preparing reservation（是跨I/O的逻辑占位，不是持有
   JVM monitor），覆盖普通文字、图片、授权发送及编辑/删除/换会话/压缩入口。
   在同一 reservation 下冻结用户消息、`CompressionSettings` 和摘要模型绑定。
2. 使用共享数据库 helper 的 ContextService 构造协调器；注入设置读取、已审核策略
   解析器和只读模型解析器。`freeze(owner, listOf(user), token)` 不开网络；
   `prepare(frozen)` 在工作线程运行，快照单次消费，失败不自动重试。
3. 父停止/关闭/owner失效必须调用 **同一个** `ContextPreparationCancellation.cancel()`，
   使活动摘要session收到cancel；同时把线程中断保留为取消。只改UI布尔值不能
   保证阻塞I/O退出。外部transport必须在open时不做阻塞I/O，并实现有效session.cancel。
4. `prepare` 调用现有 `ContextService.prepareSend`，pending只参与其预算，不入库；
   `PreparedSendContext.history` 仍不含pending，`messages` 则已经追加pending一次。
   runner使用 **result.messages，不再+user**；ScopedContextTools使用同一result.archiveId。
   `history.estimatedTokens`仍仅表示历史估算，不冒充provider usage。
5. 在父现有发送准入/SQLite事务内重新验证 reservation、owner、草稿CAS、取消以及
   原文 `ContextRevision.digest` 等于 `result.expectedHistoryRevision`，再调用原
   `database.send` 写同一冻结user/assistant。原文版本是准备前的完整权威历史版本，
   不是摘要历史版本。新会话还须验证未被意外创建/删除；空历史版本并不证明owner存在。
   所有写入口遵守reservation是必要条件；仅外层同步一次或仅比较历史版本不足以
   阻止读版本与发送提交间的竞态。发送成功后才启动runner，最后释放reservation。

示意（非当前Controller已接入代码）：

```kotlin
val frozen = coordinator.freeze(owner, listOf(frozenUser), preparationToken)
val result = coordinator.prepare(frozen) // worker, reservation remains held
// parent: atomically recheck owner/draft/reservation/cancellation/raw revision, then database.send
// runner.startSlice(frozenAssistant, result.messages)   // NOT result.messages + frozenUser
// ScopedContextTools uses result.archiveId
```

## 失败/取消保证的范围

摘要transport不接受工具权限，宿主必须通过 `SummaryModelTransport.openWithoutTools`
明确禁用全部工具广告（包括provider内置工具），不能绑定 `ModelPort.open(history)` 的
默认工具路径。摘要适配器只发一条包含不可信JSON证据的文本用户消息，不回放工具/
协议、不执行工具；ToolReady直接失败，空结果/不完整transport异常不回退、不重试。
现有ArchiveCodec提供完整原文证据；不修改原切口、估算、档案格式。

失败/取消发生在摘要/容量校验/现有发布事务提交前时，新档案不发布；旧活动档案和
原文由现有Service维护。**不扩大保证为“任何发送失败/取消都撤回档案”**：现有Service
压缩档案独立提交，提交后最终prepare取消检查仍可能抛取消；更晚父发送失败也不能
撤回已经提交的档案。要覆盖此窗口，必须另行审核Service发布与父发送事务的原子化
设计，不能靠协调器补偿删除档案或长事务包住模型I/O。本次未改该接口。

## 静态验证范围

新增定向仪器测试源码：设置5项、协调器9项、摘要适配器6项。覆盖默认/旧配置投影、
持久化失败门闩、冻结与防重复、未知窗口/关闭、缺/错绑定、容量与pending、保尾复用、
摘要失败/取消保留旧档案、摘要输入预算、纯文本证据、工具拒绝和活动session取消。
仅静态核对；未执行构建、测试、CI、设备或网络请求。测试中的策略映射明确是fixture，
不能作为旧消息/轮语义等价证明；没有父事务接线完成或真正网络取消的验收证据。
