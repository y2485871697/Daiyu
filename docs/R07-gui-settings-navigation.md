# R07 GUI 设置导航补线（不代表 GUI 执行链验收）

## 本次范围与证据

任务提供的基线为 `e54c3fae`；本实现仅通过隔离工作树文件工具操作，未调用 shell/Git、未建分支、未 push、未操作设备、未访问旧实现或凭据。未修改共同功能清单或更新说明。

静态审阅来源：

- `ui/FeatureCatalog.kt`、`ui/FeatureDetails.kt`：R07 目录槽及详情的显式打开动作。
- `ui/screens/settings/SettingsCatalog.kt`、`SettingsHomeScreen.kt`：权限分组与搜索结果共用目录路由和说明。
- `MainActivity.kt`：路由分派、实际 Compose 页面挂载、聊天页现有系统设置按钮。
- `ui/screens/settings/GuiSettingsPanel.kt`、`gui/GuiSystemSettings.kt`：既有服务状态、系统页面、用户显式 rebind 与拒绝结果。
- `gui/GuiConfirmationOverlay.kt`：既有捕获前呈现顺序；本次只修改两处文案。

## 行为

1. 新增 `FeatureRoute.GUI_SETTINGS`，实际设置的 `accessibility` / `accessibility_keep` 均连到既有面板；R07 目录与详情仅更新路由元数据。旧 FeatureHubDialog 未挂载，不算真实第三入口。
2. Main 挂载 `GuiSettingsPanel(session = null)`。进入页面只查询服务状态，不准备 run、不读取 UI 树、不截图、不创建模型屏幕授权。无任务时不呈现读取、截图或冻结授权按钮。
3. 原“强制保持无障碍”改成“无障碍服务状态与重新绑定”，控制类型由 TOGGLE 改成 PAGE。只允许用户在面板显式请求系统重绑；系统可以拒绝，不保证连接或存活。无静默 root 保活、无新权限、无权限自动点击。
4. 系统无障碍设置仍由用户点击打开。面板已有 `notice` 接收失败；Main 的现有直接打开按钮现也对 Rejected / Unknown 显示 Toast，不再丢弃失败结果。
5. 面板及真实目标浮层均明确披露：读取的是整个有界 UI 树，不只是选中节点；确认后整个有界观察和完整输入将发送给当前模型，可能进入任务或会话记录。浮层首次读取按钮前及最终观察捕获前呈现此说明。
6. 未新增或改变截图、读取、动作执行、确认交付、模型注入或 drain 链。浮层修改只有字符串：最终观察之后没有新增视图修改。`onPause` / `onStop` 完全未改，保留切到外部目标应用的既有 GUI 生命周期。

## MainActivity 父代理合并接缝

Main 只有以下三块最小增量；整合其他父任务时请按锚点合并，不整体覆盖大文件：

```diff
@@ retainedPage 的 Compose when；browser-settings 后、skills 前
+ "gui-settings" -> app.daiyu.android.ui.screens.settings.GuiSettingsPanel(
+     onBack = { if (activityUsable() && retainedPage == "gui-settings") retainedPage = "settings" },
+     session = null,
+     ownerCurrent = { activityUsable() && retainedPage == "gui-settings" },
+ )
@@ gui-settings-entry 的现有 onClick
- if (activityUsable()) GuiSystemSettings.openFromUser(this@MainActivity)
+ if (activityUsable()) {
+     when (val result = GuiSystemSettings.openFromUser(this@MainActivity)) {
+         is GuiResult.Value -> Unit
+         is GuiResult.Rejected -> toast("无法打开系统无障碍设置：${result.code}")
+         is GuiResult.Unknown -> toast("系统设置结果未知，不会自动重试：${result.code}")
+     }
+ }
@@ openSettingsRoute；RECONNECT_SETTINGS 后
+ FeatureRoute.GUI_SETTINGS -> { requireActivityUsable(); retainedPage = "gui-settings" }
```

以上是简写可读 diff，源文件中 GUI 类型使用全限定名。完整 tracked diff 可通过工作树 diff 工具查询。新增 `GuiSettingsActivityTest.kt` 和本专属文档是新文件，整合时也应纳入，不能只取 tracked diff。

另外两个文本接缝在 `GuiConfirmationOverlay.renderPicker` 和 `stage` 的 `label(body(), ...)`。它们在既有 `previewFromUser()` 调用之前，不应移到最终观察之后，也不要为显示披露新增观察/截图。

## 测试与未验证项

新增 `app/src/androidTest/kotlin/app/daiyu/android/GuiSettingsActivityTest.kt`，覆盖计划：

- R07 / 详情 / 两设置项的 route 和 PAGE 类型；权限分组及搜索可到达；
- 无任务进入与刷新不准备 GUI、不调用模型，不变更数据库、聊天配置或草稿；
- 返回、Activity 重建和旧设置点击失效；
- 已有模型运行时打开设置不会 pause、stop 或替换原任务；
- 使用拒绝启动 Activity 的 ContextWrapper 验证系统详情页与回退页均失败时返回显式错误，不启动真实外部页面。

同步更新 `FeatureCatalogTest` 中 R07 route、详情动作及新增一个控制的计数；从 `FeatureHubActivityTest` 的“无动作条目”运行中检查移除已变成导航的 `accessibility_keep`，其新行为由专属测试覆盖。

**本代理未执行构建、JVM 测试或 instrumentation 测试。** 以上为新增测试代码和静态审阅结论，不是通过结果。Main 失败 Toast 的真实显示、系统页往返刷新、系统拒绝/接受 rebind、真实外部目标切换均未做设备验证；不声称端到端完成。

父代理还需整合通用测试的既有基线偏差：当前 `SettingsCatalog` 源码可数得 41 项，而 `FeatureHubActivityTest` 仍断言 38；同文件固定“未接线数 24”也需随全任务目录更新，且 `platform_tools` / `permission_check` 已有回调，不应仍按无动作条目检查。本次未扩大范围重写这些跨功能断言。其他父任务若增加详情控制，还需汇总 `FeatureCatalogTest` 的总控制数（本次仅 +1）。

父审查5bce523b：实际产品已改用设置主页，未挂载旧FeatureHubDialog。本次交付限定两处真实设置/搜索入口，不以R07元数据冒称第三入口，也不为恢复旧Hub新增重复UI。通用目录断言以父集成实际计数更新。
