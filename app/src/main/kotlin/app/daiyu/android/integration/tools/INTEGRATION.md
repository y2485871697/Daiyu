# RunCatalogTools：Shizuku 最小接线

## 范围与依据

本轮仅修改 `RunCatalogTools.kt`、本说明、`RunCatalogShizukuTest.kt` 和同包 `RunCatalogToolsTest.kt`；没有修改 Controller、Activity、底层 Shizuku 或公共 domain，也没有构建、执行测试或 CI。

读取的接口依据：

- `domain/tools/ToolRegistry.kt`：实际不可变能力快照类型名是 **`SessionCapabilities`**，仓库内没有 `CapabilitiesSnapshot` 类型；`TaskAuthorization.allowedWrites` 也是不可变快照。
- `ShizukuRunSession.kt`：`open` 消耗显式批准，`markOutcomeUnknown` 将未知执行结果置为 sticky，`closeWithResult` 区分 Drained/Unknown。
- `RunShizukuCommands.kt`：`bind` 保存同一个 owner/authorization/capabilities 对象；真正执行仍要求精确的 WRITE grant 和活的 session 批准。
- `CatalogModelBridge.kt`、`ToolDispatcher.kt`、`ToolLedger.kt`：整批预检、持久 reservation、执行和最终 receipt 持久化都走既有路径。adapter 已返回 Confirmed，最终 Dispatcher 仍可能因持久化失败返回 Unknown。

本轮任务指定基线为 `2522080`，修复审阅 `c849a271` 指出的关闭生命周期问题；本子任务没有 Git 访问能力，未独立核验提交号。

## 给父集成的准确接口

保留旧的前八个参数与末尾 capture lambda；新参数都有默认值：

```kotlin
RunCatalogTools(
    registry: ToolRegistry,
    capabilities: SessionCapabilities,
    ledger: ToolLedger,
    conversationId: ConversationId,
    runId: RunId,
    deviceResources: RunDeviceResources? = null,
    children: RunChildModels? = null,
    backgroundFactory: ((RunExecutionOwner, SessionCapabilities, TaskAuthorization) -> RunChildTasks)? = null,
    authorization: TaskAuthorization? = null,
    shizuku: CatalogShizukuBinding? = null,
    closeResource: (AutoCloseable) -> Unit = { it.close() }, // 同包测试故障注入；生产保持默认
    capture: (SessionCapabilities, TaskAuthorization) -> SkillRunSession
)

CatalogShizukuBinding.open(
    session: ShizukuRunSession,
    access: ShizukuAuthorization
): CatalogShizukuBinding

RunCatalogTools.closeWithResult(onClosed: (RunCatalogCloseResult) -> Unit)
RunCatalogTools.closeWhenIdle(onClosed: () -> Unit) // 原签名仍可用

sealed interface RunCatalogCloseResult {
    data object Drained : RunCatalogCloseResult
    data class Unknown(val reason: String) : RunCatalogCloseResult
}
```

以上为同模块 `internal` API。`CatalogShizukuBinding` 的构造器私有，持有**由给定 session.open 创建**的 session/commands 配对。测试另有 `(Int, String) -> ShizukuBackend` 的 `open` 重载，生产应使用上列 `ShizukuAuthorization` 重载。

### 推荐顺序（父集成负责，当前 Controller 尚未改动）

1. 预留实际 run ID、决定能力快照的 session ID。`ShizukuRunSession(sessionId, runId.value)` 必须使用这些 ID，不假定 conversation ID 就是 session ID。
2. UI 展示 uid、完整 script、timeout、run，明确确认后调用 session 的 `approve(displayedState)`。不默认 uid、不代用户批准，不在后台自动重新批准。
3. 创建一次 `SessionCapabilities`（需要启用 `RunShizukuCommands.CAPABILITY`）和一次 `TaskAuthorization(sessionId, runId.value, session.allowedWrites)`。如需其他独立写权限，仍由可信 host 明确授予；Catalog 不修改、合并、补齐任何 grant。
4. 在 worker 上调用 `CatalogShizukuBinding.open(session, access)`。该调用只开启已经批准的 run，不执行探测/命令，不请求权限，不提供 fallback。
5. 将**上述同一个** authorization 和 binding 注入 Catalog；Runner 仍通过既有 `bindOwner` 传 owner。Catalog 将同一 authorization/能力快照传给 devices、children、background、capture、Shizuku commands 与 Dispatcher。
6. 不自行 `registry.register(commands.tool())`，不直接执行 prepared tool。Catalog 在 lazy preparation 中将 binding 的命令注册到同一个 registry/snapshot/Dispatcher/Ledger。

有 Shizuku binding 却未传 authorization，或 authorization/session 的 sessionId、runId 不匹配时，构造即拒绝。未授权命令/禁用能力依旧由整批预检拒绝，不会补 grant 或先执行。旧的无 Shizuku 调用仍可省略 authorization，此时只创建空 allowedWrites。

### 为什么没有裸 session + 任意已创建 commands 参数

现有底层接口没有公开“commands 是否属于某个 session”的证明：两个对象之间的关联是私有字段，而且 `RunShizukuCommands` 可直接构造，默认 admission/approval 回调不能当作产品批准证据。仅验证两组字符串 ID 不足以安全接受任意配对。

因此本实现用很小的 `CatalogShizukuBinding.open` 在 session 自身创建 commands 的位置固定配对。若父集成已经先调用过 `session.open` 得到裸 commands，**不能再次 open 或伪造 binding**；需将首次 open 移到此工厂。若必须接收那种旧的裸对象，需另行批准底层新增可验证关联接口；本次按范围限制未修改底层。

## 关闭责任与 Unknown

- `close()` 在短 `closeLock` 下同时 fence 新 Catalog dispatch、创建准入及资源发布，并认领已有 session/background/children/devices/skills 的停止动作；锁外逐个尽力关闭，不等待 factory、capture、dispatch 或远端排空。已有资源的 `close()` 仍须遵循其非阻塞停止契约，Catalog 不把任意阻塞 close 变为异步任务。
- 每个资源的停止动作按对象身份最多认领一次。单个 `close()` 抛 `Exception` 不会跳过后续资源，失败被保守记为 `Unknown("catalog_close_unconfirmed")`；失败资源不再通过 close-observer 重试。其余资源的 drain 观察分别注册，某一注册抛错不会阻止其他注册。失败原因不包含异常原文。
- `bindOwner` 的 background factory 与 lazy skill capture 都先登记在途创建。factory/capture 在 worker 的 `setupLock` 下执行，关闭路径从不获取该锁。返回后只可在 `closeLock` 下检查并发布；若已关闭，late 结果不发布，先执行一次停止再注销在途创建。factory/capture 本身抛异常时，原异常继续交还 worker、停止其他资源，并缓存 Unknown（未交回句柄，不能证明部分创建已清理）。factory 永不返回时只保留未决观察，不阻塞 close 调用，也不谎报 Drained。
- 终态发布前要求所有在途创建结束、认领的停止动作都已尝试、已有同步 dispatch 已返回最终 receipt。无故障时还须全部 drain 回调确认；已知 Unknown 时仍尝试所有其他停止/观察注册，但不再等一个可能永不回调的 drain。Unknown 不代表已释放一切资源。background 的停止只封闭该父绑定的新提交，不取消已获准的 retained children。
- Shizuku-enabled Controller 必须调用 **`closeWithResult`**，在回调内切回自己的 handler/串行控制线程。回调可能同步发生，也可能来自 worker；不保证 UI 线程。资源 close、观察注册、外部回调均不持有 `closeLock`；Catalog 的结果回调也不持有 `setupLock`。同一终态可重入注册，观察者自身异常隔离，不阻止其他观察者。全部观察注册返回前不发布终态，避免同步回调后注册又抛异常却已报 Drained。
- 该接口只观察一次底层关闭，聚合原有 child-model/device drain 和 Shizuku 的关闭结果，并等本 Catalog 已进入的同步 `execute`/`start` 返回最终 Dispatcher receipt 后通知。重复注册得到同一已缓存结果，不会重新 close-observe/retry 底层。
- `Drained` 仅表示这些已跟踪的 run 资源关闭/排空得到确认，不表示 shell 的副作用回滚或 detached 后代进程消失。执行结果 Unknown 与 cleanup Drained 可以同时成立。
- `Unknown(reason)` 是终态。Controller 应停止新命令、展示/保留未知状态，并执行自己的未知关闭分支；不能当成 Drained、自动重发、换 call ID 重试，也不能回调重入无限观察关闭。
- `closeWhenIdle { ... }` 保留旧签名，但只在 **Drained** 时调用原 callback。Unknown 不会调用这个无结果 callback。因此**当前未修改的 Controller 不应启用 Shizuku**，否则它没有处理 Unknown 的分支。
- Catalog 观察 `execute` 返回的全部 receipts 以及 `start` 的 Completed receipts；任何最终 Dispatcher Unknown（包括 ledger finish 失败、UNKNOWN reservation）都会调用同一 session 的 `markOutcomeUnknown`。这在 adapter 自身已报告 Confirmed 时同样适用。
- `start` 的 Suspended 只可能是单独的 USER_BARRIER，并非 Shizuku 命令。公共 `BarrierOffer` 不允许 app 包装最终 finish callback；Runner 后续 checkpoint/等待问题及 Controller 的总生命周期仍由原有 owner/Runner 管理。若要求“任何后续 Runner/Barrier Unknown 也标记 session”，父集成需在已知最终 Unknown 处调用 `session.markOutcomeUnknown(reason)`，不能声称本 Catalog 拦截了那个公共接口之外的后续结果。
- 已有 background child supervisor 的总排空仍归 Controller 原有生命周期逻辑；本接口不替代它。`ShizukuAuthorization` 管理器的监听器/全局连接生命周期也仍归创建它的 host，不由一个 Catalog 擅自释放。
- 工厂 open 或 Catalog 构造失败时，父集成仍持有 session/binding，必须关闭并观察清理；不能因为没有成功创建 Catalog 而丢失资源所有权。

## 增量测试源码

`RunCatalogShizukuTest` 覆盖：

- 同一授权与能力对象绑定，commands 只注册进现有 Catalog，精确 grant、Ledger 记录/重放；
- 缺少显式 authorization、跨 session/run 拒绝；能力/批准不自动授写权限；
- `execute`/`start` 的最终持久化 Unknown 回写 session、UNKNOWN reservation 不重新执行；
- Drained/Unknown 关闭区分、延迟结果、重复观察、旧 callback 不谎报 Drained；
- adapter 已结束而 Ledger finish 尚在执行时不提前发关闭结果；
- 独立 latch 阻塞 background factory，证明 close 在 factory 放行前返回、此前无 Drained，late 父绑定在终态回调尝试提交时已被封口，且原 authorization/capabilities 身份与 grant 未改变；
- 独立 latch 阻塞后抛出单资源 close 异常，证明并发关闭不会重复停止该资源，后续 devices/skills 仍关闭、所有观察者收到缓存 Unknown，旧无结果 callback 不误报 Drained；
- factory 抛异常时保留原异常并缓存 Unknown、不重建；回调自身异常和重入注册不抑制其他结果。

同包 `RunCatalogToolsTest.closeDuringCaptureDoesNotWaitOrPublishLateScope` 沿用原 latch，并新增断言：在途 capture 未返回时不报 Drained，late scope 关闭后才发结果。上述新增回归不用 sleep；超时仅作为测试死锁保护。

旧的非 Shizuku 测试源码保留。测试只编写并静态检查，未构建、未执行、未触发 CI；不声称已通过编译或运行。
