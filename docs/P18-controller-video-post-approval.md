# P18：Controller 原视频逐 POST 父许可接缝

本改动是待父接线的叶子实现，不是已经启用的产品功能。没有改动
`ChatController`、`MainActivity` 或 `ResponsesModel`，没有新增发送数据库、上传器或网络入口。
`VideoUploadApprovalController` 仍是创建线程受限的 **run 级** 授权控制器；它不直接在模型 worker 上运行，
run 级确认也不能替代下面的每个实际 POST 确认。

## 两层职责

- `ControllerMessageVideos.wrap(..., postApproval = adapter)` 在所有 `open` 重载汇合后，先重新读取/校验完整
  SQLite 历史、真正 freeze，再把 **这一次** `ControllerVideoModelPlan` 注册到 adapter，最后建立原来的
  `ResponsesModel` session。不是只登记 preflight baseline；即便内容相同，每次实际 freeze 的 videos 对象也不同。
- 有原视频时缺少 adapter，实际 open 明确失败。无视频时不新增弹窗。`modelFactory` 测试接缝增加第四参数
  `VideoPostApprovalProvider?`，默认 factory 将同一对象传给 `ResponsesModel`。自定义测试 factory 也必须原样传递。
- `ControllerVideoPostApproval` 是 `VideoPostApprovalProvider`，不是 capability 生成器。它使用真实
  `VideoPostRequest.bindPermitConsumption { consume(exactPending) }`，不是空闭包，也不是预先固定允许。
- 每个 prompt 暴露此次 endpoint/model、完整 wire 顺序的原视频引用、原件总 bytes、实际 POST number、
  最终正文 byte count / SHA-256；不暴露 key、正文或 data URL。工具续轮也要新显示、新明确确认。

## 父接线约定（必须一并实现，不能只传 adapter）

1. 父在 Android **主线程** 完成既有 run 级证据/确认/激活后，创建一次 adapter。adapter 以创建线程作为
   主线程断言，父不得从 worker 创建。每个新 run 必须创建新 adapter；不可恢复/缓存旧 permit。
2. 实现 `ControllerVideoPostApprovalHost` 的小接口：
   - `postToMain`：投递到现有主线程 dispatcher，不能在模型 worker 直接执行 UI 工作。
   - `snapshotOnMain`：在主线程重新核对父的当前 run、执行 owner、route/lifecycle、配置和完整历史授权。
     在这里调用既有 `VideoUploadApprovalController.capabilityFor(run, freshRunRequest)`，失败就返回 null。
     不要把 run 控制器传到 worker。成功后返回 `ControllerVideoPostAuthority`，其 runIdentity 是同一个父 run
     对象身份，runEpoch/configurationEpoch 是单调版本（改动再恢复也必须递增），settings 是完整实际配置。
     history/inputs 来自父权威，不能从传入 request、封面、最后一行或 UI 分页反推。允许的 model-input 投影
     与 wrap 一致：完整 retained segments、去掉初始空 active assistant、仅 active assistant 的状态/error 投影。
   - `showOnMain`：每次实际显示 `ControllerVideoPostPrompt.disclosure` 与 inventory。仅该次弹窗正按钮调用
     它捕获的 `decision(true)`；负按钮/关闭调用 `decision(false)`。不持久化“记住允许”，不以 run 级确认自动回复。
   - `cancelRun`：调用父现有的线程安全 run/session 取消信号，不等待 worker drain，不新建取消线程/发送链。
3. 初始 authority 必须由相同的父权威流程建立，然后将 adapter 传给 `wrap`。preflight baseline 仍只作为
   接受事务和后续历史 witness，不作为实际 model session 的 plan identity。不要手工重复注册 baseline。
4. **同步失效栅栏是必要的安全约束**：在编辑、配置变化/恢复、历史或 sidecar 修改、切换 owner/route、
   run 结束/替换、页面销毁或用户取消之前，先 `adapter.revoke()`，并撤销父 run 授权/取消现有 session。
   DB worker 改权威数据也必须经过父原来的串行接受/失效路径；不能等异步 DB 操作结束才通知 adapter。
   不能只关对话框而保留 gate，也不能依靠下一次轮询才失效。重建配置值不复活旧 epoch/旧 adapter。

这里刻意不提供一个“永远返回 true”的示例 host，也不在叶子中重新实现父的状态机/数据库事务。
父若不能提供上述权威快照和同步失效，不应接通原视频发送。

## 并发与线性化

- worker 登记实际 plan/提出 request；主线程在显示前和正按钮回调时分别读取新 authority。
- 私有 pending 同时绑定原 request 对象、实际 `plan.videos` 身份、完整有序 refs、historyIdentity、postNumber、
  正文 hash/bytes，以及固定父 run/config epoch。一个 plan 只允许一个 session identity，POST 必须从 1 连续增加，
  同时只能有一个 pending。refs 按 expanded history 的发送顺序排列，再保留同一行内原顺序，不按 sidecar 存储顺序。
- 正按钮只产生一个一次性 gate permit。真实请求紧邻 connection factory 前在 worker 消费它。消费使用主线程
  已核实的不可变 authority 与同步撤销 gate，不再阻塞主线程查询，也不在 worker 调用 run 控制器。
- 共享锁只包含内存状态、身份、epoch 的核验/状态转换。DB/authority 读取、UI dispatcher、dialog、resume、
  bindPermitConsumption、取消信号和网络均在锁外。撤销不等待正在进行的 authority 读取。
- 新实际 plan 会退休旧 plan 的 wait/permit，旧回调捕获旧 pending，不会批准或取消新 request。callback
  读取 authority 期间发生退休也一样。coroutine cancellation 使 gate 失效，等待者被唤醒；迟到回调不再绑定。
- 已经消费并连接的 POST 不能被“撤销字节”；父仍须用原有 session.cancel 中断传输。本接缝保证撤销先于
  permit 消费线性化时不连接，且不把未来 POST 预先授权。

## 源码测试与验证状态

新增 `ControllerVideoPostApprovalTest`：真实 request/plan、显式主线程对话框、完整顺序 inventory、正文绑定、
一次性消费、逐 POST/跨 session 防重用、run/config epoch/历史/refs 变化、撤销/陈旧回调、并发 pending、
coroutine 取消、主线程投递/权威读取阻塞时撤销，以及假 HTTPS 上真实 ResponsesModel 的工具续轮和消费前撤销。

新增 Android `ControllerVideoPostApprovalWrapTest`：真实 SQLite / witness / asset freeze，四个 open 重载每次
产生并登记新 videos 身份，不接受 preflight identity，第四 factory 参数原样传 provider，缺少 adapter 明确失败。
原件/inspector/HTTPS 都是合成 fixture，不证明真实解码或远端能力。

本实现阶段只写源码和静态检查，**未执行 build 或 tests**；交由父代理统一最终编译、单测、设备测试与提交。
