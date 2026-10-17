# P21 integrity fix v10：SQLite 坏行不能被过滤或截断成合法统计

## 范围与边界

本次任务指定基线为 `99a348c`。仅调整 `SQLitePreparationRuntime.kt`、`SQLiteRuntimeStore.kt`、`RecordedUsageReaderDatabaseTest.kt`，新增专属 `P21IntegrityDatabaseTest.kt` 与本文。没有修改 `SQLiteAdditionalUsageStore`、`RecordedUsageReader.kt`、Controller、Main、数据库 schema、唯一清单或手动 usage 功能；没有放宽 checkpoint codec。

本文记录源码检查和新增回归 fixture，不是构建/设备测试通过证明。实现环境只提供工作树文件工具，未执行 Shell、Gradle、Android instrumentation、Git 提交或推送；提交、基线/工作树状态及执行结果由主代理核验。

## 已核实的问题与最小修复

1. **HANDED 非法 presence 在严格验证前消失。**
   - 原过滤条件 `(p.state!='HANDED' OR p.has_usage=0)` 会排除 `HANDED + REAL 0.5` 和 `HANDED + INTEGER 2`，也排除其他非 0/1 的 INTEGER。之后的逐行校验看不到这些行，覆盖率可能错误地表现为完整。
   - 新过滤条件只在 `state='HANDED'` 且 `typeof(has_usage)='integer'` 且 `has_usage=1` 时排除该行。排除依据是合法的已报告 presence 位，而非“不是 0”。COUNT 和有界选择仍使用同一个 filter；非法 presence 进入原有逐行校验，计入 unreadable。
   - 合法 `HANDED + INTEGER 0` 仍是缺失 SUMMARY 的覆盖证据，不产生零值或 all-null 伪快照。合法 `HANDED + INTEGER 1` 的 SUMMARY 已在父 checkpoint 中，不重复计数，也不挤占 preparation 预算。

2. **presence 被 `getInt` 截断。**
   - `4294967296` / `4294967297` 在 32 位读取时可分别变成 0 / 1。
   - 保留 SQLite `FIELD_TYPE_INTEGER` 校验，然后只读取一次 `getLong`，在 Long 域验证 `0L..1L`；构建 Row 时复用同一个值。不通过转换、修正或造 0 来接受坏值。

3. **checkpoint SQL revision 的 REAL 被归一化。**
   - 原 `SQLiteRuntimeStore.read` 在对比 payload 前直接 `getLong(3)`，使 SQL `REAL 1.5` 看似与 payload revision 1 相等。
   - 在任何 revision `getLong` 之前要求 `FIELD_TYPE_INTEGER`。原 payload 解码、身份/版本一致性检查均保持不变；合法 INTEGER revision 不受影响。

4. **v3 reader fixture 不是合法 v3。**
   - 当前 v6 encoder 输出包含 `usage_requests`。只改 version 并删除 `usage` 仍会被严格 codec 拒绝。
   - reader 测试同时删除 `usage`、`usage_requests`，与 `backup/RunCheckpointCodecTest.writesV6AndReadsLegacyOnlyWithTheirExactFields` 的逐版本降级一致。保留原有 legacy/missing/corrupt/good 区分断言，不修改 codec。

## 真实 SQLite 回归 fixture

专属文件：`app/src/androidTest/kotlin/app/daiyu/android/storage/P21IntegrityDatabaseTest.kt`。

- `testHandedMalformedPresenceIsUnreadableNotFilteredOrFabricated`：经真实 summary admission、seal、checkpoint transfer 得到 HANDED，然后分别写入 REAL 0.5、INTEGER 2、4294967296、4294967297。读取前核实 Cursor 存储类及值；断言每个坏 preparation 被检查且计为 unreadable，`scanComplete=false`，独立 MAIN 的 10/20 token 报告仍存在，不多出 SUMMARY 快照。
- `testTerminalPresenceUsesLongDomainWithoutIntWraparound`：在真实 TERMINAL preparation 上使用同一坏值矩阵。断言没有 wraparound 成 missing 或有效 report；无有效快照时 token 保持 null，而不是 0。
- `testLegalHandedPresencePreservesMissingCoverageAndAvoidsDoubleCountingUnderBudget`：合法 INTEGER 0 的缺失证据仍存在，预算不足时显示未检查；合法 INTEGER 1 在父 checkpoint 包含 SUMMARY 后不会重复读取，即使 preparation 剩余 record 预算为 0，也不会错误标记未完成。
- `testRealCheckpointRevisionCannotNormalizeToPayloadRevisionAndGoodUsageSurvives`：SQL revision 实际存为 REAL 1.5，payload 保持 revision 1；直接 snapshot read 拒绝该行，统计将其计入 unreadable，另一合法 INTEGER revision checkpoint 的 Usage 保留。
- `testSQLiteReadFailureIsNotSwallowedAsAnUnreadablePreparation`：临时重命名真实 preparation 表，引发真实 SQLite 查询异常，断言异常传播，并在 finally 恢复表名。仅限隔离测试数据库，不是生产 schema 变更。

现有 `RecordedUsageReaderDatabaseTest.testMissingLegacyAndCorruptRowsStayDistinctWhileGoodUsageSurvives` 继续验证真实合法 v3 的无 Usage 与坏 checkpoint 分开计数。

## 异常与未验证项

生产异常处理边界未改变：`SQLiteException` 与 `CancellationException` 仍明确重抛；仅原来允许的坏行验证异常被隔离。本次没有增加 catch-all、重试网络、改写损坏数据或成功空统计的回退。新增 SQLite 失败用例验证查询错误出口；没有通过伪造 SQLite/Cancellation 对象宣称验证取消注入。

尚未编译或执行上述 instrumentation。主代理需核验新增文件纳入净改动，并运行这两个测试类；本文件不声称通过测试、已提交，或独立核实了 Git 基线。其它现存旧迁移测试未扩改。
