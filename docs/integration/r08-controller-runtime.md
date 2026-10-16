# R08 ControllerVirtualDisplayRuntime：真实生产后端适配（尚未接父控制器）

## 范围及证据

基于任务指定的 `f1dbf77` 工作树接口静态审阅；本工具环境不能读取 Git 元数据，未独立核验
HEAD 哈希。本批只新增：

- `app/src/main/kotlin/app/daiyu/android/ControllerVirtualDisplayRuntime.kt`
- `app/src/test/kotlin/app/daiyu/android/ControllerVirtualDisplayRuntimeTest.kt`
- 本文档。

**没有修改或接线** ChatController、Main、RunCatalogTools、helper、公共清单；没有执行构建、
测试、设备命令、root、网络或显示操作。下面的测试项指测试源码，不是通过结论。

采用而不重造的现有实现：

| 源码 | 实际依赖 |
| --- | --- |
| `ControllerVirtualDisplaySession.kt` | Parent.reserve 在授权/审批/工具暴露前；OwnerHandle；Lease；原始批次票据；close 纯撤销；显式物理排空及未初始化关闭入口。 |
| `integration/tools/R08PersistentJournal.kt` | 私有目录校验、文件锁、不可变事件、pending、文件 fsync、同目录 ATOMIC_MOVE、目录 fsync；claim/complete/finalOutcome 的原有原子语义。 |
| `integration/tools/R08VirtualDisplayPlan.kt` | 真实 RunExecutionOwner 与同一 capabilities 对象生成六维 binding；generation 来自 durable reserve；有限 host 计划，不接受模型路径/信封。 |
| `integration/tools/R08VirtualDisplayCatalogLeaf.kt` | 原始中央批次回传、显式 Start/Initialize、journal claim 后才执行；UNKNOWN 不还原执行许可。 |
| `vd/AndroidVirtualDisplayOwnerHost.kt` | 构造不启动；固定安装 APK/root launcher；认证 READY；真实客户端；原 PID、精确 STOPPED、launcher exit=0 的排空组合。 |
| `vd/VirtualDisplayOwnerClient.kt`、`vd/OwnerResponseValidator.kt` | 现有 v2 认证、严格字段/摘要/binding 校验、receipt-only 查询、一次性 shutdown；不替换传输或 owner。 |

## 唯一工厂与父回调

生产入口：

```kotlin
ControllerVirtualDisplayRuntime.create(
    context = application,
    owner = actualRunnerOwner,
    capabilities = sameCapabilities,
    writes = immutableHostPlannedWrites,
    reads = immutableHostPlannedReads,
    parent = actualParentCallbacks,
    retention = retainInExistingAcceptedRunRecord
)
```

返回原 `ControllerVirtualDisplaySession`，不返回新 dispatcher、registry 或 ledger。
`create` 只保存 application、冻结计划、构造 session 并同步调用 `Retention.retain`。
私有路径解析、mkdir、锁、journal 及 root host 都延迟。`Retention` 必须在返回前把 **同一 session**
放进父的**现有已接受运行记录**；不是要求新建一张登记表。它失败时不授权、不启动，不自动重试工厂。

工厂要求已获得 Runner 的真实 owner；不得自行拼造/恢复一个“等价 owner”。内部 Parent.reserve
同时比较 owner/capabilities **对象身份**，只允许一次 reserve。Parent 只提供 UI 发布、组合授权、
停止这一 accepted run 和原始批次反馈；不能提供 `durable=true` 替身或替换 journal。

- `showApproval`：投递父 UI lane，不等待 UI lane；将原始 request 保留给 answerFromUi。
- `combinedAuthorization`：与其他明确批准的 effect 求并集，返回实际中央目录要用的**同一**
  TaskAuthorization；不要给不同 leaf 重造授权对象。
- `stopAcceptedRun`：只停止绑定 owner 对应的已接受运行；不是 reject receipt，不恢复草稿/重发文字。
- `beginBatch` 的最小实现是捕获 `val ticket = leaf.beginBatch(originalWholeBatch)`，返回
  `BatchCompletion { final -> leaf.finishBatch(ticket, final) }`；不得重建票据或过滤为 VD 子批次。

## 私有 durable lease

固定位置：实际 application `noBackupFilesDir.canonicalFile/r08-controller-leases-v1`，
与固定 launcher 的 root owner receipt 目录 **分离**。不暴露路径参数给生产父或模型。
没有轮换、删除、超时回收、自动 repair 或 UNKNOWN 后 reserve 新代 API。

1. 生产进程唯一 semaphore 先取得，再创建/打开 `lease.lock`，取得非阻塞 OS 文件锁。
   semaphore 不是 run registry：它仅避免本进程另开/关闭同一锁 inode 的 descriptor 导致 POSIX
   进程锁意外丢失。锁和 channel 存于被 session 保留的 store，贯穿 lease 生命周期。
2. 专用根及每代目录 0700，记录及锁 0600；校验 owner、类型、非 symlink 祖先。祖先信任来自
   application 私有 Context，与现有 journal 相同，不声称防御恶意同 UID 改写者。
3. 根 entry、锁和父目录先 sync。扫描历史时只接受完整且校验正确的已释放代；任何 active、
   pending、缺失目录/文件、额外 entry、坏 checksum 均拒绝新代，不“跳过坏数据”。
4. 创建随机 generation 的独立目录并 sync 父目录；创建独立 0700 journal 目录并 sync 本目录/
   父目录，实例化真实 `R08PersistentJournal`。固定 claim 记录含版本、generation、六维 binding
   摘要、冻结计划摘要及 checksum，不含 token、模型参数信封、运行明文身份。
5. `claim.pending` 以 CREATE_NEW 创建，先 sync 目录，再完整写入+`force(true)`，同目录
   `ATOMIC_MOVE` 发布 `claim.event`，再 sync 目录。平台不支持任一步就失败，不退回普通 rename。
   只有这些步骤都成功，才返回实际 `DurableLease`；它的 durable 是实际完成协议的结果，
   不是可由父传入的 Boolean。之后每个执行许可仍由原 journal 单独原子 claim。
6. **仅 Session 已确认父/leaf/物理排空后**调用 release。以相同 pending/fsync/atomic publish/
   dir-sync 协议追加同一 claim 的 `released.event`，随后解锁。保留 claim、journal 和所有观察。
   release 在写入前消费一次性许可，任一异常不重写、不再次解锁，不凭 cancel 释放。

reserve 失败前就消耗本 store 尝试；一旦取得进程门闩，失败后也保留门闩/已打开 channel，
父 session 保留原 store。release 失败同样保留当前进程占用，Session 返回 UNKNOWN。
竞争者拿不到门闩也不能重试这个 store。若最早的文件系统调用就失败、尚无可落盘证据，
只能依靠已接受父记录保留；不能宣称不存在的磁盘写已经 durable。

进程崩溃后 OS 锁自然消失，但未释放/不完整磁盘代继续阻挡新 reserve。完整、校验正确的
`released.event` 是**在物理排空之后**写出的不可变最终释放证据：下一进程可以接受它，包括
前一进程在发布后、返回 release 收据前死亡的情况。当前活进程若丢 release 收据仍保持 UNKNOWN
及所有引用/门闩；没有一个“重试释放”API。未完整发布的释放只能保留，不能推测完成。

## 真实 endpoint 与未初始化关闭

工厂授权后才构造真实 `AndroidVirtualDisplayOwnerHost`，构造本身仍不启动。
OwnerAdapter.start 仅被原 leaf 的明确 Start 执行调用，先消耗启动尝试，再调用固定 host.start。
initialize、execute、lookup、lookupGeneration 都调用同一实际 v2 client；无备用 launcher、
无重连/重发、无 shell/kill、无 close 时自动 shutdown。启动失败即使原 host 尚报 NOT_STARTED，
adapter 的物理观察也降为 UNKNOWN，防止把失败尝试错判为从未启动。

`physicalObservation()` 使用原 host.observe，保留实际 Observation 全字段。Session 只有在
精确 STOPPED + launcher exit=0 + READY 的原 PID 已消失，且父/leaf 批次全部排空时释放。
shutdown 的响应、EOF、cancel、runner.stop、release 回复或 PID 单独消失都不够。

当前 v2 `receipt` **没有“本进程 initialized”字段**，不能把一个 `found=false` 或旧 receipt
单独冒充完整进程状态证明。本适配采取保守组合：

- adapter 持有私有的同一认证 READY host，generation/binding 与 reserve 摘要匹配；
- 自始未跨本 adapter 的 initialize 准入（在调用 client 前标记，失败也不撤回）；
- 进入维护先封闭 initialize/业务执行，唯一一次查询只调用 client.lookupGeneration；
- 严格校验的 receipt-only 成功，`executed=false`、`found=false`、无 receipt。

满足全部条件才报告 PROVEN_UNINITIALIZED；任何 found（包括有效历史记录）、身份不符、
已尝试 initialize、查询失败/丢响应都保持 UNKNOWN，不发送 shutdown。现有协议无法单凭
旧回执证明进程未初始化，因此这里故意不支持那种自动回收，不新增 wire 字段或 helper。
`shutdownOnce` 还独立验证相同绑定、维护证明并在调用前消耗许可，调用现有
`client.shutdownUninitialized()`；客户端/服务端再次检查初始化互斥。成功回复也只意味着
控制请求完成，仍需物理观察；UNKNOWN 或异常不重试、不 initialize+release 兜底。

## 父真正接入的位置（未在本批修改）

以下锚点都来自当前源码，而非假定已经完成的接线：

1. **ChatController 的 prepare/accept**：当前 `sendPrepared` 内创建 registry/capabilities/
   RunCatalogTools，随后才有 `database.send` 的事务收据、`RunResources` 和 `owned.startSlice`。
   只能在数据库接受后，Runner 经 bindOwner 提供 actual owner 的授权工厂里调用本 create；
   不得在前置工具构造、草稿发送尝试或 UI 页面打开时 reserve。
2. **RunCatalogTools.authorizationFactory**：`bindOwner` 在 model.open/definitions 前调用它。
   父捕获这个 actual owner，创建并保留 session，调用 `session.authorizationFactory(owner)`，
   再把 `session.registeredTools(owner, sameCapabilities, sameAuthorization)` 注册到**原 registry**，
   在 `initialized()` 的原 `registry.snapshot()` 前完成。必须显式启用 R08 capability 并注入冻结
   host plan；当前 ChatController 的 capabilities/构造参数尚未这样设置。
3. **不要混用两个 leaf 生命周期**：当前 RunCatalogTools 另有 `virtualDisplayFactory`，要求直接
   返回 leaf；本工厂返回的是由 Session 拥有的 session。不要另造 leaf，也不要让两条路径各自
   begin/finish 同一批次。采用 Session 时父必须在原 central batch 边界转发其 beginBatch/finishBatch。
   当前 `RunCatalogTools.CatalogBatch` 针对自身 `virtualDisplay` 的私有钩子并不是这个 session 的
   自动接线，**不能仅填 authorizationFactory 就宣称运行可用**。后续父变更需选择单一桥接路径，
   确保原 whole batch 以及中央最终结果（含 ledger 短路、取消、持久化失败）恰好回传一次。
   缺回传/意外 suspension 留 pending，不伪造空成功结果；不建立第二个 dispatcher/ledger。
4. **ChatController.RunResources / fenceRun / stopRun / cleanupRun**：在现有 accepted lifetime
   内保留同一 session，取消、导航、dispose 只先调用它的 close；父 UI 不能等待文件锁或 owner。
   父真正排空 Runner、中央 dispatch 和最终回传之后，在 worker 调用
   `observeCloseOnWorker(parentDrained=true)`。必要的未初始化清理是单独、显式的
   `shutdownUninitializedOnceOnWorker`，不是 close 的副作用；随后另做物理观察。
   只有 Drained 才从原 retention 移除。UNKNOWN 不能清空 resources、开放下一次 VD ownership、
   替换 host/store，或用现有其他资源的“重试停止”按钮重试 VD 创建/释放/shutdown。
5. 原 UI 对 request 的确认/拒绝调用同一 session.answerFromUi，必须带原 request 对象，
   不按字符串 ID 重建。具体 UI/父回调、完整组合授权与中央批次接线仍是后续父任务，不在本批。

## 专属测试源码与待验证项

`ControllerVirtualDisplayRuntimeTest` 使用临时私有 POSIX 文件系统、实际 R08PersistentJournal、
实际 VirtualDisplayOwnerClient+严格协议校验及脚本传输；没有网络/显示/root：

- durable reserve/文件权限/独占、真实 journal claim 重新打开仅 LOOKUP_ONLY、释放保留历史；
- 每个 reserve 发布故障点保留门闩和字节、不重试，磁盘镜像重启不跳过 active/不完整记录；
- 每个 release 故障点保留当前所有权、消费许可、不重写；损坏记录和 symlink 失败关闭；
- 父先 retention、reserve 先于审批、冻结计划、注册不启动，唯一明确 Start 才启动；
- 原始整批票据/中央最终回传；shutdown 回复不视为排空，父未排空也不释放；
- 拒绝审批不构造 host；reserve 丢收据后 accepted stop、UNKNOWN 及 store 保留；
- 绑定不符零查询；严格 receipt-only 查询、禁止初始化/二次 shutdown；
- 启动/initialize/lookup/shutdown 丢响应不重试、不假排空；有效旧回执仍无未初始化证明。

测试使用独立 semaphore 和 Host 测试缝，不注入假的 durable lease。故障测试结束后删除临时文件
仅是测试清理，不是生产 recovery API。复制磁盘镜像检查的是重新打开语义，不冒充跨进程锁/断电
实验。真实跨进程排他、Android 文件系统目录 fsync/atomic move、ROM/root/SELinux、固定 launcher
和原 PID 三项排空仍需主代理/设备 CI 验证。主代理还需编译并运行专属测试及既有 Session、Journal、
v2 client/validator、uninitialized shutdown、RunCatalog 批次回归；本批没有运行这些命令。
