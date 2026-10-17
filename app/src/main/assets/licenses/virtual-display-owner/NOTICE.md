# VirtualDisplay owner 来源与许可

Required Notice: Copyright © 2026 蛮吉 (Mangi-11).

## 原始输入（只读）

- 用户提供的 `build/agent-input/backend`。
- `SOURCE.json` 记录输入归档 SHA-256：`6d7b1fe8f58909ef64f4687f8f2bd46e32082c5febd65698e9bdac1ee60e61ab`。
- `PROVENANCE.json` 记录 Eta 基线提交 `2a7e7ab92b98da5a3711611017021441e7a8053c`。这里只作为用户输入的来源记录，不声称本次在线核验过远端或输入中的每个文件都未经后续修改。
- 32 个生产 Java 的输入路径：`sources/eta/app/src/main/java/vd/runtime/*.java`。
- 19 个既有 Java 测试的输入路径：`sources/eta/app/src/test/java/vd/runtime/*.java`。
- Anchor 的输入路径：`sources/eta/app/src/main/kotlin/io/github/mangi/eta/agent/device/VirtualDisplayAnchorActivity.kt`。

## 本项目中的位置和修改

- Java 实现：`app/src/main/java/app/daiyu/android/vd/runtime/`。
- 既有测试及新增回归：`app/src/test/java/app/daiyu/android/vd/runtime/`。
- Anchor：`app/src/main/kotlin/app/daiyu/android/vd/VirtualDisplayAnchorActivity.kt`。
- 修改限于 Java 包/测试引用、宿主组件和 URI 常量、API 26 的显示读取、input 不确定性锁及其公开入口回归、Main usage 入口类名。本轮增加 Owner 包内合成依赖/JSON body 构造接缝，覆盖成功 command 后 JSON 构造失败与公开 input/launch/status 的 no-replay 证据；不增加公共授权入口。handoff/provenance/release 算法继续使用原实现。
- 旧宿主包 `io.github.mangi.eta` 只在本来源说明中记录，不作为生产依赖、Manifest 组件或测试运行时包名。
- 未引入旧应用框架、RootSu、旧客户端或预编译 owner dex；输入目录未被修改或再次打包。

## 许可边界

原始 `sources/eta/LICENSE` 的 **PolyForm Noncommercial License 1.0.0** 原文（含 Required Notice）保留在本目录 [LICENSE](LICENSE)，原署名不变。

本项目用户已选择并确认 `copyright_owner`：以该部分源码版权拥有者身份授权本项目迁入、修改和集成。这是已确认的授权事实，不是等待再次询问版权拥有者的事项；本轮按该授权继续处理 R08 stage1 覆盖补齐。该授权记录不删除原始许可全文或 Required Notice，也不将原始源码标成 MIT。不能仅凭仓库根目录的 AGPL-3.0 说明，就推断所有迁入内容或第三方依赖已获得 AGPL 重新授权。

用户输入的 `LICENSE-NOTES.md` 还引用了 `sources/eta/docs/THIRD_PARTY_NOTICES.md`，但该文件不在本次可读的 57 文件输入中；本迁移不伪造或声称已取得该缺失文件。未迁入其中列举的网关、旧模块或预编译产物，其许可证不替代 owner 的原许可。

分发包含此部分的软件时保留并随附该原许可及 Required Notice。版权拥有者对本项目的上述授权已确认，但不自动覆盖其他第三方权利，也不是本文对 PolyForm Noncommercial 与项目总体 AGPL-3.0 组合分发/再授权兼容性的法律结论。正式发布或商业分发仍需按实际授权范围与第三方材料审查，不能把本阶段迁入或覆盖补齐本身当作兼容性认证。

本次只是 R08 第一阶段迁入，不是完整 R08 验收。宿主接线与未执行的验证见 [集成说明](../../integration/vd-owner-stage1.md)。
