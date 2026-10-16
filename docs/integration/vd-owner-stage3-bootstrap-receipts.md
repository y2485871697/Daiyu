# R08 owner 侧：认证 bootstrap 与 durable receipt 接线（协议 v2）

本说明接续 [stage1](vd-owner-stage1.md) 与 [stage2](vd-owner-stage2-receipt-foundation.md)。旧文档中的“尚未接入 Main/IPC”是当时状态；本次仅闭合 **owner 进程一侧**。不修改 MainActivity、ChatController、RunCatalogTools，不提供中央目录/应用启动器/客户端恢复策略，不宣称完整 R08、exactly-once 或真机通过。

## 生产接线与信任边界

- `VirtualDisplayOwnerMain` 仍只允许 UID 0。可信启动参数新增必填 `--receipt-root <absolute-normalized-private-root>`；目录由可信 launcher 预建为 0700，并保证祖先不能被非可信写者替换。目录、display 名称和几何只来自启动参数，业务 JSON 不能覆盖。
- `--allow-uid` 现在明确要求 Android 普通应用 appId（UID % 100000 在 10000–19999，支持多用户）。不允许 root/system/shell/isolated UID 作为初始化对端。
- Main 的顺序为：root 检查 → 主 looper → `OwnerReceiptStore` lifetime lock → `OwnerReceiptCoordinator` → IPC socket → 私有 bootstrap 行。**此时没有调用 VirtualDisplayOwner.create，也没有 display**。
- READY 行仍为 `VD_OWNER_READY v=2 socket=... token=... pid=... uid=0 allowUid=... initialized=false`。它只表示可认证，不是 display-ready。token 只允许经可信 launcher 的私有 stdout pipe 交给应用宿主，不能接日志、模型上下文、工具结果或普通诊断转储；launcher 尚待中央宿主实现，本 owner 不能验证 stdout 的最终路由。
- 每个连接的 kernel peer UID 检查保留；每个请求的常量时间 token 校验保留。二者均通过之后，才把请求投递到唯一 owner handler。绑定 JSON 的解析不会执行 owner；`RunBinding` 仅在认证后的 coordinator 内，使用真实 `bindingFromTrustedHost` 工厂构造。
- `initialize`/`receipt` 不是模型工具。六维 binding 必须由已认证应用宿主从中央签发的 session/task/conversation/run/assistantMessage/runOwner 状态填入，不能从模型参数透传。拥有正确 UID 和 token 的宿主被视为可信；恶意同 UID 代码、被攻陷的宿主、root 或私有目录写者不在防御范围内。

## v2 envelope（不兼容 v1，拒绝未知字段）

仍是一行 UTF-8 JSON。下面的占位符不是可执行启动脚本，也不是真实凭证。

初始化：

```json
{"v":2,"op":"initialize","token":"<private-token>","generation":"g_1","binding":{"session":"s","task":"t","conversation":"c","run":"r","assistantMessage":"m","runOwner":"o"}}
```

- generation 由可信宿主为此次 owner lease 选择并稳定保存，格式沿用 identity 的 1–64 ASCII `[A-Za-z0-9_-]`。旧 generation 不因重新启动变新。
- 只有 `reserveGeneration` 的 fresh claim，再成功 `start`，才调用 Main 中真实 `VirtualDisplayOwner.create(...)` 工厂；这保证 durable STARTED 先于 create。
- 初始化成功返回 `initialized=true`、generation、displayId、uniqueId 和 generation receipt。**存活期间 generation 保持 STARTED**，否则现有 store 会拒绝 reserveOperation；create 成功不是整个 lease 的 final。
- 已存在的任意 generation 状态（包括 RESERVED/STARTED/UNKNOWN）仅返回 receipt，绝不调用 create。一个进程至多尝试一次 fresh generation 初始化；create 抛异常写 UNKNOWN，不自动重试、切换 generation 或退出可能仍持有资源的进程。

六个业务操作：

```json
{"v":2,"op":"input","token":"<private-token>","generation":"g_1","operationId":"input_1","parameters":{"kind":"key","keyCode":4}}
```

支持 `status/launch/input/snapshot/handoff/release`。每个操作（包括 status/snapshot）都要求稳定 operationId；无 token、错误 UID、v1、缺 ID/parameters、任意额外 envelope 字段都拒绝。不接受原来扁平的业务参数，也不再接受 `timeoutMs`（当前等待预算固定 15 秒）。

允许的 parameters：

| op | 字段（原业务算法继续验证语义/范围） |
| --- | --- |
| status | 空对象 |
| launch | displayId, package, component, action, flags, categories |
| input/tap | displayId, kind, x, y |
| input/swipe | displayId, kind, x1, y1, x2, y2, durationMs |
| input/key | displayId, kind, keyCode |
| input/text | displayId, kind, text |
| snapshot | displayId, include, maxBytes |
| handoff | displayId, taskIds |
| release | displayId |

未知字段和不适用当前 kind 的字段报 PROTOCOL，绝不在摘要时投影丢弃。摘要和业务参数使用脱离 wire envelope 的快照，不包含实际认证 token/binding/operationId。原 `VirtualDisplayOwner.wrap` 仍会解析扁平对象，因此只在 `OwnerProtocol/OwnerCommandDispatcher` 接缝中补上 v/op/固定公开的非凭证 marker；包内 `parseRequest` 负责剥离并验证它。IPC **只调用公开的严格 `parseWireRequest`**，绝不调用这个内部兼容解码器，旧扁平请求不能借此进入 IPC。摘要和实际执行使用同一个规范化快照：flags=0、categories=[]、swipe durationMs=300、snapshot include=true，maxBytes 默认/非正值为 6 MiB、上限 6 MiB，与原算法一致。可选目标字符串缺省在 identity 中为 null；缺省 displayId 表示 **该 generation 唯一拥有的 display**（identity 中为 null），不是随时可变的系统默认显示；缺省 handoff taskIds 在 identity 中为 null，实际业务仍按原算法拒绝不合法选择。显式 displayId 与缺省形式不是相同摘要，宿主重试/查询必须保持原参数表示。JSON 对象键排序和其余 canonical 规则沿用 stage2；身份算法及 store 状态格式没有修改。

## 执行、重复与错误

`OwnerReceiptCoordinator` 使用真实 `OwnerOperationIdentity`、`OwnerReceiptStore.reserveOperation/start/complete`，成功获得一次 fresh STARTED permit 后才调用真实 `OwnerCommandDispatcher`。Dispatcher 的六条分支仍调用原 `VirtualDisplayOwner`，launch/input/handoff/release、OwnerShell 等业务算法未改。

- 同 ID 同摘要：只返回已有 receipt；无 fresh claim，没有第二次业务执行。冲突：拒绝，不换 ID、不覆写、不重放。
- 成功业务结果写 SUCCEEDED；任何业务失败写保守 UNKNOWN（包括可能只是 preflight 拒绝的情况，避免把错误解释成 NotExecuted）。mutation 失败关闭后续原业务 mutation（launch/input），不能用新 ID 绕过失败；status/snapshot 与 receipt 查询仍可用于观察。该业务 fence 与持久化/未捕获边界异常的 terminal fence 分离：仅前者允许下面说明的首次受控 cleanup。
- reserve/start/final 任一写入失败都不自动重试。后续只保守拒绝/读回 receipt，不能因报错或丢 final 推出“尚未执行”。store 的 poisoned/pending/冲突规则保持原样。
- 首次执行返回原业务 body，并附 receipt；final 存盘失败时不返回原业务成功，而报 `RECEIPT_UNAVAILABLE`。已存在 receipt 的响应为 `ok=true, receiptOnly=true, executed=false, found=true`，其中 `ok` 仅表示查询成功，`executed=false` 仅描述当前请求未执行，绝不是历史操作 NotExecuted 的证据；**必须检查 receipt.state/result，不能当作又执行成功**。
- receipt 只保存身份/摘要/状态以及固定 `{ok:boolean,released:boolean}` 小结果；不写 token、原请求、binding 明文、原异常消息、PNG、文本输入或完整业务 body。首次 snapshot 的大图仍只在即时响应；重复/恢复查询不会重新截图，也不保证可恢复原 PNG。
- 不自动 GC、不扫描并恢复业务、不重置 UNKNOWN、不补发丢失响应。调用方必须持久保存 operationId 与参数，用 receipt 查询恢复，而不是重新生成 ID 执行同一副作用。

### 异常 fence 与首次受控 cleanup

业务失败或者成功响应在写入/flush 时丢失，都不能重放原 mutation，也不能以新 ID 重做 launch/input。此前 coordinator 的通用 `executionBlocked` 和 IPC 的 `mutationUncertain` 都拦截 handoff/release，导致例如空 display 的 launch 缺少目标参数失败后，连首次 release 也被封死。本次仅拆分这一边界，不改变 `VirtualDisplayOwner`、OwnerShell 或原业务算法：

- Coordinator 的业务失败 fence 允许 **当前 live generation** 下首次 handoff 和首次 release 各自取得独立、稳定的 operationId，经原 store 的 reserve → fresh STARTED → backend → final。二者是不同 cleanup，可先 handoff 再 release；不是重试原 mutation，也不意味着 cleanup 必然成功，原 owner 的空源/任务身份/释放验证仍必须通过。
- 每种 cleanup 在取得 STARTED permit 后、调用 backend 前即锁住该种操作。相同 ID/摘要只返回收据；不同 ID 再次 handoff 或再次 release 一律拒绝，即使第一次只是 preflight 失败、仍在 STARTED 或已写 UNKNOWN。即便成功 cleanup 也不清除原 mutation fence。
- IPC 在 UID+token 认证之后，只向上述已 fenced coordinator 放行观察/receipt/handoff/release。成功响应丢失或已开始请求等待放弃仍设置 transport fence，launch/input/initialize 仍被拒绝；cleanup 放行不代表 bypass store、live claim、参数摘要、generation 或认证。
- store poisoned、reserve/start/final 不确定以及未捕获边界异常仍为 terminal fence，不因 cleanup 例外而绕过。历史 generation 收据不提供 live claim；查询命中/未命中都不授予执行。final 不确定的 release 不能换新 ID，也不能自动退出。

这里只修复“健康 store + 当前 live generation + 正确认证 + 从未开始过该种 cleanup”的入口，不提供恢复 UNKNOWN、自动补偿、重新初始化、重开 store 接管或任何高可用机制。

## 认证 receipt-only 查询

查询 generation：

```json
{"v":2,"op":"receipt","token":"<private-token>","generation":"g_1","binding":{"session":"s","task":"t","conversation":"c","run":"r","assistantMessage":"m","runOwner":"o"}}
```

查询 operation：在同一 envelope 增加 `operation`（六业务操作之一）、`operationId`、原 `parameters`。三个字段必须一起提供。binding/参数摘要匹配后调用 store.lookup；找不到返回 `found=false`，不是 NotExecuted 的授权。

此路径不会 initialize/create、reserve/start/complete、截图、读取 display 状态、补偿、release、执行原操作或改变 coordinator 的执行 fence。即使 lookup 报冲突/损坏也不修改健康 generation 的 fence。IPC 的 transport-uncertain 拦截对 receipt 放行，receipt 等待/写响应失败也不会设置 mutationUncertain。仍走同一个串行 handler，不绕过认证。

旧进程已确认退出后，可信宿主可启动一个只完成 bootstrap 的新进程并进行历史查询；它取得 store 锁但不创建 display。历史 STARTED/UNKNOWN 无执行资格。**不能因 store 锁暂时可重开便推断旧 owner 已安全结束**；中央进程/租约身份和退出确认由宿主负责，不能为查询杀死旧 owner 或自动新建 display。

## release 与 lifetime lock

原 owner 的 `isReleased()` 仍是实际显示释放证据（包括释放已验证、但后续 reader cleanup/response 构造失败）。Coordinator 在观察到释放后，先完成 operation final，再完成 generation SUCCEEDED final，两次持久化都成功后才公布 `shouldStop=true`。

IPC 原有 `OwnerReleaseCompletion` 继续等待 dispatch 完成与响应尝试结束，超时/丢连接也不提前退出。Main 的终止 callback 再检查 coordinator，随后 `closeAfterStop()` 释放 lifetime store 锁，才发 STOPPED 并退出。**仅 owner.isReleased 为真不足以绕过 receipt final**。任一 final 丢失：owner 进程保留、拒绝再次执行业务、查询可用、不得自动退出或重放 release。close 失败不宣称成功退出，需宿主人工/显式处理。socket 关闭/断开不关闭 store，不等于 release。

## 静态检查与新增测试（均未执行）

- `OwnerProtocolV2Test`：明确版本/必填 ID、拒绝 v1 与扁平/未知字段、六维绑定形状、参数快照和默认值、真实 IPC 认证 helper 的 UID+token 合取、可信启动 root/应用 UID 契约。
- `OwnerReceiptCoordinatorTest`：真实 POSIX store + 同步 fake backend 检查 bootstrap/lookup 零业务与零 receipt 写、generation STARTED 先于 factory、operation STARTED 先于 dispatch、六操作收据、重复/冲突/UNKNOWN 不执行、失败后新 ID fence、全部旧 generation 状态、真正 close/reopen 的历史 STARTED 查询、create 失败、reserve/start/final 故障、release 双 final 与退出授权、失败查询不影响健康 generation。
- 同一测试包含真实 `OwnerCommandDispatcher → VirtualDisplayOwner.input → InputAccess.run` 链，只有显示读及 command 结果为合成数据，断言 STARTED 已存在和第二次命令不执行；不是只测一个虚构业务 stub。
- cleanup 回归新增：Robolectric display/ImageReader 资源 + 真实 dispatcher/owner，合成空源任务清单，经缺少目标参数的 launch 返回 PROTOCOL/UNKNOWN，首次 release 必须经过 STARTED 并完成 operation/generation 双 final，重复 release 仅查收据、新 ID 拒绝。没有真实设备显示或 shell；这不是设备端验收。
- 真实 store + fake backend 的回包丢失回归使用生产 IPC `MutationFence` 和 UID/token helper：成功 input 回包丢失后拒绝同/新 ID input，经 receipt 查到 SUCCEEDED，认证通过才允许独立 handoff/release；重复/新 ID cleanup 被去重/拒绝。另覆盖业务失败后的 handoff→release、cleanup STARTED/UNKNOWN、cleanup reserve/start/final 故障、外部调用 poison store、历史 generation 和错误 generation。重入检查 STARTED 是确定性的测试观察，不是并行执行架构。
- `OwnerIpcServerFenceTest` 覆盖真实连接所用的状态组件：写回丢失、已开始/未开始等待放弃、观察请求不会设置或清除 fence，只有观察及 cleanup 能通过已置位的 transport fence。离线测试没有创建 socket，不声称验证内核 peer credential 或真实丢包时序。
- 既有 root-only 测试仅更新为新增必需启动参数。原 identity/store 核心及 owner/shell/launch/input/handoff/release 源码无修改。

**本实现代理没有运行 shell、构建、JUnit、GUI、root、网络或真机验证。** 测试源码不等于通过；Robolectric/Java 编译、目标设备 POSIX/fsync/rename、local socket/UID 凭据、真实退出与丢连接时序、中央可信 binding/目录/launcher 私有 pipe、进程回收/撤权仍须主代理独立集成验证。该接线采用保守拒绝换取不重放，final 写失败后可能故意保留无法自动收尾的 owner；不能声称高可用或 exactly-once。

Parent lock-boundary correction: activeLock only snapshots/claims connection identity.
Socket close and SESSION_BUSY response writes occur after leaving the monitor.
Static review 979d8549 identified the old IO-under-lock sites; socket runtime tests remain pending.
