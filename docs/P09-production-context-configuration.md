# P09：生产上下文配置与独立摘要 profile

## 固定接线入口

```kotlin
val preparation = ProductionContextPreparation.create(applicationContext, contextService)
```

入口位于 `app.daiyu.android.context`，是 `internal object`。`create` 可以在 UI 线程调用；它只装配现有 `ContextPreparationCoordinator`、生产 policy resolver 和 `ProviderSummaryModelResolver`，不打开 preferences、不读取密钥、不发起网络。

父 Controller 必须在自己的上下文准备工作线程调用 `freeze` 和 `prepare`。工厂在读取配置前拒绝主线程调用。`freeze` 才读取 v2 压缩配置；仅在已启用且窗口已知时解析所选独立摘要 profile 并解密其凭据。关闭自动压缩或窗口未知时，不能访问摘要 profile（包括其 preferences）。一次 freeze 的 policy、binding 和 ProviderSnapshot 此后不随设置变化。

这里没有另一套压缩边界、token 估算、deadline 或发送事务。父 Controller 仍负责已有 reservation/cancellation、owner/draft/revision 检查与实际发送；详细契约见 `ContextPreparationCoordinator`。此实现不修改 MainActivity、ChatController、ConversationDatabase 或既有摘要 deadline 算法。

## v2 配置：轮数而非消息数

生产保存接口：

```kotlin
ProductionCompressionSettingsStore(context).save(
    ProductionCompressionSettings(
        automatic = true,
        contextWindowTokens = 32000,
        reserveTokens = 4096,
        triggerPercent = 80,
        keepTurns = 4,
        summaryModel = SummaryModelBinding("summary-provider-id", "summary-model-id")
    )
)
```

- 存在 `context-compression` namespace 的单个 `compression-settings-v2` JSON record。
- `version = 2`，`keepTurns` 是保留**完整用户/助手轮次**，范围 1..100。
- 使用现有领域 `CompressionPolicy` 校验与计算；窗口范围、预留及轮数范围均不另行猜测。
- 阈值是 `(window - reserve) * triggerPercent / 100`，不是旧 reference 的全窗口阈值。
- `automatic = false` 不取消已知窗口的发送容量校验。
- 未配置窗口保持 `null`，不查询主模型、不根据模型名称猜窗口。
- 启用且窗口已知但未选择摘要 binding 时明确报 `MISSING`，不悄悄运行无摘要的自动压缩。

旧 `CompressionSettingsStore` 仍表示 v1 消息数（`keepRecentMessages = 0..100`），供现有兼容路径使用。生产 v2 读取器发现 `compression-settings-v1` 时，明确要求重新选择轮数并保存，**包括旧配置关闭、或旧 writer 再次写入导致 v1/v2 并存的情况**。没有消息数除二、0 转 1 或直接当轮数的迁移。用户明确调用 v2 `save` 才原子写入 v2 并删除 v1。旧 store 不能保存带 `keepTurns` 的快照，以免静默丢掉轮数语义。

配置 JSON 类型、字段集合和版本严格校验；失败的 commit 在同一进程为共享 preferences 加门禁，直到明确成功保存。

## 独立摘要 profile：完整保存接口

未实现独立 UI 或路由；父 UI 可直接使用如下完整 API，保存操作必须放到工作线程：

```kotlin
val binding = SummaryModelBinding("summary-provider-id", "summary-model-id")
val profile = SummaryProfileSettings(
    binding = binding,
    provider = ModelSettings(
        endpoint = "https://summary.example.invalid",
        model = "wire-model-name",
        apiKey = userEnteredSummaryKey,
        batteryEnabled = false,
        deviceStatusEnabled = false
    ),
    budgets = SummaryProfileBudgets(
        contextWindowTokens = 32000,
        inputBudgetTokens = 27000,
        maxOutputTokens = 2000,
        reasoningReserveTokens = 1000,
        providerOverheadTokens = 2000,
        deadlineMillis = 30000
    )
)
SummaryProfileStore(context).save(profile)
// 然后显式保存选择该 binding 的 ProductionCompressionSettings。
```

`ModelSettings` 支持现有 Responses/Messages 协议、路径及请求选项。协议切换与损坏请求选项替换仍要求显式授权，可传 `protocolChangeAuthorized` / `requestOptionsReplacementAuthorized`；不要无条件替用户授权。摘要 provider 不允许启用设备工具。`SummaryProviderFactory.validate` 是不创建模型、不联网的摘要专属校验入口，profile 保存、加载（包括 freeze 读取）和请求创建共用同一组规则：customBody 仅允许 `temperature`、`top_p`、`service_tier`；不允许覆盖 `model`、预算、指令、工具或未知字段。temperature 必须为 Responses 的 0..2 / Messages 的 0..1 有限数值，top_p 必须为 0..1；service_tier 继续复用现有协议校验。无效配置在保存前或 freeze 时拒绝，不延迟到实际摘要请求。

`load(binding)` 提供独立 profile 的完整编辑快照（含敏感凭据，不应记录日志）；`configuration(binding)` 提供现有 provider resolver 所需配置。所有数值都必须显式提供：输出上限、推理预留、协议预留、输入预算及总 deadline。校验复用 `SummaryProviderConfiguration`，输入预算必须精确等于窗口减去全部预留；协议预留还必须覆盖现有原生摘要指令。这里不实现第二套模型能力表。

### 存储隔离与失败行为

| 内容 | namespace / 路径 / alias |
|---|---|
| 独立模型 preferences | `context-summary-model-settings` |
| 摘要元数据 preferences | `context-summary-profile` |
| 完整元数据 record | `summary-profile-v1` |
| AndroidKeyStore alias 前缀 | `context-summary-api-key`（每次保存生成独立 owner/UUID alias） |
| 独立 model catalog | `model-catalog/context-summary-model-settings.bin` |

`ModelSettingsStore` 只增加可注入 namespace；现有默认 `model-settings`、`model-api-key` 和 `model-catalog/primary.bin` 不变。非主 namespace 在解密前拒绝其他 alias 前缀，也不读取主模型旧式无后缀 alias。摘要 profile 永远不会调用主模型 store 作 fallback。

摘要元数据保存 opaque binding、预算与 provider 非密钥配置的 SHA-256 identity；不保存明文密钥，不复制请求体到元数据。实际凭据继续由 `ModelSettingsStore` 使用 AndroidKeyStore 加密。

保存先验证完整配置，再 commit `saving` 门禁并回读一致，之后才保存独立模型，最后 commit `ready` 元数据并回读一致。commit 返回 false 或抛异常**不代表没有写入**。任意写入阶段失败（包括 ready 已实际写入后才报错，或回读不一致）都会尽力重新 commit `saving` 并回读；只有 commit 返回 true 且回读匹配才将失败封口视为已确认。封口 commit 失败/抛异常仍会尝试回读，但仅回读匹配不足以确认持久化成功。

单个进程内所有 profile store 的读取/保存使用同一锁，进程门禁按稳定 metadata namespace 共享，不依赖 SharedPreferences 对象或 wrapper 的身份。失败后，同 namespace 的旧实例、新实例及另一个 wrapper 一律不可用；只有下一次明确保存并确认完整成功才清除门禁。已确认写回的 `saving` 记录让不依赖该内存门禁的重开读取也能拒绝不完整保存，避免新模型与旧预算组合。

恢复边界：commit + 回读是平台 API 层的确认，不是独立磁盘取证。若最终 ready 写入后，连失败封口的持久化/回读也无法确认，则保留进程内 `SAVE_UNCONFIRMED`，明确提示重新保存。此时磁盘可能仍是 ready；进程退出会丢失内存门禁，其他进程/下次启动可能接受该完整 ready 记录。这里**不承诺存储完全失败后跨进程永远能识别上次报错**，也不新增通用 journal 或多进程事务协议。恢复方法是在存储正常时重新明确保存；仅重新读取不能清除当前进程门禁。

`SummaryProfileUnavailableException.reason` 区分：

- `MISSING`：没有完整 profile 或未选 binding；
- `INCOMPLETE_SAVE`：存在 saving 门禁，或失败后已确认写回 saving；
- `SAVE_UNCONFIRMED`：保存失败且不可用门禁写回未获 commit + 回读一致确认（进程内保持拒绝）；
- `INVALID_CONFIGURATION`：字段、版本、预算、协议或工具配置无效；
- `BINDING_MISMATCH`：所选 binding 或 provider identity 已变化；
- `CREDENTIAL_UNAVAILABLE`：摘要凭据缺失、解密失败或失效。

这些失败不会改用主模型，不会发起摘要网络请求，也不会把不可用解释为成功压缩。已启用且已选择的不可用 profile 在 freeze 就失败，即便当次历史尚未触发阈值。

## 测试与验证边界

新增 Android instrumentation 测试：

- `ProductionCompressionSettingsTest`：v2 往返、领域阈值、旧格式拒绝/显式保存、严格类型和范围、commit 门禁。
- `SummaryProfileStoreTest`：完整 profile、预算缺失/无效、binding/identity 不匹配、凭据失效、保存中断、最终 commit 实际写 ready 后返回 false/抛异常、最终/初始/封口回读不一致、封口失败、跨 wrapper 进程门禁与明确保存恢复、namespace/alias 隔离。无门禁 namespace 的读取用于模拟丢失内存状态后的存储读取，不作为实际进程重启或物理磁盘耐久性的证据。
- `SummaryProfilePreparationTest`：Responses/Messages customBody 在 save/load/freeze 阶段拒绝；合法保存后 freeze→prepare 使用真实摘要 adapter、合成 SSE 连接成功生成 archive，并验证冻结后不受新 profile 保存影响。
- `ProductionContextPreparationTest`：主线程 create 零读取、freeze 工作线程读取最新配置、关闭/未知窗口不访问摘要 profile、缺失 profile 无主模型 fallback、主线程 freeze 拒绝。

测试使用 fake model load/save；namespace 隔离测试故意提供 foreign alias，并要求它在 keystore 访问之前被拒绝。无真实网络、无真实摘要请求。本实现代理没有运行构建、测试或 keystore 操作；由父任务统一集成后执行 CI。没有新增 UI，父任务负责接线及交互呈现。
