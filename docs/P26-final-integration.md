# P26 错误重连：独立配置、保留预算与冻结接缝

叶子阶段基线 `af18af3`；本次生产接线以父指定的 `a1415b4` 叶子成果为输入，**不重做已有 12 个叶子文件**。本阶段修改 MainActivity、ChatController、导航目录和定向测试，未修改 ConversationDatabase、`docs/重构清单.md`、CI、依赖或既有 Runner／attempt gate。所有新增／调整测试源码均**未编译、未运行**，留父最终统一单测／Lint／APK／双 API 批次；不声称通过。本代理仅有文件工具，无 shell／Git 提交能力，实际非空 commit 由父在现有 detached worktree 集成时创建，不预填 SHA、不建分支、不 push。

## 1. 已读取的冻结证据与保留范围

授权输入位于父提供、仅供本地参考且由父 local exclude 的 `docs/reference-preserved/`，不是生产依赖，不应进入提交。先前 `.agent` 路径不可读的问题已经通过此镜像解决，**没有保留占位档位或目录注入要求**。

以下是**叶子阶段文档保留的证据记录**，不代表本次接线代理重读原镜像或重验哈希。该记录称输入 `provenance.json` 声明源为 `f001fb82eb9941e5673970c656f5c4d5370fbf7f`，叶子阶段读取了以下源码／测试；Git blob／SHA-256 沿用用户／父提供的校验。此次只静态检查当前生产叶子、Main／Controller／导航及相关测试源码；无 shell，未重新计算任何 Git blob／SHA-256：

| 参考文件（省略 model/app/src 或 ui/app/src 前缀） | provenance.git_blob | 本次使用 |
|---|---|---|
| main/.../agent/model/ModelErrorReconnect.kt | `7a2460989b2081f51d750c96b95a74578857ab3d` | 总窗口／单调时间、暂停冻结、继续新 segment、过期取消原 transport、迟到成功拒绝、tick generation、停止终态算法 |
| test/.../data/model/ErrorReconnectPolicyTest.kt | `e7df0a430a330291d6f970bdde41e6852f801cb6` | 五档稳定值、总窗口、缺省 continuous、未知 NONE |
| test/.../agent/model/ModelErrorReconnectPauseTest.kt | `79fd286c41f523ba38af187b7fb89f71ed7d3e23` | 已暂停绑定、快速继续、旧 tick、remaining budget、终态与解绑回归 |
| test/.../agent/model/AgentErrorReconnectTest.kt | `24e2045d970d31c03e756152c26d323be6f9f285` | 从首次错误起计时，等待和 in-flight 都耗预算，期限拒绝迟到成功，停止不启动新请求 |
| test/.../data/datastore/ErrorReconnectSettingsTest.kt、ErrorReconnectPolicyMigrationTest.kt | 见同一 provenance | 独立存储不得改无关设置；未知策略不自动覆盖；明确关与缺省区分 |
| test/.../agent/model/AgentPostToolReconnectTest.kt、ReconnectProviderBoundaryTest.kt | 见同一 provenance | 工具结果／原始 owner 保留边界；不移植其中重新提交请求的恢复机制 |

**五档是总重连窗口，不是每次 delay 或重试次数：**

| 枚举 | 稳定值 | 总窗口 |
|---|---|---|
| NONE | `none` | 0，不重连 |
| WINDOW_30S | `window_30s` | 30000ms |
| WINDOW_1M | `window_1m` | 60000ms |
| WINDOW_5M | `window_5m` | 300000ms |
| CONTINUOUS | `continuous` | null，无期限 |

新独立设置缺省保留 **CONTINUOUS**，显式 NONE 为关闭；**配置缺省不等于启用 Runner 重试**。`ReconnectPolicy()`／`RunnerOptions()` 的 `maxAttempts=0` 完全不变，当前所有生产协议的冻结 adapter 也返回这个默认值。

## 2. 新生产叶子

相对 `app/src/main/kotlin/app/daiyu/android/`：

- `model/reconnect/ReconnectSettings.kt`：五档枚举、总窗口与标签；独立 codec `v1:<稳定值>`。未知 v1 policy 读成 NONE 且不写回；错误类型／envelope／版本显式报错。没有旧应用键猜测或整份 DataStore 迁移。
- `model/reconnect/ReconnectSettingsStore.kt`：独立 namespace `daiyu-model-reconnect`、单键 `reconnect-v1`；仅存偏好，不触及模型、助手、凭据或历史。同步 commit＋完整回读；失败／异常／回读不符封住共享同一 SharedPreferences 对象的 store 实例，直到另一次明确成功保存。进程内 fence 不冒称跨进程持久化，重开以后以磁盘值为准。
- `model/reconnect/ModelErrorReconnect.kt`：迁入上述保留预算算法，用 `ReconnectControl`／`ReconnectTransportScope` 对接原 owner 和原 attempt scope，不复制旧 AgentRunController 或创建第二套 gate。事件改为本地 `ReconnectChanged`，原因只用固定 `ModelFailure`，不传原始供应商 body／secret。小范围宿主适配：attach 后终态也立即拒绝；外部取消移出 attach 短锁；绑定创建失败回收 stop 注册，解绑用 finally 保证两项都注销。保留时钟／暂停／segment／generation／deadline 主算法。
- `model/reconnect/ReconnectCapability.kt`：当前 protocol 预检与精确 session 能力检查，均无 open／POST／resume 副作用。`canResume` 抛异常视为不可用，只返回固定错误，不显示供应商异常文本。
- `model/reconnect/FrozenReconnectAdapter.kt`：run 入口只冻结一次 settings／protocol；`windowMillis` 明确是总窗口，**不能**赋给 Runner.delayMillis 或换算成固定次数。当前 `runnerPolicy()` 永远默认 0。`observeFailures` 可包最外层已组装好的 ModelPort：四种 open 重载、next(outputs,sink)、cancel 各委托原对象一次；网络／超时才回报固定“不支持无重发恢复”消息，并原样重抛原 ModelRequestException，不改变 Runner 分类／checkpoint／终态。它不实现 ResumableModelSession、不加 retry。
- `ui/screens/settings/ReconnectSettingsScreen.kt`：可直接挂载的全局五档页，含 IO 加载／保存回读、错误提示、真实限制。无外部时长目录参数；打开／编辑／保存不创建模型 session。每 visit 独占 `ReconnectSettingsSaveOwner`，导航同步 revoke，dispose 再撤权；异步结果只发布到原页。已经 admission 的保存可结算，撤销不等待磁盘锁。

## 3. 必须保持的能力限制

`core-domain/.../ModelEvent.kt` 要求 `ResumableModelSession.resume` **不重交模型输入／工具输出，且只投递未被 sink 成功确认的事件**。当前 `ResponsesModel.Session` 和 `MessagesModel.Session` 都仅实现 `ModelSession`。

没有经过可核验供应商语义证明的无新付费请求恢复实现；本任务不调用供应商，也不添加 resume。新 POST、重新 ModelPort.open、previous_response_id 或 SSE id 都不能凭名称充当该合同。旧参考的 AgentModelRetry／provider recovery POST／本地工具恢复循环**没有迁入**。

因此：五档配置和保留预算代码真实存在，但当前 Responses／Messages 不执行自动恢复；网络失败明确说明不支持，原错误与已收到正文／工具结果保留。不得对外声称五分钟或 continuous 在这些供应商已能自动恢复。

## 4. 已完成的生产接线与入口（本阶段）

生产路径均相对 `app/src/main/kotlin/app/daiyu/android/`：

### 设置真实导航：MainActivity 与目录

- `ui/screens/settings/SettingsCatalog.kt` 的 `reconnect` entry → `FeatureRoute.RECONNECT_SETTINGS`；`ui/FeatureCatalog.kt` 的 P26 同样使用这个导航 route。它只导航，不隐式发送、恢复或停止运行。
- 用户入口：**设置 → 模型功能 → 错误重连**。`MainActivity.openSettingsRoute` → `openReconnectSettings()` 创建全新 `ReconnectSettingsSaveOwner`，进入 `reconnect-settings`，Compose 以 `key(page)` 挂载 `ReconnectSettingsScreen(controller.reconnectSettings, ..., saveOwner = page)`。`ownerCurrent` 同时检查 Activity 可用、原 token 引用和原 route。
- `retainedPage` setter 在其他导航工作前同步 revoke／清空原 owner；重新打开先撤旧再建新。`onDestroy()` 开头同步 revoke；页面自身 BackHandler／onBack／dispose 保留原有撤权。系统返回也列入回设置 route。撤权不等待磁盘锁；仅已 admission 的保存可结算，排队旧 visit 不得写入，也不向新 visit 发布结果。

### ChatController：冻结与最外层诊断

- Controller 持有单一 `internal val reconnectSettings = ReconnectSettingsStore(application)`；Main 直接复用该对象，不创建第二个 UI store，不向 ModelSettings／助手／历史添加字段。构造内部归一 applicationContext，偏好 snapshot／save 仍由准备 worker／页面 IO 执行。
- `sendPrepared` 先完成精确 `provider` 冻结，紧接着一次 `FrozenReconnectAdapter.freeze(reconnectSettings.snapshot(), provider.protocol)`，早于模型组装、Runner 创建和 SQLite 发送接受。损坏／写入未确认明确拒绝并保留草稿，不回退 provider，不把默认 continuous 当成正常发送的拒绝理由。已冻结 run 的暂停／继续／工具续轮不再读偏好；后续独立发送才重新冻结。
- 原有视频 `messageVideos.wrap`（批准／物理历史／特殊 open）、图片 `messageImages.wrap`、辅助视觉 `ControllerAuxiliaryVision` 等分支照常完整组装为 `preparedModel`；之后才 `frozenReconnect.observeFailures(preparedModel)` 得到最外层 `observedModel`。没有改成裸 ResponsesModel，没有绕开视频、语音入口、上下文准备或授权接线。
- `ChatRunner(observedModel, ...)` 使用 `RunnerOptions(reconnect = frozenReconnect.runnerPolicy(), runtimeStore = 原对象, receiptBatchExecutor = 原 tools, onReasoning = 原回调)`。当前所有档位／普通协议都仍是 **maxAttempts=0**。不添加恢复循环、不调用 requireSupported 拒绝普通发送、不创建恢复 POST、不重交输入／工具输出。
- 固定能力提示只对叶子 adapter 选中的网络／超时失败生效，原异常仍由 Runner 分类／终态／持久化处理。观察闭包保存原 run 的 `RunResources` 引用，只在接受安装处赋值；到 **handler 实际执行时**、在 Controller monitor 内，重新检查 `resources === original`、`!original.fenced`、`!closed`、`runner === original.runner` 和原 assistant message id／run id／conversation id 后才写 `problem`。不以错误发生时的一次检查替代发布检查；旧 callback 不能污染新会话，即使正文 owner 看似相同但 resources 已更换。

### ConversationDatabase 与能力边界

无需 schema 变化。复用现有 request／attempt／checkpoint／usage／工具账本，不增加另一份重放记录，不把过程内预算 timer 当成跨进程恢复授权。生产只完成设置冻结与能力不足诊断；下述预算／恢复组合仍是未来能力，不是当前已启用的自动恢复。

### 未来真正可恢复的 transport（不是本次生产能力）

若以后有实证满足 ResumableModelSession 合同，才可在原 Runner failure 路径组合预算：首次错误创建一次 ModelErrorReconnect；bind 原控制器（注册时立即交付当前 paused／stopped）；每次原 attempt scope attach/detach；请求线程在等待前、恢复前、恢复返回后 check；成功 finish("succeeded")，失败 finish("failed")，停止 finish("stopped")，最后关闭 binding。暂停关旧可见 segment 并使**原** attempt gate 失效，继续新 segment；预算到期只取消原 transport，不取消本地工具 owner。

这些窄接口不是已完成的 Runner 生产接线。**当前普通协议绝不可创建恢复请求来使用预算**。新增总窗口能力也不能假称 Runner 现有最多20次重试已等价于 continuous；不得覆盖既有 ProviderEventDeliveryGate／停止终态算法。此供应商能力／Runner组合缺口是明确保留限制，而不是偷偷回退 POST。

## 5. 定向测试源码与剩余验证

叶子阶段原有 26 个新增 `@Test` 不变，仍未编译／执行：

- `ReconnectSettingsCodecTest`（3）：精确五档／窗口、continuous 缺省与显式 NONE、未知策略 fail-closed、坏 envelope／版本。
- `ReconnectSettingsStoreTest`（8）：五档重开视图、未知不改写、错误类型、失败内存突变与跨实例 fence、commit 异常／回读不符、冻结值、排队旧页撤权、已 admission 保存结算。
- `ReconnectCapabilityTest`（3）：实际 Responses／Messages 逻辑 session 非 resumable／零连接探测，真实合同 true／false，异常固定诊断。
- `FrozenReconnectAdapterTest`（4）：所有档位／协议均默认0；仅网络／超时提示；所有 open 重载／原 outputs／原异常保持；启用偏好也不开新请求／不 resume／不执行迟到工具或写入迟到 usage。
- `ModelErrorReconnectTest`（8）：保留总预算／首次错误起计时、in-flight耗时、暂停冻结／新段／旧tick、deadline取消原transport并拒绝迟到成功、已暂停绑定、停止终态／late attach、快速继续／旧scope detach、观察错误回请求线程。

本阶段新增生产集成测试源码（均**未编译／执行**）：

- `app/src/androidTest/kotlin/app/daiyu/android/ReconnectSettingsActivityTest.kt`：4 个 instrumentation 方法，真实设置目录→Activity→页面；同 Controller store 保存回读不写会话／不开模型；离页再进 token 不复用且旧 token 保存被拒；Activity 重建撤权；绕过页面自身返回、直接共享导航 setter 也同步撤权。
- `app/src/androidTest/kotlin/app/daiyu/android/ControllerReconnectIntegrationTest.kt`：4 个 instrumentation 方法。真实 Controller 准备 lane／SQLite／Runner，注入纯本地合成 model：①五档 × Responses/Messages 共10种情况检视实际 RunnerOptions 仍为0、失败仅一次 open／next、保留部分正文并按原失败终态保存；②在模型组装门禁处更改偏好／provider，再破坏全局记录，验证两种初始偏好的原冻结决定及原 Messages 提示；③坏配置在模型 factory／发送接受前拒绝，保留草稿；④从真实安装的外层 observer 发起本地失败探针，在同一 main task 内注入七类 stale 状态（message／run／conversation／resources／fenced／closed／runner），验证排队 handler 重查；正常原 owner 对照可显示提示。最后一项用反射做定向故障注入，并非真实新供应商请求或实际导航竞态的设备证明。
- `app/src/test/kotlin/app/daiyu/android/ui/FeatureCatalogTest.kt`：精确主 route 断言增加 P26，并对齐基线已存在的 P24／P25 route；未改总清单，未重写既有媒体／工具行为。

本阶段生产改动仅 `MainActivity.kt`、`ChatController.kt`、`ui/FeatureCatalog.kt`、`ui/screens/settings/SettingsCatalog.kt`；加上上述 3 个测试路径和本文档，共8个路径。没有修改12个既有 P26 叶子。没有访问网页、调用供应商、读真实凭据或操作外部目录；测试源码里的密钥仅是测试自建的 synthetic fixture。

既有 `ChatRunnerAttemptGateTest`／`RunnerControlTest` 的完整 gate／停止／工具回归没有重写。父仍需在现有 detached worktree 创建实际非空 commit，统一执行完整 JVM／Lint／APK／设备与双 API 回归；本代理没有可运行构建／Git 的工具，不能提供实际提交 SHA 或声称通过。配置页和预算源码存在不替代真断网、实际 pause／stop、视频／语音／上下文全链路回归或供应商无重发恢复验收。当前 Responses／Messages 自动恢复能力仍明确不支持。
