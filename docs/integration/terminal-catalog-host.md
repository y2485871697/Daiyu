# Terminal PTY 的中央目录宿主接线（R09）

## 本次接入边界

`RunCatalogTools` 现在接受可选的 `terminal: CatalogTerminalBinding?`，通过
`TerminalCatalogLeaf` 把已审 `RunTerminalTools.registeredTools` 注册到**原有中央目录**。
模型广告、批次校验、reservation、执行和 receipt 持久化仍走同一个
`CatalogModelBridge` / `ToolLedger`；不会在 leaf 内再调用 standalone dispatcher。

这不是默认 shell 权限，也不是新的审批入口。本次没有修改 MainActivity、
ChatController、数据库或其他 leaf。未提供 binding 的现有调用方行为不变：即使
`SessionCapabilities` 包含 `terminal.pty.approved_plan`，也不会凭空产生 terminal 工具。
提供 binding 但未启用该 capability，同样不注册 terminal 工具。

## 宿主必须先提供什么

1. 保留本次真实 conversation/session/run 身份，冻结有限的 `TerminalPtyPlan`。
   启动 argv、环境、cwd、Linux generation、journal/scope、输入字节、请求 ID、两类
   sequence、窗口变化和允许的输出页都是宿主值，不能来自模型临时参数。
2. 向用户展示该计划的精确影响。只有用户明确批准后，才把计划的完整 exact effects
   放进该任务的 `TaskAuthorization`，并用**这个对象**调用
   `CatalogTerminalBinding.approve(context, plan, authorization)`。
   capability 不等于 WRITE 授权；不允许补造 plan、扩大权限、复制授权对象或自动批准。
3. 把 binding 和**同一个 authorization 引用**交给 `RunCatalogTools`。若该任务还有
   其他经批准的写操作，必须事先由宿主完成同一授权对象中的精确授权组合，不能在
   binding 创建后替换对象。
4. 让真正的 `ChatRunner` 经 `receiptBatchExecutor` 绑定 `RunExecutionOwner`。
   不从 run 字符串伪造 owner；不把子任务或另一 Runner 的 token 当作同一 owner。

下面是接线形状，而不是自动创建计划/权限的代码：

```kotlin
// approvedBinding 和 approvedAuthorization 已由宿主按上述步骤取得。
val tools = RunCatalogTools(
    registry = registry,
    capabilities = capabilities,
    ledger = centralLedger,
    conversationId = conversationId,
    runId = runId,
    authorization = approvedAuthorization,
    terminal = approvedBinding,
    capture = captureSkills,
)
// 原有 Runner 路径：tools 同时作为 CatalogTool 和 receiptBatchExecutor。
// owner 由 Runner 在持久化 admission 后绑定，不由这里手工制造。
```

构造时检查 authorization 引用及 plan 的 conversation/session/run。绑定时再检查真实
owner；与已绑定 owner 的身份替换也会拒绝。构造、绑定、skills capture、广告、schema
校验和 prepare 都不会打开 PTY。只有通过中央批次审批/授权/reservation 的实际
`terminal_pty_step` 的 `open` 步骤才会调用原有 PTY host。

## 中央结果与 UNKNOWN

父 `execute` 和返回 `Completed` 的 `start` 将**整批中央最终 receipts**交给 leaf 的
`observeOutcomes`，不是只看 terminal 调用或底层 transport reply。因此以下情况都会
使该 binding 的后续写步骤进入 sticky UNKNOWN/no-resend：

- PTY 送达/命令完成不可确认；
- reservation 返回 `unknown_effect_protected`，包括同批其他工具触发的 UNKNOWN；
- adapter 已回复 Confirmed，但中央 receipt 持久化失败。

新 call ID、重放入口或另一种 execute/start 路径都不能绕过该状态。输出读取仍只是
预先批准的有界历史快照，不证明命令成功，也不解除 UNKNOWN。中央 dispatcher 本身
继续在同批 UNKNOWN 后停止后续执行。用户交互 barrier 不能与 terminal 同批执行；
其挂起/恢复仍由原有 Runner 协议管理，leaf 不另建恢复通道。

## 关闭、线程和资源保留

- `close()` 首先封住父目录 admission，并优先调用 terminal leaf 的非等待 close；
  不等待 setup lock、capture、dispatch、receipt 持久化或 PTY 退出。
- 宿主应使用原有 `closeWithResult` 回调协议，而不是把 `close()` 返回当作清理成功。
  `closeWhenIdle` 是仅在 Drained 时调用的旧兼容入口，不能表达 UNKNOWN。
- 有 terminal 时，父 `activeDispatches`（包括最终 ledger 持久化及结果观察）、
  `activeCreations` 和 stop 操作必须全部结束，才开始 close observation。不能借用
  standalone adapter 的空 dispatch 计数冒充父目录已经空闲。
- leaf 复用已有后台 cleanup worker 的方式，启动**一次** daemon 工作项调用已审
  `awaitClose(30_000)`。没有 polling、sleep、周期性线程或主线程 join。它只作一次
  有界真实 PTY 终止等待；回调线程不保证是 UI 线程。
- 父 `Drained` 同时要求父 dispatch/creation/stop 屏障满足、其他资源 close observation
  成功及 PTY 的真实终止被确认。晚到的 open、失败启动的 cleanup 和 Linux lease
  仍由原 binding 持有，未确认终止时不能释放或宣称 Drained。
- 超时/失败给出 `Unknown`，按父既有协议缓存；重复观察不自动重试，也不回调旧
  Drained-only 入口。保留 tools/binding 引用及诊断，不将 UNKNOWN 当作可以重建
  shell、重发命令或清空运行记录的许可。底层明确的人工/宿主后续 cleanup 策略
  不在本次父目录接线范围内。

## 可审计测试与验证状态

`RunCatalogToolsTerminalTest` 使用真实 Runner-issued owner、中央 ledger 和假 transport，
没有 shell、真实 Process、网络或 Android host 启动：

- 缺省无 terminal、仅 capability 不够、禁用 capability 不广告；
- 同一 authorization/owner/ledger 和 lazy open；拒绝复制授权、错 owner/plan；
- execute/start 的持久化 UNKNOWN、非 terminal 批成员的 reservation UNKNOWN；
- UNKNOWN 后不能继续写；读不被误解成写成功；
- 父 receipt 持久化阻塞时，即使 PTY 已退出，也不能开始 join 或报告 Drained；
- 非等待 close、真实退出后才 Drained、UNKNOWN 缓存、不自动重试；
- capture 和晚到 open 的父创建/dispatch 屏障。

本次仅进行了源码静态复核，按任务约束没有运行构建、测试、网络、进程或 CI。
