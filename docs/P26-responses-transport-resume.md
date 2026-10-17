# P26：有限的官方 Responses transport-only resume

本文件取代 `P26-final-integration.md` 中“尚无可恢复生产 transport”部分；普通四协议入口仍然精确不支持恢复，协议本身不是授权。本改动不引入第五协议、不实现新 POST retry、不扩大 ProcessRecovery。

## 官方依据（已用只读浏览器核对）

- https://developers.openai.com/api/docs/guides/background ：必须创建时同时 `background=true`、`stream=true`，才可重新读取原后台流；示例为 `GET /v1/responses/resp_123?stream=true&starting_after=42`。后台响应临时保留约十分钟，`store=false` **不等于零保留**；实际服务端到期时间不是本地承诺。
- https://developers.openai.com/api/reference/resources/responses/methods/retrieve ：GET 同一 response_id，`starting_after` 是从其后开始读取的 sequence number。
- https://developers.openai.com/api/reference/resources/responses/streaming-events ：`response.created` 带 response.id 与 sequence_number；各流事件有 sequence_number。

这里没有远程探测、模型试生成或供应商兼容性猜测。只对精确官方 HTTPS `api.openai.com`、无显式端口、无 userinfo/query/fragment、路径 `/v1/responses` 的已冻结配置发放能力 token。兼容网关、自定义路径、协议名、SSE id、previous_response_id、custom body 都不能发放能力。

## 默认与请求边界

- `ReconnectSettings.responsesBackgroundEnabled` 默认 false；旧 v1 设置无条件保持 false。显式开启存 v2，未知 v2 枚举/错误信封拒绝。
- 独立错误重连页提供 opt-in 及约十分钟临时保留披露；不改模型默认的远程保留条件。
- `ResponsesModel.withBackgroundResume` 校验 token 与完整冻结 ModelSettings 相等，保留现有 image/video/session/每次 POST approval 上下文。
- 每个正常获准工具轮次只 POST 一次，最终合并请求强制 `background=true, stream=true, store=false`。后续 transport 恢复只 GET 同 id，带成功确认的 sequence；GET 无 body、无 input/tools/history、无新 POST approval 或新生成。
- 未获得成功 checkpoint 的 handle、404/410/协议错误/consumer failure 都 fail-closed。不调用 open、next、previous_response_id 或新 POST 兜底。
- 普通 session 对外仍仅 ModelSession；显式 opt-in session 才实现 CheckpointedResumableModelSession。该模式拒绝仅有 Unit sink 的调用，避免假 ACK。

## ACK、事务与执行

`ModelEventBatch(events, checkpoint)` 是一个 wire sequence 的全部派生事件。`ModelDeliveryAck.ACCEPTED` 只能由成功的原 checkpoint 事务确认；关闭/过期 gate 返回 REJECTED，不以无操作的 Unit 返回冒充成功。

Runner 将 batch 所有状态先在原事件锁下暂存，原 AtomicRuntimeStore 单事务提交正文、reasoning/协议历史、READY 记录、usage、request attempt 与 `RunCheckpoint.transport`。`response.completed` 的 Usage 和 ProtocolOutput 在**同一终态 batch**中，不能先确认 completed 再补尾事件。失败/unknown commit 不前进游标。显式拒绝恢复上一 ACK decoder 状态；未知 sink/commit 错误封死该 session。

Responses decoder 的文本分片、reasoning、函数调用表、字符计数、handle、ACK sequence 保留跨 GET。snapshot 与 ACK sequence 一起持久化。`RunCheckpointCodec` 已完成：无 transport 仍输出原 v6；有 transport 输出 v7，严格读写 `response_id/sequence/decoder_state`，检查内外 handle/sequence 一致，兼容读取 v1-v6。无需父再补 codec。checkpoint 是审计证据，不是进程恢复许可；ProcessRecovery 仍只终态中断，不重建 session。

工具仅在原请求协议闭合、原 Runner 确认边界后执行；GET 不重复 READY 或执行工具。工具结果的后续正常 POST 是下一工具轮，不是重连；其 POST 仍经原批准路径。GET 沿用 requestId，只更换 attemptId/RESUME，不增加 usageRequests。

## 总预算与控制

`FrozenReconnectAdapter.runnerPolicy()` 仅在明确能力 + 非 NONE 时提供 `TransportReconnectBudgetFactory`；桥接既有 ModelErrorReconnect，不把连续模式换算成 max20。

- 原请求耗时不计入；首次可恢复失败创建一次预算，之后所有等待、GET 在途耗时都计入同一预算。
- 五档 NONE/30s/1m/5m/continuous 语义不变；continuous 无次数上限。
- 每 attempt attach 同一个原 delivery gate + transport scope，detach 后 drain。deadline/pause 非阻塞 expire 原 gate 并 disconnectTransport；不会 cancel 工具 owner。
- pause 只断当前读取，不最终 cancel session；暂停预算冻结，继续新可见 segment，原 session GET。快速继续也不能接受旧 attempt 回调。
- stop 是最终 session.cancel，拒绝后续事件/继续；恢复前、sink/commit 前后、返回后、最终成功前均检查预算，拒绝迟到成功。

## 父代理集中接线：精确 required hooks

本子任务不改 ChatController/Main/ControllerVideoHistory/辅助视觉的控制围栏。

1. 必须在 reasoning/request policy 已冻结后，用实际 **requestProvider**，不要用原 provider：

```kotlin
val reconnect = FrozenReconnectAdapter.freeze(reconnectSettings, requestProvider)
// overload: freeze(ReconnectSettings, ProviderSnapshot): FrozenReconnectAdapter
val background: FrozenResponsesBackground? = reconnect.responsesBackground
val options = RunnerOptions(reconnect = reconnect.runnerPolicy(), /* 原其余参数不变 */)
```

仅旧 `freeze(settings, protocol)` 不启用恢复。这是刻意的 fail-closed，不应以 protocol overload 替代 snapshot overload。

2. 在原 ResponsesModel factory 的产物上配置，不另开模型、不绕 image/video批准：

```kotlin
val configured = background?.let(originalResponsesModel::withBackgroundResume) ?: originalResponsesModel
// withBackgroundResume(FrozenResponsesBackground): ResponsesModel
// 原 open(history, definitions, images/videos) 完整保留。
```

3. ControllerVideoHistory 原 `modelFactory(provider, instructions, capability, approval)` 保留 approval 与 onFrozenPlan/registerFrozenPlan 全部顺序；factory 产物仅通过上述 withBackgroundResume 配置。将其抹掉接口的 object:ModelSession 换为：

```kotlin
fun checkRuntime() {
    if (runtimeCancelled()) { session.cancel(); throw InterruptedException("video_model_cancelled") }
}
return app.daiyu.android.model.reconnect.guardModelSession(session, ::checkRuntime)
// guardModelSession(ModelSession, () -> Unit): ModelSession
```

该 helper 在 admission 和每次事件/batch 投递保留原 checkRuntime，透传 exact session 的 canResume、nextAcknowledged、resumeAcknowledged、disconnectTransport、cancel；不 open、不创建新请求。

4. `reconnect.observeFailures(assembledPort, onUnavailable)` 对已明确许可的 checkpointed session 保留原能力；对普通四协议仍保留原不支持诊断和零重试。包装最外层已组装 port，不传裸 ResponsesModel 绕媒体。

5. **辅助视觉的 GuardedSession 另有 inFlight/cancel-all 围栏，当前该路仍无 resume。不要机械替换或宣称辅助视觉支持；不改变其所有权/取消语义。** 若原辅助视觉通过 Unit sink 调后台模式会明确拒绝；应保持该路使用普通未配置 background 的 model。本次 ACK 接线限定主 ChatRunner；摘要、title、ChildModelBatch 等仍用 Unit sink 的调用路径也不得复用已配置 background 的 model/session，不宣称其支持恢复。

## 改动及验证范围

改动仅 core domain ModelEvent/Runner/runtime/gate、app ResponsesModel/Stream、model/reconnect、独立重连页、RunCheckpointCodec（另行授权）、定向 tests/docs。未改 Controller/Main、backup、ContextService、MessageActionStore、DBhelper、清单或更新说明。

新增定向测试（全部**未运行**；子代理无 shell，构建/集成由父负责）：

- `ResponsesBackgroundResumeTest`：真实内存 HTTPS transport+Runner，POST/GET method/count/URL/body、同 request 不新增 usage、无 handle、404/410、commit unknown、completed 尾事件失败、READY 断线后只执行一次、拒绝回滚 decoder、默认关/精确官方能力/其余协议不支持。
- `ChatRunnerTransportResumeTest`：26 次 GET continuation 不受20次限制，原 request identity、在途期限拒绝终态、暂停同 session 恢复并拒绝旧 callback、最终停止拒绝迟到。
- `TransportCheckpointCodecTest`：v7 原子字段往返、v6不变、严格类型/内外 cursor mismatch、ProcessRecovery 仍中断。
- 需一并跑既有 `ModelErrorReconnectTest`（确定性五档总窗口/在途/暂停冻结/新segment/迟到）、`FrozenReconnectAdapterTest`、`ReconnectCapabilityTest`、Runner 既有 attempt/usage/pause/checkpoint 测试及 Responses decoder/request/media 测试。

尚无构建通过或设备验证证据，不将静态实现描述成测试通过。官方保留时间、服务端模型支持/项目策略及网络可达性仍以真实服务端响应为准；本实现只严格拒绝不满足合同的恢复，不主动试付费生成。
