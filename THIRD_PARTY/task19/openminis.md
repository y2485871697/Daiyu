# T01 / T02：OpenMinis独立来源

核验基线ae6e834；旧冻结发布f001fb82。来源审查子任务f5c24fd2已回收，以下分既有声明、上游核对与未闭合范围，不作为生产已迁入声明。

## T01浏览器

旧冻结 `third_party/openminis-browser/NOTICE.md` 固定 `OpenMinis/OpenMinis@4ef29002e88db1e20e462ec2ff46916e8a7dcb45`，原声明GPL version3；抽样ported文件头保留SPDX GPL-3.0-only。不以GPL模板通用条文重新选择only/or-later，也不把FSF许可证文档版权当OpenMinis源码作者。

固定上游核心目录：`src/android/app/src/main/java/com/openminis/app/browser/`，已核BrowserAction、BrowserActionInput、BrowserActionResult、BrowserHistory、BrowserTabPool、BrowserUseJS、BrowserUseManager、InterceptedDialogQueue同名文件存在。UI目录 `src/android/app/src/main/java/com/openminis/app/ui/browser/` 已核BrowserDownloadsSheet、BrowserExternalSchemeHandler、BrowserHistorySheet、BrowserSettingsSheet、BrowserSheet、BrowserWebView。路径存在不是全部逐段比较通过。

源：https://github.com/OpenMinis/OpenMinis/tree/4ef29002e88db1e20e462ec2ff46916e8a7dcb45/src/android/app/src/main/java/com/openminis/app/browser
原LICENSE见upstream-licenses/openminis-4ef2900-LICENSE及sources.json，父已从固定raw地址取原件并算SHA256；与旧冻结manifest记载的35149B、3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986相符。

旧ported树统计23个Kotlin文件；与10号新固定来源包的“20个Kotlin原件＋3份抽取资源XML”不是同一集合，不能混用。

10号提供的manifest已快照为本包`openminis-source-snapshot.json`，固定上游revision同上，归档摘要声明为`09bee336e7effeafb7908f5542533c9dd9dcf47c2efbb5656cd96a3919bc4f10`。20个Kotlin逐项列上游/本地路径、长度、摘要、transform:none，原包名保留；3份XML按source_nodes抽取每种语言54键并重新序列化，非整份未修改原件：values/strings.xml→values，values-zh/strings.xml→values-b+zh+Hans，values-zh-rTW/strings.xml→values-b+zh+Hant。

19号在10号HEAD `f2d2d43682e6db2a0f716b5da9c64ed2b8cee453`观察时，独立逐项读取实际23文件重算长度/SHA256，均与该manifest一致；组件LICENSE亦与本包固定raw副本字节相同。证据`/workspace/daiyu-coordination/task-19/browser-23-check.json`。这次检查不是重新下载/验证上游归档、逐段源文比较或资源抽取算法证明。manifest原status是10号当时暂存说明，不视为动态生产状态。

这批固定原件映射缺口已收窄；旧候选ChildBrowserSupport、BrowserHistoryStep、ChildBrowserDownloads、logging/AppLogger及新宿主/adapter各自衍生关系、最终版权/NOTICE与集成清单仍待按实际文件核验。旧23 Kotlin集合不因新包23条数量相同就全部闭合。保留版权头、LICENSE和改动说明；本包补充的来源通知见`OpenMinis-NOTICE.md`，不伪称上游原NOTICE。账号/Cookie可能共享，标签隔离不等于账户隔离。

原核验基线ae6e834未迁入浏览器生产主体；增量manifest原status仅是10号当时的暂存说明，不是本轮独立生产接入检查。旧PORT_STATUS source integrated与旧CI不能转授新项目。工具注册、下载/分享provider、所有权/取消等业务由10号验收。本包不复制旧实现。

## T02 Skill

用户已确认 SkillCompatibilityChecker 及其保留测试为 fork 后原创，不归属于 OpenMinis。保留算法与生产接线；外部 Skill 和实际第三方依赖各按其许可。见 `docs/skills-compatibility-source.md`。
