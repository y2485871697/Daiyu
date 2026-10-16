# R06：精确批准的宿主接线（第二批）

本目录保留原 ShizukuAuthorization / BinderShizukuBackend / UserService / journal，未另建执行后端。新增 `ShizukuRunSession` 是一个宿主预留运行的批准与收尾对象；`ui/screens/shizuku/ShizukuScreen.kt` 是它的专属页面。Controller、MainActivity、RunCatalogTools 未在此任务修改，**页面路由和下面公共接线尚须总集成实现**。不要据叶子页面存在声称端到端已完成。

## 可直接使用的签名（同 app 模块 internal）

```kotlin
ShizukuRunSession(sessionId: String, runId: String)
val state: ShizukuRunState
val states: StateFlow<ShizukuRunState>
val allowedWrites: Set<WriteEffect>
fun edit(uid: Int?, script: String, timeoutMillis: Long)
fun approve(displayed: ShizukuRunState)
fun open(authorization: ShizukuAuthorization): RunShizukuCommands
fun revoke()
fun markOutcomeUnknown(reason: String)
fun close()
fun closeWithResult(onClosed: (ShizukuCloseResult) -> Unit)

@Composable
ShizukuScreen(
    authorization: ShizukuAuthorization,
    session: ShizukuRunSession?,
    onBack: () -> Unit,
    onReady: (ShizukuRunSession, RunShizukuCommands) -> Unit
)
```

- sessionId/runId 必须是宿主为这次运行预留的真实标识，不从模型参数、脚本或 UI 文本读取；开始实际 Runner 时不得另生成 runId。`session = null` 只提供服务状态与显式系统授权入口，不提供全局命令批准。
- 实例由 Controller / run owner 持有，不能随 Activity 重建重新创建或存入全局默认授权。进程恢复不还原批准、更不能重发未知命令。废弃预留 run / 切换会话必须 close；保留运行仅换页面则继续持有同一实例。
- 页面首入/恢复/刷新只在工作线程 refresh；系统授权只能点击按钮触发。批准与连接是两个独立点击。编辑任意字段清空批准，未选择 UID 无默认值。批准直接绑定完整 script、timeout、uid、sessionId、runId；UI 会展示运行身份和原文，不接受模型指定 UID。
- `approve(displayed)` 核对完整显示快照，防止旧 UI 为已经变化的命令批准。`edit/approve/state/bind/tool.prepare` 不连接、不创建 journal、不发送外部命令。
- 用户明确点击连接才调用 `open`（页面已放 IO 线程），传递显式 UID 给原 `ShizukuAuthorization.connect(uid)`。连接过程中撤销则迟到 backend 被关闭、不交给宿主；连接失败/Unknown 不可用同一 session 重试。连接不会执行探针或脚本。
- 页面 `onReady` 在主线程回传原实例与已打开适配器，**不自动执行命令**。父宿主必须核验回调仍属于其待启动运行，否则 session.close；不能把它挪到当前新运行。页面自身也拒绝跨 session 的迟到回调。

## 公共文件需要总集成实施的接线

1. 在现有发送/准备运行机制中预留稳定 runId，持有 `ShizukuRunSession(capabilities.sessionId, runId.value)`，新增专属路由显示上述页面。普通发送不得默认授予或注册该能力。
2. `onReady` 成功后读取 `session.allowedWrites`；构造**一个** `TaskAuthorization(capabilities.sessionId, runId.value, existingExplicitWrites + session.allowedWrites)`，让 RunCatalogTools、CatalogModelBridge、设备/子工具、Shizuku 共用该对象。当前 RunCatalogTools 内部默认创建空 allowedWrites，需要总集成改为显式注入；不能在不同位置各自重建授权。
3. 只有用户选了这次 Shizuku 才把 `RunShizukuCommands.CAPABILITY` 加入这次冻结 `SessionCapabilities`；把 `commands.tool()` 注册到原 registry。原 `Runner.bindOwner` 路径中调用 `commands.bind(owner, capabilities, authorization)`。bind 必须消费 Runner 的原 token（不能伪造），并核对预留 runId / sessionId。bind 无 I/O，不在 bind 中 open。
4. Runner 的原批次 Dispatcher 继续核验实际 invocation、完整 WriteEffect、同一授权和 owner。若最终 DispatchOutcome 是 Unknown（包括 ledger 写回失败），调用 session.markOutcomeUnknown(code)，保证页面不把底层已回包误显示成最终成功。适配器额外核对 session 的完整批准 effect，**即使宿主误加了其它 script 的 WriteEffect，也不能扩大本次批准**。工具 schema 只有 script / timeout_ms，无 UID / scope 字段。回执仍走原 journal/ledger，不绕过。
5. 停止、撤销、准备失败、owner 失效或 run 收尾用 `session.revoke()` / `session.closeWithResult(...)`；UI 撤销先封 live gate，再收口原 adapter。请只通过 session 观察关闭，不同时抢占 commands 的唯一关闭 observer。回调的 `Drained` 只说明所属 shell/执行线程/本地 RPC 已排空，不包括脱离后代或副作用回滚；`Unknown` 不能进入 Released / 不能释放中央写操作责任。
6. 原授权管理器只有一个 current connection；同应用新 connect 会封掉旧连接。宿主在旧 run 没有明确收尾前应拒绝新 Shizuku run，尤其不要靠切换 root/shell 清掉 Unknown。不要自动降级其它 Android shell、root 或 Linux。
7. 成功的 `open` 仅代表连接/批准已就绪，不是命令成功。页面显示实际身份、最后执行回执、关闭 pending/Drained/Unknown。结果 Unknown 封口该 session，不可通过新 callId 或重建 session 自动重放；非零进程退出仍保留原错误回执。

## 静态检查与尚未执行验证

本任务仅源码回读和接口检查，未运行 Shell、Gradle、单测、仪器测试、CI、安装或真机命令。

新增 `ShizukuRunSessionTest` 14 项 JVM 源码：未批准/无身份零 I/O、精确字段/旧显示快照/无效参数、显式 UID/无自动命令、错身份关闭、错 run/session、额外 host grant 不扩大批准、撤销、Unknown 不重试、迟到 open 收尾及失败不假装排空。既有 `RunShizukuCommandsTest` 保留。

新增 `ShizukuApprovalScreenTest` 4 项仪器源码：无默认身份、批准不自动 open、字段编辑撤销、撤销后禁用、Unknown/关闭未确认展示；只渲染批准组件，不请求权限或连接服务。以上**均未运行**，总集成最终统一编译/测试。

仍待总集成和真实设备验收：新路由/稳定 run 预留、同 TaskAuthorization 注入、原 registry/Runner 绑定、中央资源关闭责任；真实 Shizuku 拒绝/撤销、shell/root 两种身份、服务重启、跨进程关闭。不得用假 backend 测试冒充真实授权或执行证据。
