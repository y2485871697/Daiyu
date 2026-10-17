# R08 cleanup：非释放 local-drain gate 与父接入计划

## 范围与证据

基于任务给定的 `ed78f7c`（已含已审 `d7db52d0`），先读 `docs/R08-actions-cleanup-production.md`，再检查实际 catalog、Session、Controller 和审批屏源码。local-drain 与 adapter 基线已在候选 `0e89492` 中。本次在该候选上接入 ChatController、MainActivity，并补充必要 adapter 关闭同步与测试源码；不改 leaf、唯一清单、公共更新说明或 `R08-actions-cleanup-production.md`。下文原父接入建议现已落到生产源码，但**未编译、未执行测试或 APK，不声称设备验证通过**。没有 Shell、设备、联网、付费、push 或凭据操作；HEAD/工作树干净状态不能通过本环境的 Git 工具独立核实。

缺口已从源码确认：`ChatController.observeVirtualDisplayClose(entry)` 的外层检查只证明原 entry/catalog、撤权、父 Runner 结果/生命周期条件；它不持有 catalog 的私有 `closeLock`，不能单凭这个条件把 cleanup 的 `parentDrained` 当作全部排空证据。原 `RunCatalogTools.observeVirtualDisplayCloseOnWorker` 在锁内另有完整本地排空门。不能调用该 physical observe 当 cleanup 的探针，也不能等待最终 catalog `Drained`：后者本来就在等这一个保留 Session 的物理退出，会让 initialized cleanup 等待自身。

## 实际新增接口

### 1. Catalog 唯一本地排空条件

`RunCatalogTools.localDrainReadyLocked()` 抽取并复用原完整条件：

```text
closed && observingClose && observationsStarted && !registeringObservations
&& activeCreations == 0 && activeDispatches == 0 && pendingStops == 0
&& remainingObservations == 0 && closeFailure == null
```

- 原 physical observe 路径继续使用同一个门，没有放松其原检查。
- `virtualDisplayCleanupLocallyDrained(originalSession)` 只读锁内状态与保留 session 对象身份，不观察 owner、不调用 journal、不释放 lease。
- `prepareVirtualDisplayCleanupOnWorker(originalSession, parentDrained)` 和 `releaseVirtualDisplayCleanupOnWorker(originalSession, originalRequest, parentDrained, admissionStillValid)` **各自重查**此门及原 session 身份；失败分别返回 null / UNAVAILABLE，不消耗发送尝试。两个操作都要求 worker。
- 父 Runner 已返回仍不够：catalog stop 尚在执行，其他资源 drain callback 尚未完成，注册尚未结束、创建/分发/最终整批回送尚未结束，或任一 close 失败，均不通过。
- 锁只保护短状态读取；不跨 Session、host、journal 或 IPC。关闭后的 admission 不会重开；零 active creations 后不会出现新资源发布，完成注册和 one-shot observations 后不会出现新一轮排空任务。
- 最终 catalog `Drained` 仍须 Session physical drain，UNKNOWN 仍保留；cleanup CONFIRMED 不置 `virtualDisplaySessionDrained`、不触发 `releaseAfterDrain`。

### 2. 专属 ControllerVirtualCleanupAdapter

构造函数实际签名：

```kotlin
ControllerVirtualCleanupAdapter(
    originalCatalog,
    originalSession,
    uiOwner: R08CleanupUiOwner,
    parentIsRetainedAndDrained: () -> Boolean,
    currentUiOwner: () -> R08CleanupUiOwner?
)
```

实际方法：

```kotlin
prepareOnWorker(): R08CleanupApprovalRequest?
canPublish(originalRequest): Boolean
answerFromUi(originalRequest, accepted): Boolean // 只回答，不调度或发送
releaseOnWorker(originalRequest): ControllerVirtualDisplaySession.CleanupResult
close() // 失效本访问，拒绝仍未回答的原请求；不 Stop，不释放
```

Adapter 不创建 executor、Session、lease、generation、owner、授权总账或第二个 dispatcher。构造和显示不准备/批准/发送。准备、UI 发布、回答和 worker 发送均检查原父记录和独立 UI 访问；发送还由 catalog 再查完整本地门。父状态暂时未排空时不消耗发送尝试，必须由原显式动作决定后续处理，不自动定时重试。

新增 `R08CleanupUiOwner` 是每次显式 cleanup UI 访问独立的一次性对象，不是 `TextSendOwner`，不依赖原文本编辑器仍活着。它只有原子 active 标志和 `close()`；切 Activity、会话、访问替换、移除时必须**先同步完成旧 adapter.close()（含拒绝旧已发布未回答请求），再发布新 owner/visit**。单独 `owner.close()` 仅是 admission fence，不等价于关闭 adapter；旧请求可能仍由 Session 保留，不能在其拒绝尚未完成时让新访问继承。`currentUiOwner` 用安全的原子/锁读，不能在 worker 直接读不受保护的 Compose state。旧 closure 只捕获旧 adapter，绝不能通过“当前 adapter”转发。

Session 唯一必要增量是 `releaseInitializedOnceOnWorker` 的可选 `admissionStillValid: () -> Boolean = { true }`，在原 `cleanupAttempted = true` 前的锁内检查。Adapter 只提供原子 UI owner/closed 读取，**这里不得调用 Controller 或其他外部锁、不得 I/O**。这样准备/接受后失效的排队 worker 不能消耗 claim/send；通过这个 admission 后已经开始的操作不能被导航撤回，也不能因导航换 ID 或重试。

底层保留不变：原 owner/client/connection、generation、一次性 `host-cleanup-release-v1` journal intent、原 Release 去重、UNKNOWN 保留、审批 binding/effect、原整批 final feedback、lease 的原物理排空释放条件。没有重写底层 Release 或引入自动收尾。

## 父 ChatController 接线（本次已实现，未运行）

在原 `observeVirtualDisplayClose(entry)` 邻近已加专属包装：

```kotlin
internal fun openVirtualDisplayCleanup(
    entry: ControllerVirtualDisplayEntry,
    uiOwner: R08CleanupUiOwner,
    currentUiOwner: () -> R08CleanupUiOwner?
): ControllerVirtualCleanupAdapter?
```

在 Controller monitor 内获取并固定 ORIGINAL catalog/session；拒绝 closed、外来 entry、未撤权或缺少保留对象。`virtualDisplayParentDrainedLocked` 已提取原 observe 外层条件，原物理观察与 cleanup 共用；adapter 的闭包另重查 !closed 和 ORIGINAL session。下列为等价合同（实际实现复用该 helper，不是第二套更宽的门）：

```kotlin
parentIsRetainedAndDrained = {
    synchronized(this@ChatController) {
        if (closed || virtualDisplayEntry !== entry || !entry.revoked ||
            entry.catalog !== originalCatalog || entry.session !== originalSession) false
        else {
            val lifetime = resources
            if (lifetime != null) {
                lifetime.tools === originalCatalog && !lifetime.busy && !lifetime.waiting &&
                    lifetime.result != null && lifetime.devicesClosing
            } else preparation == null && originalCatalog in unacceptedTools
        }
    }
}
```

此谓词不是 catalog drain，不能绕过包装直接向 Session 传 true。不要调用 observe 来判定 cleanup 可用；不要以 `cancelled`、`revoked`、UI 空闲、Runner Stop 请求或最终 `closeWithResult` 已登记代替实际返回/排空。不改原 `answerVirtualDisplay` 拒绝 revoked entry 的门；cleanup 用自己的 adapter 方法。所有 executor 调度和 UI 回调保留原 entry+adapter 身份，回到 UI 再用 `canPublish`，不把结果交给后来一轮访问。

## 父 Main 接线（本次已实现，未运行）

### 有限动作编辑

1. `openVirtualDisplayPreparation()` 的固定 Start/Initialize/Release 列表已替换为每次访问新建的 `VirtualDisplayPlanVisit` + `R08PlanEditorHost`，顶层 `VirtualDisplayR08Surfaces` 用 `key(host)` 显示既有 `R08PlanEditor`。真实参数成为 frozen.writes/reads；不默认添加 Release。
2. 新访问捕获 ORIGINAL Activity 闭包、conversation、TextSendOwner、原文本、编辑 generation/draftRevision；`freezeVirtualDisplayPlan` 再检查原访问和快照、无图片/视频附件、没有并发 send/准备，才把 frozen.writes/reads 送入原 `controller.prepareVirtualDisplaySend`。取消、导航、替换只关闭旧 plan host，旧 frozen/cancel 回调不改新访问或草稿。新增 plan visit 同时加入原视频/语音/文本/终端互斥门，不移除既有功能接线。
3. 原 `submitVirtualDisplay` / `sendVirtualDisplay` 未改，继续使用 entry、text/editor 对象身份、SQLite Accepted 回执边界、UNKNOWN 不重发和最终生产审批；编辑冻结不是批准，不在编辑回调创建运行或 TaskAuthorization。

### 显式 cleanup + 独立 UI owner

已维护独立于文本编辑器的 `AtomicReference<R08CleanupUiOwner?>` 和 `VirtualDisplayCleanupVisit(originalEntry, owner, adapter, request?, decided, status)`。显式按钮捕获 originalEntry，不读取后来“当前 entry”来转发。实际用既有 `lifecycleScope` + IO worker（NonCancellable 只保证在已调度任务中返回可核对的结果，不越过 adapter admission），UI 返回后检查原 visit，再 `canPublish`；明确 accepted 才调用 worker release。下面是等价时序，`executor/handler` 为 worker/UI lane 的示意，不是新增 executor：

```kotlin
// 仅“为原副屏申请额外 Release 收尾”的显式按钮；不是 Stop/finally/观察/导航触发。
oldVisit?.adapter?.close() // 同步失效并拒绝旧已发布请求，再替换；不能只换引用
val owner = R08CleanupUiOwner()
currentCleanupOwner.set(owner)
// 此段放在普通 openCleanup(...) 辅助函数中，按钮仅调用该函数。
val adapter = controller.openVirtualDisplayCleanup(originalEntry, owner) { currentCleanupOwner.get() }
    ?: run {
        owner.close()
        currentCleanupOwner.compareAndSet(owner, null)
        return // 不创建 fallback session
    }
executor.execute {
    val request = adapter.prepareOnWorker()
    handler.post {
        if (request != null && adapter.canPublish(request)) {
            // 只向仍为原 entry/owner/adapter 的访问发布 request，否则关闭该旧访问。
        }
    }
}

// 在该访问的组合身份内，不复用 text editor owner：
key(owner) {
    DisposableEffect(adapter) { onDispose { adapter.close() } }
    R08CleanupApprovalScreen(originalRequest, onDecision = { req, accepted ->
        val answered = adapter.answerFromUi(req, accepted)
        if (answered && accepted) executor.execute {
            val result = adapter.releaseOnWorker(req)
            handler.post {
                // 再核对原访问身份；显示结果，不自动重发、重连或清保留记录。
            }
        }
        answered
    })
}
```

- `key(owner)` 必须包括独立访问身份；仅按 request key 可能让同请求的旧 remembered callback 跨访问复用。
- Back/拒绝/移除关闭这次原访问；会话切换、Activity 销毁、访问替换必须同步完成原 `adapter.close()`，**不能用单独 `owner.close()` 替代，也不能将二者理解为二选一**。Main 的 `closeVirtualDisplayCleanup(originalVisit)` 先完成 adapter 拒绝，再 CAS 清该旧 owner；仅当仍为该旧 visit 时才隐藏，绝不清后来访问、草稿或触发 Release/lease release。原导航既有的 run 撤权/Stop 保持不变，不将其变成 Release。
- Adapter 重复 close 现在也等待原请求拒绝完成；不会因 closed 已置 true 就提前返回。否则旧 onDispose 的 close 若阻塞在 Session 回答锁，Main 再次 close 早退并发布新访问，旧 close 随后可能抢先拒绝新页面继承的同一请求。未发布的冻结请求仍不由旧 worker/close 代答；后续显式有效访问可以审阅。
- 此 cleanup 入口要求与原保留 entry 匹配，并不要求原文本 editor 还活着；新 UI owner 不赋予新的 run 权限。
- 允许单独保留“核实副屏停止”物理观察按钮。cleanup CONFIRMED 不是进程退出证明，只有原 physical drain + 已知 journal/final feedback 才可释放 lease/移除保留记录。

## 新增测试源码与待统一验证

在 `RunCatalogVirtualDisplaySessionTest` 扩展真实 catalog + Session + leaf 测试夹具，仅 endpoint/持久层和其他资源观察时机为测试替身：

- 真实 Runner-issued owner 已返回、session 已撤权，但 catalog stop 尚在阻塞：准备和发送两阶段均拒绝；stop 返回后可发送一次，最终 catalog callback 仍未 Drained。
- 其他 browser 资源的 observation 尚未回调：两阶段均拒绝；回调 Drained 后 cleanup 可发，不等待自身 Session 的物理退出。
- stop 抛错、其他 observation UNKNOWN：重复检查仍不通过，不 claim、不 send，不释放保留对象。
- Adapter 在 worker 准备后、UI 发布、回答、接受后的排队发送分别重查父门；失败不消耗尝试，原显式调用在门通过后仅一次发送。
- 旧独立 UI owner 的 worker/回调在 owner 替换后不能发布、回答、重新准备或发送；关闭旧 adapter 不会关闭新 owner。另用测试时序接缝暂停冻结后、发布前的旧 worker：替换访问可审阅原未发布的冻结请求，旧 worker 恢复只能返回 null，不能替新访问作拒绝决定。
- Session 发送 admission 的纯原子 UI fence 不通过时没有 claim，后续原请求可在有效门下发送一次。
- 上述 gate/adapter 测试断言 physical observe 次数及 lease release 次数仍为零，防止偷偷用物理观察探测或提前释放。

本次另补/适配的测试源码：

- `RunCatalogVirtualDisplaySessionTest.repeatedCloseMustWaitForDelayedOriginalDenialBeforePublishingReplacementOwner`：真实 Session 的回答锁延迟旧已发布请求的 close，重复 close 不得早退发布新 owner；旧拒绝完成后新访问不得继承已拒绝请求，claim/send/observe/lease release 均为零。原 stop 阻塞、其他资源 observation pending/UNKNOWN、本地 drain 两阶段回归保留。
- `ControllerVirtualDisplayParentTest`：真实 Controller open 包装 + SQLite Accepted + Runner + 唯一 catalog + 原持久 lease/journal 与认证 v2 client（仅 transport/process host 脚本替身）。Start/Initialize 后，Stop 已撤权但 Runner 尚未返回时仍拒绝 prepare；返回后拒绝未撤权/外来 entry 与被替换的 catalog/session；worker prepare/UI canPublish/明确批准/worker release 保持原 owner/caps/generation/authorization/session。CONFIRMED/丢回执 UNKNOWN 均至多一次发送，不新增消息、不探测物理退出或释放 lease；批准后排队任务被 close 失效时不发送。
- `MainVirtualDisplayCallbackTest`：真实 Main R08 编辑面 Tap 参数进入原 prepare；未冻结无 entry/lease/授权；旧 frozen/cancel、导航回调只作用原 visit；原 submit/SQLite Accepted、旧 Dialog/Stop callback 回归改走真实编辑冻结。显式 cleanup 在原 text editor 已移除时仍能打开；无 Initialize 证据时显示不可收尾，不用 physical observe 探测；旧 close 不影响替换访问、导航只关闭 UI、UNKNOWN 仍保留。另有真实 Main initialized cleanup 审批/拒绝/CONFIRMED/丢回执 UNKNOWN 源码：测试仅向被暂停的原模型 event sink 输入冻结 step_id，仍由原 Runner/整批 ledger 执行 Start/Initialize，使用原认证 client 的脚本 transport；Main worker 发布后才允许显式批准，结果与第二次显式访问均不重发、不物理探测、不释放 lease。脚本端点不是设备验证，所有这些测试均未运行。
- `VirtualDisplayMainActivityTest`：旧真实父入口测试适配先冻结，再走既有准备/导航/受理边界。

这些是**源码断言，不是测试通过报告**。既有 Session/Runtime/PersistentJournal/审批屏测试仍负责原 owner/generation、固定 intent、Release 去重和 UNKNOWN 持久保留等不变量。本实现环境只有文件工具，没有 Shell，未编译，未运行任何单测、instrumentation、APK、网络或付费操作；主代理需统一审阅并在允许的离线构建环境验证。没有设备验证，不能声称 Android 输入、物理退出或视频/语音/终端端到端回归通过。本次没有 Git commit/hash/patch 导出能力，不声称已提交或 push；工作树非空改动供主代理形成 review-ready Git 产物。
