# R08：显式关闭从未初始化的 owner 进程

## 边界与依据

本变更只补 lookup-only bootstrap 的退出缺口，不接线业务宿主、不宣称 R08 完成。
依据 `docs/重构清单.md` 的「R08：认证 bootstrap v2 与不确定结果后的受控清理」及
「R08：v2客户端、固定root启动与精确收据校验」记录：READY 只代表认证传输可用，
lookup 不创建 backend；原 `closeAfterStop()` 仅接受已经释放的 backend，因而 lookup-only
进程不能借查询完成退出。不得用“先 initialize 再 release”伪造清理。

沿用当前 `OwnerIpcServer`、`OwnerReceiptCoordinator`、`OwnerReceiptStore`、
`OwnerReleaseCompletion`、`AndroidVirtualDisplayOwnerHost` 和 `VirtualDisplayOwnerClient`。
没有第二套 owner、receipt、launcher 或 lease 栈；没有改动共享清单、Controller、Activity、
工具目录、授权接线或 minSdk（仍为 26）。保留来源及版权声明。

## 线协议（v2，host-only）

经过原有 kernel peer UID == allowUid **且**本次 READY 私有 token 匹配之后，发送单行 JSON：

```json
{"v":2,"op":"shutdown_uninitialized","token":"<private READY token>"}
```

只允许这三个字段。不能带 `generation`、`binding`、`operationId`、`parameters`、超时或路径。
该操作沿用 v2，runtime/client 应由同一安装版本提供；旧 runtime 的 UNKNOWN_OP 不能触发
initialize/release 兜底。该操作针对这个已认证的进程身份，不是某个旧 generation 的操作，不产生操作回执，不能通过
`execute()`、`lookup()` 或内部 business parser 调用。`SUPPORTED_OPS` 增加此 host 控制操作，
`isBusinessOperation()` 仍只接受原六个业务操作。不要把 token、READY 原文、此 envelope
或协议响应交给模型、工具参数、普通日志或异常 cause。

成功响应的字段及类型严格限定为：

```json
{"v":2,"op":"shutdown_uninitialized","ok":true,"initialized":false,"receiptsClosed":true}
```

没有 `released`、`receipt`、`receiptOnly`、`found`、`executed` 或 generation 摘要。
`initialized=false` 指本次进程从未跨过初始化准入，**不表示磁盘不存在历史回执**。
Kotlin 验证器拒绝缺字段、错误类型、额外字段、伪 release 或 receipt-only 成功。

拒绝/不确定响应沿用严格的 `v/op/ok=false/error/message` 形式：

- `SHUTDOWN_UNINITIALIZED_DENIED`：已跨初始化准入、backend/claim 已存在、存在执行不确定性，
  或已尝试此关闭。不尝试关闭 store，也不授权重试。
- `SHUTDOWN_UNINITIALIZED_UNKNOWN`：关闭过程抛异常或结果不确定。仍封闭初始化，
  `shouldStop=false`，不打印 STOPPED、不退出、不重试 store.close。
- UID、token、schema、transport fence 的原错误继续适用。丢响应没有 NotExecuted 含义。

## owner 顺序与不变量

1. UID/token 检查和单活连接门禁不变；`shutdown_uninitialized` 与 initialize 一样投递到
   **同一个 owner looper**。没有 socket 线程直接关闭 store 的旁路。
2. 只有 `initializationAttempted=false`、`executionBlocked=false`、`backend==null`、
   `generationClaim==null`、未尝试 shutdown 且尚未 stop 才准入。
   initialize 在 reserve 之前已设置初始化准入标志。因此 reserve/start 写失败、创建异常、
   已初始化后失败、已初始化但尚未释放等情况，均不能靠本操作退出。
3. shutdown 在 close **之前**封闭初始化及自身的一次性准入。构造响应不触发显示；调用原
   `store.close()` 关闭生命周期 channel/文件锁。只在确认 close 返回后置 terminal/stop。
   close 失败也不重新开放任何准入。
4. 不 reserve、start、complete、重写或删除任何旧回执。lookup 老 RESERVED/STARTED/UNKNOWN/
   SUCCEEDED、或 initialize 仅发现旧记录而未取得新 claim，不代表初始化当前 backend。
   owner 可允许此时关闭；客户端更保守，任何 initialize 发送尝试后都不再提供该关闭发送。
5. 原 `OwnerReleaseCompletion` 仍等待 dispatch terminal proof **及**响应尝试结束，二者顺序
   不限。因此响应丢失/写失败/等待者超时后，只有已经确认 store 关闭的这条路径才能退出。
   超时发生在执行之前不产生 terminal proof；失联本身不关闭、不 release、不 kill。
6. main 的 terminal 回调仍验证 `shouldStop` 并调用 `closeAfterStop()`。这条未初始化路径
   的 store 已关闭；已初始化路径仍要求 `backend.shouldStop()` 且执行原 store.close。
   原 release 的操作/代双 final、关闭顺序、错误保持存活等行为不变。

未初始化成功的**私有 stdout**终止行必须逐字为：

```text
VD_OWNER_STOPPED initialized=false receiptsClosed=true
```

随后才 `System.exit(0)`。已初始化成功 release 仍逐字为：

```text
VD_OWNER_STOPPED released=true receiptsFinal=true
```

二者互不冒充。任一关闭失败都不能制造成功终止行。没有发送终止信号或 destroy/kill 的兜底。
原 PID 存在性探测 `Os.kill(pid, 0)` 仍只观察、不终止。

## 宿主显式调用

仅在可信 worker、父 session/journal 保存 generation、六维 binding、host 身份的前提下：

```kotlin
val host = AndroidVirtualDisplayOwnerHost(context)
val started = host.start(journalGeneration, trustedBinding) // 不 initialize
if (started == AndroidVirtualDisplayOwnerHost.Start.READY) {
    val client = requireNotNull(host.client)
    val lookup = client.lookupGeneration() // 或按原 operationId 查询；只读
    // 父宿主明确决定本次 bootstrap 仅查询，且不再初始化此进程：
    val shutdown = client.shutdownUninitialized() // 唯一显式控制入口；至多一次
    // 无论 shutdown 是 REPLIED / UNKNOWN，均不能把它当作 DRAINED。
    val observation = host.observe()
    // 保留 host/journal；只在 lifecycle == DRAINED 时释放进程占用的父资源。
}
```

`OwnerReply.Outcome.REPLIED` 只证明 schema 验证通过，不代表 ok=true，更不代表进程已退出。
若本次 `start` 结果 UNKNOWN，不得通过重建 host/身份或新启动来伪造重试；先保留原 host
观察，若父显式选择 `reconnect()`，其目标仍为保存的同一个 READY socket/PID/token。

- 构造、lookup、disconnect、重连和父 close/清理不得隐式调用 shutdown。host 没有新增
  自动 close API；`disconnectTransport()` 只关闭 socket，生命周期仍可 UNKNOWN。
- 客户端在发送前消费 shutdown 一次性许可；即使失联、返回拒绝、解析失败或发送前已断开，
  也不能再次调用它。initialize 与 shutdown 互斥；shutdown 后不再发送业务执行。
- `host.reconnect()` 的新连接 client 保留同一 READY 身份、曾发送 initialize 的事实和
  shutdown 的已消耗许可；旧 client 仍断开。它本身不发协议命令，可由父明确继续只读 receipt
  观察。原 initialize/业务请求的连接内防重复规则及服务端 receipt/cleanup 一次性规则不改；
  正常已初始化会话仍可按原规则受控 handoff/release，同 ID 的检查应使用 lookup。
- 丢响应仅观察原进程；不再次发送 shutdown，不换 generation，不先初始化后释放。
  若 store 已关闭而响应丢失，receipt 重连/读取可能失败，这也不是重新执行的理由。

### DRAINED 的三个必要证据

host 只在下列三项同时成立时返回 DRAINED：

1. 本次私有 stdout 收到上述两个精确终止行之一（仅换行由 reader 去除）；
2. 本次 launcher 进程 `waitFor()` 确认 exit code **0**；
3. READY 中保存的**原 owner PID** 已由存在性检查确认为 ESRCH。

单有关闭响应、socket EOF、su 退出、PID 消失、marker，或 `initialized=false` 任意子集均为
UNKNOWN。close 失败不得人为 kill 后补齐证据。未观察到 READY PID 的启动尝试也不可能
靠此条件伪造 DRAINED。

## 测试源与未验证项

本次按任务约束**没有执行构建、测试、设备命令或 root owner**。以下仅为已添加的可运行测试源，
不是通过报告：

| 路径 | 覆盖范围 |
| --- | --- |
| `app/src/test/java/app/daiyu/android/vd/runtime/OwnerUninitializedShutdownTest.java` | API26/36 Robolectric：严格 envelope/UID+token helper；lookup-only 零创建零写；旧代/操作回执字节不变；关闭后初始化禁止；初始化失败无 backend 也拒绝；已初始化 release finals/marker 不变；close fault 不退出/不重试/保持不确定登记。 |
| `app/src/test/java/app/daiyu/android/vd/runtime/OwnerIpcServerFenceTest.java` | 原 fence 回归扩展：丢 shutdown/initialize 响应都不能放行 shutdown 重试；原受控 cleanup 允许集不变。 |
| `app/src/test/kotlin/app/daiyu/android/vd/OwnerUninitializedShutdownClientTest.kt` | API26/36 脚本传输：只显式发送、精确 schema、防伪 release、丢响应后的原身份只读重连、一次性许可不重置、初始化失联后拒绝、两种 marker 及三项排空证据组合。 |
| `app/src/androidTest/java/app/daiyu/android/vd/runtime/OwnerUninitializedShutdownSocketTest.java` | 无需 root 的真设备 LocalSocket/kernel UID、token/schema、实际 owner HandlerThread 串行、真实 store、响应不读取仍完成 terminal、初始化准入先到则拒绝；不创建显示，不执行 main 的 System.exit。 |
| `app/src/androidTest/kotlin/app/daiyu/android/vd/OwnerUninitializedShutdownLauncherTest.kt` | 显式 opt-in 的 root 设备测试：真实固定 launcher/READY/client/lookup/shutdown/marker/exit0/原 PID 消失，不创建显示或启动任务。缺少 opt-in 时直接返回，不代表 root 场景通过。 |

父 CI 应运行原 `OwnerReceiptCoordinatorTest`、`OwnerReleaseCompletionRegressionTest`、
`VirtualDisplayOwnerClientTest` 及本批测试，确认已初始化 release 未回归；本地静态审阅不能替代它们。
root 场景只在隔离、可丢弃、已授权 root 的设备上手动选择，例如安装相应 APK 后：

```text
adb shell am instrument -w -e class app.daiyu.android.vd.OwnerUninitializedShutdownLauncherTest -e r08RootShutdown true app.daiyu.android.test/android.test.InstrumentationTestRunner
```

命令仅为调用说明，未执行。测试失败也不会 initialize/release/kill/retry 来使结果变绿；操作者必须
保留原进程证据并处理遗留 UNKNOWN，不能当作已排空。真实 ROM/root/SELinux、launcher 私有管道、
PID 存在性与文件锁行为仍待设备验证；故障注入的 close 失败由 JVM 测试源覆盖，未模拟成真实 root
进程成功退出。中央授权/lease/真实 GUI 生命周期接线仍在本次范围之外。
