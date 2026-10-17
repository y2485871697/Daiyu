# P05 生产宿主接线增量（review-ready，未运行）

## 范围和来源

先读 `docs/P05-balance-production.md`，再静态读取当前 `ChatApplication`（位于 `ChatController.kt`）、`MainActivity.kt`、真实设置 catalog、ChatScreen/TopBar，以及 Balance Runtime、Store、SettingsScreen、Indicator 和相关测试。该文档原先的“入口待父接 / composition scope 手动查询”描述是本次接线前的历史状态，以本文说明的增量为准。

沿用当前工作树已有余额实现，不重写四预设、URL/路径表达式、金额缓存/delta、加密 revision 或 30 秒显式 poller。本工具不能读取 Git 元数据或执行 Git，因此 **未独立核验任务所述 f09e901 / daf20d41 / main 基线提交关系**，不宣称已操作这些提交。

## 实际接线

- `BalanceRuntimeHost.kt`：Application 拥有一个 lazy deferred；默认进程 scope 是 `SupervisorJob + Dispatchers.IO`。首次 persistence/Store/Keystore 初始化由 deferred 的 IO factory 执行。Activity 只是 await；等待者重建取消不会取消进程初始化，也不另开同 namespace Store。构造/读取不启动 HTTP。
- `ChatApplication.balanceHost`：默认 factory 只构造独立 `EncryptedBalancePersistence(applicationContext)`，不读聊天账户、模型 key、数据库或备份。内部 host 属性也是设备测试隔离内存 fixture 的接缝，正常生产路径只有这一实例；Activity 不关闭进程 host。`onTerminate` 仅处理 hosted-runtime 关闭。
- `BalanceForegroundOwner.kt`：Activity 是唯一 poll owner，START 时创建新的前台子 Job/scope 并明确 `runtime.start`，STOP 时 `runtime.stop` **且取消子 scope**；重入前台重新创建 scope。生命周期 observer 对已 STARTED 的挂载也同步，DESTROY/dispose 移除 observer 并取消。
- `MainActivity.kt`：await 进程 host 后安装 Activity lifecycle binding，观察余额 `StateFlow`；设置 route 为 `balance-settings`，设置返回和系统 Back 均回设置页，Bundle 仅保存 route 布尔值（不保存密钥/金额/草稿）。真实 P05 FeatureRoute 和设置页“供应商余额”都指向该 route。初始化未完成时提供返回和本地读取提示。
- `ChatScreenState → DaiyuChatScreen → ChatTopBar` 传完整 `BalanceState`；TopBar 实际调用 `ProviderBalanceIndicator(..., animateChanges = true)`，保留事件/差值动画，不以金额字符串代替。未知状态由 Indicator 自身不占位；失败/刷新沿用成功金额。为窄屏对顶部金额槽设置最大宽度，实际测量仍待设备验证。
- 原 `controller.problem`、备份中、恢复中、文本准备/图片核实状态转入 `statusText`，在聊天顶部下方单独带 live-region 的状态区展示，不再进入余额槽；未删除原有错误反馈。
- `BalanceSettingsScreen.kt`：手动查询不再从 composition scope 调 `manualRefresh()`；页面记住 lifecycle binding（`poll = false`），查询只能由其 STARTED 前台 scope 发起。后台即使页面不 dispose，也会取消对应请求 Job；页面离开 dispose 同样取消；恢复前台可再次查询。composition scope 仅处理本地保存/删除，页面不隐式启动 poller。
- 仍保存独立账户配置，不借聊天 key；默认新配置 `BalanceOption.enabled = false`。只有用户明确启用并保存后，已显式启动的前台 poller 才发请求。查询仍只用已保存配置，脏草稿必须先保存。没有扩数据库、模型 schema、manifest 权限、依赖或共同清单/更新说明。Main/Controller 中其它视频、语音、终端等接线保持原样；`ChatController.kt` 的改动仅在其同文件的 Application 宿主。

## 测试源码（不是运行结果）

- 新 `BalanceProductionOwnershipTest`：进程 factory 只创建一次且离调用线程执行、旧等待者取消后新 Activity 等待同实例；真实 lifecycle binding 的 STOP（不 dispose）取消手动任务并可 START 后再查询；dispose 取消；失败保留金额/成功时间；禁用配置不查询；Main route/state/topbar/独立状态区的静态接线合同。
- 扩展 `BalanceHttpQueryTest`：内存 OkHttp Call.Factory + 真实 BalanceHttpQuery/Runtime/前台 owner；stop 取消实际 `Call`，迟到 callback 不写缓存，恢复 owner 后可查询，HTTP 失败仍保留成功金额与时间。仅内存 Call，不创建 socket/DNS。
- 新 `BalanceProductionActivityTest`：复用现有真实 MainActivity 的 Wired fixture，替换成进程拥有的独立内存余额 host；真实设置入口/Back、Activity recreate 同 runtime/route、真实 topbar 金额和独立 Controller 错误区、系统 task 后台（设置页不 dispose）取消手动查询、回前台可再查及失败保留金额。该 fixture 不读真实余额 namespace 或真实余额密钥，不调用付费接口；这些设备行为尚未执行验证。
- 同步 UI catalog 测试的 P05 route 期望；顺便纳入当前源码已存在的 P24/P25 route 期望，以及当前设置条目总数（原断言 37 已落后于当前源码；加入余额后逐组静态计数为 39 项）。没有删测试、跳过用例或改变其它路由。

## 精确未运行说明及验收边界

本次只有 UTF-8 工作树读写/静态 diff 工具。**没有执行构建、编译、JVM 测试、设备/Compose 测试、CI、Git 提交/push、真实网络或付费接口探测；不能声称编译通过、测试通过或已验证真实服务。** 交付的是非空生产修改和测试源码，供主代理审核/统一验证。

仍需主代理在授权环境验证 Kotlin/Compose/lifecycle API 编译、上述 JVM 与设备测试、系统 task 后台/恢复和重建时序、窄屏/大字体布局、减弱动画和变化事件视觉表现。设备测试中系统后台/回前台时序依赖测试设备，未运行前不能据源码断言设备行为已通过。内存 HTTP Call 测试也不等同 TLS/真实服务集成测试。
