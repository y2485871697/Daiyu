---
name: daiyu-new
description: 用于独立代鱼项目 y2485871697/Daiyu 开发、检查和交付；main唯一分支。电脑性能优先单测/Lint，GitHub负责APK及模拟器；默认E:\daiyu，必要目录外写入须逐项汇报。协作入口、准确快照、报告与离线云端复用以仓库持久脚本为准。
---
# daiyu new

## 持久入口与授权

- 重构边界：只替换经来源核验的 Eta 原作者实现和必要耦合；第三方及后期代鱼自研代码/功能/资源/测试全部按保留复用推进，包括我们自己的UI样式、布局、导航、交互与性能优化；不默认重新实现或整页重做。按提交历史、共同基线、最新上游、跨路径复制和实际依赖核验；新增文件或 fork 作者不是充分证明。混合文件保留自研和第三方，只替换确认的Eta原作者片段及必要耦合。第三方即使经Eta引入也保留，来源/版本/许可/署名另列；来源未核明先登记待核并保留原件，不默认删除/重写，不冒称自研。必要适配仅限替代实现的接口/包名/配置接线，不扩展为重写保留部分。范围与精确 revision 见 `docs/来源清单.md`。

- 仓库 `y2485871697/Daiyu`，当前已授权工作区 `/workspace/代鱼`，仅main，不创建分支/工作树分支、不强推、不改旧Eta。源码和协作脚本、此Skill均提交仓库，不依赖会话虚拟Skill目录；新会话优先读取仓库的 `skills/daiyu-new/SKILL.md`。
- 电脑默认 `E:\daiyu`，优先复用便携JDK/SDK、缓存、parallel/build-cache/daemon及合理CPU workers；允许经审阅检查，没有系统沙箱不阻止执行。项目工具确需根外写入已获授权，最小范围并记录具体路径、工具、原因、操作、结果、证据及保留/清理状态，向用户汇报。无关个人数据、破坏性覆盖、认证/安全/全局配置、管理员权限及常驻服务不在此例外内。配置不是OS级写入审计，空列表不代表全系统无外写。
- SSH主机、端口、账号、密码仅主代理按需读取 `/workspace/ssh账户密码`，内存解析不执行、不输出、不复制/写死。严格核验已确认known_hosts的准确目标键；认证/网络失败不重复探测、不等待电脑，直接检查云端已有覆盖并补缺项。
- 电脑仅领域单测/增量编译/Lint，不assemble/package APK、不运行模拟器。GitHub完整APK和双API回归门禁不因电脑报告跳过。用户批准AGP9.5.0-alpha09单项预览例外，其他工具链稳定固定；检查用warning-mode=fail，不屏蔽弃用提示。

## 工作分配与协作链路

| 执行方 | 职责 |
|---|---|
| 用户电脑 | 性能优先领域单测/Lint；每项完成立即回收，复用根内缓存 |
| GitHub Linux | APK及双API回归；电脑不可用时只补缺项 |
| GitHub Windows | Windows兼容检查；不是用户电脑 |
| 主代理 | SSH、集成、摘要/真实退出码核验、去重、验收与交付 |
| 子代理 | 同轮并行独立阅读/审查，不接收凭据、不建分支 |

1. 使用 `uv run --locked --script scripts/cooperation/cooperate.py run --state <仓库外持久证据目录> --known-hosts <已核验信任文件>`。电脑run入口用PEP 723与cooperate.py.lock准备独立的固定依赖环境，不用裸python3或旧venv；cloud/offline及证据测试仍只需标准库，纯离线不通过uv；所选Linux环境执行，不在Android下载工具。确认main工作树干净、远端SHA；git archive不可变提交、逐文件摘要，电脑二次核验后才执行入口。
2. 每个单测/Lint记录CHECK_ID、attempt、SHA、配置指纹、精确命令/范围、缓存基线、环境、真实退出码、日志/报告摘要。逐项回收核验，失败保留并修复，不当缺项抹去。未知远端执行不重启、不乱杀进程。
3. 电脑掉线仅补缺项。`mode=checks`的`check_scope=unit|lint|all`不打包；唯一check_id写入run-name，dispatch前持久记录，结果未知只查询不重发。准确SHA已有排队/运行/成功则绑定复用，不触发APK。远端main变更则停止派发，不猜revision。
4. `cooperate.py offline --state <目录>`完全不联网、不SSH、不dispatch；重新核对SHA、配置、attempt、终态、文件摘要和XML，不只读passed标记。新SHA不得复用旧报告；离线缺项退出3，证据错误不冒充通过。
5. 受控断线验收仅用`--interrupt-after unit`：电脑单测回收后关闭SSH，不派电脑Lint；云端只补Lint。这是任务边界断线，不宣称测过任意任务中间强杀。
6. 完成必要源码修改和提交前检查后立即推origin main，不等电脑。仅协作链路维护跑checks-only，不触发APK；APK目标真需变更才按下面门禁。

## APK交付与调度

- 工作流383377276/代鱼构建，APK artifact代鱼开发包；执行前核对真实YAML、main、所有未结束run。先复用准确目标，不因电脑掉线或缺检查cancel/rerun APK。
- 真需不同APK目标：旧构建实际运行>540秒等完成；<=540秒取消准确run并确认终态后重查。来源明确本任务失败默认读日志修复、验证、推送后调度；非自身/来源不明失败先ask_user。不对未知dispatch重发。
- 保留OWN_SHA、最新REQUIRED_SHA、实际BUILD_SHA及run/attempt；替代run必须包含OWN与REQUIRED，核验后跟进，不包含则停止。不以同actor/SHA推断自己发起，不以build成功代替所有必需smoke成功。
- 用户要求交付时持续跟进至全部门禁通过，准确run/attempt artifact下载校验至`/storage/emulated/0/Download/代鱼/代鱼-<版本>-<短SHA>.apk`，不安装、不从电脑取包，不覆盖不同内容同名包。签名目前debug开发签名，不冒称正式版；更新说明记录实际证据和剩余项，仅清理本任务副本。

详见 [电脑协作](references/pc-workflow.md)。
