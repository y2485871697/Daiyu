# 20号：验收工具与待执行证据交接

本包只改验收脚本、合成工具测试和对应文档，不改应用生产实现、公共清单、Manifest、构建或CI。以 `ae6e834a554ee85291e00b008e53d227d2ce0a1c` 为共同基线，由1号统一合流；20号不自行推送、触发CI、下载安装APK。代码与本文同次交付，精确提交从Git历史定位。

## 六路产物

| 入口 | 交付内容 | 结论边界 |
| --- | --- | --- |
| `scripts/acceptance/coverage.py` | 全81项AC的编号owner、最低证据种类和未满足项 | 81项全部not_run；AC-062→06已由1号确认责任；19号是provenance，不是生产owner |
| `scripts/acceptance/recovery_suite.py` | 历史恢复证据结构转换、JUnit/仪器帧/Python原文和身份/摘要校验 | 所有支持断言均partial；AC-003缺可信编译来源，不能获得业务passed |
| `scripts/acceptance/cloud_report.py` | 离线双API报告快照、原文、run/attempt/job和SHA一致性 | 固定API26与37.2；内部一致性不证明可信采集或业务完成 |
| `scripts/acceptance/performance.py` | AC-072/073物理设备样本、冻结基线与p95/阈值比较 | 不采样、不认证物理设备；没有真实release样本和冻结基准时仍阻塞 |
| `scripts/acceptance/scenarios.py` | 16个强杀/断网/权限/跨App/OEM/音频/性能等场景计划 | 仅覆盖38个去重AC，另43项未规划；默认12 blocked、4 not_run，不执行场景 |
| `scripts/acceptance/source_gate.py` | 明确独立仓库根的HEAD与dirty只读门禁 | 不是APK provenance；拒绝linked worktree和commondir布局；非原子观察，不支持并发写者 |

各入口以 `--help` 和对应文档为参数契约。既有 `evidence.py`、`template.py` 原样复用，不另建公共分类器、不降低证据floor。计划、工具单测和输入摘要都不能替代业务证据。

## 已修复的审查问题

- coverage：AC-075仍路由19号，但item/汇总角色改为provenance、production_owner为空；补schema_version=1.0负测。JSON文档收窄为拒绝NaN/Infinity字面量，不宣称拒绝所有指数溢出。
- recovery：1号接收时补齐与cloud一致的第0列及单行唯一协议校验，拒绝空格/tab/日志前缀隐藏失败；新增parser、完整摘要/ZIP重绑collect与convert CLI负测。拒绝未知JUnit outcome及矛盾计数；先严格UTF-8解码再拒绝DTD/ENTITY，堵住UTF-16/32绕过；严格绑定receipt job身份；domain argv限制为有限语法；撤销历史AC-003 full资格。
- cloud：协议标记必须位于第0列且单行唯一，前导空格/tab/日志前缀/内嵌协议不再被当普通文本忽略；完整摘要重绑的负测贯通verify和真实文件CLI，统一失败JSON边界。
- source gate：固定core.ignoreCase=false防大小写别名漏检；调用Git前拒绝任何commondir，包括悬空symlink。commondir/filter执行未被实证，不将其描述为已发生执行漏洞；checkStat观察不冒充已确认缺陷。

## 验证与证据保存

主代理在授权Debian环境执行工具专项测试、直接CLI检查及最终集成测试；不编译APK、不访问网络或设备业务入口。基线65项工具测试的报告保留，不改写成新SHA证据。最终检查须记录完整CHECK_SHA、命令、环境、真实退出码、日志及散列。

任务外交接目录：`/workspace/daiyu-coordination/task-20/`。其中 `reports/` 保存各专项、独立负测、最终集成日志和准确SHA；`dispatch.json` 保存任务、审查与候选/集成关系；`handoff.md`/`handoff.json` 保存最终本地交付状态。历史报告不覆盖，后续源码变更不能给旧报告重绑SHA。

1号合流后应针对实际合流SHA按既定CI契约补齐必要检查，API26和37.2均保留。这里未改变公共项目清单的业务状态，也未收集新的真实业务通过证据。

## 仍须1号安排的业务工作

- AC-062→06生产修复责任已由1号确认（分工v1.1）；仍须落实MCP连接/传输实际文件归属、场景触发入口及最小身份/UNKNOWN/停用契约，不能据责任确认计业务完成。
- 可信构建来源、准确双API运行报告，以及强杀/重启/跨App grant/OEM/权限等真实环境采集。
- AC-072/073的release物理设备数据集、冻结性能基准和原始样本；20号负责组织测量，不代替生产修复owner。
- 所有81项均须取得对应类别的真实证据后逐项核销；本包工具通过不表示R15/R16或完整业务验收完成。
