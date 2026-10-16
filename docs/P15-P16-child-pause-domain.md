# P15/P16：保留子任务的可恢复暂停/继续（domain 接口合同）

本次仅接入 `core-domain/.../delegation`。继续复用 `RetainedChildTasks` 的任务保留、默认 64 个 live reservation、共享模型池，以及 `ChildModelBatch` 的 worker/transport 清理 pin。不新增调度器、清理队列、UI、追加/替换输入或重派请求。

## 宿主接口

```kotlin
fun RetainedChildTasks.pause(
    binding: ChildTaskBinding,
    owner: ChildRunOwner
): ChildTaskControlReceipt

fun RetainedChildTasks.resume(
    binding: ChildTaskBinding,
    owner: ChildRunOwner
): ChildTaskControlReceipt

// RetainedChildTask 新增字段；原有 record/stopRequested/settled/journalUnconfirmed 不变。
val RetainedChildTask.pauseState: ChildTaskPauseState
```

`ChildTaskPauseState` 为 `ACTIVE / PENDING / PAUSED`，只描述当前进程内的控制状态，不替代 `record.status` 或 `settled`。

| 回执 | 精确含义 |
| --- | --- |
| `PENDING` | 接受暂停意图，但 worker 尚未确认处于可恢复边界。重复 pause 保持 pending；不能显示成已暂停。 |
| `PAUSED` | 已在安全边界停住，未开始下一次请求/继续读取；重复 pause 幂等。 |
| `RESUMED` | 解除暂停或撤回尚未到达边界的意图。ACTIVE 上 resume 也幂等返回此值。它不是提供商成功、网络已开始或任务已完成的证明。 |
| `STALE` | 完整 child identity 不匹配、跨 parent binding、任务已停止/终结/离开 live 集合，或 supervisor 已关闭；不改变目标任务。 |

接口同步返回，不等待网络、不等待到达边界。宿主通过现有 `onChanged` 与 `snapshots(binding)` 观察 `PENDING -> PAUSED`。回调在产生变化的线程上调用，仅是通知，不是存储回执；宿主自行切换 UI 线程并重新读取快照。

### 授权和 identity

- `ChildTaskBinding` 仍是可信宿主创建/保留的 capability。必须使用原 `bind` 返回的 handle，不提供按 conversation/current selection 全局查找后控制的捷径。
- 使用 admission 中返回的完整 `ChildRunOwner`；conversation、parent run、group、generation、child run、assistant message 六项全部匹配，且该 task 必须属于同一 binding。
- 另一个 supervisor 的 binding 与既有 stop/submit 一样抛出 `IllegalArgumentException("foreign_child_binding")`，不返回伪造成功。
- 不新增模型/工具权限；仍使用原冻结模型选择、父 session/authorization 的 identity 校验、`ChildRunScope` 权限收窄以及禁用子工具的路径。pause/resume 不是面向模型开放的工具，也不是绕过宿主权限校验的公开路由。
- `finishParent(binding)` 仅封住新 admission。已接受子任务仍保留原 capability，可以完成或由可信宿主显式 pause/resume/stop；父完成本身不触发暂停、继续或取消。

## 两种安全边界

1. **初次请求前**：在原 `ModelPort.open` 之前停住。工厂/`store.started` 可能阻塞，因此在这些回调之后、实际 open 之前再次检查暂停意图。暂停时没有发起新的 provider 请求。
2. **同一 session 的可恢复继续读取前**：原 `next`（或先前的 `resume`）已抛出 `ModelRequestException(NETWORK/TIMEOUT)`；真实 transport 实现 `ResumableModelSession` 且 `canResume == true`；事件消费者没有失败；仍有暂停意图。先用既有 `ProviderEventDeliveryGate` 排空该次已进入的事件消费者并隔离迟到回调，再确认 PAUSED。继续后仅调用**原 session 对象**的 `resume(sink)`。

这不是主动中断 streaming：

- `next` 阻塞时 pause 只返回 PENDING，**不调用 cancel**，不打断读线程，不从新连接重发 POST。
- 读成功终结时直接完成任务，不在已完成请求之后制造 PAUSED。
- 非可恢复 transport、协议/权限等非网络失败、消费者失败，均沿原路径终结为未确认/未执行，不伪造可恢复暂停。
- 没有暂停意图时，网络失败也不会因为此功能而自动重试。撤回 pending 早于边界确认时，同样不启用自动重连。
- 继续读取不重复 `open`、`next(outputs)`、工具 outputs 或原始 prompt。已确认事件/文本计数保留，旧 attempt 的迟到事件被隔离。提供商的 `canResume` 必须遵守现有“不重发请求，只投递尚未确认事件”的合同；domain 不替不可靠 transport 伪造能力。
- resume 回执后再次 pause，可能先显示 PENDING，直到下一个真实边界；不保证任意 provider 都会产生第二个可恢复边界。

## 超时、Stop 和资源

仅 **PAUSED** 的实际等待时长从现有执行超时中扣除；PENDING 仍受原排队/网络超时约束。暂停不扩充超时配置上限，也不为 pending 挂起一个失去约束的网络请求。

暂停保留原 worker、原 session、模型池 permit、父 binding resources 和 live reservation；不是 settled，也不能据此回收一个容量槽。既有默认容量 64 与模型池限额不变，暂停可以占住整个池，这是有意的保守语义。

`stop(binding, owner)`、`revoke(binding)`、supervisor `close()` 仍是不可恢复终止：清除暂停意图并唤醒边界等待，随后走原 `ChatRunner.stop` / 异步 transport cancel / `closeWhenIdle` 清理链。之后 pause/resume 返回 STALE。**停止请求不等于清理完成**；必须继续等待真实 worker 和 transport cleanup 同时退出，才允许 `settled`、释放 binding 或复用容量。

不要将父 `finishParent` 替换成 supervisor `close()`：后者本来就是全体终止，不是“父完成”。

## 持久化和重启

本次不增加 `ChildTaskStatus` 枚举，也不改变 `ChildTaskStore`、SQLite schema/version 或现有存储适配。PAUSED/PENDING 仅附加在当前进程的 `RetainedChildTask` 快照上；durable row 仍可能是 QUEUED/RUNNING。不要从 durable RUNNING 推断它仍有活 session，亦不要从 UI 快照重建控制 capability。

进程退出会丢失 session 和 pause control。已有重启恢复规则仍把未确认运行视为中断/UNKNOWN/NOT_EXECUTED，**不能自动 open、重发 POST、恢复 pause 或重派 prompt**。若父后续需要持久化暂停展示/控制状态，SQLite 的读写、旧值兼容与终止恢复必须由父单独接入；这份 domain 功能不宣称已完成该工作。

## 新增源码证据（未执行）

`RetainedChildTasksPauseTest` 覆盖：

- 阻塞 next 时 PENDING，而非 PAUSED；不取消 transport。
- 安全失败后 PAUSED，使用同一 session 恢复；open/next 各一次，迟到旧事件不会进入输出。
- 初次 open 前暂停、PAUSED 不消耗超时、父封口不取消/释放/解除暂停。
- Stop 唤醒 pause 等待，真实 transport cleanup 阻塞时仍保留资源及容量。
- Stop 后 next worker 仍阻塞时不能提前 settled。
- 六个 child identity 字段、跨 owner binding、外部 supervisor 拒绝；撤回 pending 不发网络。
- 正常终结不伪装暂停、无暂停意图不自动恢复、不可恢复 transport 不重发。

本次按任务约束没有编译或运行测试。上列为提交的单测覆盖意图，不是通过结果；构建、宿主 UI/权限路由和真实 provider/SQLite 接入验证留给父代理。
