# R08：有限动作编辑与停止后的额外收尾接缝

## 范围和交付状态

本增量沿用基线 `af18af3` 的唯一 `ControllerVirtualDisplayRuntime`、持久 lease/journal、原 owner/client，以及中央 `RunCatalogTools` / registry / dispatcher / ledger / 整批最终结果反馈。没有新 runtime、目录、授权总账、owner launcher、Manifest 路由、CI 或清单。没有改 MainActivity、ChatController 或旧 Eta。没有新复制 backend-kit 文件；既有授权/许可文件不变。

**新增的是可嵌入的非 Main Compose 编辑/收尾审批面和精确方法接缝，不声称现有 Main 已切到新界面。** 父集成需替换 Main 固定 Start/Initialize/Release 计划入口并提供显式收尾入口，见下文。没有 shell、设备操作或构建/测试执行权限；本文列出的是源码与待执行测试，不是通过报告。

## 1. 编辑 → 冻结 → 既有生产审批（不提前启动或授权）

文件：`vdapproval/R08PlanEditorHost.kt`、`R08PlanEditor.kt`。

```kotlin
val visit = R08PlanEditorHost() // 每次编辑访问一个独立宿主
R08PlanEditor(
    host = visit,
    onFrozen = { frozen ->
        // 在原 UI/Controller 所有权检查内：校验 Activity、当前会话、编辑 generation、
        // draftRevision、原 draft、无附件及没有并发 send。不要从此回调自动重发消息。
        val entry = controller.prepareVirtualDisplaySend(
            originalConversation, originalTextSendOwner, originalText,
            frozen.writes, frozen.reads)
        // 保留这个 ORIGINAL entry，走现有 submitVirtualDisplay/sendVirtualDisplay
        // 和 DB 受理回执；不要在这里另建 session、Runner、lease 或 authorization。
    },
    onCancel = { /* 仅关闭这次未提交的计划访问；不清草稿、不调用 send */ }
)
```

父的准确替换点为 MainActivity `openVirtualDisplayPreparation()` 中现有硬编码 writes/reads 列表，**不要**改 `submitVirtualDisplay()` 的原 entry/text/editor 对象身份与草稿检查、数据库受理边界及 UNKNOWN 不重发逻辑。编辑访问也必须捕获原 conversation/editor/text，访问替换、返回、切会话只取消原访问，不让旧 `onFrozen` 写入新访问。

- 动作：Start、Initialize、Launch(package)、Tap(x,y)、Swipe(x1,y1,x2,y2,durationMs)、Text(text)、Key(keyCode)、Release；读取：Status、Snapshot(include,maxBytes)。按钮、文本输入、删除、上下移真实修改 rows；冻结后这些参数真实成为 `R08VirtualDisplayWrite/Read`，不只是描述文本。
- UI 有限范围：坐标 0–32767，时长 1–60000 ms，keyCode 0–1000；完整包名 ≤255 字符；文本 1–4096 字符且无控制字符；snapshot include 必须 true/false、预算 1–6291456。整数不接受负号、前导零、溢出或隐式 clamp。OwnerV2Contract 仍负责最终规范参数和默认值，不从 UI 获取 displayId。
- 总共 2–64 项；写计划以唯一 Start、Initialize 开头，Release 至多一次且是最后一项写。读取列表独立可用，不会启动或初始化；编辑顺序不创造另一个执行器，也不保证模型按步骤执行。现有唯一中央目录只暴露冻结的 step_id/read_id。
- `R08PlanEditorHost.freeze()` 成功仅一次；失败可继续编辑；取消消耗原访问；输入列表/参数列表后续修改不能改变冻结对象。构造、显示、编辑、冻结没有存储、root、owner、工具或权限副作用。
- 最终审批仍由现有 `ControllerVirtualDisplaySession.authorizationFactory(actualOwner)` 在消息持久受理后 reserve 原生产 lease/generation，并发出 `VirtualDisplayApprovalRequest`。原 `VirtualDisplayApprovalScreen` 展示全部 binding、canonical 参数、effect，必须经 `session.answerFromUi(originalRequest, accepted)`。冻结不是审批，编辑结束不调用 `VirtualDisplayApproval.issue()`，不预生成 UUID/generation 或 TaskAuthorization。
- `R08FrozenPlan.prepare(owner,caps,generation)` 是纯转换/检查便利方法，不是 reserve API。生产仍将 frozen.writes/reads 送入原 runtime 的唯一 reserve/prepare 流程。

## 2. Stop 后的额外 Release 授权

Stop、`close()` 和 `observeCloseOnWorker()` 继续是撤权/观察，**不会隐式 Release**。已 Initialize 的原 owner 停留在 Looper 时，新的额外审批路径只作用于这个被保留的 session；它不恢复原工具授权、不重新运行文本，不换 generation、owner、连接或 UUID。

新增 API 位于 `ControllerVirtualDisplaySession`：

```kotlin
// 用户主动点击“为原副屏申请收尾”，不是 Stop/close/observe 的 finally、定时器或导航副作用。
// 先按现有 observeVirtualDisplayClose(entry) 同等级的父运行排空检查取出 ORIGINAL session：
// entry 身份仍匹配、已 revoked、catalog 仍被保留，Runner、所有中央分发、最终整批
// ledger 回调和父生命周期已排空。不能只因 cancellation flag 为 true 就宣称 drained。
val originalSession = retainedEntry.session ?: return
executor.execute {
    val request = originalSession.prepareInitializedCleanupOnWorker(parentDrained = true)
    // null = 不安全/无需 Release/已经回答或尝试，不得创建新 session/ID 作为回退。
    // 发布 request 到 UI 时再次核对原 entry/session/UI 访问身份。
}

// 展示只读冻结的 binding、generation、Release {} 和精确 effect；显示本身不批准、不发送。
R08CleanupApprovalScreen(originalRequest, onDecision = { req, accepted ->
    // 此闭包捕获 ORIGINAL retained entry + originalSession。父再次检查仍为同一保留记录。
    val answered = originalSession.answerCleanupFromUi(req, accepted)
    if (answered && accepted) {
        // 这是额外 Release 的明确 UI 动作，不是对原工具授权的恢复。
        executor.execute {
            // 再检查父已排空，否则传 false（不消耗发送尝试）。
            val result = originalSession.releaseInitializedOnceOnWorker(req, parentDrained = true)
            // 展示结果，不自动重复调用，不自动 reserve/start/initialize/reconnect。
        }
    }
    answered
})

// 用户可随后单独点击原观察入口。只有 physical drain + 已知 journal/最终结果
// 才会触发原 lease.releaseAfterDrain。额外 Release CONFIRMED 本身不是进程退出证明。
```

本提交没有增加 Controller 方法。父可在原 `observeVirtualDisplayClose(entry)` 旁加两个显式包装（准备收尾请求 / 回答并调度一次 Release），复用它现有的原 entry/catalog/Runner-drained 检查；不可把它插入自动观察路径。原 `answerVirtualDisplay()` 明确拒绝 revoked entry，因此**不能用它回答 cleanup**；使用新增 `answerCleanupFromUi()`，并用父的保留记录而非“当前编辑器”做身份门控。Back/移除/拒绝只拒绝这个原 cleanup 请求；拒绝不触发 send、Release、lease 释放或另一轮默认审批。

### 允许与保留矩阵

| 条件 | 行为 |
|---|---|
| 未 Stop，父未排空，批次/最终反馈缺失，正在 bind/cleanup/lease release | 不生成可发送 cleanup；继续保留 |
| Start 与 Initialize 都有已知成功、已撤权、最终反馈全部完成、无 UNKNOWN、从未实际尝试原 Release | 可显式准备一次 Release-only 冻结请求 |
| 原 Release 已尝试并成功、失败/不确定，或中央 ledger 已确认原 Release（包括无本地 execute 的语义回放） | 禁止替换成 cleanup Release；只观察、保留回执 |
| 原 Release 在实际 claim/send 前被拒绝/取消、没有 owner Release 尝试，其他证据均已知 | 可另行显式授权 cleanup；不是重放曾发送的 Release |
| 原 Initialize/input/launcher/最终 ledger 状态 UNKNOWN | 不以额外授权洗白 UNKNOWN；保留原 owner/session/lease/journal |
| 只有 Start、未证实 Initialize | 不用本路径推断未初始化；继续使用既有严格查证的 uninitialized shutdown 路径 |
| 额外请求被拒绝或失效 | 无任何 owner I/O，不自动生成第二个请求 |
| cleanup 已确认但尚未物理退出 | 保留 lease，等待用户显式观察 |
| cleanup 回执未知、journal claim/complete 故障或已有 claim | UNKNOWN 保留，不换 operationId、不重连、不重试发送 |

### 持久化和一次性

- cleanup 使用原 `Lease.journal`（生产仍为 `R08PersistentJournal`），没有新目录。固定 host invocation `host-cleanup-release-v1` 通过同一 `plan.intent()` 从原 binding/generation 派生稳定 operationId；它不是模型参数、不是新随机 UUID。
- 内存尝试标志在 journal/IPC 前消耗；持久 `claim(intent)` 只有 EXECUTE_ONCE 能发送；LOOKUP_ONLY/UNKNOWN 永不再 execute，保守记录/保留 UNKNOWN。若模型原调用 ID 意外同名，持久身份不匹配也失败关闭，不产生新 ID 回退。
- 同一个 retained endpoint 的原 `VirtualDisplayOwnerClient` 完成唯一发送、认证、身份/回执验证及失连 poison；没有新连接或 launcher。只接受非 receipt-only 的 `ok && released && receipt.state == SUCCEEDED`，且持久 complete(CONFIRMED) 成功才返回 CONFIRMED。
- 维护证据写入原 journal，不伪造一条中央工具 finalOutcome。普通动作仍是中央工具流程并通过原整批 ticket 回送最终 ledger 结果。新增路径不注册另一套 catalog/tool/ledger，也不把原 revoked TaskAuthorization 重新打开。
- journal 完成回执丢失即 UNKNOWN；已留下的 claim/pending 证据不删、不重置、不重试 complete 或 send。观察不能清除此 UNKNOWN。进程重启后已有 journal claim 仍只允许 lookup，本增量**不**声称可重建已丢失的活 owner/内存审批或允许改新 generation 恢复。
- `R08VirtualDisplayCatalogLeaf` 的小增量仅记录原 Release 尝试及中央确认的 Release，供 session 阻止重复；其工具分发、业务动作、原 release-once/owner 生命周期没有重做。

## 3. 验证来源和待执行测试

新增/扩展源码测试（未在本环境执行）：

- `R08PlanEditorHostTest`：所有实际参数到最终 canonical plan/approval rows、实际 owner/caps 对象、不可变冻结、有限输入拒绝、取消/访问单次性。
- `R08PlanEditorScreenTest`（androidTest）：真实 Compose 字段编辑产生 Launch/Tap/Swipe/Text/Key/Snapshot 参数；冻结和取消/替换身份；没有 runtime/root。
- `R08CleanupApprovalScreenTest`（androidTest）：打开不回答；确认、替换、Back/移除回调始终绑定原 request/宿主，不操作新的请求。
- `ControllerVirtualDisplaySessionTest`：Stop/观察不自动写、单独审批、原 owner/generation、拒绝/外来请求、parent drain/final feedback、一次发送、UNKNOWN/完成回执丢失保留、原 Release 成功/UNKNOWN/中央语义回放禁止替代、并发观察与重复发送栅栏、已有 cleanup claim 不重放。
- `ControllerVirtualDisplayRuntimeTest`：真实私有文件 lease/journal + 原认证 v2 client 的脚本 transport；Initialize → Stop → 额外 Release，同 host/身份/一次发送；成功与丢回执；journal 重开仅 LOOKUP_ONLY；只在已知成功且物理退出后写 released.event。顺带纠正原相关测试把 String 传给 `OwnerProtocol.ok(op, JSONObject?)` 的静态类型错误（改用 `.put("generation", ...)`）。
- `R08PersistentJournalTest`：确定性 cleanup Release 的持久 UNKNOWN 事件与重开不执行/身份冲突拒绝。既有多线程/跨进程、故障注入/lease 测试不替代、不宣称已跑。

主代理需执行目标编译/单元测试及可用的 Compose instrumentation 测试；无设备时不能宣称 Android 输入/物理释放已实测。本交付没有构建成功、设备成功或全量回归通过的断言。
