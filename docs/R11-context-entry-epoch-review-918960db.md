# 918960db 阻断修复：Main 真实 context 入口能力撤销

## 范围与交付状态

任务指定审查对象为 `6e08e1b` 的真实 context 入口、阻断来源为 `918960db`。本代理只能
读取/编辑隔离工作树 UTF-8 源文件，不能读取 Git 元数据，故这些标识是任务给定来源，
不是本代理独立核验的 HEAD/提交声明。本次只修改：

- `app/src/main/kotlin/app/daiyu/android/MainActivity.kt`
- `app/src/androidTest/kotlin/app/daiyu/android/ContextManagementEntryActivityTest.kt`
- 本专属文档。

**未运行构建、编译或测试，也未进行设备/GUI 验证。** 无 shell、GUI、网络、commit、
分支或 push 操作；未接触历史自有源码 或真实凭据。唯一清单、更新说明、整合与提交由父代理负责。

## 独立静态核实与修复

来源为本工作树 `MainActivity.kt` 的 `sidebarVisible` / `retainedPage` setter、
`chatActions.onContextClick`、`onResume` / `onStop` 及原 visit invalidation 实现。

原入口捕获 `editorGeneration`，但没有专用入口能力：没有打开 visit 时，切 settings
不会推进 editor generation；sidebar 与 onStop 也未撤 context visit。因此仅检查当前
page / Boolean 前台状态不能阻止恢复后旧 click 重新获得权限。

本次新增不持久化的 `contextManagementEntryEpoch`，由 `chatActions()` 捕获；
`onContextClick` 除原 editor/精确会话检查外，同时要求捕获 epoch 当前有效、前台
`voiceForeground` 为真且 `!sidebarVisible`。不通过改变共享 editorGeneration 的语义
解决此问题，不给其它聊天控制扩大 generation fence。

原 visit 撤销 helper 改为 `revokeContextManagementEntry()`：先无条件推进入口 epoch，
**再**检查是否存在 visit；若存在，仍同步调用原
`controller.invalidateContextManagement(visit.adapter)` 后清 Main visit。页面切换、
选择/原 editor invalidation、删除/销毁沿用原先的调用次序；既有“有 visit 才推进
editorGeneration”的行为保留，没有扩展为“任何导航都推进共享 editorGeneration”。

- 离开 chat（以及其它真实 page 变更）：即使没有 visit，也先撤入口能力。
- sidebar 开启：在更改 visible 状态和后续工作前撤入口与已有 visit；关闭时再次换 epoch，
  sidebar 背后捕获的 click 也不能成为关闭后的有效入口。
- `onStop`：第一步同步撤入口与已有 visit，不等待 Compose disposal。
- `onResume`：只推进入口 epoch 触发新回调渲染，不恢复 visit、不重放旧点击、不自动打开。
  停止期间取得的回调不能因前台 Boolean 恢复而获得权限。

成功点击仍打开原 Controller adapter，再进入原 Route。没有改准备 admission、原 token、
cancellation、cancel 或 worker drain / finish 的实现；尤其没有从 Main 提前 finish。
Service / Panel / Controller 的共锁与既有 UNKNOWN、刷新、显式费用确认语义均不重做。

## 新增三条 Activity 回归源码（未运行）

仍使用原 `WiredActivityTestCase`、真 Main chat 回调、原 Controller / adapter / Route 和
production 只读 worker。新增用例为：

1. `testSidebarRevokesEntryWithoutVisitAndClosingDoesNotReviveOldClicks`
   - 无 visit 时捕获旧 click；真实 sidebar 打开期间拒绝，关闭且不重选会话后仍拒绝。
   - sidebar 背后新捕获的 click 同样不能跨关闭复活；新渲染的 click 才能进入。
   - 打开新 visit 后再投递保留的真实 sidebar 动作，同步断言旧 snapshot/visit 清除，
     不等待 Compose disposal。
2. `testSettingsRoundTripWithoutReselectionCannotRestoreOldEntry`
   - 直接走原 page setter，排除 sidebar 与重新 select 的干扰。
   - settings 期间及返回原 chat、未重选时拒绝旧 click；新 click 可以打开。
3. `testStopRevokesUnopenedEntryAndOpenAdapterConsentAndResumeNeedsFreshClick`
   - 先覆盖没有 visit 时 stop/resume：旧入口与停止期间捕获的入口均拒绝。
   - 再覆盖已打开且已确认提示的 visit：onStop 同步清 snapshot / consent；旧 adapter
     refresh/request/confirm、旧入口在停止、恢复及下一 visit 中均不能恢复旧权限。
   - 通过 Instrumentation 的 pause/stop/restart/start/resume 分发生命周期；同步断言放在
     stop 返回后的同一主线程调用中，不能仅依赖后续 Compose disposal 清理。

无 visit 的三条路径还断言共享 editorGeneration 未变化；均比对数据库备份与零聊天
model open。有效点击前仍等待原 dataBusy 本地 reader 结束，不添加重试式入口。

### 确认探针边界

stop 用例为了实际进入原 Panel 的 confirmation 状态，在已完成的 production 只读快照上，
**仅测试代码反射替换 adapter 的展示快照 metadata**（不换 adapter/owner/历史/worker，
不修改生产可见性），供原 `context-compress` 按钮调用真实 `requestCompression()`。
endpoint 使用 `.invalid`，binding / revision 为测试文本；没有保存 profile/压缩配置、
读取/写入凭据或建立摘要 transport。保存的确认回调是 Panel 使用的同一个
`adapter::confirmCompression` 方法。此探针依赖 adapter 私有 `mutable` 字段，父需编译及
设备运行确认，**不是实际摘要 profile、网络请求、收费或取消 drain 的动态验收证据**。

## 明确不属于本叶子的工作

- usage-port 接线由任务指定的 `46521b46` 与父代理另行完成。本次**没有新造 usage/schema，
  没有修改 usage 模块或其 API/存储/计费接线**，也不能声称该边界已验证。
- 不重写 Service / Panel / Controller，不改 preparation 原 token/cancel/drain，不引入
  第二把锁，不提前释放 preparing 槽；慢摘要取消与 drain 回归由父结合既有 Controller
  测试验证，本叶子只提供入口权限与宿主同步撤销的源码修复。
- 未修改唯一清单或更新说明；文档中的修改清单只描述本叶子范围，不替代父维护的唯一清单。

## 父代理后续验证与不确定性

请审核三文件 diff，编译主源与 androidTest，运行完整
`ContextManagementEntryActivityTest` 及相关 ActivityLifecycle / Controller context /
adapter / Panel 回归。需设备验证真实后台/恢复的系统生命周期（本用例为 Instrumentation
显式分发）、sidebar 返回、settings 无重选往返、确认显示与同步撤销；确认私有字段反射与
Compose 生命周期重订阅行为符合运行环境。尚无任何编译/测试通过结论，不可将此文档写成
已运行或已生产验收证据。usage-port 与真实摘要收费路径仍由各自任务和父独立验收。
