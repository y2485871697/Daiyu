# P09：Controller 实际发送准备链

## 接线边界

`ChatController` 的普通文字、带 children 文字、图片、明确批准的 Shizuku 和新会话发送，都进入同一个 `sendPrepared`。它使用已有 preparation reservation、executor、SQLite helper，不增加第二队列或数据库。

生产入口由配置模块提供：

```kotlin
internal object ProductionContextPreparation {
    fun create(context: Context, service: ContextService): ContextPreparationCoordinator
}
```

Controller 以 `lazy` 创建服务/协调器。构造器末尾的默认兼容测试注入点为：

```kotlin
contextPreparationFactory: (Context, ContextService) -> ContextPreparationCoordinator =
    ProductionContextPreparation::create
```

不在 Controller 推测容量、摘要模型、消息数到轮次数转换或生产配置。工厂和配置快照的具体规则属于配置模块。`freeze` 和 `prepare` 在 worker 执行，不更改摘要 deadline 算法。

## 单次发送与提交

1. 短锁内获取唯一 reservation，冻结当前全局 assistant 选择，并携带单向 `ContextPreparationCancellation`。
2. 冻结草稿、用户消息、assistant/model 配置。协调器 `freeze` 后 `prepare`，新会话也执行预算检查。
3. `PreparedSendContext.messages` 已含 pending **一次**；Runner 直接使用它，不再追加 user。
4. 上下文工具使用 result 的 archive ID。图片预检覆盖完整权威原文与 result 中的图片，不因摘要隐藏旧图片而跳过其所有权/能力/资源验证。
5. 短锁 `beginCommit` 只核对 token 身份、取消/关闭状态，并进入已有图片 acceptance gate；所有数据库和模型 I/O 在锁外。
6. 同一 `DatabaseTransactions` 外层 SQLite 事务内，核对旧 owner 仍存在 / 新 ID 尚未存在、冻结草稿完全一致，以及 `ContextService.matchesHistoryRevision` 对全部权威原文的摘要。随后调用原 `database.send`，不创建另一持久化路径。
7. 收到数据库 receipt 后才可安装 Runner。close/stop 已经撤销的 preparation 仍可返回已提交 receipt，但不启动 Runner；排队但尚未启动的 slice 也检查关闭/撤销。

摘要 archive 在 ContextService 自己的事务中发布，**不宣称 archive 与发送是同一个事务**。若随后原文/草稿 CAS 失败，Controller 保留草稿，不打开主模型；有效 archive 可以保留。

## 取消和生命周期

stop/close 先在短锁内撤销 reservation，再于锁外调用取消，包括真实摘要 session 的取消，不只是轮询标记。图片 handle 的兼容默认回调也把取消转发给同一 context cancellation；回调不持有 handle monitor。

取消本身不释放 reservation。准备 worker 结束后才按身份释放；Shizuku 仍遵守原 cleanup/UNKNOWN 保留规则。迟到的旧 completion 不能清掉新 token。原图片 journal、receipt 和 Shizuku approval 机制保持不变。

## 父 Main 接入 API

```kotlin
internal fun sendText(
    conversation: Conversation?,
    editorGeneration: Long,
    draftRevision: Long,
    text: String,
    complete: (ChatController.TextSendResult) -> Unit
): Boolean

internal data class TextSendOwner(
    val conversationId: ConversationId?,
    val editorGeneration: Long,
    val draftRevision: Long
)
```

`ChatController.TextSendResult`：

- `Accepted(owner: TextSendOwner, conversation: Conversation)`：数据库已确认。
- `Rejected(owner: TextSendOwner)`：本次未确认进入数据库发送，不清草稿。
- `Unknown(owner: TextSendOwner)`：数据库发送已尝试但没有确认 receipt，保留草稿，禁止自动重发。

Boolean 只表示已排队；false 时没有 task/callback。callback 在 main 执行，可能发生在 Controller close 后，因此父层必须自行校验 Activity/editor 生命周期。

父 Main 必须在调用前持久化 **完全相同** 的 `text`，捕获原 conversation ID、editor generation、draft revision 和文本，直到对应 completion 保持冻结/防重复。仅在 `Accepted` 且全部 owner 字段及文本仍匹配时，切换到 receipt 的 conversation、清当前编辑器并递增 revision/generation。新会话的 callback owner ID 仍为 null，创建后的 ID 见 `Accepted.conversation`。Rejected/Unknown 不清草稿；过期 owner 回调不得修改当前编辑器。切换 owner 或销毁时撤销准备并让 worker 自行退出，不把 queue admission 当 receipt。

原 `send(conversation, text): Conversation?` 和 `sendWithChildren(conversation, text): Conversation?` 签名保留，兼容 **worker** 调用。main 调用立即返回 null 并给出迁移提示，不读配置、不创建模型、不等待摘要；生产 UI 必须使用异步 `sendText`。同步 worker 入口以准备开始时实际持久化草稿做 CAS，因此仍兼容发送文本与现有持久化草稿不同的旧调用。本候选将相应旧 fixture 中的同步发送移到 instrumentation worker，不能再用 main 包围同步发送来证明 UI 可同步发起网络准备。

## 测试与验证状态

新增/扩充源码测试：

- `ControllerContextPreparationTest`：真实 Controller + SQLite + Runner，验证摘要 result/pending 一次、新会话预算、超过 UI 一页的完整原文 CAS、草稿变更、owner 删除、真实摘要取消并在 worker 未退出时仍拒绝第二次发送、commit gate 后 close 仍回 receipt 但不开模型、旧同步 main 调用快速拒绝而 worker 签名保持兼容。
- `ContextPreparationCoordinatorTest`：最终 revision helper 对 archived/分页外原文的检查，以及必须处于同一 helper 的事务。
- `ImageSendHandleTest`：取消回调不持有 handle monitor，原 acceptance/cancel 竞争规则继续保留。

旧同步 fixture 的发送动作移到 instrumentation worker（UI/control 断言及观察仍留 main）：
`ControllerAssistantBindingTest`、`ControllerModelPreparationTest`、`ControllerLifecycleTest`、
`ControllerChildModelsTest`、`ControllerChildRetentionTest`、`ControllerDeviceResourcesTest`、
`ControllerImageSendingTest`、`ControllerBackupTest`、`ControllerModelSettingsTest`、
`ControllerRunControlTest`、`RunControlActivityTest`、`ControllerHapticsStopTest`、
`ControllerUsageAttributionTest`、`PlatformTest`、`ActivityTest`、`QuestionBarrierIntegrationTest`。
生命周期并发测试同时明确第二次发送在第一段 I/O 尚阻塞时立即拒绝，不能以持锁等待来通过测试。

本任务仅做源码静态核查，未执行构建、instrumentation、CI；生产工厂文件由并行配置候选集成后，主代理应执行完整候选验证。未修改 MainActivity、ConversationDatabase、ModelSettingsStore 或生产配置文件。
