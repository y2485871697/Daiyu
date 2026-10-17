# P06：消费已导入本地模型目录（父代理接缝）

## 交付范围与状态

本补丁只修改 catalog/store、`ModelSettingsStore` 必要方法、独立聊天 picker/helper 及测试；**未修改 MainActivity、ChatController、ModelSettings data class/codec、目录文件 schema、CI、唯一清单或旧 Eta**。没有重做勾选追加；原 `SavedModelCatalogStore.appendSelected` / `compareAndAppend` 保留。

**Main 仍未接入本地目录聊天选择。** 目前其 `openChatModels` 仍构造“当前模型 + 实时远端”列表，`selectChatModel` 仍调用字符串版 Controller 方法。下面是父代理应完成的接线，不应把本交付描述成 Main 已上线该入口。

未执行编译、单测或设备测试（实现代理无 Shell/构建能力）。没有网络请求、付费请求或全量测试。测试均为新增/调整的源码，待父代理定向验证。

## 已实现端口

- `ModelSettingsStore.localModelCatalogSnapshot()`：只读已有模型数据，不读凭据、不访问网络、不注册目录；缺失/删除返回 null，损坏抛错而非回退默认记录。沿用文件 backend 的 lease，因此可能创建目录/lock sidecar，但不会创建或恢复 provider 文档。不同于现有用于显式迁移/导入准备的 `modelCatalogSnapshot()`。
- `SavedModelCatalogSnapshot.selectionFor(id)`：从该快照创建 `CatalogModelSelection<CatalogModelParameters>`，包含 provider UUID、完整目录 revision、完整所选记录（含 model-local UUID、显示名、requestOptions、工具授权）。没有导入的 ID 返回 null。
- `ModelSettingsStore.selectCatalogModel(expected, ticket)`：工作线程调用；在文件的进程 monitor + OS lease 内检查 provider identity、精确 revision、完整 record 相等，再持有同一 lease 进入 settings lock、校验当前活动配置等于 expected、保存目标参数。旧票据绝不按相同 wire model ID 自动重解析。无变化选择也校验并消耗 revision；失败保存保守地使旧票据失效。
- `SavedModelCatalogSelectionPersistence` 是独立读/消费端口，文件实现提供 `current` / `consumeSelection`。读后无条件 save 不等价于该端口。
- `CatalogModelParameters.applyTo(current, id)` 只继承 **当前** endpoint/protocol/path/key；requestOptions、battery、device-status 完整取自目标本地记录。
- `save` 同步当前模型的本地参数，保持它原有 UUID、显示名；切换前保留原活动模型参数。其它模型保留原编码，不重编码、不删掉。旧字符串 `selectModel` 对已存在本地 ID 也加载本地参数；未导入的远端 ID 使用 detached 默认参数，不再继承原模型 requestOptions/tools。字符串接口无法代表 UI 打开时的 catalog revision，**不能用它接本地 picker**。
- 相同 host slot 的 endpoint/protocol/responsesPath/API key 任一变化，或旧凭据不可用后显式重绑，保存成功后用新 provider UUID 和新当前模型记录替换该 slot 的目录。旧账户的同名 wire ID 不会套用参数。这里是保守隔离，不是多账户归档：旧 slot 的导入列表不会跨账户保留；其它 host slot 完全不动。改 key 即使只是轮换同账户 key 也采用该策略，没有持久化凭据指纹/新 schema。
- 非秘密恢复仍受 credential-rebind gate 约束；需要重绑的恢复不允许旧目录参数成为可执行配置，之后显式保存会轮换目录身份。仅工具开关恢复则同步活动模型的本地参数。

所有 provider 配置修改必须走现有 `configurationChange` 边界，锁顺序固定 **catalog lease → settings lock**。禁止父代理在 settings lock 内开始目录操作，或直接改 preferences 绕过 revision。删除仍使用 `deleteProvider` tombstone；新只读端口不会把它自动复活。

## 可复用 picker

`ui/screens/chat/conversation/LocalCatalogChatModelPicker.kt`：

- `LocalCatalogChatModelPickerSession(snapshot, currentModel)` 保留本次快照，并映射现有 `ChatModelPickerState`；列表仅含本地记录，保留本地显示名。
- `LocalCatalogChatModelPicker(...)` 复用现有 `ChatModelPicker` 的搜索、单选列表、测试标签和样式。`onSelect` 返回 ticket 而不是裸 ID；`onReload` 表示重新读**本地**目录。没有远端获取、导入或保存副作用。
- session、expected active settings 和 Main owner token 必须作为同一批次持有。重载要替换整个 session；不要把旧点击 ID 放到新快照里重新解析。
- 不把远端列表覆盖到 session。远端发现/追加仍在设置页现有导入入口完成；追加使旧 ticket 失效，返回聊天时重载即可。

## MainActivity 的准确异步接缝（待父代理改）

现状可查询符号：

1. 字段 `chatModelPicker` / `chatModelSettings` / `chatModelOwner` / `chatModelCancelled`。
2. 展示位置是现有 `ChatModelPicker(picker, ...)` 块，内部抓取 `val owner = chatModelOwner`。
3. `openChatModels()` 先 `requireActivityUsable()`、`dismissChatModels()`，再分配 owner；现在同步 `settings.load()`，然后自动 `fetchChatModels()`。
4. `dismissChatModels()` 设置 cancellation 并清空 picker/settings/owner。
5. `selectChatModel(id, owner)` 检查 owner、activity、chat page、ready、active==null、!dataBusy、列表包含 ID；现在调用 `selectConfiguredModel(saved, id)`。成功回调先 `imageHost.configurationChanged()`，同 owner/chat 页再 dismiss/refresh；失败只更新错误提示。

建议接线：

- 新增本地 session 字段（不要修改现有 UI state schema），open 时先发布 loading shell，再用 `lifecycleScope.launch { withContext(Dispatchers.IO) { ... } }` 读 `localModelCatalogSnapshot()` 和 `settings.load()`。**这两项都不能在 Compose/Main 线程做文件/Keystore IO。** 即使凭据读取失败也可展示已导入列表，但不可执行切换；告知先修复配置。普通离线且已保存凭据时完全无需远端。
- 读取 null 时不要自动调用迁移 API 复活已删除 provider；显示“到设置导入/保存配置”。确需首次迁移时沿用设置页显式 `modelCatalogSnapshot()` 路径。
- 回到 Main 后再次检查 `activityUsable()`、`retainedPage == "chat"`、owner 仍是同一个、cancellation 未设置；否则丢弃结果。dismiss/重开/页面离开不能被旧异步结果复活。
- 展示 `LocalCatalogChatModelPicker`，enabled 仍受 ready/active/dataBusy（以及现有 admission）限制。刷新只重新读取本地快照；不要自动 `fetchChatModels()`。导入仍去模型设置。
- 点击捕获该 session 的 ticket + expected settings + owner，在主线程检查现有 UI gates 后提交下面的 Controller overload；不在 UI 自己调用 store.save/select。
- 保留原成功回调顺序：持久化成功才 `imageHost.configurationChanged()`；只同 owner/chat 页 dismiss/refresh；失败保留弹窗并提示重新打开/刷新。不能把排队成功当作已保存。捕获的旧票据被拒绝后不要自动重试新 revision。

## ChatController 的准确 dataTask 接缝（待父代理改）

现状：`selectConfiguredModel(expected, model, completed)` 检查 Main looper，调用 `invalidateVideoConfiguration()`，再进入 `dataTask(failureNotice=..., completed=..., work={ settings.selectModel(...); "模型已切换" })`。

父代理可以保留旧方法给现有调用点，新增相同模式的方法（名字自行协调）：

```kotlin
internal fun selectConfiguredCatalogModel(
    expected: ModelSettings,
    ticket: CatalogModelSelection<CatalogModelParameters>,
    completed: (Boolean) -> Unit,
) {
    check(Looper.myLooper() == Looper.getMainLooper())
    invalidateVideoConfiguration()
    dataTask(failureNotice = "模型切换未保存", completed = completed, work = {
        settings.selectCatalogModel(expected, ticket)
        "模型已切换"
    })
}
```

`dataTask` 是当前实际的异步 admission/完成边界：

- `@Synchronized` 检查 `idleAvailable() && !hasLiveChildTasks()`，同步置 `dataBusy=true` 并通知，再 `executor.execute` 跑 work。
- work 成功返回才置 success=true；异常使用统一失败提示，不泄漏 adapter/凭据错误。
- finally 经 handler 回主线程，在 Controller monitor 内确认未 closed、发布 notice、清 dataBusy，再 dispatchChanged / completed(success)。closed 时不会调用 completed。
- executor 拒绝排队会同步清 dataBusy、通知并抛出；Main 仍需保留 guard/catch。
- 不用 `importing=true`；该标志关系会话导入/dataRevision，不是模型选择。

不要把上述逻辑另写成 Main 的 fire-and-forget IO save，否则会绕过运行中任务冻结、后台子任务、video configuration 撤销与 dataBusy。

## 证据与待验证项

源码依据：`SavedModelCatalogStore.kt`（既有 CAS append 协议）；`FileSavedModelCatalogPersistence.kt`（文件 lease、revision、tombstone、原编码保留）；`CatalogModelParameters.kt`；`ModelSettings.kt` 的 load/select/save/restore；`MainActivity.kt` 的 open/fetch/select/dismissChatModels；`ChatController.kt` 的 selectConfiguredModel/dataTask；现有 `ChatModelPicker.kt`。

新增/调整定向测试源码：

- `CatalogModelSelectionTest`：只读离线重开、完整记录篡改、配置/追加/记录修改 revision、删除重建/账户替换、失败及无变化选择不可重放、其它记录不重编码、消费回调全程持有 lease。
- `CatalogModelParametersTest`：当前连接/凭据保留、目标 requestOptions/tools 加载、local UUID/显示名更新保留、同 wire ID 不同 local UUID 拒绝。
- `ModelSettingsCatalogBindingTest`（设备）：本地参数多次切换/保存/重开，保留其它模型、配置/删除拒绝旧选择、换 key 同 wire ID 不套旧账户参数、旧远端接口 detached defaults、只读端口不访问 preferences/凭据。
- `LocalCatalogChatModelPickerSessionTest`：本地显示名/精确票据、列表脱离原可变集合、未知/未导入 ID 不补造记录、空目录无远端回退。

不确定性/限制：未运行这些测试或编译；需要父代理定向验证并接上 Main/Controller 后补 UI/Controller 异步用例。文件目录与 SharedPreferences 不是跨文件事务：保存沿用现有 prefs 补偿机制，目录失败会尝试恢复 prefs；目录 revision 可保守前进，原活动模型的同步可已经完成。进程崩溃/断电跨两种存储的一致性不新增保证，文件 backend 原有“原子替换但无目录 fsync”的限制不变。也没有新增多账户归档或从远端实时获取目录的能力。
