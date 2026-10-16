# R08：RunCatalogTools 唯一中央目录接缝

## 范围与依赖

本变更基于中央目录基线 `6e3df47`，引用 R08 叶子 `ff120cf` 的 batch-ticket API，以及其已有的 `VirtualDisplayApproval`。父集成须先合入叶子。这里不复制或修改叶子、plan、approval host、journal，也不修改 ChatController、MainActivity 或 core-domain。

本变更只提供可选目录接缝，不启用默认 GUI 能力，不安装审批 UI，不构建生产 journal，也不声称 root/VirtualDisplay 真机成功。

实现参考来自父代理提供的只读 `build/agent-input/`：

- `R08VirtualDisplayCatalogLeaf.kt`：`bind`、`registeredTools`、`beginBatch`/`finishBatch`、撤销与观察语义。
- `R08VirtualDisplayPlan.kt`：明确分离 start、initialize、GUI write、read、release 的固定授权计划。
- `VirtualDisplayApprovalHost.kt`：显式审批与唯一 owner/capabilities/authorization 对象约束。
- `R08VirtualDisplayCatalogLeafTest.kt`：已知有效的 scripted v2 endpoint 和真实 client/validator 测试形状。

这些输入不是产物，也不应加入提交。

## 单一 owner、authorization、registry、dispatcher、ledger

`RunCatalogTools` 新增可选参数：

```kotlin
virtualDisplayFactory:
    ((RunExecutionOwner, SessionCapabilities, TaskAuthorization) -> R08VirtualDisplayCatalogLeaf)?
```

- factory 仅在 `bindOwner` 收到真正 Runner owner 后调用，不在构造、查看 definitions 或默认路径中调用。
- 目录使用已有唯一 `TaskAuthorization`，校验 session/run，并将同一对象交给各 factory、capture 和中央 bridge。提供 `authorizationFactory` 时只调用它，没有 fallback；R08 factory 无明确 authorization 来源时拒绝构造。
- factory 与叶子 `bind` 均在目录 lifecycle/setup 锁外执行。`activeCreations` 覆盖整个 owner 绑定阶段。
- 返回后 bind 失败、close 抢先发生或资源迟到，均先保留返回的叶子，再撤销、观察它；不能丢失物理 owner 的持有关系。
- `initialized` 只把叶子的 `registeredTools` 注册进已有 registry。仍由唯一 `CatalogModelBridge` 创建唯一中央 `ToolDispatcher`，使用原来的 ledger；不使用第二叶子 dispatcher。

## 整批 ticket 与最终反馈

只要整批包含 R08 `WRITE_TOOL` 或 `READ_TOOL`，中央在进入 dispatcher/ledger 前调用 `beginBatch`，传递原整批的不可变快照，而不是过滤后的 R08 子集。非 R08 调用、重复 callId、调用顺序都保留。

`execute` 和 `start.Completed` 将实际完整中央结果传给 `finishBatch`，包括：

- 参数、能力、授权、重复 callId、未知工具、过大批次等整批拒绝；
- 取消导致的 NotExecuted；
- 中央 ledger replay、冲突和 receipt persistence Unknown；
- 正常确认结果。

中央 dispatch 或结果观察抛出异常时，不构造成功或虚构 NotExecuted 结果去释放 ticket。异常原样传播，R08 ticket 保留，目录关闭也保持 Unknown。叶子最终 journal I/O 完成之前 `activeDispatches` 不减，不能提前观察为 drain。叶子自身最终 journal 失败时按其契约保留 pending/Unknown，即使中央业务 receipt 已确认，也不能据此声称整个 owner 已回收。

已关闭目录拒绝新 dispatch，返回 `run_closed`，不再向已经撤销的叶子申请新 ticket。

### USER_BARRIER / Suspended

依据现有 `core-domain/.../tools/ToolDispatcher.kt`：任意 USER_BARRIER 与另一调用混批，会在准备、执行、ledger reservation 之前整批返回 `NotExecuted("ask_user_batch_barrier")`。唯一合法挂起路径是单独的 `ask_user`。

因此：

- `ask_user + R08` 仍创建 **原整批** ticket，中央整批拒绝后正常以全部最终结果 finish。
- 纯 `ask_user` 不涉及 R08，不创建 R08 ticket；原 `BarrierOffer` 不包装、不替换，其等待、恢复和取消仍归原 Runner 管理，不会阻塞未启动的 R08 关闭。
- 若未来中央契约改变而带 R08 ticket 的批次意外返回 `Suspended`，目录抛出 `virtual_display_unexpected_suspension`，保留 ticket 并保持 Unknown，不伪造 finish。扩展支持之前应同步修改本接缝与测试，而不是提前 drain。

不新增 core BarrierOffer 回调，也不轮询、反射或模拟恢复结果。

## 关闭与锁

目录 `close` 对 R08 只调用其撤销型 `close()`，不自动调用 release、shutdown、handoff、断开连接、重连或 kill。真正 release 必须是另外一个明确批准且经过中央 dispatch 的操作。

`advanceClose` 在包含 R08 时等待中央 `activeDispatches`、全部 `activeCreations` 和 stop 请求结束，再注册七类资源观察。R08 `observeClose` 与最终 journal 操作均在目录锁外；`R08VirtualDisplayCloseResult.Unknown.code` 保留到 `RunCatalogCloseResult.Unknown.reason`。

目录保留叶子引用及一次性关闭结果。Unknown 不是 Drained，不会因后来的物理状态变化而自动重试或重置。

## 专属测试与验证边界

新增 `app/src/test/kotlin/app/daiyu/android/integration/tools/RunCatalogVirtualDisplayTest.kt`，使用真实 Runner-issued owner、真实审批对象和唯一授权；endpoint 通过真实 `VirtualDisplayOwnerClient` 与 v2 validator 消费 scripted transport，不直接伪造成功 `OwnerReply`。

测试覆盖：

- 唯一目录/授权/owner、广告无 I/O、显式初始化、中央 replay 与共享 ledger；
- 无 factory 默认关闭、缺失/错误/替代/抛错授权、错误 owner/消息、返回错误绑定叶子的撤销；
- 两条 dispatch 路径的整批拒绝、取消、重复 callId、过大批次、能力关闭、未知工具与参数错误；
- mixed ask_user+R08 整批拒绝并正确 finish、纯 ask_user 真正 Suspended 且不占 R08 ticket；
- 中央 persistence Unknown、异常不伪造最终反馈、最终 journal 失败留 pending；
- 关闭等待中央最终 journal I/O 与实际 owner I/O、factory 迟到仍保留撤销；
- 关闭后不再创建 ticket、显式 release 不等同于物理 drain、Unknown 一次性缓存。

**实现代理未运行任何构建或测试。** 测试源码与静态检查不是通过证据。父代理应在先合入 `ff120cf` 叶子后执行构建/测试并记录实际结果。没有真机、root、进程、socket 或生产 journal 验证。
