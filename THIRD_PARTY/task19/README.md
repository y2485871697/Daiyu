# 19号第三方来源证据包

> 本包原基线记录保留为历史。输入 af18af3 的当前随包组件、真实 runtime 声明、仓内许可原件/副本和待解析边界见 `THIRD_PARTY/license-bundle/source-index.md`；核验入口为 `scripts/verify_license_bundle.py`。早期“只有 Wrapper”“没有图片/JNI”“浏览器尚未编译”不适用于当前树。既有 Sherpa 完整许可保留，不在本次重新审理。未执行实际 APK 检查不宣称 APK 已核。

本包补充 `docs/来源清单.md`，功能状态仍只由 `docs/重构清单.md` 记录。工作基线 `ae6e834a554ee85291e00b008e53d227d2ce0a1c`；这是19号独立来源交付，不代表所有第三方计划已迁入或整个应用发布审查已完成。

## 实际制品与计划保留分开

`tracked-artifacts.json` 对本基线 Git 跟踪的非文本制品与现存许可/NOTICE逐一记录路径、字节数、SHA-256；当前非文本制品只有构建用 Gradle Wrapper JAR。这里没有 APK、JNI、语音模型、桌宠图片或移植浏览器的制品绑定，也没有解析构建后的完整传递依赖树。未来迁入制品必须由所属功能编号提供真实路径与摘要，再追加证据，不能把本包理解成未来文件自动获准。

`verify_tracked_artifacts.py` 仅核对本清单所列字节，退出0不表示来源齐全、功能可用或满足全部发布条件。它不联网、不读用户数据、不下载或构建APK。

## 分项证据

- `openminis.md`：T01浏览器与T02 Skill独立来源。
- `voice.md`：T03现有RikkaHub改编与拟保留TTS、T07语音原生/模型。
- `ui-assets.md`：T04图片、T05图标；用户自研图标/UI不重审原创。
- `compression.md`：T06参考与自研保留边界。
- `native-runtime.md`：T07 PRoot/talloc/shmem及原生制品。
- `build-dependencies.md`：T08当前声明依赖与CI action精确固定源。

## 迁入时的最小交接

所属功能编号提交：组件/用途，实际目标文件，上游repo+完整commit或已验证发行版本，逐文件原path，LICENSE与适用NOTICE原文，原文件及目标制品SHA-256，宿主改动范围；原生/模型另列ABI、构建脚本/补丁及对应源码位置。未知字段明确为缺项；不按项目名、作者名或继承路径猜归属，不删除用户自研或合法第三方。

本包保留既有RikkaHub NOTICE范围，不把计划TTS算作已复制的RikkaHub完整模块；不借浏览器NOTICE覆盖Skill，不借Sherpa许可覆盖具体音色/模型权重，不将平台公开API调用算作复制平台实现。

## 固定上游许可证原件

`upstream-licenses/sources.json`记录固定提交raw地址、字节数与摘要。RikkaHub c94237e的LICENSE与既有副本字节相同；OpenMinis 4ef2900的LICENSE原件亦保全。这里只收录原许可，不据GPL模板中的“or later”示例文字推断实际代码授权为“-or-later”，也不据项目根许可证替代每份资源的适用通知。本包不是源项目完整源码副本，其他编号迁入代码时仍应保留对应版权头、NOTICE与改动说明。

`ci-actions.json`另逐项固定8个workflow uses入口及其原仓库许可证路径/字节摘要，含共享gradle/actions仓库的两个subaction。固定根许可证副本不代表递归审核该action打包的所有依赖。

10号固定来源快照见`openminis-source-snapshot.json`；本包自有来源与变更告知见`OpenMinis-NOTICE.md`。20 Kotlin与3份抽取XML已对10号实际本地文件重算一致，详见openminis.md；快照不替代生产集成验收。
