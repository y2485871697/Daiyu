# P06：聊天模型 picker 仪器测试契约对齐

## 范围与核实来源

本次仅修改 `app/src/androidTest/kotlin/app/daiyu/android/IntegrationSettingsActivityTest.kt` 的前四个聊天 picker 测试及其专用 helper；新增本文档。不改 Main、Controller、清单，也不处理普通模型保存 guard。第五个 `testCatalogImportsCheckedModelsWithoutChangingCurrentDraftOrCredentials` 起的真正远端导入/设置/技能测试，以及原 `CatalogConnection`、`catalogue`、`fetch`、`finished` helper 均保留原文。

实现前直接核对了当前工作树源码，而未依赖旧交付文档中的 Main 状态描述：

- `MainActivity.kt`：`reloadChatModels` 在 IO dispatcher 读取 `localModelCatalogSnapshot()` 与当前配置，不调用远端服务；`selectChatModel(ticket, owner, session)` 同时校验原 owner 和 session。失败提示为“切换未保存，请刷新本地目录后重新选择”。
- `storage/ModelSettings.kt`：`modelCatalogSnapshot()` 是显式注册/迁移入口，`localModelCatalogSnapshot()` 是只读已有目录入口；`selectCatalogModel` 消费完整本地 ticket。
- `ControllerModelSettingsTest.kt`：从真实快照创建 ticket；以 `modelCatalog.configurationChange` 持有目录 lease，验证异步等待与过期拒绝。
- `storage/ModelSettingsCatalogBindingTest.kt`：以 `modelCatalogSnapshot()` 和 `compareAndAppend(providerIdentity, revision, records)` 创建持久本地夹具，并校验 `COMMITTED`。
- `ui/screens/chat/conversation/LocalCatalogChatModelPicker.kt` 及 `src/test/.../LocalCatalogChatModelPickerSessionTest.kt`：session 保留原快照，从中签发 ticket，不以新会话重新解析旧点击。

## 新测试契约

| 测试 | 对齐后的行为 |
| --- | --- |
| 打开、刷新、搜索、选择 | 显式注册夹具后，真实 compare-and-append 写入 first/second；打开与刷新均验证零远端凭据加载、零远端连接；刷新不改目录/配置/DB；选中仅改模型 ID，保留草稿和完整连接配置，零模型打开。 |
| 旧 owner 不能借重开 visit | 在原 visit 捕获真实 ticket、owner、session，调用三参数生产方法；取消后与重开后分别调用同一个旧能力，均不能写配置/关闭新 picker；新 UI 点击仍能正常选择。最终核对草稿、DB、完整配置和零模型打开。 |
| 已取消的本地读取不能复活 picker | 不再伪造挂起 HTTP。后台线程持有真实目录 lease，让本地读取停在 loading；在同一主线程回调内捕获原 children、调用生产 openChatModels 并登记新增 job，在取消并释放 lease 后等待该 job 完成，再断言旧 picker 未复活。随后重新打开并取消仍不改配置/DB/草稿，零远端请求和模型打开。 |
| 配置变更/运行中拒绝 | 外部保存使原 ticket 过期；核对新提示和禁用选择，刷新本地目录后可重新查看最新记录。失败阶段保持草稿、DB、外部配置及零模型打开。真正发送并进入运行后，验证模型选择禁用，非 loading 时允许本地刷新且等待新 session，核对 active owner、配置、运行中 DB 快照及模型打开计数不因 picker 再增加。 |

first/second 的模型本地策略在夹具中被显式设为与当前模型一致，因此完整配置预期可继续使用 `before.copy(model = "second")`；这不是把生产选模逻辑退回字符串 API。所有记录通过真实目录 backend 持久化，未伪造 picker state 或替换 settings store。

远端 guard 的两个计数器在抛错前递增：即使生产吞掉请求异常，也不能把一次误发请求误判为零网络。此 guard 仅用于前四测，不改变后续远端导入夹具。

## 验证边界

- pending 本地读取测试在同一个 `mainThread` 回调中取得原 children、反射调用生产 `openChatModels()`，立即登记新增直接 child 的完成回调，避免 Activity 异步初始化在两次主线程访问间新增 Job 的竞争。loading 断言和取消仍在测试线程经 Compose 完成；首测保留真实按钮入口覆盖，不以 sleep 推测读取完成。
- 未执行编译、JVM 测试、仪器测试或设备操作。本子任务仅具备工作树文件读写工具，构建和运行由主代理负责；静态核对和净 diff 检查不等于执行通过。
- 未读取 Git 元数据，故不独立声称核实任务指定的基线或 detached 状态；未创建分支、提交或 push。

- 父组合修正运行中刷新契约：刷新只读本地目录，等待新的非空 session 后再次核对禁用模型选项、原 active owner、配置和 DB 不变、零额外模型打开及远端访问。未改生产按钮可用条件。
