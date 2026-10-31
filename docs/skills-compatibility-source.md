# Skills 静态兼容检查：原创归属与生产接线

用户明确确认本检查器为其 fork 后原创；历史存储包名不构成第三方原创归属。保留 `SkillCompatibilityChecker` 的字符串检测算法、标记数组、分支优先级、iOS 词边界及 Android/Termux 例外，不因来源说明纠正而重写实现。

`SkillCompatibilityPolicy` 将名称、描述、compatibility、metadata 及正文交给检查器；安装、列表、技能读取与 run 快照沿用同一判定。保留完整 SKILL.md 字节和现有静态测试；不执行脚本、不新增工具名黑名单。

该判定是已知平台标记的静态筛选，不代表实际执行结果。真实第三方 Skill 的作者、许可证及其独立来源不因此改变。功能与回归证据统一见 `docs/重构清单.md`。
