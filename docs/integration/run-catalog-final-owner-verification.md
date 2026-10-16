# RunCatalogTools：最终 Runner owner / generation 接线

## 实现范围与状态

更正授权范围后修改真实 `app/src/main/kotlin/app/daiyu/android/integration/tools/RunCatalogTools.kt`，新增同包 `GenerationCatalogLeaf.kt`，新增专属单测 `RunCatalogGenerationBindingTest.kt`。未修改 Controller、Activity、domain、browser 或 generation 执行叶子。不存在也未创建 `RunCatalogToolsFactory.kt` 或平行 catalog 实现。

**仅完成源码实现与静态核查；未构建、未运行任何单测/设备测试、未触发 CI。**

## 父层准确接线 API

保留 `RunCatalogTools` 原参数及末尾 `capture` lambda。在 `closeResource` 后、`capture` 前增加两个带默认值的可选参数：

```kotlin
authorizationFactory: ((RunExecutionOwner) -> TaskAuthorization)? = null,
generationFactory: ((RunExecutionOwner, SessionCapabilities, TaskAuthorization) -> RunGenerationTools)? = null,
```

保留 Runner 已使用的接口：

```kotlin
override fun bindOwner(owner: RunExecutionOwner)
```

建议 Controller 构造时只保存配置/批准信息，不提前创建 generation 执行叶子：

```kotlin
val tools = RunCatalogTools(
    registry = registry,
    capabilities = capabilities, // 显式包含 generation.paid 才可广告/执行生成工具
    ledger = toolLedger,
    conversationId = conversationId,
    runId = runId,
    browser = browserBinding,
    authorizationFactory = { realOwner ->
        // Controller 负责创建、保存并返回最终同一个对象。
        // runId 必须与 realOwner.runId 相等，sessionId 必须与 capabilities 相等。
        // 仅包含可信批准流程明确授予的精确 effects，不能自动全选付费生成配置。
        finalAuthorizationFor(realOwner)
    },
    generationFactory = { realOwner, frozenCapabilities, finalAuthorization ->
        // RunGenerationTools 没有 open 方法；它的构造器本身不做 I/O。
        // 使用上述真实 owner 和原样传来的 finalAuthorization，不重建/复制授权。
        createGenerationLeaf(realOwner, frozenCapabilities, finalAuthorization)
    },
) { caps, authorization ->
    skillRegistry.captureForRun(caps, authorization)
}
```

上面 `finalAuthorizationFor` / `createGenerationLeaf` 是父层需提供的函数，不是本次新增的产品 API。`generationFactory` 返回实际同包 `RunGenerationTools`，由 Catalog 自动管理注册和清理，不应再次在外部 registry 注册或使用独立 Dispatcher。

- factory 仅在真实 Runner 调用 `bindOwner` 后执行。构造和未绑定的 `definitions` 读取都不会创建 generation；未绑定读取拒绝。
- `authorizationFactory` 输出须满足 sessionId == capabilities.sessionId、taskId == catalog runId == owner.runId。返回同一个引用到 browser、generation、skills、children、devices、中央 bridge/dispatcher。
- 若同时传旧 `authorization` 参数与 factory，factory 必须返回该**同一个对象**，不能用值相等的新对象代替。
- 若包含已批准 terminal binding，terminal 已固定其 authorization；factory 必须返回 terminal 的原始授权对象，不能重新构造或扩权。
- 旧无外部 binding/无 generation 的调用可保持省略授权，仍使用空写权限。generation 必须显式传 `authorization` 或 `authorizationFactory`，不会走隐式空授权退路。
- 同一 owner 重复绑定为幂等；不同 owner 拒绝；绑定进行中的并发绑定明确拒绝，而非等待 factory 或再次 open。

## Runner 时序证据

当前真实类是 `core-domain/.../ChatRunner.kt`，不是任务最初描述的 `Runner.requireToolsDispatch`；没有该方法，不应新增一个假 API。

`ChatRunner.open()` 当前顺序为：`boundary()` → `options.receiptBatchExecutor?.bindOwner(executionOwner)` 成功返回 → checkpoint/admission → 读取 `CatalogTool.definitions` → `model.open(...)`。生产父层必须仍以 **同一个 tools** 同时作为 CatalogTool 和 `RunnerOptions(receiptBatchExecutor = tools)`，不能漏传 receipt executor。

此次新增 generation factory 在 bind 返回之前完成，所有 bound 叶子和最终 authorization 就绪后才发布 owner。随后 lazy `definitions` 会把 generation 的 `registeredTools` 注册到同一个 registry，再创建一个中央 bridge/dispatcher 快照。因此模型首次读取 schema 已能包含配置且启用的生成工具。

## 生命周期与无锁外部调用

绑定准入、重复身份检查、在途创建计数仅在短 `closeLock` 中进行；授权 factory、资源 bind、browser bind、background/generation factory 不持 Catalog 的 `closeLock` 或 `setupLock`。owner 仅在完整绑定后发布，读取 schema 不会得到半绑定状态。

继续复用既有 activeCreations / pendingStops / identity-based stopped / close observers：

- close 先封闭 Catalog dispatch 及 generation admission。
- factory 在途时 close 不等待它，不提前报告 Drained。
- factory 关闭后才返回的 generation/background 仍保留在父层生命周期字段，以便认领一次停止并观察真实排空；不发布 owner/schema。
- generation adapter 的 `close()` 快速撤销 admission，并开启一次 daemon cleanup worker；实际 leaf.close（可能等待 fence/validator）及 bounded awaitClose(30s) 不在控制线程或 Catalog 锁下执行。
- generation close 与父层最终 dispatch receipt 持久化分别完成后，才能发布父层 Drained。未知/超时不自动 retry 或当作成功。若底层 publication validator 永不退出，leaf.close 本身可能一直 pending；控制线程不等待，也不会伪报排空。30s 是 awaitClose 的预算，不是对任意 validator 的强制中止承诺。
- factory 抛异常时原异常返回，父层关闭，未交回句柄的潜在部分资源保守记录 Unknown，不允许重新绑定重试。
- generation factory 若自行构造资源后再抛错，仍须自行保留/清理该未返回的部分资源；Catalog 不可能关闭没有获得的句柄。

## 不自动批准 paid 写

本改动不计算、添加、合并任何 `WriteEffect`，也不把能力开关或 schema 广告当作付费批准。`RunGenerationTools.prepare` 原有精确 grant 检查、上下文 owner/authorization 对象身份检查及执行时 `GenerationRunApproval.review` 均保留。Controller 若提供 `authorizationFactory`，仍负责可信批准流程和最终授权对象，不可仅因为冻结了某个 batch 就自动授予对应 paid effect。

## 专属测试源码（未执行）

`RunCatalogGenerationBindingTest` 新增：

1. 真实 ChatRunner 首次 model.open 前调用两 factory；未绑定读 schema 不创建；重复同 owner 不重新创建；空写授权不会因绑定/能力自动批准生成。
2. 显式精确 grant 下中央 audit 工具与 generation 接收相同 owner/authorization/原始 call；生成 admission 通过后在 owner validator 拒绝，SQL/资产/网络/收费仍为零。
3. 真实 browser binding 与 generation 共享最终 factory authorization，注册于同一 Catalog，browser 调用可通过原有批准，generation 不使用另一个 owner/授权副本。
4. latch 阻塞 generation factory，close 返回且暂不 Drained；late leaf 排空后才通知；未绑定/并发绑定拒绝、无重开。
5. 跨 session/run 授权在 generation factory 前拒绝；先 close 后 bind 不执行 factory。
6. throwing generation factory 保守 Unknown、不可重新创建。
7. 显式 authorization 不接受 factory 返回值相等的副本；generation 不接受隐式缺省授权。
8. 有 generation 的父 Catalog 在另一个工具调用仍在途时不提前报告 Drained。

建议父层在可用环境执行本测试及原 `RunCatalogToolsTest`、`RunCatalogBrowserIntegrationTest`、`RunCatalogToolsTerminalTest`、`RunCatalogShizukuTest`、`RunGenerationToolsTest`。设备侧原 generation store/asset 集成测试仍有价值；本次未把 fake transport 结果称作真实服务验证。
