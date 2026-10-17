# R11 / P10 / P22 真实上下文入口接线交接

## 本叶子交付及验证边界

任务给定基线为 `a9072ab`；本代理只有隔离工作树 UTF-8 文件工具，**不能读取 Git 元数据、
运行 shell / 构建 / 测试 / GUI、执行 commit、创建分支或 push**。因此这里是实际非空源码
变更和静态复核记录，不是运行通过、生产验收或已提交的声明。请父代理审核 diff，执行实际
验证，并形成非空提交；提交哈希与测试结果由父代理报告。本次未访问旧 Eta、真实密钥，
未发真实聊天或摘要请求，未修改唯一清单或更新说明。

`P10-context-user-production.md` 的 admission / lease / 精确接线合同是本次实现依据；该文
此前关于 Main pending / Controller 尚未实现的段落是组件阶段的历史状态，不代表本增量
之后的接线源码。没有重写 adapter、worker、panel、coordinator、Service 或压缩算法。

## 修改来源与结论（仅静态）

### `app/src/main/kotlin/app/daiyu/android/ChatController.kt`

- 补齐内部确切方法 `openContextManagement(owner)` 和
  `invalidateContextManagement(expected)`。同一个 controller monitor 下以 visit / adapter
  引用身份 + 完整 owner 注册，不以相同会话 ID 复活旧 visit。open 只构造内存对象，已有
  visit / closed / not-ready / recoveryFailed / dataBusy / 原 idle 禁入条件 / 当前会话子任务
  均沿用原门控拒绝。open 不占准备槽、不查持久化 owner、不加载 profile 或 service。
- admission 每次重复 current / `idleAvailable()` / `hasLiveChildTasks(owner.conversationId)`
  检查后，调用原 `reservePreparation()`。lease 持有其原 token 与**同一** cancellation；
  `checkCurrent()` 只检查内存注册、owner、preparation 引用身份、revoked 和原
  `preparationCancelled(token)`，不查库。持久化 owner 继续交由现有 worker readQuery 验证。
- backend 是 worker-only 延迟委托：adapter 获准并进入父现有单线程 executor 后才解析
  lazy 共享 `contextService` 并调用 `ContextManagementWorker.production`。UI open、adapter
  构造、controller monitor 均不会初始化 service / archive store。新增构造参数 factory 仅是
  仪器测试探针接缝，默认严格调用原 production worker，没有替代 Service。
- UI Executor 包装现有 main Handler，继续使用 adapter 的 worker-drain 后二次 current
  检查。没有独立 mutex、队列、Boolean busy 兜底或绕过 Preparation 的 UI Service 调用。
- 精确 invalidate 在 monitor 内清注册并 revoke 该 visit 的 token，monitor 外 cancel 原
  cancellation 与 close adapter。Main 导航同步 close；若 hosted stop / close 来自后台线程，
  注册与取消仍同步完成，adapter.close 由原 Handler 回主线程以保持 UI confinement。
  旧 expected 与新注册不匹配时完全 no-op。
  stop / close 也撤销当前 visit；**不提前 finish**。排队期间也保留原 preparation；只有
  adapter worker finally 或明确 pre-start rejection 才调用 lease.finish，AtomicBoolean 限一次，
  按原 token 调 `finishPreparation`。其异常边界不向 adapter 抛出，异常时保留原槽而非擅自
  清空。存储关闭仍走原 `closeStorageWhenIdle()` 等待 preparing drain 的实现。

### `app/src/main/kotlin/app/daiyu/android/MainActivity.kt`

- `chatActions.onContextClick` 不再 pending。要求原 `editorMatches()`、当前 chat 页面、非空
  selected 与捕获 conversationKey 精确相等、捕获 editorGeneration 相等后，以原 selected.id
  和该代次打开 visit。忙拒绝只 toast，不保留目标页、不建会话、不自动重试。
- 成功保存精确 owner + adapter 页面实例，渲染既有 `ContextManagementRoute`，不改 Panel 的
  显式 endpoint / API model 费用确认。首次加载仍只是 Route 的只读 refresh。
- 页切换 setter、select 入口、selected ID 替换、editorGeneration 变化、当前会话删除入口和
  onDestroy 先同步 invalidate 再改变相应页面 / 选择 / 代次 / 存储。页返回后推进代次，同 ID
  再进入重新创建 adapter。Route 的 onClose 与系统返回最终经过同一个页面 setter 前置
  fence；Route dispose 的再次 close 只是幂等补充，不承担注册失效职责。
- generation 被外部编辑变化推进时清掉旧 visit；若仍在原 route，只显示失效提示和返回，
  不渲染旧历史、不自动打开新会话。activity 不持久化或恢复 context visit。
- 没有改消息、自动发送 pending 聊天、修改压缩设置、自动确认收费、失败重试、聊天模型
  fallback 或新增摘要 transport。手动压缩仍全由原 adapter + worker + Service 路径执行。

## 新增专属测试源码（均未运行）

1. `app/src/androidTest/kotlin/app/daiyu/android/ControllerContextManagementEntryTest.kt`
   - 真 Controller / monitor / executor / Preparation / SQLite 与 production 只读 worker：
     open 不初始化 lazy service；只读加载原会话、消息与草稿保全；相同 ID 新 visit；旧
     invalidate / 旧命令不影响新 visit；缺持久化 owner 保持原精确页失败且不建会话。
   - 在父真实 executor 前排阻塞任务，验证 admission 排队期间已经占原 token，发送和
     新页面拒绝；invalidate 不释放；排队旧任务退出后才释放且零 backend factory 调用。
   - 真 dataTask 的 dataBusy 门控覆盖 open 与 refresh；恢复 idle 不自动重试。
   - 注入不联网阻塞 backend 探针，只借用 production snapshot，验证明确确认前零压缩；
     lease cancellation 与私有 Preparation cancellation 是同对象；stop / close 取消期间
     保持 reservation / SQLite，退出才释放，旧内容清除且零聊天调用。
     **这不是实际摘要网络 / 收费 / 档案发布成功证据。**
2. `app/src/androidTest/kotlin/app/daiyu/android/ContextManagementEntryActivityTest.kt`
   - 真 Main 的 `chat-context` 点击挂载原 Route / production 本地读取；原会话、未发送草稿
     与物理消息保持，系统返回清内容，旧入口点击失效，同 ID 新 visit / 新代次。
   - 切选择、切页面、recreate 与旧动作隔离。使用既有 WiredActivityTestCase 隔离 fixture；
     通过 host 私有 getter 仅取得实际 adapter 以断言内容清除，不提供生产测试快捷入口。
   - Activity 用例等待既有消息动作只读 reader 的 dataBusy 结束再作有效点击，不以重试
     context click 绕过拒绝。

## 父仍须实际验证

- Kotlin 主源与 androidTest 编译；运行上述两套新仪器测试，以及原
  ContextManagementControllerAdapterTest、ContextManagementWorkerTest、ContextManagementPanelTest、
  ControllerContextPreparationTest / ActivityLifecycleTest 等关联回归。
- 设备上真实入口的阅读、归档分页、设置缺失 / profile revision 改变拒绝、明确费用确认、
  慢摘要取消直到 worker drain、切同 ID / 删除 / recreate / stop / close，以及消息 / 档案
  CAS 不变式。新 Controller 探针不覆盖真实 provider transport。
- 新用例没有构造真实活跃子任务 / recoveryFailed / executor rejection；这些门控为源码复用
  证据，不能写成已动态覆盖。原 adapter 的 rejection 测试也不等同父 executor 实测。
- 不声称“取消一定撤回档案”；既有 adapter 的 UNKNOWN / requiresRefresh / 不重试语义原样
  保留，成功后也由用户显式只读刷新。
- 宿主其它写入口仍须遵守原 Preparation 门控；本次没有扩大到重写全体历史写路径。
  既有 Service 全历史 CAS 的保证与外部绕过 reservation 的边界不变。
