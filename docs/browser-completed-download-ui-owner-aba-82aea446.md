# Browser completed-download 保存 UI owner ABA 修复交付

## 范围与状态

- 任务提供的候选基线标识：`82aea446`。本实现环境仅使用工作树文件工具，未读取 Git 元数据，因此未独立核验 HEAD / detached 状态。
- 修改限于 `MainActivity.kt` 中 completed-download 的 UI / selection / worker owner，以及 `BrowserDownloadSaveActivityTest.kt`；本文件为独立交付 artifact。
- 未改下载器、传输、controller、manifest、权限、共同清单或更新说明；未执行 shell、GUI、网络、建分支或 push，未读取旧 Eta 或凭据。
- **未执行编译或测试**。下述测试均为新增源码覆盖，不代表运行通过；构建及设备测试交由主代理执行。

## 问题与修复

原 `MainActivity.assertBrowserSaveOwner` 仅比较会话、editor generation、当前 route 与 sidebar 状态；离 chat 再回来、打开再关闭 sidebar 后，这些值可重新相等，旧列表 / picker / worker 因此存在 owner ABA。

现在每次有效 chat/editor visit 使用内存中的 `BrowserDownloadVisit` 对象及只能从有效变为无效的 `AtomicBoolean`：

1. 列表和 `BrowserSaveSelection` 固定持有同一个 visit。列表展示、旧列表按钮和 callback 准入检查该 visit 的身份及有效性。
2. 离 chat、打开 sidebar、所选会话 ID 改变、editor generation 变化（已有 `select` 对 A → A 重入也递增 generation）、destroy 同步撤销 visit，清空列表与 pending。只清理该 browser selection 自己占用的 `pendingBackupRequest`，不误清其他功能的 picker。
3. callback 先消费 selection / request code，再验证 URI 并启动复制；保留已有单调 request code 分配和重建时不恢复 browser selection 的行为。新列表或新 visit 不给旧 callback 分配新 token。
4. worker 只捕获原 visit 的原子取消标志，不再读取当前 activity 的 Compose / navigation / editor 状态。回前台或打开新列表不会把旧标志重新设为有效。
5. `onPause` 仅为仍然匹配的未消费 DocumentsUI selection 保留 visit，让本次文件选择可以正常返回；selection 消费后再进入后台则撤销复制的 visit token。`onDestroy` 始终撤销。

## 保留的安全 / 完成语义

- 仍只接收用户明确返回的 `content:` URI；无自动发布、路径恢复、权限增加或 SAF 到宿主路径转换。
- 未改 `BrowserDownloadCoordinator.saveCompleted` 的双遍大小 / SHA-256 校验、分块 owner 检查、destination open/write/flush/close 错误传播。
- provider 阻塞时只标记 token 失效，不强行关闭或声称 worker 已 drain；`dataBusy` 仍由原 `dataTask` 在实际返回后完成。目标可能为空或部分写入，取消不等于回滚，不把失败报告为成功。
- DocumentsUI 本身导致的 pause 不是离页授权失效；返回 callback 可发生在 `onResume` 之前。

## 新增测试源码

文件：`app/src/androidTest/kotlin/app/daiyu/android/BrowserDownloadSaveActivityTest.kt`。

- `testLeavingChatAndSidebarRoundTripsRevokeListingAndOldCallback`：route / sidebar 来回后清旧列表及 pending，旧结果不得打开目标。
- `testSameConversationReentryRevokesPickerAndListing`：真实 `activity.select(conversation)` 的 A → A 重入撤销旧 picker / 列表。
- `testNewVisitCannotAdoptOldPickerEvenWhileNewPickerIsPending`：新 visit 打开列表不收养旧 picker；旧列表点击被拒；旧 / 重复 callback 不消费新 request code。
- `testDocumentsUiPausePreservesOnlyItsOutstandingSelection`：必要 picker pause 不误废当前选择，callback 在 resume 前仍可保存原字节。
- `testBackgroundCopyCancellationSurvivesResumeAndNewVisitWithoutFalseDrain`：真实 controller 保存工作进入阻塞 provider 后 pause / resume / 新 visit；释放前 `dataBusy` 必须保持，释放后旧 token 失败且不写入原字节，不因新 visit 或旧 callback 复活。

Fixture 使用真实 completed reference、controller dataTask 和 content provider；只新增 provider open 闸门用于确定性控制阻塞点，不使用伪 exporter。原成功字节 / hash、取消及非 content URI、旧 editor / 重复 callback、损坏源和 provider 拒绝测试保留。

## 证据来源与待核验

- `MainActivity.kt`：`BrowserDownloadVisit`、`cancelBrowserDownloadVisit`、`browserDownloadVisitCurrent`、`openBrowserDownloadOutputs`、`pickBrowserDownloadOriginal`、`onPause`、`onDestroy`、browser `onActivityResult` 分支。
- `ChatController.kt`（只读）：`saveBrowserDownload` 将 owner 断言交给 `saveOriginal`；`dataTask` 在 worker 实际返回后才发布 notice 并清 `dataBusy`。
- `browser/download/BrowserDownloadCoordinator.kt`（只读）：`saveCompleted` 在 provider 返回、传输分块及 close 后继续检查 owner。
- 待主代理执行编译与 `BrowserDownloadSaveActivityTest`，尤其核验设备端 ActivityMonitor callback 顺序、私有 setter / delegate 的反射名称及实际 DocumentsUI 生命周期。新增生命周期测试调用真实 override，但未通过外部 GUI 驱动系统 picker；不应据此宣称真实设备行为已经验证。
