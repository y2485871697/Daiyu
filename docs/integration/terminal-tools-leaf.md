# R09：中央终端工具叶子接线（待主代理集成/验证）

## 范围与来源

本增量仅新增：

- `app/src/main/kotlin/app/daiyu/android/integration/tools/RunTerminalTools.kt`
- 同目录 `CatalogTerminalBinding.kt`
- `app/src/test/kotlin/app/daiyu/android/integration/tools/RunTerminalToolsTest.kt`
- 本接线说明。

没有修改 `RunCatalogTools`、Controller、Main、TerminalEnvironmentHost/TerminalScreen 或 terminal 底层，没有新增安装/下载/后台守护路径。**不是 R09 整项完成，也没有自动启用生产工具。**

本次局部并发修复只修改 `CatalogTerminalBinding.kt`、`RunTerminalToolsTest.kt` 与本说明；不修改父 `RunCatalogTools`、Main、DB 或全局清单。

实现依据：`docs/重构清单.md` 的 R09 主项、Android PTY 原生制品/可信入口记录及 Linux PTY 代际租约记录；现有 `ToolRegistry.kt` 的 `PreparedTool.contextual(admit, execute)`、`ToolDispatcher.kt` 的整批预准入顺序、`ToolLedger.kt`；现有 `AndroidPtyHost`、`PtyRunSession`、`LinuxPtySession` / `LinuxPtyStartupFailure` / `PtyStartupFailure`。以这些源码为准，不把旧 INTEGRATION 中“尚无 contextual API/启动清理句柄”的历史记录当作当前限制。

## 主宿主的显式批准流程

1. 在可信宿主准备阶段冻结 `TerminalPtyLaunch`：
   - Android：明确的绝对 argv、环境和 cwd；不接受模型提供的路径、身份或命令，不选择默认 root，不做降级。
   - Linux：worker 上调用 `TerminalPtyLaunch.Linux.freeze(store, exactGeneration, temp, cwd)`。它取得现有 generation lease，冻结该配置生成的 argv/env，并在审批期间阻止通过 `select/clearSelection` 替换同一 store。不是启动 shell。
2. 构造有限 `TerminalPtyPlan`：可信 conversation/session/run、environment/generation、private journalDirectory、稳定 sessionScope、首个 Open 步骤、精确输入字节/控制参数及其 requestId/sequence、明确的输出页。上限为 64 个步骤、64 个读页、每输入 64 KiB、总输入 256 KiB、每读页 16 KiB、offset 至多 256 KiB；input/control 两种 sequence 域分别严格递增。argv/env 也有数量和长度上限。
3. 向用户展示**实际 argv/env/cwd/backend/generation 与原始输入或控制参数**，不能只展示 digest 或“允许终端”开关。仅在用户批准这些精确效果后，才将 `plan.effects.values` 加入这个任务真实 `TaskAuthorization.allowedWrites`。本叶子不创建授权、不确认用户操作、不扩大既有授权。
4. 调用 `CatalogTerminalBinding.approve(context, plan, originalAuthorization)`。缺任意一个精确 WRITE 即拒绝。同一个 plan 只能交给一个 binding。此调用不启动进程。
5. 创建 `RunTerminalTools(binding, frozenCapabilities, sharedDurableToolLedger)`。只有 `terminal.pty.approved_plan` 能力存在且 binding 已完整批准才提供叶子定义。能力开关不是 WRITE 授权。
6. 使用 Runner 发出的真实 `RunExecutionOwner` 调用 `bindOwner`，不能复制 owner 字段或重建 authorization。conversation/run/session 不符立即拒绝。

审批放弃或 plan 构造失败时，**尚未被 binding 接管的 Linux snapshot** 必须 `close()` 归还审批 pin；审批成功后不可再直接 close snapshot，只能关闭/排空 binding。已接管的 snapshot 会拒绝外部 close。Linux 启动仍通过 `AndroidPtyHost.openLinux` 和 `LinuxPtySession`，审批 pin 与实际会话/失败清理所持的 lease 都需要排空。

没有用户批准的计划，不应构造 binding 或广告替代工具。没有已选 Linux generation 时返回显式不可用，不安装环境、不自动选择、不切到 Android。

## 中央工具目录接线

本次不直接编辑统一宿主。主代理可选择：

- 在未来中央组合点，把 `registeredTools` 注册到**同一个** `ToolRegistry`，使用既有整批 `ToolDispatcher` 和同一个真实 SQLite `ToolLedger`。宿主需调用此叶子的 `bindOwner`，并将统一调度的最终 receipts 交给 `observeOutcomes`（包括 ledger 持久化失败造成的 UNKNOWN）。不要为每个 invocation 新建 binding/plan/session。
- 离线验证或明确只使用终端目录时，`RunTerminalTools` 自身实现 `CatalogTool` / `ReceiptBatchToolExecutor`，用现有 `CatalogModelBridge` 完成相同所有权与整批调度。它不是在统一目录外逐条分发原 batch 的许可。

模型仅能发送：

- `terminal_pty_step({"step_id": 已批准枚举值})`，WRITE。
- 若预定了输出页：`terminal_pty_read({"read_id": 已批准枚举值})`，READ_ONLY。

没有原始 shell/script/input/base64、argv、session、owner、requestId、sequence、generation、任意 offset 等模型可写字段。Open 也是 WRITE：打开 shell 本身可能有启动副作用。所有 prepare 和 contextual admission 均不做 I/O、不打开进程、不分配 sequence；在 reserve **及 replay 之前**核验 owner 实例、同一个授权实例、invocation 名称及完整规范化参数。宿主消息归属再由既有 bridge 验证。

## 结果与不可重发语义

- `Open` 的 Confirmed 表示本次新进程完成既有 PTY 启动握手，并不表示任何命令成功。重放是**历史回执**，不是活进程句柄或重建授权。即使使用新 model call ID，也不重开同一效果。
- 既有 PTY 写入/resize/interrupt 的 `helper_input_written` 只证明 helper 管道写入，仍标有 `pty_delivery=unknown` / `command_completion=unknown`。叶子保守返回 typed UNKNOWN，不将它改成命令成功。
- 一旦有 transport 或中央 ledger UNKNOWN，此 binding 禁止后续 WRITE、重发或重开；已有进程允许读取事先规划的有界输出页作为诊断。**这意味着第一条不确定输入后其余写步骤不会继续执行**。没有“继续执行未知计划”开关。未来若增加可确认的 PTY ACK/业务完成协议，必须单独设计，不能把输出提示符当证明。
- requestId/inputSequence/controlSequence 在 plan 中由可信宿主一次性分配，模型 call ID 从不映射为新的终端事件身份。底层 journal 与中央 ToolLedger 共同保留 UNKNOWN，不能删除 journal、换 scope 或新建 binding 绕过它。
- 读页是调度时的历史快照；同一个 read invocation 的 ledger replay 不保证刷新输出。即使输出中出现 exit code 或 X 帧，也不提升为命令成功/全部后代已停。

## 关闭和排空

`close()` 只 fence 新调用并向已有资源请求关闭，不等待。必须保留**整个 RunTerminalTools / binding**，直到 worker 上 `awaitClose(1..30000)` 返回 `TerminalToolsCloseResult.Drained`。

- `execute` / `read` 的 `context.cancelled()` 是调用方外部回调，可能阻塞或抛异常：只在 binding 锁外求值。返回后重新取得锁，重验 closed、owner / 原 authorization 引用，以及 busy、UNKNOWN fence 和步骤 claim 等对应执行条件；回调求值前不消费步骤，求值成功也不表示已获执行权。有效步骤仍只在锁内单次 claim，外部 transport I/O 同样在锁外。整批 admission-before-reserve/replay 与 UNKNOWN 不重发语义不变。
- binding 单独计数正在求值的取消回调（异常时也在 finally 归还计数）。阻塞回调不会持有 binding 锁妨碍 close，但在它返回前不能报告 Drained，即使尚未打开进程或已有资源已终止。回调返回 false 也不能绕过其间发生的 close；组合注册模式不能仅依赖独立桥接的 dispatch 计数来保证这一点。
- 在途启动计入 active；close 时尚未返回的 late-open 仍被接管并请求关闭，在返回前无法报告 Drained。独立桥接模式还跟踪整次中央 dispatch（包括 ledger receipt 持久化），其结束前也不报 Drained；组合注册模式由中央宿主保留既有整批 dispatch 排空屏障。
- 同时保留 Android `PtyStartupFailure.cleanup` 或 Linux `LinuxPtyStartupFailure.cleanup`；不能因为 open 抛异常而丢弃。
- close 抛错仍保留，后续 worker join 会重试 close。只有原 resource 的 `awaitTermination` 真返回 true 后才释放 Linux 审批 pin；false、异常、并发 join 或仍有活动操作均返回 Unknown。
- 不使用 GC、定时器、EOF、X 帧或关闭请求作为归还 lease 的依据；不主动轮询开线程。宿主必须保留 UNKNOWN 状态并安排有界的后续 join，不能提前释放运行资源/数据库。
- 进程从未分配且已无 active 操作时，closed binding 可立即 Drained。既有生产打开 API 对所有已分配进程以 typed startup cleanup 转移所有权；测试 seam 也必须遵守同一契约。

## 非目标与证据边界

这是显式的**持久 PTY**工具族。原 `TerminalRegistry` 每命令独立固定环境 shell 的语义、async/daemon、旧工具名完全不变；没有将旧 shell 路径悄悄路由到 PTY。没有 Linux 安装 UI、Root 授权 UI、终端屏幕、权限提升、下载网络或守护进程管理。

Linux 审批 pin 阻止受支持 store API 的配置替换，配置再次校验沿用现有底层身份检查；它不是对抗恶意同 UID 直接篡改文件的沙箱或 descriptor-exec 保证。Android argv/path 也是可信宿主快照，不新增底层不具备的文件不可变性保证。

测试源码覆盖精确授权/禁用广告、纯 prepare、context admission 在 ledger 前拒绝复制 owner/auth 与 invocation 错配、整批拒绝/取消、冻结 bytes/argv/env、host sequence、UNKNOWN 不重发/不重开、中央 commit 失败、late-open、启动失败清理、关闭/等待失败重试、Linux 审批 pin 及失败清理双 lease、重复 plan 拒绝和 control sequence 拒绝。新增 execute/read 取消回调的确定性 latch 回归：只阻塞 binding 的回调求值，另一个线程执行 close，并在释放回调前确认 close 已返回、awaitClose / binding join 仍不报告 Drained；释放后返回 false 仍不得 open/read。另覆盖取消不消费步骤、回调延迟期间另一个调用取得 claim 后不得重复 open、回调异常不泄漏排空计数。测试夹具由真实 dispatcher 创建执行 context，专用入口隔离 binding 检查，不依赖取消回调调用次数、sleep 或独立桥接的 dispatch 计数。测试只使用 fake transport/process；Linux fixture 只创建元数据，不执行命令。

本实现代理按约束没有执行 shell、构建、测试、网络或设备操作；以上测试**仅已编写，不宣称通过**。主代理需统一编译/JVM 测试，并将 API26/高版本、实际 native PTY/SELinux/四 ABI 与 UI/中央生产组合纳入后续验证。不改变清单验收状态，不替主代理推送任何分支。
