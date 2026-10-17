# R12 / P38：复用文件入口，补恢复期间的 VIEW 接缝

## 本次范围与来源

按本任务后续收敛要求：复用已接通的 JSON VIEW、四个 SAF 入口和二次确认，不重做文件导出、生成原件保存或媒体逻辑。只补 Controller 尚未 ready 时外部文件请求被丢弃的问题、真实隐式解析回归，以及过期导出文件名/测试断言。未改唯一进度清单和更新说明。

已核本工作树中的直接来源：

- `docs/重构清单.md`：R12 与 P38 的备份/文件关联范围，父代理统一维护进度。
- `app/src/main/AndroidManifest.xml`：MainActivity 已为 singleTop/exported，已有 ACTION_VIEW + DEFAULT + content/application-json 过滤；没有 file/http/wildcard MIME 捕获，本次不重复加过滤器。
- `MainActivity.kt`：`onCreate(state == null)`、`onNewIntent` → `offerOpenedBackup`；四个 SAF 请求码 101–104、单会话导出 105、历史原件保存 106；`confirmBackupImport` 仍通过现有单次 prompt 做第二次确认。
- `ChatController.kt`：`importUserBackup` 仅在明确确认后打开 ContentResolver 流，`use` 关闭；完整包读取上限 112 MiB、文本包 16 MiB，严格 UTF-8 解码，然后进入既有 `NeutralBackupService.importPackage` / `ConversationDatabase.importBackup`。现有拒绝损坏/未知格式的 parser 不在本次修改范围。
- `backup/NeutralBackupService.kt` 与 `storage/ConversationDatabase.kt`：当前导出 envelope version 都是 **2**，兼容导入 version 1–2。`docs/R12-extended-backup-production.md` 记录已集成的完整备份/原件生产链，本次不替换它。
- `BackupActivityTest.kt`：复用原有真实 Activity/SAF monitor、MockContentResolver 私有 provider、Controller/SQLite 与恢复门控夹具。本次不是重新建立另一条导入实现。

这里只能核文件内容；委派材料提及的审查/提交 SHA 及 Git 分支状态须由父代理核验，子代理未执行 Git/Shell/网络。

## 单个内存待处理请求

新增 `DeferredOpenedBackup`，只持有一个经过原有 MIME/读取标志校验的 `content` URI 和本次等待 prompt 的对象身份，不保存 Intent/ClipData、文件内容、流或持久化授权。URI 还必须有 authority，字符串长度最多 8192 字符；超限不进入等待槽。

1. 仅 `ready == false` 且 `problem == null`、无 active、无 dataBusy 时允许进入等待槽。这不是运行中/备份忙碌/恢复失败后的自动重试队列。
2. 等待时显示“等待会话恢复”，明确尚未读取文件。取消按钮/关闭弹窗及“取消打开”正按钮都会撤销等待身份。
3. Controller 原有 observer 触发 `refresh` 后，若 ready 且仍是相同页面/等待 prompt，先消费等待槽，再进入**原有**“打开备份”类型选择和二次确认。这个过程不调用 resolver、parser 或导入服务。
4. 新 Intent（包括无效 VIEW 或其它 action）先撤销旧等待请求；新合法请求最多替换一个 URI，不增长列表。页面导航、problem/active/dataBusy、保存 Activity 状态和销毁也撤销。
5. 不写入 saved state；原有 `state == null` 限制仍保留，Activity 重建不会重放其旧 VIEW。重复 ready 通知不会再次生成选择框。
6. 等待不建立新的系统授权。提供者在等待期间撤销读取权限，最终明确点击导入时仍由原有 ContentResolver/provider 拒绝，不将标志存在当作实际成功。

没有在 ready 回调中自动恢复数据或应用配置，也没有改变原有非秘密配置的独立预览/人工恢复步骤。

## 导出命名

建议文件名去掉会随 codec 升级而过期的版本号：

- `daiyu-text.json`
- `daiyu-complete.json`
- `daiyu-conversation.json`

这只影响 ACTION_CREATE_DOCUMENT 的 `EXTRA_TITLE`，不改变字节/格式、写流、原件保存、媒体或备份服务。BackupActivityTest 的真实导出测试改为断言 envelope version 2，而不是过期的 1。

## 新增/修正回归

均在 `app/src/androidTest/kotlin/app/daiyu/android/BackupActivityTest.kt`：

- `testImplicitViewDuringRecoveryDefersOneUriThenRequiresChoiceAndConfirmation`：无 component 的包限定隐式 Intent 经 PackageManager resolve 后实际启动 Activity；通过现有夹具控制恢复门控并发布 Controller 的真实 observer 通知，验证 ready 前后、选择后均零打开流；只有最终确认才进入现有导入服务。这里是受控恢复状态，不冒充真实慢磁盘/进程恢复故障注入。
- `testManifestOnlyResolvesContentJsonView`：查询 Intent 既无 component 也无 package，验证系统候选含代鱼且只匹配 content/application-json，不捕获 file/http、text/plain 或 octet-stream。
- `testRecoveryDeferredViewBothCancelActionsRevokeWithoutLateReplay`：等待状态两种取消按钮均撤销，后续 ready 不重放。
- `testRecoveryDeferredViewReplacementKeepsOnlyNewestUri`：新 URI 替换旧 URI，最终确认实际导入新文件。
- `testRecoveryDeferredViewInvalidReplacementAndOversizedUriRevoke`：无 grant、file scheme、错 MIME、超长 URI 均不读文件、不恢复旧请求。
- `testRecoveryDeferredViewRevokedProviderStillFailsOnlyAtExplicitImport`：等待期间权限撤销，ready/选择不读文件；最终确认读取失败，无数据 revision 或执行请求。
- `testRecoveryDeferredViewRecreationDropsUriAndDoesNotReplayActivityIntent`：重建后没有等待请求或隐式重放。
- `testRecoveryDeferredViewFailureBusyActiveOrNavigationRevokesVisit`：失败、忙碌、运行和导航使等待失效，后续 ready 不恢复。
- `testConversationExportPickerUsesVersionlessSuggestedName`：通过真实 Main picker 方法/ActivityMonitor 核单会话导出的新建议名称及取消零写入；其它两个名称由既有四 SAF 测试核验。
- 旧的 VIEW 门控回归不再把“无失败的 not-ready”当成必须丢弃的场景；active/busy/recovery 仍拒绝。原有未知格式、权限撤销、四 SAF 取消/失败、二次确认和配置恢复测试继续保留。

## 验证状态和父接线边界

本实现代理只有文件工具权限，**未运行编译、单测、设备/模拟器测试，也未执行 diff-check 命令**。上述是新增回归覆盖意图，不是已通过证据。父代理需编译并执行 BackupActivityTest（包括受控冷启动/重建与真实隐式 resolve），核验设备 API 上的 Activity 生命周期/monitor 时序。

本次未改 Controller 的读取取消/drain、既有 picker 的 owner 协议或格式 parser。不能把本增量当成整个 R12、未知格式自动探测/迁移、任意 provider 阻塞读取取消或所有导入导出业务验收完成。已复用的有界读取不等于有超时或可中断的 provider。原有手工完整/文本选择仍由对应 parser 严格校验，选错不被当成成功。

并行 backup-migration 负责旧格式 codec/service/迁移界面；本次不给旧格式增加猜测 parser 或自动覆盖路径。其父接线点仍是 Main 的 `offerOpenedBackup`（ready 后）/`confirmBackupImport`（最终人工确认）及 Controller 既有 `importUserBackup` 接缝。若并行任务提供新探测/预览路由，父代理应复用其明确接口连接 `showOpenedBackup`，而不是绕过等待身份、二次确认或重新实作 codec。
