# OpenMinis — 本包来源与变更告知

本文件由代鱼项目维护者编写，不是从上游取得的原始NOTICE，不替代原有版权头、许可及其他适用告知。

来源：OpenMinis/OpenMinis，固定修订 `4ef29002e88db1e20e462ec2ff46916e8a7dcb45`。
仓库：https://github.com/OpenMinis/OpenMinis

`openminis-source-snapshot.json`记录10号提供的20个Kotlin文件及3份抽取资源。Kotlin标为transform:none，保留原包名；资源从默认、简体与繁体strings.xml逐节点选择被引用的R.string并重新序列化，本地文件名和语言限定符已调整。路径、source_nodes和摘要见快照；本包不复制该23文件的源码。本次19号对10号实际23文件的本地摘要重算见`openminis.md`，不是重新认证上游归档。

原许可文本保存在`upstream-licenses/openminis-4ef2900-LICENSE`。旧冻结NOTICE声明GPL version3，抽样ported文件头标GPL-3.0-only；本告知不重新选择only/or-later，不作额外授权，也不把许可证文本本身版权视为OpenMinis源码作者。迁入内容原有版权/许可头和其它适用通知须原样保留；如上游另有适用NOTICE，不得以本文件替代。

本次固定来源20 Kotlin＋3 XML不同于旧ported树23 Kotlin。快照不覆盖宿主适配、工具注册、下载/分享provider、日志封装、其它新增/衍生实现或T02 Skill，未列入也不意味着已证独立自研。后续实际变更须注明范围及日期；本包仅记录来源与变换，不证明生产功能或全部分发条件已完成。

## 浏览器 UI 迁入补充

本批保留 OpenMinis 第三方浏览器的本地衍生 UI（本地适配快照 `3196270e361c82cc1f399218d914ec49de0d83cc`，不是上游修订）至 `browser/ported/`：BrowserSheet、BrowserSettingsSheet、BrowserHistorySheet、BrowserDownloadsSheet、StandardChatSheet、Theme、ChatColors、MinisButton 与 BringIntoViewOnFocus。保留 GPL-3.0-only 文件头及原布局/文案，调整包名、资源命名空间、统一触感与弹窗动效适配；移除旧全局池依赖，改接当前任务 lease 或人工会话，新增 UA/viewport/下载/历史控制及安全生命周期。三语言 `openminis_browser.xml` 对应迁入页面引用。该补充不改原 20 文件快照摘要，也不把本地衍生 UI 误标为未修改上游文件。

本次原样纠偏移除迁移时自加的任务说明、任务按钮与主面板诊断区。`browser/ui/BrowserUiPool.kt` 的裸主机识别沿用上述本地适配快照的 `agent/browser/BrowserNavigation.kt::looksLikeUrl`；下载打开恢复原版应用选择器、扩展名 MIME 与只记日志的失败处理，文件仍由 Android DownloadManager 的 content URI 分享，不搬入旧私有缓存或新增存储权限。固定原始 UI 与三语言资源摘要见 `tests/fixtures/browser_original_ui.json`。

2026-10-23：本地衍生 `BrowserHistorySheet.kt` 改为从 Compose 可观察的 `LocalLocale.current.platformLocale` 读取地区设置，保留 `HH:mm` 格式、原时间戳、字号、颜色与布局；未修改固定上游快照。

浏览器人工浏览修复：分离真实任务占用与失效／UNKNOWN展示；人工页面拒绝不允许的URL和子资源时仍拦截，但不将整张WebView按模型操作UNKNOWN退役。人工历史写入移至完成回调并隔离存储错误，取消后的旧start和人工旧finish不重新武装模型。三语言只将`om_browser_minis_browsing`显示品牌改为Daiyu／代鱼／代魚；原固定资源摘要保留，测试仅批准该键精确品牌替换，不修改上游署名或技术标识。

2026-10-31：用户确认浏览器为独立第三方 OpenMinis，不属于历史宿主项目原创；本地中转快照不作为其原创来源。上游固定快照、GPL 文本、文件头、既有本地适配记录及摘要保持，不把本地适配快照冒称上游原件。
