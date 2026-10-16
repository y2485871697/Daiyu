# P25 辅助视觉：待接线的生产 host 叶子

## 范围与入口

新增 `app/src/main/kotlin/app/daiyu/android/ControllerAuxiliaryVision.kt`。
这是一个 **每个 parent run 一个实例** 的内部 `ModelPort` 装饰器，不是已上线的 UI/Controller 接线。
复用现有 `AuxiliaryModelSelection`、`AuxiliaryVisionBridge`、`AuxiliaryVisionModelAdapter`，不复制协议解析器。
未修改 ChatController、ControllerImageSending、ImageChatHost、Main、ModelSettings 或 auxiliary 三个实现文件。

父 host 的最窄接入面：

```kotlin
val port = ControllerAuxiliaryVision.create(
    main = frozenMainPort,
    selection = frozenAuxiliarySelection,
    frozenCandidates = trustedFrozenRegistryRecords,
    resolver = messageImageResolver,
    authorizeHistory = ::authorizeOriginalPersistedRows,
)
// 将 port 交给本 run 的 runner；普通/ReadOnlyTool/ToolDefinition 三个 open 重载均可。
// 停止按钮、生命周期终止及 run 结束均须接到该实例：
port.cancel() // 或 finally 中 port.close()
```

示例中的 registry/授权函数是**父 host 必须提供的依赖**，不是本叶子虚构的配置来源。
`connect`/`encoder` 参数仅提供无网络测试接缝；生产默认走已合入的真实 adapter/transport。

## 可信配置与无 fallback

- 父 host 必须从真实 registry 捕获完整、可信、不可变的候选记录；providerId/modelId 是 registry ID，不是显示名或 API model 字符串。
- 每个候选的 endpoint、protocol、API model、credential、enabled 状态及已验证图片能力必须来自**同一精确记录**。
- 禁止把当前唯一的主模型快照包装成“辅助目录”，也禁止靠名称猜测支持图片。
- 本叶子只处理显式启用的辅助路由。父 host 如需普通未启用路径，应在显式 disabled 分支独立决定；不得用 catch 此工厂/open 的错误来回退到主模型直传图片或丢弃图片。
- 工厂立即且仅一次 resolve；缺失、重复、禁用、能力未知/false 等均失败，adapter 还保留其自身协议/custom-body 检查。之后修改 caller 的候选列表不会改变 binding。
- 不读取 settings、不选默认候选、不修改凭据，也不引入新的配置持久化。

## 原始持久行授权契约

`authorizeHistory: (List<Message>) -> List<AuthorizedVisionObservation>` 在**每一次有效 open** 调用一次，包括纯文本与描述缓存命中。

1. 回调得到 detached/unmodifiable、**未展开**的原始行快照，包括完整 `historySegments`；不能先展开再把合成行误当持久行授权。
2. 父 host 在一个批次中查验原始行、所属 conversation/run、持久 owner/segment 关系及资产访问授权。应以可信 DB/已冻结授权上下文为依据，不以引用自带 owner、tool JSON、路径或当前选中会话为依据。
3. 全部授权成功后，返回与 `MessageHistory.expand(rawRows)` 中图像顺序一致的授权列表。可在回调中为了排序调用 `MessageHistory.expand`，但应先核对原始持久行。没有图片时返回空列表。
4. 每个 observationId 来自可信持久观察身份；`sourceRunId` 来自**原图所在的已授权持久行/segment owner run**，不是当前 parent run。对于旧历史，两者可以不同。
5. 回调拒绝或部分拒绝应抛错，不能返回“剩余可用图片”。叶子在 IO 前验证列表完整性、顺序及 run/conversation/message/image 身份，再调用 bridge.prepare。

授权在 bridge 的任何图片读取、辅助 HTTP、描述缓存访问和 main.open 之前完成。
授权撤销时，缓存不能让该 run 继续通过；首次失败会封闭实例，不会在本实例再次调用授权/HTTP。
本叶子验证回调输出的一致性，**不能替代父 host 的 DB 授权**；相同的伪造输入和伪造授权不能靠纯本地比较鉴别。

## 请求与生命周期

- 三个 `open` 重载分别调用 main 对应重载，保留全部 tool/definition 参数；辅助模型仍使用 adapter 的空工具列表。
- 传给 main 的只是 bridge 生成的文本投影。原始 `Message`、原图块、segment、DB、资产及原始 history 不写回、不改动。
- 已冻结 image-plan 的第四个 `open` 不在本叶子接口范围内，继承 ModelPort 的 fail-closed 默认；父 host 应传原始持久行而非预先投影/预冻结输入。
- 正常 tool continuation 的 `next(outputs, sink)` 保留 outputs/events；本叶子不暴露 resume、不重连、不自动重发任何请求或工具输出。
- 授权、prepare、main.open、next/sink 任一操作失败后，本实例不可继续 open/next，并尽力取消辅助及已登记主会话。不能把重新创建实例当自动 HTTP 重试策略。
- 状态锁不包住授权、prepare、main.open、next 或外部 cancel 调用。另一个线程可在 main.open 阻塞期间设置 sticky cancel；晚到的 session 会被取消，不能用于后续 next。
- cancel/close 是**请求停止，不是 drain/join**。父 host 仍须等待其 worker/runner 的真实完成边界，不能因 cancel 返回便宣称所有副作用已终止、释放 run 槽位或删除状态。外部 transport 的 cancel 应为非阻塞停止请求。
- 正常结束也必须 close。取消只影响这一实例，不影响其他 run。

## 专属测试与验证状态

`app/src/test/kotlin/app/daiyu/android/ControllerAuxiliaryVisionTest.kt` 使用假 HttpsURLConnection、假主 ModelPort、假图片字节与 inspector；不访问网络，不使用 Android 图片解码器。
测试覆盖：

- 精确 binding 与候选列表冻结；失败无 main fallback；辅助请求独立 API model/credential/endpoint、无工具。
- 三种 open 分派、工具定义/工具输出保留、主端文本投影和原始图像不变。
- 原始 owner/segment 批量授权先于展开/IO；完整授权数量、顺序与原图 sourceRunId 校验。
- 缓存仍每次授权；授权拒绝/撤销无 HTTP 或主端泄漏。
- HTTP 错误/不完整流、主端 next/sink 失败后不自动重试。
- 阻塞 main.open 的取消不等于 drain，晚 session 取消且 run 隔离。
- 辅助阻塞 read 的取消会透传到 adapter 的真实会话，disconnect 后不打开 main。

本实现代理按任务约束**未运行构建或测试**；以上为测试源码覆盖，不是已执行通过的声明。
编译、执行测试、最终集成及父 host 的真实授权/目录接线由主代理负责。
